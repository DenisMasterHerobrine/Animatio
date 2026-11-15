package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.gpu.GpuRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;

/**
 * Chooses how a prepared instance is drawn: batched GPU skinning when available, otherwise the CPU path (batched by
 * render type during world rendering, immediate everywhere else).
 */
public final class ModelRenderer {
    /** Where the draw happens; only world rendering is batched. */
    public enum Context { ENTITY, BLOCK_ENTITY, OTHER }

    private ModelRenderer() {
    }

    public static void render(GltfModel model, AnimatedInstance instance, PoseStack poseStack, MultiBufferSource buffers,
                              int light, int overlay, int tint, Context context) {
        if (context != Context.OTHER && GpuRenderer.tryEnqueue(model, instance, poseStack.last(), buffers, light, overlay, tint, context)) {
            return;
        }
        renderCpu(model, instance, poseStack.last(), buffers, light, overlay, tint, null, context);
    }

    /** CPU path: deferred into the phase's {@link CpuBatcher} when possible, else written immediately. */
    public static void renderCpu(GltfModel model, AnimatedInstance instance, PoseStack.Pose pose, MultiBufferSource buffers,
                                 int light, int overlay, int tint, CpuSkinner.@Nullable DrawnElsewhere drawnElsewhere, Context context) {
        CpuBatcher batcher = FramePipeline.cpuBatcher(context, buffers);
        if (batcher != null) batcher.add(model, instance, pose, light, overlay, tint, drawnElsewhere);
        else CpuSkinner.render(model, instance, pose, buffers, light, overlay, tint, drawnElsewhere);
    }
}
