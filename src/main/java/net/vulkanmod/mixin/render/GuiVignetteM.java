package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * === RT PATCH (M8.123d) === Ванильная ВИНЬЕТКА рисуется в GUI-стадии — ПОВЕРХ нашего
 * композита, то есть стакалась с нашей виньеткой из поста и в RT, и в растровом фолбэке
 * (у нас своя ручка «Виньетка» на странице «Изображение»). Глушим ванильную, когда
 * работает наш пост. Остальные оверлеи renderCameraOverlays (тыква, иней, портал,
 * подзорная труба) не заменяем — их не трогаем.
 */
@Mixin(Gui.class)
public class GuiVignetteM {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void rtSkipVanillaVignette(CallbackInfo ci) {
        var cfg = net.vulkanmod.Initializer.CONFIG;
        if (cfg.rtEnabled || cfg.shadersEnabled) ci.cancel();
    }
}
