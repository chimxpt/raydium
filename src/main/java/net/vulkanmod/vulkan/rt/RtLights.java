package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.7): ЦВЕТНОЙ СВЕТ ===
// Проблема: свет блоков мы читаем из ВЕРШИННЫХ данных чанка, а там лежит только УРОВЕНЬ 0..15 —
// какой блок его излучил, вершина не знает. Значит фонарь душ, медный факел и портал светят
// одинаковым оранжевым.
//
// Решение: из Java сканируем блоки вокруг игрока, собираем СВЕТЯЩИЕСЯ (getLightEmission > 0),
// каждому даём цвет и дальность из таблицы Eclipse (shaders/dimensions/setup.csh) и шлём
// список в шейдер (SSBO). Шейдер:
//   * ОТТЕНОК   — смешивает цвета ближних источников (яркость по-прежнему из вершинного
//                 lightmap, т.е. ванильная заливка света за углы сохраняется);
//   * ЭМИССИЯ   — если точка попадания лежит ВНУТРИ куба источника, поверхность светится сама
//                 (пламя факела, лава, светокамень).
//
// Таблица цветов — порт из Eclipse (© Chocapic13/Xonk), проект личный, не публикуется.

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class RtLights {

    /** Сколько источников максимум держим (ближние к игроку). 1 источник = 32 байта. */
    public static final int MAX = 256;   // было 128 — в пещерах со скалком не хватало
    private static final int STRIDE = 32;          // vec4 posRange + vec4 colorPad

    /** Радиус скана вокруг камеры (блоки). Куб (2R+1)^3 — держим скромным, скан не бесплатный. */
    private static final int R_XZ = 20, R_Y = 14;
    private static final long SCAN_MS = 150;

    /** Кольцо буферов: кадр «в полёте» может читать прошлый — писать в него нельзя. */
    private static final int SLOTS = 4;

    // Режим эмиссии (уходит в шейдер через col.w) — ЧТО именно светится на блоке:
    static final int MODE_NORMAL  = 0;   // обычный источник: факел, лава, светокамень
    static final int MODE_FIREFLY = 1;   // куст светлячков: блуждающие МЕРЦАЮЩИЕ точки
    static final int MODE_DOTS    = 2;   // светятся ТОЛЬКО яркие тексели (квадратики-огонёчки лишайника)
    static final int MODE_FLAME   = 3;   // ПЛАМЯ (огонь, костёр): яркие тёплые тексели горят + мерцают
    static final int MODE_POINT   = 4;   // ДИНАМИЧЕСКИЙ огонь (горящий моб/игрок): только светит, не эмиссивит
    static final int MODE_FIRE    = 5;   // огонь-факел/фонарь: эмиссия как обычная, но СВЕТ на мир МЕРЦАЕТ

    // ---- Таблица «блок -> (цвет, дальность, ...)» — СОБСТВЕННАЯ палитра (M8.102):
    //      состав блоков вдохновлён шейдер-паками, значения цветов свои ----
    // floorEmit: «искусственный» уровень света для ДЕКОРАТИВНЫХ блоков, у которых ванильный
    //            свет = 0 (куст светлячков, светящиеся корни) — чтобы они всё же светились.
    // mode:      режим эмиссии (см. MODE_*).
    private record LightDef(float r, float g, float b, float range, int floorEmit, int mode) {}

    private static final LightDef WARM_TORCH = new LightDef(1.00f, 0.53f, 0.27f, 14f, 0, MODE_NORMAL);  // как TORCH_COL шейдера
    private static volatile Map<Block, LightDef> TABLE;   // строим лениво (реестр блоков должен быть загружен)

    // Таблицу задаём ПО ИМЕНИ блока (а не по классу Blocks.X): часть блоков появилась только в
    // свежих версиях (медный факел — 1.21.9), и жёсткая ссылка на них не собралась бы на старых.
    private static void put(Map<String, LightDef> m, String id, float r, float g, float b, float range) {
        m.put(id, new LightDef(r, g, b, range, 0, MODE_NORMAL));
    }

    // Декоративный СВЕТЯЩИЙСЯ блок с НУЛЕВЫМ ванильным светом: светится сам (эмиссивные тексели)
    // + мягко подсвечивает округу на floorEmit блоков. mode задаёт, ЧТО светится.
    private static void putEmis(Map<String, LightDef> m, String id, float r, float g, float b,
                                float range, int floorEmit, int mode) {
        m.put(id, new LightDef(r, g, b, range, floorEmit, mode));
    }

    // Быстрый путь без блокировки. Инициализацию (buildTable) теперь может дёрнуть ЛЮБОЙ
    // из потоков сборки чанков (BuildTask зовёт lightOf), а не только рендер-поток, — поэтому
    // TABLE сделан volatile, а сама сборка ушла под synchronized (двойная проверка).
    private static Map<Block, LightDef> table() {
        Map<Block, LightDef> t = TABLE;
        return (t != null) ? t : buildTable();
    }

    private static synchronized Map<Block, LightDef> buildTable() {
        if (TABLE != null) return TABLE;
        Map<String, LightDef> m = new HashMap<>();
        // --- тёплый огонь ---
        // Факелы/фонари — режим MODE_FIRE: эмиссия как раньше, но их СВЕТ на мир мерцает (огонь)
        putEmis(m, "torch",             0.941f, 0.490f, 0.244f, 14f, 0, MODE_FIRE);
        putEmis(m, "wall_torch",        1.000f, 0.483f, 0.249f, 14f, 0, MODE_FIRE);
        put(m, "lantern",               0.795f, 0.561f, 0.191f, 15f);   // закрытая лампа — НЕ мерцает
        // ПЛАМЯ — режим MODE_FLAME: яркие тексели горят сильнее + мерцают (не тусклое тление)
        putEmis(m, "fire",              0.950f, 0.559f, 0.240f, 15f, 0, MODE_FLAME);
        putEmis(m, "campfire",          0.959f, 0.548f, 0.264f, 15f, 0, MODE_FLAME);
        putEmis(m, "soul_campfire",     0.195f, 0.618f, 0.981f, 10f, 0, MODE_FLAME);
        putEmis(m, "jack_o_lantern",    0.857f, 0.604f, 0.360f, 15f, 0, MODE_FIRE);
        put(m, "shroomlight",           0.803f, 0.443f, 0.217f, 15f);
        put(m, "glowstone",             0.748f, 0.572f, 0.308f, 15f);
        put(m, "furnace",               0.897f, 0.609f, 0.116f, 12f);
        put(m, "blast_furnace",         0.869f, 0.566f, 0.126f, 12f);
        put(m, "smoker",                0.821f, 0.602f, 0.115f, 12f);
        put(m, "redstone_lamp",         0.975f, 0.767f, 0.511f, 15f);
        put(m, "copper_bulb",           1.000f, 0.727f, 0.578f, 15f);
        // --- лава и магма (тёплый красно-оранжевый) ---
        put(m, "lava",                  0.987f, 0.291f, 0.112f, 15f);
        put(m, "magma_block",           0.715f, 0.325f, 0.105f,  3f);
        // --- ДУШИ: холодный сине-голубой ---
        putEmis(m, "soul_torch",        0.096f, 0.596f, 1.000f, 10f, 0, MODE_FIRE);
        putEmis(m, "soul_wall_torch",   0.095f, 0.618f, 1.000f, 10f, 0, MODE_FIRE);
        put(m, "soul_lantern",          0.101f, 0.621f, 1.000f, 10f);   // закрытая лампа — НЕ мерцает
        putEmis(m, "soul_fire",         0.189f, 0.635f, 1.000f, 10f, 0, MODE_FLAME);   // синее пламя душ
        // --- МЕДЬ (1.21.9+): зелёный ---
        putEmis(m, "copper_torch",      0.098f, 0.818f, 0.309f, 10f, 0, MODE_FIRE);
        putEmis(m, "copper_wall_torch", 0.101f, 0.844f, 0.313f, 10f, 0, MODE_FIRE);
        put(m, "copper_lantern",        0.106f, 0.782f, 0.303f, 10f);   // закрытая лампа — НЕ мерцает
        // --- редстоун (красный) ---
        put(m, "redstone_torch",        0.923f, 0.299f, 0.162f,  7f);
        put(m, "redstone_wall_torch",   0.908f, 0.298f, 0.164f,  7f);
        put(m, "redstone_ore",          0.926f, 0.317f, 0.168f,  7f);
        put(m, "deepslate_redstone_ore",0.942f, 0.299f, 0.157f,  7f);
        // --- море/эндер ---
        put(m, "sea_lantern",           0.552f, 0.790f, 0.818f, 15f);
        put(m, "conduit",               1.000f, 1.000f, 0.983f, 15f);
        put(m, "beacon",                0.989f, 0.964f, 1.000f, 15f);
        put(m, "end_rod",               0.987f, 0.886f, 0.921f, 12f);
        // --- ПОРТАЛЫ ---
        put(m, "nether_portal",         0.518f, 0.163f, 0.804f, 11f);   // фиолетовый (Eclipse)
        put(m, "end_portal",            0.201f, 0.898f, 0.659f, 15f);   // зеленовато-бирюзовый
        put(m, "end_gateway",           0.204f, 0.897f, 0.718f, 15f);
        put(m, "crying_obsidian",       0.382f, 0.063f, 0.622f, 10f);
        put(m, "respawn_anchor",        1.000f, 0.212f, 0.949f, 15f);
        // --- прочее цветное ---
        put(m, "amethyst_cluster",      0.470f, 0.236f, 0.810f,  5f);
        // Светящийся лишайник: пользователь хочет, чтобы светились его КВАДРАТИКИ-огонёчки.
        // Ванильно светит уровнем 7; цвет — бирюзово-зелёный (яркий, чтобы огоньки читались).
        putEmis(m, "glow_lichen",       0.359f, 0.913f, 0.738f,  7f, 0, MODE_DOTS);
        // СВЕТЯЩИЕСЯ ЯГОДЫ. Гореть должны ТОЛЬКО ягоды, а не куст целиком — режим «точки»
        // (светятся лишь яркие тексели), как у лишайника и скалка. Цвет — тёплый жёлтый.
        // ⚠️ В ванили светят только лианы С ЯГОДАМИ (уровень 14); пустой стебель не светит вовсе,
        // и точечный режим сам это учтёт: у него ярких текселей просто нет.
        putEmis(m, "cave_vines",        0.979f, 0.834f, 0.256f, 11f, 0, MODE_DOTS);
        putEmis(m, "cave_vines_plant",  1.000f, 0.785f, 0.246f, 11f, 0, MODE_DOTS);
        put(m, "ochre_froglight",       0.777f, 0.645f, 0.107f, 15f);
        put(m, "verdant_froglight",     0.458f, 0.721f, 0.395f, 15f);
        put(m, "pearlescent_froglight", 0.755f, 0.452f, 0.643f, 15f);
        put(m, "sea_pickle",            0.295f, 0.388f, 0.216f, 12f);
        // СКАЛК. Горят ТОЛЬКО пятнышки (режим «точки», как у светящегося лишайника), но свет он
        // отдаёт НАСТОЯЩИЙ.
        // ⚠️ Почему настоящий. В ванили скалк не светит вовсе (уровень 0), и сперва я подставил ему
        // маленький «искусственный» уровень. Вышло плохо: источник получался слабым и шатким, а цвет
        // ближних источников я СМЕШИВАЮ ПО ВЕСАМ — и лава рядом со скалком ловила то синеву, то нет,
        // смотря чей вес перевесил. Даём ему полноценный уровень: свет становится устойчивым, и
        // смешение перестаёт скакать.
        // Оттенок взят ЗАМЕРОМ со свечения вардена (скриншот пользователя): средний цвет ярких
        // текселей (93, 232, 243) -> нормировано (0.38, 0.95, 1.00). Это бирюза: голубого больше
        // всего, зелени чуть меньше, красного совсем немного — он и даёт «живой» оттенок вместо
        // мёртвого синего.
        // Яркость вернули по просьбе — так красивее. Заливкой пещеры это больше не грозит: отбор
        // источников теперь идёт ПО ЗНАЧИМОСТИ (сила / расстояние), и скалк не вытесняет лаву.
        putEmis(m, "sculk_catalyst",    0.385f, 0.959f, 1.000f,  8f, 6, MODE_DOTS);
        putEmis(m, "sculk_sensor",      0.388f, 0.945f, 1.000f,  6f, 4, MODE_DOTS);
        putEmis(m, "sculk",             0.395f, 0.959f, 1.000f,  6f, 5, MODE_DOTS);
        putEmis(m, "sculk_vein",        0.368f, 0.931f, 1.000f,  5f, 4, MODE_DOTS);
        putEmis(m, "sculk_shrieker",    0.403f, 0.933f, 1.000f,  6f, 5, MODE_DOTS);
        putEmis(m, "candle",            0.950f, 0.384f, 0.104f,  3f, 0, MODE_FIRE);

        // --- ЭМИССИВНЫЕ ДЕКОРАТИВНЫЕ (ванильный свет = 0, светятся сами) ---
        // Куст светлячков (1.21.5+): светятся ТОЛЬКО светлячки, блуждают и мерцают. Мягкий
        // зеленовато-жёлтый свет в округу.
        putEmis(m, "firefly_bush",      0.679f, 0.944f, 0.305f, 5f, 6, MODE_FIREFLY);
        // Светящиеся корни (id уточняется у пользователя по F3): светятся только квадратики.
        // putEmis(m, "<roots_id>",     1.00f, 0.55f, 0.15f, 4f, 5, MODE_DOTS);

        // Проходим реестр блоков и связываем имя -> объект (незнакомые имена просто не найдутся)
        Map<Block, LightDef> out = new HashMap<>();
        for (Block bl : BuiltInRegistries.BLOCK) {
            var key = BuiltInRegistries.BLOCK.getKey(bl);
            if (key == null) continue;
            LightDef d = m.get(key.getPath());
            if (d != null) out.put(bl, d);
        }
        Initializer.LOGGER.info("[RT] RtLights: таблица цветов — {} блоков из {} записей", out.size(), m.size());
        TABLE = out;
        return out;
    }

    /** Определение блока из таблицы, ИЛИ null (не в таблице). */
    private static LightDef defOf(BlockState st) {
        return table().get(st.getBlock());
    }

    // ---- GPU-состояние ----
    private static AccelStruct.RawBuffer[] slots;
    private static int writeSlot = 0, readSlot = 0;
    private static volatile int count = 0;
    private static long lastScanMs = 0, lastLogMs = 0;
    private static boolean failed = false;

    /** Цвет света предмета в руке (для динамического источника в шейдере). */
    private static volatile float heldR = 1f, heldG = 0.5f, heldB = 0.25f;

    public static long buffer() {
        return (slots != null) ? slots[readSlot].buffer : VK_NULL_HANDLE;
    }
    public static int count() { return count; }
    public static float heldR() { return heldR; }
    public static float heldG() { return heldG; }
    public static float heldB() { return heldB; }

    /** Уровень света предмета в руке (0..1) по стакам обеих рук + его цвет. */
    public static float scanHeldItem(ItemStack main, ItemStack off) {
        int best = 0;
        LightDef bestDef = WARM_TORCH;
        for (ItemStack st : new ItemStack[]{ main, off }) {
            if (st == null || st.isEmpty()) continue;
            if (st.getItem() instanceof BlockItem bi) {
                BlockState bs = bi.getBlock().defaultBlockState();
                int e = bs.getLightEmission();
                if (e > best) {
                    best = e;
                    LightDef d = defOf(bs);
                    bestDef = d != null ? d : WARM_TORCH;   // неизвестный светящийся предмет — тёплый
                }
            } else if (st.is(Items.LAVA_BUCKET) && best < 15) {
                best = 15; bestDef = new LightDef(0.759f, 0.302f, 0.106f, 8f, 0, MODE_NORMAL);   // Eclipse ITEM_LAVA_BUCKET
            }
        }
        heldR = bestDef.r(); heldG = bestDef.g(); heldB = bestDef.b();
        return best / 15.0f;
    }

    // ⚠️ РАЗДЕЛЕНИЕ ЧАСТОТ. Блоки статичны, а их скан (куб 41x29x41) дорогой -> троттл 150 мс.
    // Но БРОШЕННЫЕ ПРЕДМЕТЫ и ГОРЯЩИЕ МОБЫ ДВИГАЮТСЯ, и на том же троттле их свет дёргался
    // рывками (6-7 обновлений в секунду). Поэтому сущности сканируем КАЖДЫЙ КАДР и подмешиваем
    // к закэшированным блокам; заливка буфера (4 КБ) каждый кадр — дёшево.
    private static final int MAX_BLOCKS = 192;             // было 96; остальное — под сущности
    private static final float[] blockPx = new float[MAX_BLOCKS * 8];
    private static final float[] framePx = new float[MAX * 8];
    private static int blockN = 0;

    // === RT PATCH (M8.142): ЗАПЕЧЁННЫЙ СВЕТ ПРИ СБОРКЕ ЧАНКА ===
    // Раньше источники искал per-frame скан куба 41x41x29 вокруг КАМЕРЫ (R_XZ=20). Две беды:
    //   * дорого (десятки тысяч getBlockState за кадр) — отсюда троттл SCAN_MS и рывки списка;
    //   * ЖЁСТКИЙ обрез на 20 блоках: отлетел от портала/лавы — их цвет пропал, цветной свет на
    //     округу сменялся тёплым фолбэком.
    // Теперь эмиссивные блоки собираются ОДИН РАЗ при сборке секции (BuildTask, фоновые потоки)
    // в CompileResult.lights и складываются сюда по ключу секции. Каждый кадр мы лишь РАНЖИРУЕМ
    // готовый список (дёшево) и берём топ-MAX_BLOCKS по значимости. Покрытие — вся дальность
    // прорисовки, без per-frame скана и без фризов. Карту трогает ТОЛЬКО рендер-поток
    // (заливка в doSectionUpdate, чистка в resetDrawParameters/resetWorld, чтение в update).
    private static final Long2ObjectOpenHashMap<float[]> sectionLights = new Long2ObjectOpenHashMap<>();

    /** Ключ секции (как в RtWorld, но нибл renderType = 0 — свет собирается на всю секцию разом). */
    public static long sectionKey(int sx, int sy, int sz) {
        return (((long) sx & 0xFFFFFF) << 38) | (((long) sz & 0xFFFFFF) << 14) | (((long) sy & 0x3FF) << 4);
    }

    /** Один источник -> 8 float (формат writeLight), либо null если блок не светится. Зовёт BuildTask. */
    public static float[] lightOf(BlockState st, int wx, int wy, int wz) {
        // Рамка портала в Энд: НЕ источник света (return null). Само-свечение даёт КАРТА МАТЕРИАЛОВ
        // по текселям — спрайт ока end_portal_frame_eye запечён эмиссивным, а EMIS_CURVE гасит тёмное
        // и оставляет только яркие бирюзовые тексели ока. Так светится РОВНО око, а не весь блок.
        // ⚠️ Источником рамку делать нельзя: MODE_NORMAL палит весь блок (рамка = светлый энд-камень,
        // почти все тексели ярче кривой), а MODE_POINT красит соседей оранжевым «как в огне».
        // Заодно return null чинит «свечение без ока» (у рамки нет тёплого фолбэка вовсе).
        if (st.getBlock() == Blocks.END_PORTAL_FRAME) return null;
        int emit = st.getLightEmission();
        LightDef def = table().get(st.getBlock());
        if (emit <= 0 && (def == null || def.floorEmit() == 0)) return null;
        float[] e = new float[8];
        writeLight(e, 0, wx, wy, wz, emit, def);
        return e;
    }

    /** Рендер-поток: залить/снять свет секции (float[] = 8*N; null/пусто -> снять секцию). */
    public static void setSectionLights(long key, float[] lights) {
        if (lights == null || lights.length == 0) sectionLights.remove(key);
        else sectionLights.put(key, lights);
    }
    public static void removeSectionLights(long key) { sectionLights.remove(key); }
    public static void clearSectionLights() { sectionLights.clear(); }

    // Цвет end_portal из таблицы (для опознания портала среди запечённых источников).
    private static final float EP_R = 0.201f, EP_G = 0.898f, EP_B = 0.659f;

    /** M8.144h: ближайший активный портал в Энд из ЗАПЕЧЁННЫХ источников (вся дальность, БЕЗ
     *  блочного скана — убрали периодический спайк 22к блоков/500мс). {центрX, центрZ, Y_глади} или null.
     *  Зовётся с рендер-потока (только чтение sectionLights). end_portal попадает в sectionLights как
     *  бирюзовый источник (level 15) при сборке чанка; опознаём по точному цвету таблицы. */
    public static float[] nearestEndPortal(double cx, double cy, double cz) {
        FloatArrayList eps = null;                  // позиции текселей end_portal (их мало — 9 на портал)
        for (float[] arr : sectionLights.values())
            for (int b = 0; b + 8 <= arr.length; b += 8)
                if (Math.abs(arr[b+4]-EP_R) < 0.02f && Math.abs(arr[b+5]-EP_G) < 0.02f
                        && Math.abs(arr[b+6]-EP_B) < 0.02f) {
                    if (eps == null) eps = new FloatArrayList();
                    eps.add(arr[b]); eps.add(arr[b+1]); eps.add(arr[b+2]);
                }
        if (eps == null) return null;
        int nb = 0; double best = Double.MAX_VALUE;                 // ближайший тексель
        for (int i = 0; i < eps.size(); i += 3) {
            double dx = eps.getFloat(i)-cx, dy = eps.getFloat(i+1)-cy, dz = eps.getFloat(i+2)-cz;
            double d = dx*dx + dy*dy + dz*dz;
            if (d < best) { best = d; nb = i; }
        }
        float bx = eps.getFloat(nb), by = eps.getFloat(nb+1), bz = eps.getFloat(nb+2);
        double sX = 0, sZ = 0; int c = 0;                          // центр 3x3 = средний XZ того же слоя Y
        for (int i = 0; i < eps.size(); i += 3) {
            if (Math.abs(eps.getFloat(i+1) - by) > 0.5f) continue;
            if (Math.abs(eps.getFloat(i) - bx) > 2.5f || Math.abs(eps.getFloat(i+2) - bz) > 2.5f) continue;
            sX += eps.getFloat(i); sZ += eps.getFloat(i+2); c++;
        }
        // позиции = центр блока (x+0.5); Y глади = blockY+0.75 = (by-0.5)+0.75 = by+0.25
        return new float[]{ (float)(sX / c), (float)(sZ / c), by + 0.25f };
    }

    // M8.147 ЦВЕТОВЫЕ ЯКОРЯ — обобщение приёма, которым вытащили портал Энда.
    private static final int   ANCHOR_MAX  = 24;    // слотов под цветовые семейства (из MAX_BLOCKS)
    private static final float ANCHOR_DIST = 128f;  // дальше якорь бесполезен: шейдер всё равно уводит оттенок в TORCH_COL
    private static final Int2IntOpenHashMap anchorSlot = new Int2IntOpenHashMap();
    private static final float[]   anchorDist = new float[ANCHOR_MAX];
    private static final float[][] anchorArr  = new float[ANCHOR_MAX][];
    private static final int[]     anchorOff  = new int[ANCHOR_MAX];
    private static int anchorN = 0;

    /** Дёшево: ранжируем ЗАПЕЧЁННЫЕ источники всех секций -> топ-MAX_BLOCKS в blockPx. */
    private static void gatherSectionLights(double cx, double cy, double cz) {
        float[] score = new float[MAX_BLOCKS];
        int n = 0, worst = -1;
        float worstScore = Float.MAX_VALUE;

        // === M8.147 БРОНЬ ПО ЦВЕТУ ===
        // Ранжир ниже отбирает по РАССТОЯНИЮ и слеп к ЦВЕТУ. Две сотни одинаковых факелов вокруг
        // камеры занимают все слоты и дают ОДИН И ТОТ ЖЕ тёплый оттенок — они избыточны; а
        // единственный soul-фонарь, несущий уникальную синеву, вытесняется, и его блоки желтеют
        // (репорт: «синева не держится»). Поэтому сперва бронируем слот КАЖДОМУ цветовому
        // семейству — ближайшего представителя ищем в ПОЛНОМ наборе sectionLights, минуя ранжир.
        // Это ровно то, чем вытащили портал Энда (nearestEndPortal): поиск по цветовой сигнатуре
        // в некапнутом списке. Цвет квантуем по 5 бит на канал — оттенки одной природы
        // (soul_torch/soul_lantern/soul_fire) схлопываются в одно семейство и делят один слот.
        anchorSlot.clear();
        anchorN = 0;
        for (float[] arr : sectionLights.values()) {
            for (int b = 0; b + 8 <= arr.length; b += 8) {
                float ax = (float) (arr[b] - cx), ay = (float) (arr[b + 1] - cy), az = (float) (arr[b + 2] - cz);
                float ad = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                if (ad > ANCHOR_DIST) continue;
                int key = ((int) (arr[b + 4] * 31f) << 10)
                        | ((int) (arr[b + 5] * 31f) << 5)
                        |  (int) (arr[b + 6] * 31f);
                int s = anchorSlot.getOrDefault(key, -1);
                if (s < 0) {
                    if (anchorN == ANCHOR_MAX) continue;    // семейств больше, чем брони — редкий случай
                    s = anchorN++;
                    anchorSlot.put(key, s);
                    anchorDist[s] = Float.MAX_VALUE;
                }
                if (ad < anchorDist[s]) { anchorDist[s] = ad; anchorArr[s] = arr; anchorOff[s] = b; }
            }
        }
        for (int i = 0; i < anchorN; i++) {
            System.arraycopy(anchorArr[i], anchorOff[i], blockPx, n * 8, 8);
            score[n] = Float.MAX_VALUE;     // неприкосновенен: вытеснение ищет МИНИМУМ score
            n++;
        }
        // Ближний источник может попасть и в бронь, и в топ — слот-дубль из 192 погоды не делает,
        // а на смешивании оттенка сказывается ничтожно (тот же цвет уже представлен соседями).

        for (float[] arr : sectionLights.values()) {
            for (int b = 0; b + 8 <= arr.length; b += 8) {
                float dx = (float) (arr[b]     - cx);
                float dy = (float) (arr[b + 1] - cy);
                float dz = (float) (arr[b + 2] - cz);
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                // Значимость = сила (уровень, обрезанный дальностью, = arr[3]) / расстояние.
                // ⚠️ M8.145: штраф за дистанцию СМЯГЧЁН (был /(1+dist)). Причина: источник светит
                // на СВОЮ округу независимо от того, где камера. При жёстком штрафе фонарь за 40
                // блоков получал 15/41 и вытеснялся ближними факелами из топ-MAX_BLOCKS — и блоки
                // вокруг него теряли цвет, сваливаясь в тёплый фолбэк lightTint (репорт: «синие
                // фонари теряют оттенок на расстоянии»). Теперь 15/7 — ближние всё ещё приоритетнее,
                // но дальние не вылетают. Полное решение (запечь цвет в вершины) — отдельная веха.
                float sc = arr[b + 3] / (1.0f + dist * 0.15f);

                if (n == MAX_BLOCKS) {
                    if (sc <= worstScore) continue;
                    System.arraycopy(arr, b, blockPx, worst * 8, 8);
                    score[worst] = sc;
                    worst = -1; worstScore = Float.MAX_VALUE;
                    for (int i = 0; i < MAX_BLOCKS; i++) if (score[i] < worstScore) { worstScore = score[i]; worst = i; }
                } else {
                    System.arraycopy(arr, b, blockPx, n * 8, 8);
                    score[n] = sc;
                    if (sc < worstScore) { worstScore = sc; worst = n; }
                    n++;
                }
            }
        }
        blockN = n;
    }

    /** Вызывать с рендер-потока (доступ к Level) КАЖДЫЙ КАДР. */
    public static void update(Level level, double cx, double cy, double cz) {
        if (failed || level == null) return;
        try {
            if (slots == null) alloc();
            long now = System.currentTimeMillis();

            // --- 1) БЛОКИ: ранжируем ЗАПЕЧЁННЫЕ источники (ТРОТТЛ). Ранжир идёт по списку ВСЕЙ
            //     дальности прорисовки; в светодёнсных зонах он churn-ит буфер (O(N*MAX)) — каждый
            //     кадр это съедало FPS (M8.144d). Достаточно ~7 раз/сек: между кадрами blockPx кэшируется. ---
            if (now - lastScanMs >= SCAN_MS) {
                lastScanMs = now;
                long t0 = System.nanoTime();
                gatherSectionLights(cx, cy, cz);
                if (now - lastLogMs > 5000) {
                    lastLogMs = now;
                    // M8.147: показываем СОСТАВ набора — какие цветовые семейства реально нашлись
                    // и на каком расстоянии их ближайший представитель. Если синего soul-фонаря
                    // тут нет, значит его нет и в sectionLights — и виновата не выборка, а сбор.
                    StringBuilder ab = new StringBuilder();
                    for (int i = 0; i < anchorN; i++) {
                        float[] a = anchorArr[i]; int o = anchorOff[i];
                        ab.append(String.format(" [%.2f %.2f %.2f ур%.0f d=%.0f]",
                                a[o + 4], a[o + 5], a[o + 6], a[o + 3], anchorDist[i]));
                    }
                    int total = 0;
                    for (float[] a : sectionLights.values()) total += a.length / 8;
                    Initializer.LOGGER.info(
                            "[RT] lights: {}/{} в буфере, ВСЕГО {} из {} секций, ранжир {} мкс | якоря:{}",
                            blockN, MAX_BLOCKS, total, sectionLights.size(),
                            (System.nanoTime() - t0) / 1000, ab);
                }
            }

            // --- 2) СУЩНОСТИ: каждый кадр (движутся!) ---
            System.arraycopy(blockPx, 0, framePx, 0, blockN * 8);
            int n = scanEntities(level, cx, cy, cz, blockN);

            // --- 3) заливка в слот кольца (кадр «в полёте» читает прошлый — его не трогаем) ---
            writeSlot = (writeSlot + 1) % SLOTS;
            AccelStruct.RawBuffer buf = slots[writeSlot];
            VkDevice device = Vulkan.getVkDevice();
            try (MemoryStack stack = stackPush()) {
                PointerBuffer pp = stack.mallocPointer(1);
                vkMapMemory(device, buf.memory, 0, (long) MAX * STRIDE, 0, pp);
                ByteBuffer bb = MemoryUtil.memByteBuffer(pp.get(0), MAX * STRIDE).order(ByteOrder.nativeOrder());
                for (int i = 0; i < n; i++)
                    for (int k = 0; k < 8; k++)
                        bb.putFloat(i * STRIDE + k * 4, framePx[i * 8 + k]);
                vkUnmapMemory(device, buf.memory);
            }
            count = n;
            readSlot = writeSlot;
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] RtLights update failed: ", t);
        }
    }

    /** Дорогой скан куба блоков -> кэш blockPx/blockN (топ-MAX_BLOCKS по близости). */
    private static void scanBlocks(Level level, double cx, double cy, double cz) {
        int bx = (int) Math.floor(cx), by = (int) Math.floor(cy), bz = (int) Math.floor(cz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // ⚠️ ОТБОР ПО ЗНАЧИМОСТИ, А НЕ ПО БЛИЗОСТИ (M8.77). Раньше я держал 96 БЛИЖАЙШИХ источников.
        // Пока их было немного, это работало. Но стоило дать свет СКАЛКУ — а в глубокой тьме он
        // покрывает всё, — как ближайшими всегда оказывались скалковые блоки: они вытесняли из списка
        // ЛАВУ и фонарь. Отойдёшь на пару шагов — и лава перестаёт быть источником вовсе, а всё
        // вокруг заливает синевой от скалка. Считаем ЗНАЧИМОСТЬ: сильный источник рядом важнее
        // слабого. Вылетает из списка самый незначимый, а не самый дальний.
        float[] score = new float[MAX_BLOCKS];
        int n = 0, worst = -1;
        float worstScore = Float.MAX_VALUE;

        int minY = Math.max(level.getMinY(), by - R_Y);
        int maxY = Math.min(level.getMaxY() - 1, by + R_Y);

        for (int y = minY; y <= maxY; y++)
            for (int x = bx - R_XZ; x <= bx + R_XZ; x++)
                for (int z = bz - R_XZ; z <= bz + R_XZ; z++) {
                    p.set(x, y, z);
                    BlockState st = level.getBlockState(p);
                    int emit = st.getLightEmission();
                    LightDef def = table().get(st.getBlock());
                    if (emit <= 0 && (def == null || def.floorEmit() == 0)) continue;

                    float dx = (float) (x + 0.5 - cx), dy = (float) (y + 0.5 - cy), dz = (float) (z + 0.5 - cz);
                    float dist2 = dx*dx + dy*dy + dz*dz;
                    // Значимость = сила источника, поделённая на расстояние: яркая лава за 15 блоков
                    // важнее скалковой крапинки под ногами.
                    int lvl = Math.max(emit, def != null ? def.floorEmit() : 0);
                    float sc = lvl / (1.0f + (float) Math.sqrt(dist2));

                    if (n == MAX_BLOCKS) {
                        if (sc <= worstScore) continue;
                        writeLight(blockPx, worst, x, y, z, emit, def);
                        score[worst] = sc;
                        worst = -1; worstScore = Float.MAX_VALUE;
                        for (int i = 0; i < MAX_BLOCKS; i++) if (score[i] < worstScore) { worstScore = score[i]; worst = i; }
                    } else {
                        writeLight(blockPx, n, x, y, z, emit, def);
                        score[n] = sc;
                        if (sc < worstScore) { worstScore = sc; worst = n; }
                        n++;
                    }
                }
        blockN = n;
    }

    /** Дешёвый скан сущностей КАЖДЫЙ КАДР: горящие мобы + БРОШЕННЫЕ СВЕТЯЩИЕСЯ ПРЕДМЕТЫ. */
    private static int scanEntities(Level level, double cx, double cy, double cz, int n) {
        if (!(level instanceof net.minecraft.client.multiplayer.ClientLevel cl)) return n;
        float maxR2 = (float) (R_XZ * R_XZ);
        for (net.minecraft.world.entity.Entity ent : cl.entitiesForRendering()) {
            if (n >= MAX) break;
            float lr, lg, lb, lrange;
            double eyOff = ent.getBbHeight() * 0.5;
            if (ent.isOnFire()) {
                lr = 1.00f; lg = 0.50f; lb = 0.18f; lrange = 8f;          // огонь на сущности
            } else if (ent instanceof net.minecraft.world.entity.item.ItemEntity ie) {
                // M8.17 БРОШЕННЫЙ ПРЕДМЕТ (LambDynamicLights): цвет/дальность из таблицы Eclipse
                ItemStack st = ie.getItem();
                int emit = 0;
                LightDef d = WARM_TORCH;
                if (st.getItem() instanceof BlockItem bi) {
                    BlockState bs = bi.getBlock().defaultBlockState();
                    emit = bs.getLightEmission();
                    LightDef dd = defOf(bs);
                    if (dd != null) d = dd;
                } else if (st.is(Items.LAVA_BUCKET)) {
                    emit = 15; d = new LightDef(0.759f, 0.302f, 0.106f, 8f, 0, MODE_NORMAL);
                }
                if (emit <= 0) continue;
                lr = d.r(); lg = d.g(); lb = d.b();
                lrange = Math.min(emit, d.range());
                eyOff = 0.25;                                              // предмет лежит низко
            } else if (ent instanceof net.minecraft.world.entity.LightningBolt) {
                // === МОЛНИЯ (M8.46) ===
                // Ваниль рисует её через submit-конвейер, мимо нашего захвата сущностей — в TLAS её
                // не было вовсе, поэтому она не светила и не отражалась. Но это самый яркий источник
                // света в игре: столб плазмы, который на пару тиков заливает округу белым.
                // Здесь даём ей свет; сама геометрия болта — отдельно (RtEntities.collectLightning).
                lr = 0.82f; lg = 0.88f; lb = 1.00f;   // холодный электрический белый
                lrange = 30f;                          // бьёт далеко — это не факел
                eyOff = 6.0;                           // центр свечения по высоте столба
            } else {
                continue;
            }
            double ex = ent.getX(), ey = ent.getY() + eyOff, ez = ent.getZ();
            float dx = (float) (ex - cx), dy = (float) (ey - cy), dz = (float) (ez - cz);
            if (dx*dx + dy*dy + dz*dz > maxR2) continue;
            writeRaw(framePx, n, ex, ey, ez, lrange, lr, lg, lb, MODE_POINT);
            n++;
        }
        return n;
    }

    // posRange (x,y,z центр блока, w = дальность в блоках) + цвет (r,g,b, w = РЕЖИМ эмиссии)
    private static void writeLight(float[] a, int i, int x, int y, int z, int emit, LightDef def) {
        LightDef d = (def != null) ? def : WARM_TORCH;   // неизвестный источник -> тёплый факельный
        // Уровень: max(ванильный, искусственный floorEmit), но не выше «художественной» дальности
        int lvl = Math.max(emit, d.floorEmit());
        int o = i * 8;
        a[o    ] = x + 0.5f;
        a[o + 1] = y + 0.5f;
        a[o + 2] = z + 0.5f;
        a[o + 3] = Math.min(lvl, d.range());
        a[o + 4] = d.r();
        a[o + 5] = d.g();
        a[o + 6] = d.b();
        a[o + 7] = d.mode();          // 0=обычный, 1=светлячки, 2=квадратики (для эмиссии в шейдере)
    }

    // Сырой источник (для горящих сущностей): позиция+дальность+цвет+режим напрямую.
    private static void writeRaw(float[] a, int i, double x, double y, double z,
                                 float range, float r, float g, float b, int mode) {
        int o = i * 8;
        a[o] = (float) x; a[o+1] = (float) y; a[o+2] = (float) z;
        a[o+3] = range; a[o+4] = r; a[o+5] = g; a[o+6] = b; a[o+7] = mode;
    }

    private static void alloc() {
        slots = new AccelStruct.RawBuffer[SLOTS];
        for (int i = 0; i < SLOTS; i++)
            slots[i] = AccelStruct.createBuffer((long) MAX * STRIDE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
        Initializer.LOGGER.info("[RT] RtLights: буфер источников света готов ({} слотов x {} огней)", SLOTS, MAX);
    }

    public static void shutdown() {
        if (slots == null) return;
        for (AccelStruct.RawBuffer b : slots) AccelStruct.destroyBuffer(b);
        slots = null;
        count = 0;
    }
}
