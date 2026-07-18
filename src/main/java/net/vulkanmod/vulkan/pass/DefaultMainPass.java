package net.vulkanmod.vulkan.pass;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;

import java.util.function.IntSupplier;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class DefaultMainPass implements MainPass {

    public static DefaultMainPass create() {
        return new DefaultMainPass();
    }

    private Framebuffer mainFramebuffer;

    private RenderPass mainRenderPass;
    private RenderPass auxRenderPass;

    private GpuTexture[] colorAttachmentTextures;
    private GpuTextureView[] colorAttachmentTextureViews;
    IntSupplier imageIdxSupplier;
    private GpuTexture depthAttachmentTexture;

    DefaultMainPass() {
        createResources();
    }

    private void createResources() {
        if (this.mainFramebuffer != null) {
            if (this.mainFramebuffer != Renderer.getInstance()
                                                .getSwapChain()) {
                this.mainFramebuffer.cleanUp(true);
            }

            this.mainRenderPass.cleanUp();
            this.auxRenderPass.cleanUp();
        }

        Framebuffer framebuffer;
        if (Renderer.getInstance().getSwapChain().hasImages()) {
            framebuffer = Renderer.getInstance().getSwapChain();
        }
        else {
            framebuffer = Framebuffer.builder(10, 10, 1, true)
                                     .build();
        }

        this.mainFramebuffer = framebuffer;

        createRenderPasses();
        createAttachmentTextures();
    }

    private void createRenderPasses() {
        RenderPass.Builder builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);

        this.mainRenderPass = builder.build();

        // Create an auxiliary RenderPass needed in case of main target rebinding
        builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        this.auxRenderPass = builder.build();
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.rtComposited = false;   // новый кадр -> композит снова разрешён
        Framebuffer framebuffer = this.mainFramebuffer;

        VulkanImage colorAttachment = framebuffer.getColorAttachment();
        colorAttachment.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        Renderer.getInstance().beginRenderPass(this.mainRenderPass, framebuffer);

        Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight(), stack);

        VkRect2D.Buffer pScissor = framebuffer.scissor(stack);
        vkCmdSetScissor(commandBuffer, 0, pScissor);
    }

    // === RT PATCH (M8.6 v1): блит RT в swapchain ПЕРЕД отрисовкой GUI (из GuiRenderer.render).
    // Мир уже в swapchain; закрываем проход -> блит RT -> возобновляем aux-проход (LOAD),
    // и GUI рисуется ПОВЕРХ RT (HUD/меню/F3 сохраняются). ===
    // M8.13: композит должен пройти РОВНО РАЗ за кадр. Теперь его зовут ДВА места:
    //   1) перед отрисовкой ПАРТИКЛОВ (ParticleFeatureRendererM) — чтобы ваниль нарисовала
    //      партиклы/облака/погоду ПОВЕРХ нашего кадра (aux-проход грузит и цвет, и ГЛУБИНУ,
    //      поэтому они честно перекрываются геометрией);
    //   2) перед GUI (GuiRendererMixin) — страховка на кадры, где партиклов нет вовсе.
    private boolean rtComposited = false;
    public void resetRtComposited() { this.rtComposited = false; }

    public void compositeRtBeforeGui(VkCommandBuffer cmd) {
        if (this.rtComposited) return;
        if (this.mainFramebuffer != Renderer.getInstance().getSwapChain()) return;
        boolean rtOn = net.vulkanmod.vulkan.rt.RtScreen.enabled
                && net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported;
        // M8.122: РАСТРОВЫЙ ФОЛБЭК — «Шейдеры» ВКЛ при выключенном/недоступном RT: к этому
        // моменту ваниль дорисовала ВСЁ (мир, партиклы, погоду), и наш пост накрывает весь кадр.
        boolean rasterPost = !rtOn && net.vulkanmod.Initializer.CONFIG.shadersEnabled;
        if (!rtOn && !rasterPost) return;
        // ⚠️ Только при живом мире — иначе на меню/выходе трассируем выгружаемый мир = фриз.
        if (net.minecraft.client.Minecraft.getInstance().level == null) return;
        try {
            // компьют нужно писать ВНЕ прохода -> закрываем проход мира сначала
            if (Renderer.getInstance().getBoundRenderPass() != null)
                Renderer.getInstance().endRenderPass(cmd);
            boolean ready;
            if (rtOn) {
                // ASYNC: entity-BLAS + TLAS пересобираются В ЭТОМ ЖЕ кадровом буфере (без waitIdle),
                // барьеры внутри упорядочивают BLAS→TLAS→compute. Сущности/игрок — на частоте кадров.
                net.vulkanmod.vulkan.rt.RtWorld.recordFrameRebuild(cmd);
                // ASYNC: компьют RT пишется В ЭТОТ ЖЕ кадровый буфер (без waitIdle), барьер -> блит
                ready = net.vulkanmod.vulkan.rt.RtSnapshot.recordCompute(cmd);
            } else {
                // растр со свопчейна -> hdr -> наш пост (AgX + эффекты + SSAO/тени) -> ldr
                ready = net.vulkanmod.vulkan.rt.RtPost.recordRaster(cmd,
                        this.mainFramebuffer.getColorAttachment(), this.mainFramebuffer.getDepthAttachment());
            }
            if (ready) {
                net.vulkanmod.vulkan.rt.RtScreen.composite(cmd, this.mainFramebuffer.getColorAttachment());  // swapchain -> TRANSFER_DST
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                }
            }
            Renderer.getInstance().beginRenderPass(this.auxRenderPass, this.mainFramebuffer);   // всегда: GUI поверх (LOAD)
            this.rtComposited = true;
        } catch (Throwable t) {
            net.vulkanmod.Initializer.LOGGER.error("[RT] composite before gui", t);
        }
    }
    // === RT PATCH (M8.124): снимок глубины ТВЕРДИ перед translucent-слоем — маска и толщина
    // воды для растрового фолбэка. Копия требует места ВНЕ прохода; renderSectionLayer сразу
    // после зовёт rebindMainTarget(), и тот возобновляет проход сам. ===
    public void captureSolidDepth(VkCommandBuffer cmd) {
        if (this.mainFramebuffer != Renderer.getInstance().getSwapChain()) return;
        boolean rtOn = net.vulkanmod.vulkan.rt.RtScreen.enabled
                && net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported;
        if (rtOn || !net.vulkanmod.Initializer.CONFIG.shadersEnabled) return;
        if (net.minecraft.client.Minecraft.getInstance().level == null) return;
        try {
            if (Renderer.getInstance().getBoundRenderPass() != null)
                Renderer.getInstance().endRenderPass(cmd);
            net.vulkanmod.vulkan.rt.RtPost.copySolidDepth(cmd, this.mainFramebuffer.getDepthAttachment());
        } catch (Throwable t) {
            net.vulkanmod.Initializer.LOGGER.error("[RT] снимок глубины тверди", t);
        }
    }
    // === /RT PATCH ===

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);

        if (this.mainFramebuffer == Renderer.getInstance().getSwapChain()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }
    }

    @Override
    public void cleanUp() {
        this.mainRenderPass.cleanUp();
        this.auxRenderPass.cleanUp();
    }

    @Override
    public void onResize() {
        createResources();
    }

    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Do not rebind if the framebuffer is already bound
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            return;

        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.auxRenderPass, this.mainFramebuffer);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Check if render pass is using the framebuffer
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(this.mainFramebuffer.getColorAttachment());
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return mainFramebuffer;
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.colorAttachmentTextures[this.imageIdxSupplier.getAsInt()];
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.colorAttachmentTextureViews[this.imageIdxSupplier.getAsInt()];
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.depthAttachmentTexture;
    }

    private void createAttachmentTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        if (this.mainFramebuffer == swapChain) {
            var swapChainImages = swapChain.getImages();

            int imageCount = swapChainImages.size();
            this.colorAttachmentTextures = new GpuTexture[imageCount];
            this.colorAttachmentTextureViews = new GpuTextureView[imageCount];

            for (int i = 0; i < imageCount; ++i) {
                VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(swapChainImages.get(i));
                GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
                this.colorAttachmentTextures[i] = attachmentTexture;
                this.colorAttachmentTextureViews[i] = attachmentTextureView;
            }

            this.imageIdxSupplier = Renderer::getCurrentImage;
        }
        else {
            this.colorAttachmentTextures = new GpuTexture[1];
            this.colorAttachmentTextureViews = new GpuTextureView[1];

            VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getColorAttachment());
            GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
            this.colorAttachmentTextures[0] = attachmentTexture;
            this.colorAttachmentTextureViews[0] = attachmentTextureView;

            // Always return idx 0 as there's only 1 image
            this.imageIdxSupplier = () -> 0;
        }

        this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getDepthAttachment());
    }
}
