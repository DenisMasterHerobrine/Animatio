package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import net.minecraft.util.FastColor;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;

/**
 * CPU skinning primitives. Pure functions on caller-provided arrays and memory, safe to run on any thread.
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
     * Linear-blend-skin every vertex of {@code mesh} with matrices {@code m}, writing positions and unit normals
     * (3 floats per vertex) starting at {@code offset} in {@code outP} / {@code outN}.
     */
    public static void skin(MeshData mesh, float[] m, float[] normalScale, float[] outP, float[] outN, int offset) {
        float[] pos = mesh.positions(), nrm = mesh.normals(), w = mesh.weights();
        int[] j = mesh.joints();
        int n = mesh.vertexCount();
        for (int v = 0, o = offset; v < n; v++, o += 3) {
            float px = pos[v * 3], py = pos[v * 3 + 1], pz = pos[v * 3 + 2];
            float nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
            float ox, oy, oz, qx, qy, qz;
            int w4 = v * 4;
            float scale;
            if (w[w4 + 1] <= 0f) {
                // rigid vertex: one matrix, weight 1 (box models and most props)
                int k = j[w4];
                int b = k * 16;
                ox = m[b] * px + m[b + 4] * py + m[b + 8] * pz + m[b + 12];
                oy = m[b + 1] * px + m[b + 5] * py + m[b + 9] * pz + m[b + 13];
                oz = m[b + 2] * px + m[b + 6] * py + m[b + 10] * pz + m[b + 14];
                qx = m[b] * nx + m[b + 4] * ny + m[b + 8] * nz;
                qy = m[b + 1] * nx + m[b + 5] * ny + m[b + 9] * nz;
                qz = m[b + 2] * nx + m[b + 6] * ny + m[b + 10] * nz;
                scale = normalScale[k];
            } else {
                ox = oy = oz = qx = qy = qz = 0f;
                for (int i = 0; i < 4; i++) {
                    float wk = w[w4 + i];
                    if (wk <= 0f) continue;
                    int b = j[w4 + i] * 16;
                    ox += wk * (m[b] * px + m[b + 4] * py + m[b + 8] * pz + m[b + 12]);
                    oy += wk * (m[b + 1] * px + m[b + 5] * py + m[b + 9] * pz + m[b + 13]);
                    oz += wk * (m[b + 2] * px + m[b + 6] * py + m[b + 10] * pz + m[b + 14]);
                    qx += wk * (m[b] * nx + m[b + 4] * ny + m[b + 8] * nz);
                    qy += wk * (m[b + 1] * nx + m[b + 5] * ny + m[b + 9] * nz);
                    qz += wk * (m[b + 2] * nx + m[b + 6] * ny + m[b + 10] * nz);
                }
                scale = Float.NaN; // blended matrices: normalise exactly
            }
            if (scale != scale) { // NaN
                float len2 = qx * qx + qy * qy + qz * qz;
                scale = len2 > 1e-24f ? (float) (1.0 / Math.sqrt(len2)) : 1f;
            }
            outP[o] = ox;
            outP[o + 1] = oy;
            outP[o + 2] = oz;
            outN[o] = qx * scale;
            outN[o + 1] = qy * scale;
            outN[o + 2] = qz * scale;
        }
    }

    /** Write a mesh's quads from skinned vertices at {@code offset} through a vertex consumer. */
    public static void emitQuads(int[] quads, MeshData mesh, float[] p, float[] n, int offset, VertexConsumer vc,
                                 int color, int overlay, int light) {
        float[] uv = mesh.uvs();
        float[] colors = mesh.colors();
        for (int q = 0; q < quads.length; q++) {
            int v = quads[q];
            int o = offset + v * 3;
            int col = colors == null ? color : MaterialResolver.multiply(color, FastColor.ARGB32.colorFromFloat(
                    colors[v * 4 + 3], colors[v * 4], colors[v * 4 + 1], colors[v * 4 + 2]));
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
        float[] colors = mesh.colors();
        int abgr = FastColor.ABGR32.fromArgb32(argb);
        long a = address;
        for (int q = 0; q < quads.length; q++, a += ENTITY_VERTEX_SIZE) {
            int v = quads[q];
            int o = offset + v * 3;
            MemoryUtil.memPutFloat(a, p[o]);
            MemoryUtil.memPutFloat(a + 4, p[o + 1]);
            MemoryUtil.memPutFloat(a + 8, p[o + 2]);
            MemoryUtil.memPutInt(a + 12, colors == null ? abgr : FastColor.ABGR32.fromArgb32(MaterialResolver.multiply(argb,
                    FastColor.ARGB32.colorFromFloat(colors[v * 4 + 3], colors[v * 4], colors[v * 4 + 1], colors[v * 4 + 2]))));
            MemoryUtil.memPutFloat(a + 16, uv[v * 2]);
            MemoryUtil.memPutFloat(a + 20, uv[v * 2 + 1]);
            MemoryUtil.memPutInt(a + 24, overlay);
            MemoryUtil.memPutInt(a + 28, light);
            MemoryUtil.memPutByte(a + 32, normalByte(n[o]));
            MemoryUtil.memPutByte(a + 33, normalByte(n[o + 1]));
            MemoryUtil.memPutByte(a + 34, normalByte(n[o + 2]));
            MemoryUtil.memPutByte(a + 35, (byte) 0);
        }
    }

    private static byte normalByte(float f) {
        return (byte) ((int) (Math.max(-1f, Math.min(1f, f)) * 127f) & 255);
    }
}
