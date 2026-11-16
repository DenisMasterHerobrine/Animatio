package dev.denismasterherobrine.animatio.anim.math;

import dev.denismasterherobrine.animatio.anim.model.Pose;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;

/**
 * Column-major 4x4 affine matrices stored in flat float arrays (16 floats per matrix, element {@code [col*4+row]}).
 * All methods are allocation-free and tolerate the output aliasing an input.
 */
public final class Mat4 {
    private Mat4() {
    }

    public static void identity(float[] m, int o) {
        for (int i = 0; i < 16; i++) m[o + i] = 0f;
        m[o] = 1f;
        m[o + 5] = 1f;
        m[o + 10] = 1f;
        m[o + 15] = 1f;
    }

    /** {@code m = T * R * S}. */
    public static void trs(float[] m, int o, float tx, float ty, float tz, float qx, float qy, float qz, float qw,
                           float sx, float sy, float sz) {
        float xx = qx * qx, yy = qy * qy, zz = qz * qz;
        float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        float wx = qw * qx, wy = qw * qy, wz = qw * qz;
        m[o] = (1f - 2f * (yy + zz)) * sx;
        m[o + 1] = 2f * (xy + wz) * sx;
        m[o + 2] = 2f * (xz - wy) * sx;
        m[o + 3] = 0f;
        m[o + 4] = 2f * (xy - wz) * sy;
        m[o + 5] = (1f - 2f * (xx + zz)) * sy;
        m[o + 6] = 2f * (yz + wx) * sy;
        m[o + 7] = 0f;
        m[o + 8] = 2f * (xz + wy) * sz;
        m[o + 9] = 2f * (yz - wx) * sz;
        m[o + 10] = (1f - 2f * (xx + yy)) * sz;
        m[o + 11] = 0f;
        m[o + 12] = tx;
        m[o + 13] = ty;
        m[o + 14] = tz;
        m[o + 15] = 1f;
    }

    /** {@code out = a * b}, assuming both are affine (last row 0,0,0,1). */
    public static void mulAffine(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float a00 = a[ao], a10 = a[ao + 1], a20 = a[ao + 2];
        float a01 = a[ao + 4], a11 = a[ao + 5], a21 = a[ao + 6];
        float a02 = a[ao + 8], a12 = a[ao + 9], a22 = a[ao + 10];
        float a03 = a[ao + 12], a13 = a[ao + 13], a23 = a[ao + 14];
        float b00 = b[bo], b10 = b[bo + 1], b20 = b[bo + 2];
        float b01 = b[bo + 4], b11 = b[bo + 5], b21 = b[bo + 6];
        float b02 = b[bo + 8], b12 = b[bo + 9], b22 = b[bo + 10];
        float b03 = b[bo + 12], b13 = b[bo + 13], b23 = b[bo + 14];
        out[oo] = a00 * b00 + a01 * b10 + a02 * b20;
        out[oo + 1] = a10 * b00 + a11 * b10 + a12 * b20;
        out[oo + 2] = a20 * b00 + a21 * b10 + a22 * b20;
        out[oo + 3] = 0f;
        out[oo + 4] = a00 * b01 + a01 * b11 + a02 * b21;
        out[oo + 5] = a10 * b01 + a11 * b11 + a12 * b21;
        out[oo + 6] = a20 * b01 + a21 * b11 + a22 * b21;
        out[oo + 7] = 0f;
        out[oo + 8] = a00 * b02 + a01 * b12 + a02 * b22;
        out[oo + 9] = a10 * b02 + a11 * b12 + a12 * b22;
        out[oo + 10] = a20 * b02 + a21 * b12 + a22 * b22;
        out[oo + 11] = 0f;
        out[oo + 12] = a00 * b03 + a01 * b13 + a02 * b23 + a03;
        out[oo + 13] = a10 * b03 + a11 * b13 + a12 * b23 + a13;
        out[oo + 14] = a20 * b03 + a21 * b13 + a22 * b23 + a23;
        out[oo + 15] = 1f;
    }

    /** Transform a point; writes x, y, z into {@code out[oo..oo+2]}. */
    public static void transformPoint(float[] m, int o, float x, float y, float z, float[] out, int oo) {
        float rx = m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12];
        float ry = m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13];
        float rz = m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14];
        out[oo] = rx;
        out[oo + 1] = ry;
        out[oo + 2] = rz;
    }

    /** Transform a direction (no translation, not normalised). */
    public static void transformDir(float[] m, int o, float x, float y, float z, float[] out, int oo) {
        float rx = m[o] * x + m[o + 4] * y + m[o + 8] * z;
        float ry = m[o + 1] * x + m[o + 5] * y + m[o + 9] * z;
        float rz = m[o + 2] * x + m[o + 6] * y + m[o + 10] * z;
        out[oo] = rx;
        out[oo + 1] = ry;
        out[oo + 2] = rz;
    }

    /**
     * The rotation of an affine matrix (scale removed per axis) as a unit quaternion x, y, z, w at {@code out[oo]}.
     * Mirrored matrices give the rotation of the matrix with its X axis flipped.
     */
    public static void rotationOf(float[] m, int o, float[] out, int oo) {
        float sx = len(m[o], m[o + 1], m[o + 2]), sy = len(m[o + 4], m[o + 5], m[o + 6]), sz = len(m[o + 8], m[o + 9], m[o + 10]);
        if (sx < 1e-12f || sy < 1e-12f || sz < 1e-12f) {
            out[oo] = 0f;
            out[oo + 1] = 0f;
            out[oo + 2] = 0f;
            out[oo + 3] = 1f;
            return;
        }
        float m00 = m[o] / sx, m10 = m[o + 1] / sx, m20 = m[o + 2] / sx;
        float m01 = m[o + 4] / sy, m11 = m[o + 5] / sy, m21 = m[o + 6] / sy;
        float m02 = m[o + 8] / sz, m12 = m[o + 9] / sz, m22 = m[o + 10] / sz;
        // det < 0: flip X so the basis is a proper rotation
        if (m00 * (m11 * m22 - m21 * m12) - m01 * (m10 * m22 - m20 * m12) + m02 * (m10 * m21 - m20 * m11) < 0f) {
            m00 = -m00;
            m10 = -m10;
            m20 = -m20;
        }
        float x, y, z, w;
        float trace = m00 + m11 + m22;
        if (trace > 0f) {
            float s = (float) Math.sqrt(trace + 1f) * 2f;
            w = 0.25f * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1f + m00 - m11 - m22) * 2f;
            w = (m21 - m12) / s;
            x = 0.25f * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1f + m11 - m00 - m22) * 2f;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25f * s;
            z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1f + m22 - m00 - m11) * 2f;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25f * s;
        }
        float inv = 1f / (float) Math.sqrt(x * x + y * y + z * z + w * w);
        out[oo] = x * inv;
        out[oo + 1] = y * inv;
        out[oo + 2] = z * inv;
        out[oo + 3] = w * inv;
    }

    private static float len(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /** Compute model-space (global) matrices for every node of a pose. {@code globals} holds 16 floats per node. */
    public static void computeGlobals(Skeleton skeleton, Pose pose, float[] globals) {
        int[] order = skeleton.order();
        int[] parents = skeleton.parents();
        float[] t = pose.t, r = pose.r, s = pose.s;
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            int o = i * 16;
            trs(globals, o, t[i * 3], t[i * 3 + 1], t[i * 3 + 2], r[i * 4], r[i * 4 + 1], r[i * 4 + 2], r[i * 4 + 3],
                    s[i * 3], s[i * 3 + 1], s[i * 3 + 2]);
            int p = parents[i];
            if (p >= 0) mulAffine(globals, p * 16, globals, o, globals, o);
        }
    }
}
