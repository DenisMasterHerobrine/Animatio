package dev.denismasterherobrine.animatio.anim.math;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bowyer–Watson Delaunay triangulation for the small point sets of 2D blend spaces.
 */
public final class Delaunay {
    private Delaunay() {
    }

    /**
     * @return triangle corner indices (3 per triangle, counter-clockwise); empty if all points are collinear
     */
    public static int[] triangulate(double[] xs, double[] ys) {
        int n = xs.length;
        if (n < 3) return new int[0];
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        double d = Math.max(maxX - minX, maxY - minY);
        if (d <= 0) return new int[0];
        double midX = (minX + maxX) / 2, midY = (minY + maxY) / 2;

        double[] px = new double[n + 3];
        double[] py = new double[n + 3];
        System.arraycopy(xs, 0, px, 0, n);
        System.arraycopy(ys, 0, py, 0, n);
        px[n] = midX - 20 * d;
        py[n] = midY - d;
        px[n + 1] = midX;
        py[n + 1] = midY + 20 * d;
        px[n + 2] = midX + 20 * d;
        py[n + 2] = midY - d;

        List<int[]> tris = new ArrayList<>();
        tris.add(ccw(px, py, n, n + 1, n + 2));

        for (int p = 0; p < n; p++) {
            List<int[]> bad = new ArrayList<>();
            for (int[] t : tris) {
                if (inCircumcircle(px, py, t, px[p], py[p])) bad.add(t);
            }
            Map<Long, int[]> boundary = new HashMap<>();
            for (int[] t : bad) {
                for (int e = 0; e < 3; e++) {
                    int a = t[e], b = t[(e + 1) % 3];
                    long key = edgeKey(a, b);
                    if (boundary.remove(key) == null) boundary.put(key, new int[]{a, b});
                }
            }
            tris.removeAll(bad);
            for (int[] edge : boundary.values()) {
                if (Math.abs(cross(px, py, edge[0], edge[1], p)) < 1e-14) continue; // degenerate sliver
                tris.add(ccw(px, py, edge[0], edge[1], p));
            }
        }

        List<int[]> result = new ArrayList<>();
        for (int[] t : tris) {
            if (t[0] >= n || t[1] >= n || t[2] >= n) continue;
            if (Math.abs(cross(px, py, t[0], t[1], t[2])) < 1e-12) continue;
            result.add(t);
        }
        int[] out = new int[result.size() * 3];
        for (int i = 0; i < result.size(); i++) {
            System.arraycopy(result.get(i), 0, out, i * 3, 3);
        }
        return out;
    }

    /** Edges that belong to exactly one triangle (the convex hull), 2 indices per edge. */
    public static int[] hullEdges(int[] triangles) {
        Map<Long, int[]> count = new HashMap<>();
        for (int t = 0; t < triangles.length; t += 3) {
            for (int e = 0; e < 3; e++) {
                int a = triangles[t + e], b = triangles[t + (e + 1) % 3];
                long key = edgeKey(a, b);
                int[] entry = count.get(key);
                if (entry == null) count.put(key, new int[]{a, b, 1});
                else entry[2]++;
            }
        }
        int[] out = new int[count.size() * 2];
        int k = 0;
        for (int[] entry : count.values()) {
            if (entry[2] == 1) {
                out[k++] = entry[0];
                out[k++] = entry[1];
            }
        }
        return java.util.Arrays.copyOf(out, k);
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static double cross(double[] px, double[] py, int a, int b, int c) {
        return (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a]);
    }

    private static int[] ccw(double[] px, double[] py, int a, int b, int c) {
        return cross(px, py, a, b, c) >= 0 ? new int[]{a, b, c} : new int[]{a, c, b};
    }

    private static boolean inCircumcircle(double[] px, double[] py, int[] t, double x, double y) {
        double ax = px[t[0]] - x, ay = py[t[0]] - y;
        double bx = px[t[1]] - x, by = py[t[1]] - y;
        double cx = px[t[2]] - x, cy = py[t[2]] - y;
        double det = (ax * ax + ay * ay) * (bx * cy - cx * by)
                - (bx * bx + by * by) * (ax * cy - cx * ay)
                + (cx * cx + cy * cy) * (ax * by - bx * ay);
        return det > 1e-12; // triangles are stored counter-clockwise
    }
}
