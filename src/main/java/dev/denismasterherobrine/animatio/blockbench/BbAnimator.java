package dev.denismasterherobrine.animatio.blockbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.denismasterherobrine.animatio.anim.model.Channel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One Blockbench bone animator: keyframes per channel, evaluated exactly like Blockbench's
 * {@code BoneAnimator.interpolate} and baked into {@link Channel}s.
 */
final class BbAnimator {
    private static final double EPSILON = 1.0 / 1200.0;
    private static final String[] CHANNELS = {"rotation", "position", "scale"};

    /** One keyframe: one or two data points (before/after values of a discontinuous key). */
    static final class Keyframe {
        final float time;
        final String interpolation;
        final String[][] points; // [data point][axis]
        final float[] bezierLeftTime = {-0.1f, -0.1f, -0.1f}, bezierLeftValue = new float[3];
        final float[] bezierRightTime = {0.1f, 0.1f, 0.1f}, bezierRightValue = new float[3];

        Keyframe(float time, String interpolation, String[][] points) {
            this.time = time;
            this.interpolation = interpolation;
            this.points = points;
        }

        /** {@code Keyframe.calc}: the value of one axis of a data point (clamped index). */
        double calc(int axis, int dataPoint, double animTime) {
            String[] p = points[Math.max(0, Math.min(points.length - 1, dataPoint))];
            return Molang.eval(p[axis], animTime);
        }
    }

    private final List<List<Keyframe>> channels = new ArrayList<>(3);
    private final boolean loop;
    private final float length;

    private BbAnimator(boolean loop, float length) {
        this.loop = loop;
        this.length = length;
    }

    static BbAnimator parse(JsonObject animator, boolean loop, float length) {
        BbAnimator a = new BbAnimator(loop, length);
        for (int c = 0; c < 3; c++) a.channels.add(new ArrayList<>());
        JsonElement kfs = animator.get("keyframes");
        if (kfs == null || !kfs.isJsonArray()) return a;
        for (JsonElement e : kfs.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject kf = e.getAsJsonObject();
            int channel = switch (BbmodelLoader.str(kf, "channel", "")) {
                case "rotation" -> 0;
                case "position" -> 1;
                case "scale" -> 2;
                default -> -1; // sounds, particles, timeline
            };
            if (channel < 0) continue;
            JsonElement dps = kf.get("data_points");
            if (dps == null || !dps.isJsonArray() || dps.getAsJsonArray().isEmpty()) continue;
            JsonArray arr = dps.getAsJsonArray();
            String[][] points = new String[Math.min(2, arr.size())][];
            String fallback = channel == 2 ? "1" : "0";
            for (int i = 0; i < points.length; i++) {
                JsonObject dp = arr.get(i).getAsJsonObject();
                points[i] = new String[]{value(dp, "x", fallback), value(dp, "y", fallback), value(dp, "z", fallback)};
            }
            Keyframe k = new Keyframe(BbmodelLoader.num(kf, "time", 0f), BbmodelLoader.str(kf, "interpolation", "linear"), points);
            readBezier(kf, "bezier_left_time", k.bezierLeftTime);
            readBezier(kf, "bezier_left_value", k.bezierLeftValue);
            readBezier(kf, "bezier_right_time", k.bezierRightTime);
            readBezier(kf, "bezier_right_value", k.bezierRightValue);
            a.channels.get(channel).add(k);
        }
        return a;
    }

    private static String value(JsonObject dp, String axis, String fallback) {
        JsonElement e = dp.get(axis);
        if (e == null || e.isJsonNull()) return fallback;
        String s = e.getAsString();
        // Blockbench treats an empty or missing value as 0 (calc of "" is 0); scale keys default to the identity
        return s.isBlank() ? (fallback.equals("1") ? "1" : "0") : s;
    }

    private static void readBezier(JsonObject kf, String key, float[] out) {
        JsonElement e = kf.get(key);
        if (e == null || !e.isJsonArray()) return;
        JsonArray a = e.getAsJsonArray();
        for (int i = 0; i < Math.min(3, a.size()); i++) out[i] = a.get(i).getAsFloat();
    }

    boolean isEmpty() {
        return channels.get(0).isEmpty() && channels.get(1).isEmpty() && channels.get(2).isEmpty();
    }

    // ------------------------------------------------------------------ evaluation

    /**
     * {@code BoneAnimator.interpolate} for one axis: the offset (rotation in degrees, position in pixels) or factor
     * (scale) at {@code time}, or NaN when the channel has no keyframes.
     */
    double value(int channel, int axis, double time) {
        List<Keyframe> kfs = channels.get(channel);
        if (kfs.isEmpty()) return Double.NaN;
        Keyframe before = null, after = null;
        for (Keyframe k : kfs) {
            if (k.time < time) {
                if (before == null || k.time > before.time) before = k;
            } else if (after == null || k.time < after.time) {
                after = k;
            }
        }
        if (before != null && Math.abs(before.time - time) < EPSILON) return at(before, axis, time);
        if (after != null && Math.abs(after.time - time) < EPSILON) return at(after, axis, time);
        if (before != null && before.interpolation.equals("step")) return at(before, axis, time);
        if (before != null && after == null) return at(before, axis, time);
        if (after != null && before == null) return at(after, axis, time);
        double alpha = (time - before.time) / (after.time - before.time);
        if (before.interpolation.equals("linear") && (after.interpolation.equals("linear") || after.interpolation.equals("step"))) {
            double a = before.calc(axis, before.points.length > 1 ? 1 : 0, time);
            return a + (after.calc(axis, 0, time) - a) * alpha;
        }
        if (before.interpolation.equals("catmullrom") || after.interpolation.equals("catmullrom")) {
            List<Keyframe> sorted = new ArrayList<>(kfs);
            sorted.sort(Comparator.comparingDouble(k -> k.time));
            int bi = sorted.indexOf(before);
            Keyframe beforePlus = bi - 1 >= 0 ? sorted.get(bi - 1) : null;
            Keyframe afterPlus = bi + 2 < sorted.size() ? sorted.get(bi + 2) : null;
            if (loop && sorted.size() >= 3) {
                if (beforePlus == null) beforePlus = sorted.get(sorted.size() - 2);
                if (afterPlus == null) afterPlus = sorted.get(1);
            }
            return catmullrom(beforePlus, before, after, afterPlus, axis, alpha, time);
        }
        if (before.interpolation.equals("bezier") || after.interpolation.equals("bezier")) {
            return bezier(before, after, axis, alpha, time);
        }
        double a = before.calc(axis, before.points.length > 1 ? 1 : 0, time);
        return a + (after.calc(axis, 0, time) - a) * alpha;
    }

    /** A keyframe's own value: its first data point if it lies at or after {@code time}, else its last. */
    private static double at(Keyframe k, int axis, double time) {
        int dp = (k.time > time || Math.abs(k.time - time) < EPSILON) ? 0 : k.points.length - 1;
        return k.calc(axis, dp, time);
    }

    /** {@code Keyframe.getCatmullromLerp}: a THREE.SplineCurve through the neighbouring keys, by index. */
    private static double catmullrom(@Nullable Keyframe beforePlus, Keyframe before, Keyframe after, @Nullable Keyframe afterPlus,
                                     int axis, double alpha, double time) {
        double[] ys = new double[4];
        int n = 0;
        if (beforePlus != null && before.points.length == 1) ys[n++] = beforePlus.calc(axis, 1, time);
        ys[n++] = before.calc(axis, 1, time);
        ys[n++] = after.calc(axis, 0, time);
        if (afterPlus != null && after.points.length == 1) ys[n++] = afterPlus.calc(axis, 0, time);
        double t = (alpha + (beforePlus != null ? 1 : 0)) / (n - 1);
        // THREE.SplineCurve.getPoint
        double p = (n - 1) * t;
        int i = (int) Math.floor(p);
        double w = p - i;
        double p0 = ys[i == 0 ? i : i - 1];
        double p1 = ys[Math.min(i, n - 1)];
        double p2 = ys[i > n - 2 ? n - 1 : i + 1];
        double p3 = ys[i > n - 3 ? n - 1 : i + 2];
        double v0 = (p2 - p0) * 0.5, v1 = (p3 - p1) * 0.5;
        double w2 = w * w, w3 = w * w2;
        return (2 * p1 - 2 * p2 + v0 + v1) * w3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * w2 + v0 * w + p1;
    }

    /** {@code Keyframe.getBezierLerp}: a cubic Bézier in (time, value) through the keys' handles. */
    private static double bezier(Keyframe before, Keyframe after, int axis, double alpha, double time) {
        double vb = before.calc(axis, 1, time), va = after.calc(axis, 0, time);
        double gap = after.time - before.time;
        double hb = Math.max(0, Math.min(gap, before.bezierRightTime[axis]));
        double ha = Math.max(-gap, Math.min(0, after.bezierLeftTime[axis]));
        double x0 = before.time, x1 = before.time + hb, x2 = after.time + ha, x3 = after.time;
        double y0 = vb, y1 = vb + before.bezierRightValue[axis], y2 = va + after.bezierLeftValue[axis], y3 = va;
        double target = before.time + gap * alpha;
        // x(s) is monotonic for clamped handles: bisect for x(s) = target
        double lo = 0, hi = 1;
        for (int it = 0; it < 40; it++) {
            double mid = (lo + hi) / 2;
            if (cubic(x0, x1, x2, x3, mid) < target) lo = mid;
            else hi = mid;
        }
        return cubic(y0, y1, y2, y3, (lo + hi) / 2);
    }

    private static double cubic(double a, double b, double c, double d, double s) {
        double u = 1 - s;
        return u * u * u * a + 3 * u * u * s * b + 3 * u * s * s * c + s * s * s * d;
    }

    /** Quaternion of a keyframe's rotation on top of the rest rotation ({@code Keyframe.getFixed} with quaternions). */
    private static Quaternionf fixedQuat(Keyframe k, int dataPoint, float[] rest, double time, Quaternionf out) {
        Quaternionf restQ = BbmodelLoader.euler(rest[0], rest[1], rest[2], new Quaternionf());
        Quaternionf kq = BbmodelLoader.euler((float) k.calc(0, dataPoint, time), (float) k.calc(1, dataPoint, time),
                (float) k.calc(2, dataPoint, time), new Quaternionf());
        return out.set(restQ).mul(kq);
    }

    /** Rotation with per-bone quaternion interpolation: slerp between the keys' fixed quaternions. */
    private Quaternionf quaternionRotation(double time, float[] rest, Quaternionf out) {
        List<Keyframe> kfs = channels.get(0);
        Keyframe before = null, after = null;
        for (Keyframe k : kfs) {
            if (k.time < time) {
                if (before == null || k.time > before.time) before = k;
            } else if (after == null || k.time < after.time) {
                after = k;
            }
        }
        Keyframe single = null;
        if (before != null && Math.abs(before.time - time) < EPSILON) single = before;
        else if (after != null && Math.abs(after.time - time) < EPSILON) single = after;
        else if (before != null && (before.interpolation.equals("step") || after == null)) single = before;
        else if (before == null) single = after;
        if (single != null) {
            int dp = (single.time > time || Math.abs(single.time - time) < EPSILON) ? 0 : single.points.length - 1;
            return fixedQuat(single, dp, rest, time, out);
        }
        double alpha = (time - before.time) / (after.time - before.time);
        Quaternionf a = fixedQuat(before, 1, rest, time, new Quaternionf());
        Quaternionf b = fixedQuat(after, 0, rest, time, new Quaternionf());
        return a.slerp(b, (float) alpha, out);
    }

    // ------------------------------------------------------------------ baking

    /**
     * Sample every keyed channel at {@link BbmodelLoader#SAMPLE_RATE} over the animation's length and add the
     * resulting translation (pixels to blocks), rotation (Euler offset on the rest rotation, ZYX) and scale channels.
     */
    void bake(int node, float[] origin, float[] parentOrigin, float[] restRotation, boolean quaternionInterpolation, List<Channel> out) {
        int count = Math.max(2, (int) Math.ceil(length * BbmodelLoader.SAMPLE_RATE) + 1);
        float[] times = new float[count];
        for (int i = 0; i < count; i++) times[i] = length * i / (count - 1);
        if (length <= 0f) {
            count = 1;
            times = new float[]{0f};
        }
        Quaternionf q = new Quaternionf();
        if (!channels.get(0).isEmpty()) {
            float[] values = new float[count * 4];
            for (int i = 0; i < count; i++) {
                double t = times[i];
                if (quaternionInterpolation) {
                    quaternionRotation(t, restRotation, q);
                } else {
                    BbmodelLoader.euler(restRotation[0] + (float) value(0, 0, t), restRotation[1] + (float) value(0, 1, t),
                            restRotation[2] + (float) value(0, 2, t), q);
                }
                values[i * 4] = q.x;
                values[i * 4 + 1] = q.y;
                values[i * 4 + 2] = q.z;
                values[i * 4 + 3] = q.w;
            }
            out.add(new Channel(node, Channel.ROTATION, Channel.LINEAR, times, values));
        }
        if (!channels.get(1).isEmpty()) {
            float[] values = new float[count * 3];
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < 3; c++) {
                    values[i * 3 + c] = (float) ((origin[c] - parentOrigin[c] + value(1, c, times[i])) / 16.0);
                }
            }
            out.add(new Channel(node, Channel.TRANSLATION, Channel.LINEAR, times, values));
        }
        if (!channels.get(2).isEmpty()) {
            float[] values = new float[count * 3];
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < 3; c++) {
                    float v = (float) value(2, c, times[i]);
                    values[i * 3 + c] = v == 0f ? 0.00001f : v;
                }
            }
            out.add(new Channel(node, Channel.SCALE, Channel.LINEAR, times, values));
        }
    }
}
