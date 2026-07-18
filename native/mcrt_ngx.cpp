// === ТОНКАЯ ПРОСЛОЙКА К NVIDIA NGX (DLSS Ray Reconstruction) ===
//
// ⚠️ ЗАЧЕМ ОНА ВООБЩЕ НУЖНА: NGX API поставляется СТАТИЧЕСКОЙ библиотекой (libnvsdk_ngx.a на Linux,
// nvsdk_ngx_d.lib на Windows). У неё нет .so/.dll с C-экспортами, поэтому позвать DLSS напрямую из
// Java (через Panama FFI или LWJGL) физически нечем — символы надо влинковать в свой нативный код.
// Здесь ровно это: пять простых JNI-функций, всё остальное (буферы, шейдеры, барьеры) — на Java.
//
// Сборка: native/build.sh

#include <jni.h>
#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

#ifdef _WIN32
  #include <windows.h>
#else
  #include <dlfcn.h>
#endif

#include "nvsdk_ngx.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_defs_dlssd.h"
#include "nvsdk_ngx_params_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

static NVSDK_NGX_Parameter* g_params = nullptr;
static NVSDK_NGX_Handle*    g_feature = nullptr;
static VkDevice             g_device = VK_NULL_HANDLE;
static std::wstring         g_modelPath;

// Идентификатор приложения для NGX. Проект личный, не публикуется — берём id из примеров SDK.
static const unsigned long long APP_ID = 0x4D435254;   // 'MCRT'

static std::wstring toWide(const char* s) {
    std::wstring w;
    while (*s) w.push_back((wchar_t) (unsigned char) *s++);
    return w;
}

/**
 * ⚠️ ГЛАВНАЯ ГРАБЛЯ ЗАПУСКА ПОД JVM: NGX, если не дать ему загрузчик, ищет vkGetInstanceProcAddr
 * среди ГЛОБАЛЬНЫХ символов процесса. В обычном приложении Vulkan слинкован — символ есть. А в
 * Minecraft libvulkan грузит LWJGL через dlopen ЛОКАЛЬНО, глобального символа нет, NGX получает
 * NULL и падает по SIGSEGV. Поэтому загрузчик берём сами и передаём NGX явно.
 */
static PFN_vkGetInstanceProcAddr loadGIPA() {
#ifdef _WIN32
    HMODULE h = LoadLibraryA("vulkan-1.dll");
    if (!h) return nullptr;
    return (PFN_vkGetInstanceProcAddr) GetProcAddress(h, "vkGetInstanceProcAddr");
#else
    void* h = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!h) h = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) return nullptr;
    return (PFN_vkGetInstanceProcAddr) dlsym(h, "vkGetInstanceProcAddr");
#endif
}

// Лог NGX уходит в файл рядом с моделью — иначе диагностировать нечем: изнутри игры его не видно.
static FILE* g_log = nullptr;
static void ngxLog(const char* msg, NVSDK_NGX_Logging_Level, NVSDK_NGX_Feature) {
    if (g_log) { fputs(msg, g_log); fflush(g_log); }
}

extern "C" {

/**
 * Расширения устройства, которых DLSS требует от Vulkan. Их надо включить ДО создания VkDevice,
 * иначе NGX не запустится. Возвращаем строкой через '\n' — Java добавит их в список расширений.
 */
JNIEXPORT jstring JNICALL
Java_net_vulkanmod_vulkan_rt_RtDlss_nRequiredDeviceExtensions(JNIEnv* env, jclass) {
    unsigned int instCount = 0, devCount = 0;
    const char** instExts = nullptr;
    const char** devExts = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_RequiredExtensions(&instCount, &instExts, &devCount, &devExts);
    if (NVSDK_NGX_FAILED(r)) return env->NewStringUTF("");
    std::string out;
    for (unsigned int i = 0; i < devCount; i++) {
        if (!out.empty()) out += "\n";
        out += devExts[i];
    }
    return env->NewStringUTF(out.c_str());
}

/**
 * Инициализация NGX. modelPath — папка с моделью Ray Reconstruction:
 * на Linux это libnvidia-ngx-dlssd.so.<версия>, на Windows — nvngx_dlssd.dll.
 * NGX сам находит файл в этой папке по своему имени, нам достаточно указать путь.
 */
JNIEXPORT jint JNICALL
Java_net_vulkanmod_vulkan_rt_RtDlss_nInit(JNIEnv* env, jclass,
                                          jlong instance, jlong physDevice, jlong device,
                                          jstring jModelPath, jstring jLogPath) {
    const char* mp = env->GetStringUTFChars(jModelPath, nullptr);
    const char* lp = env->GetStringUTFChars(jLogPath, nullptr);
    std::string mpCopy(mp);
    g_modelPath = toWide(mp);
    std::wstring logPath = toWide(lp);
    env->ReleaseStringUTFChars(jModelPath, mp);
    env->ReleaseStringUTFChars(jLogPath, lp);

    // ⚠️⚠️ ГЛАВНАЯ ГРАБЛЯ ЗАПУСКА NGX ПОД JVM — РАЗМЕР СТЕКА ПОТОКА.
    // NGX (и CUDA внутри него) при инициализации кладёт на стек очень много. Потоки JVM имеют ~1 МБ
    // (нативный main — 8 МБ), и NGX сносит процесс по SIGSEGV прямо посреди работы — в логе это
    // выглядело как обрыв строки на полуслове. Лечится НЕ здесь, а на стороне Java: все нативные
    // вызовы идут через один поток с большим стеком (см. RtDlss.NGX_THREAD). Проверено: из потока
    // с 64 МБ стека ядра модели поднимаются (InitCubins / SetGPUArch / Fast UAV clear), Init = 0.
    //
    // Тупики, которые проверены и отброшены (чтобы никто не наступил снова):
    //   • предзагрузить модель самим с RTLD_GLOBAL — падения нет, но её символы NVSDK_NGX_*
    //     перехватывают вызовы у статической библиотеки, и Init мгновенно даёт FeatureNotSupported;
    //   • то же с RTLD_LOCAL — символы целы, но падение возвращается;
    //   • LD_PRELOAD модели и glibc.rtld.optional_static_tls — то же самое, дело не в TLS.
    PFN_vkGetInstanceProcAddr gipa = loadGIPA();
    if (!gipa) return -100;
    PFN_vkGetDeviceProcAddr gdpa =
            (PFN_vkGetDeviceProcAddr) gipa((VkInstance) instance, "vkGetDeviceProcAddr");
    if (!gdpa) return -101;

    if (!g_log) {
        char path[4096];
        snprintf(path, sizeof(path), "%ls/mcrt_ngx.log", g_modelPath.c_str());
        g_log = fopen(path, "w");   // лог NGX кладём рядом с моделью
    }

    // Где NGX искать модель фичи (кроме папки приложения)
    const wchar_t* paths[1] = { g_modelPath.c_str() };
    NVSDK_NGX_FeatureCommonInfo info = {};
    info.PathListInfo.Path = paths;
    info.PathListInfo.Length = 1;
    info.LoggingInfo.LoggingCallback = ngxLog;
    info.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_ON;

    g_device = (VkDevice) device;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init(
            APP_ID, logPath.c_str(),
            (VkInstance) instance, (VkPhysicalDevice) physDevice, (VkDevice) device,
            gipa, gdpa, &info);
    if (NVSDK_NGX_FAILED(r)) return (jint) r;

    r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&g_params);
    if (NVSDK_NGX_FAILED(r)) return (jint) r;

    // Поддерживается ли Ray Reconstruction этим драйвером/GPU?
    int available = 0;
    NVSDK_NGX_Parameter_GetI(g_params, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &available);
    if (!available) return -1;
    return 0;
}

/** Создание фичи DLSS-D (Ray Reconstruction). Возвращает handle (0 = ошибка). */
JNIEXPORT jlong JNICALL
Java_net_vulkanmod_vulkan_rt_RtDlss_nCreateFeature(JNIEnv*, jclass, jlong cmdBuf,
                                                   jint inW, jint inH, jint outW, jint outH,
                                                   jint preset) {
    if (!g_params) return 0;
    if (g_feature) { NVSDK_NGX_VULKAN_ReleaseFeature(g_feature); g_feature = nullptr; }

    // МОДЕЛЬ (пресет). ⚠️ У Ray Reconstruction СВОЯ линейка пресетов, не та, что у апскейлера:
    // здесь D = трансформер по умолчанию, E = САМЫЙ СВЕЖИЙ трансформер, а F..O в этом SDK помечены
    // «do not use, reverts to default behavior» — то есть знаменитый «preset K» тут молча
    // откатился бы к дефолту. Ставим явно, для всех уровней качества.
    NVSDK_NGX_Parameter_SetUI(g_params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_DLAA, preset);
    NVSDK_NGX_Parameter_SetUI(g_params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Quality, preset);
    NVSDK_NGX_Parameter_SetUI(g_params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Balanced, preset);
    NVSDK_NGX_Parameter_SetUI(g_params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance, preset);
    NVSDK_NGX_Parameter_SetUI(g_params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraQuality, preset);

    NVSDK_NGX_DLSSD_Create_Params p = {};
    p.InDenoiseMode   = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
    p.InRoughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed;   // шероховатость лежит в normals.w
    p.InUseHWDepth    = NVSDK_NGX_DLSS_Depth_Type_HW;           // мы пишем NDC-глубину, как растеризатор
    p.InWidth = inW;   p.InHeight = inH;
    p.InTargetWidth = outW;   p.InTargetHeight = outH;
    p.InPerfQualityValue = NVSDK_NGX_PerfQuality_Value_MaxQuality;
    p.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_IsHDR       // цвет линейный HDR (до тонмаппера)
                           | NVSDK_NGX_DLSS_Feature_Flags_MVLowRes    // векторы движения в разрешении рендера
                           | NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;

    NVSDK_NGX_Handle* handle = nullptr;
    NVSDK_NGX_Result r = NGX_VULKAN_CREATE_DLSSD_EXT1(g_device, (VkCommandBuffer) cmdBuf,
                                                      1, 1, &handle, g_params, &p);
    if (NVSDK_NGX_FAILED(r)) return 0;
    g_feature = handle;
    return (jlong) handle;
}

static NVSDK_NGX_Resource_VK mkRes(jlong image, jlong view, jint format, int w, int h, bool rw) {
    VkImageSubresourceRange range = {};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;
    return NVSDK_NGX_Create_ImageView_Resource_VK((VkImageView) view, (VkImage) image, range,
                                                  (VkFormat) format, (unsigned) w, (unsigned) h, rw);
}

/**
 * Один вызов Ray Reconstruction.
 * imgs: пары (image, view) в порядке: color, depth, motion, normal, diffAlb, specAlb, output
 * fmts: форматы тех же семи ресурсов
 * nums: jitterX, jitterY, reset, frameTimeMs
 * mats: worldToView[16], viewToClip[16]
 */
JNIEXPORT jint JNICALL
Java_net_vulkanmod_vulkan_rt_RtDlss_nEvaluate(JNIEnv* env, jclass, jlong cmdBuf,
                                              jlongArray jImgs, jintArray jFmts,
                                              jint inW, jint inH, jint outW, jint outH,
                                              jfloatArray jNums, jfloatArray jMats) {
    if (!g_feature || !g_params) return -1;

    jlong imgs[16];  env->GetLongArrayRegion(jImgs, 0, 16, imgs);
    jint  fmts[8];   env->GetIntArrayRegion(jFmts, 0, 8, fmts);
    jfloat nums[4];  env->GetFloatArrayRegion(jNums, 0, 4, nums);
    jfloat mats[32]; env->GetFloatArrayRegion(jMats, 0, 32, mats);

    NVSDK_NGX_Resource_VK color  = mkRes(imgs[0],  imgs[1],  fmts[0], inW,  inH,  false);
    NVSDK_NGX_Resource_VK depth  = mkRes(imgs[2],  imgs[3],  fmts[1], inW,  inH,  false);
    NVSDK_NGX_Resource_VK motion = mkRes(imgs[4],  imgs[5],  fmts[2], inW,  inH,  false);
    NVSDK_NGX_Resource_VK normal = mkRes(imgs[6],  imgs[7],  fmts[3], inW,  inH,  false);
    NVSDK_NGX_Resource_VK diff   = mkRes(imgs[8],  imgs[9],  fmts[4], inW,  inH,  false);
    NVSDK_NGX_Resource_VK spec   = mkRes(imgs[10], imgs[11], fmts[5], inW,  inH,  false);
    NVSDK_NGX_Resource_VK output = mkRes(imgs[12], imgs[13], fmts[6], outW, outH, true);
    // Маска реактивности: где сеть НЕ должна накапливать по времени (вода — рябь бежит по
    // неподвижной поверхности, и накопление превращает её в кисель).
    NVSDK_NGX_Resource_VK react = mkRes(imgs[14], imgs[15], fmts[7], inW,  inH,  false);

    NVSDK_NGX_VK_DLSSD_Eval_Params ev = {};
    ev.pInColor           = &color;
    ev.pInDepth           = &depth;
    ev.pInMotionVectors   = &motion;
    ev.pInNormals         = &normal;
    ev.pInDiffuseAlbedo   = &diff;
    ev.pInSpecularAlbedo  = &spec;
    ev.pInRoughness       = nullptr;              // режим Packed: шероховатость в normals.w
    ev.pInOutput          = &output;
    ev.InJitterOffsetX    = nums[0];
    ev.InJitterOffsetY    = nums[1];
    ev.InReset            = (int) nums[2];
    ev.InFrameTimeDeltaInMsec = nums[3];
    ev.InMVScaleX         = 1.0f;                 // векторы движения уже в пикселях
    ev.InMVScaleY         = 1.0f;
    ev.InRenderSubrectDimensions.Width  = inW;
    ev.InRenderSubrectDimensions.Height = inH;
    ev.pInWorldToViewMatrix = &mats[0];
    ev.pInViewToClipMatrix  = &mats[16];
    ev.pInResponsivityMask  = &react;

    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSSD_EXT((VkCommandBuffer) cmdBuf, g_feature, g_params, &ev);
    return (jint) (NVSDK_NGX_FAILED(r) ? r : 0);
}

JNIEXPORT void JNICALL
Java_net_vulkanmod_vulkan_rt_RtDlss_nShutdown(JNIEnv*, jclass) {
    if (g_feature) { NVSDK_NGX_VULKAN_ReleaseFeature(g_feature); g_feature = nullptr; }
    if (g_device)  { NVSDK_NGX_VULKAN_Shutdown1(g_device); g_device = VK_NULL_HANDLE; }
    g_params = nullptr;
}

}   // extern "C"
