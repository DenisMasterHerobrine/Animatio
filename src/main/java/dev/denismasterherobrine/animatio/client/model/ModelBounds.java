package dev.denismasterherobrine.animatio.client.model;

import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.model.Pose;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.gltf.ModelData;

/**
 * Conservative model-space bounds over the rest pose and every clip, for frustum culling.
 * <p>
 * Each palette entry's vertices are boxed in bind space; for a sampled pose the boxes are transformed by the
 * palette matrices. Linear blend skinning keeps every vertex inside the union of its joints' transformed boxes,
 * so the union over all samples bounds the animated mesh (between samples a small margin is added).
 */
public final class ModelBounds {
    private static final float SAMPLES_PER_SECOND = 10f;
    private static final int MAX_SAMPLES_PER_CLIP = 120;
    private static final float MARGIN = 0.1f;

    private ModelBounds() {
    }

    public static float[] compute(ModelData model) {
        float[] out = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        Skeleton sk = model.skeleton();
        Pose pose = new Pose(sk);
        float[] globals = new float[sk.size() * 16];
        float[] palette = new float[model.paletteSize() * 16];

        pose.setRest(sk);
        include(model, sk, pose, globals, palette, out);
        for (Clip clip : model.clips()) {
            int samples = Math.min(MAX_SAMPLES_PER_CLIP, Math.max(2, (int) Math.ceil(clip.duration() * SAMPLES_PER_SECOND) + 1));
            for (int i = 0; i < samples; i++) {
                pose.setRest(sk);
                clip.sample(clip.duration() * i / (samples - 1), pose);
                include(model, sk, pose, globals, palette, out);
            }
        }
        if (out[0] > out[3]) return new float[]{-0.5f, 0f, -0.5f, 0.5f, 1f, 0.5f}; // no geometry
        for (int c = 0; c < 3; c++) {
            out[c] -= MARGIN;
            out[c + 3] += MARGIN;
        }
        return out;
    }

    private static void include(ModelData model, Skeleton sk, Pose pose, float[] globals, float[] palette, float[] out) {
        Mat4.computeGlobals(sk, pose, globals);
        model.computePalette(globals, palette);
        float[] jb = model.jointBounds();
        float[] p = new float[3];
        for (int j = 0; j < model.paletteSize(); j++) {
            int o = j * 6;
            if (jb[o] > jb[o + 3]) continue;
            for (int corner = 0; corner < 8; corner++) {
                float x = (corner & 1) == 0 ? jb[o] : jb[o + 3];
                float y = (corner & 2) == 0 ? jb[o + 1] : jb[o + 4];
                float z = (corner & 4) == 0 ? jb[o + 2] : jb[o + 5];
                Mat4.transformPoint(palette, j * 16, x, y, z, p, 0);
                for (int c = 0; c < 3; c++) {
                    out[c] = Math.min(out[c], p[c]);
                    out[c + 3] = Math.max(out[c + 3], p[c]);
                }
            }
        }
    }
}
