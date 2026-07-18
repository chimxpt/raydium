package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.23): УДАРЫ КАПЕЛЬ ПО ВОДЕ -> КОЛЬЦА РЯБИ ===
// Раньше рябь на воде была равномерным ПРОЦЕДУРНЫМ полем (не привязана к каплям). Теперь каждый
// удар капли рождает расходящееся кольцо В КОНКРЕТНОЙ ТОЧКЕ и В КОНКРЕТНЫЙ МОМЕНТ. Точки ударов
// генерируем той же частотой и распределением, что ванильные всплеск-партиклы: вокруг игрока,
// количество ∝ силе дождя (статистически это те же капли). Шейдер строит кольца только там, где
// в этой точке есть вода/лужа.
//
// SSBO: [0] = число активных ударов (в .x), далее vec4(x, z, времяРождения_тики, _). Время — в тех
// же тиках, что params.y в шейдере (gameTime % 3600), чтобы возраст совпадал.

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class RtRipples {

    public static final int MAX = 96;              // одновременных ударов (капель в полёте)
    private static final int STRIDE = 16;          // vec4 (x, z, birth, _)
    private static final int HEADER = 1;           // элемент [0] = счётчик
    private static final int SLOTS = 4;            // кольцо буферов (кадр в полёте читает прошлый)
    private static final float LIFE = 22f;         // жизнь кольца в тиках (~1.1с)
    private static final float GEN_RADIUS = 15f;   // радиус генерации ударов вокруг игрока

    private static AccelStruct.RawBuffer[] slots;
    private static int writeSlot = 0, readSlot = 0, count = 0;
    private static boolean failed = false;

    // Кольцевой список активных ударов (CPU). birth<=0 -> пустой слот.
    private static final float[] impX = new float[MAX];
    private static final float[] impZ = new float[MAX];
    private static final float[] impT = new float[MAX];
    private static int head = 0;
    private static final Random rng = new Random();

    public static long buffer() { return (slots != null) ? slots[readSlot].buffer : VK_NULL_HANDLE; }
    public static int  count()  { return count; }

    public static void init() { if (slots == null && !failed) alloc(); }

    private static void alloc() {
        try {
            slots = new AccelStruct.RawBuffer[SLOTS];
            for (int i = 0; i < SLOTS; i++)
                slots[i] = AccelStruct.createBuffer((long) (HEADER + MAX) * STRIDE,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
            Initializer.LOGGER.info("[RT] RtRipples: raindrop impact buffer ready ({} slots x {})", SLOTS, MAX);
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] RtRipples alloc failed: ", t);
        }
    }

    /** Каждый кадр с рендер-потока: рождаем новые удары ∝ дождю, гасим старые, заливаем в слот. */
    public static void update(double cx, double cz, float rain, float nowTicks) {
        if (failed) return;
        try {
            if (slots == null) { alloc(); if (failed) return; }

            // 1) новые удары этого кадра (при полном дожде ~2/кадр — плотность на воде ок)
            if (rain > 0.02f) {
                int drops = Math.round(rain * 2.0f);
                for (int d = 0; d < drops; d++) {
                    double ang = rng.nextDouble() * Math.PI * 2.0;
                    double dist = 1.0 + rng.nextDouble() * GEN_RADIUS;
                    impX[head] = (float) (cx + Math.cos(ang) * dist);
                    impZ[head] = (float) (cz + Math.sin(ang) * dist);
                    impT[head] = nowTicks <= 0f ? 0.0001f : nowTicks;   // 0 = «пусто», сдвигаем
                    head = (head + 1) % MAX;
                }
            }

            // 2) заливка ЖИВЫХ ударов в слот кольца
            writeSlot = (writeSlot + 1) % SLOTS;
            AccelStruct.RawBuffer buf = slots[writeSlot];
            VkDevice device = Vulkan.getVkDevice();
            int cnt = 0;
            try (MemoryStack stack = stackPush()) {
                PointerBuffer pp = stack.mallocPointer(1);
                vkMapMemory(device, buf.memory, 0, (long) (HEADER + MAX) * STRIDE, 0, pp);
                ByteBuffer bb = MemoryUtil.memByteBuffer(pp.get(0), (HEADER + MAX) * STRIDE).order(ByteOrder.nativeOrder());
                for (int i = 0; i < MAX; i++) {
                    if (impT[i] <= 0f) continue;
                    float age = nowTicks - impT[i];
                    if (age < 0f) age += 3600f;                 // wrap gameTime % 3600
                    if (age > LIFE) { impT[i] = 0f; continue; } // истёк
                    int base = (HEADER + cnt) * STRIDE;
                    bb.putFloat(base,      impX[i]);
                    bb.putFloat(base + 4,  impZ[i]);
                    bb.putFloat(base + 8,  impT[i]);
                    bb.putFloat(base + 12, 0f);
                    cnt++;
                }
                bb.putFloat(0, (float) cnt);                    // заголовок: число активных
                vkUnmapMemory(device, buf.memory);
            }
            count = cnt;
            readSlot = writeSlot;
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] RtRipples update failed: ", t);
        }
    }
}
