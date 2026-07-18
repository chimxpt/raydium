package net.vulkanmod.vulkan.rt;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkOffset3D;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * M8.6 — вывод RT на экран. Раньше: SSBO с 8-битным кадром → копия в образ → блит.
 * M8.27: трассировка пишет HDR-образ, {@link RtPost} превращает его в LDR-образ (тонмаппинг +
 * экранные эффекты), и мы просто МАСШТАБИРУЕМ этот образ на swapchain (билинейно).
 * Промежуточной копии больше нет — писать в SSBO и тащить его обратно в образ было незачем.
 * Вызывается из {@link net.vulkanmod.vulkan.pass.DefaultMainPass} после закрытия прохода мира.
 */
public class RtScreen {
    public static boolean enabled = true;   // тумблер RT-экрана

    /** Больше нечего освобождать: образами владеет RtPost. */
    public static void shutdown() { }

    /** Блит готового RT-кадра на swapchain (масштаб до размера окна). cmd — кадровый буфер. */
    public static void composite(VkCommandBuffer cmd, VulkanImage swap) {
        RtImage ldr = RtPost.ldrImage();
        if (ldr == null || swap == null) return;
        // RtPost уже перевёл ldr в TRANSFER_SRC_OPTIMAL и поставил барьер compute -> transfer
        try (MemoryStack stack = stackPush()) {
            swap.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.srcOffsets(1, VkOffset3D.calloc(stack).set(ldr.width, ldr.height, 1));
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.dstOffsets(1, VkOffset3D.calloc(stack).set(swap.width, swap.height, 1));
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            // ⚠️ Форматы разные (RGBA8 у нас, BGRA8 у swapchain) — блит сам переставит каналы.
            vkCmdBlitImage(cmd, ldr.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    swap.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
            // swapchain остаётся в TRANSFER_DST; DefaultMainPass.end переведёт его в PRESENT_SRC
        }
    }
}
