package net.vulkanmod.vulkan.rt;

import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.*;   // M8.126e: проба текселя (константы Vulkan)

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * M8.4 — сбор мешей СУЩНОСТЕЙ для трассировки (+ их текстуры).
 *
 * Вся немедленная геометрия сущностей/блок-сущностей проходит через
 * {@code Drawer.draw(ByteBuffer, ...)} с форматом NEW_ENTITY (36 Б/вершину:
 * pos 3×float @0, color RGBA8 @12, uv 2×float @16, overlay @24, light @28,
 * normal @32), КВАДАМИ, в координатах ОТНОСИТЕЛЬНО КАМЕРЫ и уже в позе после
 * анимации. Мы копим эти байты за кадр (CPU) вместе с ТЕКСТУРОЙ каждого батча
 * (в момент отрисовки она привязана в слот 0 {@link VTextureSelector}); в конце
 * кадра фиксируем «кадр» (двойной буфер): байты вершин + таблица «квад→слот
 * текстуры» + список уникальных текстур (&le;{@link #MAX_TEX}).
 *
 * Фазовое окно: собираем только во время рендера МИРА (beginLevel в
 * setupRenderer, endLevel на TRANSLUCENT-слое) — рука 1-го лица и GUI рисуются
 * после и в трассировку не попадают.
 */
public class RtEntities {
    public static final int STRIDE = 36;   // размер вершины NEW_ENTITY
    public static final int MAX_TEX = 256; // максимум разных текстур сущностей на кадр
    // ⚠️ Было 64 — В ОНЛАЙНЕ НЕ ХВАТАЛО: у каждого игрока свой скин (+плащ, +наш эмиссивный
    // слой), у каждого моба своя текстура, плюс атлас шрифта для ников. Всё, что не влезало,
    // падало в слот 0 и читало ЧУЖУЮ текстуру: ломались скины, ники превращались в квадраты,
    // рука пропадала (её UV попадали в прозрачные тексели -> убивал альфа-тест). Зависимость
    // от угла взгляда — потому что игра рисует только попавшее в кадр.

    // Старшие биты слота квада = маски (слоты < MAX_TEX, биги свободны):
    //  бит 31 = «тело игрока» (первичный луч пропускает — не видишь лицо; тень/отражение ловят);
    //  бит 30 = «рука 1-го лица» (первичный/тень/амбиент ловят — видишь руку с тенями;
    //           отражённый луч из мира ПРОПУСКАЕТ — в воде не болтается рука).
    public static final int PLAYER_FLAG   = 0x80000000;
    public static final int HAND_FLAG     = 0x40000000;
    // M8.126f: бит 25 — рендертайп С КУЛЛИНГОМ («..._cull», но не «..._no_cull»): луч отбрасывает
    // обратные грани, как растр. Нужен вывернутым оболочкам-контурам (Actually 3D Stuff).
    public static final int CULL_FLAG     = 0x02000000;

    // M8.121: рука ФИЗИЧЕСКИ выше на пол-блока. Ванильный вьюмодел лежит у пояса (~1.0 над
    // землёй) — тени соседних блоков накрывали руку (репорт: «рука чернеет»). Геометрию
    // поднимаем при захвате, а луч оверлея руки в шейдере стартует с той же добавкой —
    // в кадре рука остаётся ровно там же, но тень/амбиент считаются на высоте ~1.5.
    public static final float HAND_LIFT = 0.5f;
    //  бит 29 = «ПАРТИКЛ» (виден первичным лучом и в отражениях, но НЕ отбрасывает тень:
    //           дым/искры не должны затемнять мир).
    public static final int PARTICLE_FLAG = 0x20000000;
    public static final int WEATHER_FLAG  = 0x10000000;   // бит 28: капли дождя/снега — свой шейдинг
    public static final int BOLT_FLAG     = 0x08000000;   // бит 27: МОЛНИЯ — эмиссивная, текстуры нет
    //  бит 26 = «СВЕТЯЩИЙСЯ СЛОЙ»: глаза паука/эндермена (ванильный тайп "eyes") и эмиссивные слои
    //           ETF. Ваниль рисует их с полной яркостью и без затенения; у меня они приезжали
    //           обычной геометрией и тонули в ночном свете. Шейдер светит ими сам.
    public static final int EMISSIVE_FLAG = 0x04000000;
    private static int weatherExtra = 0;                    // OR к флагу партикла для батчей погоды

    /** Один батч: сколько квадов, с какой текстурой, и биты-маски (PLAYER/HAND/0). */
    private record Batch(int quads, VulkanImage tex, int flags) {}

    // Накопление текущего кадра (пишется из Drawer.draw, render-поток)
    private static long accPtr = 0;
    private static int accCap = 0, accBytes = 0;

    // ⚠️ СЛОИ-НАЛОЖЕНИЯ, ОПОЗНАННЫЕ ПО ГЕОМЕТРИИ (M8.59). Ваниль рисует одежду жителя, отметины
    // лошади, глаза паука и прочие слои ПОВЕРХ базовой шкуры — теми же вершинами, той же позой,
    // то есть координаты совпадают бит в бит. В растре порядок отрисовки решает, кто сверху; в
    // трассировке это два треугольника В ОДНОЙ ПЛОСКОСТИ, и какой поймает луч — решает драйвер.
    // Отсюда лоскуты на жителе и белые пятна на лошади (и то, что на Linux их нет, а на Windows есть).
    // Раньше я поднимал слои по СПИСКУ ИМЁН текстур — список дырявый по своей природе: жителя в нём
    // не было. Теперь опознаём слой по факту: если такой же квад в этом кадре уже встречался, это
    // наложение — поднимаем его наружу по нормали, тем сильнее, чем он выше в стопке.
    private static final java.util.HashMap<Long, Integer> quadSeen = new java.util.HashMap<>();
    // ⚠️ 2.5 мм. Полтора миллиметра луч различал не всегда (сборка BLAS хранит треугольники сжато,
    // и слои, разведённые на волосок, снова слипались в одну плоскость), а четыре давали слишком
    // широкую щель на рёбрах. 2.5 мм — с запасом для луча и незаметно глазу; щель на рёбрах закрыта
    // расширением квада, см. ниже.
    private static final float LAYER_STEP = 0.0025f;

    private static final ArrayList<Batch> accBatches = new ArrayList<>();

    // Зафиксированный последний кадр (читается при пересборке TLAS)
    private static long framePtr = 0;
    private static int frameCap = 0, frameBytes = 0;
    private static int[] frameQuadSlots = new int[0];       // квад -> слот текстуры
    // M8.146: ОСАДКИ лежат НЕПРЕРЫВНО в ХВОСТЕ буфера вершин -> из того же буфера строится
    // отдельный weather-BLAS (со смещением) под своей маской: тени/отражения его не видят.
    // Слот текстуры у осадков ОДИН на кадр (rain.png или snow.png) -> per-quad quadTex им не нужен.
    private static int frameWeatherStart = 0, frameWeatherCount = 0, frameWeatherSlot = 0;
    private static VulkanImage[] frameTex = new VulkanImage[0];

    private static double camX, camY, camZ;
    private static double fCamX, fCamY, fCamZ;
    private static boolean camSet = false;

    // Фазовое окно сбора: только рендер МИРА.
    private static boolean collecting = false;

    // Текстура СЛЕДУЮЩЕГО батча (из RenderTypeM.draw — там настоящий Sampler0 батча;
    // VTextureSelector для immediate-пути устаревший).
    private static VulkanImage nextBatchTex = null;

    // Режим «только захват» (без экрана): включаем на время принудительного off-screen
    // рендера игрока — RenderTypeM.draw ловит вершины+скин, но не рисует на экран.
    private static boolean captureOnly = false;
    public static boolean captureOnly() { return captureOnly; }
    public static void setCaptureOnly(boolean v) { captureOnly = v; }

    // Окно захвата РУКИ 1-го лица (renderItemInHand): собранное тегируется HAND_FLAG.
    private static boolean captureHand = false;

    /** Из RenderTypeM.draw: текстура батча, который сейчас уйдёт в Drawer.draw. */
    public static void setNextBatchTexture(VulkanImage tex) { nextBatchTex = tex; }

    /** Текстура текущего батча: точная из RenderTypeM, иначе — привязанная к слоту 0. */
    public static VulkanImage batchTexture() {
        VulkanImage t = nextBatchTex != null ? nextBatchTex : VTextureSelector.getBoundTexture(0);
        return freshAtlas(t);
    }

    /** Из RenderTypeM.draw: имя рендер-тайпа батча — по нему опознаём светящиеся слои. */
    public static void setNextBatchType(String name) { nextBatchType = name; }

    private static String nextBatchType = null;

    // ⚠️ ЭМИССИВНЫЙ СЛОЙ РИСУЕМ САМИ (M8.68). ETF под VulkanMod свой светящийся проход НЕ отдаёт:
    // он вешает данные на обёртку потребителя вершин, а этот путь здесь не срабатывает — ловушка в
    // логе не сработала ни разу, хотя эмиссивные текстуры пак загрузил. Чинить чужой мод изнутри не
    // будем: слой можно нарисовать самим, и это даже надёжнее. Правило OptiFine простое — рядом с
    // текстурой моба лежит её вариант с суффиксом _e, где непрозрачны ТОЛЬКО светящиеся пиксели.
    // Значит достаточно повторить квады батча с этой текстурой и флагом свечения: механизм слоёв
    // поднимет их наружу, альфа-тест отбросит всё, кроме светящихся пикселей, а шейдер зажжёт их.
    // Работает независимо от того, каким путём ETF (или его отсутствие) рисует мобов.
    private static boolean mismatchLogged = false;
    private static final java.util.HashMap<String, Boolean> EMIS_EXISTS = new java.util.HashMap<>();

    private static VulkanImage emissiveSibling(VulkanImage base) {
        if (base == null || base.name == null) return null;
        String n = base.name;
        if (!n.endsWith(".png") || n.endsWith("_e.png") || !n.contains("/entity/")) return null;
        // ⚠️ ПАУК — БЕЗ ЭМИССИИ. Его эмиссивная текстура из пака (spider_e.png) нарисована ПОД МОДЕЛЬ
        // FreshAnimations, и на другой развёртке глаза ложатся не туда — вылезают на лоб. Проверено:
        // выключаешь пак FA+Emissive — лишние глаза исчезают. Свои глаза у паука рисует ванильный
        // слой, ему эмиссия и не нужна.
        if (n.contains("spider")) return null;
        String emisName = n.substring(0, n.length() - 4) + "_e.png";
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return null;
            net.minecraft.resources.Identifier rl = net.minecraft.resources.Identifier.parse(emisName);
            Boolean has = EMIS_EXISTS.get(emisName);
            if (has == null) {   // есть ли такой файл в паках — спрашиваем ОДИН раз
                has = mc.getResourceManager().getResource(rl).isPresent();
                EMIS_EXISTS.put(emisName, has);
                if (has) net.vulkanmod.Initializer.LOGGER.info("[RT] светящийся слой: {}", emisName);
            }
            if (!has) return null;
            // Сам образ берём КАЖДЫЙ РАЗ заново: при смене набора ресурсов текстуры пересоздаются,
            // и закэшированный образ стал бы висячей ссылкой.
            var abs = mc.getTextureManager().getTexture(rl);
            if (abs != null && abs.getTexture() instanceof net.vulkanmod.render.engine.VkGpuTexture vk) {
                var glt = net.vulkanmod.gl.VkGlTexture.getTexture(vk.glId());
                if (glt != null) return glt.getVulkanImage();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Светящийся слой? Только явная эмиссия (паки: имя содержит emissive/glow).
     *
     * ⚠️ ВАНИЛЬНЫЙ ТАЙП "eyes" СЮДА БОЛЬШЕ НЕ ВХОДИТ. Им ваниль рисует глаза паука и эндермена, и я
     * его зажигал — но у паука светящиеся глаза вылезали НА ЛБУ, причём и с наборами ресурсов, и без
     * них. Геометрия этого слоя лежала там всегда, просто раньше она тонула в темноте: стоило её
     * зажечь, как ошибка стала видна. Гасим слой обратно — он остаётся, но не светится.
     * Цена: глаза эндермена тоже перестают гореть. Эмиссия из паков (_e) работает как работала.
     */
    private static boolean isEmissiveType(String n) {
        if (n == null) return false;
        String l = n.toLowerCase();
        return l.contains("emissive") || l.contains("glow");
    }

    /** Из WorldRenderer.setupRenderer: начался рендер мира. */
    // M8.123b: НЕТ ТРАССИРОВКИ — НЕТ СБОРА. С выключенным RT потребитель (endFrame из
    // recordFrameRebuild) не вызывается, а сборщики продолжали КОПИТЬ каждый кадр: accPtr
    // рос минутами, пока nmemRealloc не вернул NULL (без исключения!), и memCopy в ноль
    // ронял процесс (SIGSEGV в collectParticle, exit 6). Гейт — в точках входа.
    private static boolean rtActive() {
        return net.vulkanmod.Initializer.CONFIG.rtEnabled
                && net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported;
    }

    public static void beginLevel() { collecting = rtActive(); captureHand = false; quadSeen.clear(); }

    /** Из WorldRenderer.renderSectionLayer(TRANSLUCENT): сущности кадра отрисованы. */
    public static void endLevel() { collecting = false; }

    /** Из GameRendererM: началась отрисовка руки 1-го лица. */
    public static void beginHand() { collecting = rtActive(); captureHand = collecting; }

    // M8.126b: разовый дамп батчей окна РУКИ (клавиша F9) — тип, текстура, вершины.
    // Диагностика «картонной коробки»: какой путь отрисовки теряет текстуру предмета.
    private static int handDumpBudget = 0;
    public static void armHandDump() { handDumpBudget = 48; texProbeArmed = true; }

    // === M8.126e: ПРОБА ТЕКСЕЛЯ С GPU (по F9) ===
    // Симптом M8.103 («спрайт по его же координатам — пустота») для НОВОГО атласа items.png:
    // читаем 4x4 текселя из образа, который реально биндится шейдеру, по UV из дампа.
    // Пусто -> образ не тот/устарел (чинить идентичность атласа); золото -> копать шейдинг.
    private static volatile boolean texProbeArmed = false;
    private static void probeTexel(VulkanImage tex, float u, float v) {
        if (!texProbeArmed || tex == null) return;
        texProbeArmed = false;
        try {
            int px = Math.max(0, Math.min(tex.width  - 4, (int) (u * tex.width)));
            int py = Math.max(0, Math.min(tex.height - 4, (int) (v * tex.height)));
            var buf = AccelStruct.createBuffer(4 * 4 * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
            var cmd = net.vulkanmod.vulkan.device.DeviceManager.getComputeQueue().beginCommands();
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                int prevLayout = tex.getCurrentLayout();
                tex.transitionImageLayout(stack, cmd.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                var region = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(0).bufferRowLength(4).bufferImageHeight(4);
                region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).imageOffset().set(px, py, 0);
                region.get(0).imageExtent().set(4, 4, 1);
                vkCmdCopyImageToBuffer(cmd.getHandle(), tex.getId(),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buf.buffer, region);
                tex.transitionImageLayout(stack, cmd.getHandle(), prevLayout);
            }
            net.vulkanmod.vulkan.device.DeviceManager.getComputeQueue().submitCommands(cmd);
            net.vulkanmod.vulkan.device.DeviceManager.getComputeQueue().waitIdle();
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var pMap = stack.mallocPointer(1);
                vkMapMemory(net.vulkanmod.vulkan.Vulkan.getVkDevice(), buf.memory, 0, 64, 0, pMap);
                ByteBuffer bb = MemoryUtil.memByteBuffer(pMap.get(0), 64);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    int c = bb.getInt(i * 4);
                    sb.append(String.format("%08X ", c));
                }
                net.vulkanmod.Initializer.LOGGER.info(
                        "[RT] ПРОБА {} @({},{}) из {}x{} (фмт {}): {}",
                        tex.name, px, py, tex.width, tex.height, tex.format, sb);
                vkUnmapMemory(net.vulkanmod.vulkan.Vulkan.getVkDevice(), buf.memory);
            }
            AccelStruct.destroyBuffer(buf);
        } catch (Throwable t) {
            net.vulkanmod.Initializer.LOGGER.error("[RT] проба текселя: ", t);
        }
    }
    /** M8.126d: + габариты и диапазон UV — определить, КАКОЙ батч рисуется «коробкой»
     *  и куда смотрят его UV (однотонная область скина = ровная заливка). */
    private static void dumpHandBatch(String where, String type, VulkanImage tex, int vertexCount,
                                      ByteBuffer buf, int stride, int uvOff) {
        if (!captureHand || handDumpBudget <= 0) return;
        handDumpBudget--;
        float nx=1e9f, ny=1e9f, nz=1e9f, mx=-1e9f, my=-1e9f, mz=-1e9f;
        float nu=1e9f, nv=1e9f, mu=-1e9f, mv=-1e9f;
        int n = 0;
        if (buf != null && stride > 0) {
            long a = MemoryUtil.memAddress(buf) + buf.position();
            n = Math.min(vertexCount, buf.remaining() / stride);
            for (int v = 0; v < n; v++) {
                long p = a + (long) v * stride;
                float x = MemoryUtil.memGetFloat(p), y = MemoryUtil.memGetFloat(p + 4), z = MemoryUtil.memGetFloat(p + 8);
                nx = Math.min(nx, x); ny = Math.min(ny, y); nz = Math.min(nz, z);
                mx = Math.max(mx, x); my = Math.max(my, y); mz = Math.max(mz, z);
                float u = MemoryUtil.memGetFloat(p + uvOff), w = MemoryUtil.memGetFloat(p + uvOff + 4);
                nu = Math.min(nu, u); nv = Math.min(nv, w);
                mu = Math.max(mu, u); mv = Math.max(mv, w);
            }
        }
        net.vulkanmod.Initializer.LOGGER.info(String.format(
                "[RT] РУКА-батч [%s]: тип=%s, текстура=%s, вершин=%d, bbox=[%.2f..%.2f, %.2f..%.2f, %.2f..%.2f], uv=[%.3f..%.3f, %.3f..%.3f]",
                where, type != null ? type : "?", tex != null ? tex.name : "NULL -> фолбэк слот 0",
                vertexCount, nx, mx, ny, my, nz, mz, nu, mu, nv, mv));
    }
    /** Из GameRendererM: рука отрисована. */
    public static void endHand() { collecting = false; captureHand = false; }

    /** Из WorldRenderer.setupRenderer: позиция камеры кадра. */
    public static void setCamera(double x, double y, double z) {
        camX = x; camY = y; camZ = z; camSet = true;
    }

    // ⚠️ АТЛАСЫ — ТОЛЬКО СВЕЖИЕ, ПО ИМЕНИ (M8.72). Текстуру батча я беру из привязки в момент
    // отрисовки. Для скинов мобов это годится, а вот АТЛАСЫ игра пересобирает и заново создаёт при
    // смене набора ресурсов — и в руках у меня оставался УСТАРЕВШИЙ образ: спрайты в нём лежат по
    // старым местам. Наружу это вылезло на 3D-моделях предметов: UV указывали на спрайт пака, а в
    // старом атласе там было пусто, и предмет выходил ровной бледной заливкой («картонная коробка»).
    // Доказано замером: чтение заведомо известного текселя из ТОГО ЖЕ слота давало золото, а
    // спрайт пака по его же координатам — пустоту. Спрашиваем атлас у менеджера текстур по имени.
    private static VulkanImage freshAtlas(VulkanImage tex) {
        if (tex == null || tex.name == null || !tex.name.contains("textures/atlas/")) return tex;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return tex;
            var abs = mc.getTextureManager().getTexture(
                    net.minecraft.resources.Identifier.parse(tex.name));
            if (abs != null && abs.getTexture() instanceof net.vulkanmod.render.engine.VkGpuTexture vk) {
                var glt = net.vulkanmod.gl.VkGlTexture.getTexture(vk.glId());
                if (glt != null && glt.getVulkanImage() != null) return glt.getVulkanImage();
            }
        } catch (Throwable ignored) {}
        return tex;
    }

    /** Из Drawer.draw: скопировать вершины батча сущностей (NEW_ENTITY, QUADS). */
    public static void collect(ByteBuffer vertexData, int vertexCount) {
        // приоритет — текстура из RenderTypeM (точная); VTextureSelector — фолбэк
        VulkanImage tex = nextBatchTex != null ? nextBatchTex : VTextureSelector.getBoundTexture(0);
        tex = freshAtlas(tex);   // атлас — свежий, по имени (см. выше)
        String type = nextBatchType;
        nextBatchTex = null; nextBatchType = null;   // одноразовые: следующий батч принесёт свои
        int flags = captureHand ? HAND_FLAG : 0;
        if (isEmissiveType(type)) flags |= EMISSIVE_FLAG;
        // M8.126f: тайпы с куллингом — луч уважает обратные грани (вывернутые оболочки)
        if (type != null && type.contains("cull") && !type.contains("no_cull")) flags |= CULL_FLAG;
        dumpHandBatch("collect", type, tex, vertexCount, vertexData, 36, 16);   // M8.126b/d: F9-диагностика
        // M8.126e: проба текселя АТЛАСА ПРЕДМЕТОВ по UV первой вершины батча (один раз на F9)
        if (captureHand && texProbeArmed && tex != null && tex.name != null
                && tex.name.contains("atlas/items") && vertexCount > 0) {
            long a0 = MemoryUtil.memAddress(vertexData) + vertexData.position();
            probeTexel(tex, MemoryUtil.memGetFloat(a0 + 16), MemoryUtil.memGetFloat(a0 + 20));
        }
        append(vertexData, vertexCount, tex, flags);

        // И СРАЗУ — светящийся слой этого же меша, если у текстуры есть вариант _e.
        // Те же вершины: механизм слоёв опознает их как наложение и поднимет наружу.
        if ((flags & EMISSIVE_FLAG) == 0) {
            VulkanImage emis = emissiveSibling(tex);
            if (emis != null) append(vertexData, vertexCount, emis, flags | EMISSIVE_FLAG);
        }
    }

    /**
     * Из Drawer.draw: ПАРТИКЛЫ (формат PARTICLE, 28 Б: Position 3f @0, UV0 2f @12, Color 4ub @20,
     * UV2 2s @24). Перепаковываем в формат СУЩНОСТИ (NEW_ENTITY, 36 Б) — тогда весь готовый
     * конвейер (BLAS -> TLAS -> слоты текстур -> шейдинг) подхватывает их без переделки.
     *   NEW_ENTITY: pos 3f @0, color 4ub @12, uv 2f @16, overlay 2s @24, light 2s @28, normal @32
     */
    // --- Партиклы: КОПИЯ CPU-буфера из QuadParticleRenderState.prepare + окно отрисовки ---
    // ⚠️ USE-AFTER-FREE: в prepare() буфер живёт внутри try-with-resources (ByteBufferBuilder.close()),
    // а drawIndexed зовётся ПОЗЖЕ — к тому моменту память уже освобождена и переиспользована.
    // Читать её лениво нельзя: получались мусорные координаты (дым дёргался, летал, мерцал) и
    // вырожденные треугольники, от которых распухал BLAS -> просадка FPS. Копируем СРАЗУ.
    private static long partPtr = 0;
    private static int  partCap = 0, partBytes = 0;
    private static boolean inParticleRender = false;

    /** Из миксина: перехваченный MeshData.vertexBuffer(). КОПИРУЕМ немедленно — он вот-вот умрёт. */
    public static void setParticleVerts(ByteBuffer buf) {
        if (!rtActive()) { partBytes = 0; return; }   // M8.123b: без RT копить некому
        int n = buf.remaining();
        if (n <= 0) { partBytes = 0; return; }
        if (n > partCap) {
            partPtr = (partPtr == 0) ? MemoryUtil.nmemAlloc(n) : MemoryUtil.nmemRealloc(partPtr, n);
            // nmemRealloc при отказе возвращает NULL БЕЗ исключения — memCopy в ноль = SIGSEGV
            if (partPtr == 0) { partCap = 0; partBytes = 0; return; }
            partCap = n;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(buf), partPtr, n);
        partBytes = n;
    }
    public static void beginParticleRender() { inParticleRender = rtActive(); }   // M8.123b
    /** ⚠️ Копию НЕ обнуляем: render() зовётся ДВАЖДЫ за один prepare() (разные слои в каждом).
     *  Обнуление после первого вызова выбрасывало все слои второго — пропадали дым и ломание,
     *  а искры (они были в первом) оставались. Копия живёт до следующего prepare(). */
    public static void endParticleRender()   { inParticleRender = false; }

    /**
     * Из VkRenderPass.drawIndexed во время отрисовки партиклов: ОДИН СЛОЙ (у него своя текстура,
     * её уже поймал хук bindTexture). vertexOffset — базовая вершина слоя, indexCount — 6 на квад.
     */
    public static void collectParticleRange(int vertexOffset, int indexCount) {
        if (!inParticleRender || partBytes <= 0) return;
        int quads = indexCount / 6;
        if (quads <= 0) return;
        // Диапазон обязан лежать внутри скопированного буфера (иначе читали бы за его границей)
        long need = (long) (vertexOffset + quads * 4) * 28L;
        if (need > partBytes) return;
        collectParticle(partPtr, vertexOffset, quads * 4);
    }

    // === RT PATCH (M8.21c): ГЕОМЕТРИЯ ПОГОДЫ как партиклы ===
    private static long wPtr = 0; private static int wCap = 0;
    private static long weatherLogT = 0; private static int weatherQuads = 0;
    // КЛИП ДОЖДЯ ПО ВЫСОТЕ: капли не должны идти выше ОСНОВАНИЯ облаков (иначе «из воздуха»).
    // Гасим альфу вершин выше порога плавной полосой — это же даёт мягкий переход при подъёме
    // над тучами (заменяет прежний жёсткий гейт по высоте камеры).
    private static volatile float wCamY = 0f, wCapHi = 1e9f, wRamp = 20f;
    // M8.144j: радиус клипа дождя/снега (блоки). Дальше — квады ДРОПАЮТСЯ (дождь идёт стеной,
    // дальний тонет в тумане; меньше геометрии = дешевле все лучи). Край прячет туман дождя.
    private static final float WEATHER_RADIUS = 22f;
    public static void setWeatherClip(float camY, float capHi, float ramp) {
        wCamY = camY; wCapHi = capHi; wRamp = Math.max(ramp, 1f);
    }
    /** Из RenderTypeM.draw: батч дождя/снега (формат PARTICLE) + его текстура. Копируем вершины
     *  (meshData вот-вот закроется) и гоним через партикловый конвейер — дождь идёт в TLAS. */
    public static void collectWeather(java.nio.ByteBuffer buf, int vertexCount, VulkanImage tex) {
        if (!rtActive() || !camSet || buf == null || vertexCount <= 0) return;   // M8.123b
        int n = Math.min(vertexCount * 28, buf.remaining());
        if (n <= 0) return;
        if (n > wCap) { wPtr = (wPtr == 0) ? MemoryUtil.nmemAlloc(n) : MemoryUtil.nmemRealloc(wPtr, n); wCap = n; }
        MemoryUtil.memCopy(MemoryUtil.memAddress(buf), wPtr, n);
        int vc = Math.min(vertexCount, n / 28);
        // КЛИП: (1) по ВЫСОТЕ — альфа (offset 23) гаснет выше основания облаков; (2) по РАДИУСУ —
        // ДРОП квадов дальше WEATHER_RADIUS (дождь стеной, дальний тонет в тумане, а трассируется
        // впустую -> перф). Позиции camera-relative float (x@0, y@4, z@8; мир Y = camY + relY).
        // Уцелевшие квады компактим в начало буфера. M8.144j.
        final float wr2 = WEATHER_RADIUS * WEATHER_RADIUS;
        int quadsIn = vc / 4, outV = 0;
        for (int q = 0; q < quadsIn; q++) {
            long qp = wPtr + (long) q * 4 * 28;
            float rx = MemoryUtil.memGetFloat(qp);          // XZ квада относительно камеры (1-я вершина)
            float rz = MemoryUtil.memGetFloat(qp + 8);
            if (rx * rx + rz * rz > wr2) continue;          // дальше радиуса -> дроп квада
            long dst = wPtr + (long) outV * 28;
            for (int vi = 0; vi < 4; vi++) {
                long sp = qp + (long) vi * 28;
                float worldY = wCamY + MemoryUtil.memGetFloat(sp + 4);
                float hf = (wCapHi - worldY) / wRamp;
                hf = hf < 0f ? 0f : (hf > 1f ? 1f : hf);
                if (hf < 0.999f) {
                    int a = MemoryUtil.memGetByte(sp + 23) & 0xFF;
                    MemoryUtil.memPutByte(sp + 23, (byte) Math.round(a * hf));
                }
                if (dst != sp) MemoryUtil.memCopy(sp, dst, 28);   // сдвиг квада в компакт-позицию
                dst += 28;
            }
            outV += 4;
        }
        vc = outV;
        if (vc <= 0) return;                                 // всё за радиусом — нечего слать
        nextBatchTex = tex;
        int before = accBatches.size();
        weatherExtra = WEATHER_FLAG;         // пометить капли -> в шейдере свой (наш) оттенок
        collectParticle(wPtr, 0, vc);
        weatherExtra = 0;
        weatherQuads += vc / 4;   // фактически ушло в BLAS (после клипа по радиусу)
        long now = System.currentTimeMillis();
        if (now - weatherLogT > 3000) {
            weatherLogT = now;
            net.vulkanmod.Initializer.LOGGER.info("[RT] погода: захвачено ~{} квадов/3с (батчей +{})",
                    weatherQuads, accBatches.size() - before);
            weatherQuads = 0;
        }
    }

    public static void collectParticle(long srcBase, int firstVertex, int vertexCount) {
        // ⚠️ НЕ гейтим по `collecting`! Партиклы рисуются ПОСЛЕ слоя TRANSLUCENT, где endLevel()
        // закрывает фазовое окно, — и весь захват молча выбрасывался (диагностика показала: 986
        // квадов в кадре, все отброшены). Окно `collecting` нужно, чтобы не поймать руку и GUI,
        // а у партиклов гейт свой и точный: inParticleRender (только внутри их отрисовки).
        if (!camSet) return;
        VulkanImage tex = nextBatchTex != null ? nextBatchTex : VTextureSelector.getBoundTexture(0);
        nextBatchTex = null;
        try {
            int quads = vertexCount / 4;
            if (quads <= 0) return;
            int bytes = quads * 4 * STRIDE;
            if (accBytes + bytes > accCap) {
                int cap = Math.max(accBytes + bytes, Math.max(1 << 20, accCap * 2));
                accPtr = accPtr == 0 ? MemoryUtil.nmemAlloc(cap) : MemoryUtil.nmemRealloc(accPtr, cap);
                accCap = cap;
            }
            final int PSTRIDE = 28;
            long src = srcBase + (long) firstVertex * PSTRIDE;
            long dst = accPtr + accBytes;
            for (int v = 0; v < vertexCount; v++) {
                long sp = src + (long) v * PSTRIDE;
                long dp = dst + (long) v * STRIDE;
                // позиция (3 float)
                MemoryUtil.memPutInt(dp,      MemoryUtil.memGetInt(sp));
                MemoryUtil.memPutInt(dp + 4,  MemoryUtil.memGetInt(sp + 4));
                MemoryUtil.memPutInt(dp + 8,  MemoryUtil.memGetInt(sp + 8));
                // цвет: PARTICLE @20 -> NEW_ENTITY @12
                MemoryUtil.memPutInt(dp + 12, MemoryUtil.memGetInt(sp + 20));
                // uv (2 float): PARTICLE @12 -> NEW_ENTITY @16
                MemoryUtil.memPutInt(dp + 16, MemoryUtil.memGetInt(sp + 12));
                MemoryUtil.memPutInt(dp + 20, MemoryUtil.memGetInt(sp + 16));
                // overlay: NO_OVERLAY = pack(0, 10) = 10 << 16 (иначе моб-оверлей покрасит партикл)
                MemoryUtil.memPutInt(dp + 24, 10 << 16);
                // свет (2 short): PARTICLE @24 -> NEW_ENTITY @28
                MemoryUtil.memPutInt(dp + 28, MemoryUtil.memGetInt(sp + 24));
                // нормаль не нужна — в RT считаем геометрическую по треугольнику
                MemoryUtil.memPutInt(dp + 32, 0);
            }
            accBytes += bytes;
            accBatches.add(new Batch(quads, tex, PARTICLE_FLAG | weatherExtra));
        } catch (Throwable ignored) {}
    }

    // === МОЛНИЯ (M8.46) ===
    // Ваниль рисует болт через RenderType "lightning" форматом POSITION_COLOR (16 Б: 3 float + 4 байта
    // цвета) — БЕЗ текстуры и без света, это чистая эмиссивная геометрия. Наш захват сущностей ждёт
    // NEW_ENTITY и её не видел, поэтому молнии в TLAS не было вовсе: она не рисовалась, не светила и
    // не отражалась в воде. Здесь переливаем её вершины в наш формат и метим BOLT_FLAG — шейдер даст
    // им яркую эмиссию вместо выборки из атласа.
    private static long bPtr = 0; private static int bCap = 0;

    /**
     * КАРТА В РУКЕ (M8.80). Карта рисуется НЕ как обычный предмет: у неё своя динамическая текстура и
     * СВОЙ формат вершин (POSITION_COLOR_TEX_LIGHTMAP, 28 Б), а мой перехват берёт только формат
     * сущностей (NEW_ENTITY, 36 Б) и всё прочее пропускает. Оттого карта в трассировку не попадала
     * вовсе — в руке её просто не было. Перепаковываем в формат сущности, как уже делали с молнией
     * и дождём: тогда весь готовый конвейер (BLAS -> TLAS -> текстуры -> шейдинг) подхватывает её сам.
     *   POSITION_COLOR_TEX_LIGHTMAP: pos 3f @0, color 4ub @12, uv0 2f @16, light 2s @24
     *   NEW_ENTITY:                  pos 3f @0, color 4ub @12, uv0 2f @16, overlay 2s @24,
     *                                light 2s @28, normal 3b @32
     * Нормаль оставляем нулевой: она нам не нужна — нормаль грани шейдер считает по геометрии.
     */
    public static void collectFlat(java.nio.ByteBuffer buf, int vertexCount, VulkanImage tex, int extraFlags) {
        if (!camSet || !collecting || buf == null || vertexCount < 4) return;
        dumpHandBatch("flat", null, tex, vertexCount, buf, 28, 16);   // M8.126b/d: F9-диагностика
        final int MSTRIDE = 28;
        int need = vertexCount * MSTRIDE;
        if (need > buf.remaining()) return;
        try {
            int quads = vertexCount / 4;
            int bytes = quads * 4 * STRIDE;
            if (accBytes + bytes > accCap) {
                int cap = Math.max(accBytes + bytes, Math.max(1 << 20, accCap * 2));
                accPtr = accPtr == 0 ? MemoryUtil.nmemAlloc(cap) : MemoryUtil.nmemRealloc(accPtr, cap);
                accCap = cap;
            }
            long src = MemoryUtil.memAddress(buf);
            long dst = accPtr + accBytes;
            for (int v = 0; v < quads * 4; v++) {
                long sp = src + (long) v * MSTRIDE;
                long dp = dst + (long) v * STRIDE;
                MemoryUtil.memCopy(sp, dp, 24);                       // позиция + цвет + UV
                // ⚠️ ОВЕРЛЕЙ = «НЕТ ОВЕРЛЕЯ», А НЕ НОЛЬ. В этом поле игра держит признак урона, и
                // НОЛЬ означает ровно «моба ударили» — шейдер честно красит такую грань в красное.
                // Отсюда был красный текст на табличках и красные пятна на карте. Ванильное значение
                // «нет оверлея» = 0x000A0000 (u=0, v=10).
                MemoryUtil.memPutInt(dp + 24, 0x000A0000);            // overlay: НЕТ урона
                MemoryUtil.memPutInt(dp + 28, MemoryUtil.memGetInt(sp + 24));   // свет
                MemoryUtil.memPutInt(dp + 32, 0);                     // нормаль (считается по геометрии)
            }
            // M8.121: карта/текст в руке поднимаются вместе с рукой (см. HAND_LIFT).
            if (captureHand)
                for (int v = 0; v < quads * 4; v++) {
                    long vp = dst + (long) v * STRIDE + 4;
                    MemoryUtil.memPutFloat(vp, MemoryUtil.memGetFloat(vp) + HAND_LIFT);
                }
            accBytes += bytes;
            accBatches.add(new Batch(quads, tex, (captureHand ? HAND_FLAG : 0) | extraFlags));
        } catch (Throwable ignored) {}
    }

    public static void collectLightning(java.nio.ByteBuffer buf, int vertexCount) {
        if (!collecting || !camSet || buf == null || vertexCount < 4) return;   // M8.123b: гейт сбора
        final int LSTRIDE = 16;                       // POSITION_COLOR
        int need = vertexCount * LSTRIDE;
        if (need > buf.remaining()) return;
        if (need > bCap) { bPtr = (bPtr == 0) ? MemoryUtil.nmemAlloc(need) : MemoryUtil.nmemRealloc(bPtr, need); bCap = need; }
        MemoryUtil.memCopy(MemoryUtil.memAddress(buf), bPtr, need);
        try {
            int quads = vertexCount / 4;
            if (quads <= 0) return;
            int bytes = quads * 4 * STRIDE;
            if (accBytes + bytes > accCap) {
                int cap = Math.max(accBytes + bytes, Math.max(1 << 20, accCap * 2));
                accPtr = accPtr == 0 ? MemoryUtil.nmemAlloc(cap) : MemoryUtil.nmemRealloc(accPtr, cap);
                accCap = cap;
            }
            // ТОЛЩИНА БОЛТА. Ванильная молния — широкий столб (её рисовали под растр с прозрачностью,
            // а у нас она сплошная и эмиссивная, оттого кажется бревном). Ось болта — среднее XZ по
            // всем вершинам (он вертикальный), и мы поджимаем к ней каждую вершину по горизонтали.
            final float THIN = 0.45f;
            int nv = quads * 4;
            double sx = 0, sz = 0;
            for (int v = 0; v < nv; v++) {
                long sp = bPtr + (long) v * LSTRIDE;
                sx += MemoryUtil.memGetFloat(sp);
                sz += MemoryUtil.memGetFloat(sp + 8);
            }
            float axisX = (float) (sx / nv), axisZ = (float) (sz / nv);

            long dst = accPtr + accBytes;
            for (int v = 0; v < nv; v++) {
                long sp = bPtr + (long) v * LSTRIDE;
                long dp = dst  + (long) v * STRIDE;
                float px = MemoryUtil.memGetFloat(sp);
                float pz = MemoryUtil.memGetFloat(sp + 8);
                MemoryUtil.memPutFloat(dp,     axisX + (px - axisX) * THIN);   // позиция, поджатая к оси
                MemoryUtil.memPutInt(dp + 4,   MemoryUtil.memGetInt(sp + 4));  // высота как есть
                MemoryUtil.memPutFloat(dp + 8, axisZ + (pz - axisZ) * THIN);
                MemoryUtil.memPutInt(dp + 12, MemoryUtil.memGetInt(sp + 12));  // цвет @12 -> @12
                MemoryUtil.memPutInt(dp + 16, 0);                              // uv не нужен
                MemoryUtil.memPutInt(dp + 20, 0);
                MemoryUtil.memPutInt(dp + 24, 10 << 16);                       // NO_OVERLAY
                MemoryUtil.memPutInt(dp + 28, 0xF000F0);                       // свет: полный
                MemoryUtil.memPutInt(dp + 32, 0);
            }
            accBytes += bytes;
            accBatches.add(new Batch(quads, null, BOLT_FLAG));
        } catch (Throwable ignored) {}
    }

    /** Из RenderTypeM.draw (captureOnly): вершины ТЕЛА ИГРОКА + его скин. */
    public static void collectPlayer(ByteBuffer vertexData, int vertexCount, VulkanImage tex) {
        append(vertexData, vertexCount, tex, PLAYER_FLAG);
    }

    private static void append(ByteBuffer vertexData, int vertexCount, VulkanImage tex, int flags) {
        // Только окно рендера МИРА (collecting). GUI-превью мобов/предметов рисуются ПОСЛЕ
        // endLevel (collecting=false) => и так отсекаются. Гейт по screen!=null убран: он
        // рубил мировые сущности при открытом меню (мир за меню рисуется, а мобы пропадали).
        if (!camSet || !collecting) return;
        // ⚠️ ВАНИЛЬНАЯ БЛОБ-ТЕНЬ (misc/shadow.png) — В TLAS ЕЙ НЕ МЕСТО. Это плоский тёмный квад,
        // который игра кладёт под мобом вместо настоящей тени. Попав в трассировку, он становится
        // РЕАЛЬНОЙ ГЕОМЕТРИЕЙ и сам отбрасывает тень — под курицей вырастает огромная чёрная клякса.
        // Мы гасим их настройкой (entityShadows=false), но на Windows игра рисует их всё равно, так
        // что полагаться на опцию нельзя: отсекаем по текстуре — это работает при любых настройках.
        if (tex != null && tex.name != null && tex.name.contains("shadow")) return;

        try {
            int quads = vertexCount / 4;
            int bytes = quads * 4 * STRIDE;
            if (bytes <= 0) return;
            if (accBytes + bytes > accCap) {
                int cap = Math.max(accBytes + bytes, Math.max(1 << 20, accCap * 2));
                accPtr = accPtr == 0 ? MemoryUtil.nmemAlloc(cap) : MemoryUtil.nmemRealloc(accPtr, cap);
                accCap = cap;
            }
            long dst = accPtr + accBytes;
            MemoryUtil.memCopy(MemoryUtil.memAddress(vertexData), dst, bytes);

            // M8.121: руку поднимаем в мире (координаты камерно-относительные, +Y = мир +Y).
            if ((flags & HAND_FLAG) != 0)
                for (int v = 0; v < quads * 4; v++) {
                    long vp = dst + (long) v * STRIDE + 4;
                    MemoryUtil.memPutFloat(vp, MemoryUtil.memGetFloat(vp) + HAND_LIFT);
                }

            // Каждый квад: не рисовали ли такой же в этом кадре? Если да — это верхний слой,
            // поднимаем его наружу по нормали (нормаль лежит в NEW_ENTITY по смещению 32,
            // 3 signed byte). Позиции слоёв совпадают бит в бит — сравниваем прямо по битам.
            for (int q = 0; q < quads; q++) {
                long q0 = dst + (long) q * 4 * STRIDE;
                // ⚠️ ХЕШ НЕ ЗАВИСИТ ОТ ПОРЯДКА ВЕРШИН (M8.70). Раньше углы квада сворачивались в хеш
                // ПОДРЯД — и два совпадающих в пространстве квада, у которых углы перечислены с другого
                // угла или в другую сторону, давали РАЗНЫЕ хеши. Наложение не опознавалось, слои
                // оставались в одной плоскости и спорили. Так сломалась овца: её подшёрсток — тот же
                // самый куб, что и тело (те же координаты, нулевое раздутие), но перечислен иначе.
                // Хешируем каждый угол отдельно и складываем — сумма от порядка не зависит.
                long h = 0L;
                for (int v = 0; v < 4; v++) {
                    long vp = q0 + (long) v * STRIDE;
                    long vh = 1469598103934665603L;
                    for (int c = 0; c < 3; c++) {
                        vh ^= MemoryUtil.memGetInt(vp + c * 4L) & 0xFFFFFFFFL;
                        vh *= 1099511628211L;
                    }
                    h += vh;
                }
                int depth = quadSeen.merge(h, 1, Integer::sum) - 1;   // 0 = база, 1+ = слой поверх
                if (depth <= 0) continue;
                float lift = depth * LAYER_STEP;

                // ⚠️ НАПРАВЛЕНИЕ ПОДЪЁМА — ИЗ САМОЙ ГЕОМЕТРИИ, а не из вершинной нормали.
                // Вершинная нормаль лежит в NEW_ENTITY по смещению 32 (3 signed byte), но полагаться
                // на неё нельзя: окажись она нулевой (или не на том смещении) — весь подъём молча
                // умножается на ноль, слои остаются в одной плоскости, и рябь никуда не девается,
                // а я думаю, что «фикс не помог». Нормаль квада считаем векторным произведением его
                // рёбер — она ненулевая всегда. Вершинную берём лишь как ориентир для ЗНАКА: наружу
                // от модели, а не внутрь.
                float ax = MemoryUtil.memGetFloat(q0),               ay = MemoryUtil.memGetFloat(q0 + 4),
                      az = MemoryUtil.memGetFloat(q0 + 8);
                float bx = MemoryUtil.memGetFloat(q0 + STRIDE),      by = MemoryUtil.memGetFloat(q0 + STRIDE + 4),
                      bz = MemoryUtil.memGetFloat(q0 + STRIDE + 8);
                float cx = MemoryUtil.memGetFloat(q0 + 2L * STRIDE), cy = MemoryUtil.memGetFloat(q0 + 2L * STRIDE + 4),
                      cz = MemoryUtil.memGetFloat(q0 + 2L * STRIDE + 8);
                float e1x = bx - ax, e1y = by - ay, e1z = bz - az;
                float e2x = cx - ax, e2y = cy - ay, e2z = cz - az;
                float gx = e1y * e2z - e1z * e2y;
                float gy = e1z * e2x - e1x * e2z;
                float gz = e1x * e2y - e1y * e2x;
                float glen = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
                if (glen < 1e-9f) continue;                       // вырожденный квад — пропускаем
                gx /= glen; gy /= glen; gz /= glen;

                // Знак: если вершинная нормаль есть — она задаёт «наружу»; если её нет, оставляем как есть.
                float vnx = MemoryUtil.memGetByte(q0 + 32) / 127f;
                float vny = MemoryUtil.memGetByte(q0 + 33) / 127f;
                float vnz = MemoryUtil.memGetByte(q0 + 34) / 127f;
                if (vnx * vnx + vny * vny + vnz * vnz > 0.01f
                        && (gx * vnx + gy * vny + gz * vnz) < 0f) { gx = -gx; gy = -gy; gz = -gz; }

                // ⚠️ ЩЕЛЬ НА РЁБРАХ. Поднимая КАЖДУЮ грань вдоль ЕЁ нормали, мы разводим соседние
                // грани куба: на ребре они перестают смыкаться, и в зазор проглядывает слой под ними
                // (у жителя — голая кожа на углах, одежда «не цельная»). Поэтому вместе с подъёмом
                // расширяем квад в его же плоскости на ту же величину: грани заходят друг за друга и
                // стык закрывается. Текстура при этом растягивается на доли процента — глазу никак.
                float ux = bx - ax, uy = by - ay, uz = bz - az;          // ребро v0->v1
                float ulen = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                float dx = MemoryUtil.memGetFloat(q0 + 3L * STRIDE)     - ax;   // ребро v0->v3
                float dy = MemoryUtil.memGetFloat(q0 + 3L * STRIDE + 4) - ay;
                float dz = MemoryUtil.memGetFloat(q0 + 3L * STRIDE + 8) - az;
                float dlen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (ulen > 1e-6f) { ux /= ulen; uy /= ulen; uz /= ulen; } else { ux = uy = uz = 0f; }
                if (dlen > 1e-6f) { dx /= dlen; dy /= dlen; dz /= dlen; } else { dx = dy = dz = 0f; }

                // знаки расширения по углам квада: v0(-,-) v1(+,-) v2(+,+) v3(-,+)
                final float[] su = {-1f, 1f, 1f, -1f};
                final float[] sv = {-1f, -1f, 1f, 1f};
                for (int v = 0; v < 4; v++) {
                    long vp = q0 + (long) v * STRIDE;
                    float ox = gx * lift + (ux * su[v] + dx * sv[v]) * lift;
                    float oy = gy * lift + (uy * su[v] + dy * sv[v]) * lift;
                    float oz = gz * lift + (uz * su[v] + dz * sv[v]) * lift;
                    MemoryUtil.memPutFloat(vp,     MemoryUtil.memGetFloat(vp)     + ox);
                    MemoryUtil.memPutFloat(vp + 4, MemoryUtil.memGetFloat(vp + 4) + oy);
                    MemoryUtil.memPutFloat(vp + 8, MemoryUtil.memGetFloat(vp + 8) + oz);
                }
            }


            accBytes += bytes;
            accBatches.add(new Batch(quads, tex, flags));
        } catch (Throwable ignored) {
            // сбор сущностей никогда не должен ронять рендер
        }
    }

    /** Конец кадра (из RtWorld.tick): зафиксировать кадр, очистить накопление. */
    static void endFrame() {
        if (frameCap < accBytes) {
            int cap = Math.max(accBytes, 1 << 20);
            framePtr = framePtr == 0 ? MemoryUtil.nmemAlloc(cap) : MemoryUtil.nmemRealloc(framePtr, cap);
            // M8.123b: отказ аллокации = NULL без исключения; кадр без сущностей лучше краша
            if (framePtr == 0) { frameCap = 0; frameBytes = 0; accBytes = 0; accBatches.clear(); return; }
            frameCap = cap;
        }
        // === M8.146: ОСАДКИ — В КОНЕЦ БУФЕРА (под отдельный BLAS без теней/отражений) ===
        // Копируем ДВУМЯ проходами: сперва все НЕ-погодные батчи, затем погодные. Тогда квады
        // осадков лежат непрерывно в хвосте, и из ЭТОГО ЖЕ буфера строится второй BLAS (вершины
        // со смещением) со своей маской. Порядок accBatches правим так же — иначе таблица
        // «квад -> слот текстуры» съедет (инвариант ниже это и ловит).
        if (accBytes > 0) {
            long dst = 0;
            ArrayList<Batch> ordered = new ArrayList<>(accBatches.size());
            for (int pass = 0; pass < 2; pass++) {
                long off = 0;
                for (Batch b : accBatches) {
                    int bytes = b.quads() * 4 * STRIDE;
                    boolean isW = (b.flags() & WEATHER_FLAG) != 0;
                    if ((pass == 1) == isW) {            // pass 0 -> не погода, pass 1 -> погода
                        MemoryUtil.memCopy(accPtr + off, framePtr + dst, bytes);
                        dst += bytes;
                        ordered.add(b);
                    }
                    off += bytes;
                }
                if (pass == 0) frameWeatherStart = (int) (dst / (STRIDE * 4L));   // первый квад осадков
            }
            accBatches.clear();
            accBatches.addAll(ordered);
        } else {
            frameWeatherStart = 0;
        }
        frameBytes = accBytes;
        frameWeatherCount = Math.max(frameBytes / (STRIDE * 4) - frameWeatherStart, 0);

        // Таблица «квад -> слот текстуры» + список уникальных текстур кадра.
        int totalQuads = frameBytes / (STRIDE * 4);
        // ⚠️ ИНВАРИАНТ: сумма квадов по батчам ОБЯЗАНА совпасть с числом квадов в буфере. Если нет —
        // таблица «квад -> текстура» съезжает, и квады получают ЧУЖОЙ слот: предмет начинает читать
        // тексель из скина игрока и становится ровной заливкой («картонная коробка»).
        int sumQ = 0;
        for (Batch b : accBatches) sumQ += b.quads();
        if (sumQ != totalQuads && !mismatchLogged) {
            mismatchLogged = true;
            net.vulkanmod.Initializer.LOGGER.error(
                    "[RT] РАССОГЛАСОВАНИЕ таблицы текстур: квадов в буфере {}, а по батчам {}",
                    totalQuads, sumQ);
        }
        if (frameQuadSlots.length < totalQuads) frameQuadSlots = new int[totalQuads];
        ArrayList<VulkanImage> texList = new ArrayList<>();
        int q = 0;
        for (Batch b : accBatches) {
            int slot = 0;
            if (b.tex() != null) {
                slot = texList.indexOf(b.tex());
                if (slot < 0) {
                    if (texList.size() < MAX_TEX) { texList.add(b.tex()); slot = texList.size() - 1; }
                    else {                                           // переполнение — в слот 0 (чужая текстура!)
                        if (overflowTex == null) overflowTex = new ArrayList<>();
                        if (!overflowTex.contains(b.tex())) overflowTex.add(b.tex());
                        slot = 0;
                    }
                }
            } else if (!texList.isEmpty()) {
                slot = 0;
            }
            int val = slot | b.flags();   // биты 31/30 = тело игрока / рука
            if ((b.flags() & WEATHER_FLAG) != 0) frameWeatherSlot = slot;   // M8.146: у осадков слот один на кадр
            for (int i = 0; i < b.quads() && q < totalQuads; i++) frameQuadSlots[q++] = val;
        }
        frameTex = texList.toArray(new VulkanImage[0]);

        // Замер вместо догадок: сколько разных текстур сущностей просит кадр и упираемся ли в потолок.
        if (texList.size() > peakTex) peakTex = texList.size();
        long now = System.currentTimeMillis();
        if (overflowTex != null) {
            if (now - lastTexLog > 5000) {
                lastTexLog = now;
                net.vulkanmod.Initializer.LOGGER.error(
                        "[RT] таблица текстур ПЕРЕПОЛНЕНА: влезло {}, за бортом ещё {} уникальных "
                                + "(они читают ЧУЖУЮ текстуру!)", MAX_TEX, overflowTex.size());
            }
            overflowTex = null;
        } else if (now - lastTexLog > 30000) {
            lastTexLog = now;
            net.vulkanmod.Initializer.LOGGER.info("[RT] текстур сущностей в кадре: {} (пик {}, потолок {})",
                    texList.size(), peakTex, MAX_TEX);
        }

        fCamX = camX; fCamY = camY; fCamZ = camZ;
        accBytes = 0;
        accBatches.clear();
    }

    private static ArrayList<VulkanImage> overflowTex = null;
    private static int peakTex = 0;
    private static long lastTexLog = 0;

    /**
     * M8.93 ПРЕДМЕТ В РУКЕ (и блочные модели предметов) — формат BLOCK, 32 Б.
     *
     * ⚠️ ПОЧЕМУ КУСТ В РУКЕ БЫЛ НЕВИДИМ. Мы ловили только NEW_ENTITY (36 Б), а предмет в руке
     * рисуется КАК БЛОК: 32 байта, без поля оверлея. Его вершины к нам просто не доходили — в
     * трассировке рука держала пустоту. Замер выброшенных форматов это и показал.
     *
     *   BLOCK:      pos 3f @0, color 4ub @12, uv0 2f @16, light 2s @24, normal 3b @28
     *   NEW_ENTITY: pos 3f @0, color 4ub @12, uv0 2f @16, overlay 2s @24, light 2s @28, normal 3b @32
     */
    private static long blkPtr = 0;
    private static int blkCap = 0;

    public static void collectBlock(java.nio.ByteBuffer buf, int vertexCount, VulkanImage tex) {
        if (!camSet || !collecting || buf == null || vertexCount < 4) return;
        final int MSTRIDE = 32;
        if (vertexCount * MSTRIDE > buf.remaining()) return;
        try {
            int need = vertexCount * STRIDE;
            if (need > blkCap) {
                blkPtr = blkPtr == 0 ? MemoryUtil.nmemAlloc(need) : MemoryUtil.nmemRealloc(blkPtr, need);
                blkCap = need;
            }
            long src = MemoryUtil.memAddress(buf);
            for (int v = 0; v < vertexCount; v++) {
                long sp = src + (long) v * MSTRIDE;
                long dp = blkPtr + (long) v * STRIDE;
                MemoryUtil.memCopy(sp, dp, 24);                                 // позиция + цвет + UV
                MemoryUtil.memPutInt(dp + 24, 0x000A0000);                      // overlay: НЕТ урона
                MemoryUtil.memPutInt(dp + 28, MemoryUtil.memGetInt(sp + 24));   // свет
                MemoryUtil.memPutInt(dp + 32, MemoryUtil.memGetInt(sp + 28));   // нормаль
            }
            // ⚠️ ЧЕРЕЗ append, А НЕ НАПРЯМУЮ В БУФЕР. У плоского предмета передняя и задняя грани
            // лежат В ОДНОЙ ПЛОСКОСТИ: луч на каждом пикселе выбирал случайную из двух, и текстура
            // шла крапчатой мешаниной. append разводит совпадающие квады по нормали — тот самый
            // механизм, которым чинили слои одежды у жителей.
            append(MemoryUtil.memByteBuffer(blkPtr, need), vertexCount, tex,
                    captureHand ? HAND_FLAG : 0);
        } catch (Throwable ignored) {}
    }

    // ЗАМЕР (M8.93): какие форматы вершин мы ВЫБРАСЫВАЕМ в окне рендера мира. Предмет в руке
    // (куст) в трассировке не виден вовсе — значит его вершины к нам не доходят. Логируем раз в 3 с.
    private static final java.util.HashMap<String, Integer> skipped = new java.util.HashMap<>();
    private static long skipLogAt = 0;

    public static void logSkipped(com.mojang.blaze3d.vertex.VertexFormat fmt, int vertexCount) {
        if (!collecting) return;   // только окно рендера МИРА (рука рисуется в нём же)
        String key = fmt.toString() + " (" + fmt.getVertexSize() + " Б)";
        skipped.merge(key, vertexCount / 4, Integer::sum);
        long now = System.currentTimeMillis();
        if (now - skipLogAt > 3000 && !skipped.isEmpty()) {
            skipLogAt = now;
            net.vulkanmod.Initializer.LOGGER.info("[RT] ВЫБРОШЕНО из трассировки: {}", skipped);
            skipped.clear();
        }
    }

    static int quads()             { return frameBytes / (STRIDE * 4); }
    static long ptr()              { return framePtr; }
    static int bytes()             { return frameBytes; }
    static int[] quadSlots()       { return frameQuadSlots; }
    // M8.146: диапазон квадов ОСАДКОВ в хвосте буфера (для отдельного weather-BLAS) + их слот текстуры
    static int weatherStart()      { return frameWeatherStart; }
    static int weatherQuads()      { return frameWeatherCount; }
    static int weatherSlot()       { return frameWeatherSlot; }
    static VulkanImage[] textures(){ return frameTex; }
    static double camXf()          { return fCamX; }
    static double camYf()          { return fCamY; }
    static double camZf()          { return fCamZ; }
}
