package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

// === RT PATCH (M8.22b): МЕДЛЕННЕЕ ПАДЕНИЕ КАПЕЛЬ ДОЖДЯ ===
// Ваниль анимирует падение струек через vOffset = (time)/32.0 * speed. Увеличиваем делитель ->
// капли падают медленнее, БЕЗ рывков (время непрерывное). ordinal=0 бьёт только по делителю
// скорости; второй 32.0f в методе — период тайла текстуры (wrap), его не трогаем, чтобы не менять
// вид струек. Снег считается иначе (свои константы) и так медленный — его не трогаем.
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererM {

    @ModifyConstant(method = "createRainColumnInstance",
                    constant = @Constant(floatValue = 32.0f, ordinal = 0))
    private float rtSlowRainFall(float original) {
        return original;   // ОТКЛЮЧЕНО (проверка FPS): вернули ванильную скорость падения.
                           // Замедление — смена константы, GPU-стоимость нулевая; не влияет на FPS.
    }
}
