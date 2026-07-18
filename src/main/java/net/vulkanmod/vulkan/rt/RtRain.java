package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.21b): НАСТОЯЩИЕ ТЕКСТУРЫ ДОЖДЯ/СНЕГА MC ===
// Ваниль рисует дождь/снег в проходе МИРА (WeatherEffectRenderer), а наш RT-кадр его
// перезаписывает — осадки пропадали (как партиклы). Захватывать геометрию погоды сложно
// (свой формат вершин, камеро-ориентированные наклонные полотна). Проще и надёжнее: взять
// РОДНЫЕ текстуры MC rain.png/snow.png и нарисовать их своим экранным оверлеем в RT — вид
// получается ванильный (это те же тексели капель/снежинок).
//
// rain.png и snow.png оба 64x256. Складываем в ОДИН атлас 64x512: дождь сверху (строки 0..255),
// снег снизу (256..511). Шейдер берёт нужный регион по snowF (v в [0,0.5) или [0.5,1)).

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class RtRain {

    private static VulkanImage tex;
    private static boolean failed = false;

    /** Атлас 64x512 (дождь сверху, снег снизу), или null. */
    public static VulkanImage get() { return tex; }

    private static BufferedImage load(String path) throws Exception {
        try (InputStream in = RtRain.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("нет ресурса " + path);
            return ImageIO.read(in);
        }
    }

    public static void init() {
        if (tex != null || failed) return;
        try {
            BufferedImage rain = load("/assets/minecraft/textures/environment/rain.png");
            BufferedImage snow = load("/assets/minecraft/textures/environment/snow.png");
            int w = rain.getWidth(), h = rain.getHeight();   // 64 x 256
            if (snow.getWidth() != w || snow.getHeight() != h)
                throw new IllegalStateException("rain/snow разного размера");
            int totalH = h * 2;

            ByteBuffer buf = MemoryUtil.memAlloc(w * totalH * 4);
            putImage(buf, rain, w, h);   // строки 0..255 — дождь
            putImage(buf, snow, w, h);   // строки 256..511 — снег
            buf.flip();

            VulkanImage image = VulkanImage.builder(w, totalH)
                    .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                    .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(true)    // капли мягкие, без ступенек
                    .setClamp(true)              // wrap делаем сами через fract() — регионы не должны смешиваться
                    .createVulkanImage();
            image.uploadSubTextureAsync(0, 0, w, totalH, 0, 0, 0, 0, w, buf);
            MemoryUtil.memFree(buf);

            tex = image;
            Initializer.LOGGER.info("[RT] осадки: атлас дождь+снег {}x{} собран", w, totalH);
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] не смог собрать атлас осадков: ", t);
        }
    }

    private static void putImage(ByteBuffer buf, BufferedImage im, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = im.getRGB(x, y);
                buf.put((byte) ((argb >> 16) & 0xFF));   // R
                buf.put((byte) ((argb >> 8)  & 0xFF));   // G
                buf.put((byte) ( argb        & 0xFF));   // B
                buf.put((byte) ((argb >>> 24) & 0xFF));  // A
            }
        }
    }
}
