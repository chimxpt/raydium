package net.vulkanmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.vulkanmod.config.video.VideoModeSet;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class Config {
    public VideoModeSet.VideoMode videoMode = VideoModeSet.getDummy().getVideoMode();
    public int windowMode = 0;

    public int advCulling = 2;
    public boolean indirectDraw = true;

    public boolean uniqueOpaqueLayer = true;
    public boolean entityCulling = true;
    public int device = -1;
    public boolean useWayland = false;

    public int ambientOcclusion = 1;
    public int frameQueueSize = 2;
    public int builderThreads = 0;

    public boolean backFaceCulling = true;
    public boolean textureAnimations = true;

    // === RT PATCH === НАСТРОЙКИ ТРАССИРОВКИ (страница «Трассировка» в настройках графики).
    // ⚠️ Каждая ручка обязана что-то реально выключать в шейдере или в Java. Настройка, которая
    // ничего не меняет, — обман: пользователь крутит её и верит, что стало быстрее.
    public boolean rtEnabled = true;        // мастер-тумблер: RT-картинка вместо растеризации
    // M8.122: «Шейдеры» — наш пост (AgX + экранные эффекты) поверх ванильного растра, когда RT
    // выключен. Иерархия: RT ВКЛ подразумевает шейдеры; оба ВЫКЛ = чистый VulkanMod-растр.
    public boolean shadersEnabled = true;
    public boolean rtShadows = true;        // теневой луч к солнцу
    public int rtAmbientRays = 4;           // лучей небесного света на пиксель: 0 (выкл) / 2 / 4
    public boolean rtReflections = true;    // отражения (вода, стекло, металлы)
    public boolean rtClouds = true;         // объёмные облака (марш по лучу — дорого)
    public boolean rtColoredLights = true;  // цветной свет от блоков (факелы, лава, фонари)
    public boolean dlss = true;             // DLSS (апскейл + нейроденойзер Ray Reconstruction)
    public int renderScale = 67;            // РАЗРЕШЕНИЕ ТРАССИРОВКИ, % от экрана (с него DLSS восстанавливает кадр)
    public int rtQuality = 3;               // КАЧЕСТВО: 0 низкое, 1 среднее, 2 высокое, 3 ультра
    // ⚠️ По умолчанию УЛЬТРА — это ровно то, что было зашито в коде до появления настроек.
    // Поставь я «высокое», новая сборка тихо поменяла бы картинку и FPS без ведома игрока.

    // === RT PATCH === СТРАНИЦА «ИЗОБРАЖЕНИЕ» — порт ручек Eclipse (Post_Processing / Color_Processing).
    // Всё в процентах: так их показывает UI, а шейдер уже переводит в свои единицы (см. RtPost).
    public int motionBlur = 50;    // размытие в движении: доля вектора движения кадра (0 = выкл)
    public int vignette = 0;       // виньетка: затемнение углов
    public int filmGrain = 0;      // зерно плёнки
    public int chromatic = 0;      // хроматическая аберрация линзы
    public int exposureBias = 0;   // сдвиг экспозиции, десятые доли стопа (-20..20 => -2..+2 EV)
    public int saturation = 100;   // насыщенность (100 = как снято)
    public int lensFlare = 60;     // блики линзы от солнца/луны (гасятся, если светило загорожено)

    // === RT PATCH === СТРАНИЦА «МИР» — порт ручек Eclipse (Torch_Colors, Ambient_Colors, Fog, Clouds).
    // Множители на константы, которые у нас УЖЕ есть в шейдере (см. RtSnapshot: rtCfg2/rtCfg3).
    public int fogDensity = 100;          // плотность дымки на горизонте (границы — от дальности прорисовки)
    public int torchBrightness = 100;     // яркость света факелов (Eclipse: TORCH_AMOUNT)
    public int emissiveBrightness = 100;  // яркость светящихся текселей (Eclipse: Emissive_Brightness)
    public int minLight = 100;            // минимальное освещение: пол яркости в полной темноте
    public int starsBrightness = 100;     // яркость звёзд (Eclipse: STARS_BRIGHTNESS)
    public int cloudAmount = 100;         // количество облаков (тип погоды не меняется)

    public void write() {
        if (!Files.exists(CONFIG_PATH.getParent())) {
            try {
                Files.createDirectories(CONFIG_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            Files.write(CONFIG_PATH, Collections.singleton(GSON.toJson(this)));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path CONFIG_PATH;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static Config load(Path path) {
        Config config;
        Config.CONFIG_PATH = path;

        if (Files.exists(path)) {
            try (FileReader fileReader = new FileReader(path.toFile())) {
                config = GSON.fromJson(fileReader, Config.class);
            } catch (IOException exception) {
                throw new RuntimeException(exception.getMessage());
            }
        }
        else {
            config = new Config();
            config.write();
        }

        return config;
    }
}
