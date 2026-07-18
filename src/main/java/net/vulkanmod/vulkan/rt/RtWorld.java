package net.vulkanmod.vulkan.rt;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteOrder;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * M8.2/8.3 — менеджер BVH мира.
 *   * секции копятся в очередь и раз в кадр БАТЧЕМ строятся в BLAS (один
 *     syncUploads + один submit + один waitIdle на весь батч, лимит на кадр);
 *   * раз в кадр (троттл) собирается TLAS из всех живых BLAS с мировым сдвигом.
 *
 * Асинхронность (батч) убрала главный тормоз — синк/waitIdle на КАЖДУЮ секцию.
 * ⚠️ Всё RT-железо крутится на render-потоке (uploadSections→DrawBuffers.upload
 * и endFrame→tick), поэтому гонок за compute-очередь нет.
 */
public class RtWorld {
    public static RtWorld INSTANCE;
    // M8.146: индекс TLAS-инстанса ОСАДКОВ (-1 = осадков в кадре нет). Шейдер опознаёт по нему
    // попадание в каплю: у осадков одна текстура на кадр, поэтому per-quad quadTex им не нужен.
    public static volatile int weatherInstanceIdx = -1;

    /** Максимум BLAS, собираемых за один кадр (размазываем нагрузку прогрузки). */
    // ⚠️ КАЖДЫЙ суб-батч = отдельный submit + waitIdle, то есть ПОЛНАЯ ОСТАНОВКА рендер-потока.
    // При 64 секциях на батч прогрузка чанков давала десятки таких остановок за кадр — это и
    // чувствовалось как фризы (на Windows драйвер на синхронизациях дороже, потому там заметнее).
    // Крупный батч не делает работу тяжелее: тех же секций столько же, но синков в 4 раза меньше.
    private static final int BUILD_CAP = 256;

    // ⚠️ СВОЙ СТЕК. Батч из 256 сборок — это 256 геометрий + 256 build-info + диапазоны: под сотню
    // килобайт native-структур. Стандартный стек LWJGL — 64 КБ, мы бы его переполнили.
    private static final MemoryStack BUILD_STACK = MemoryStack.create(8 << 20);

    public static void createInstance() {
        if (DeviceManager.rayTracingSupported) INSTANCE = new RtWorld();
    }

    private static boolean vanillaShadowsOff = false;

    /**
     * Раз в кадр (из Renderer.endFrame): собрать накопленные секционные BLAS (статика).
     * ⚠️ ASYNC: entity-BLAS + TLAS больше НЕ здесь — они пишутся в КАДРОВЫЙ буфер в
     * {@link #recordFrameRebuild} (композит), чтобы сущности/игрок обновлялись каждый
     * кадр без per-frame waitIdle. Здесь остаётся только прогрузка чанков.
     */
    public static void tick() {
        if (INSTANCE != null) {
            // M8.4: ванильные блоб-тени под мобами в RT не нужны (наши тени настоящие),
            // а их плоский меш попадал в сбор сущностей белыми кляксами на земле.
            if (!vanillaShadowsOff) {
                try {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc != null && mc.options != null) {
                        mc.options.entityShadows().set(false);
                        // ⚠️ СГЛАЖИВАНИЕ ТЕКСТУР — ВЫКЛ. Ванильная фильтрация (RGSS/анизотропия) мылит
                        // атлас, а Minecraft держится на жёстких текселях. Трассировка и так берёт
                        // атлас через texelFetch с мип-0, так что сглаживание ей ничего не даёт —
                        // только портит растровые пути. Держим NONE принудительно.
                        mc.options.textureFiltering().set(net.minecraft.client.TextureFilteringMethod.NONE);
                        vanillaShadowsOff = true;
                        Initializer.LOGGER.info("[RT] ванильные блоб-тени выключены (тени даёт трассировка); сглаживание текстур выключено");
                    }
                } catch (Throwable ignored) {}
            }
            net.vulkanmod.vulkan.rt.RtSnapshot.resetBobCapture();   // новая поза покачивания — в новом кадре
            INSTANCE.retireBuilds();   // подобрать то, что GPU уже достроил (без ожидания)
            INSTANCE.drainBuilds();
        }
        // RtProbe (диагностика M8.3, луч-в-секунду) отключён: он трассирует TLAS на
        // compute-очереди в tick(), а теперь TLAS собирается async в кадровом буфере и
        // на момент tick() ещё НЕ исполнен -> проба читала бы непостроенную структуру.
        // RtProbe.tick();
        RtSnapshot.tick();
    }

    /**
     * ASYNC (из DefaultMainPass.compositeRtBeforeGui, ВНЕ прохода, перед RT-компьютом):
     * зафиксировать меши сущностей кадра и записать entity-BLAS + TLAS в КАДРОВЫЙ буфер
     * (recordBuild, без waitIdle). Компьют читает свежий TLAS в том же сабмите — сущности
     * и игрок обновляются на частоте кадров (не троттл). Барьеры внутри упорядочивают
     * BLAS→TLAS→compute.
     */
    public static void recordFrameRebuild(VkCommandBuffer frameCmd) {
        if (INSTANCE != null) INSTANCE.recordFrameRebuildImpl(frameCmd);
    }

    /** Одна секция: её BLAS + СВОЯ копия вершин (UV/color для шейдера) + мировое смещение. */
    private static final class Section {
        AccelStruct blas;
        AccelStruct.RawBuffer ownedVtx;   // наша копия вершин секции; из неё же строится BLAS
        long vtxAddr;                      // device address ownedVtx (для таблицы инстансов)
        int wx, wy, wz;
        boolean water;                     // TRANSLUCENT: вода/стекло — шейдер даёт Френель+отражение
    }

    /** Отложенный запрос на сборку BLAS (копится в кадре, строится батчем). */
    /**
     * Сборка, ОТПРАВЛЕННАЯ НА GPU, но ещё не завершённая. Раньше мы ждали её тут же (waitIdle, потом
     * fence) — рендер-поток стоял, и при прогрузке чанков это чувствовалось как рывок. Теперь кадр
     * не ждёт: секции регистрируются, когда GPU реально закончил (проверяем fence без блокировки).
     * ⚠️ Ресурсы (scratch, cmd) держим здесь до сигнала — освободить их раньше = писать в память,
     * которую GPU ещё читает.
     */
    private static final class InFlightBuild {
        net.vulkanmod.vulkan.queue.CommandPool.CommandBuffer cmd;
        long[] keys;
        PendingBuild[] items;
        AccelStruct[] blas;
        AccelStruct.RawBuffer[] owned;
        java.util.List<AccelStruct.RawBuffer> scratches;
    }
    private final java.util.ArrayDeque<InFlightBuild> inFlight = new java.util.ArrayDeque<>();

    private static final class PendingBuild {
        int wx, wy, wz;
        long bufferId, byteOffset;
        int vertexCount, vertexSize;
        boolean opaque;   // SOLID=true; CUTOUT=false -> альфа-тест в шейдере (ажурная трава/листва)
        boolean water;    // TRANSLUCENT
    }

    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    // Очередь на сборку; ключ = (секция, renderType). Коалесинг: повторная заливка
    // секции заменяет прежний запрос (строим только актуальный).
    private final Long2ObjectLinkedOpenHashMap<PendingBuild> pending = new Long2ObjectLinkedOpenHashMap<>();

    // ⚠️ ASYNC: entity-BLAS + TLAS собираются В КАДРОВОМ командном буфере (recordBuild,
    // без waitIdle) и читаются компьютом в ТОМ ЖЕ сабмите. Значит старый TLAS/BLAS/копии
    // вершин/scratch/instance-буфер нельзя рушить сразу — их ещё читает кадр «в полёте».
    // КОЛЬЦО из RING поколений: на каждой пересборке освобождаем поколение RING кадров назад.
    // RING должен быть больше кадров «в полёте» (frameQueueSize 2..5) с запасом на разброс
    // момента deferXxx относительно cycleRing → 8.
    static final int RING = 8;
    private final ArrayList<AccelStruct>[] ringAS = newRing();
    private final ArrayList<AccelStruct.RawBuffer>[] ringBuf = newRing();
    private int ringGen = 0;
    @SuppressWarnings("unchecked")
    private static <T> ArrayList<T>[] newRing() {
        ArrayList<T>[] a = new ArrayList[RING];
        for (int i = 0; i < RING; i++) a[i] = new ArrayList<>();
        return a;
    }
    private void deferAS(AccelStruct a)  { if (a != null) ringAS[ringGen].add(a); }
    private void deferBuf(AccelStruct.RawBuffer b) { if (b != null) ringBuf[ringGen].add(b); }
    private void cycleRing() {   // сдвиг поколения + освобождение того, что было RING кадров назад
        ringGen = (ringGen + 1) % RING;
        for (AccelStruct a : ringAS[ringGen]) a.free();
        ringAS[ringGen].clear();
        for (AccelStruct.RawBuffer b : ringBuf[ringGen]) AccelStruct.releaseBuffer(b);
        ringBuf[ringGen].clear();
    }

    // Общая матрица декодирования позиций (SNORM -> блок-координаты секции).
    private AccelStruct.RawBuffer decodeTransform;
    // Общий quad->triangle индекс-буфер (UINT32): 0,1,2, 0,2,3 на квад.
    private AccelStruct.RawBuffer quadIndex;
    private int quadCapacity = 0;

    // --- TLAS мира ---
    private AccelStruct tlas;
    private AccelStruct.RawBuffer instanceBuffer;
    private int instanceCapacity = 0;
    // Таблица «инстанс(instanceCustomIndex) → device address вершин секции» — шейдер
    // по ней находит вершины попавшего треугольника и берёт UV/color (текстуры в RT).
    private AccelStruct.RawBuffer vtxAddrTable;
    private int vtxTableCapacity = 0;
    private boolean tlasDirty = false;
    // ⚠️ КЭШ ИНСТАНСОВ СЕКЦИЙ (M8.82). Профиль показал: каждый кадр я записывал 8375 инстансов по
    // одному через структурные сеттеры — 350 мкс, — хотя меняется из них РОВНО ОДИН (сущности: их
    // геометрия в координатах камеры). Держим готовые байты секций в своей памяти и раз в кадр
    // копируем одним куском; пересобираем их, только когда состав секций реально изменился.
    private boolean sectionsChanged = true;
    private long instStagePtr = 0; private int instStageCap = 0, instStageCount = 0;
    private long tableStagePtr = 0; private int tableStageCap = 0;

    // --- M8.4: динамический BLAS сущностей (пересоздаётся при каждой пересборке TLAS) ---
    // В instanceCustomIndex бит 23 (0x800000) = «сущность» (шейдер читает формат NEW_ENTITY).
    static final int ENTITY_FLAG = 0x800000;
    // В instanceCustomIndex бит 22 (0x400000) = «вода» (TRANSLUCENT-секция): шейдер даёт
    // Френель + отражение (луч-отражение) + преломление (видно дно, тинт поглощением).
    static final int WATER_FLAG = 0x400000;
    private AccelStruct entityBlas;
    // M8.146: ОСАДКИ — свой BLAS (строится из ТОГО ЖЕ буфера вершин, хвост со смещением).
    // У его инстанса маска 0x04, и вторичные лучи (тени/отражения/амбиент) идут маской 0x03 —
    // значит BVH дождя они не обходят ВООБЩЕ (аппаратно). Чинит и «капли в отражениях», и FPS.
    private AccelStruct weatherBlas;
    private int weatherQuadsN = 0;
    private long weatherVtxBase = 0;
    private AccelStruct.RawBuffer entityVtx;
    private AccelStruct.RawBuffer entityQuadTex;   // SSBO: квад -> слот текстуры сущности
    private net.vulkanmod.vulkan.texture.VulkanImage[] entityTexList = new net.vulkanmod.vulkan.texture.VulkanImage[0];
    private int entityQuads = 0;
    private double entCamX, entCamY, entCamZ;
    private int entityLogged = 0;

    private int builtCount = 0;
    private int tlasBuilds = 0;
    private long errorLogged = 0;

    private RtWorld() {
        initDecodeTransform();
        Initializer.LOGGER.info("[RT] RtWorld ready — batched BLAS + world TLAS (async build).");
    }

    private void initDecodeTransform() {
        final float s = 32767.0f / 2048.0f, t = 4.0f;
        float[] m = {s, 0, 0, t, 0, s, 0, t, 0, 0, s, t};   // 3x4 (row-major) — VkTransformMatrixKHR
        decodeTransform = AccelStruct.acquireBuffer(48,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            vkMapMemory(Vulkan.getVkDevice(), decodeTransform.memory, 0, 48, 0, p);
            MemoryUtil.memByteBuffer(p.get(0), 48).order(ByteOrder.nativeOrder()).asFloatBuffer().put(m);
            vkUnmapMemory(Vulkan.getVkDevice(), decodeTransform.memory);
        }
    }

    private void ensureQuadIndex(int quads) {
        if (quads <= quadCapacity) return;
        int cap = Math.max(quads, Math.max(4096, quadCapacity * 2));
        if (quadIndex != null) deferBuf(quadIndex);   // async: старый индекс ещё может читать кадр «в полёте»
        long bytes = (long) cap * 6 * 4;   // 6 индексов на квад, UINT32
        quadIndex = AccelStruct.acquireBuffer(bytes,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            vkMapMemory(Vulkan.getVkDevice(), quadIndex.memory, 0, bytes, 0, p);
            var ib = MemoryUtil.memByteBuffer(p.get(0), (int) bytes).order(ByteOrder.nativeOrder()).asIntBuffer();
            for (int q = 0; q < cap; q++) {
                int b = q * 4;
                ib.put(b).put(b + 1).put(b + 2).put(b).put(b + 2).put(b + 3);
            }
            vkUnmapMemory(Vulkan.getVkDevice(), quadIndex.memory);
        }
        quadCapacity = cap;
    }

    /**
     * Из DrawBuffers.upload (render-поток): поставить секцию в очередь на сборку BLAS.
     * НИКАКИХ GPU-операций тут — только запись намерения (коалесинг по ключу).
     */
    public void enqueueSection(long key, int wx, int wy, int wz,
                               long bufferId, long byteOffset, int vertexCount, int vertexSize,
                               boolean opaque, boolean water) {
        // Трассировка выключена в настройках — не строим BLAS вовсе (это чистая экономия CPU и
        // видеопамяти). При обратном включении настройка заставляет игру перестроить чанки, и
        // секции приедут сюда заново — см. Options, страница «Трассировка».
        if (!net.vulkanmod.Initializer.CONFIG.rtEnabled) return;
        if (vertexCount < 4) { pending.remove(key); removeSection(key); return; }
        PendingBuild pb = pending.get(key);
        if (pb == null) { pb = new PendingBuild(); pending.put(key, pb); }
        pb.wx = wx; pb.wy = wy; pb.wz = wz;
        pb.bufferId = bufferId; pb.byteOffset = byteOffset;
        pb.vertexCount = vertexCount; pb.vertexSize = vertexSize;
        pb.opaque = opaque; pb.water = water;
    }

    /**
     * Раз в кадр: собрать ВСЕ накопленные секции в BLAS — обязательно в ТОМ ЖЕ кадре,
     * где они поставлены в очередь. Иначе захваченный bufferId устареет: буфер чанка
     * может быть реаллоцирован/освобождён VulkanMod'ом за следующие кадры → use-after-free
     * → DEVICE_LOST. Разбиваем на суб-батчи ≤BUILD_CAP только ради размера стека и того,
     * чтобы каждый submit был обозримым; всю очередь опустошаем за этот кадр.
     */
    private void drainBuilds() {
        if (pending.isEmpty()) return;

        // ЗАМЕР ФРИЗА: что именно останавливает кадр при прогрузке чанков — ожидание вершин
        // (syncUploads держит рендер-поток) или запись и отправка сборок. Логируем ТОЛЬКО всплески:
        // среднее тут врёт, фриз — это редкий длинный кадр, а не медленный средний.
        int queued = pending.size();
        long t0 = System.nanoTime();
        AccelStruct.missNs = AccelStruct.sizesNs = AccelStruct.createAsNs = 0;
        AccelStruct.buildCmdNs = AccelStruct.addrNs = 0;
        AccelStruct.acquireNs = 0; copyRecNs = 0; submitNs = 0;
        AccelStruct.missCount = AccelStruct.hitCount = 0;

        // Один синк на кадр: все вершины этого кадра реально на GPU.
        try {
            UploadManager.INSTANCE.syncUploads();
        } catch (Throwable e) { logThrottled("syncUploads", e); return; }
        long t1 = System.nanoTime();

        int guard = 0, batches = 0;
        while (!pending.isEmpty() && guard++ < 4096) {
            buildSubBatch();
            batches++;
        }
        long t2 = System.nanoTime();

        long totalUs = (t2 - t0) / 1000;
        if (totalUs > 3000) {   // всё, что дольше 3 мс, — это уже заметный рывок
            // Разбор ПО ВИНОВНИКАМ: ожидание вершин оказалось нулевым, значит время сидит в создании
            // объектов Vulkan. Три подозреваемых: промах пула буферов (= vkAllocateMemory, самая
            // дорогая операция API), запрос размеров структуры и её создание.
            long knownUs = (t1 - t0) / 1000 + AccelStruct.acquireNs / 1000 + AccelStruct.sizesNs / 1000
                    + AccelStruct.createAsNs / 1000 + AccelStruct.addrNs / 1000
                    + AccelStruct.buildCmdNs / 1000 + copyRecNs / 1000 + submitNs / 1000;
            Initializer.LOGGER.warn("[RT] РЫВОК: {} мкс на {} секций ({} мкс/секция) | вершины {} | "
                            + "ПУЛ(всё) {} мкс (промахов {}: {} мкс) | размеры {} | создание AS {} | адреса {} | "
                            + "запись копий {} | ЗАПИСЬ СБОРКИ {} | отправка {} | ПРОЧЕЕ {} мкс",
                    totalUs, queued, queued == 0 ? 0 : totalUs / queued, (t1 - t0) / 1000,
                    AccelStruct.acquireNs / 1000, AccelStruct.missCount, AccelStruct.missNs / 1000,
                    AccelStruct.sizesNs / 1000, AccelStruct.createAsNs / 1000,
                    AccelStruct.addrNs / 1000, copyRecNs / 1000, AccelStruct.buildCmdNs / 1000,
                    submitNs / 1000, Math.max(totalUs - knownUs, 0));
        }
    }

    /**
     * Забрать сборки, которые GPU уже ЗАКОНЧИЛ. Проверяем fence БЕЗ блокировки: не готово — уходим,
     * заберём в следующем кадре. Здесь же освобождаем scratch — раньше нельзя, GPU их ещё читал.
     */
    private void retireBuilds() {
        while (!inFlight.isEmpty()) {
            InFlightBuild fb = inFlight.peek();
            int st = org.lwjgl.vulkan.VK10.vkGetFenceStatus(net.vulkanmod.vulkan.Vulkan.getVkDevice(), fb.cmd.fence);
            if (st != org.lwjgl.vulkan.VK10.VK_SUCCESS) return;   // ещё строится — кадр НЕ ждёт
            inFlight.poll();

            for (AccelStruct.RawBuffer sc : fb.scratches) AccelStruct.releaseBuffer(sc);
            // ⚠️ Вернуть командный буфер в пул. Раньше он освобождался сразу после waitIdle; теперь
            // живёт до сигнала fence — без явного возврата буферы бы утекали кадр за кадром.
            DeviceManager.getComputeQueue().getCommandPool().addToAvailable(fb.cmd);

            int added = 0;
            for (int i = 0; i < fb.keys.length; i++) {
                if (fb.blas[i] == null) continue;
                PendingBuild pb = fb.items[i];
                Section sec = sections.get(fb.keys[i]);
                if (sec == null) { sec = new Section(); sections.put(fb.keys[i], sec); sectionsChanged = true; }
                else {   // отложенно: TLAS/шейдер ещё ссылаются на старые
                    if (sec.blas != null) deferAS(sec.blas);
                    if (sec.ownedVtx != null) deferBuf(sec.ownedVtx);
                }
                sec.blas = fb.blas[i];
                sectionsChanged = true;   // адрес структуры сменился -> кэш инстансов устарел
                sec.ownedVtx = fb.owned[i];
                sec.vtxAddr = fb.owned[i].address;
                sec.wx = pb.wx; sec.wy = pb.wy; sec.wz = pb.wz;
                sec.water = pb.water;
                added++;
            }
            if (added > 0) tlasDirty = true;
        }
    }

    /** Один суб-батч ≤BUILD_CAP: записать и ОТПРАВИТЬ на GPU, не дожидаясь окончания. */
    static long copyRecNs = 0, submitNs = 0;   // слепые пятна рывка (M8.105)

    private void buildSubBatch() {
        int n = Math.min(pending.size(), BUILD_CAP);
        long[] keys = new long[n];
        PendingBuild[] items = new PendingBuild[n];
        int c = 0, maxQuads = 0;
        var kit = pending.keySet().iterator();
        while (c < n && kit.hasNext()) {
            long k = kit.nextLong();
            keys[c] = k;
            items[c] = pending.get(k);
            maxQuads = Math.max(maxQuads, items[c].vertexCount / 4);
            c++;
        }
        n = c;
        if (n == 0) return;
        for (int i = 0; i < n; i++) pending.remove(keys[i]);

        // ВАЖНО: расширить общий quad-индекс до максимума ДО записи любой сборки —
        // иначе его реаллокация в середине батча оставит записанные сборки с висячим адресом.
        ensureQuadIndex(maxQuads);

        AccelStruct[] blas = new AccelStruct[n];
        AccelStruct.RawBuffer[] owned = new AccelStruct.RawBuffer[n];
        var scratches = new ArrayList<AccelStruct.RawBuffer>(n);
        boolean batchFailed = false;
        try (MemoryStack stack = BUILD_STACK.push()) {
            CommandPool.CommandBuffer cmd = DeviceManager.getComputeQueue().beginCommands();

            // 1) Копируем вершины КАЖДОЙ секции в НАШ буфер (владеем им — нет зависимости
            //    от жизни буфера VulkanMod; шейдер будет читать UV/color отсюда же).
            long __tCopy = System.nanoTime();
            for (int i = 0; i < n; i++) {
                PendingBuild pb = items[i];
                long bytes = (long) pb.vertexCount * pb.vertexSize;
                owned[i] = AccelStruct.acquireBuffer(bytes,
                        VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                                | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);
                // ⚠️ dstOffset — СМЕЩЕНИЕ В ПЛИТЕ, а не 0: буфер общий на десятки секций (суб-аллокация).
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                        .srcOffset(pb.byteOffset).dstOffset(owned[i].offset).size(bytes);
                vkCmdCopyBuffer(cmd.getHandle(), pb.bufferId, owned[i].buffer, region);
            }

            copyRecNs += System.nanoTime() - __tCopy;
            // 2) Барьер: копии (transfer) должны завершиться до чтения сборкой BLAS.
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
            mb.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            vkCmdPipelineBarrier(cmd.getHandle(), VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0, mb, null, null);

            // 3) Строим BLAS из НАШИХ копий — ВЕСЬ БАТЧ ОДНИМ ВЫЗОВОМ (M8.91).
            // Раньше на каждую секцию шёл свой vkCmdBuildAccelerationStructures, и замер показал:
            // после суб-аллокации именно эти вызовы съедали 98% рывка. API принимает массив.
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer builds =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(n, stack);
            int[] prims = new int[n];
            for (int i = 0; i < n; i++) {
                PendingBuild pb = items[i];
                int quads = pb.vertexCount / 4;

                VkAccelerationStructureGeometryKHR.Buffer geo = VkAccelerationStructureGeometryKHR.calloc(1, stack);
                geo.get(0).sType$Default()
                        .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        // CUTOUT без opaque-флага: луч отдаёт кандидата шейдеру на альфа-тест
                        .flags(pb.opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
                var tri = geo.get(0).geometry().triangles();
                tri.sType$Default()
                        .vertexFormat(VK_FORMAT_R16G16B16A16_SNORM)
                        .vertexStride(pb.vertexSize).maxVertex(pb.vertexCount - 1)
                        .indexType(VK_INDEX_TYPE_UINT32);
                tri.vertexData().deviceAddress(owned[i].address);
                tri.indexData().deviceAddress(quadIndex.address);
                tri.transformData().deviceAddress(decodeTransform.address);

                builds.get(i).sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        // ⚠️ Пробовали FAST_TRACE (плотнее дерево -> быстрее обход, дольше сборка).
                        // Замер вышел несопоставимым (разные ОС и места) — вернёмся отдельным опытом.
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                        .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .pGeometries(geo).geometryCount(1);
                prims[i] = quads * 2;
            }
            AccelStruct[] built = AccelStruct.recordBuildBatch(cmd.getHandle(), builds, prims,
                    VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, stack, scratches);
            System.arraycopy(built, 0, blas, 0, n);
            // ⚠️ ОТПРАВИЛИ — И ПОШЛИ ДАЛЬШЕ. Раньше здесь стоял waitIdle (а потом ожидание fence):
            // рендер-поток замирал, пока GPU строит BLAS, и при прогрузке чанков это и было рывком.
            // Секции зарегистрируем в следующем кадре, когда fence реально сигналит (retireBuilds).
            // Задержка в один кадр незаметна, зато кадр больше НИКОГДА не ждёт сборку.
            long __tSub = System.nanoTime();
            DeviceManager.getComputeQueue().submitCommands(cmd);
            submitNs += System.nanoTime() - __tSub;

            InFlightBuild fb = new InFlightBuild();
            fb.cmd = cmd; fb.keys = keys; fb.items = items;
            fb.blas = blas; fb.owned = owned; fb.scratches = scratches;
            inFlight.add(fb);
            return;
        } catch (Throwable e) {
            batchFailed = true;
            logThrottled("drainBuilds", e);
        }

        if (batchFailed) {   // не регистрируем недостроенное — рушим созданные AS и копии
            for (AccelStruct.RawBuffer s : scratches) AccelStruct.releaseBuffer(s);
            for (AccelStruct a : blas) if (a != null) a.free();
            for (AccelStruct.RawBuffer b : owned) if (b != null) AccelStruct.releaseBuffer(b);
            return;
        }

        int added = 0;
        for (int i = 0; i < n; i++) {
            if (blas[i] == null) continue;
            PendingBuild pb = items[i];
            Section sec = sections.get(keys[i]);
            if (sec == null) { sec = new Section(); sections.put(keys[i], sec); sectionsChanged = true; }
            else {   // отложенно: TLAS/шейдер ещё ссылаются на старые
                if (sec.blas != null) deferAS(sec.blas);
                if (sec.ownedVtx != null) deferBuf(sec.ownedVtx);
            }
            sec.blas = blas[i];
            sectionsChanged = true;   // адрес структуры сменился -> кэш инстансов устарел
            sec.ownedVtx = owned[i];
            sec.vtxAddr = owned[i].address;
            sec.wx = pb.wx; sec.wy = pb.wy; sec.wz = pb.wz;
            sec.water = pb.water;
            added++;
        }
        if (added > 0) {
            tlasDirty = true;
            int before = builtCount;
            builtCount += added;
            if (builtCount / 200 != before / 200)
                // ⚠️ ЗАМЕР ПУЛА (M8.58): «выделений» — сколько раз реально звали vkAllocateMemory
                // (дорого, особенно на Windows), «из пула» — сколько раз буфер пришёл готовым.
                // Если фикс работает, при беготне по миру растёт ВТОРОЕ число, а первое стоит.
                Initializer.LOGGER.info("[RT] built {} section BLASes (live: {}, очередь: {}) | "
                                + "память: выделений {}, из пула {}, запас {} МБ",
                        builtCount, sections.size(), pending.size(),
                        AccelStruct.allocCount(), AccelStruct.reuseCount(), AccelStruct.pooledMB());
        }
    }

    // === RT PATCH (M8.98): СМЕНА/ПЕРЕСОЗДАНИЕ МИРА (WorldRenderer.allChanged/setLevel).
    // VulkanMod пересобирает сетку чанков ОПТОМ — по-секционные хуки удаления не срабатывают,
    // и BLAS-ы старого мира оставались в TLAS: старый мир «призраком» наслаивался на новый.
    // Ресурсы уходим в ОТЛОЖЕННОЕ освобождение (кольцо) — GPU мог читать их в этом кадре.
    // Трассировка встаёт до первого TLAS нового мира (tlasHandle = NULL — пробники ждут). ===
    public static void resetWorld() {
        if (INSTANCE == null) return;
        RtWorld w = INSTANCE;
        try {
            for (Section sec : w.sections.values()) {
                if (sec.blas != null) w.deferAS(sec.blas);
                if (sec.ownedVtx != null) w.deferBuf(sec.ownedVtx);
            }
            w.sections.clear();
            w.pending.clear();
            RtLights.clearSectionLights();   // M8.142: запечённый свет старого мира — тоже сбросить
            RtLightVolume.reset();           // M8.153: решётка старого мира недействительна
            if (w.entityBlas != null)    { w.deferAS(w.entityBlas);     w.entityBlas = null; }
            // M8.147: BLAS осадков живёт по тем же правилам, что и BLAS сущностей — он лежит
            // в ХВОСТЕ того же буфера вершин, поэтому обязан сбрасываться вместе с ним.
            if (w.weatherBlas != null)   { w.deferAS(w.weatherBlas);    w.weatherBlas = null; }
            w.weatherQuadsN = 0; w.weatherVtxBase = 0; weatherInstanceIdx = -1;
            if (w.entityVtx != null)     { w.deferBuf(w.entityVtx);     w.entityVtx = null; }
            if (w.entityQuadTex != null) { w.deferBuf(w.entityQuadTex); w.entityQuadTex = null; }
            w.entityQuads = 0;
            if (w.tlas != null)          { w.deferAS(w.tlas);           w.tlas = null; }
            w.sectionsChanged = true;
            w.tlasDirty = true;
            Initializer.LOGGER.info("[RT] мир сменился — геометрия трассировки сброшена");
        } catch (Throwable t) {
            Initializer.LOGGER.error("[RT] resetWorld", t);
        }
    }
    // === /RT PATCH ===

    // === RT PATCH (M8.6): освобождение ВСЕХ RT-ресурсов при завершении (из Vulkan.cleanUp,
    // до уничтожения устройства). Буферы/BLAS — сырой vkAllocateMemory, VulkanMod их не чистит. ===
    public static void shutdown() {
        if (INSTANCE == null) return;
        try { INSTANCE.freeAll(); }
        catch (Throwable t) { Initializer.LOGGER.error("[RT] RtWorld shutdown", t); }
        INSTANCE = null;
    }
    private void freeAll() {
        if (tlas != null) { tlas.free(); tlas = null; }
        for (Section sec : sections.values()) {
            if (sec.blas != null) sec.blas.free();
            if (sec.ownedVtx != null) AccelStruct.releaseBuffer(sec.ownedVtx);
        }
        sections.clear();
        for (int g = 0; g < RING; g++) {   // всё отложенное во всех поколениях кольца
            for (AccelStruct a : ringAS[g]) a.free();
            ringAS[g].clear();
            for (AccelStruct.RawBuffer b : ringBuf[g]) AccelStruct.releaseBuffer(b);
            ringBuf[g].clear();
        }
        if (entityBlas != null) { entityBlas.free(); entityBlas = null; }
        if (weatherBlas != null) { weatherBlas.free(); weatherBlas = null; }   // M8.147: вместе с сущностями
        weatherQuadsN = 0; weatherVtxBase = 0; weatherInstanceIdx = -1;
        if (entityVtx != null) { AccelStruct.releaseBuffer(entityVtx); entityVtx = null; }
        if (entityQuadTex != null) { AccelStruct.releaseBuffer(entityQuadTex); entityQuadTex = null; }
        if (instanceBuffer != null) { AccelStruct.releaseBuffer(instanceBuffer); instanceBuffer = null; }
        if (vtxAddrTable != null) { AccelStruct.releaseBuffer(vtxAddrTable); vtxAddrTable = null; }
        if (decodeTransform != null) { AccelStruct.releaseBuffer(decodeTransform); decodeTransform = null; }
        if (quadIndex != null) { AccelStruct.releaseBuffer(quadIndex); quadIndex = null; }
        AccelStruct.clearPool();   // запас пула — тоже освободить
        Initializer.LOGGER.info("[RT] RtWorld освобождён при выходе");
    }
    // === /RT PATCH ===

    public void removeSection(long key) {
        pending.remove(key);          // и из очереди сборки — иначе призрак вернётся следующим батчем
        sectionsChanged = true;
        Section sec = sections.remove(key);
        if (sec != null) {
            if (sec.blas != null) deferAS(sec.blas);
            if (sec.ownedVtx != null) deferBuf(sec.ownedVtx);
            tlasDirty = true;
        }
    }

    /** ASYNC: зафиксировать сущности кадра и записать entity-BLAS + TLAS в кадровый буфер. */
    private void recordFrameRebuildImpl(VkCommandBuffer frameCmd) {
        RtEntities.endFrame();                        // меши сущностей / тела игрока / руки этого кадра
        boolean haveEntities = RtEntities.quads() > 0;
        // Сущности заданы В КООРДИНАТАХ КАМЕРЫ -> при движении камеры их инстанс-перенос
        // меняется каждый кадр. Значит при наличии сущностей TLAS устаревает каждый кадр.
        if (haveEntities || entityQuads > 0) tlasDirty = true;
        if (!tlasDirty && tlas != null) return;       // статичная сцена без сущностей — старый TLAS валиден
        try {
            cycleRing();                              // освободить ресурсы RING кадров назад (те кадры завершились)
            recordRebuildTlas(frameCmd);
            tlasDirty = false;
        } catch (Throwable e) {
            logThrottled("recordFrameRebuild", e);
        }
    }

    /**
     * ASYNC M8.4: entity-BLAS кадра пишется в КАДРОВЫЙ буфер (recordBuild, без waitIdle).
     * Старый BLAS/копии — в кольцо (их ещё читает кадр «в полёте»). scratch — в scratchSink
     * (то же кольцо). Native build-info живёт на переданном stack: он читается только в
     * момент vkCmdBuildAccelerationStructures (record-time), а pop произойдёт до сабмита —
     * это допустимо (device-адреса внутри читаются на исполнении, а сами структуры — нет).
     */
    private void recordEntityBlas(VkCommandBuffer frameCmd, MemoryStack stack,
                                  ArrayList<AccelStruct.RawBuffer> scratchSink) {
        if (entityBlas != null) { deferAS(entityBlas); entityBlas = null; }
        if (weatherBlas != null) { deferAS(weatherBlas); weatherBlas = null; }   // M8.146
        weatherQuadsN = 0; weatherVtxBase = 0;
        if (entityVtx != null) { deferBuf(entityVtx); entityVtx = null; }
        if (entityQuadTex != null) { deferBuf(entityQuadTex); entityQuadTex = null; }
        entityQuads = 0;
        int quads = RtEntities.quads();
        if (quads == 0) return;
        try {
            ensureQuadIndex(quads);
            long bytes = RtEntities.bytes();
            entityVtx = AccelStruct.acquireBuffer(bytes,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
            // Таблица «квад -> слот текстуры» для шейдера (текстуры сущностей)
            entityQuadTex = AccelStruct.acquireBuffer((long) quads * 4,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
            entityTexList = RtEntities.textures().clone();

            PointerBuffer p = stack.mallocPointer(1);
            vkMapMemory(Vulkan.getVkDevice(), entityVtx.memory, 0, bytes, 0, p);
            MemoryUtil.memCopy(RtEntities.ptr(), p.get(0), bytes);
            vkUnmapMemory(Vulkan.getVkDevice(), entityVtx.memory);

            vkMapMemory(Vulkan.getVkDevice(), entityQuadTex.memory, 0, (long) quads * 4, 0, p);
            var slots = MemoryUtil.memByteBuffer(p.get(0), quads * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            int[] src = RtEntities.quadSlots();
            slots.put(src, 0, quads);   // одним куском (было: цикл по тысячам квадов)
            vkUnmapMemory(Vulkan.getVkDevice(), entityQuadTex.memory);

            // === M8.146: ДВА BLAS — сущности и ОСАДКИ отдельно ===
            // Осадки лежат непрерывно в ХВОСТЕ буфера (RtEntities их туда переупорядочил), поэтому
            // второй BLAS строится из ТОГО ЖЕ буфера вершин, просто со смещением. У его инстанса
            // будет маска 0x04, и вторичные лучи пропустят его АППАРАТНО — не обходя BVH дождя.
            final int nEnt = Math.min(RtEntities.weatherStart(), quads);   // сущности/партиклы
            final int nWea = Math.max(quads - nEnt, 0);                    // осадки (хвост)

            VkAccelerationStructureGeometryKHR.Buffer geo = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geo.get(0).sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    // с текстурами сущности идут через альфа-тест (шерсть/грива/сёдла)
                    .flags(DeviceManager.rtTextureArraySupported ? 0 : VK_GEOMETRY_OPAQUE_BIT_KHR);
            var tri = geo.get(0).geometry().triangles();
            tri.sType$Default()
                    .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)     // позиции сущностей — float
                    .vertexStride(RtEntities.STRIDE).maxVertex(Math.max(nEnt * 4 - 1, 0))
                    .indexType(VK_INDEX_TYPE_UINT32);
            tri.vertexData().deviceAddress(entityVtx.address);
            tri.indexData().deviceAddress(quadIndex.address);
            // transformData не задаём: позиции уже во «космосе камеры», перенос — в инстансе

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.get(0).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geo).geometryCount(1);

            if (nEnt > 0) {
                entityBlas = AccelStruct.recordBuild(frameCmd, build, nEnt * 2,
                        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, stack, scratchSink);
            }
            entityQuads = nEnt;

            // === ОСАДКИ: свой BLAS из того же буфера, вершины со смещением на nEnt квадов ===
            // Индексный буфер общий: индексы относительны базы вершин, поэтому подходит как есть.
            if (nWea > 0) {
                VkAccelerationStructureGeometryKHR.Buffer wgeo = VkAccelerationStructureGeometryKHR.calloc(1, stack);
                wgeo.get(0).sType$Default()
                        .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        .flags(DeviceManager.rtTextureArraySupported ? 0 : VK_GEOMETRY_OPAQUE_BIT_KHR);
                var wtri = wgeo.get(0).geometry().triangles();
                wtri.sType$Default()
                        .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                        .vertexStride(RtEntities.STRIDE).maxVertex(nWea * 4 - 1)
                        .indexType(VK_INDEX_TYPE_UINT32);
                weatherVtxBase = entityVtx.address + (long) nEnt * 4 * RtEntities.STRIDE;
                wtri.vertexData().deviceAddress(weatherVtxBase);
                wtri.indexData().deviceAddress(quadIndex.address);

                VkAccelerationStructureBuildGeometryInfoKHR.Buffer wbuild =
                        VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
                wbuild.get(0).sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                        .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .pGeometries(wgeo).geometryCount(1);

                weatherBlas = AccelStruct.recordBuild(frameCmd, wbuild, nWea * 2,
                        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, stack, scratchSink);
                weatherQuadsN = nWea;
            }
            entCamX = RtEntities.camXf(); entCamY = RtEntities.camYf(); entCamZ = RtEntities.camZf();
            if (entityLogged++ % 200 == 0) {
                StringBuilder sb = new StringBuilder();
                int[] src2 = RtEntities.quadSlots();
                int[] hist = new int[entityTexList.length + 1];
                int playerQuads = 0, handQuads = 0;
                for (int i = 0; i < quads; i++) {
                    if ((src2[i] & RtEntities.PLAYER_FLAG) != 0) playerQuads++;   // тело игрока
                    if ((src2[i] & RtEntities.HAND_FLAG) != 0) handQuads++;       // рука 1-го лица
                    int s = src2[i] & 0xFFFF;
                    hist[s < entityTexList.length ? s : entityTexList.length]++;
                }
                for (int i = 0; i < entityTexList.length; i++)
                    sb.append(i).append('=').append(entityTexList[i] != null ? entityTexList[i].name : "null")
                      .append('[').append(entityTexList[i] != null ? entityTexList[i].width : -1).append('x')
                      .append(entityTexList[i] != null ? entityTexList[i].height : -1).append(']')
                      .append('(').append(hist[i]).append("q) ");
                Initializer.LOGGER.info("[RT] entity BLAS: {} quads (тело игрока: {}, рука: {}) | {}", quads, playerQuads, handQuads, sb);
            }
        } catch (Throwable e) {
            logThrottled("recordEntityBlas", e);
            if (entityVtx != null) { deferBuf(entityVtx); entityVtx = null; }
            if (entityQuadTex != null) { deferBuf(entityQuadTex); entityQuadTex = null; }
            entityBlas = null; entityQuads = 0;
            // M8.147 ОБЯЗАТЕЛЬНО: BLAS осадков ссылается на ХВОСТ entityVtx, который здесь уже
            // отдан на освобождение — оставить его значило бы держать висячий указатель на
            // освобождённую память (наш давний класс багов). Не deferAS: прошлый BLAS осадков
            // уже отложен в начале перестройки, повторная отдача была бы двойным освобождением.
            weatherBlas = null; weatherQuadsN = 0; weatherVtxBase = 0;
        }
    }

    /** VkBuffer таблицы «квад сущности → слот текстуры» (для дескриптора шейдера). */
    public long entityQuadTexBuffer() {
        return entityQuadTex != null ? entityQuadTex.buffer : VK_NULL_HANDLE;
    }

    /** Текстуры сущностей, соответствующие ТЕКУЩЕМУ TLAS (слоты массива в шейдере). */
    public net.vulkanmod.vulkan.texture.VulkanImage[] entityTextures() {
        return entityTexList;
    }

    /**
     * ASYNC: записать пересборку TLAS (+ entity-BLAS) В КАДРОВЫЙ буфер. Без submit/waitIdle:
     * компьют читает свежий TLAS в том же сабмите. Барьеры упорядочивают BLAS→TLAS→compute.
     * instance/vtx-table/entity-буферы — НОВЫЕ каждый кадр (их читает GPU в этом сабмите),
     * старые в кольцо. scratch — в текущее поколение кольца (scratchSink).
     */
    private void recordRebuildTlas(VkCommandBuffer frameCmd) {
        try (MemoryStack stack = stackPush()) {
            ArrayList<AccelStruct.RawBuffer> scratchSink = ringBuf[ringGen];   // scratch освобождается кольцом

            recordEntityBlas(frameCmd, stack, scratchSink);   // entity-BLAS в кадровый буфер (async)

            int count = sections.size() + (entityBlas != null ? 1 : 0)
                                        + (weatherBlas != null ? 1 : 0);   // M8.146: +инстанс осадков
            if (count == 0) return;

            ensureInstanceBuffer(count);   // новый буфер каждый кадр -> старый в кольцо (GPU читает его в этом сабмите)
            ensureVtxTable(count);

            final int ISIZE = VkAccelerationStructureInstanceKHR.SIZEOF;

            // ⚠️ ИНСТАНСЫ СЕКЦИЙ — ИЗ КЭША. Их переносы и адреса не меняются от кадра к кадру:
            // секция стоит на месте, пока её не пересоберут. Пересобираем кэш только когда состав
            // секций реально изменился (см. sectionsChanged), а в кадре копируем его ОДНИМ КУСКОМ.
            boolean structChanged = sectionsChanged || instStagePtr == 0;
            if (structChanged) {
                int needI = Math.max(count, 2048) * ISIZE;
                if (needI > instStageCap) {
                    instStagePtr = instStagePtr == 0 ? MemoryUtil.nmemAlloc(needI)
                                                     : MemoryUtil.nmemRealloc(instStagePtr, needI);
                    instStageCap = needI;
                }
                int needT = Math.max(count, 2048) * 8;
                if (needT > tableStageCap) {
                    tableStagePtr = tableStagePtr == 0 ? MemoryUtil.nmemAlloc(needT)
                                                       : MemoryUtil.nmemRealloc(tableStagePtr, needT);
                    tableStageCap = needT;
                }
                VkAccelerationStructureInstanceKHR.Buffer sInst =
                        VkAccelerationStructureInstanceKHR.create(instStagePtr, Math.max(count, 1));
                var sTable = MemoryUtil.memByteBuffer(tableStagePtr, Math.max(count, 1) * 8)
                        .order(ByteOrder.nativeOrder()).asLongBuffer();
                int k = 0;
                for (Section sec : sections.values()) {
                    if (sec.blas == null) continue;
                    VkAccelerationStructureInstanceKHR e = sInst.get(k);
                    var m = e.transform().matrix();      // 3x4: I + перенос (wx,wy,wz)
                    m.put(0, 1f).put(1, 0f).put(2, 0f).put(3, (float) sec.wx);
                    m.put(4, 0f).put(5, 1f).put(6, 0f).put(7, (float) sec.wy);
                    m.put(8, 0f).put(9, 0f).put(10, 1f).put(11, (float) sec.wz);
                    // mask 0x01 = «мир» (секции). Луч-оверлей руки (cullMask 0x02) их не видит.
                    e.instanceCustomIndex(sec.water ? (WATER_FLAG | k) : k).mask(0x01)
                            .instanceShaderBindingTableRecordOffset(0)
                            .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                            .accelerationStructureReference(sec.blas.deviceAddress);
                    sTable.put(k, sec.vtxAddr);        // instanceCustomIndex → адрес вершин секции
                    k++;
                }
                instStageCount = k;
                sectionsChanged = false;
            }

            PointerBuffer p = stack.mallocPointer(1);
            long bytes = (long) count * ISIZE;
            vkMapMemory(Vulkan.getVkDevice(), instanceBuffer.memory, 0, bytes, 0, p);
            PointerBuffer pt = stack.mallocPointer(1);
            vkMapMemory(Vulkan.getVkDevice(), vtxAddrTable.memory, 0, (long) count * 8, 0, pt);

            // Готовые байты секций — одним копированием (было: 8375 структур по одной, 350 мкс).
            MemoryUtil.memCopy(instStagePtr, p.get(0), (long) instStageCount * ISIZE);
            MemoryUtil.memCopy(tableStagePtr, pt.get(0), (long) instStageCount * 8);

            VkAccelerationStructureInstanceKHR.Buffer inst =
                    VkAccelerationStructureInstanceKHR.create(p.get(0), count);
            var table = MemoryUtil.memByteBuffer(pt.get(0), count * 8).order(ByteOrder.nativeOrder()).asLongBuffer();
            int i = instStageCount;
            // M8.4: инстанс сущностей — перенос на камеру кадра, бит-метка «сущность»
            if (entityBlas != null) {
                VkAccelerationStructureInstanceKHR e = inst.get(i);
                var m = e.transform().matrix();
                m.put(0, 1f).put(1, 0f).put(2, 0f).put(3, (float) entCamX);
                m.put(4, 0f).put(5, 1f).put(6, 0f).put(7, (float) entCamY);
                m.put(8, 0f).put(9, 0f).put(10, 1f).put(11, (float) entCamZ);
                // mask 0x02 = «сущности» (в т.ч. рука). Оверлей руки трассируется cullMask 0x02
                // (только этот инстанс) -> мир не даёт opaque-авто-коммит, и это дешевле.
                e.instanceCustomIndex(ENTITY_FLAG | i).mask(0x02).instanceShaderBindingTableRecordOffset(0)
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(entityBlas.deviceAddress);
                table.put(i, entityVtx.address);
                i++;
            }
            // === M8.146: ИНСТАНС ОСАДКОВ — маска 0x04 ===
            // Вторичные лучи (тени/отражения/амбиент) ходят маской 0x03 и этот инстанс НЕ ВИДЯТ:
            // аппаратный пропуск, BVH дождя не обходится вовсе. Первичный луч идёт 0xFF — видит.
            // Шейдер опознаёт осадки по индексу инстанса (приходит в rtCfg7.x), а слот их текстуры —
            // в rtCfg7.y: у осадков она одна на кадр, поэтому per-quad quadTex им не нужен.
            weatherInstanceIdx = -1;
            if (weatherBlas != null) {
                VkAccelerationStructureInstanceKHR w = inst.get(i);
                var wm = w.transform().matrix();
                wm.put(0, 1f).put(1, 0f).put(2, 0f).put(3, (float) entCamX);
                wm.put(4, 0f).put(5, 1f).put(6, 0f).put(7, (float) entCamY);
                wm.put(8, 0f).put(9, 0f).put(10, 1f).put(11, (float) entCamZ);
                w.instanceCustomIndex(ENTITY_FLAG | i).mask(0x04).instanceShaderBindingTableRecordOffset(0)
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(weatherBlas.deviceAddress);
                table.put(i, weatherVtxBase);        // вершины осадков = хвост общего буфера
                weatherInstanceIdx = i;
                i++;
                // ДИАГНОСТИКА M8.146 (временно): капли рисуются чужой текстурой — смотрим реальные числа
                if ((entityLogged % 200) == 0) {
                    Initializer.LOGGER.info(
                            "[RT] ОСАДКИ: квадов {} (сущностей {}), инстанс #{}, слот текстуры {}, всего текстур {}",
                            weatherQuadsN, entityQuads, weatherInstanceIdx,
                            RtEntities.weatherSlot(), entityTexList.length);
                }
            }
            vkUnmapMemory(Vulkan.getVkDevice(), vtxAddrTable.memory);
            vkUnmapMemory(Vulkan.getVkDevice(), instanceBuffer.memory);
            count = i;
            if (count == 0) return;

            VkAccelerationStructureGeometryKHR.Buffer geo = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geo.get(0).sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geo.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
            geo.get(0).geometry().instances().data().deviceAddress(instanceBuffer.address);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.get(0).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    // ⚠️ FAST_BUILD, А НЕ FAST_TRACE. TLAS пересобирается ЦЕЛИКОМ КАЖДЫЙ КАДР (в нём
                    // тысячи секций плюс сущности, которые движутся). FAST_TRACE просит драйвер
                    // строить дольше ради чуть более быстрых лучей — для структуры, живущей один
                    // кадр, это плата ни за что: строим тысячи раз, а выигрыш на трассировке
                    // копеечный. FAST_BUILD режет время сборки в разы.
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geo).geometryCount(1);

            // Барьер: entity-BLAS (если писался этим cmd) должен достроиться до чтения TLAS-сборкой.
            asBuildBarrier(frameCmd, stack, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

            // TLAS пересобираем целиком каждый кадр. Обновление (refit) пробовали — оно ломало
            // геометрию (двери «ехали слайдами»: обновление сохраняет СТАРОЕ дерево, правя лишь
            // границы) и НЕ дало ни кадра прироста: узкое место кадра не здесь.
            AccelStruct newTlas = AccelStruct.recordBuild(frameCmd, build, count,
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, stack, scratchSink);
            deferAS(tlas);   // старый TLAS ещё читает кадр «в полёте» -> кольцо
            tlas = newTlas;

            // Барьер: TLAS должен достроиться до чтения RT-компьютом (recordCompute идёт следом).
            asBuildBarrier(frameCmd, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            if (++tlasBuilds % 120 == 0 || tlasBuilds <= 3)
                Initializer.LOGGER.info("[RT] async TLAS в кадре: {} инстансов (addr=0x{})",
                        count, Long.toHexString(tlas.deviceAddress));
        }
    }

    /** Барьер «сборка AS завершена -> её читает следующая стадия» (build или compute). */
    private static void asBuildBarrier(VkCommandBuffer cmd, MemoryStack stack, int dstStage) {
        VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
        mb.get(0).sType$Default()
                .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                dstStage, 0, mb, null, null);
    }

    private void ensureInstanceBuffer(int count) {
        // ⚠️ ASYNC: instance-буфер читает сборка TLAS на GPU в этом сабмите. Поэтому каждый
        // кадр — НОВЫЙ буфер (старый в кольцо), иначе перезапись на CPU пока кадр «в полёте»
        // читает старый = рваные данные инстансов -> DEVICE_LOST.
        if (instanceBuffer != null) deferBuf(instanceBuffer);
        int cap = Math.max(count, 2048);
        long bytes = (long) cap * VkAccelerationStructureInstanceKHR.SIZEOF;
        instanceBuffer = AccelStruct.acquireBuffer(bytes,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
        instanceCapacity = cap;
    }

    private void ensureVtxTable(int count) {
        // ⚠️ ASYNC: таблицу адресов читает кадровый компьют ОТЛОЖЕННО. Поэтому каждый цикл —
        // НОВЫЙ буфер (не переписываем и не рушим старый in-place), старый в отложенное:
        // кадр, читающий старую таблицу (консистентную со старым TLAS), к следующей пересборке
        // завершится. Иначе реаллокация/перезапись = use-after-free / рваный адрес -> DEVICE_LOST.
        if (vtxAddrTable != null) deferBuf(vtxAddrTable);   // старую таблицу ещё читает кадр «в полёте» -> кольцо
        int cap = Math.max(count, 2048);
        vtxAddrTable = AccelStruct.acquireBuffer((long) cap * 8,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
        vtxTableCapacity = cap;
    }

    /** VkBuffer таблицы «инстанс→адрес вершин» (для дескриптора шейдера). */
    public long vtxTableBuffer() {
        return vtxAddrTable != null ? vtxAddrTable.buffer : VK_NULL_HANDLE;
    }

    /** Адрес TLAS мира для ray-query шейдера (0, если ещё не собран). */
    public long tlasAddress() {
        return tlas != null ? tlas.deviceAddress : 0L;
    }

    public long tlasHandle() {
        return tlas != null ? tlas.handle : VK_NULL_HANDLE;
    }

    private void logThrottled(String what, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - errorLogged > 5000) {
            errorLogged = now;
            Initializer.LOGGER.error("[RT] {} failed: ", what, e);
        }
    }

    private static long bufferDeviceAddress(long bufferHandle) {
        try (MemoryStack stack = stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default().buffer(bufferHandle);
            return vkGetBufferDeviceAddress(Vulkan.getVkDevice(), info);
        }
    }
}
