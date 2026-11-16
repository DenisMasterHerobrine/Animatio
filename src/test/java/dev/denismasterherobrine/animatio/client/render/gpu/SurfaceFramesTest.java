package dev.denismasterherobrine.animatio.client.render.gpu;

import dev.denismasterherobrine.animatio.client.model.QuadPacker;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceFramesTest {
    private static final int F = SurfaceFrames.FLOATS_PER_VERTEX;

    private static MeshData mesh(float[] positions, float[] normals, float[] uvs, int[] indices) {
        int n = positions.length / 3;
        int[] joints = new int[n * 4];
        float[] weights = new float[n * 4];
        for (int v = 0; v < n; v++) weights[v * 4] = 1f;
        return new MeshData("test", -1, n, positions, normals, uvs, null, joints, weights, indices);
    }

    private static float[] repeat(float[] v, int times) {
        float[] out = new float[v.length * times];
        for (int i = 0; i < times; i++) System.arraycopy(v, 0, out, i * v.length, v.length);
        return out;
    }

    /** A unit quad facing +Z, U along +X and V along +Y (in the texture's own orientation). */
    private static MeshData quad(float[] uvs) {
        float[] p = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        return mesh(p, repeat(new float[]{0, 0, 1}, 4), uvs, new int[]{0, 1, 2, 2, 3, 0});
    }

    @Test
    void tangentFollowsUWithIrisHandedness() {
        MeshData m = quad(new float[]{0, 0, 1, 0, 1, 1, 0, 1});
        float[] f = SurfaceFrames.compute(m, QuadPacker.pack(m.indices()));
        for (int v = 0; v < 4; v++) {
            assertEquals(1f, f[v * F], 1e-6f);
            assertEquals(0f, f[v * F + 1], 1e-6f);
            assertEquals(0f, f[v * F + 2], 1e-6f);
            // V runs along +Y, tangent x normal = X x Z = -Y: Iris stores -1
            assertEquals(-1f, f[v * F + 3]);
            assertEquals(0.5f, f[v * F + 4], 1e-6f);
            assertEquals(0.5f, f[v * F + 5], 1e-6f);
        }
    }

    @Test
    void mirroredUFlipsTangentAndKeepsHandednessConvention() {
        MeshData m = quad(new float[]{1, 0, 0, 0, 0, 1, 1, 1});
        float[] f = SurfaceFrames.compute(m, QuadPacker.pack(m.indices()));
        for (int v = 0; v < 4; v++) {
            assertEquals(-1f, f[v * F], 1e-6f);
            assertEquals(1f, f[v * F + 3]); // mirrored mapping: the other handedness
        }
    }

    @Test
    void matchesIrisPerTriangleTangentOnFlatTriangle() {
        float[] p = {0.2f, -0.1f, 0.3f, 1.4f, 0.2f, -0.5f, 0.1f, 1.1f, 0.7f};
        float[] uv = {0.1f, 0.9f, 0.8f, 0.7f, 0.3f, 0.1f};
        // face normal, as every vertex of a flat triangle has
        float e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2], e2x = p[6] - p[0], e2y = p[7] - p[1], e2z = p[8] - p[2];
        float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= nl;
        ny /= nl;
        nz /= nl;
        MeshData m = mesh(p, repeat(new float[]{nx, ny, nz}, 3), uv, new int[]{0, 1, 2});
        float[] f = SurfaceFrames.compute(m, QuadPacker.pack(m.indices()));
        float[] iris = irisTangentSmooth(nx, ny, nz, p, uv);
        for (int v = 0; v < 3; v++) {
            for (int c = 0; c < 3; c++) assertEquals(iris[c], f[v * F + c], 1e-5f);
            assertEquals(iris[3], f[v * F + 3]);
        }
    }

    @Test
    void loneTriangleMidCountsRepeatedCornerTwice() {
        float[] p = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        float[] uv = {0, 0, 1, 0, 0, 1};
        MeshData m = mesh(p, repeat(new float[]{0, 0, 1}, 3), uv, new int[]{0, 1, 2});
        int[] quads = QuadPacker.pack(m.indices());
        float[] f = SurfaceFrames.compute(m, quads);
        // drawn as (a, b, c, c): Iris averages the four corners
        float mu = (uv[quads[0] * 2] + uv[quads[1] * 2] + uv[quads[2] * 2] + uv[quads[3] * 2]) / 4f;
        float mv = (uv[quads[0] * 2 + 1] + uv[quads[1] * 2 + 1] + uv[quads[2] * 2 + 1] + uv[quads[3] * 2 + 1]) / 4f;
        for (int v = 0; v < 3; v++) {
            assertEquals(mu, f[v * F + 4], 1e-6f);
            assertEquals(mv, f[v * F + 5], 1e-6f);
        }
    }

    @Test
    void sharedVertexAveragesTheQuadsItBelongsTo() {
        // two quads side by side sharing the edge x = 1
        float[] p = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 2, 0, 0, 2, 1, 0};
        float[] uv = {0, 0, 0.5f, 0, 0.5f, 1, 0, 1, 1, 0, 1, 1};
        MeshData m = mesh(p, repeat(new float[]{0, 0, 1}, 6), uv, new int[]{0, 1, 2, 2, 3, 0, 1, 4, 5, 5, 2, 1});
        float[] f = SurfaceFrames.compute(m, QuadPacker.pack(m.indices()));
        assertEquals(0.25f, f[0 * F + 4], 1e-6f); // left quad only
        assertEquals(0.75f, f[4 * F + 4], 1e-6f); // right quad only
        assertEquals(0.5f, f[1 * F + 4], 1e-6f);  // shared corner: mean of both centres
        assertEquals(0.5f, f[1 * F + 5], 1e-6f);
    }

    @Test
    void missingUvMappingStillGivesUnitTangentInPlane() {
        MeshData m = quad(new float[8]);
        float[] f = SurfaceFrames.compute(m, QuadPacker.pack(m.indices()));
        for (int v = 0; v < 4; v++) {
            float tx = f[v * F], ty = f[v * F + 1], tz = f[v * F + 2];
            assertEquals(1f, tx * tx + ty * ty + tz * tz, 1e-5f);
            assertEquals(0f, tz, 1e-6f); // perpendicular to the +Z normal
        }
    }

    /** Iris' NormalHelper.computeTangentSmooth for one triangle, unpacked (tangent xyz, w). */
    private static float[] irisTangentSmooth(float nx, float ny, float nz, float[] p, float[] uv) {
        float[] x = new float[3], y = new float[3], z = new float[3];
        for (int i = 0; i < 3; i++) {
            float d = p[i * 3] * nx + p[i * 3 + 1] * ny + p[i * 3 + 2] * nz;
            x[i] = p[i * 3] - d * nx;
            y[i] = p[i * 3 + 1] - d * ny;
            z[i] = p[i * 3 + 2] - d * nz;
        }
        float e1x = x[1] - x[0], e1y = y[1] - y[0], e1z = z[1] - z[0];
        float e2x = x[2] - x[0], e2y = y[2] - y[0], e2z = z[2] - z[0];
        float du1 = uv[2] - uv[0], dv1 = uv[3] - uv[1], du2 = uv[4] - uv[0], dv2 = uv[5] - uv[1];
        float denom = du1 * dv2 - du2 * dv1;
        float f = denom == 0 ? 1f : 1f / denom;
        float tx = f * (dv2 * e1x - dv1 * e2x), ty = f * (dv2 * e1y - dv1 * e2y), tz = f * (dv2 * e1z - dv1 * e2z);
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        float bx = f * (-du2 * e1x + du1 * e2x), by = f * (-du2 * e1y + du1 * e2y), bz = f * (-du2 * e1z + du1 * e2z);
        float px = ty * nz - tz * ny, py = tz * nx - tx * nz, pz = tx * ny - ty * nx;
        float w = bx * px + by * py + bz * pz < 0 ? -1f : 1f;
        return new float[]{tx, ty, tz, w};
    }
}
