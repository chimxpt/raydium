package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.state.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

// === RT PATCH (M8.14): ЗАХВАТ ПАРТИКЛОВ ===
// ⚠️ Партиклы НЕ идут через Drawer.draw (там ловятся сущности) — в 1.21.11 они строят свой
// MeshData и рисуются напрямую через RenderPass.setVertexBuffer + drawIndexed. Первый заход
// с хуком в Drawer.draw поэтому вообще не сработал.
//
// Здесь:
//   1) prepare()  -> @Redirect на MeshData.vertexBuffer() перехватывает CPU-буфер ВСЕХ партиклов
//      кадра (формат PARTICLE, 28 Б на вершину; слои лежат подряд).
//   2) render()   -> окно: внутри него VkRenderPass.drawIndexed отдаёт нам ДИАПАЗОН каждого слоя
//      (vertexOffset + indexCount), а текстуру слоя ловит хук в bindTexture.
// Так у каждого слоя своя текстура: у частиц — атлас частиц, у ЛОМАНИЯ БЛОКОВ — блочный атлас.
@Mixin(QuadParticleRenderState.class)
public class QuadParticleRenderStateM {

    @Redirect(method = "prepare",
              at = @At(value = "INVOKE",
                       target = "Lcom/mojang/blaze3d/vertex/MeshData;vertexBuffer()Ljava/nio/ByteBuffer;"))
    private ByteBuffer rtCaptureParticleVerts(MeshData meshData) {
        ByteBuffer buf = meshData.vertexBuffer();
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported)
            net.vulkanmod.vulkan.rt.RtEntities.setParticleVerts(buf);
        return buf;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void rtBeginParticles(CallbackInfo ci) {
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported)
            net.vulkanmod.vulkan.rt.RtEntities.beginParticleRender();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtEndParticles(CallbackInfo ci) {
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported)
            net.vulkanmod.vulkan.rt.RtEntities.endParticleRender();
    }
}
