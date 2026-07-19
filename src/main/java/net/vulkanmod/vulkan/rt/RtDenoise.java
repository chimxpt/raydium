package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.158): ВСТРОЕННЫЙ ВРЕМЕННОЙ ДЕНОЙЗЕР ===
//
// ЗАЧЕМ. Картинка держалась на DLSS Ray Reconstruction, то есть мод жил только у NVIDIA. Своя
// очистка снимает эту привязку: трассировка идёт через VK_KHR_ray_query, который есть и у RDNA2+,
// и у Intel Arc, а не хватало именно денойзера — без него кадр остаётся в зерне.
//
// КАК. Портирован наш же денойзер из rt-engine (веха M6): накапливаем цвет во времени, находя
// тот же пиксель в прошлом кадре. Два отличия от оригинала, оба в пользу мода:
//   * там мы репроецировали мировую точку вручную, а здесь берём ГОТОВЫЕ ВЕКТОРЫ ДВИЖЕНИЯ —
//     они точнее, потому что учитывают ещё и движущиеся объекты, а не только поворот камеры;
//   * добавлено отсечение по окрестности (сравниваем историю с разбросом соседних пикселей) —
//     это убирает призраков там, где вектор движения соврал, без хранения прошлых нормалей.
//
// ⚠️ ДЕНОЙЗЕР, А НЕ АПСКЕЙЛЕР. Он чистит кадр в том разрешении, в котором тот посчитан, и НЕ
// поднимает его. Поэтому при выборе встроенного денойзера масштаб трассировки принудительно 100%
// (см. Config.upscalingActive) — иначе кадр растянулся бы простым блитом в мыло.

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class RtDenoise {

    private static final String SRC = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;

            layout(binding = 0, rgba16f) uniform readonly  image2D srcCol;    // шумный кадр
            layout(binding = 1, r32f)    uniform readonly  image2D srcDepth;  // NDC-глубина
            layout(binding = 2, rg16f)   uniform readonly  image2D srcMotion; // сдвиг В ПИКСЕЛЯХ в прошлый кадр
            layout(binding = 3, rgba16f) uniform readonly  image2D srcNormal; // нормаль + шероховатость
            layout(binding = 4, r16f)    uniform readonly  image2D srcReact;  // 1 = НЕ накапливать (вода)
            layout(binding = 5, rgba32f) uniform readonly  image2D histIn;    // rgb = накоплено, a = счётчик
            layout(binding = 6, rgba32f) uniform writeonly image2D histOut;
            layout(binding = 7, rgba16f) uniform writeonly image2D dst;       // чистый кадр
            // Guide прошлого кадра: нормаль + глубина. Без него историю не отличить «своей» от
            // чужой — на краях объектов и при вскрытии фона она перетекает между поверхностями,
            // и это читается как «изображение плывёт». В rt-engine (M6) роль играли мировая точка
            // с номером объекта; здесь дешевле и точнее нормаль с глубиной, они уже посчитаны.
            layout(binding = 8, rgba16f) uniform readonly  image2D guideIn;
            layout(binding = 9, rgba16f) uniform writeonly image2D guideOut;

            layout(push_constant) uniform PC { int w; int h; int reset; int debug; } pc;

            void main() {
                ivec2 ip = ivec2(gl_GlobalInvocationID.xy);
                if (ip.x >= pc.w || ip.y >= pc.h) return;
                ivec2 last = ivec2(pc.w - 1, pc.h - 1);

                vec3 cur = imageLoad(srcCol, ip).rgb;

                // ОКРЕСТНОСТЬ 3x3: среднее и разброс. По ним отсечём историю, которая «не про этот
                // пиксель». Это дешевле и надёжнее, чем хранить прошлые нормали с глубиной: вектор
                // движения врёт на краях объектов и при вскрытии фона, и именно там лезут призраки.
                vec3 m1 = vec3(0.0), m2 = vec3(0.0);
                for (int y = -1; y <= 1; y++)
                    for (int x = -1; x <= 1; x++) {
                        vec3 c = imageLoad(srcCol, clamp(ip + ivec2(x, y), ivec2(0), last)).rgb;
                        m1 += c; m2 += c * c;
                    }
                m1 /= 9.0; m2 /= 9.0;
                vec3 sigma = sqrt(max(m2 - m1 * m1, vec3(0.0)));
                // ⚠️ Коридор УЖЕ (было 1.5). Разброс считается по ШУМНОМУ кадру, то есть задаётся
                // самим зерном: при широком коридоре старая история проходит почти без проверки,
                // и призраки живут. Узкий режет их заметно строже, ценой чуть большего шума.
                vec3 lo = m1 - sigma * 0.9;
                vec3 hi = m1 + sigma * 0.9;

                vec3  nrm = imageLoad(srcNormal, ip).xyz;
                float dep = imageLoad(srcDepth,  ip).r;

                vec3  accum = cur;
                float n = 1.0;

                if (pc.reset == 0) {
                    vec2 mv = imageLoad(srcMotion, ip).xy;
                    vec2 pf = vec2(ip) + 0.5 + mv;
                    ivec2 pp = ivec2(floor(pf));
                    if (pp.x >= 0 && pp.y >= 0 && pp.x <= last.x && pp.y <= last.y) {
                        // ОТБРАКОВКА ПО ГЕОМЕТРИИ: та же поверхность в прошлом кадре или чужая?
                        // Нормаль сравниваем по углу, глубину — по ОТНОСИТЕЛЬНОЙ разнице (дальние
                        // объекты имеют право отличаться сильнее, чем ближние).
                        vec4  pg    = imageLoad(guideIn, pp);
                        float nDot  = dot(normalize(nrm), normalize(pg.xyz));
                        float dRel  = abs(pg.w - dep) / max(max(dep, pg.w), 1e-4);
                        if (nDot < 0.85 || dRel > 0.06) {
                            imageStore(histOut,  ip, vec4(cur, 1.0));
                            imageStore(guideOut, ip, vec4(nrm, dep));
                            imageStore(dst,      ip, vec4(cur, 1.0));
                            return;                       // история чужая — начинаем копить заново
                        }

                        vec4 h  = imageLoad(histIn, pp);
                        vec3 hc = clamp(h.rgb, lo, hi);   // призраки обрезаются здесь

                        // ПАМЯТЬ ТЕМ КОРОЧЕ, ЧЕМ БЫСТРЕЕ ПИКСЕЛЬ ЕДЕТ ПО ЭКРАНУ (приём из M6).
                        // Стоим — копим до 32 кадров и получаем чистую картинку. Бежим — падаем
                        // до 3: шумнее, зато без смаза. Длинная история на движении наслаивает
                        // сдвинутые копии, и это выглядит хуже любого зерна.
                        // Память падает БЫСТРЕЕ, чем в M6 (было 32->3 на 5 пикселях). При обычной
                        // ходьбе экран едет на 2-3 пикселя за кадр, и прежняя кривая всё ещё копила
                        // под два десятка кадров — отсюда шлейф. Теперь на тех же скоростях память
                        // короткая, а полная глубина работает только когда камера реально стоит.
                        float speed = length(mv);
                        float maxN  = mix(32.0, 2.0, clamp(speed / 1.5, 0.0, 1.0));

                        // МАСКА РЕАКТИВНОСТИ: анимированные поверхности (рябь воды, частицы).
                        // ⚠️ Память им КОРОТКАЯ, но НЕ НУЛЕВАЯ. Сначала я запрещал копить вовсе —
                        // и вода осталась в чистом зерне, потому что «не копить» значит «не чистить»
                        // (на карте копилки она была сплошь чёрной). Шесть кадров при 75 FPS это
                        // около 80 мс: шум усредняется заметно, а рябь за это время сдвигается
                        // на доли пикселя и смазаться не успевает.
                        float react = clamp(imageLoad(srcReact, ip).r, 0.0, 1.0);
                        maxN = mix(maxN, 6.0, react);

                        n = min(h.a + 1.0, max(maxN, 1.0));
                        accum = mix(hc, cur, 1.0 / n);    // скользящее среднее
                    }
                }

                imageStore(histOut,  ip, vec4(accum, n));
                imageStore(guideOut, ip, vec4(nrm, dep));
                // ОТЛАДКА (F6, режим «denoiser history»): рисуем не кадр, а КОПИЛКУ. Чёрное —
                // история не набирается вовсе, белое — набралась полностью. Один взгляд отвечает
                // на вопрос, который иначе приходится угадывать: работает накопление или нет.
                if (pc.debug != 0) { imageStore(dst, ip, vec4(vec3(n / 32.0), 1.0)); return; }
                imageStore(dst,      ip, vec4(accum, 1.0));
            }
            """;

    private static final int BINDINGS = 10;
    /** Номер режима F6, в котором показываем копилку вместо кадра. */
    public static final int DEBUG_VIEW = 7;

    private static long descLayout, descPool, pipelineLayout, pipeline;
    // ⚠️ НАБОР ДЕСКРИПТОРОВ — НА КАЖДЫЙ КАДР В ПОЛЁТЕ, А НЕ ДВА НА ЧЁТНОСТЬ. Видеокарта держит
    // в работе несколько кадров сразу; двух наборов не хватало, и я переписывал тот, который она
    // ещё читала. Привязки истории при этом могли указывать не на тот образ — накопление
    // превращалось в кашу, и шум оставался прежним что стоя, что на ходу (репорт M8.158c).
    private static final long[] sets = new long[RtSnapshot.FRAMES];
    private static RtImage[] hist = new RtImage[2];
    private static RtImage[] guide = new RtImage[2];   // нормаль + глубина прошлого кадра
    // M8.159: чистый кадр в РЕНДЕРНОМ разрешении. Писать прямо в hdr нельзя — мы из него же
    // читаем окрестность 3x3, и рабочие группы затирали бы данные друг другу. Поэтому свой образ,
    // а потом копия в hdr, откуда пост поднимет кадр до экранного.
    private static RtImage clean;
    private static int width, height;
    private static boolean failed = false;
    private static int frame = 0;
    private static boolean historyValid = false;

    /** Сброс истории: смена мира, разрешения, включение денойзера. */
    public static void resetHistory() { historyValid = false; }

    /**
     * Записать проход очистки. Возвращает true, если чистый кадр лежит в dst.
     * Зовётся вместо DLSS, когда выбран встроенный денойзер.
     */
    public static boolean record(VkCommandBuffer cmd, MemoryStack stack,
                                 RtImage src, RtImage[] guides, RtImage dst,
                                 int w, int h, boolean reset) {
        if (failed || src == null || dst == null || guides == null || guides.length < 6) return false;
        try {
            if (pipeline == 0L) init();
            ensureHistory(w, h);
            if (hist[0] == null) return false;

            int rd = frame & 1, wr = 1 - rd;          // ping-pong истории — по чётности
            int si = frame % RtSnapshot.FRAMES;       // набор дескрипторов — по кадру в полёте

            // Все участники — в GENERAL: компьют и читает, и пишет образы.
            for (RtImage img : new RtImage[]{src, guides[0], guides[1], guides[2], guides[5]})
                img.transition(stack, cmd, VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            for (RtImage img : new RtImage[]{hist[rd], hist[wr], guide[rd], guide[wr], clean})
                img.transition(stack, cmd, VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            writeSet(sets[si], src, guides, clean, hist[rd], hist[wr], guide[rd], guide[wr]);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(sets[si]), null);

            java.nio.ByteBuffer pc = stack.malloc(16);
            boolean doReset = reset || !historyValid;
            pc.putInt(0, w).putInt(4, h).putInt(8, doReset ? 1 : 0)
              .putInt(12, RtPost.debugView == DEBUG_VIEW ? 1 : 0);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);

            // Чистый кадр -> hdr. Возвращаем FALSE: кадр лежит в РЕНДЕРНОМ разрешении, и поднять
            // его до экранного должен пост (билинейка с подчёркиванием). Вернув true, мы сказали бы
            // «готовый кадр в экранном образе» — и при масштабе < 100% он занял бы угол экрана.
            clean.transition(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            src.transition(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkImageCopy.Buffer reg = VkImageCopy.calloc(1, stack);
            reg.get(0).srcSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                      .dstSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                      .extent(e -> e.width(w).height(h).depth(1));
            vkCmdCopyImage(cmd, clean.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           src.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, reg);
            src.transition(stack, cmd, VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            historyValid = true;
            frame++;
            return false;
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] built-in denoiser disabled (failure)", t);
            return false;
        }
    }

    private static void ensureHistory(int w, int h) {
        if (hist[0] != null && width == w && height == h) return;
        destroyHistory();
        // rgba32f: rgb — накопленный цвет, a — сколько кадров в копилке. Половинная точность
        // здесь опасна: скользящее среднее с шагом 1/n на 32 кадрах теряет младшие разряды.
        for (int i = 0; i < 2; i++) {
            hist[i]  = new RtImage(w, h, VK_FORMAT_R32G32B32A32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
            guide[i] = new RtImage(w, h, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT);
        }
        clean = new RtImage(w, h, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        width = w; height = h;
        historyValid = false;   // новый размер — копилка недействительна
    }

    private static void destroyHistory() {
        for (int i = 0; i < 2; i++) {
            if (hist[i]  != null) { hist[i].free();  hist[i]  = null; }
            if (guide[i] != null) { guide[i].free(); guide[i] = null; }
        }
        if (clean != null) { clean.free(); clean = null; }
    }

    private static void writeSet(long set, RtImage src, RtImage[] g, RtImage dst,
                                 RtImage hIn, RtImage hOut, RtImage gIn, RtImage gOut) {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            RtImage[] order = { src, g[0], g[1], g[2], g[5], hIn, hOut, dst, gIn, gOut };
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
                ii.get(0).imageView(order[i].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                w.get(i).sType$Default().dstSet(set).dstBinding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(ii);
            }
            vkUpdateDescriptorSets(device, w, null);
        }
    }

    private static void init() {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++)
                binds.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, dslInfo, null, p), "denoise dsl");
            descLayout = p.get(0);

            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(1, stack);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(BINDINGS * RtSnapshot.FRAMES);
            VkDescriptorPoolCreateInfo dp = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().pPoolSizes(ps).maxSets(RtSnapshot.FRAMES);
            check(vkCreateDescriptorPool(device, dp, null, p), "denoise pool");
            descPool = p.get(0);

            for (int i = 0; i < RtSnapshot.FRAMES; i++) {
                VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default().descriptorPool(descPool).pSetLayouts(stack.longs(descLayout));
                LongBuffer pSet = stack.mallocLong(1);
                check(vkAllocateDescriptorSets(device, ai, pSet), "denoise set");
                sets[i] = pSet.get(0);
            }

            long module = RtSnapshot.compileCompute(device, SRC);
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descLayout)).pPushConstantRanges(pcr);
            check(vkCreatePipelineLayout(device, plInfo, null, p), "denoise layout");
            pipelineLayout = p.get(0);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpci, null, p), "denoise pipeline");
            pipeline = p.get(0);
            vkDestroyShaderModule(device, module, null);

            Initializer.LOGGER.info("[RT] built-in temporal denoiser ready");
        }
    }

    public static void destroy() {
        VkDevice device = Vulkan.getVkDevice();
        destroyHistory();
        if (pipeline != 0L)       { vkDestroyPipeline(device, pipeline, null); pipeline = 0L; }
        if (pipelineLayout != 0L) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0L; }
        if (descPool != 0L)       { vkDestroyDescriptorPool(device, descPool, null); descPool = 0L; }
        if (descLayout != 0L)     { vkDestroyDescriptorSetLayout(device, descLayout, null); descLayout = 0L; }
        historyValid = false;
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new RuntimeException("[RT] denoise " + what + ": " + r);
    }
}
