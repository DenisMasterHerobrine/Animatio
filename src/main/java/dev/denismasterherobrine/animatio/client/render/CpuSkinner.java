package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immediate CPU skinning into vanilla buffers, inside the caller's render call. Used where geometry must be written
 * while the object renders: with shader packs (Iris tags vertices with the entity being drawn and renders shadow
 * passes separately), for glowing outlines, in GUIs and for items. During normal world rendering the
 * {@link CpuBatcher} is used instead.
 * <p>
 * Render thread only (static scratch buffers).
 */
public final class CpuSkinner {
    private static final Scratch SCRATCH = new Scratch();
    private static final float[] POSE = new float[16];

    private CpuSkinner() {
    }

    /** Decides which parts' base layer another path (the GPU) already draws. */
    @FunctionalInterface
    public interface DrawnElsewhere {
        boolean test(GltfModel.Part part, int color);
    }

    /** Per-thread working memory of the skinning kernels. */
    static final class Scratch {
        float[] finals = new float[16 * 64];
        float[] normalScales = new float[64];
        float[] positions = new float[0];
        float[] normals = new float[0];
        float[] morphP = new float[0];
        float[] morphN = new float[0];

        void ensure(int entries, int vertices) {
            if (finals.length < entries * 16) {
                finals = new float[entries * 16];
                normalScales = new float[entries];
            }
            if (positions.length < vertices * 3) {
                positions = new float[vertices * 3];
                normals = new float[vertices * 3];
            }
        }

        /** Skin one mesh (applying morph targets first) into {@link #positions} / {@link #normals}. */
        void skin(MeshData mesh, @Nullable float[] morphWeights) {
            if (mesh.hasMorphs()) {
                int n = mesh.vertexCount() * 3;
                if (morphP.length < n) {
                    morphP = new float[n];
                    morphN = new float[n];
                }
                if (SkinKernel.morph(mesh, morphWeights, morphP, morphN)) {
                    SkinKernel.skin(mesh, morphP, morphN, true, finals, normalScales, positions, normals, 0);
                    return;
                }
            }
            SkinKernel.skin(mesh, finals, normalScales, positions, normals, 0);
        }
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
        Scratch s = SCRATCH;
        s.ensure(entries, model.maxVertices());
        pose.pose().get(POSE);
        SkinKernel.finals(POSE, 0, palette, entries, s.finals, s.normalScales);
        float[] morphWeights = instance.morphWeights();

        List<GltfModel.Part> parts = model.parts();
        for (int i = 0, size = parts.size(); i < size; i++) {
            GltfModel.Part part = parts.get(i);
            MaterialOverride override = instance.override(part.materialName());
            int color = MaterialResolver.color(part, override, tint);
            RenderType type = MaterialResolver.renderType(part, override, color, instance.skinTexture());
            if (type == null) continue;
            boolean base = drawnElsewhere == null || !drawnElsewhere.test(part, color);
            if (!base && part.emissiveTexture() == null) continue;
            MeshData mesh = part.mesh();
            s.skin(mesh, morphWeights);
            if (base) {
                SkinKernel.emitQuads(part.quads(), mesh, s.positions, s.normals, 0, buffers.getBuffer(type), color, overlay,
                        MaterialResolver.light(part, override, light));
            }
            if (part.emissiveTexture() != null) {
                SkinKernel.emitQuads(part.quads(), mesh, s.positions, s.normals, 0, buffers.getBuffer(RenderType.eyes(part.emissiveTexture())),
                        0xFFFFFFFF, overlay, LightTexture.FULL_BRIGHT);
            }
        }
        if (RenderStats.ENABLED) {
            RenderStats.cpuInstances++;
            RenderStats.cpuEmitNanos += System.nanoTime() - t0;
        }
    }
}
