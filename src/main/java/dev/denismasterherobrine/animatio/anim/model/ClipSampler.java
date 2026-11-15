package dev.denismasterherobrine.animatio.anim.model;

import dev.denismasterherobrine.animatio.anim.math.PoseMath;

/**
 * Keyframe interpolation following the glTF 2.0 rules for STEP, LINEAR and CUBICSPLINE samplers.
 */
public final class ClipSampler {
    private ClipSampler() {
    }

    /** Sample {@code ch} at {@code time}, writing 3 or 4 floats at {@code out[o]}. */
    public static void sample(Channel ch, float time, float[] out, int o) {
        float[] times = ch.times();
        float[] values = ch.values();
        int comps = Channel.components(ch.path());
        int interp = ch.interpolation();
        int n = times.length;
        boolean cubic = interp == Channel.CUBIC;
        int stride = cubic ? comps * 3 : comps;
        int valueOffset = cubic ? comps : 0;

        if (n == 1 || time <= times[0]) {
            copy(values, valueOffset, comps, out, o);
            return;
        }
        if (time >= times[n - 1]) {
            copy(values, (n - 1) * stride + valueOffset, comps, out, o);
            return;
        }
        int k = findKey(times, time);
        float t0 = times[k], t1 = times[k + 1];
        float dt = t1 - t0;
        float u = dt > 0f ? (time - t0) / dt : 0f;

        switch (interp) {
            case Channel.STEP -> copy(values, k * stride, comps, out, o);
            case Channel.LINEAR -> {
                int a = k * comps, b = (k + 1) * comps;
                if (comps == 4) PoseMath.slerp(values, a, values, b, u, out, o);
                else PoseMath.lerp3(values, a, values, b, u, out, o);
            }
            default -> {
                float u2 = u * u, u3 = u2 * u;
                float h00 = 2f * u3 - 3f * u2 + 1f;
                float h10 = u3 - 2f * u2 + u;
                float h01 = -2f * u3 + 3f * u2;
                float h11 = u3 - u2;
                int base0 = k * stride, base1 = (k + 1) * stride;
                for (int c = 0; c < comps; c++) {
                    float p0 = values[base0 + comps + c];
                    float m0 = values[base0 + 2 * comps + c] * dt; // out-tangent of key k
                    float p1 = values[base1 + comps + c];
                    float m1 = values[base1 + c] * dt;            // in-tangent of key k+1
                    out[o + c] = h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
                }
                if (comps == 4) PoseMath.normalizeQuat(out, o);
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

    private static void copy(float[] src, int so, int comps, float[] out, int o) {
        System.arraycopy(src, so, out, o, comps);
        if (comps == 4) PoseMath.normalizeQuat(out, o);
    }
}
