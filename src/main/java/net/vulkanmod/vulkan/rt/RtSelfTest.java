package net.vulkanmod.vulkan.rt;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteOrder;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * M8.2 — самопроверка движка структур ускорения ПРЯМО В РАНТАЙМЕ Minecraft.
 *
 * Строит тестовый треугольный BLAS + TLAS через {@link AccelStruct} (как M1b в
 * прототипе) и пишет результат в лог. Доказывает, что аппаратная сборка AS
 * реально идёт на GPU через compute-очередь VulkanMod — прежде чем браться за
 * сложную интеграцию геометрии чанков. Всё обёрнуто в try/catch: провал
 * самопроверки логируется, но НЕ роняет игру.
 */
public class RtSelfTest {

    public static void run() {
        if (!DeviceManager.rayTracingSupported) return;

        VkDevice device = Vulkan.getVkDevice();
        try {
            // --- 1. Треугольник -> буфер (host-visible + device address) ---
            float[] verts = {0f, 0.6f, 0f, -0.6f, -0.4f, 0f, 0.6f, -0.4f, 0f};
            AccelStruct.RawBuffer vtx = AccelStruct.createBuffer(verts.length * 4L,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
            uploadFloats(device, vtx.memory, verts);

            // --- 2. BLAS вокруг треугольника ---
            AccelStruct blas;
            try (MemoryStack stack = stackPush()) {
                VkAccelerationStructureGeometryKHR.Buffer geo = VkAccelerationStructureGeometryKHR.calloc(1, stack);
                geo.get(0).sType$Default()
                        .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
                geo.get(0).geometry().triangles().sType$Default()
                        .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(12)
                        .maxVertex(2).indexType(VK_INDEX_TYPE_NONE_KHR);
                geo.get(0).geometry().triangles().vertexData().deviceAddress(vtx.address);

                VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                        VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
                build.get(0).sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .pGeometries(geo).geometryCount(1);

                blas = AccelStruct.build(build, 1, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            }

            // --- 3. TLAS из одного экземпляра BLAS ---
            AccelStruct.RawBuffer inst = AccelStruct.createBuffer(VkAccelerationStructureInstanceKHR.SIZEOF,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
            AccelStruct tlas;
            try (MemoryStack stack = stackPush()) {
                PointerBuffer pData = stack.mallocPointer(1);
                vkMapMemory(device, inst.memory, 0, VkAccelerationStructureInstanceKHR.SIZEOF, 0, pData);
                VkAccelerationStructureInstanceKHR e = VkAccelerationStructureInstanceKHR.create(pData.get(0));
                var m = e.transform().matrix();          // единичная матрица 3x4
                m.put(0, 1f).put(1, 0f).put(2, 0f).put(3, 0f);
                m.put(4, 0f).put(5, 1f).put(6, 0f).put(7, 0f);
                m.put(8, 0f).put(9, 0f).put(10, 1f).put(11, 0f);
                e.instanceCustomIndex(0).mask(0xFF).instanceShaderBindingTableRecordOffset(0)
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(blas.deviceAddress);
                vkUnmapMemory(device, inst.memory);

                VkAccelerationStructureGeometryKHR.Buffer geo = VkAccelerationStructureGeometryKHR.calloc(1, stack);
                geo.get(0).sType$Default()
                        .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                        .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
                geo.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
                geo.get(0).geometry().instances().data().deviceAddress(inst.address);

                VkAccelerationStructureBuildGeometryInfoKHR.Buffer build =
                        VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
                build.get(0).sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .pGeometries(geo).geometryCount(1);

                tlas = AccelStruct.build(build, 1, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            }

            Initializer.LOGGER.info("[RT] Self-test OK — built triangle BLAS (addr=0x{}) + TLAS (addr=0x{}) on GPU.",
                    Long.toHexString(blas.deviceAddress), Long.toHexString(tlas.deviceAddress));

            // --- 4. Уборка ---
            tlas.free();
            blas.free();
            AccelStruct.destroyBuffer(inst);
            AccelStruct.destroyBuffer(vtx);
        } catch (Throwable t) {
            Initializer.LOGGER.error("[RT] Self-test FAILED (движок AS не собрал структуру): ", t);
        }
    }

    private static void uploadFloats(VkDevice device, long memory, float[] data) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            vkMapMemory(device, memory, 0, data.length * 4L, 0, p);
            MemoryUtil.memByteBuffer(p.get(0), data.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(data);
            vkUnmapMemory(device, memory);
        }
    }
}
