package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * === RT PATCH (M8.123c) === Ванильный ОГНЕННЫЙ оверлей глушим, когда работает наш пост:
 * свой огонь (языки пламени + тёплый тинт) рисует RtPost — и в RT, и в растровом фолбэке.
 * В RT ваниль и так перезаписывалась целиком блитом кадра, а вот в фолбэке ванильный
 * огонь оставался в кадре ПОД нашим — двойное пламя (репорт со скрином). Подводный и
 * лавовый оверлеи НЕ трогаем: в фолбэке их пока честно рисует ваниль.
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererM {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void rtSkipVanillaFire(CallbackInfo ci) {
        var cfg = net.vulkanmod.Initializer.CONFIG;
        if (cfg.rtEnabled || cfg.shadersEnabled) ci.cancel();
    }
}
