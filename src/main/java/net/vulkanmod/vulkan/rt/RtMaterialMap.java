package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.24 + M8.26): КАРТА МАТЕРИАЛОВ ===
// Геометрия чанка не несёт ID блока — только UV в блочном атласе. Чтобы опознать «это золото/алмаз/
// обсидиан» в трассировке БЕЗ правки мешера и БЕЗ цикла на пиксель, строим текстуру-карту В ТЕХ ЖЕ
// UV, что атлас, и кладём в каждый тексель байт material ID. В шейдере одна выборка
// texture(materialMap, hitUV).r -> сразу материал. Дёшево (O(1)) и надёжно.
//
// M8.26 — КАРТА СТАЛА ПОПИКСЕЛЬНОЙ (по просьбе: «у рельс есть деревянные шпалы, они не должны
// отражать»). Раньше заливался весь прямоугольник спрайта. Теперь читаем сами пиксели текстуры
// (SpriteContentsAccessor) и заливаем ТОЛЬКО подходящие:
//   MASK_GRAY    — серый металл (рельсы блестят, шпалы нет)
//   MASK_SPECKLE — вкрапления, отличные от фона (в руде блестит руда, не камень)
//   MASK_RED     — красные лампочки редстоуна (эмиссия)
// Разрешение карты = разрешению атласа, поэтому 1 тексель карты = 1 тексель текстуры: маска точная.

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.vulkanmod.Initializer;
import net.vulkanmod.mixin.texture.SpriteContentsAccessor;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class RtMaterialMap {

    // --- режимы маски ---
    private static final int ALL     = 0;   // весь спрайт (сплошной материал: блок золота, стекло…)
    private static final int GRAY    = 1;   // только СЕРЫЙ МЕТАЛЛ (замер: металл sat<0.30, дерево шпал sat~0.40)
    private static final int SPECKLE = 2;   // только ВКРАПЛЕНИЯ (пиксели, далёкие от фонового цвета)
    private static final int RED     = 3;   // только КРАСНЫЕ ЛАМПОЧКИ (редстоун под сигналом)

    // Спрайт -> {material ID, режим маски}. ID соответствует matProps()/эмиссии в шейдере.
    private static final Object[][] MATS = {
            // --- ЗОЛОТО (1) ---
            {"block/gold_block", 1, ALL}, {"block/raw_gold_block", 1, ALL},
            {"block/bell_top", 1, ALL}, {"block/bell_side", 1, ALL}, {"block/bell_bottom", 1, ALL},
            // золотые крапины В ЧЁРНОМ КАМНЕ: блестят только они
            {"block/gilded_blackstone", 1, SPECKLE},
            // --- ЖЕЛЕЗО (2): блок сплошной, утварь — только серый металл (шпалы/дерево не блестят) ---
            {"block/iron_block", 2, ALL}, {"block/raw_iron_block", 2, ALL}, {"block/iron_bars", 2, ALL},
            {"block/anvil", 2, GRAY}, {"block/anvil_top", 2, GRAY},
            {"block/chipped_anvil_top", 2, GRAY}, {"block/damaged_anvil_top", 2, GRAY},
            {"block/iron_chain", 2, GRAY},                       // ⚠️ в 1.21.11 спрайт зовётся iron_chain
            {"block/iron_door_top", 2, GRAY}, {"block/iron_door_bottom", 2, GRAY}, {"block/iron_trapdoor", 2, GRAY},
            {"block/cauldron_side", 2, GRAY}, {"block/cauldron_top", 2, GRAY},
            {"block/cauldron_bottom", 2, GRAY}, {"block/cauldron_inner", 2, GRAY},
            {"block/hopper_outside", 2, GRAY}, {"block/hopper_top", 2, GRAY}, {"block/hopper_inside", 2, GRAY},
            {"block/rail", 2, GRAY}, {"block/rail_corner", 2, GRAY},
            {"block/powered_rail", 2, GRAY}, {"block/powered_rail_on", 2, GRAY},
            {"block/detector_rail", 2, GRAY}, {"block/detector_rail_on", 2, GRAY},
            {"block/activator_rail", 2, GRAY}, {"block/activator_rail_on", 2, GRAY},
            {"block/lantern", 2, GRAY}, {"block/soul_lantern", 2, GRAY}, {"block/heavy_core", 2, GRAY},
            // --- МЕДЬ (3): база + цепи; окисление и изделия — в цикле COPPER_* ниже ---
            {"block/copper_block", 3, ALL}, {"block/raw_copper_block", 3, ALL},
            {"block/lightning_rod", 3, ALL}, {"block/lightning_rod_on", 3, ALL},
            // --- НЕЗЕРИТ (4) ---
            {"block/netherite_block", 4, ALL},
            {"block/ancient_debris_side", 4, ALL}, {"block/ancient_debris_top", 4, ALL},
            // --- САМОЦВЕТЫ (5-9) ---
            {"block/diamond_block", 5, ALL},
            {"block/emerald_block", 6, ALL},
            {"block/obsidian", 7, ALL}, {"block/crying_obsidian", 7, ALL},
            {"block/lapis_block", 8, ALL},
            {"block/redstone_block", 9, ALL},
            // ⚠️ ВОДА ПОМЕЧЕНА ЯВНО (30). Раньше «водой» считалось ВСЁ прозрачное без материала — и
            // стеклянная панель, чей спрайт в карту не попал, честно вела себя как вода: преломляла
            // руку, синила мир. Угадывать спрайты панелей по одному — тупик. Теперь наоборот: воду
            // опознаём по её собственной текстуре, а всё прочее прозрачное считаем стеклом.
            {"block/water_still", 30, ALL}, {"block/water_flow", 30, ALL},
            {"block/water_overlay", 30, ALL},
            // --- ПРОЗРАЧНЫЕ (10..15) — своя ветка в шейдере ---
            {"block/glass", 10, ALL}, {"block/tinted_glass", 10, ALL},
            // ⚠️ КРОМКЕ ПАНЕЛИ (glass_pane_top) МАТЕРИАЛ НЕ НУЖЕН ВОВСЕ. Я сперва сделал её стеклом,
            // потом непрозрачной (18) — и получил тёмный крест на стыке панелей. После того как воду
            // стали помечать явно, всё прозрачное БЕЗ материала и так считается стеклом: кромка
            // становится стеклом сама, без единой записи здесь.
            {"block/ice", 11, ALL},
            {"block/slime_block", 12, ALL},
            {"block/honey_block_top", 13, ALL}, {"block/honey_block_side", 13, ALL},
            // ⚠️ Плотный/синий лёд — SOLID (непрозрачная геометрия!), а не TRANSLUCENT. Сквозь них
            // смотрим САМИ (луч пропускания с отсечением задних граней). 14 плотный, 15 синий.
            {"block/packed_ice", 14, ALL},
            {"block/blue_ice", 15, ALL},

            // ================== ВТОРАЯ ВОЛНА (M8.26) ==================
            // --- 16: ПОЛИРОВАННЫЙ КАМЕНЬ (шлифовка -> слабое зеркало) ---
            {"block/polished_andesite", 16, ALL}, {"block/polished_diorite", 16, ALL},
            {"block/polished_granite", 16, ALL}, {"block/polished_deepslate", 16, ALL},
            {"block/polished_blackstone", 16, ALL}, {"block/polished_tuff", 16, ALL},
            {"block/polished_basalt_side", 16, ALL}, {"block/polished_basalt_top", 16, ALL},
            {"block/smooth_stone", 16, ALL}, {"block/smooth_stone_slab_side", 16, ALL},
            {"block/smooth_basalt", 16, ALL},
            // --- 17: КВАРЦ (IOR 1.55) ---
            {"block/quartz_block_side", 17, ALL}, {"block/quartz_block_top", 17, ALL},
            {"block/quartz_block_bottom", 17, ALL}, {"block/quartz_bricks", 17, ALL},
            {"block/quartz_pillar", 17, ALL}, {"block/quartz_pillar_top", 17, ALL},
            {"block/chiseled_quartz_block", 17, ALL}, {"block/chiseled_quartz_block_top", 17, ALL},
            // --- 19: РУДЫ — блестят ТОЛЬКО вкрапления, камень-подложка матовый ---
            // (уголь намеренно НЕ включён: антрацит матовый, блестеть ему незачем)
            {"block/iron_ore", 19, SPECKLE}, {"block/deepslate_iron_ore", 19, SPECKLE},
            {"block/copper_ore", 19, SPECKLE}, {"block/deepslate_copper_ore", 19, SPECKLE},
            {"block/gold_ore", 19, SPECKLE}, {"block/deepslate_gold_ore", 19, SPECKLE},
            {"block/redstone_ore", 19, SPECKLE}, {"block/deepslate_redstone_ore", 19, SPECKLE},
            {"block/emerald_ore", 19, SPECKLE}, {"block/deepslate_emerald_ore", 19, SPECKLE},
            {"block/lapis_ore", 19, SPECKLE}, {"block/deepslate_lapis_ore", 19, SPECKLE},
            {"block/diamond_ore", 19, SPECKLE}, {"block/deepslate_diamond_ore", 19, SPECKLE},
            {"block/nether_gold_ore", 19, SPECKLE}, {"block/nether_quartz_ore", 19, SPECKLE},
            // --- 20: АМЕТИСТ (кристалл) ---
            {"block/amethyst_block", 20, ALL}, {"block/budding_amethyst", 20, ALL},
            {"block/amethyst_cluster", 20, ALL}, {"block/large_amethyst_bud", 20, ALL},
            {"block/medium_amethyst_bud", 20, ALL}, {"block/small_amethyst_bud", 20, ALL},
            // --- 21: ПРИЗМАРИН (мокрый камень моря) ---
            {"block/prismarine", 21, ALL}, {"block/prismarine_bricks", 21, ALL},
            {"block/dark_prismarine", 21, ALL}, {"block/sea_lantern", 21, ALL},

            // ================== РЕДСТОУН, СВЕТЯЩИЙСЯ ПО СИГНАЛУ (M8.26) ==================
            // 23 — ПЫЛЬ: сила сигнала (0..15) приходит в ЦВЕТЕ ВЕРШИНЫ (ваниль тинтует пыль от
            //      тёмно-бордового к алому). Шейдер берёт яркость тинта -> яркость свечения.
            {"block/redstone_dust_dot", 23, ALL}, {"block/redstone_dust_line0", 23, ALL},
            {"block/redstone_dust_line1", 23, ALL}, {"block/redstone_dust_overlay", 23, ALL},
            // 24 — ГОРЯЩИЕ ЛАМПОЧКИ: у «включённых» блоков ОТДЕЛЬНЫЕ текстуры, поэтому состояние
            //      знать не нужно. Светятся ТОЛЬКО красные пиксели (маска RED), а не весь блок.
            {"block/powered_rail_on", 24, RED}, {"block/detector_rail_on", 24, RED},
            {"block/activator_rail_on", 24, RED},
            {"block/repeater_on", 24, RED}, {"block/comparator_on", 24, RED},
            {"block/observer_back_on", 24, RED},
            {"block/redstone_torch", 24, RED}, {"block/redstone_lamp_on", 24, ALL},
    };

    // 16 цветов Minecraft: витражное стекло -> 10, глазурованная терракота -> 18
    private static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };
    // Медь 1.21: каждая стадия окисления × каждое изделие. Воскованные (waxed_*) блоки используют
    // ТЕ ЖЕ спрайты, поэтому покрываются автоматически. Несуществующие имена fillSprite пропустит
    // и напишет их в лог (чтобы опечатка не пряталась).
    private static final String[] COPPER_AGE = {"", "exposed_", "weathered_", "oxidized_"};
    private static final String[] COPPER_PARTS = {
            "copper", "cut_copper", "chiseled_copper", "copper_grate", "copper_chain",
            "copper_bulb", "copper_bulb_lit", "copper_bulb_powered", "copper_bulb_lit_powered",
            "copper_door_top", "copper_door_bottom", "copper_trapdoor",
    };

    // M8.141: КРУПНЫЕ ВСЕГДА-СВЕТЯЩИЕСЯ блоки — цвет эмиссии (0..255) пишем в GBA карты, чтобы
    // блок светился своим цветом на ЛЮБОЙ дистанции (буфер источников — только 20 блоков). Формат
    // {спрайт, R, G, B}. Значения — свои (близко к палитре RtLights). Мелкие спец-режимы (ягоды/
    // лишайник/пламя/факелы) СЮДА НЕ кладём — они локальны, останутся на буфере.
    private static final Object[][] EMISSIVE = {
            {"block/lava_still",        255, 130, 42},   {"block/lava_flow",         255, 122, 40},
            {"block/glowstone",         255, 205, 120},  {"block/nether_portal",     174, 74, 224},
            {"block/sea_lantern",       180, 232, 255},  {"block/shroomlight",       255, 150, 66},
            {"block/magma",             208, 92, 40},    {"block/redstone_lamp_on",  255, 112, 48},
            {"block/ochre_froglight_side",     255, 224, 150},
            {"block/verdant_froglight_side",   190, 255, 176},
            {"block/pearlescent_froglight_side", 246, 190, 236},
            // Око древности в рамке портала Энда — крипкое бирюзовое свечение. Спрайт рендерится
            // ТОЛЬКО на рамке с оком (модель _filled), поэтому зажигается ровно на активных рамках.
            {"block/end_portal_frame_eye",     90, 235, 200},
            // M8.150 СКАЛК ПЕРЕЕХАЛ СЮДА ИЗ БУФЕРА ИСТОЧНИКОВ. Им покрыт весь Deep Dark — замер
            // дал 280 711 источников на 192 слота буфера. Состав буфера плясал при каждом шаге
            // камеры: фонари душ ГАСЛИ (их собственное свечение читает тот же буфер), свечи
            // теряли оттенок, по полу ходили мигающие пятна. В ванилле скалк светит НОЛЬ —
            // светимость ему придумали мы, значит его место именно тут: слот не занимает, от
            // дистанции не зависит, а EMIS_CURVE зажигает только яркие пятнышки, гася тёмное
            // тело (проверено счётом: пятно 0.42^2=0.18, тело 0.045^2=0.002).
            {"block/sculk",                    90, 220, 235},
            {"block/sculk_vein",               84, 212, 230},
            // M8.151 ФОНАРИ — тоже сюда. Их собственное свечение читало БУФЕР источников (192
            // слота на всю сцену), и стоило фонарю из него вылететь, как он ПЕРЕСТАВАЛ СВЕТИТЬСЯ:
            // оставалась голая текстура, подкрашенная ближайшей лампой. Замер на скрине поймал это
            // точно — фонарь ДУШ светил R160 G96 B50, то есть тёплым цветом соседней свечи.
            // Свечение лампы не должно зависеть от того, попала ли она в список ближних к камере;
            // у лавы, светокамня и ока портала оно давно берётся отсюда и не ломается никогда.
            // Оба фонаря — закрытые лампы (в палитре помечены «НЕ мерцает»), так что переезд
            // ничего не отнимает. Буфер по-прежнему задаёт ОТТЕНОК, который лампа бросает вокруг.
            {"block/soul_lantern",             26, 158, 255},
            {"block/lantern",                 203, 143,  49},
            // M8.152: сенсор и шрайкер — следом за скалком. В ванилле они светят 1 и 0, то есть
            // источниками не являются; но в Ancient City их тысячи, и по логу они занимали слоты
            // буфера на дистанциях 5-8 блоков, вытесняя дальние лампы — отчего округа тех ламп
            // красилась цветом ближайшей свечи. Катализатор НЕ трогаем: он в ванилле честно
            // светит 6, его единицы, и он остаётся настоящим источником.
            {"block/sculk_sensor_top",             96, 232, 245},
            {"block/sculk_sensor_side",            92, 226, 240},
            {"block/sculk_sensor_tendril_active", 105, 240, 255},
            {"block/sculk_shrieker_top",           98, 230, 242},
            {"block/sculk_shrieker_side",          92, 224, 238},
            {"block/sculk_shrieker_inner_top",    105, 238, 252},
            {"block/sculk_shrieker_can_summon_inner_top", 110, 242, 255},
    };

    private static VulkanImage tex;
    private static boolean failed = false;
    private static final StringBuilder missed = new StringBuilder();

    public static VulkanImage get() { return tex; }

    // === RT PATCH (M8.100): перезагрузка ресурсов (F3+T / смена паков) пересобирает атлас —
    // спрайты меняют UV, и карта устаревает: вода теряла материал 30 и шейдилась стеклом.
    // Старую текстуру рушим ОТЛОЖЕННО (кадры в полёте могли её читать): держим до 2 отставных.
    private static final java.util.ArrayList<VulkanImage> retired = new java.util.ArrayList<>();
    public static synchronized void invalidate() {
        if (tex != null) { retired.add(tex); tex = null; }
        while (retired.size() > 2) retired.remove(0).free();   // 2 перезагрузки назад — GPU не читает
        failed = false;
        missed.setLength(0);
    }
    // === /RT PATCH ===


    // --- разбор пикселя (NativeImage.getPixel = ARGB, проверено по байткоду) ---
    private static int a(int p) { return (p >>> 24) & 0xFF; }
    private static int r(int p) { return (p >> 16) & 0xFF; }
    private static int g(int p) { return (p >> 8) & 0xFF; }
    private static int b(int p) { return p & 0xFF; }

    /** Насыщенность HSL: 0 — серый, 1 — чистый цвет. Металл в MC серый, дерево/редстоун — нет. */
    private static float sat(int p) {
        int mx = Math.max(r(p), Math.max(g(p), b(p)));
        int mn = Math.min(r(p), Math.min(g(p), b(p)));
        if (mx == mn) return 0f;
        int sum = mx + mn;
        return sum <= 255 ? (mx - mn) / (float) sum : (mx - mn) / (float) (510 - sum);
    }

    /**
     * МЕДИАННАЯ НАСЫЩЕННОСТЬ спрайта = насыщенность ФОНА (камня-подложки): подложка занимает больше
     * половины пикселей, значит медиана — это она.
     * ⚠️ Так выглядел первый (сломанный) подход: искать «самый частый ЦВЕТ» с квантованием. Он давал
     * (а) битую распаковку кванта и (б) ложные срабатывания на ТЕНЕВЫХ ШТРИХАХ камня — они отличаются
     * от фона ЯРКОСТЬЮ, и весь блок помечался как руда. Вкрапление отличает не яркость, а ЦВЕТНОСТЬ.
     */
    private static float medianSat(NativeImage img, int w, int h) {
        float[] s = new float[w * h];
        int n = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = img.getPixel(x, y);
                if (a(p) < 128) continue;
                s[n++] = sat(p);
            }
        if (n == 0) return 0f;
        float[] cut = java.util.Arrays.copyOf(s, n);
        java.util.Arrays.sort(cut);
        return cut[n / 2];
    }

    /** Проходит ли пиксель маску данного режима? bgSat — насыщенность фона (только для SPECKLE). */
    private static boolean pass(int p, int mode, float bgSat) {
        if (a(p) < 128) return false;                       // прозрачное (вокруг рельсы/цепи) — не материал
        switch (mode) {
            case GRAY:                                      // металл серый; шпалы (sat~0.40) отсекаются
                return sat(p) < 0.30f;
            case SPECKLE:                                   // вкрапление = цветность НЕ как у подложки
                // Работает в обе стороны: цветная руда в сером камне И белые кристаллы кварца в
                // красном незерраке. Замер: у всех руд помечается 15-29% пикселей (сами вкрапления).
                return Math.abs(sat(p) - bgSat) > 0.12f;
            case RED:                                       // горящая лампочка: насыщенно-красная
                return r(p) > 90 && r(p) > g(p) * 1.6f && r(p) > b(p) * 1.6f;
            default:
                return true;
        }
    }

    /** Залить пиксели спрайта, прошедшие маску, байтом matId. */
    private static void fillSprite(TextureAtlas atlas, byte[] map, int mapW, int mapH,
                                   String name, int id, int mode, int[] filled) {
        Identifier idn = Identifier.withDefaultNamespace(name);
        TextureAtlasSprite sprite = atlas.getSprite(idn);
        if (sprite == null || !idn.equals(sprite.contents().name())) {
            // Спрайта нет (в этой версии MC он зовётся иначе) — в лог, чтобы опечатка не пряталась:
            // молчаливый пропуск оставил бы материал просто НЕ РАБОТАЮЩИМ, и это было бы незаметно.
            if (missed.length() < 900) missed.append(name).append(' ');
            return;
        }
        var contents = sprite.contents();
        NativeImage img = ((SpriteContentsAccessor) (Object) contents).rtOriginalImage();
        if (img == null) return;
        int sw = Math.min(contents.width(), img.getWidth());
        int sh = Math.min(contents.height(), img.getHeight());   // только ПЕРВЫЙ кадр анимации
        int x0 = Math.round(sprite.getU0() * mapW);
        int y0 = Math.round(sprite.getV0() * mapH);
        float bgSat = mode == SPECKLE ? medianSat(img, sw, sh) : 0f;

        int painted = 0;
        for (int py = 0; py < sh; py++) {
            int my = y0 + py;
            if (my < 0 || my >= mapH) continue;
            for (int px = 0; px < sw; px++) {
                int mx = x0 + px;
                if (mx < 0 || mx >= mapW) continue;
                if (!pass(img.getPixel(px, py), mode, bgSat)) continue;
                map[(my * mapW + mx) * 4] = (byte) id;
                painted++;
            }
        }
        if (painted > 0) filled[0]++;
    }

    /** M8.141: ЦВЕТ ЭМИССИИ спрайта -> GBA карты (R не трогаем — там matId). Пишем всем непрозрачным
     *  текселям; «только яркие светятся» доделывает шейдер кривой по луме albedo. Так эмиссивный блок
     *  светится своим цветом на любой дистанции, минуя буфер источников. */
    private static void fillEmissive(TextureAtlas atlas, byte[] map, int mapW, int mapH,
                                     String name, int er, int eg, int eb, int[] filled) {
        Identifier idn = Identifier.withDefaultNamespace(name);
        TextureAtlasSprite sprite = atlas.getSprite(idn);
        if (sprite == null || !idn.equals(sprite.contents().name())) {
            if (missed.length() < 900) missed.append(name).append(' ');
            return;
        }
        var contents = sprite.contents();
        NativeImage img = ((SpriteContentsAccessor) (Object) contents).rtOriginalImage();
        if (img == null) return;
        int sw = Math.min(contents.width(), img.getWidth());
        int sh = Math.min(contents.height(), img.getHeight());
        int x0 = Math.round(sprite.getU0() * mapW);
        int y0 = Math.round(sprite.getV0() * mapH);
        int painted = 0;
        for (int py = 0; py < sh; py++) {
            int my = y0 + py;
            if (my < 0 || my >= mapH) continue;
            for (int px = 0; px < sw; px++) {
                int mx = x0 + px;
                if (mx < 0 || mx >= mapW) continue;
                if (a(img.getPixel(px, py)) < 8) continue;   // прозрачные пиксели не светятся
                int idx = (my * mapW + mx) * 4;
                map[idx + 1] = (byte) er;   // G = эмиссия R
                map[idx + 2] = (byte) eg;   // B = эмиссия G
                map[idx + 3] = (byte) eb;   // A = эмиссия B
                painted++;
            }
        }
        if (painted > 0) filled[0]++;
    }

    /** Ленивая сборка: атлас должен быть загружен. Зовём каждый кадр из descriptor-записи, пока не выйдет. */
    public static void init() {
        if (tex != null || failed) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            var abs = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            if (!(abs instanceof TextureAtlas atlas)) return;
            // Готов ли атлас? getWidth() package-private — пробуем известный спрайт: если он «missing»,
            // атлас ещё не сшит -> выходим и пробуем в следующем кадре (иначе построим пустую карту).
            Identifier probeId = Identifier.withDefaultNamespace("block/stone");
            TextureAtlasSprite probe = atlas.getSprite(probeId);
            if (probe == null || !probeId.equals(probe.contents().name())) return;

            // РАЗМЕР АТЛАСА — из пропорции спрайта: sprite.width / (u1-u0). Карта должна быть ТОЧНО
            // такой же, иначе один тексель карты накроет несколько текселей текстуры и маска (рельсы,
            // вкрапления руды) размажется на соседей.
            float du = probe.getU1() - probe.getU0();
            float dv = probe.getV1() - probe.getV0();
            if (du <= 0f || dv <= 0f) return;
            int mapW = Math.round(probe.contents().width() / du);
            int mapH = Math.round(probe.contents().height() / dv);
            // M8.103: «искорки на соседних текстурах». Размер по пропорции может ошибиться
            // на ±1 тексель (float-округление, зависит от ресурспака) — тогда ВСЕ маски
            // съезжают, и вкрапления (SPECKLE) рисуются на СОСЕДНИХ спрайтах атласа.
            // Берём РЕАЛЬНЫЙ размер захваченного атласа; пропорция — фолбэк и контроль.
            var atlasImg = RtSnapshot.atlasImage();
            if (atlasImg != null) {
                if (atlasImg.width != mapW || atlasImg.height != mapH)
                    Initializer.LOGGER.warn("[RT] atlas size: actual {}x{}, derived from UVs {}x{} - using the actual one",
                            atlasImg.width, atlasImg.height, mapW, mapH);
                mapW = atlasImg.width;
                mapH = atlasImg.height;
            }
            if (mapW < 256 || mapH < 256 || mapW > 8192 || mapH > 8192) {
                Initializer.LOGGER.warn("[RT] implausible atlas size {}x{} - material map skipped", mapW, mapH);
                failed = true;
                return;
            }

            byte[] map = new byte[mapW * mapH * 4];   // RGBA: R = matId (отражения), GBA = ЦВЕТ ЭМИССИИ (M8.141)
            int[] filled = {0};
            for (Object[] m : MATS)
                fillSprite(atlas, map, mapW, mapH, (String) m[0], (Integer) m[1], (Integer) m[2], filled);
            // M8.141: ЦВЕТ ЭМИССИИ в GBA — блок светится своим цветом на ЛЮБОЙ дистанции (не зависит
            // от буфера источников, который сканируется лишь в 20 блоках). Фикс «портал/лава светятся
            // огнём вдали». Только КРУПНЫЕ всегда-светящиеся блоки (NORMAL-режим); мелкие спец-режимы
            // (ягоды/лишайник/пламя) остаются на буфере — они локальны, дистанция им не важна.
            for (Object[] e : EMISSIVE)
                fillEmissive(atlas, map, mapW, mapH, (String) e[0],
                             (Integer) e[1], (Integer) e[2], (Integer) e[3], filled);
            for (String c : COLORS) {
                fillSprite(atlas, map, mapW, mapH, "block/" + c + "_stained_glass", 10, ALL, filled);
                fillSprite(atlas, map, mapW, mapH, "block/" + c + "_glazed_terracotta", 18, ALL, filled);
            }
            for (String age : COPPER_AGE)
                for (String part : COPPER_PARTS)
                    fillSprite(atlas, map, mapW, mapH, "block/" + age + part, 3, ALL, filled);

            ByteBuffer buf = MemoryUtil.memAlloc(map.length);
            buf.put(map);
            buf.flip();
            VulkanImage image = VulkanImage.builder(mapW, mapH)
                    .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                    .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(false)   // matId точный, БЕЗ интерполяции между соседними спрайтами
                    .setClamp(true)
                    .createVulkanImage();
            image.uploadSubTextureAsync(0, 0, mapW, mapH, 0, 0, 0, 0, mapW, buf);
            MemoryUtil.memFree(buf);

            tex = image;
            Initializer.LOGGER.info("[RT] material map {}x{}: {} sprites filled", mapW, mapH, filled[0]);
            if (missed.length() > 0)
                Initializer.LOGGER.info("[RT] sprites NOT found: {}", missed.toString().trim());
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] material map build failed: ", t);
        }
    }
}
