package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;

/**
 * Immediate CPU skinning into vanilla buffers, inside the caller's render call. Used where geometry must be written
 * while the object renders: with shader packs (Iris tags vertices with the entity being drawn and renders shadow
 * passes separately), for glowing outlines, in GUIs and for items. During normal world rendering the
 * {@link CpuBatcher} is used instead.
 * <p>
 * Render thread only (static scratch buffers).
 */
public final class CpuSkinner {
    private static float[] positions = new float[0];
    private static float[] normals = new float[0];
    private static float[] finals = new float[0];
    private static float[] normalScales = new float[0];
    private static final float[] POSE = new float[16];

    private CpuSkinner() {
    }

    /** Decides which parts' base layer another path (the GPU) already draws. */
    @FunctionalInterface
    public interface DrawnElsewhere {
        boolean test(GltfModel.Part part, int color);
    }

    public static void render(GltfModel model, AnimatedInstance instance, PoseStack.Pose pose, MultiBufferSource buffers,
                              int light, int overlay, int tint) {
        render(model, instance, pose, buffers, light, overlay, tint, null);
    }

    /**
     * @param drawnElsewhere parts whose base layer is skipped here (their emissive layers are still drawn), or {@code null}
     */
    public static void render(GltfModel model, AnimatedInstance instance, PoseStack.Pose pose, MultiBufferSource buffers,
                              int light, int overlay, int tint, @Nullable DrawnElsewhere drawnElsewhere) {
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        float[] palette = instance.palette();
        int entries = model.paletteSize();
        if (palette.length < entries * 16) return;
        if (finals.length < entries * 16) {
            finals = new float[entries * 16];
            normalScales = new float[entries];
        }
        pose.pose().get(POSE);
        SkinKernel.finals(POSE, 0, palette, entries, finals, normalScales);
        int maxVerts = model.maxVertices();
        if (positions.length < maxVerts * 3) {
            positions = new float[maxVerts * 3];
            normals = new float[maxVerts * 3];
        }

        for (GltfModel.Part part : model.parts()) {
            MaterialOverride override = instance.override(part.materialName());
            int color = MaterialResolver.color(part, override, tint);
            RenderType type = MaterialResolver.renderType(part, override, color);
            if (type == null) continue;
            boolean base = drawnElsewhere == null || !drawnElsewhere.test(part, color);
            if (!base && part.emissiveTexture() == null) continue;
            MeshData mesh = part.mesh();
            SkinKernel.skin(mesh, finals, normalScales, positions, normals, 0);
            if (base) {
                SkinKernel.emitQuads(part.quads(), mesh, positions, normals, 0, buffers.getBuffer(type), color, overlay,
                        MaterialResolver.light(part, override, light));
            }
            if (part.emissiveTexture() != null) {
                SkinKernel.emitQuads(part.quads(), mesh, positions, normals, 0, buffers.getBuffer(RenderType.eyes(part.emissiveTexture())),
                        0xFFFFFFFF, overlay, LightTexture.FULL_BRIGHT);
            }
        }
        if (RenderStats.ENABLED) {
            RenderStats.cpuInstances++;
            RenderStats.cpuEmitNanos += System.nanoTime() - t0;
        }
    }
}
