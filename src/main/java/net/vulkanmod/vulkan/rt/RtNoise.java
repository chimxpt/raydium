package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.12/M8.95): НАША текстура шума (noisetex) ===
// 512x512 RGBA, сгенерирована нами (генератор: бесшовный fBm value-noise + blue-noise
// альфа, зерно 20260716; см. коммит M8.95). Статистики каналов (гистограммы, частотный
// характер) подобраны совместимыми с ожиданиями шейдера, САМИ ПИКСЕЛИ — собственные:
// ассетов Eclipse в сборке больше нет. По ней считаются капли воды на камере, всплеск
// при погружении, тепловое дрожание и плотность облаков.

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class RtNoise {

    private static final String PATH = "/assets/vulkanmod/textures/rt/noise_rgba.png";
    private static VulkanImage tex;
    private static boolean failed = false;

    /** Текстура шума Eclipse, или null (тогда шейдер обходится без эффектов искажения). */
    public static VulkanImage get() { return tex; }

    /** Ленивая загрузка (с render-потока, когда Vulkan-устройство уже живо). */
    public static void init() {
        if (tex != null || failed) return;
        try (InputStream in = RtNoise.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("нет ресурса " + PATH);
            BufferedImage img = ImageIO.read(in);
            int w = img.getWidth(), h = img.getHeight();

            ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    buf.put((byte) ((argb >> 16) & 0xFF));   // R
                    buf.put((byte) ((argb >> 8)  & 0xFF));   // G
                    buf.put((byte) ( argb        & 0xFF));   // B
                    buf.put((byte) ((argb >>> 24) & 0xFF));  // A
                }
            }
            buf.flip();

            VulkanImage image = VulkanImage.builder(w, h)
                    .setFormat(VK_FORMAT_R8G8B8A8_UNORM)     // шум — НЕ sRGB
                    .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(true)                 // Eclipse сэмплит texture(), не texelFetch
                    .setClamp(false)                          // повторение (tiling) — как noisetex
                    .createVulkanImage();
            image.uploadSubTextureAsync(0, 0, w, h, 0, 0, 0, 0, w, buf);
            MemoryUtil.memFree(buf);

            tex = image;
            Initializer.LOGGER.info("[RT] noisetex Eclipse загружен: {}x{}", w, h);
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] не смог загрузить noisetex Eclipse: ", t);
        }
    }
}
