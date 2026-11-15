package dev.denismasterherobrine.animatio.anim.math;

/**
 * Allocation-free vector and quaternion helpers on flat float arrays. Quaternions are x, y, z, w.
 */
public final class PoseMath {
    private PoseMath() {
    }

    public static void lerp3(float[] a, int ao, float[] b, int bo, float u, float[] out, int oo) {
        float x = a[ao] + (b[bo] - a[ao]) * u;
        float y = a[ao + 1] + (b[bo + 1] - a[ao + 1]) * u;
        float z = a[ao + 2] + (b[bo + 2] - a[ao + 2]) * u;
        out[oo] = x;
        out[oo + 1] = y;
        out[oo + 2] = z;
    }

    public static float dot4(float[] a, int ao, float[] b, int bo) {
        return a[ao] * b[bo] + a[ao + 1] * b[bo + 1] + a[ao + 2] * b[bo + 2] + a[ao + 3] * b[bo + 3];
    }

    /** Normalised lerp along the shortest arc. */
    public static void nlerp(float[] a, int ao, float[] b, int bo, float u, float[] out, int oo) {
        float sign = dot4(a, ao, b, bo) < 0f ? -1f : 1f;
        float x = a[ao] + (b[bo] * sign - a[ao]) * u;
        float y = a[ao + 1] + (b[bo + 1] * sign - a[ao + 1]) * u;
        float z = a[ao + 2] + (b[bo + 2] * sign - a[ao + 2]) * u;
        float w = a[ao + 3] + (b[bo + 3] * sign - a[ao + 3]) * u;
        writeNormalized(out, oo, x, y, z, w);
    }

    /** Spherical lerp along the shortest arc. */
    public static void slerp(float[] a, int ao, float[] b, int bo, float u, float[] out, int oo) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
        float cos = ax * bx + ay * by + az * bz + aw * bw;
        if (cos < 0f) {
            cos = -cos;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        float wa, wb;
        if (cos > 0.9995f) {
            wa = 1f - u;
            wb = u;
        } else {
            float angle = (float) Math.acos(cos);
            float invSin = 1f / (float) Math.sin(angle);
            wa = (float) Math.sin((1f - u) * angle) * invSin;
            wb = (float) Math.sin(u * angle) * invSin;
        }
        writeNormalized(out, oo, ax * wa + bx * wb, ay * wa + by * wb, az * wa + bz * wb, aw * wa + bw * wb);
    }

    public static void normalizeQuat(float[] q, int o) {
        writeNormalized(q, o, q[o], q[o + 1], q[o + 2], q[o + 3]);
    }

    private static void writeNormalized(float[] out, int o, float x, float y, float z, float w) {
        float len2 = x * x + y * y + z * z + w * w;
        if (len2 < 1e-12f) {
            out[o] = 0f;
            out[o + 1] = 0f;
            out[o + 2] = 0f;
            out[o + 3] = 1f;
            return;
        }
        float inv = (float) (1.0 / Math.sqrt(len2));
        out[o] = x * inv;
        out[o + 1] = y * inv;
        out[o + 2] = z * inv;
        out[o + 3] = w * inv;
    }

    /** {@code out = a * b} (Hamilton product). {@code out} may alias either input. */
    public static void mulQuat(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
        out[oo] = aw * bx + ax * bw + ay * bz - az * by;
        out[oo + 1] = aw * by - ax * bz + ay * bw + az * bx;
        out[oo + 2] = aw * bz + ax * by - ay * bx + az * bw;
        out[oo + 3] = aw * bw - ax * bx - ay * by - az * bz;
    }
}
