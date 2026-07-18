package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.vulkanmod.interfaces.ExtendedRenderType;
import net.vulkanmod.render.engine.VkCommandEncoder;
import net.vulkanmod.render.engine.VkRenderPass;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;

@Mixin(RenderType.class)
public class RenderTypeM implements ExtendedRenderType {
    @Unique
    TerrainRenderType terrainRenderType;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void inj(String string, RenderSetup renderSetup, CallbackInfo ci) {
        terrainRenderType = switch (string) {
            case "solid" -> TerrainRenderType.SOLID;
            case "cutout" -> TerrainRenderType.CUTOUT;
            case "translucent" -> TerrainRenderType.TRANSLUCENT;
            case "tripwire" -> TerrainRenderType.TRIPWIRE;
            default -> null;
        };
    }

    @Override
    public TerrainRenderType getTerrainRenderType() {
        return terrainRenderType;
    }

    @Shadow @Final private RenderSetup state;
    @Shadow @Final protected String name;

    @Overwrite
    public void draw(MeshData meshData) {
        // === RT PATCH (M8.4): off-screen захват ТЕЛА ИГРОКА. В captureOnly ловим
        // вершины (NEW_ENTITY) + скин батча и выходим БЕЗ рисования на экран/GPU —
        // тело игрока попадает только в TLAS (тень/отражение), но не на растровый кадр. ===
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported
                && net.vulkanmod.vulkan.rt.RtEntities.captureOnly()) {
            try {
                if (meshData.drawState().format() == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {
                    net.vulkanmod.vulkan.texture.VulkanImage tex = null;
                    var tas = this.state.getTextures().get("Sampler0");
                    if (tas != null) {
                        var gpuTex = (net.vulkanmod.render.engine.VkGpuTexture) tas.textureView().texture();
                        var glTex = net.vulkanmod.gl.VkGlTexture.getTexture(gpuTex.glId());
                        if (glTex != null) tex = glTex.getVulkanImage();
                    }
                    net.vulkanmod.vulkan.rt.RtEntities.collectPlayer(
                            meshData.vertexBuffer(), meshData.drawState().vertexCount(), tex);
                }
            } catch (Throwable ignored) {}
            meshData.close();
            return;
        }
        // === RT PATCH (M8.21c): ЗАХВАТ ГЕОМЕТРИИ ПОГОДЫ (дождь/снег) ===
        // Формат погоды = PARTICLE (как у партиклов), рисуется этим же путём. Ловим её вершины и
        // текстуру (rain.png/snow.png в Sampler0) и пускаем в партикловый конвейер -> дождь
        // становится НАСТОЯЩЕЙ мировой геометрией в TLAS: верная перспектива, окклюзия блоками,
        // отражения в воде, без теней. Ванильную растровую отрисовку пропускаем — RT её заменяет.
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported
                && "weather".equals(this.name)
                && meshData.drawState().format() == com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE
                && meshData.drawState().mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) {
            try {
                net.vulkanmod.vulkan.texture.VulkanImage tex = null;
                var tas = this.state.getTextures().get("Sampler0");
                if (tas != null) {
                    var gpuTex = (net.vulkanmod.render.engine.VkGpuTexture) tas.textureView().texture();
                    var glTex = net.vulkanmod.gl.VkGlTexture.getTexture(gpuTex.glId());
                    if (glTex != null) tex = glTex.getVulkanImage();
                }
                net.vulkanmod.vulkan.rt.RtEntities.collectWeather(
                        meshData.vertexBuffer(), meshData.drawState().vertexCount(), tex);
            } catch (Throwable ignored) {}
            meshData.close();
            return;
        }
        // === RT PATCH (M8.46): ЗАХВАТ МОЛНИИ ===
        // Болт рисуется типом "lightning" форматом POSITION_COLOR — без текстуры, чистая эмиссивная
        // геометрия. Наш захват сущностей ждёт NEW_ENTITY и молнию не видел: её не было в TLAS вовсе,
        // поэтому она не рисовалась, не светила и не отражалась. Ловим её вершины и ведём в RT.
        // ⚠️ ФОРМАТ СРАВНИВАЕМ ПО РАЗМЕРУ ВЕРШИНЫ, А НЕ ПО ССЫЛКЕ. Замер показал: батч сюда приходит
        // (name=lightning, [Position, Color], QUADS, 896 вершин), но условие `format == POSITION_COLOR`
        // не срабатывало — игра создаёт для молнии СВОЙ экземпляр формата с теми же элементами, и
        // сравнение объектов по ссылке даёт false. Размер вершины (12 позиция + 4 цвет = 16) надёжнее.
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported
                && "lightning".equals(this.name)
                && meshData.drawState().format().getVertexSize() == 16
                && meshData.drawState().mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) {
            try {
                net.vulkanmod.vulkan.rt.RtEntities.collectLightning(
                        meshData.vertexBuffer(), meshData.drawState().vertexCount());
            } catch (Throwable ignored) {}
            meshData.close();
            return;
        }
        // === /RT PATCH ===
        Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
        final var renderSetupAccessor = (RenderSetupAccessor) (Object) this.state;
        Consumer<Matrix4fStack> consumer = renderSetupAccessor.layeringTransform().getModifier();
        if (consumer != null) {
            matrix4fStack.pushMatrix();
            consumer.accept(matrix4fStack);
        }

        GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
                                                    .writeTransform(RenderSystem.getModelViewMatrix(),
                                                                    new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                                                                    new Vector3f(),
                                                                    renderSetupAccessor.textureTransform().getMatrix());

        Map<String, RenderSetup.TextureAndSampler> map = this.state.getTextures();

        GpuBuffer gpuBuffer = renderSetupAccessor.pipeline().getVertexFormat().uploadImmediateVertexBuffer(meshData.vertexBuffer());
        GpuBuffer gpuBuffer2;
        VertexFormat.IndexType indexType;
        if (meshData.indexBuffer() == null) {
            RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(meshData.drawState().mode());
            gpuBuffer2 = autoStorageIndexBuffer.getBuffer(meshData.drawState().indexCount());
            indexType = autoStorageIndexBuffer.type();
        } else {
            gpuBuffer2 = renderSetupAccessor.pipeline().getVertexFormat().uploadImmediateIndexBuffer(meshData.indexBuffer());
            indexType = meshData.drawState().indexType();
        }

        RenderTarget renderTarget = renderSetupAccessor.outputTarget().getRenderTarget();
        GpuTextureView gpuTextureView = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : renderTarget.getColorTextureView();
        GpuTextureView gpuTextureView2 = renderTarget.useDepth ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView()) : null;

        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Immediate draw for " + this.name, gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty())) {
            renderPass.setPipeline(renderSetupAccessor.pipeline());
            ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissorState.enabled()) {
                renderPass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
            }

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
            renderPass.setVertexBuffer(0, gpuBuffer);

            for(Map.Entry<String, RenderSetup.TextureAndSampler> entry : map.entrySet()) {
                renderPass.bindTexture(entry.getKey(), entry.getValue().textureView(), entry.getValue().sampler());
            }

            VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
            VRenderSystem.calculateMVP();

//            renderPass.setIndexBuffer(gpuBuffer2, indexType);
//            renderPass.drawIndexed(0, 0, meshData.drawState().indexCount(), 1);

            VkCommandEncoder commandEncoder = (VkCommandEncoder) RenderSystem.getDevice().createCommandEncoder();
            commandEncoder.trySetup((VkRenderPass) renderPass);

            // === RT PATCH (M8.4): настоящая текстура ЭТОГО батча (Sampler0) для сбора
            // сущностей — VTextureSelector здесь устаревший (биндится через renderPass) ===
            if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported) {
                net.vulkanmod.vulkan.texture.VulkanImage batchTex = null;
                try {
                    var tas = map.get("Sampler0");
                    if (tas != null) {
                        var gpuTex = (net.vulkanmod.render.engine.VkGpuTexture) tas.textureView().texture();
                        var glTex = net.vulkanmod.gl.VkGlTexture.getTexture(gpuTex.glId());
                        if (glTex != null) batchTex = glTex.getVulkanImage();
                    }
                } catch (Throwable ignored) {}
                net.vulkanmod.vulkan.rt.RtEntities.setNextBatchTexture(batchTex);
                // ⚠️ ИМЯ РЕНДЕР-ТАЙПА (M8.66). По нему опознаём СВЕТЯЩИЕСЯ слои: ванильные глаза
                // паука/эндермена рисуются тайпом "eyes" (полная яркость, без затенения), а ETF
                // добавляет свои эмиссивные слои. Без этого признака они приезжали ко мне обычной
                // геометрией и шейдились обычным светом — ночью почти не видны.
                net.vulkanmod.vulkan.rt.RtEntities.setNextBatchType(this.name);


                // === RT PATCH (M8.80): КАРТА В РУКЕ ===
                // Карта рисуется своим рендер-тайпом (имя начинается с "map") и СВОИМ форматом вершин
                // (POSITION_COLOR_TEX_LIGHTMAP, 28 Б). Мой перехват сущностей ждёт NEW_ENTITY (36 Б) и
                // карту не видел вовсе — в руке её просто не было. Ловим по РАЗМЕРУ вершины (сравнивать
                // формат по ссылке ненадёжно, см. историю с молнией) и перепаковываем в формат сущности.
                // ⚠️ ЛОВИМ ПО ТЕКСТУРЕ, А НЕ ПО ИМЕНИ ТАЙПА. Замер показал: карта рисуется тайпом
                // "text" — тем же, которым игра рисует НАДПИСИ (таблички, имена мобов). По имени её
                // не отличить, а тащить в трассировку весь текст мира нельзя. Отличает её ТЕКСТУРА:
                // у карты она своя — map_background.png и динамическая map/<номер>.
                boolean isFlat28 = meshData.drawState().format().getVertexSize() == 28
                        && meshData.drawState().mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS
                        && batchTex != null && batchTex.name != null;
                // ⚠️ ИМЕНА ТЕКСТУР ЛОВИМ БЕЗ УЧЁТА РЕГИСТРА. Замер показал: содержимое карты приходит
                // с текстурой "Map 0" — с БОЛЬШОЙ буквы и без префикса (это динамическая текстура,
                // игра дорисовывает в неё изученные чанки). Мой фильтр искал строчное "map" и мимо
                // неё прошёл: подложка и метка игрока рисовались, а сама местность — нет.
                boolean isMap = isFlat28 && batchTex.name.toLowerCase().contains("map");
                // ⚠️ ТЕКСТ (таблички, имена мобов) рисуется ТЕМ ЖЕ тайпом "text", а его шрифтовая
                // текстура зовётся "minecraft:default/0" — слова "font" в ней нет. Поэтому текстом
                // считаем ВСЁ прочее плоское, что не карта. Помечаем «светится сам, тени не бросает»
                // (те же биты, что у частиц): иначе имена мобов — билборды к камере — ловили бы
                // освещение и клали тени на землю от букв.
                if (isFlat28 && !isMap) {
                    try {
                        net.vulkanmod.vulkan.rt.RtEntities.collectFlat(
                                meshData.vertexBuffer(), meshData.drawState().vertexCount(), batchTex,
                                net.vulkanmod.vulkan.rt.RtEntities.PARTICLE_FLAG
                                        | net.vulkanmod.vulkan.rt.RtEntities.EMISSIVE_FLAG);
                    } catch (Throwable ignored) {}
                }
                if (isMap) {
                    try {
                        net.vulkanmod.vulkan.rt.RtEntities.collectFlat(
                                meshData.vertexBuffer(), meshData.drawState().vertexCount(), batchTex, 0);
                    } catch (Throwable ignored) {}
                }
            }
            // === /RT PATCH ===

            Renderer.getDrawer().draw(meshData.vertexBuffer(), meshData.indexBuffer(), meshData.drawState().mode(), meshData.drawState().format(), meshData.drawState().vertexCount());
        }

        if (meshData != null) {
            meshData.close();
        }

        if (consumer != null) {
            matrix4fStack.popMatrix();
        }

    }
}
