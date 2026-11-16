package dev.denismasterherobrine.animatio.gltf.texture;

/**
 * LSB-first bit reader with the Basis Universal Huffman and VLC codes (a port of {@code basist::bitwise_decoder}).
 * Reading past the end yields zero bits, like the reference; callers bound their loops by the data they expect.
 */
final class BitReader {
    private final byte[] data;
    private int pos;
    private final int end;
    private long buf;
    private int bits;

    BitReader(byte[] data, int offset, int length) throws Ktx2Exception {
        if (offset < 0 || length < 0 || offset + length > data.length) throw new Ktx2Exception("Bit stream exceeds the file");
        this.data = data;
        this.pos = offset;
        this.end = offset + length;
    }

    private void fill(int n) {
        while (bits < n) {
            long c = pos < end ? data[pos++] & 0xFF : 0;
            buf |= c << bits;
            bits += 8;
        }
    }

    int get(int n) {
        if (n == 0) return 0;
        fill(n);
        int result = (int) (buf & ((1L << n) - 1));
        buf >>>= n;
        bits -= n;
        return result;
    }

    int decodeVlc(int chunkBits) throws Ktx2Exception {
        int chunkSize = 1 << chunkBits, chunkMask = chunkSize - 1;
        int v = 0, ofs = 0;
        while (true) {
            int s = get(chunkBits + 1);
            v |= (s & chunkMask) << ofs;
            ofs += chunkBits;
            if ((s & chunkSize) == 0) break;
            if (ofs >= 32) throw new Ktx2Exception("Malformed variable-length code");
        }
        return v;
    }

    int decode(Huffman table) throws Ktx2Exception {
        if (table.lookup == null) throw new Ktx2Exception("Use of an empty Huffman table");
        fill(Huffman.MAX_BITS);
        int entry = table.lookup[(int) (buf & table.mask)];
        // unmapped bit patterns read as symbol 0 without consuming bits, like the reference decoder
        int len = entry >>> 16;
        buf >>>= len;
        bits -= len;
        return entry & 0xFFFF;
    }

    /** {@code bitwise_decoder::read_huffman_table}: code lengths coded with a small Huffman code of their own. */
    Huffman readHuffman() throws Ktx2Exception {
        int totalUsed = get(14);
        if (totalUsed == 0) return Huffman.EMPTY;
        if (totalUsed > 16384) throw new Ktx2Exception("Too many Huffman symbols");
        int[] lengthCodeSizes = new int[21];
        int numLengthCodes = get(5);
        if (numLengthCodes < 1 || numLengthCodes > 21) throw new Ktx2Exception("Malformed Huffman table");
        for (int i = 0; i < numLengthCodes; i++) lengthCodeSizes[SORTED_CODELENGTH_CODES[i]] = get(3);
        Huffman lengthTable = Huffman.build(lengthCodeSizes, 21);
        if (lengthTable.lookup == null) throw new Ktx2Exception("Malformed Huffman table");
        int[] sizes = new int[totalUsed];
        int cur = 0;
        while (cur < totalUsed) {
            int c = decode(lengthTable);
            if (c <= 16) {
                sizes[cur++] = c;
            } else if (c == 17) {
                cur += get(3) + 3;
            } else if (c == 18) {
                cur += get(7) + 11;
            } else {
                if (cur == 0) throw new Ktx2Exception("Malformed Huffman table");
                int count = c == 19 ? get(2) + 3 : get(7) + 7;
                int prev = sizes[cur - 1];
                if (prev == 0 || cur + count > totalUsed) throw new Ktx2Exception("Malformed Huffman table");
                for (int k = 0; k < count; k++) sizes[cur++] = prev;
            }
        }
        if (cur != totalUsed) throw new Ktx2Exception("Malformed Huffman table");
        Huffman table = Huffman.build(sizes, totalUsed);
        if (table.lookup == null) throw new Ktx2Exception("Malformed Huffman table");
        return table;
    }

    private static final int[] SORTED_CODELENGTH_CODES = {17, 18, 19, 20, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15, 16};

    /**
     * A canonical Huffman code (deflate-style assignment, codes bit-reversed for LSB-first reading) decoded through a
     * single lookup table indexed by the next {@code maxLength} bits. Entries are {@code length << 16 | symbol}.
     */
    static final class Huffman {
        static final int MAX_BITS = 16;
        static final Huffman EMPTY = new Huffman(null, 0, 0);
        final int[] lookup;
        final long mask;
        final int symbols;

        private Huffman(int[] lookup, int maxLength, int symbols) {
            this.lookup = lookup;
            this.mask = (1L << maxLength) - 1;
            this.symbols = symbols;
        }

        boolean isEmpty() {
            return lookup == null;
        }

        static Huffman build(int[] sizes, int count) throws Ktx2Exception {
            int[] perLength = new int[MAX_BITS + 1];
            int maxLength = 0, used = 0;
            for (int i = 0; i < count; i++) {
                int s = sizes[i];
                if (s > MAX_BITS) throw new Ktx2Exception("Huffman code longer than 16 bits");
                if (s > 0) {
                    perLength[s]++;
                    used++;
                    maxLength = Math.max(maxLength, s);
                }
            }
            if (used == 0) return EMPTY;
            // the reference only accepts complete codes (or a single symbol)
            long kraft = 0;
            for (int len = 1; len <= MAX_BITS; len++) kraft += (long) perLength[len] << (MAX_BITS - len);
            if (used > 1 && kraft != 1L << MAX_BITS) throw new Ktx2Exception("Incomplete or over-subscribed Huffman code");
            int[] next = new int[MAX_BITS + 1];
            int code = 0;
            for (int len = 1; len <= MAX_BITS; len++) {
                next[len] = code;
                code = (code + perLength[len]) << 1;
            }
            int size = 1 << maxLength;
            int[] lookup = new int[size];
            for (int sym = 0; sym < count; sym++) {
                int len = sizes[sym];
                if (len == 0) continue;
                int c = next[len]++;
                int rev = 0;
                for (int l = 0; l < len; l++, c >>= 1) rev = (rev << 1) | (c & 1);
                int entry = len << 16 | sym;
                for (int k = rev; k < size; k += 1 << len) {
                    if (lookup[k] != 0) throw new Ktx2Exception("Invalid Huffman code lengths");
                    lookup[k] = entry;
                }
            }
            return new Huffman(lookup, maxLength, count);
        }
    }
}
