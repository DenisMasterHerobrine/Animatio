package dev.denismasterherobrine.animatio.api.playable;

import dev.denismasterherobrine.animatio.anim.math.Delaunay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Blends clips placed freely on a plane of two parameters, for example forward speed and sideways speed:
 * <pre>{@code
 * BlendSpace2D MOVE = BlendSpace2D.builder("move", "move_x", "move_z")
 *         .sample(0, 0, IDLE)
 *         .sample(0, 4, WALK_FORWARD).sample(0, -3, WALK_BACK)
 *         .sample(-3, 0, STRAFE_LEFT).sample(3, 0, STRAFE_RIGHT)
 *         .build();
 * }</pre>
 * The samples are triangulated once (Delaunay, on axes normalised to their range). A parameter point inside a
 * triangle blends its three corners by barycentric weights; a point outside the samples' hull uses the nearest
 * point on the hull edge.
 */
public final class BlendSpace2D extends BlendSpace {
    private static final float INSIDE_EPSILON = 1e-5f;

    private final String parameterX;
    private final String parameterY;
    private final float[] xs;
    private final float[] ys;
    private final float minX, minY, invRangeX, invRangeY;
    /** Normalised sample coordinates. */
    private final float[] nx, ny;
    /** Triangle corner indices, 3 per triangle. */
    private final int[] triangles;
    /** Edge endpoint indices, 2 per edge, used when the point lies outside every triangle. */
    private final int[] edges;

    private BlendSpace2D(String name, String parameterX, String parameterY, AnimationRef[] clips, float[] xs, float[] ys, float smoothing) {
        super(name, clips, smoothing);
        this.parameterX = Objects.requireNonNull(parameterX, "parameterX");
        this.parameterY = Objects.requireNonNull(parameterY, "parameterY");
        this.xs = xs;
        this.ys = ys;
        int n = xs.length;
        float loX = Float.POSITIVE_INFINITY, hiX = Float.NEGATIVE_INFINITY, loY = Float.POSITIVE_INFINITY, hiY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            loX = Math.min(loX, xs[i]);
            hiX = Math.max(hiX, xs[i]);
            loY = Math.min(loY, ys[i]);
            hiY = Math.max(hiY, ys[i]);
        }
        this.minX = loX;
        this.minY = loY;
        this.invRangeX = hiX > loX ? 1f / (hiX - loX) : 1f;
        this.invRangeY = hiY > loY ? 1f / (hiY - loY) : 1f;
        this.nx = new float[n];
        this.ny = new float[n];
        double[] dx = new double[n];
        double[] dy = new double[n];
        for (int i = 0; i < n; i++) {
            nx[i] = (xs[i] - minX) * invRangeX;
            ny[i] = (ys[i] - minY) * invRangeY;
            dx[i] = nx[i];
            dy[i] = ny[i];
        }
        this.triangles = n >= 3 ? Delaunay.triangulate(dx, dy) : new int[0];
        this.edges = triangles.length > 0 ? Delaunay.hullEdges(triangles) : chain(nx, ny);
    }

    /** For collinear samples: segments between neighbours along the line. */
    private static int[] chain(float[] nx, float[] ny) {
        int n = nx.length;
        if (n < 2) return new int[0];
        int a = 0, b = 0;
        float best = -1f;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                float dx = nx[j] - nx[i], dy = ny[j] - ny[i];
                float d = dx * dx + dy * dy;
                if (d > best) {
                    best = d;
                    a = i;
                    b = j;
                }
            }
        }
        float dirX = nx[b] - nx[a], dirY = ny[b] - ny[a];
        Integer[] order = new Integer[n];
        float[] proj = new float[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
            proj[i] = (nx[i] - nx[a]) * dirX + (ny[i] - ny[a]) * dirY;
        }
        Arrays.sort(order, (p, q) -> Float.compare(proj[p], proj[q]));
        int[] out = new int[(n - 1) * 2];
        for (int i = 0; i < n - 1; i++) {
            out[i * 2] = order[i];
            out[i * 2 + 1] = order[i + 1];
        }
        return out;
    }

    public static Builder builder(String name, String parameterX, String parameterY) {
        return new Builder(name, parameterX, parameterY);
    }

    public float positionX(int index) {
        return xs[index];
    }

    public float positionY(int index) {
        return ys[index];
    }

    /** Number of triangles in the triangulation (0 when the samples are collinear or fewer than 3). */
    public int triangleCount() {
        return triangles.length / 3;
    }

    @Override
    public String parameterX() {
        return parameterX;
    }

    @Override
    public String parameterY() {
        return parameterY;
    }

    @Override
    public void weights(float x, float y, float[] out) {
        int n = xs.length;
        Arrays.fill(out, 0, n, 0f);
        if (n == 1 || Float.isNaN(x) || Float.isNaN(y)) {
            out[0] = 1f;
            return;
        }
        float px = (x - minX) * invRangeX;
        float py = (y - minY) * invRangeY;

        for (int t = 0; t < triangles.length; t += 3) {
            int a = triangles[t], b = triangles[t + 1], c = triangles[t + 2];
            float v0x = nx[b] - nx[a], v0y = ny[b] - ny[a];
            float v1x = nx[c] - nx[a], v1y = ny[c] - ny[a];
            float v2x = px - nx[a], v2y = py - ny[a];
            float den = v0x * v1y - v1x * v0y;
            if (Math.abs(den) < 1e-12f) continue;
            float wb = (v2x * v1y - v1x * v2y) / den;
            float wc = (v0x * v2y - v2x * v0y) / den;
            float wa = 1f - wb - wc;
            if (wa >= -INSIDE_EPSILON && wb >= -INSIDE_EPSILON && wc >= -INSIDE_EPSILON) {
                wa = Math.max(0f, wa);
                wb = Math.max(0f, wb);
                wc = Math.max(0f, wc);
                float sum = wa + wb + wc;
                out[a] = wa / sum;
                out[b] = wb / sum;
                out[c] = wc / sum;
                return;
            }
        }

        // Outside the hull (or no triangles): nearest point on an edge.
        float bestDist = Float.POSITIVE_INFINITY;
        int bestA = 0, bestB = -1;
        float bestT = 0f;
        for (int e = 0; e < edges.length; e += 2) {
            int a = edges[e], b = edges[e + 1];
            float ex = nx[b] - nx[a], ey = ny[b] - ny[a];
            float len2 = ex * ex + ey * ey;
            float t = len2 > 0f ? ((px - nx[a]) * ex + (py - ny[a]) * ey) / len2 : 0f;
            t = Math.max(0f, Math.min(1f, t));
            float qx = nx[a] + ex * t - px, qy = ny[a] + ey * t - py;
            float d = qx * qx + qy * qy;
            if (d < bestDist) {
                bestDist = d;
                bestA = a;
                bestB = b;
                bestT = t;
            }
        }
        if (bestB < 0) {
            out[0] = 1f;
            return;
        }
        out[bestA] = 1f - bestT;
        out[bestB] += bestT;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BlendSpace2D b && name.equals(b.name) && parameterX.equals(b.parameterX)
                && parameterY.equals(b.parameterY) && smoothing == b.smoothing && Arrays.equals(xs, b.xs)
                && Arrays.equals(ys, b.ys) && Arrays.equals(clips, b.clips);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, parameterX, parameterY, Arrays.hashCode(xs), Arrays.hashCode(ys), Arrays.hashCode(clips));
    }

    @Override
    public String toString() {
        return "BlendSpace2D[" + name + " on " + parameterX + "/" + parameterY + ", " + clips.length + " samples]";
    }

    public static final class Builder {
        private record Sample(float x, float y, AnimationRef clip) {
        }

        private final String name;
        private final String parameterX;
        private final String parameterY;
        private final List<Sample> samples = new ArrayList<>();
        private float smoothing;

        private Builder(String name, String parameterX, String parameterY) {
            this.name = Objects.requireNonNull(name, "name");
            this.parameterX = Objects.requireNonNull(parameterX, "parameterX");
            this.parameterY = Objects.requireNonNull(parameterY, "parameterY");
        }

        public Builder sample(float x, float y, AnimationRef clip) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Invalid position " + x + ", " + y);
            for (Sample s : samples) {
                if (s.x == x && s.y == y) {
                    throw new IllegalArgumentException("Blend space '" + name + "' has two samples at " + x + ", " + y);
                }
            }
            samples.add(new Sample(x, y, Objects.requireNonNull(clip, "clip")));
            return this;
        }

        public Builder smoothing(float seconds) {
            this.smoothing = seconds;
            return this;
        }

        public BlendSpace2D build() {
            int n = samples.size();
            AnimationRef[] clips = new AnimationRef[n];
            float[] xs = new float[n];
            float[] ys = new float[n];
            for (int i = 0; i < n; i++) {
                clips[i] = samples.get(i).clip;
                xs[i] = samples.get(i).x;
                ys[i] = samples.get(i).y;
            }
            return new BlendSpace2D(name, parameterX, parameterY, clips, xs, ys, smoothing);
        }
    }
}
