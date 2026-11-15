package dev.denismasterherobrine.animatio.client.model;

import java.util.Arrays;

/**
 * Converts a triangle list into Minecraft's quad list. Two consecutive triangles that share an edge with matching
 * winding, {@code (p,q,r)} and {@code (r,s,p)} in any rotation, become one quad {@code (p,q,r,s)}: the game draws a
 * quad as exactly those two triangles. Other triangles become degenerate quads {@code (a,b,c,c)}.
 * <p>
 * Meshes made of quads (box models, most Blender exports) need half the vertices this way.
 */
public final class QuadPacker {
    private QuadPacker() {
    }

    /** @return 4 indices per quad */
    public static int[] pack(int[] triangles) {
        int tris = triangles.length / 3;
        int[] out = new int[tris * 4];
        int n = 0;
        int t = 0;
        while (t < tris) {
            int a = triangles[t * 3], b = triangles[t * 3 + 1], c = triangles[t * 3 + 2];
            if (t + 1 < tris) {
                int d = triangles[t * 3 + 3], e = triangles[t * 3 + 4], f = triangles[t * 3 + 5];
                int[] quad = merge(a, b, c, d, e, f);
                if (quad != null) {
                    System.arraycopy(quad, 0, out, n, 4);
                    n += 4;
                    t += 2;
                    continue;
                }
            }
            out[n++] = a;
            out[n++] = b;
            out[n++] = c;
            out[n++] = c;
            t++;
        }
        return Arrays.copyOf(out, n);
    }

    private static int[] merge(int a, int b, int c, int d, int e, int f) {
        int[][] first = {{a, b, c}, {b, c, a}, {c, a, b}};
        int[][] second = {{d, e, f}, {e, f, d}, {f, d, e}};
        for (int[] t1 : first) {
            int p = t1[0], q = t1[1], r = t1[2];
            for (int[] t2 : second) {
                // second triangle must be (r, s, p): it shares edge p-r, walked the other way
                if (t2[0] == r && t2[2] == p && t2[1] != q) return new int[]{p, q, r, t2[1]};
            }
        }
        return null;
    }
}
