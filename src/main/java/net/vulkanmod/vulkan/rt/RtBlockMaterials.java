package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.25h): список блоков с особым RT-материалом ===
// ⚠️ ПОЧЕМУ ОТДЕЛЬНЫЙ КЛАСС, А НЕ ПОЛЕ В MIXIN'Е: mixin ВЛИВАЕТ инициализаторы своих статических
// полей в <clinit> ЦЕЛЕВОГО класса. Набор `Set.of(Blocks.GOLD_BLOCK, ...)` внутри @Mixin(Block.class)
// попадал прямо в Block.<clinit>, который лез в Blocks, а тот в этот момент сам конструирует
// Block'и -> круговая инициализация -> NPE на бутстрапе (краш 255).
//
// Здесь набор строится ЛЕНИВО, при первом обращении (мешинг чанков идёт много позже загрузки
// классов), поэтому Blocks к тому моменту уже полностью инициализирован.

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

public class RtBlockMaterials {

    private static volatile Set<Block> set;

    /** Есть ли у блока особый RT-материал (зеркальный/прозрачный)? */
    public static boolean isRtMaterial(Block b) {
        Set<Block> s = set;
        if (s == null) {
            s = Set.of(
                    // металлы
                    Blocks.GOLD_BLOCK, Blocks.RAW_GOLD_BLOCK,
                    Blocks.IRON_BLOCK, Blocks.RAW_IRON_BLOCK,
                    Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.EXPOSED_COPPER,
                    Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER, Blocks.RAW_COPPER_BLOCK,
                    Blocks.NETHERITE_BLOCK, Blocks.ANCIENT_DEBRIS,
                    // самоцветы / стекловидные
                    Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
                    Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK,
                    Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
                    // прозрачные в RT, но SOLID-геометрия в ваниле
                    Blocks.PACKED_ICE, Blocks.BLUE_ICE
            );
            set = s;
        }
        return s.contains(b);
    }
}
