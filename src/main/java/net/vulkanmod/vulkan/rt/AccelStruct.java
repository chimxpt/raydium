package net.vulkanmod.vulkan.rt;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * M8.2 — низкоуровневая машинерия аппаратных структур ускорения (BLAS/TLAS).
 *
 * Адаптировано из нашего рабочего прототипа rt-engine (RayQueryScene) под VulkanMod:
 *   * девайс           — {@link Vulkan#getVkDevice()};
 *   * подача команд     — compute-очередь VulkanMod
 *                         ({@code DeviceManager.getComputeQueue().beginCommands()});
 *   * буферы AS/scratch — «сырые» (vkCreateBuffer + vkAllocateMemory с флагом
 *                         device address), как в прототипе.
 *
 * Это фундамент: конкретные строители (BLAS секции, TLAS мира) собирают
 * VkAccelerationStructureBuildGeometryInfoKHR и зовут {@link #build}.
 */
public class AccelStruct {
    public long handle;          // VkAccelerationStructureKHR
    public long buffer;          // буфер, в котором ЖИВЁТ структура (нельзя освобождать раньше неё)
    public long memory;
    public long deviceAddress;   // адрес самой структуры (для ссылки из TLAS/шейдера)
    RawBuffer buf;               // тот же буфер объектом — чтобы вернуть его В ПУЛ, а не уничтожить

    /** Сырой буфер: хэндл + память + (опц.) адрес устройства. */
    public static class RawBuffer {
        public long buffer, memory, address;
        // Смещение внутри ПЛИТЫ (см. суб-аллокатор ниже). slab < 0 -> буфер отдельный, смещение 0.
        public long offset;
        int slab = -1;
        // Ёмкость и параметры создания — нужны, чтобы буфер можно было ВЕРНУТЬ В ПУЛ и выдать снова.
        // capacity == 0 -> буфер не из пула (разовый), его освобождают обычным destroyBuffer.
        long capacity; int usage, props; boolean addressable;
    }

    // ==================== ПУЛ БУФЕРОВ (M8.58) ====================
    // ⚠️ ПОЧЕМУ. На каждую секцию мира мы делали ТРИ vkAllocateMemory (буфер структуры, scratch,
    // копия вершин). При прогрузке чанков это сотни выделений памяти ЯДРА за кадр. На Linux драйвер
    // это проглатывает, а на Windows каждое такое выделение идёт через WDDM и стоит дорого — отсюда
    // фризы при отбегании от точки, которых на CachyOS нет.
    //
    // ⚠️ ЧТО ДЕЛАЕМ. Буферы больше не уничтожаются, а возвращаются в пул и выдаются снова. Секции
    // при ходьбе умирают ровно с той же скоростью, с какой рождаются, поэтому в установившемся
    // режиме новых выделений — НОЛЬ. Размер округляем вверх до кратного 64 КБ: так буфер подходит
    // не только под свой прежний размер (иначе совпадений почти не будет), а перерасход ограничен
    // 64 КБ на буфер. Адрес устройства у переиспользованного буфера тот же — он привязан к буферу.
    private static final long GRAN   = 64L * 1024;             // шаг размеров: 64 КБ
    private static final long BUDGET = 512L * 1024 * 1024;     // сколько держим «про запас» (сверх — рушим)

    private record PoolKey(int usage, int props, long capacity) {}
    private static final java.util.HashMap<PoolKey, java.util.ArrayDeque<RawBuffer>> POOL = new java.util.HashMap<>();
    private static long pooledBytes = 0;
    private static long allocCount = 0, reuseCount = 0;   // счётчики для лога: реальные выделения vs попадания в пул

    /** Взять буфер из пула или создать новый. Отдавать обратно — releaseBuffer. */
    // ЗАМЕР (M8.90): куда уходит время при прогрузке чанков. Промах пула = дорогая аллокация памяти.
    public static long missNs = 0, sizesNs = 0, createAsNs = 0, buildCmdNs = 0, addrNs = 0;
    public static long acquireNs = 0;   // ВСЁ время acquireBuffer (hit-путь пула был слепым пятном)
    public static int missCount = 0, hitCount = 0;

    public static RawBuffer acquireBuffer(long size, int usage, int memProps, boolean wantAddress) {
        long __t0 = System.nanoTime();
        try {
            return acquireBuffer0(size, usage, memProps, wantAddress);
        } finally { acquireNs += System.nanoTime() - __t0; }
    }
    private static RawBuffer acquireBuffer0(long size, int usage, int memProps, boolean wantAddress) {
        // ⚠️ ПАМЯТЬ ВИДЕОКАРТЫ — ИЗ ПЛИТЫ (суб-аллокация). Именно на ней мы теряли кадры: каждая
        // новая секция требовала два вызова драйвера. Теперь это арифметика по списку свободных
        // блоков. Буферы, которые мы отображаем в память процессора (host-visible: таблицы, staging),
        // остаются отдельными — их единицы, и им нужна своя VkDeviceMemory для vkMapMemory.
        if ((memProps & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) {
            hitCount++;
            return slabAlloc(size);
        }
        long cap = ((size + GRAN - 1) / GRAN) * GRAN;
        PoolKey key = new PoolKey(usage, memProps, cap);
        java.util.ArrayDeque<RawBuffer> free = POOL.get(key);
        if (free != null && !free.isEmpty()) {
            RawBuffer b = free.pop();
            pooledBytes -= b.capacity;
            reuseCount++;
            hitCount++;
            return b;
        }
        long t0 = System.nanoTime();
        RawBuffer b = createBuffer(cap, usage, memProps, wantAddress);
        b.capacity = cap; b.usage = usage; b.props = memProps; b.addressable = wantAddress;
        allocCount++;
        missNs += System.nanoTime() - t0;   // ЗАМЕР: промах пула = vkCreateBuffer + vkAllocateMemory
        missCount++;
        return b;
    }

    /** Вернуть буфер в пул (или уничтожить, если он не из пула / запас переполнен). */
    public static void releaseBuffer(RawBuffer b) {
        if (b == null) return;
        if (b.slab >= 0) { slabFree(b); return; }   // кусок плиты — обратно в её список свободных
        if (b.capacity == 0 || pooledBytes + b.capacity > BUDGET) {   // не из пула или запас полон
            destroyBuffer(b);
            return;
        }
        POOL.computeIfAbsent(new PoolKey(b.usage, b.props, b.capacity),
                k -> new java.util.ArrayDeque<>()).push(b);
        pooledBytes += b.capacity;
    }

    /** Для лога: сколько РЕАЛЬНЫХ выделений памяти сделано и сколько раз буфер пришёл из пула. */
    public static long allocCount() { return allocCount; }
    public static long reuseCount() { return reuseCount; }
    public static long pooledMB()   { return pooledBytes >> 20; }

    /** Освободить весь запас (выход из мира). */
    public static void clearPool() {
        for (java.util.ArrayDeque<RawBuffer> d : POOL.values())
            for (RawBuffer b : d) destroyBuffer(b);
        POOL.clear();
        pooledBytes = 0;
        VkDevice device = Vulkan.getVkDevice();
        for (Slab sl : slabs) {
            vkDestroyBuffer(device, sl.buffer, null);
            vkFreeMemory(device, sl.memory, null);
        }
        slabs.clear();
    }

    // ==================== СУБ-АЛЛОКАЦИЯ ПАМЯТИ (M8.90) ====================
    // ⚠️ ЗАЧЕМ. Замер прогрузки чанков сказал прямо: 80-90% рывка — это vkCreateBuffer +
    // vkAllocateMemory, по ДВА на каждую новую секцию (копия вершин + память под структуру),
    // ~45 мкс каждый. На 120 секциях в кадре это 10-15 мс — вот он, фриз.
    //
    // Пул тут бессилен ПО ПРИРОДЕ: эти два буфера секция забирает СЕБЕ и держит, пока жива, —
    // возвращать в пул нечего, каждая новая секция честно идёт к драйверу.
    //
    // Решение — не просить у драйвера по кусочку, а взять ПЛИТУ (64 МБ: один VkBuffer + одна
    // память) и раздавать из неё куски по смещениям. Выделение превращается из вызова драйвера
    // в арифметику по списку свободных блоков. Освобождение — возврат блока со СКЛЕЙКОЙ соседей,
    // иначе память со временем расползлась бы в труху из мелких дыр.
    private static final long SLAB_SIZE = 64L << 20;
    // 256 байт покрывает всё: смещение AS-хранилища обязано быть кратно 256, scratch — обычно 128.
    private static final long SUB_ALIGN = 256;
    private static final int SLAB_USAGE =
            VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;

    private static final class Slab {
        long buffer, memory, baseAddr, size;
        /** Свободные блоки: смещение -> размер. TreeMap, чтобы находить соседей для склейки. */
        final java.util.TreeMap<Long, Long> free = new java.util.TreeMap<>();
        /** M8.106: ВТОРОЙ индекс «размер -> смещения» — best-fit за O(log n) вместо скана.
         *  Замер (лог пользователя): first-fit скан фрагментированного списка съедал 3.1 мс
         *  из 3.4 мс рывка (25-40 мкс на выдачу при «нуле промахов пула»). */
        final java.util.TreeMap<Long, java.util.ArrayDeque<Long>> bySize = new java.util.TreeMap<>();

        void freePut(long off, long sz) {
            free.put(off, sz);
            bySize.computeIfAbsent(sz, k -> new java.util.ArrayDeque<>()).push(off);
        }
        void freeDel(long off, long sz) {
            free.remove(off);
            var dq = bySize.get(sz);
            if (dq != null) { dq.remove(off); if (dq.isEmpty()) bySize.remove(sz); }
        }
    }
    private static final java.util.ArrayList<Slab> slabs = new java.util.ArrayList<>();
    public static int slabCount() { return slabs.size(); }

    private static Slab newSlab(long size) {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            Slab sl = new Slab();
            sl.size = size;
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default().size(size).usage(SLAB_USAGE).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(device, info, null, pBuf), "create slab buffer");
            sl.buffer = pBuf.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, sl.buffer, req);
            VkMemoryAllocateFlagsInfo fl = VkMemoryAllocateFlagsInfo.calloc(stack)
                    .sType$Default().flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default().allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
                    .pNext(fl.address());
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(device, alloc, null, pMem), "allocate slab memory");
            sl.memory = pMem.get(0);
            vkBindBufferMemory(device, sl.buffer, sl.memory, 0);

            VkBufferDeviceAddressInfo ai = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default().buffer(sl.buffer);
            sl.baseAddr = vkGetBufferDeviceAddress(device, ai);
            sl.freePut(0L, size);
            net.vulkanmod.Initializer.LOGGER.info("[RT] новая плита памяти: {} МБ (всего плит: {})",
                    size >> 20, slabs.size() + 1);
            return sl;
        }
    }

    /** Выдать кусок из плиты; если ни в одной нет места — завести новую. */
    private static RawBuffer slabAlloc(long size) {
        long need = ((size + SUB_ALIGN - 1) / SUB_ALIGN) * SUB_ALIGN;
        for (int i = 0; i < slabs.size(); i++) {
            RawBuffer b = tryAlloc(slabs.get(i), i, need);
            if (b != null) return b;
        }
        slabs.add(newSlab(Math.max(SLAB_SIZE, need)));
        RawBuffer b = tryAlloc(slabs.get(slabs.size() - 1), slabs.size() - 1, need);
        if (b == null) throw new RuntimeException("суб-аллокатор: не влезло даже в свежую плиту");
        return b;
    }

    private static RawBuffer tryAlloc(Slab sl, int idx, long need) {
        // BEST-FIT за O(log n): наименьший блок, куда влезает (меньше режем крупные блоки,
        // фрагментация ниже, скана по списку нет вовсе).
        var fit = sl.bySize.ceilingEntry(need);
        if (fit == null) return null;
        long sz = fit.getKey();
        long off = fit.getValue().peek();
        sl.freeDel(off, sz);
        if (sz > need) sl.freePut(off + need, sz - need);   // остаток блока — обратно
        RawBuffer b = new RawBuffer();
        b.buffer = sl.buffer; b.memory = 0L; b.offset = off; b.capacity = need;
        b.address = sl.baseAddr + off; b.slab = idx; b.addressable = true;
        return b;
    }

    /** Вернуть кусок в плиту, СКЛЕИВ с соседями (иначе память рассыплется в дыры). */
    private static void slabFree(RawBuffer b) {
        Slab sl = slabs.get(b.slab);
        long off = b.offset, sz = b.capacity;
        var prev = sl.free.floorEntry(off);
        if (prev != null && prev.getKey() + prev.getValue() == off) {
            off = prev.getKey(); sz += prev.getValue(); sl.freeDel(prev.getKey(), prev.getValue());
        }
        var next = sl.free.get(off + sz);
        if (next != null) { sl.freeDel(off + sz, next); sz += next; }
        sl.freePut(off, sz);
        b.slab = -1;   // буфер отдан; повторное освобождение ничего не сломает
    }

    /** Создаёт буфер + память. wantAddress -> можно получить device address. */
    public static RawBuffer createBuffer(long size, int usage, int memProps, boolean wantAddress) {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            RawBuffer b = new RawBuffer();
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(device, info, null, pBuf), "create buffer");
            b.buffer = pBuf.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, b.buffer, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default().allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(req.memoryTypeBits(), memProps));
            if (wantAddress) {
                VkMemoryAllocateFlagsInfo fl = VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType$Default().flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                alloc.pNext(fl.address());
            }
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(device, alloc, null, pMem), "allocate memory");
            b.memory = pMem.get(0);
            vkBindBufferMemory(device, b.buffer, b.memory, 0);

            if (wantAddress) {
                VkBufferDeviceAddressInfo ai = VkBufferDeviceAddressInfo.calloc(stack)
                        .sType$Default().buffer(b.buffer);
                b.address = vkGetBufferDeviceAddress(device, ai);
            }
            return b;
        }
    }

    public static void destroyBuffer(RawBuffer b) {
        if (b.slab >= 0) { slabFree(b); return; }   // ⚠️ это кусок ПЛИТЫ, а не свой буфер — не рушить!
        VkDevice device = Vulkan.getVkDevice();
        vkDestroyBuffer(device, b.buffer, null);
        vkFreeMemory(device, b.memory, null);
    }

    /**
     * Общая сборка структуры ускорения по готовому build-info.
     * Спрашивает размеры, выделяет буфер под структуру и scratch, строит на GPU
     * через compute-очередь VulkanMod и ждёт завершения.
     *
     * @param build          заполненный geometry+build info (dst/scratch выставим сами)
     * @param primitiveCount сколько примитивов (треугольников для BLAS / экземпляров для TLAS)
     * @param type           VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR / TOP_LEVEL_KHR
     */
    public static AccelStruct build(VkAccelerationStructureBuildGeometryInfoKHR.Buffer build,
                                    int primitiveCount, int type) {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            // 1. Сколько памяти нужно под структуру и scratch.
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), stack.ints(primitiveCount), sizes);

            // 2. Буфер, в котором будет жить структура.
            RawBuffer asBuf = acquireBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);

            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default().buffer(asBuf.buffer)
                    .size(sizes.accelerationStructureSize()).type(type);
            LongBuffer pAs = stack.mallocLong(1);
            check(vkCreateAccelerationStructureKHR(device, ci, null, pAs), "create AS");
            long as = pAs.get(0);

            // 3. Временный scratch-буфер.
            RawBuffer scratch = acquireBuffer(sizes.buildScratchSize(),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);

            build.get(0).dstAccelerationStructure(as);
            build.get(0).scratchData().deviceAddress(scratch.address);

            // 4. Диапазон: сколько примитивов строим.
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(primitiveCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
            PointerBuffer pRanges = stack.mallocPointer(1);
            pRanges.put(0, range.address());

            // 5. Строим на GPU через compute-очередь VulkanMod и ждём.
            CommandPool.CommandBuffer cmd = DeviceManager.getComputeQueue().beginCommands();
            vkCmdBuildAccelerationStructuresKHR(cmd.getHandle(), build, pRanges);
            DeviceManager.getComputeQueue().submitCommands(cmd);
            DeviceManager.getComputeQueue().waitIdle();

            releaseBuffer(scratch);   // scratch больше не нужен — назад в пул

            AccelStruct out = new AccelStruct();
            out.handle = as;
            out.buffer = asBuf.buffer;
            out.memory = asBuf.memory;
            out.buf = asBuf;
            out.deviceAddress = accelAddress(as);
            return out;
        }
    }

    /**
     * БАТЧ-версия: записывает сборку структуры во ВНЕШНИЙ командный буфер (без
     * submit/waitIdle). Создаёт буфер под структуру и scratch; scratch кладётся в
     * {@code scratchSink} — вызывающий обязан освободить их ПОСЛЕ общего submit+wait.
     * Все временные native-структуры аллоцируются на переданном {@code stack},
     * который вызывающий не должен pop-ать до submit (иначе UB).
     */
    public static AccelStruct recordBuild(VkCommandBuffer cmd,
                                          VkAccelerationStructureBuildGeometryInfoKHR.Buffer build,
                                          int primitiveCount, int type,
                                          MemoryStack stack, List<RawBuffer> scratchSink) {
        VkDevice device = Vulkan.getVkDevice();
        VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        long tS = System.nanoTime();
        vkGetAccelerationStructureBuildSizesKHR(device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), stack.ints(primitiveCount), sizes);
        sizesNs += System.nanoTime() - tS;

        RawBuffer asBuf = acquireBuffer(sizes.accelerationStructureSize(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);

        long tC = System.nanoTime();
        // ⚠️ СМЕЩЕНИЕ ОБЯЗАТЕЛЬНО: буфер теперь общий на всю плиту, структура живёт в его середине.
        VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                .sType$Default().buffer(asBuf.buffer).offset(asBuf.offset)
                .size(sizes.accelerationStructureSize()).type(type);
        LongBuffer pAs = stack.mallocLong(1);
        check(vkCreateAccelerationStructureKHR(device, ci, null, pAs), "create AS");
        long as = pAs.get(0);
        createAsNs += System.nanoTime() - tC;

        RawBuffer scratch = acquireBuffer(sizes.buildScratchSize(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);

        build.get(0).dstAccelerationStructure(as);
        build.get(0).scratchData().deviceAddress(scratch.address);

        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(primitiveCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer pRanges = stack.mallocPointer(1);
        pRanges.put(0, range.address());

        vkCmdBuildAccelerationStructuresKHR(cmd, build, pRanges);   // только запись, без submit
        scratchSink.add(scratch);

        AccelStruct out = new AccelStruct();
        out.handle = as;
        out.buffer = asBuf.buffer;
        out.memory = asBuf.memory;
        out.buf = asBuf;
        out.deviceAddress = accelAddress(as);
        return out;
    }

    /**
     * БАТЧ ЦЕЛИКОМ — ОДНИМ ВЫЗОВОМ (M8.91).
     *
     * ⚠️ ЗАЧЕМ. Мы звали vkCmdBuildAccelerationStructures ПО РАЗУ НА СЕКЦИЮ. Замер показал: после
     * суб-аллокации 98% рывка сидит именно здесь — драйвер на каждом вызове делает свою подготовку,
     * и сто вызовов стоят в сто раз дороже одного. А API с самого начала принимает МАССИВ сборок:
     * все структуры батча уходят на GPU за один вызов.
     *
     * Вызывающий заполняет builds[i] (геометрия, флаги, режим); мы добираем размеры, буфер под
     * структуру, scratch — и записываем всё разом.
     */
    public static AccelStruct[] recordBuildBatch(VkCommandBuffer cmd,
                                                 VkAccelerationStructureBuildGeometryInfoKHR.Buffer builds,
                                                 int[] primCounts, int type,
                                                 MemoryStack stack, List<RawBuffer> scratchSink) {
        VkDevice device = Vulkan.getVkDevice();
        int n = primCounts.length;
        AccelStruct[] out = new AccelStruct[n];

        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(n, stack);
        PointerBuffer pRanges = stack.mallocPointer(n);
        VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        LongBuffer pAs = stack.mallocLong(1);
        java.nio.IntBuffer pCount = stack.mallocInt(1);

        for (int i = 0; i < n; i++) {
            pCount.put(0, primCounts[i]);
            long tS = System.nanoTime();
            vkGetAccelerationStructureBuildSizesKHR(device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, builds.get(i), pCount, sizes);
            sizesNs += System.nanoTime() - tS;

            RawBuffer asBuf = acquireBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);

            long tC = System.nanoTime();
            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default().buffer(asBuf.buffer).offset(asBuf.offset)
                    .size(sizes.accelerationStructureSize()).type(type);
            check(vkCreateAccelerationStructureKHR(device, ci, null, pAs), "create AS");
            long as = pAs.get(0);
            createAsNs += System.nanoTime() - tC;

            RawBuffer scratch = acquireBuffer(sizes.buildScratchSize(),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);
            scratchSink.add(scratch);

            builds.get(i).dstAccelerationStructure(as);
            builds.get(i).scratchData().deviceAddress(scratch.address);
            ranges.get(i).primitiveCount(primCounts[i]).primitiveOffset(0).firstVertex(0).transformOffset(0);
            pRanges.put(i, ranges.get(i).address());

            long tA = System.nanoTime();
            AccelStruct a = new AccelStruct();
            a.handle = as; a.buffer = asBuf.buffer; a.memory = asBuf.memory; a.buf = asBuf;
            a.deviceAddress = accelAddress(as);
            addrNs += System.nanoTime() - tA;
            out[i] = a;
        }

        long tB = System.nanoTime();
        vkCmdBuildAccelerationStructuresKHR(cmd, builds, pRanges);   // ВЕСЬ БАТЧ — ОДНИМ ВЫЗОВОМ
        buildCmdNs += System.nanoTime() - tB;
        return out;
    }

    /** Адрес устройства самой структуры (на него ссылается TLAS/шейдер). */
    public static long accelAddress(long as) {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureDeviceAddressInfoKHR info =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                            .sType$Default().accelerationStructure(as);
            return vkGetAccelerationStructureDeviceAddressKHR(device, info);
        }
    }

    /** Рушит структуру; её буфер ВОЗВРАЩАЕТСЯ В ПУЛ (не vkFreeMemory) — см. пул выше. */
    public void free() {
        VkDevice device = Vulkan.getVkDevice();
        vkDestroyAccelerationStructureKHR(device, handle, null);
        if (buf != null) releaseBuffer(buf);
        else { vkDestroyBuffer(device, buffer, null); vkFreeMemory(device, memory, null); }
    }

    // ---- утилиты ----

    /** Тип памяти по фильтру и свойствам — нужен и вне AccelStruct (storage-образы DLSS-пайплайна). */
    public static int memoryType(int filter, int props) { return findMemoryType(filter, props); }

    // ⚠️ КЭШ. Раньше свойства памяти спрашивались у драйвера на КАЖДЫЙ создаваемый буфер —
    // сотни раз за кадр при прогрузке. Они не меняются, спрашиваем один раз на пару (фильтр, флаги).
    private static final java.util.HashMap<Long, Integer> MEM_TYPE_CACHE = new java.util.HashMap<>();

    private static int findMemoryType(int filter, int props) {
        long key = ((long) filter << 32) | (props & 0xFFFFFFFFL);
        Integer hit = MEM_TYPE_CACHE.get(key);
        if (hit != null) return hit;
        int found = findMemoryTypeUncached(filter, props);
        MEM_TYPE_CACHE.put(key, found);
        return found;
    }

    private static int findMemoryTypeUncached(int filter, int props) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(DeviceManager.physicalDevice, mp);
            for (int i = 0; i < mp.memoryTypeCount(); i++) {
                if ((filter & (1 << i)) != 0
                        && (mp.memoryTypes(i).propertyFlags() & props) == props) {
                    return i;
                }
            }
        }
        throw new RuntimeException("[RT] no suitable memory type");
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new RuntimeException("[RT] Vulkan error in '" + what + "': code " + result);
        }
    }
}
