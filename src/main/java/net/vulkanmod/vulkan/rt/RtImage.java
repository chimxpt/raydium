package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.27, подготовка к DLSS): storage-образ с ручным контролем layout ===
// ⚠️ Почему не VulkanImage: его transitionImageLayout не знает VK_IMAGE_LAYOUT_GENERAL (кидает
// исключение на default), а именно GENERAL нужен для storage image, в который пишет compute.
// Здесь — минимальный образ: image + память + view + текущий layout, и барьер на переход.

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class RtImage {
    public long image, memory, view;
    public final int width, height, format;
    private int layout = VK_IMAGE_LAYOUT_UNDEFINED;

    public RtImage(int width, int height, int format, int usage) {
        this.width = width; this.height = height; this.format = format;
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().width(width).height(height).depth(1);
            LongBuffer pImg = stack.mallocLong(1);
            check(vkCreateImage(device, info, null, pImg), "create image");
            image = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(AccelStruct.memoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(device, alloc, null, pMem), "alloc image memory");
            memory = pMem.get(0);
            check(vkBindImageMemory(device, image, memory, 0), "bind image memory");

            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(device, vi, null, pView), "create image view");
            view = pView.get(0);
        }
    }

    /** Перевод в нужный layout. Барьер широкий (ALL_COMMANDS) — проходов мало, цена ничтожна. */
    public void transition(MemoryStack stack, VkCommandBuffer cmd, int newLayout,
                           int srcAccess, int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default()
                .oldLayout(layout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
        layout = newLayout;
    }

    public int layout() { return layout; }

    public void free() {
        VkDevice device = Vulkan.getVkDevice();
        if (view != VK_NULL_HANDLE)   vkDestroyImageView(device, view, null);
        if (image != VK_NULL_HANDLE)  vkDestroyImage(device, image, null);
        if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, null);
        view = image = memory = VK_NULL_HANDLE;
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new RuntimeException("[RT] Vulkan error in '" + what + "': " + r);
    }
}
