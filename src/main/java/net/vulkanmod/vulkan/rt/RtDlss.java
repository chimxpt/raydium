package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.29): DLSS RAY RECONSTRUCTION ===
// Java-сторона тонкой прослойки к NGX (native/mcrt_ngx.cpp).
//
// ⚠️ Почему через нативную .so, а не напрямую: NGX API поставляется СТАТИЧЕСКОЙ библиотекой
// (libnvsdk_ngx.a) — .so/.dll с C-экспортами у неё нет, поэтому позвать DLSS из Java (FFI, LWJGL)
// физически нечем. Символы надо влинковать в свой нативный код, что прослойка и делает.
//
// Что где лежит:
//   libmcrt_ngx.so        — наша прослойка, едет ВНУТРИ jar (ресурс), распаковывается во временную папку
//   libnvidia-ngx-dlssd.so — сама модель Ray Reconstruction (40 МБ), лежит в <папка игры>/dlss/
//
// ⚠️ Расширения устройства, которые требует DLSS, надо включить ДО создания VkDevice — см.
// requiredDeviceExtensions(), её зовёт DeviceManager.

import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.Callable;

public class RtDlss {

    // ⚠️⚠️ ВСЕ вызовы NGX идут ЧЕРЕЗ ЭТОТ ПОТОК, и это не прихоть.
    // NGX (и CUDA внутри него) кладёт на стек очень много. У потоков JVM стека ~1 МБ, и NGX сносил
    // игру по SIGSEGV прямо в момент инициализации (лог обрывался на середине строки). Из потока с
    // большим стеком всё поднимается штатно — проверено отдельным опытом вне игры.
    // Вызовы синхронные (submit + get): рендер-поток просто ждёт, параллельного доступа к
    // командному буферу нет, а стоимость двух переключений контекста на кадр незаметна.
    private static final long NGX_STACK = 64L * 1024 * 1024;
    private static final ExecutorService NGX_THREAD = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(null, r, "RT-DLSS-NGX", NGX_STACK);
            t.setDaemon(true);
            return t;
        }
    });

    /** Выполнить нативный вызов в потоке с большим стеком и дождаться результата. */
    private static <T> T onNgxThread(Callable<T> call, T fallback) {
        try {
            return NGX_THREAD.submit(call).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException e) {
            Initializer.LOGGER.error("[RT] DLSS: native call threw: ", e.getCause());
            failed = true;
            return fallback;
        }
    }

    /** Пользовательский тумблер (позже — кнопка в настройках). */
    public static boolean enabled = true;

    /**
     * Знаки jitter'а, которые отдаём DLSS (циклически переключаются F7).
     * ⚠️ Мы смещаем ЛУЧ на +jitter, а DLSS ждёт смещение в СВОЕЙ конвенции — и по X и по Y она
     * может ждать разного, потому что экранный Y перевёрнут относительно нашего v. Ошибка знака =
     * сэмплы копятся «не туда»: дрожание не гасится. Перебираем все четыре комбинации глазами.
     */
    private static final float[][] JITTER_MODES = {{-1, -1}, {1, 1}, {-1, 1}, {1, -1}};
    private static final String[]  JITTER_NAMES = {"−X −Y", "+X +Y", "−X +Y", "+X −Y"};
    public static int jitterMode = 0;

    public static float jitterSignX() { return JITTER_MODES[jitterMode][0]; }
    public static float jitterSignY() { return JITTER_MODES[jitterMode][1]; }

    public static String cycleJitter() {
        jitterMode = (jitterMode + 1) % JITTER_MODES.length;
        return JITTER_NAMES[jitterMode];
    }

    private static boolean libLoaded = false;
    private static boolean libTried = false;
    private static boolean initialized = false;
    private static boolean failed = false;
    private static long feature = 0;
    private static long lastFrameNs = 0;
    private static long evalNs = 0;      // замер: сколько рендер-поток ждёт DLSS (Windows медленнее Linux)
    private static int  evalCount = 0;

    // --- нативные точки входа (см. native/mcrt_ngx.cpp) ---
    private static native String nRequiredDeviceExtensions();
    private static native int  nInit(long instance, long physDevice, long device, String modelPath, String logPath);
    private static native long nCreateFeature(long cmdBuf, int inW, int inH, int outW, int outH, int preset);

    /**
     * Модель Ray Reconstruction. У неё СВОЯ линейка пресетов, не та, что у апскейлера:
     * 4 = D (трансформер по умолчанию), 5 = E (самый свежий трансформер).
     * Всё, что дальше по алфавиту (в т.ч. «preset K»), в этом SDK помечено «do not use» и молча
     * откатывается к дефолту — ставить его бессмысленно.
     */
    private static final int PRESET_E_LATEST_TRANSFORMER = 5;
    private static native int  nEvaluate(long cmdBuf, long[] imgs, int[] fmts,
                                         int inW, int inH, int outW, int outH, float[] nums, float[] mats);
    private static native void nShutdown();

    /** Распаковать прослойку из jar во временный файл и загрузить. Без неё DLSS просто выключен. */
    private static synchronized boolean loadLib() {
        if (libTried) return libLoaded;
        libTried = true;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String res = windows ? "/natives/mcrt_ngx.dll" : "/natives/libmcrt_ngx.so";
        String ext = windows ? ".dll" : ".so";
        try (InputStream in = RtDlss.class.getResourceAsStream(res)) {
            if (in == null) {
                Initializer.LOGGER.info("[RT] DLSS: shim {} is not bundled in the jar - DLSS disabled", res);
                return false;
            }
            Path tmp = Files.createTempFile("mcrt_ngx", ext);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            libLoaded = true;
            Initializer.LOGGER.info("[RT] DLSS: shim loaded ({})", tmp);
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[RT] DLSS: shim failed to load - DLSS disabled: {}", t.toString());
        }
        return libLoaded;
    }

    /**
     * Расширения VkDevice, которых требует DLSS. Зовётся ДО создания устройства: включить их
     * потом нельзя, а без них NGX не стартует.
     * ⚠️ СПРАШИВАЕМ ВСЕГДА, а не только при включённом DLSS. Раньше здесь стояла проверка
     * `enabled`, и если игра стартовала со встроенным денойзером, устройство создавалось без
     * этих расширений. Переключение на DLSS в настройках после этого молча не работало: NGX
     * падал с «vkCreateCuModuleNVX ... not available. Request VK_NVX_binary_import extension»,
     * а игрок видел лишь то, что ничего не изменилось. Неиспользуемое расширение не стоит
     * ничего, зато переключение работает без перезапуска игры (M8.160).
     */
    public static List<String> requiredDeviceExtensions() {
        List<String> out = new ArrayList<>();
        if (!loadLib()) return out;
        try {
            String s = nRequiredDeviceExtensions();
            if (s != null && !s.isEmpty())
                for (String e : s.split("\n"))
                    if (!e.isBlank()) out.add(e.trim());
            Initializer.LOGGER.info("[RT] DLSS requires device extensions: {}", out);
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[RT] DLSS: could not query required extensions: {}", t.toString());
        }
        return out;
    }

    /** Модель Ray Reconstruction лежит рядом с игрой — в <папка игры>/dlss/. */
    private static Path modelDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dlss");
    }

    /**
     * M8.96: РЕЛИЗНЫЙ jar несёт модели RR с собой (ресурс /dlss/ + манифест files.txt,
     * кладёт scripts/build-release.sh). Если рядом с игрой модели нужной ОС нет —
     * распаковываем при первом старте: один файл = самодостаточная установка.
     * В dev-сборке ресурса нет — поведение прежнее (ждём готовую папку dlss/).
     */
    private static void ensureModel(Path dir) {
        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String prefix = windows ? "nvngx_dlssd" : "libnvidia-ngx-dlssd.so.";
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    if (files.anyMatch(f -> f.getFileName().toString().startsWith(prefix))) return;
                }
            }
            try (InputStream list = RtDlss.class.getResourceAsStream("/dlss/files.txt")) {
                if (list == null) return;   // dev-сборка без вшитых моделей
                Files.createDirectories(dir);
                String[] names = new String(list.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).split("\n");
                for (String name : names) {
                    name = name.trim();
                    // только модель СВОЕЙ ОС; имя с версией обязательно (NGX выбирает по имени)
                    if (name.isEmpty() || name.equals("files.txt") || !name.startsWith(prefix)) continue;
                    try (InputStream in = RtDlss.class.getResourceAsStream("/dlss/" + name)) {
                        if (in == null) continue;
                        Path dst = dir.resolve(name);
                        Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                        Initializer.LOGGER.info("[RT] DLSS: model extracted from the jar: {}", dst);
                    }
                }
            }
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[RT] DLSS: extracting the model from the jar failed: {}", t.toString());
        }
    }

    /** Инициализация NGX (один раз). false — DLSS недоступен, работаем как раньше. */
    public static synchronized boolean init(long instance, long physDevice, long device) {
        if (initialized) return true;
        if (failed || !enabled || !loadLib()) return false;
        try {
            Path dir = modelDir();
            ensureModel(dir);   // релизный jar несёт модель с собой — распакуем при первом старте
            if (!Files.isDirectory(dir)) {
                Initializer.LOGGER.warn("[RT] DLSS: model directory {} is missing - DLSS disabled", dir);
                failed = true;
                return false;
            }
            Initializer.LOGGER.info("[RT] DLSS: initialising NGX (model: {})", dir);
            int r = onNgxThread(() -> nInit(instance, physDevice, device, dir.toString(), dir.toString()), -999);
            if (r != 0) {
                Initializer.LOGGER.warn("[RT] DLSS: NGX failed to initialise (code {}) - DLSS disabled", r);
                failed = true;
                return false;
            }
            initialized = true;
            Initializer.LOGGER.info("[RT] DLSS Ray Reconstruction is AVAILABLE");
            return true;
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] DLSS init threw: ", t);
            return false;
        }
    }

    /** Создать фичу под конкретные разрешения (рендер -> вывод). Пересоздаётся при смене размеров. */
    public static synchronized boolean createFeature(long cmdBuf, int inW, int inH, int outW, int outH) {
        if (!initialized || failed) return false;
        try {
            Initializer.LOGGER.info("[RT] DLSS: creating feature {}x{} -> {}x{}, preset E (latest transformer)",
                    inW, inH, outW, outH);
            feature = onNgxThread(
                    () -> nCreateFeature(cmdBuf, inW, inH, outW, outH, PRESET_E_LATEST_TRANSFORMER), 0L);
            if (feature == 0) {
                Initializer.LOGGER.warn("[RT] DLSS: feature creation failed - DLSS disabled");
                failed = true;
                return false;
            }
            Initializer.LOGGER.info("[RT] DLSS: feature created {}x{} -> {}x{}", inW, inH, outW, outH);
            return true;
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] DLSS createFeature threw: ", t);
            return false;
        }
    }

    /**
     * СМЕНИЛОСЬ РАЗРЕШЕНИЕ: гасим ТОЛЬКО фичу — NGX и его рабочий поток остаются живы.
     *
     * ⚠️ Раньше тут звался shutdown(), и это убивало DLSS НАСОВСЕМ: он сносит NGX целиком И
     * ОСТАНАВЛИВАЕТ рабочий поток, через который идут все вызовы. Поднять обратно уже нельзя —
     * следующий вызов летит в мёртвый поток, ловится как сбой, и DLSS выключается до перезапуска.
     * Старую фичу освобождает сама нативная сторона при создании новой (см. nCreateFeature).
     */
    public static synchronized void resetFeature() { feature = 0; }

    public static boolean ready() { return initialized && !failed && feature != 0; }

    /** M8.122e: железо/драйвер тянут DLSS (инициализация не провалена). Гейт ТУМБЛЕРА в UI:
     *  на старых GPU ручка гаснет. Нарочно без enabled — иначе выключенный тумблер сам себя
     *  заблокировал бы и обратно не включался. */
    public static boolean hardwareOk() { return !failed; }

    /** M8.122e: DLSS в деле (включён и не провален) — для масштаба трассировки и джиттера.
     *  Нарочно НЕ ready(): та гаснет на кадр при пересоздании фичи (смена разрешения), и
     *  масштаб прыгал бы 67<->100 с waitIdle на каждом шаге — петля ресайзов. */
    public static boolean usable() { return enabled && !failed; }

    /**
     * Один вызов Ray Reconstruction: шумный кадр + guide-буферы -> чистый кадр в полном разрешении.
     * imgs — пары (image, view): color, depth, motion, normal, diffAlb, specAlb, output.
     */
    public static boolean evaluate(long cmdBuf, long[] imgs, int[] fmts,
                                   int inW, int inH, int outW, int outH,
                                   float jitterX, float jitterY, boolean reset,
                                   float[] worldToViewAndViewToClip) {
        if (!ready()) return false;
        long now = System.nanoTime();
        float dtMs = lastFrameNs == 0 ? 16.6f : (now - lastFrameNs) / 1_000_000.0f;
        lastFrameNs = now;
        try {
            float[] nums = {jitterX, jitterY, reset ? 1f : 0f, dtMs};
            // ЗАМЕР: на Windows FPS ниже, чем на Linux, при том же железе. Надо знать, сколько времени
            // рендер-поток реально стоит на этом вызове — сюда входит и переключение на NGX-поток.
            long t0 = System.nanoTime();
            int r = onNgxThread(
                    () -> nEvaluate(cmdBuf, imgs, fmts, inW, inH, outW, outH, nums, worldToViewAndViewToClip), -999);
            evalNs += System.nanoTime() - t0;
            if (++evalCount >= 300) {
                Initializer.LOGGER.info("[RT] DLSS: evaluate call recording = {} us/frame (the render thread waits this long)",
                        evalNs / 1000 / evalCount);
                evalNs = 0; evalCount = 0;
            }
            if (r != 0) {
                Initializer.LOGGER.error("[RT] DLSS evaluate returned code {} - disabling DLSS", r);
                failed = true;
                return false;
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] DLSS evaluate threw: ", t);
            return false;
        }
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        onNgxThread(() -> { nShutdown(); return null; }, null);
        NGX_THREAD.shutdown();
        initialized = false;
        feature = 0;
    }
}
