package net.vulkanmod.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getDepthFar", at = @At("HEAD"), cancellable = true)
    public void getInfiniteDepthFar(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(Float.POSITIVE_INFINITY);
    }

    // === RT PATCH (M8.4): захват РУКИ 1-го лица (обе руки + предмет) в TLAS. ===
    // renderItemInHand рисует руку через СОБСТВЕННЫЙ submit-конвейер GameRenderer
    // (после мира). Открываем окно захвата с тегом HAND: рука попадает в RT и видна
    // первичным лучом (с тенью/самозатенением), но отражённый луч мира её пропускает.
    // === RT PATCH (M8.76): забрать позу покачивания камеры ===
    // Оба метода правят ОДНУ И ТУ ЖЕ позу по очереди, поэтому ловим на выходе из каждого —
    // последний даёт накопленный результат. Camera Overhaul вклинивается в начало bobView и
    // крутит эту же позу, так что его наклон приезжает к нам сам.
    // ⚠️ Их же зовут и для РУКИ (renderItemInHand), но рука рисуется ПОСЛЕ мира, а камеру для
    // трассировки я беру во время отрисовки мира — значит там лежит именно мировая поза.
    // ⚠️ ТОЛЬКО МИРОВАЯ ПОЗА. Эти же два метода игра зовёт и для РУКИ (renderItemInHand), а рука
    // крутится сильно — замер поймал крен 0.609 там, где у покачивания максимум около 0.05. Значит
    // ловилась поза руки. Окно руки у нас уже размечено (rtHandBegin/rtHandEnd), им и отсекаемся.
    @Inject(method = "bobView", at = @At("RETURN"))
    private void rtBobView(com.mojang.blaze3d.vertex.PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (DeviceManager.rayTracingSupported)
            net.vulkanmod.vulkan.rt.RtSnapshot.setBobPose(poseStack.last().pose());
    }

    @Inject(method = "bobHurt", at = @At("RETURN"))
    private void rtBobHurt(com.mojang.blaze3d.vertex.PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (DeviceManager.rayTracingSupported)
            net.vulkanmod.vulkan.rt.RtSnapshot.setBobPose(poseStack.last().pose());
    }

    @Unique private static boolean rtInHand = false;

    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void rtHandBegin(float partialTick, boolean detached, Matrix4f matrix4f, CallbackInfo ci) {
        rtInHand = true;
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported)
            net.vulkanmod.vulkan.rt.RtEntities.beginHand();
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void rtHandEnd(float partialTick, boolean detached, Matrix4f matrix4f, CallbackInfo ci) {
        rtInHand = false;
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported) {
            try {   // дофлашить последний слой руки В ОКНЕ (иначе флашится позже = не поймаем)
                Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            } catch (Throwable ignored) {}
            net.vulkanmod.vulkan.rt.RtEntities.endHand();
        }
    }

}
