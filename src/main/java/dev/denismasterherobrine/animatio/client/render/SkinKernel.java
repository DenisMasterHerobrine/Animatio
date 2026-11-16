package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;

/**
 * CPU skinning primitives. Pure functions on caller-provided arrays and memory, safe to run on any thread.
 * <p>
 * The kernels walk a mesh's {@linkplain MeshData#spans() spans}: runs of vertices bound rigidly to one joint are
 * transformed with that joint's matrix held in locals, so box models and props pay one matrix load per run; only
 * blended vertices read weights.
 */
public final class SkinKernel {
    /** Bytes per vertex of {@code DefaultVertexFormat.NEW_ENTITY}. */
    public static final int ENTITY_VERTEX_SIZE = 36;
    /** The raw writer assumes little-endian memory, like every platform Minecraft ships for. */
    public static final boolean RAW_WRITES = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private SkinKernel() {
    }

    /**
     * {@code out[k] = pose × palette[k]} for every palette entry (16 floats each), plus for each entry the factor
     * that turns a transformed unit normal back into a unit normal ({@code NaN} if the matrix scales unevenly).
     */
    public static void finals(float[] pose, int poseOffset, float[] palette, int entries, float[] out, float[] normalScale) {
        for (int k = 0; k < entries; k++) {
            int o = k * 16;
            Mat4.mulAffine(pose, poseOffset, palette, o, out, o);
            float a = out[o] * out[o] + out[o + 1] * out[o + 1] + out[o + 2] * out[o + 2];
            float b = out[o + 4] * out[o + 4] + out[o + 5] * out[o + 5] + out[o + 6] * out[o + 6];
            float c = out[o + 8] * out[o + 8] + out[o + 9] * out[o + 9] + out[o + 10] * out[o + 10];
            float max = Math.max(a, Math.max(b, c)), min = Math.min(a, Math.min(b, c));
            normalScale[k] = min > 1e-20f && max - min <= 1e-3f * max ? (float) (1.0 / Math.sqrt(a)) : Float.NaN;
        }
    }

    /**
     * Apply a mesh's morph targets: writes base positions and normals plus the weighted deltas into {@code outP} /
     * {@code outN} (3 floats per vertex).
     *
     * @param weights a pose's morph weights ({@code Pose.m}); the mesh reads its slots from its weight offset
     * @return false (writing nothing) if the mesh has no targets or every weight is zero: skin the base arrays
     */
    public static boolean morph(MeshData mesh, @Nullable float[] weights, float[] outP, float[] outN) {
        int targets = mesh.morphTargetCount();
        if (targets == 0 || weights == null) return false;
        int wo = mesh.morphWeightOffset();
        if (wo + targets > weights.length) return false;
        boolean any = false;
        for (int t = 0; t < targets && !any; t++) any = weights[wo + t] != 0f;
        if (!any) return false;
        int n = mesh.vertexCount() * 3;
        System.arraycopy(mesh.positions(), 0, outP, 0, n);
        System.arraycopy(mesh.normals(), 0, outN, 0, n);
        int[] start = mesh.morphStart(), vertex = mesh.morphVertex();
        float[] delta = mesh.morphDelta();
        for (int t = 0; t < targets; t++) {
            float w = weights[wo + t];
            if (w == 0f) continue;
            for (int e = start[t], end = start[t + 1]; e < end; e++) {
                int o = vertex[e] * 3, d = e * 6;
                outP[o] += delta[d] * w;
                outP[o + 1] += delta[d + 1] * w;
                outP[o + 2] += delta[d + 2] * w;
                outN[o] += delta[d + 3] * w;
                outN[o + 1] += delta[d + 4] * w;
                outN[o + 2] += delta[d + 5] * w;
            }
        }
        return true;
    }

    /**
     * Linear-blend-skin every vertex of {@code mesh} with matrices {@code m}, writing positions and unit normals
     * (3 floats per vertex) starting at {@code offset} in {@code outP} / {@code outN}.
     */
    public static void skin(MeshData mesh, float[] m, float[] normalScale, float[] outP, float[] outN, int offset) {
        skin(mesh, mesh.positions(), mesh.normals(), false, m, normalScale, outP, outN, offset);
    }

    /**
     * Skin from explicit source positions and normals (for example after {@link #morph}).
     *
     * @param exactNormals normalise every output normal exactly (source normals are not unit length)
     */
    public static void skin(MeshData mesh, float[] pos, float[] nrm, boolean exactNormals, float[] m, float[] normalScale,
                            float[] outP, float[] outN, int offset) {
        int[] spans = mesh.spans();
        float[] w = mesh.weights();
        int[] j = mesh.joints();
        for (int s = 0; s < spans.length; s += 3) {
            int start = spans[s], end = spans[s + 1], joint = spans[s + 2];
            if (joint >= 0) {
                int b = joint * 16;
                float m00 = m[b], m10 = m[b + 1], m20 = m[b + 2];
                float m01 = m[b + 4], m11 = m[b + 5], m21 = m[b + 6];
                float m02 = m[b + 8], m12 = m[b + 9], m22 = m[b + 10];
                float m03 = m[b + 12], m13 = m[b + 13], m23 = m[b + 14];
                float scale = exactNormals ? Float.NaN : normalScale[joint];
                for (int v = start, i = start * 3, o = offset + start * 3; v < end; v++, i += 3, o += 3) {
                    float px = pos[i], py = pos[i + 1], pz = pos[i + 2];
                    float nx = nrm[i], ny = nrm[i + 1], nz = nrm[i + 2];
                    outP[o] = m00 * px + m01 * py + m02 * pz + m03;
                    outP[o + 1] = m10 * px + m11 * py + m12 * pz + m13;
                    outP[o + 2] = m20 * px + m21 * py + m22 * pz + m23;
                    float qx = m00 * nx + m01 * ny + m02 * nz;
                    float qy = m10 * nx + m11 * ny + m12 * nz;
                    float qz = m20 * nx + m21 * ny + m22 * nz;
                    float k = scale;
                    if (k != k) { // NaN: normalise exactly
                        float len2 = qx * qx + qy * qy + qz * qz;
                        k = len2 > 1e-24f ? (float) (1.0 / Math.sqrt(len2)) : 1f;
                    }
                    outN[o] = qx * k;
                    outN[o + 1] = qy * k;
                    outN[o + 2] = qz * k;
                }
            } else {
                for (int v = start, i = start * 3, o = offset + start * 3; v < end; v++, i += 3, o += 3) {
                    float px = pos[i], py = pos[i + 1], pz = pos[i + 2];
                    float nx = nrm[i], ny = nrm[i + 1], nz = nrm[i + 2];
                    float ox = 0f, oy = 0f, oz = 0f, qx = 0f, qy = 0f, qz = 0f;
                    int w4 = v * 4;
                    for (int c = 0; c < 4; c++) {
                        float wk = w[w4 + c];
                        if (wk <= 0f) continue;
                        int b = j[w4 + c] * 16;
                        ox += wk * (m[b] * px + m[b + 4] * py + m[b + 8] * pz + m[b + 12]);
                        oy += wk * (m[b + 1] * px + m[b + 5] * py + m[b + 9] * pz + m[b + 13]);
                        oz += wk * (m[b + 2] * px + m[b + 6] * py + m[b + 10] * pz + m[b + 14]);
                        qx += wk * (m[b] * nx + m[b + 4] * ny + m[b + 8] * nz);
                        qy += wk * (m[b + 1] * nx + m[b + 5] * ny + m[b + 9] * nz);
                        qz += wk * (m[b + 2] * nx + m[b + 6] * ny + m[b + 10] * nz);
                    }
                    float len2 = qx * qx + qy * qy + qz * qz;
                    float k = len2 > 1e-24f ? (float) (1.0 / Math.sqrt(len2)) : 1f;
                    outP[o] = ox;
                    outP[o + 1] = oy;
                    outP[o + 2] = oz;
                    outN[o] = qx * k;
                    outN[o + 1] = qy * k;
                    outN[o + 2] = qz * k;
                }
            }
        }
    }

    /** Write a mesh's quads from skinned vertices at {@code offset} through a vertex consumer. */
    public static void emitQuads(int[] quads, MeshData mesh, float[] p, float[] n, int offset, VertexConsumer vc,
                                 int color, int overlay, int light) {
        float[] uv = mesh.uvs();
        int[] colors = mesh.packedColors();
        for (int q = 0; q < quads.length; q++) {
            int v = quads[q];
            int o = offset + v * 3;
            int col = colors == null ? color : MaterialResolver.multiply(color, colors[v]);
            vc.addVertex(p[o], p[o + 1], p[o + 2], col, uv[v * 2], uv[v * 2 + 1], overlay, light, n[o], n[o + 1], n[o + 2]);
        }
    }

    /**
     * Write a mesh's quads in the {@code NEW_ENTITY} layout directly to memory at {@code address}
     * ({@code quads.length × 36} bytes). Little-endian only (see {@link #RAW_WRITES}).
     */
    public static void emitQuadsRaw(int[] quads, MeshData mesh, float[] p, float[] n, int offset, long address,
                                    int argb, int overlay, int light) {
        float[] uv = mesh.uvs();
        int[] colors = mesh.packedColors();
        int abgr = toAbgr(argb);
        long a = address;
        for (int q = 0; q < quads.length; q++, a += ENTITY_VERTEX_SIZE) {
            int v = quads[q];
            int o = offset + v * 3;
            MemoryUtil.memPutFloat(a, p[o]);
            MemoryUtil.memPutFloat(a + 4, p[o + 1]);
            MemoryUtil.memPutFloat(a + 8, p[o + 2]);
            MemoryUtil.memPutInt(a + 12, colors == null ? abgr : toAbgr(MaterialResolver.multiply(argb, colors[v])));
            MemoryUtil.memPutFloat(a + 16, uv[v * 2]);
            MemoryUtil.memPutFloat(a + 20, uv[v * 2 + 1]);
            MemoryUtil.memPutInt(a + 24, overlay);
            MemoryUtil.memPutInt(a + 28, light);
            MemoryUtil.memPutInt(a + 32, (normalByte(n[o]) & 0xFF) | (normalByte(n[o + 1]) & 0xFF) << 8 | (normalByte(n[o + 2]) & 0xFF) << 16);
        }
    }

    private static int toAbgr(int argb) {
        return argb & 0xFF00FF00 | (argb >> 16) & 0xFF | (argb & 0xFF) << 16;
    }

    private static byte normalByte(float f) {
        return (byte) ((int) (Math.max(-1f, Math.min(1f, f)) * 127f) & 255);
    }
}
