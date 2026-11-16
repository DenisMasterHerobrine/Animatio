package dev.denismasterherobrine.animatio.gltf.texture;

/**
 * UASTC LDR 4x4 block decoding to RGBA8, ported from the Basis Universal reference transcoder
 * ({@code unpack_uastc}). Each 16-byte block holds one of 19 modes: a solid colour, or ASTC-style endpoints (BISE
 * coded with bits and trits/quints) with plain-bit weights over one to three subsets (BC7-compatible partition
 * patterns) or two planes. Tables are copied from the reference.
 * <p>
 * Derived from the Basis Universal transcoder, Copyright Binomial LLC, Apache License 2.0
 * (https://github.com/BinomialLLC/basis_universal).
 */
final class UastcDecoder {
    private UastcDecoder() {
    }

    private static final int SOLID_MODE = 8, TOTAL_MODES = 19;
    private static final int[] HUFF_CODE_SIZES = {4, 6, 5, 5, 5, 5, 5, 5, 5, 5, 3, 2, 3, 5, 5, 7, 6, 6, 4, 7};
    private static final int[] WEIGHT_BITS = {4, 2, 3, 2, 2, 3, 2, 2, 0, 2, 4, 2, 3, 1, 2, 4, 2, 2, 5};
    private static final int[] ENDPOINT_RANGES = {19, 20, 8, 7, 12, 20, 18, 12, 0, 8, 13, 13, 19, 20, 20, 20, 20, 20, 11};
    private static final int[] PLANES = {1, 1, 1, 1, 1, 1, 2, 1, 0, 1, 1, 2, 1, 2, 1, 1, 1, 2, 1};
    private static final int[] COMPS = {3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 2, 2, 2, 3};
    private static final int[] TOTAL_HINT_BITS = {15, 15, 15, 15, 15, 15, 15, 15, 0, 23, 17, 17, 17, 23, 23, 23, 23, 23, 15};
    /** BISE ranges: bits, trits, quints. */
    private static final int[][] BISE = {
            {1, 0, 0}, {0, 1, 0}, {2, 0, 0}, {0, 0, 1}, {1, 1, 0}, {3, 0, 0}, {1, 0, 1}, {2, 1, 0}, {4, 0, 0}, {2, 0, 1},
            {3, 1, 0}, {5, 0, 0}, {3, 0, 1}, {4, 1, 0}, {6, 0, 0}, {4, 0, 1}, {5, 1, 0}, {7, 0, 0}, {5, 0, 1}, {6, 1, 0}, {8, 0, 0}};
    private static final String[] UNQUANT_B = {"", "", "", "", "000000000", "", "000000000", "b000b0bb0", "", "b0000bb00",
            "cb000cbcb", "", "cb0000cbc", "dcb000dcb", "", "dcb0000dc", "edcb000ed", "", "edcb0000e", "fedcb000f", ""};
    private static final int[] UNQUANT_C = {0, 0, 0, 0, 204, 0, 113, 93, 0, 54, 44, 0, 26, 22, 0, 13, 11, 0, 6, 5, 0};
    private static final int[][] WEIGHT_TABLES = {null, {0, 64}, {0, 21, 43, 64}, {0, 9, 18, 27, 37, 46, 55, 64},
            {0, 4, 8, 12, 17, 21, 25, 29, 35, 39, 43, 47, 52, 56, 60, 64},
            {0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64}};
    private static final int[] ZERO_PATTERN = new int[16];
    /** Unquantised endpoint value per [range][BISE value]. */
    private static final int[][] UNQUANT = new int[21][256];

    static {
        for (int range = 0; range < 21; range++) {
            if ((BISE[range][1] != 0 || BISE[range][2] != 0) && UNQUANT_C[range] == 0) continue;
            int levels = (1 + 2 * BISE[range][1] + 4 * BISE[range][2]) << BISE[range][0];
            for (int v = 0; v < levels; v++) UNQUANT[range][v] = unquantValue(v, range);
        }
    }

    private static int unquantValue(int packed, int range) {
        int bits = BISE[range][0], trits = BISE[range][1], quints = BISE[range][2];
        if (trits == 0 && quints == 0) return unquant(packed, 0, 0, range);
        int low = packed & ((1 << bits) - 1), high = packed >> bits;
        return trits != 0 ? unquant(low, high, 0, range) : unquant(low, 0, high, range);
    }

    private static int unquant(int packedBits, int packedTrits, int packedQuints, int range) {
        int bits = BISE[range][0], trits = BISE[range][1], quints = BISE[range][2];
        if (trits == 0 && quints == 0) {
            int val = 0, left = 8;
            while (left > 0) {
                int v = packedBits;
                int n = Math.min(left, bits);
                if (n < bits) v >>= bits - n;
                val |= v << (left - n);
                left -= n;
            }
            return val;
        }
        int a = (packedBits & 1) != 0 ? 511 : 0;
        int c = UNQUANT_C[range];
        int d = trits != 0 ? packedTrits : packedQuints;
        int b = 0;
        String pattern = UNQUANT_B[range];
        for (int i = 0; i < 9; i++) {
            b <<= 1;
            char ch = pattern.charAt(i);
            if (ch != '0') b |= (packedBits >> (ch - 'a')) & 1;
        }
        int val = d * c + b;
        val ^= a;
        return (a & 0x80) | (val >> 2);
    }

    private static int interpolate(int l, int h, int w) {
        l = (l << 8) | l;
        h = (h << 8) | h;
        return ((l * (64 - w) + h * w + 32) >> 6) >> 8;
    }

    // ------------------------------------------------------------------ tables from basisu_transcoder.cpp

    static final int[][] PATTERNS2 =
    {
        { 0,0,1,1,0,0,1,1,0,0,1,1,0,0,1,1 }, { 0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1 }, { 1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0 }, { 0,0,0,1,0,0,1,1,0,0,1,1,0,1,1,1 },
        { 1,1,1,1,1,1,1,0,1,1,1,0,1,1,0,0 }, { 0,0,1,1,0,1,1,1,0,1,1,1,1,1,1,1 }, { 1,1,1,0,1,1,0,0,1,0,0,0,0,0,0,0 }, { 1,1,1,1,1,1,1,0,1,1,0,0,1,0,0,0 },
        { 0,0,0,0,0,0,0,0,0,0,0,1,0,0,1,1 }, { 1,1,0,0,1,0,0,0,0,0,0,0,0,0,0,0 }, { 0,0,0,0,0,0,0,1,0,1,1,1,1,1,1,1 }, { 1,1,1,1,1,1,1,1,1,1,1,0,1,0,0,0 },
        { 1,1,1,0,1,0,0,0,0,0,0,0,0,0,0,0 }, { 1,1,1,1,1,1,1,1,0,0,0,0,0,0,0,0 }, { 0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1 }, { 1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0 },
        { 1,0,0,0,1,1,1,0,1,1,1,1,1,1,1,1 }, { 1,1,1,1,1,1,1,1,0,1,1,1,0,0,0,1 }, { 0,1,1,1,0,0,1,1,0,0,0,1,0,0,0,0 }, { 0,0,1,1,0,0,0,1,0,0,0,0,0,0,0,0 },
        { 0,0,0,0,1,0,0,0,1,1,0,0,1,1,1,0 }, { 1,1,1,1,1,1,1,1,0,1,1,1,0,0,1,1 }, { 1,0,0,0,1,1,0,0,1,1,0,0,1,1,1,0 }, { 0,0,1,1,0,0,0,1,0,0,0,1,0,0,0,0 },
        { 1,1,1,1,0,1,1,1,0,1,1,1,0,0,1,1 }, { 0,1,1,0,0,1,1,0,0,1,1,0,0,1,1,0 }, { 1,1,1,1,0,0,0,0,0,0,0,0,1,1,1,1 }, { 1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0 },
        { 1,1,1,1,0,0,0,0,1,1,1,1,0,0,0,0 }, { 1,0,0,1,0,0,1,1,0,1,1,0,1,1,0,0 }
    };
    static final int[][] PATTERNS3 =
    {
        { 0,0,0,0,0,0,0,0,1,1,2,2,1,1,2,2 }, { 1,1,1,1,1,1,1,1,0,0,0,0,2,2,2,2 }, { 1,1,1,1,0,0,0,0,0,0,0,0,2,2,2,2 },	{ 1,1,1,1,2,2,2,2,0,0,0,0,0,0,0,0 },
        { 1,1,2,0,1,1,2,0,1,1,2,0,1,1,2,0 }, { 0,1,1,2,0,1,1,2,0,1,1,2,0,1,1,2 }, { 0,2,1,1,0,2,1,1,0,2,1,1,0,2,1,1 },	{ 2,0,0,0,2,0,0,0,2,1,1,1,2,1,1,1 },
        { 2,0,1,2,2,0,1,2,2,0,1,2,2,0,1,2 }, { 1,1,1,1,0,0,0,0,2,2,2,2,1,1,1,1 }, { 0,0,2,2,0,0,1,1,0,0,1,1,0,0,2,2 }
    };
    static final int[][] PATTERNS2_MODE7 =
    {
        { 0,0,0,0,1,1,1,1,0,0,0,0,0,0,0,0 }, { 0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0 }, { 1,1,0,0,1,1,0,0,1,0,0,0,0,0,0,0 },	{ 0,0,0,0,0,0,0,1,0,0,1,1,0,0,1,1 },
        { 1,1,1,1,1,1,1,1,0,0,0,0,1,1,1,1 }, { 0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0 }, { 0,0,0,1,0,0,1,1,1,1,1,1,1,1,1,1 },	{ 0,1,1,1,0,0,1,1,0,0,1,1,0,0,1,1 },
        { 1,1,0,0,0,0,0,0,0,0,1,1,1,1,0,0 }, { 0,1,1,1,0,1,1,1,0,0,0,0,0,0,0,0 }, { 0,0,0,0,0,0,0,0,1,1,1,0,1,1,1,0 },	{ 1,1,0,0,0,0,0,0,0,0,0,0,1,1,0,0 },
        { 0,1,1,1,0,0,1,1,0,0,0,0,0,0,0,0 }, { 0,0,0,0,0,0,0,1,1,1,1,1,1,1,1,1 }, { 1,1,1,1,1,1,1,1,1,1,1,1,0,1,1,0 },	{ 1,1,0,0,1,1,0,0,1,1,0,0,1,0,0,0 },
        { 1,1,1,1,1,1,1,1,1,0,0,0,1,0,0,0 }, { 0,0,1,1,0,1,1,0,1,1,0,0,1,0,0,0 }, { 1,1,1,1,0,1,1,1,0,0,0,0,0,0,0,0 }
    };
    static final int[][] ANCHORS2 =
    {
        { 0, 2 }, { 0, 3 }, { 1, 0 }, { 0, 3 }, { 7, 0 }, { 0, 2 }, { 3, 0 }, { 7, 0 },
        { 0, 11 }, { 2, 0 }, { 0, 7 }, { 11, 0 }, { 3, 0 }, { 8, 0 }, { 0, 4 }, { 12, 0 },
        { 1, 0 }, { 8, 0 }, { 0, 1 }, { 0, 2 }, { 0, 4 }, { 8, 0 }, { 1, 0 }, { 0, 2 },
        { 4, 0 }, { 0, 1 }, { 4, 0 }, { 1, 0 }, { 4, 0 }, { 1, 0 }
    };
    static final int[][] ANCHORS3 =
    {
        { 0, 8, 10 },	{ 8, 0, 12 }, { 4, 0, 12 }, { 8, 0, 4 }, { 3, 0, 2 }, { 0, 1, 3 }, { 0, 2, 1 }, { 1, 9, 0 }, { 1, 2, 0 }, { 4, 0, 8 }, { 0, 6, 2 }
    };
    static final int[][] ANCHORS2_MODE7 =
    {
        { 0, 4 }, { 0, 2 }, { 2, 0 }, { 0, 7 }, { 8, 0 }, { 0, 1 }, { 0, 3 }, { 0, 1 }, { 2, 0 }, { 0, 1 }, { 0, 8 }, { 2, 0 }, { 0, 1 }, { 0, 7 }, { 12, 0 }, { 2, 0 }, { 9, 0 }, { 0, 2 }, { 4, 0 }
    };
    static final int[] HUFF_MODES =
    {
        11,0,10,3,11,15,12,7,11,18,10,5,11,14,12,9,11,0,10,4,11,16,12,8,11,18,10,6,11,2,12,13,11,0,10,3,11,17,12,7,11,18,10,5,11,14,12,9,11,0,10,4,11,1,12,8,11,18,10,6,11,2,12,13,11,0,10,3,11,
        19,12,7,11,18,10,5,11,14,12,9,11,0,10,4,11,16,12,8,11,18,10,6,11,2,12,13,11,0,10,3,11,17,12,7,11,18,10,5,11,14,12,9,11,0,10,4,11,1,12,8,11,18,10,6,11,2,12,13
    };

    // ------------------------------------------------------------------ decoding

    private static int bits(byte[] b, int base, int bitOffset, int count) {
        int v = 0;
        for (int i = 0; i < count; i++) {
            int bit = bitOffset + i;
            v |= ((b[base + (bit >> 3)] >> (bit & 7)) & 1) << i;
        }
        return v;
    }

    private static long bits64(byte[] b, int base, int bitOffset, int count) {
        long v = 0;
        for (int i = 0; i < count; i++) {
            int bit = bitOffset + i;
            v |= (long) ((b[base + (bit >> 3)] >> (bit & 7)) & 1) << i;
        }
        return v;
    }

    /** Per-thread scratch for {@link #decodeBlock}. */
    static final class Scratch {
        final int[] endpoints = new int[18];
        final int[] weights = new int[32];
        final int[] tq = new int[8];
        final int[] palette = new int[3 * 32 * 4];
        final int[] lo = new int[4], hi = new int[4];
    }

    /**
     * Decode the block at {@code data[base..base+16)} into 16 RGBA pixels ({@code out}, 64 bytes, row-major).
     *
     * @return false if the block is invalid
     */
    static boolean decodeBlock(byte[] data, int base, Scratch s, byte[] out) {
        int mode = HUFF_MODES[data[base] & 127];
        if (mode >= TOTAL_MODES) return false;
        int bitOfs = HUFF_CODE_SIZES[mode];
        if (mode == SOLID_MODE) {
            int r = bits(data, base, bitOfs, 8), g = bits(data, base, bitOfs + 8, 8), b = bits(data, base, bitOfs + 16, 8),
                    a = bits(data, base, bitOfs + 24, 8);
            for (int i = 0; i < 16; i++) {
                out[i * 4] = (byte) r;
                out[i * 4 + 1] = (byte) g;
                out[i * 4 + 2] = (byte) b;
                out[i * 4 + 3] = (byte) a;
            }
            return true;
        }
        bitOfs += TOTAL_HINT_BITS[mode];
        int subsets = 1, commonPattern = 0;
        switch (mode) {
            case 2, 4, 7, 9, 16 -> {
                commonPattern = bits(data, base, bitOfs, 5);
                bitOfs += 5;
                subsets = 2;
            }
            case 3 -> {
                commonPattern = bits(data, base, bitOfs, 4);
                bitOfs += 4;
                subsets = 3;
            }
            default -> {
            }
        }
        if (subsets == 3 && commonPattern >= PATTERNS3.length) return false;
        if (subsets == 2 && mode == 7 && commonPattern >= PATTERNS2_MODE7.length) return false;
        if (subsets == 2 && mode != 7 && commonPattern >= PATTERNS2.length) return false;
        int planes = PLANES[mode], ccs = -1;
        switch (mode) {
            case 6, 11, 13 -> {
                ccs = bits(data, base, bitOfs, 2);
                bitOfs += 2;
            }
            case 17 -> ccs = 3;
            default -> {
            }
        }
        int comps = COMPS[mode];
        int weightBits = WEIGHT_BITS[mode];
        int totalValues = comps * 2 * subsets;
        int range = ENDPOINT_RANGES[mode];
        int epBits = BISE[range][0], epTrits = BISE[range][1], epQuints = BISE[range][2];
        int[] endpoints = s.endpoints, weights = s.weights, tq = s.tq;

        // endpoints: trit/quint bundles first, then the low bits of every value
        int totalTqs = 0, bundle = 0, mul = 0;
        if (epTrits != 0) {
            totalTqs = (totalValues + 4) / 5;
            bundle = 5;
            mul = 3;
        } else if (epQuints != 0) {
            totalTqs = (totalValues + 2) / 3;
            bundle = 3;
            mul = 5;
        }
        for (int i = 0; i < totalTqs; i++) {
            int n = epTrits != 0 ? 8 : 7;
            if (i == totalTqs - 1) {
                int remaining = totalValues - (totalTqs - 1) * bundle;
                if (epTrits != 0) {
                    n = switch (remaining) {
                        case 1 -> 2;
                        case 2 -> 4;
                        case 3 -> 5;
                        case 4 -> 7;
                        default -> n;
                    };
                } else {
                    n = switch (remaining) {
                        case 1 -> 3;
                        case 2 -> 5;
                        default -> n;
                    };
                }
            }
            tq[i] = bits(data, base, bitOfs, n);
            bitOfs += n;
        }
        int accum = 0, accumRemaining = 0, nextTq = 0;
        for (int i = 0; i < totalValues; i++) {
            int value = bits(data, base, bitOfs, epBits);
            bitOfs += epBits;
            if (totalTqs > 0) {
                if (accumRemaining == 0) {
                    accum = tq[nextTq++];
                    accumRemaining = bundle;
                }
                int v = accum % mul;
                accum /= mul;
                accumRemaining--;
                value |= v << epBits;
            }
            endpoints[i] = value & 0xFF;
        }

        int[] pattern;
        int[] anchors;
        if (subsets == 3) {
            pattern = PATTERNS3[commonPattern];
            anchors = ANCHORS3[commonPattern];
        } else if (subsets == 2) {
            pattern = mode == 7 ? PATTERNS2_MODE7[commonPattern] : PATTERNS2[commonPattern];
            anchors = mode == 7 ? ANCHORS2_MODE7[commonPattern] : ANCHORS2[commonPattern];
        } else {
            pattern = ZERO_PATTERN;
            anchors = ZERO_PATTERN;
        }

        // weights: anchor pixels (and the first weight of each plane) drop their top bit
        if (mode == 18) {
            for (int i = 0; i < 16; i++) {
                int n = i == 0 ? weightBits - 1 : weightBits;
                weights[i] = bits(data, base, bitOfs, n);
                bitOfs += n;
            }
        } else {
            long w = bits64(data, base, bitOfs, Math.min(64, 128 - bitOfs));
            int at = 0;
            int mask = (1 << weightBits) - 1, anchorMask = (1 << (weightBits - 1)) - 1;
            if (planes == 2) {
                weights[0] = (int) (w >>> at) & anchorMask;
                at += weightBits - 1;
                weights[1] = (int) (w >>> at) & anchorMask;
                at += weightBits - 1;
                for (int i = 2; i < 32; i++) {
                    weights[i] = (int) (w >>> at) & mask;
                    at += weightBits;
                }
            } else if (subsets == 1) {
                weights[0] = (int) w & anchorMask;
                at = weightBits - 1;
                for (int i = 1; i < 16; i++) {
                    weights[i] = (int) (w >>> at) & mask;
                    at += weightBits;
                }
            } else {
                int a0 = anchor(anchors, 0), a1 = anchor(anchors, 1), a2 = anchor(anchors, 2);
                for (int i = 0; i < 16; i++) {
                    if (i == a0 || i == a1 || i == a2) {
                        weights[i] = (int) (w >>> at) & anchorMask;
                        at += weightBits - 1;
                    } else {
                        weights[i] = (int) (w >>> at) & mask;
                        at += weightBits;
                    }
                }
            }
        }

        // endpoint colours, then the colour of every weight level per subset
        int c4 = Math.min(4, comps);
        int[] unq = UNQUANT[range];
        int[] table = WEIGHT_TABLES[weightBits];
        int levels = 1 << weightBits;
        int[] palette = s.palette, lo = s.lo, hi = s.hi;
        for (int sub = 0; sub < subsets; sub++) {
            int e = sub * comps * 2;
            if (c4 == 2) {
                int ll = unq[endpoints[e]], lh = unq[endpoints[e + 1]], al = unq[endpoints[e + 2]], ah = unq[endpoints[e + 3]];
                lo[0] = lo[1] = lo[2] = ll;
                hi[0] = hi[1] = hi[2] = lh;
                lo[3] = al;
                hi[3] = ah;
            } else {
                for (int c = 0; c < 4; c++) {
                    if (c < c4) {
                        lo[c] = unq[endpoints[e + c * 2]];
                        hi[c] = unq[endpoints[e + c * 2 + 1]];
                    } else {
                        lo[c] = hi[c] = 255;
                    }
                }
            }
            for (int l = 0; l < levels; l++) {
                int p = (sub * 32 + l) * 4;
                for (int c = 0; c < 4; c++) {
                    palette[p + c] = c4 == 2 || c < c4 ? interpolate(lo[c], hi[c], table[l]) : 255;
                }
            }
        }

        for (int i = 0; i < 16; i++) {
            if (planes == 1) {
                int p = (pattern[i] * 32 + weights[i]) * 4;
                out[i * 4] = (byte) palette[p];
                out[i * 4 + 1] = (byte) palette[p + 1];
                out[i * 4 + 2] = (byte) palette[p + 2];
                out[i * 4 + 3] = (byte) palette[p + 3];
            } else {
                int p0 = weights[i * 2] * 4, p1 = weights[i * 2 + 1] * 4;
                for (int c = 0; c < 4; c++) out[i * 4 + c] = (byte) palette[(c == ccs ? p1 : p0) + c];
            }
        }
        return true;
    }

    private static int anchor(int[] anchors, int i) {
        return i < anchors.length ? anchors[i] : 0;
    }
}
