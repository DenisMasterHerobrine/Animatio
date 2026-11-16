package dev.denismasterherobrine.animatio.anim.model;

import dev.denismasterherobrine.animatio.anim.math.PoseMath;

/**
 * Keyframe interpolation following the glTF 2.0 rules for STEP, LINEAR and CUBICSPLINE samplers.
 * {@link Clip} uses the same kernel on its flattened channel data; this entry point samples a single {@link Channel}.
 */
public final class ClipSampler {
    private ClipSampler() {
    }

    /** Sample {@code ch} at {@code time}, writing {@link Channel#comps()} floats at {@code out[o]}. */
    public static void sample(Channel ch, float time, float[] out, int o) {
        float[] times = ch.times();
        int n = times.length;
        int k;
        float u, dt;
        int mode;
        if (n == 1 || time <= times[0]) {
            k = 0;
            u = 0f;
            dt = 0f;
            mode = CLAMP;
        } else if (time >= times[n - 1]) {
            k = n - 1;
            u = 0f;
            dt = 0f;
            mode = CLAMP;
        } else {
            k = findKey(times, time);
            dt = times[k + 1] - times[k];
            u = dt > 0f ? (time - times[k]) / dt : 0f;
            mode = BETWEEN;
        }
        interpolate(ch.values(), 0, ch.comps(), ch.interpolation(), ch.path() == Channel.ROTATION, k, u, dt, mode, out, o);
    }

    static final int CLAMP = 0, BETWEEN = 1;

    /**
     * The interpolation kernel.
     *
     * @param values channel values starting at {@code base}
     * @param k      key index (the key to hold when {@code mode == CLAMP}, else the key before {@code u})
     */
    static void interpolate(float[] values, int base, int comps, int interp, boolean quat, int k, float u, float dt, int mode,
                            float[] out, int o) {
        boolean cubic = interp == Channel.CUBIC;
        int stride = cubic ? comps * 3 : comps;
        if (mode == CLAMP) {
            System.arraycopy(values, base + k * stride + (cubic ? comps : 0), out, o, comps);
            if (quat) PoseMath.normalizeQuat(out, o);
            return;
        }
        switch (interp) {
            case Channel.STEP -> {
                System.arraycopy(values, base + k * stride, out, o, comps);
                if (quat) PoseMath.normalizeQuat(out, o);
            }
            case Channel.LINEAR -> {
                int a = base + k * comps, b = a + comps;
                if (quat) {
                    PoseMath.slerp(values, a, values, b, u, out, o);
                } else if (comps == 3) {
                    PoseMath.lerp3(values, a, values, b, u, out, o);
                } else {
                    for (int c = 0; c < comps; c++) out[o + c] = values[a + c] + (values[b + c] - values[a + c]) * u;
                }
            }
            default -> {
                float u2 = u * u, u3 = u2 * u;
                float h00 = 2f * u3 - 3f * u2 + 1f;
                float h10 = (u3 - 2f * u2 + u) * dt;
                float h01 = -2f * u3 + 3f * u2;
                float h11 = (u3 - u2) * dt;
                int base0 = base + k * stride, base1 = base0 + stride;
                for (int c = 0; c < comps; c++) {
                    float p0 = values[base0 + comps + c];
                    float m0 = values[base0 + 2 * comps + c]; // out-tangent of key k
                    float p1 = values[base1 + comps + c];
                    float m1 = values[base1 + c];             // in-tangent of key k+1
                    out[o + c] = h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
                }
                if (quat) PoseMath.normalizeQuat(out, o);
            }
        }
    }

    /** Largest {@code k} with {@code times[k] <= time}, for {@code times[0] < time < times[n-1]}. */
    static int findKey(float[] times, float time) {
        int lo = 0, hi = times.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= time) lo = mid;
            else hi = mid;
        }
        return lo;
    }
}
