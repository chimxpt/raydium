package net.vulkanmod.mixin.render;

// === RT PATCH (M8.28): отладочные клавиши — F6 (guide-буферы), F7 (знак jitter), F8 (DLSS вкл/выкл)
//
// ⚠️ ОТКЛЮЧЁН: класс не зарегистрирован в vulkanmod.mixins.json, клавиши в игре не работают.
// Оставлен намеренно — этими тремя переключателями были найдены причины дрожания экрана, ночных
// «пастельных полос» и провала теней в черноту. Если снова понадобится смотреть, что именно
// скармливается нейросети, достаточно вернуть строку "render.RtDebugKeyM" в mixins.json.

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.rt.RtPost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class RtDebugKeyM {

    private static final String[] NAMES = {
            "off", "depth", "motion vectors", "normals", "diffuse albedo",
            "specular albedo (F0)", "roughness", "denoiser history"
    };

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void rtDebugKey(long window, int action, KeyEvent keyEvent, CallbackInfo ci) {
        if (action != 1 || !DeviceManager.rayTracingSupported) return;   // 1 = GLFW_PRESS
        Minecraft mc = Minecraft.getInstance();

        if (keyEvent.key() == 295) {                                     // F6 — просмотр guide-буферов
            RtPost.debugView = (RtPost.debugView + 1) % RtPost.DEBUG_MODES;
            if (mc.gui != null)
                mc.gui.setOverlayMessage(Component.literal("[RT] buffer: " + NAMES[RtPost.debugView]), false);
        } else if (keyEvent.key() == 296) {                              // F7 — знаки jitter для DLSS
            String mode = net.vulkanmod.vulkan.rt.RtDlss.cycleJitter();
            if (mc.gui != null)
                mc.gui.setOverlayMessage(Component.literal("[RT] DLSS jitter: " + mode), false);
        } else if (keyEvent.key() == 298) {                              // F9 — дамп батчей РУКИ (M8.126b)
            // Диагностика «картонной коробки» (Actually 3D Stuff + Hold My Items): какой
            // рендер-тайп несёт предмет и какая текстура ему досталась. Пишет в latest.log.
            net.vulkanmod.vulkan.rt.RtEntities.armHandDump();
            if (mc.gui != null)
                mc.gui.setOverlayMessage(Component.literal("[RT] hand batch dump -> latest.log"), false);
        } else if (keyEvent.key() == 297) {                              // F8 — САМ DLSS вкл/выкл
            // Нужен, чтобы отличать «так рисует наш шейдер» от «так это перерисовала нейросеть».
            net.vulkanmod.vulkan.rt.RtDlss.enabled = !net.vulkanmod.vulkan.rt.RtDlss.enabled;
            if (mc.gui != null)
                mc.gui.setOverlayMessage(Component.literal(
                        "[RT] DLSS: " + (net.vulkanmod.vulkan.rt.RtDlss.enabled ? "ВКЛ" : "ВЫКЛ (шумный кадр как есть)")), false);
        }
    }
}
