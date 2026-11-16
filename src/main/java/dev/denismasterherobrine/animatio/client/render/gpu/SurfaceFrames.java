package dev.denismasterherobrine.animatio.client.render.gpu;

import dev.denismasterherobrine.animatio.gltf.MeshData;

/**
 * Per-vertex data shader packs read besides the vanilla entity attributes: a tangent with handedness
 * ({@code at_tangent}) and the centre of the primitive's texture coordinates ({@code mc_midTexCoord}).
 * <p>
 * Iris fills these per primitive while vertices are written on the CPU; the GPU path computes them once per mesh in
 * the model's bind pose and skins the tangent like the normal. Conventions follow Iris so a pack sees the same data
 * from both paths:
 * <ul>
 *     <li>the tangent points along increasing U; {@code w} is {@code +1} when increasing V runs along
 *     {@code tangent × normal}, else {@code -1};</li>
 *     <li>the mid texture coordinate is the average UV of a quad's four corners (a lone triangle drawn as the
 *     degenerate quad {@code (a,b,c,c)} counts {@code c} twice). A vertex shared by several quads gets the average
 *     of their centres; box models share none, so they get exactly what Iris writes.</li>
 * </ul>
 */
public final class SurfaceFrames {
    /** Floats per vertex: tangent x, y, z, w, then mid U, mid V and two unused (two RGBA32F texels). */
    public static final int FLOATS_PER_VERTEX = 8;

    private SurfaceFrames() {
    }

    /**
     * @param quads the mesh's quads, 4 indices each (see {@code QuadPacker})
     * @return {@link #FLOATS_PER_VERTEX} floats per vertex
     */
    public static float[] compute(MeshData mesh, int[] quads) {
        int n = mesh.vertexCount();
        float[] out = new float[n * FLOATS_PER_VERTEX];
        tangents(mesh, out);
        midTexCoords(mesh, quads, out);
        return out;
    }

    private static void tangents(MeshData mesh, float[] out) {
        int n = mesh.vertexCount();
        float[] p = mesh.positions(), nrm = mesh.normals(), uv = mesh.uvs();
        int[] idx = mesh.indices();
        float[] t = new float[n * 3], b = new float[n * 3];
        for (int i = 0; i + 2 < idx.length; i += 3) {
            int i0 = idx[i], i1 = idx[i + 1], i2 = idx[i + 2];
            float e1x = p[i1 * 3] - p[i0 * 3], e1y = p[i1 * 3 + 1] - p[i0 * 3 + 1], e1z = p[i1 * 3 + 2] - p[i0 * 3 + 2];
            float e2x = p[i2 * 3] - p[i0 * 3], e2y = p[i2 * 3 + 1] - p[i0 * 3 + 1], e2z = p[i2 * 3 + 2] - p[i0 * 3 + 2];
            float du1 = uv[i1 * 2] - uv[i0 * 2], dv1 = uv[i1 * 2 + 1] - uv[i0 * 2 + 1];
            float du2 = uv[i2 * 2] - uv[i0 * 2], dv2 = uv[i2 * 2 + 1] - uv[i0 * 2 + 1];
            float det = du1 * dv2 - du2 * dv1;
            if (det == 0f || !Float.isFinite(det)) continue; // no UV mapping to derive a direction from
            float f = 1f / det;
            float tx = f * (dv2 * e1x - dv1 * e2x), ty = f * (dv2 * e1y - dv1 * e2y), tz = f * (dv2 * e1z - dv1 * e2z);
            float bx = f * (du1 * e2x - du2 * e1x), by = f * (du1 * e2y - du2 * e1y), bz = f * (du1 * e2z - du2 * e1z);
            // area-weighted unit directions, so the UV scale of a triangle does not decide how much it counts
            float cx = e1y * e2z - e1z * e2y, cy = e1z * e2x - e1x * e2z, cz = e1x * e2y - e1y * e2x;
            float area = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz), bl = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            if (area == 0f || tl == 0f || bl == 0f) continue;
            float ts = area / tl, bs = area / bl;
            for (int v : new int[]{i0, i1, i2}) {
                t[v * 3] += tx * ts;
                t[v * 3 + 1] += ty * ts;
                t[v * 3 + 2] += tz * ts;
                b[v * 3] += bx * bs;
                b[v * 3 + 1] += by * bs;
                b[v * 3 + 2] += bz * bs;
            }
        }
        for (int v = 0; v < n; v++) {
            float nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl > 1e-12f) {
                nx /= nl;
                ny /= nl;
                nz /= nl;
            } else {
                nx = 0f;
                ny = 1f;
                nz = 0f;
            }
            // Gram-Schmidt: the tangent lies in the surface plane of the (smooth) normal
            float tx = t[v * 3], ty = t[v * 3 + 1], tz = t[v * 3 + 2];
            float d = tx * nx + ty * ny + tz * nz;
            tx -= nx * d;
            ty -= ny * d;
            tz -= nz * d;
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl > 1e-6f) {
                tx /= tl;
                ty /= tl;
                tz /= tl;
            } else { // no usable UV direction: any unit vector perpendicular to the normal
                if (Math.abs(nx) < 0.9f) {
                    tx = 0f;
                    ty = nz;
                    tz = -ny; // normal × (1, 0, 0)
                } else {
                    tx = -nz;
                    ty = 0f;
                    tz = nx; // normal × (0, 1, 0)
                }
                float l = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                tx /= l;
                ty /= l;
                tz /= l;
            }
            // handedness as Iris computes it: bitangent against tangent × normal
            float px = ty * nz - tz * ny, py = tz * nx - tx * nz, pz = tx * ny - ty * nx;
            float w = b[v * 3] * px + b[v * 3 + 1] * py + b[v * 3 + 2] * pz < 0f ? -1f : 1f;
            int o = v * FLOATS_PER_VERTEX;
            out[o] = tx;
            out[o + 1] = ty;
            out[o + 2] = tz;
            out[o + 3] = w;
        }
    }

    private static void midTexCoords(MeshData mesh, int[] quads, float[] out) {
        int n = mesh.vertexCount();
        float[] uv = mesh.uvs();
        float[] sum = new float[n * 2];
        int[] count = new int[n];
        for (int q = 0; q + 3 < quads.length; q += 4) {
            int a = quads[q], b = quads[q + 1], c = quads[q + 2], d = quads[q + 3];
            float mu = (uv[a * 2] + uv[b * 2] + uv[c * 2] + uv[d * 2]) * 0.25f;
            float mv = (uv[a * 2 + 1] + uv[b * 2 + 1] + uv[c * 2 + 1] + uv[d * 2 + 1]) * 0.25f;
            for (int k = 0; k < 4; k++) {
                int v = quads[q + k];
                boolean seen = false; // count each corner once per quad (degenerate quads repeat one)
                for (int j = 0; j < k; j++) seen |= quads[q + j] == v;
                if (seen) continue;
                sum[v * 2] += mu;
                sum[v * 2 + 1] += mv;
                count[v]++;
            }
        }
        for (int v = 0; v < n; v++) {
            int o = v * FLOATS_PER_VERTEX + 4;
            if (count[v] == 0) {
                out[o] = uv[v * 2];
                out[o + 1] = uv[v * 2 + 1];
            } else {
                out[o] = sum[v * 2] / count[v];
                out[o + 1] = sum[v * 2 + 1] / count[v];
            }
        }
    }
}
