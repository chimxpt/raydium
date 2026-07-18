package net.vulkanmod.mixin.texture;

// === RT PATCH (M8.26): доступ к ПИКСЕЛЯМ спрайта ===
// Карта материалов должна знать не только «где спрайт», но и ЧТО в нём: у рельс металл — только
// сами рельсы, шпалы деревянные; у руды блестят только вкрапления. Пиксели лежат в приватном
// SpriteContents.originalImage — берём их аксессором (mixin читает поле, объявленное В ЭТОМ классе,
// поэтому ловушка «@Shadow унаследованного поля» здесь не срабатывает).

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage rtOriginalImage();
}
