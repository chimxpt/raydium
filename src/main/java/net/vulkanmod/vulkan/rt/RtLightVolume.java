package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.153): ОБЪЁМ ЦВЕТА СВЕТА ===
//
// ЗАЧЕМ. Яркость света мы берём из ванильного вершинного lightmap — она запечена в геометрию,
// знает про углы и верна на любой дистанции. А ЦВЕТ брали из буфера на 192 источника, который
// КАЖДЫЙ КАДР переотбирался по близости к камере. Эта развилка и была источником целого класса
// багов: лампа вылетала из 192 -> её округа красилась чужим оттенком; камера сдвигалась -> состав
// буфера менялся -> оттенок плыл и пятна мигали; толпа слабых источников перевешивала одну
// сильную, потому что мы усредняем, а ванильная заливка берёт максимум.
//
// РЕШЕНИЕ. Считаем смешанный цвет ЗАРАНЕЕ, в мировых координатах, в трёхмерную решётку вокруг
// камеры. Смешивание идёт на процессоре по ПОЛНОМУ списку источников (без потолка 192), решётка
// привязана к МИРУ (а не к камере), поэтому поворот взгляда ничего не переотбирает. Шейдер берёт
// цвет одной выборкой с трилинейной интерполяцией — вместо цикла по 192 источникам на пиксель.
//
// ПОЧЕМУ ЯЧЕЙКА В 2 БЛОКА ДОСТАТОЧНА. Оттенок — величина НИЗКОЧАСТОТНАЯ: он плавно растекается от
// ламп и резких границ не имеет. Вся мелкая деталь освещения (тени за углами, градиенты) приходит
// из ванильной ЯРКОСТИ, которую мы не трогаем. Поэтому грубая решётка неотличима от точной, а
// стоит в 8 раз дешевле.
//
// ЧЕГО ЗДЕСЬ НЕТ. Динамические источники (факел в руке, горящие мобы, брошенные предметы) сюда НЕ
// попадают: их геометрия движется, запекать её некуда. Для них остаётся прежний буфер, и там он
// работает честно — таких источников единицы и они всегда рядом с камерой.

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

public class RtLightVolume {

    /** Блоков на ячейку. 2 — компромисс: оттенок низкочастотный, а цена падает восьмикратно. */
    public static final int CELL = 2;
    /** Размер решётки в ЯЧЕЙКАХ: 64^3 ячеек = 128x128x128 блоков вокруг камеры.
     *  ⚠️ По высоте берём столько же, сколько по горизонтали, НАМЕРЕННО: пещера под ногами или
     *  потолок Ancient City обязаны попадать внутрь. Увеличение решётки почти бесплатно — цена
     *  запекания зависит от ЧИСЛА ИСТОЧНИКОВ, а не от размера сетки. */
    public static final int NX = 64, NY = 64, NZ = 64;
    private static final int CELLS = NX * NY * NZ;          // 262 144
    private static final int BYTES = CELLS * 4;             // 1 МБ (RGBA8)

    /** Пересобираем, когда камера ушла от центра дальше этого (блоки). */
    private static final int RECENTER_DIST = 24;
    /** Не чаще, чем раз в столько мс — заливка идёт на фоне, но дёргать её зря незачем. */
    private static final long REBUILD_MS = 400;

    // --- состояние на стороне GPU ---
    private static AccelStruct.RawBuffer buffer;
    private static boolean failed = false;

    // --- начало решётки в БЛОКАХ (мировые координаты угла), то, что уходит в шейдер ---
    private static volatile int originX = Integer.MIN_VALUE, originY = 0, originZ = 0;

    // --- фоновая пересборка ---
    private static Thread worker;
    private static volatile boolean building = false;
    private static volatile byte[] ready;          // готовая решётка, ждёт заливки на GPU
    private static volatile int readyX, readyY, readyZ;
    private static long lastBuild = 0;
    private static int logCount = 0;
    private static int bakedVersion = -1;   // версия набора источников, с которой запечена решётка

    /** Дескриптор нельзя привязать к пустоте, поэтому буфер заводим ПО ТРЕБОВАНИЮ: даже пока
     *  решётка не запечена, привязка обязана указывать на живую память (шейдер её просто не
     *  читает — за это отвечает флаг valid в rtCfg8.w). */
    public static long bufferHandle() {
        if (buffer == null && !failed) {
            try {
                buffer = AccelStruct.createBuffer(BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
            } catch (Throwable e) {
                failed = true;
                Initializer.LOGGER.error("[RT] light volume: buffer creation failed", e);
            }
        }
        return buffer != null ? buffer.buffer : VK_NULL_HANDLE;
    }
    public static int  originXf() { return originX; }
    public static int  originYf() { return originY; }
    public static int  originZf() { return originZ; }
    /** Готов ли объём к чтению (иначе шейдер обязан идти старым путём — по буферу источников). */
    public static boolean valid() { return buffer != null && originX != Integer.MIN_VALUE; }

    /** Рендер-поток, каждый кадр: при нужде запускаем фоновую пересборку и заливаем готовое. */
    public static void update(double cx, double cy, double cz) {
        if (failed) return;
        try {
            if (bufferHandle() == VK_NULL_HANDLE) return;

            // 1) готовое с прошлого раза — залить и объявить актуальным
            byte[] r = ready;
            if (r != null) {
                ready = null;
                upload(r);
                originX = readyX; originY = readyY; originZ = readyZ;
            }

            // 2) нужна ли пересборка? (первый раз или камера ушла от центра)
            // Прошлый результат ещё не забран -> буферы запекания заняты, ждём следующий кадр
            if (building || ready != null) return;
            long now = System.currentTimeMillis();
            if (now - lastBuild < REBUILD_MS) return;

            int wantX = align((int) Math.floor(cx) - (NX * CELL) / 2);
            int wantY = align((int) Math.floor(cy) - (NY * CELL) / 2);
            int wantZ = align((int) Math.floor(cz) - (NZ * CELL) / 2);
            // Пересобираем по ДВУМ поводам, и второй не менее важен первого:
            //  1) камера ушла от центра решётки (нужны новые области);
            //  2) ИЗМЕНИЛСЯ САМ НАБОР ИСТОЧНИКОВ — поставили или сломали лампу. Без этого свет
            //     разбитого факела продолжал бы красить округу, пока игрок стоит на месте.
            boolean first = (originX == Integer.MIN_VALUE);
            int ver = RtLights.lightsVersion();
            boolean moved = Math.abs(wantX - originX) >= RECENTER_DIST
                         || Math.abs(wantY - originY) >= RECENTER_DIST
                         || Math.abs(wantZ - originZ) >= RECENTER_DIST;
            if (!first && !moved && ver == bakedVersion) return;

            // Позицию решётки при пересборке «по свету» НЕ двигаем: иначе каждый догруженный
            // чанк дёргал бы центр, и объём переезжал бы рывками без всякой нужды.
            if (!first && !moved) { wantX = originX; wantY = originY; wantZ = originZ; }

            bakedVersion = ver;
            lastBuild = now;
            building = true;
            List<float[]> src = RtLights.snapshotSources();
            final int bx = wantX, by = wantY, bz = wantZ;
            worker = new Thread(() -> {
                try {
                    long t0 = System.nanoTime();
                    byte[] grid = bake(src, bx, by, bz);
                    readyX = bx; readyY = by; readyZ = bz;
                    ready = grid;
                    if ((logCount++ % 8) == 0) {
                        int filled = 0;
                        for (int i = 3; i < BYTES; i += 4) if (grid[i] != 0) filled++;
                        Initializer.LOGGER.info(
                                "[RT] light volume: baked in {} ms, lit cells {} of {} ({}%), sections {}",
                                (System.nanoTime() - t0) / 1_000_000, filled, CELLS,
                                filled * 100 / CELLS, src.size());
                    }
                } catch (Throwable e) {
                    Initializer.LOGGER.error("[RT] light volume: bake failed", e);
                } finally {
                    building = false;
                }
            }, "RT light volume");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable e) {
            failed = true;
            Initializer.LOGGER.error("[RT] light volume disabled (failure)", e);
        }
    }

    private static int align(int v) { return Math.floorDiv(v, CELL) * CELL; }

    /** M8.153: попадает ли секция в решётку? Нужно, чтобы НЕ пересобирать объём из-за чанков,
     *  которые грузятся на краю прорисовки — они за сотни блоков и на цвет здесь не влияют.
     *  Раньше любая сборка чанка щёлкала версию, и объём перезапекался каждые 400 мс впустую. */
    public static boolean affects(long key) {
        if (originX == Integer.MIN_VALUE) return false;
        int sx = signed24((int) ((key >> 38) & 0xFFFFFF));
        int sz = signed24((int) ((key >> 14) & 0xFFFFFF));
        int sy = signed10((int) ((key >> 4)  & 0x3FF));
        int bx0 = sx << 4, by0 = sy << 4, bz0 = sz << 4;   // угол секции в блоках
        int pad = 16 + 16;                                  // размер секции + запас на дальность лампы
        return bx0 + pad >= originX && bx0 - pad <= originX + NX * CELL
            && by0 + pad >= originY && by0 - pad <= originY + NY * CELL
            && bz0 + pad >= originZ && bz0 - pad <= originZ + NZ * CELL;
    }

    private static int signed24(int v) { return v >= 0x800000 ? v - 0x1000000 : v; }
    private static int signed10(int v) { return v >= 0x200      ? v - 0x400      : v; }

    /**
     * ФОНОВЫЙ ПОТОК: смешиваем цвета всех источников в решётку.
     * Вес источника — тот же, что был в шейдере: (дальность - расстояние)^2, то есть ближний и
     * сильный перебивает дальний и слабый, а на стыке двух ламп цвета переходят плавно. Разница
     * с прежним в одном, но решающем: сюда попадают ВСЕ источники, а не 192 ближайших к камере.
     */
    // M8.154: буферы запекания ПЕРЕИСПОЛЬЗУЕМ. Раньше каждое запекание выделяло ~5 МБ (четыре
    // массива накопления + выход) и тут же отдавало их сборщику мусора — при полёте это десятки
    // мегабайт в секунду, то есть рывки в кадре, ровно то, от чего мы уходили. Гонки нет: запекание
    // идёт строго по одному (флаг building), а новое не стартует, пока прошлый результат не забран.
    private static final float[] accR = new float[CELLS], accG = new float[CELLS],
                                 accB = new float[CELLS], accW = new float[CELLS];
    private static final byte[]  outGrid = new byte[BYTES];

    private static byte[] bake(List<float[]> sections, int bx, int by, int bz) {
        java.util.Arrays.fill(accR, 0f); java.util.Arrays.fill(accG, 0f);
        java.util.Arrays.fill(accB, 0f); java.util.Arrays.fill(accW, 0f);
        java.util.Arrays.fill(outGrid, (byte) 0);

        final int maxX = bx + NX * CELL, maxY = by + NY * CELL, maxZ = bz + NZ * CELL;
        for (float[] arr : sections) {
            if (arr == null) continue;
            for (int b = 0; b + 8 <= arr.length; b += 8) {
                float sx = arr[b], sy = arr[b + 1], sz = arr[b + 2], range = arr[b + 3];
                if (range <= 0.01f) continue;
                // источник вне решётки (с запасом на свою дальность) — мимо
                if (sx < bx - range || sx > maxX + range) continue;
                if (sy < by - range || sy > maxY + range) continue;
                if (sz < bz - range || sz > maxZ + range) continue;

                float sr = arr[b + 4], sg = arr[b + 5], sb = arr[b + 6];
                // окно ячеек, куда источник вообще достаёт
                int c0x = clamp((int) Math.floor((sx - range - bx) / CELL), 0, NX - 1);
                int c1x = clamp((int) Math.ceil ((sx + range - bx) / CELL), 0, NX - 1);
                int c0y = clamp((int) Math.floor((sy - range - by) / CELL), 0, NY - 1);
                int c1y = clamp((int) Math.ceil ((sy + range - by) / CELL), 0, NY - 1);
                int c0z = clamp((int) Math.floor((sz - range - bz) / CELL), 0, NZ - 1);
                int c1z = clamp((int) Math.ceil ((sz + range - bz) / CELL), 0, NZ - 1);

                // ⚠️ Корень считаем ТОЛЬКО для ячеек внутри сферы. Замер: запекание стоило 196 мс,
                // и львиная доля уходила на sqrt для каждой ячейки описанного куба — а внутрь
                // сферы попадает лишь половина из них. Сравнение квадратов отсекает остальные
                // даром. Заодно выносим наружу всё, что не зависит от внутреннего цикла.
                final float r2 = range * range;
                for (int cy2 = c0y; cy2 <= c1y; cy2++) {
                    float wy = by + cy2 * CELL + CELL * 0.5f - sy;
                    float wy2 = wy * wy;
                    for (int cz2 = c0z; cz2 <= c1z; cz2++) {
                        float wz = bz + cz2 * CELL + CELL * 0.5f - sz;
                        float wyz2 = wy2 + wz * wz;
                        if (wyz2 > r2) continue;                  // вся строка вне сферы
                        int row = (cy2 * NZ + cz2) * NX;
                        for (int cx2 = c0x; cx2 <= c1x; cx2++) {
                            float wx = bx + cx2 * CELL + CELL * 0.5f - sx;
                            float d2 = wyz2 + wx * wx;
                            if (d2 >= r2) continue;               // без корня
                            float w = (range - (float) Math.sqrt(d2)) / 15.0f;
                            w *= w;
                            int i = row + cx2;
                            accR[i] += sr * w; accG[i] += sg * w; accB[i] += sb * w; accW[i] += w;
                        }
                    }
                }
            }
        }

        // нормируем: в ячейке остаётся ЧИСТЫЙ оттенок (яркость даёт ванильный lightmap).
        // Альфа = «есть ли тут вообще свет»; по ней шейдер решает, доверять ячейке или нет.
        byte[] out = outGrid;
        for (int i = 0; i < CELLS; i++) {
            float w = accW[i];
            int o = i * 4;
            if (w < 1e-6f) continue;                       // пусто -> альфа 0
            float r = accR[i] / w, g = accG[i] / w, bl = accB[i] / w;
            float m = Math.max(r, Math.max(g, bl));        // нормируем по максимуму канала:
            if (m > 1e-6f) { r /= m; g /= m; bl /= m; }    // храним ЧИСТЫЙ оттенок, без яркости
            out[o]     = (byte) clamp((int) (r  * 255.0f + 0.5f), 0, 255);
            out[o + 1] = (byte) clamp((int) (g  * 255.0f + 0.5f), 0, 255);
            out[o + 2] = (byte) clamp((int) (bl * 255.0f + 0.5f), 0, 255);
            out[o + 3] = (byte) 255;
        }
        return out;
    }

    private static void upload(byte[] grid) {
        VkDevice device = Vulkan.getVkDevice();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(device, buffer.memory, 0, BYTES, 0, pp);
            ByteBuffer bb = MemoryUtil.memByteBuffer(pp.get(0), BYTES).order(ByteOrder.LITTLE_ENDIAN);
            bb.put(grid);
            vkUnmapMemory(device, buffer.memory);
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Смена мира / выключение трассировки. */
    public static void reset() {
        originX = Integer.MIN_VALUE;
        bakedVersion = -1;
        ready = null;
        lastBuild = 0;
    }

    public static void destroy() {
        reset();
        if (buffer != null) { AccelStruct.releaseBuffer(buffer); buffer = null; }
    }
}
