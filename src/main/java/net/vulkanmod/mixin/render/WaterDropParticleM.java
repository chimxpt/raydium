package net.vulkanmod.mixin.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.WaterDropParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// === RT PATCH (M8.23d): НАШ ОТТЕНОК дождевых всплесков ===
// Всплеск-капли дождя (WaterDropParticle) шли через ОБЩИЙ партикловый путь и рисовались ванильным
// насыщенно-синим — выбивались из нашей воды. По текстуре их не отличить (общий атлас частиц), но
// у партикла есть СОБСТВЕННЫЙ цвет (умножается на текстуру). Красим его в наш приглушённый сине-серый
// прямо в конструкторе -> корректный цвет ВЕЗДЕ (в т.ч. в отражениях), без флагов и правок шейдера.
//
// ⚠️ Цвет (rCol/gCol/bCol) объявлен в СУПЕРКЛАССЕ SingleQuadParticle, а не в WaterDropParticle —
// @Shadow полей роняет игру ("field not located in target"). Используем ПУБЛИЧНЫЙ setColor() через
// приведение this (WaterDropParticle IS-A SingleQuadParticle).
@Mixin(WaterDropParticle.class)
public class WaterDropParticleM {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtWaterTint(ClientLevel level, double x, double y, double z,
                             TextureAtlasSprite sprite, CallbackInfo ci) {
        ((SingleQuadParticle) (Object) this).setColor(0.32f, 0.44f, 0.52f);   // наш оттенок воды
    }
}
