package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.19): ТЕКСТУРА ЛУНЫ (порт Eclipse) ===
// Eclipse рисует луну не спрайтом, а ОСВЕЩЁННЫМ ШАРОМ: его `texture/moon.png` — это
// равнопромежуточная (equirectangular) карта ВСЕЙ сферы Луны, 8192x4096. Мы делаем так же:
// в шейдере проецируем видимое полушарие и сэмплим эту карту.
//
// ⚠️ Оригинал 8192x4096 = ~134 МБ в RGBA — грузить как есть нельзя. Ужимаем при загрузке
// до 1024x512 (2 МБ): на угловом размере луны этого с запасом.

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class RtMoon {

    private static final String PATH = "/assets/vulkanmod/textures/rt/moon.png";
    private static final int W = 1024, H = 512;      // во сколько ужимаем

    private static VulkanImage tex;
    private static boolean failed = false;

    public static VulkanImage get() { return tex; }

    public static void init() {
        if (tex != null || failed) return;
        try (InputStream in = RtMoon.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("нет ресурса " + PATH);
            BufferedImage src = ImageIO.read(in);

            // ужать до W x H
            BufferedImage dst = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            var g = dst.createGraphics();
            g.drawImage(src.getScaledInstance(W, H, Image.SCALE_SMOOTH), 0, 0, null);
            g.dispose();

            ByteBuffer buf = MemoryUtil.memAlloc(W * H * 4);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int argb = dst.getRGB(x, y);
                    buf.put((byte) ((argb >> 16) & 0xFF));   // R
                    buf.put((byte) ((argb >> 8)  & 0xFF));   // G
                    buf.put((byte) ( argb        & 0xFF));   // B
                    buf.put((byte) ((argb >>> 24) & 0xFF));  // A
                }
            }
            buf.flip();

            VulkanImage image = VulkanImage.builder(W, H)
                    .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                    .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(true)     // луна близко к глазу — без мыла не обойтись
                    .setClamp(false)              // по долготе карта заворачивается
                    .createVulkanImage();
            image.uploadSubTextureAsync(0, 0, W, H, 0, 0, 0, 0, W, buf);
            MemoryUtil.memFree(buf);

            tex = image;
            Initializer.LOGGER.info("[RT] moon: equirectangular sphere map {}x{} (from {}x{})",
                    W, H, src.getWidth(), src.getHeight());
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] failed to load the moon texture: ", t);
        }
    }
}
