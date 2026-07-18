package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiItemRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.render.engine.VkRenderPass;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @Shadow @Final private GuiRenderState renderState;
    @Shadow private @Nullable GpuTextureView itemsAtlasView;

    // === RT PATCH (M8.6 v1): блит RT в swapchain ПЕРЕД отрисовкой GUI -> HUD/меню/F3 поверх RT ===
    @Inject(method = "render", at = @At("HEAD"))
    private void rtCompositeBeforeGui(GpuBufferSlice gpuBufferSlice, CallbackInfo ci) {
        // M8.123b: НЕ рубить по rayTracingSupported/RtScreen.enabled — растровый фолбэк
        // («Шейдеры» без RT) живёт в том же композите и должен вызываться без трассировки.
        // Из-за старого гейта пост фолбэка не запускался вовсе (репорт: «где SSAO?»).
        // Точные проверки по режимам — внутри compositeRtBeforeGui.
        if (!net.vulkanmod.vulkan.rt.RtScreen.enabled
                && !net.vulkanmod.Initializer.CONFIG.shadersEnabled) return;
        var mainPass = net.vulkanmod.vulkan.Renderer.getInstance().getMainPass();
        if (mainPass instanceof net.vulkanmod.vulkan.pass.DefaultMainPass dmp)
            dmp.compositeRtBeforeGui(net.vulkanmod.vulkan.Renderer.getCommandBuffer());
    }

//    // Debug
//    @Redirect(method = "method_71055", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;isAnimated()Z"))
//    private boolean forceRender(TrackingItemStackRenderState instance) {
//        return true;
//    }

    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"), cancellable = true)
    private void submitBlitFromItemAtlas(GuiItemRenderState guiItemRenderState, float u, float v, int size, int atlasSize,
                                         CallbackInfo ci) {
        v = 1.0f - v;
        float u1 = u + (float)size / atlasSize;
        float v1 = v + (float)(size) / atlasSize;
        this.renderState
                .submitBlitToCurrentLayer(
                        new BlitRenderState(
                                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                                TextureSetup.singleTexture(this.itemsAtlasView, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)),
                                guiItemRenderState.pose(),
                                guiItemRenderState.x(),
                                guiItemRenderState.y(),
                                guiItemRenderState.x() + 16,
                                guiItemRenderState.y() + 16,
                                u,
                                u1,
                                v,
                                v1,
                                -1,
                                guiItemRenderState.scissorArea(),
                                null
                        )
                );

        ci.cancel();
    }

    @Redirect(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setIndexBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;)V"))
    private void removeIndexBuffer(RenderPass instance, GpuBuffer gpuBuffer, VertexFormat.IndexType indexType) {
        // This draw method forces quad index buffer, not allowing other draw modes
        // Not binding it here will allow for a proper index  selection in lower level methods
    }

    @Redirect(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V"))
    private void useVertexCount(RenderPass renderPass, int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        // For the same reason here we need to use vertexCount instead of indexCount

        VkRenderPass vkRenderPass = (VkRenderPass) renderPass;
        if (vkRenderPass.getPipeline().getVertexFormatMode() != VertexFormat.Mode.TRIANGLES) {
            int vertexCount = indexCount * 2 / 3;
            renderPass.drawIndexed(baseVertex, 0, vertexCount, 1);
        }
        else {
            renderPass.drawIndexed(baseVertex, 0, indexCount, 1);
        }
    }

}
