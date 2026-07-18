package net.vulkanmod.vulkan.rt;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * M8.3 шаг-1 — доказательство, что по TLAS мира реально ИДУТ лучи (не только
 * строятся структуры). Свой минимальный compute-пайплайн (в VulkanMod нет
 * compute-инфраструктуры) с ray-query шейдером: раз в ~секунду пускает ОДИН луч
 * из позиции камеры по направлению взгляда и пишет дистанцию попадания в
 * host-visible буфер, который читаем обратно и логируем.
 *
 * Всё под {@code rayTracingSupported}, в try/catch — провал логируется, игру не
 * роняет. Coord-система: BLAS секций локальны [0..16), TLAS переносит их в
 * АБСОЛЮТНЫЕ мировые блок-координаты; камера ({@code camera.position()}) тоже в
 * абсолютных — значит origin/dir подаём напрямую.
 */
public class RtProbe {

    // Ray-query шейдер: один поток, один луч из камеры в TLAS мира.
    private static final String COMP_SRC = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

            layout(binding = 0) uniform accelerationStructureEXT tlas;
            layout(std430, binding = 1) buffer Result {
                float t;      // дистанция до попадания (-1 = промах)
                int   hit;    // 1 попал / 0 мимо
                int   primId; // индекс треугольника
                int   pad;
            };
            layout(push_constant) uniform Push {
                vec4 origin;  // xyz мировая позиция камеры
                vec4 dir;     // xyz направление взгляда
            };

            void main() {
                rayQueryEXT rq;
                rayQueryInitializeEXT(rq, tlas, gl_RayFlagsOpaqueEXT, 0xFF,
                        origin.xyz, 0.01, normalize(dir.xyz), 4096.0);
                while (rayQueryProceedEXT(rq)) {}
                if (rayQueryGetIntersectionTypeEXT(rq, true)
                        == gl_RayQueryCommittedIntersectionTriangleEXT) {
                    t = rayQueryGetIntersectionTEXT(rq, true);
                    hit = 1;
                    primId = rayQueryGetIntersectionPrimitiveIndexEXT(rq, true);
                } else {
                    t = -1.0; hit = 0; primId = -1;
                }
                pad = 0;
            }
            """;

    private static RtProbe INSTANCE;
    private static boolean failed = false;

    // Камера (обновляется каждый кадр из WorldRenderer.setupRenderer).
    private static volatile double camOx, camOy, camOz;
    private static volatile float camDx, camDy, camDz;
    private static volatile boolean cameraSet = false;

    private static long lastProbeMs = 0;

    /** Из WorldRenderer: текущие позиция и направление взгляда камеры (мировые). */
    public static void setCamera(double ox, double oy, double oz, float dx, float dy, float dz) {
        camOx = ox; camOy = oy; camOz = oz;
        camDx = dx; camDy = dy; camDz = dz;
        cameraSet = true;
    }

    /** Раз в кадр (из RtWorld.tick): не чаще раза в секунду пустить пробный луч. */
    public static void tick() {
        if (failed || !DeviceManager.rayTracingSupported || !cameraSet) return;
        if (RtWorld.INSTANCE == null) return;
        long tlas = RtWorld.INSTANCE.tlasHandle();
        if (tlas == VK_NULL_HANDLE) return;   // TLAS мира ещё не собран

        long now = System.currentTimeMillis();
        if (now - lastProbeMs < 1000) return;
        lastProbeMs = now;

        try {
            if (INSTANCE == null) INSTANCE = new RtProbe();
            INSTANCE.trace(tlas);
        } catch (Throwable e) {
            failed = true;
            Initializer.LOGGER.error("[RT] probe failed (test ray was not traced): ", e);
        }
    }

    // --- ресурсы пайплайна ---
    private final long descLayout, descPool, descSet, pipelineLayout, pipeline;
    private final AccelStruct.RawBuffer result;   // host-visible SSBO для читалки

    private RtProbe() {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            // 1. Дескрипторы: 0 = TLAS, 1 = результат (SSBO).
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            LongBuffer pDsl = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, dslInfo, null, pDsl), "dsl");
            descLayout = pDsl.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().pPoolSizes(poolSizes).maxSets(1);
            LongBuffer pDp = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device, dpInfo, null, pDp), "descriptor pool");
            descPool = pDp.get(0);

            VkDescriptorSetAllocateInfo dsAlloc = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descPool).pSetLayouts(stack.longs(descLayout));
            LongBuffer pSet = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(device, dsAlloc, pSet), "alloc set");
            descSet = pSet.get(0);

            // 2. Результат-буфер (host-visible) + запись его дескриптора (не меняется).
            result = AccelStruct.createBuffer(16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
            VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack);
            bi.get(0).buffer(result.buffer).offset(0).range(VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType$Default().dstSet(descSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);
            vkUpdateDescriptorSets(device, w, null);

            // 3. Пайплайн: push-константы (2×vec4 = 32Б) + compute-стадия.
            long shader = createShaderModule(device, COMP_SRC);
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(32);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descLayout)).pPushConstantRanges(pcr);
            LongBuffer pPl = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, plInfo, null, pPl), "pipeline layout");
            pipelineLayout = pPl.get(0);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer pPipe = stack.mallocLong(1);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpci, null, pPipe), "compute pipeline");
            pipeline = pPipe.get(0);

            vkDestroyShaderModule(device, shader, null);
        }
        Initializer.LOGGER.info("[RT] RtProbe ready - compute ray-query pipeline built.");
    }

    /** Пустить один луч из камеры по TLAS мира и залогировать результат. */
    private void trace(long tlasHandle) {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            // Обновляем привязку TLAS (хэндл меняется при пересборке мира).
            VkWriteDescriptorSetAccelerationStructureKHR asw =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType$Default().pAccelerationStructures(stack.longs(tlasHandle));
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType$Default().dstSet(descSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).pNext(asw.address());
            vkUpdateDescriptorSets(device, w, null);

            ByteBuffer push = stack.malloc(32);
            push.putFloat(0, (float) camOx).putFloat(4, (float) camOy).putFloat(8, (float) camOz).putFloat(12, 0f);
            push.putFloat(16, camDx).putFloat(20, camDy).putFloat(24, camDz).putFloat(28, 0f);

            CommandPool.CommandBuffer cmd = DeviceManager.getComputeQueue().beginCommands();
            vkCmdBindPipeline(cmd.getHandle(), VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd.getHandle(), VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descSet), null);
            vkCmdPushConstants(cmd.getHandle(), pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(cmd.getHandle(), 1, 1, 1);
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
            mb.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cmd.getHandle(), VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, mb, null, null);
            DeviceManager.getComputeQueue().submitCommands(cmd);
            DeviceManager.getComputeQueue().waitIdle();

            // Читаем результат.
            PointerBuffer p = stack.mallocPointer(1);
            vkMapMemory(device, result.memory, 0, 16, 0, p);
            ByteBuffer r = MemoryUtil.memByteBuffer(p.get(0), 16).order(ByteOrder.nativeOrder());
            float t = r.getFloat(0);
            int hit = r.getInt(4);
            int prim = r.getInt(8);
            vkUnmapMemory(device, result.memory);

            if (hit == 1)
                Initializer.LOGGER.info(String.format(
                        "[RT] camera ray HIT world at t=%.2f blocks (prim=%d) from (%.1f,%.1f,%.1f)",
                        t, prim, camOx, camOy, camOz));
            else
                Initializer.LOGGER.info(String.format(
                        "[RT] camera ray MISS (небо/пусто) from (%.1f,%.1f,%.1f) dir(%.2f,%.2f,%.2f)",
                        camOx, camOy, camOz, camDx, camDy, camDz));
        }
    }

    private static long createShaderModule(VkDevice device, String source) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        try {
            // vulkan_1_3 -> SPIR-V, где доступна capability RayQueryKHR (GL_EXT_ray_query).
            shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_3, VK_API_VERSION_1_3);
            long res = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader,
                    "world_probe.comp", "main", options);
            try {
                if (shaderc_result_get_compilation_status(res) != shaderc_compilation_status_success)
                    throw new RuntimeException("shaderc: " + shaderc_result_get_error_message(res));
                ByteBuffer spirv = shaderc_result_get_bytes(res);
                try (MemoryStack stack = stackPush()) {
                    VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack)
                            .sType$Default().pCode(spirv);
                    LongBuffer pMod = stack.mallocLong(1);
                    check(vkCreateShaderModule(device, ci, null, pMod), "shader module");
                    return pMod.get(0);
                }
            } finally {
                shaderc_result_release(res);
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new RuntimeException("[RT] Vulkan error in '" + what + "': code " + result);
    }
}
