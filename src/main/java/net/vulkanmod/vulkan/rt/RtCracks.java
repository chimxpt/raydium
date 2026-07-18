package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.16): ТРЕЩИНЫ ЛОМАНИЯ БЛОКОВ ===
// Ваниль рисует их в проходе МИРА, а наш RT-кадр его перезаписывает — трещины пропадали.
// Захватывать их (как партиклы) не нужно: проще и надёжнее нарисовать ПРЯМО В RT, как обводку.
//
// Ломаемый блок — это блок ПОД ПРИЦЕЛОМ, чей AABB мы уже шлём для обводки. Значит из Java нужен
// только ПРОГРЕСС (0..1), а стадию (0..9) шейдер выведет сам.
//
// 10 текстур destroy_stage_0..9 (16x16) склеиваем в ОДНУ 16x160 (стадии стопкой) — так шейдер
// берёт нужную одним сэмплом: uv.y смещается на стадию. Тот же приём, что у ванильных анимаций.

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class RtCracks {

    public static final int STAGES = 10;
    private static VulkanImage tex;
    private static boolean failed = false;

    /** Атлас стадий (16 x 160), или null. */
    public static VulkanImage get() { return tex; }

    // === RT PATCH (M8.100): перезагрузка ресурсов (F3+T / смена паков) пересобирает атлас —
    // спрайты меняют UV, и карта устаревает: вода теряла материал 30 и шейдилась стеклом.
    // Старую текстуру рушим ОТЛОЖЕННО (кадры в полёте могли её читать): держим до 2 отставных.
    private static final java.util.ArrayList<VulkanImage> retired = new java.util.ArrayList<>();
    public static synchronized void invalidate() {
        if (tex != null) { retired.add(tex); tex = null; }
        while (retired.size() > 2) retired.remove(0).free();   // 2 перезагрузки назад — GPU не читает
        failed = false;
    }
    // === /RT PATCH ===


    public static void init() {
        if (tex != null || failed) return;
        try {
            BufferedImage[] imgs = new BufferedImage[STAGES];
            for (int i = 0; i < STAGES; i++) {
                String path = "/assets/minecraft/textures/block/destroy_stage_" + i + ".png";
                try (InputStream in = RtCracks.class.getResourceAsStream(path)) {
                    if (in == null) throw new IllegalStateException("нет ресурса " + path);
                    imgs[i] = ImageIO.read(in);
                }
            }
            int w = imgs[0].getWidth(), h = imgs[0].getHeight();
            int totalH = h * STAGES;

            ByteBuffer buf = MemoryUtil.memAlloc(w * totalH * 4);
            for (int s = 0; s < STAGES; s++) {
                BufferedImage im = imgs[s];
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
            buf.flip();

            VulkanImage image = VulkanImage.builder(w, totalH)
                    .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                    .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(false)   // трещины — жёсткие тексели MC, без мыла
                    .setClamp(true)
                    .createVulkanImage();
            image.uploadSubTextureAsync(0, 0, w, totalH, 0, 0, 0, 0, w, buf);
            MemoryUtil.memFree(buf);

            tex = image;
            Initializer.LOGGER.info("[RT] block breaking: {}x{} destroy-stage atlas built", w, totalH);
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] failed to build the destroy-stage atlas: ", t);
        }
    }
}
