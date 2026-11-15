package dev.denismasterherobrine.animatio.client.render.gpu;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.AnimatioClientConfig;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.CpuSkinner;
import dev.denismasterherobrine.animatio.client.render.FramePipeline;
import dev.denismasterherobrine.animatio.client.render.MaterialResolver;
import dev.denismasterherobrine.animatio.client.render.ModelRenderer;
import dev.denismasterherobrine.animatio.data.ModelDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.joml.Matrix4f;

/**
 * Batched, instanced GPU skinning for world rendering.
 * <p>
 * Instances drawn while entities (or block entities) render are queued and drawn together right after that phase.
 * Opaque and cutout materials go to the GPU; translucent materials, emissive texture layers, glowing outlines,
 * GUI rendering and shader packs use the CPU path ({@link CpuSkinner} / {@code CpuBatcher}), which writes into
 * vanilla buffers.
 */
public final class GpuRenderer {
    private static final InstanceBatcher ENTITIES = new InstanceBatcher();
    private static final InstanceBatcher BLOCK_ENTITIES = new InstanceBatcher();

    private GpuRenderer() {
    }

    /** True when the GPU path can be used right now. */
    public static boolean isActive() {
        return AnimatioClientConfig.gpuSkinning() && GpuShaders.defaultShader() != null && !IrisCompat.isShaderPackInUse();
    }

    /**
     * Queue an instance for the GPU path. Parts the GPU does not handle are drawn immediately on the CPU.
     *
     * @return true if handled (fully or partly); false means the caller should draw everything on the CPU
     */
    public static boolean tryEnqueue(GltfModel model, AnimatedInstance instance, PoseStack.Pose pose, MultiBufferSource buffers,
                                     int light, int overlay, int tint, ModelRenderer.Context context) {
        if (!FramePipeline.inPhase(context) || !isActive() || buffers instanceof OutlineBufferSource) return false;
        InstanceBatcher batcher = context == ModelRenderer.Context.ENTITY ? ENTITIES : BLOCK_ENTITIES;
        if (!batcher.hasRoom(model.paletteSize(), model.parts().size())) return false;
        GpuModelData gpu = GpuModelData.get(model);
        if (gpu == null) return false;

        int boneOffset = -1;
        boolean cpuParts = false;
        var parts = model.parts();
        for (int i = 0; i < parts.size(); i++) {
            GltfModel.Part part = parts.get(i);
            if (part.emissiveTexture() != null) cpuParts = true;
            MaterialOverride override = instance.override(part.materialName());
            if (override.hidden() || part.kind() == ModelDefinition.RenderTypeKind.INVISIBLE) continue;
            int color = MaterialResolver.color(part, override, tint);
            if (!onGpu(part, color)) {
                cpuParts = true;
                continue;
            }
            ResourceLocation shaderId = override.shader() != null ? override.shader() : part.shader();
            ShaderInstance shader = GpuShaders.get(shaderId);
            if (shader == null) {
                cpuParts = true;
                continue;
            }
            if (boneOffset < 0) boneOffset = batcher.addBones(instance.palette(), model.paletteSize());
            ResourceLocation texture = override.texture() != null ? override.texture() : part.texture();
            batcher.add(shader, model, gpu, i, texture, !part.doubleSided(), pose.pose(),
                    MaterialResolver.light(part, override, light), overlay, color, override.emissive(),
                    override.param0(), override.param1(), override.param2(), override.param3(), boneOffset);
        }
        if (cpuParts) ModelRenderer.renderCpu(model, instance, pose, buffers, light, overlay, tint, GpuRenderer::onGpu, context);
        return true;
    }

    /** Whether a part's base layer is drawn by the GPU path (translucent materials need vanilla sorting). */
    private static boolean onGpu(GltfModel.Part part, int color) {
        ModelDefinition.RenderTypeKind kind = part.kind();
        if (kind == ModelDefinition.RenderTypeKind.TRANSLUCENT) return false;
        if (kind == ModelDefinition.RenderTypeKind.EMISSIVE) return true;
        return FastColor.ARGB32.alpha(color) == 255;
    }

    /** Draw the instances queued during a phase. */
    public static void flush(ModelRenderer.Context context, Matrix4f modelView, Matrix4f projection) {
        (context == ModelRenderer.Context.ENTITY ? ENTITIES : BLOCK_ENTITIES).flush(modelView, projection);
    }

    public static void clear() {
        ENTITIES.clear();
        BLOCK_ENTITIES.clear();
    }

    /** "records/draw calls" of the last entity and block-entity flushes, for diagnostics. */
    public static String stats() {
        return "entities " + ENTITIES.lastRecords + " records / " + ENTITIES.lastDrawCalls + " draws, block entities "
                + BLOCK_ENTITIES.lastRecords + " records / " + BLOCK_ENTITIES.lastDrawCalls + " draws";
    }

    public static void onShadersReloaded() {
        InstanceBatcher.invalidateShaders();
    }
}
