package dev.denismasterherobrine.animatio.gltf.texture;

/**
 * BasisLZ / ETC1S decoding to RGBA8, ported from the Basis Universal reference transcoder
 * ({@code basisu_lowlevel_etc1s_transcoder::decode_palettes}, {@code decode_tables} and {@code transcode_slice}).
 * <p>
 * The supercompression global data holds the endpoint and selector codebooks and the Huffman tables shared by every
 * slice; each image has an RGB slice and optionally an alpha slice, decoded independently.
 * <p>
 * Derived from the Basis Universal transcoder, Copyright Binomial LLC, Apache License 2.0
 * (https://github.com/BinomialLLC/basis_universal).
 */
final class Etc1sDecoder {
    private static final int[][] INTEN_TABLES = {
            {-8, -2, 2, 8}, {-17, -5, 5, 17}, {-29, -9, 9, 29}, {-42, -13, 13, 42},
            {-60, -18, 18, 60}, {-80, -24, 24, 80}, {-106, -33, 33, 106}, {-183, -47, 47, 183}};
    private static final int ENDPOINT_PRED_REPEAT_LAST_SYMBOL = 256;
    private static final int ENDPOINT_PRED_MIN_REPEAT_COUNT = 3;
    private static final int ENDPOINT_PRED_COUNT_VLC_BITS = 4;
    private static final int SELECTOR_HISTORY_BUF_RLE_COUNT_THRESH = 3;
    private static final int SELECTOR_HISTORY_BUF_RLE_COUNT_TOTAL = 64;

    /** Per endpoint: 4 colours (RGB packed as 0xRRGGBB) for the 4 selector values. */
    private final int[] endpointColors;
    /** Per selector: 4 rows, each a byte of four 2-bit selectors (x = 0 in the low bits). */
    private final int[] selectors;
    private final int numEndpoints, numSelectors;
    private final BitReader.Huffman endpointPredModel, deltaEndpointModel, selectorModel, selectorHistoryRleModel;
    private final int selectorHistorySize;

    /**
     * @param data the file; {@code sgdOffset} / {@code sgdLength} locate the supercompression global data
     * @param imageCount number of image descriptors in the global data
     */
    Etc1sDecoder(byte[] data, long sgdOffset, long sgdLength, int imageCount, ImageDesc[] descsOut) throws Ktx2Exception {
        if (sgdOffset < 0 || sgdLength < 20 || sgdOffset + sgdLength > data.length) throw new Ktx2Exception("Missing BasisLZ global data");
        int o = (int) sgdOffset;
        numEndpoints = u16(data, o);
        numSelectors = u16(data, o + 2);
        long endpointsLength = u32(data, o + 4), selectorsLength = u32(data, o + 8), tablesLength = u32(data, o + 12),
                extendedLength = u32(data, o + 16);
        if (numEndpoints == 0 || numSelectors == 0 || endpointsLength == 0 || selectorsLength == 0 || tablesLength == 0) {
            throw new Ktx2Exception("Empty BasisLZ codebooks");
        }
        long needed = 20L + 20L * imageCount + endpointsLength + selectorsLength + tablesLength + extendedLength;
        if (needed > sgdLength) throw new Ktx2Exception("BasisLZ global data is truncated");
        int d = o + 20;
        for (int i = 0; i < imageCount; i++, d += 20) {
            descsOut[i] = new ImageDesc(u32(data, d), u32(data, d + 4), u32(data, d + 8), u32(data, d + 12), u32(data, d + 16));
        }
        int endpointsAt = d, selectorsAt = (int) (endpointsAt + endpointsLength), tablesAt = (int) (selectorsAt + selectorsLength);

        // tables
        BitReader t = new BitReader(data, tablesAt, (int) tablesLength);
        endpointPredModel = t.readHuffman();
        deltaEndpointModel = t.readHuffman();
        selectorModel = t.readHuffman();
        selectorHistoryRleModel = t.readHuffman();
        if (endpointPredModel.isEmpty() || deltaEndpointModel.isEmpty() || selectorModel.isEmpty() || selectorHistoryRleModel.isEmpty()) {
            throw new Ktx2Exception("Missing BasisLZ Huffman tables");
        }
        selectorHistorySize = t.get(13);
        if (selectorHistorySize == 0) throw new Ktx2Exception("Invalid selector history size");

        // endpoint codebook
        BitReader e = new BitReader(data, endpointsAt, (int) endpointsLength);
        BitReader.Huffman delta0 = e.readHuffman(), delta1 = e.readHuffman(), delta2 = e.readHuffman(), intenModel = e.readHuffman();
        if (delta0.isEmpty() || delta1.isEmpty() || delta2.isEmpty() || intenModel.isEmpty()) throw new Ktx2Exception("Missing endpoint tables");
        boolean grayscale = e.get(1) != 0;
        endpointColors = new int[numEndpoints * 4];
        int[] prev = {16, 16, 16};
        int prevInten = 0;
        int[] c5 = new int[3];
        for (int i = 0; i < numEndpoints; i++) {
            int inten = (e.decode(intenModel) + prevInten) & 7;
            prevInten = inten;
            for (int c = 0; c < (grayscale ? 1 : 3); c++) {
                int delta;
                if (prev[c] <= 9) delta = e.decode(delta0);
                else if (prev[c] <= 21) delta = e.decode(delta1);
                else delta = e.decode(delta2);
                int v = (prev[c] + delta) & 31;
                c5[c] = v;
                prev[c] = v;
            }
            if (grayscale) c5[1] = c5[2] = c5[0];
            int r = (c5[0] << 3) | (c5[0] >> 2), g = (c5[1] << 3) | (c5[1] >> 2), b = (c5[2] << 3) | (c5[2] >> 2);
            int[] table = INTEN_TABLES[inten];
            for (int s = 0; s < 4; s++) {
                endpointColors[i * 4 + s] = clamp(r + table[s]) << 16 | clamp(g + table[s]) << 8 | clamp(b + table[s]);
            }
        }

        // selector codebook
        BitReader s = new BitReader(data, selectorsAt, (int) selectorsLength);
        if (s.get(1) != 0) throw new Ktx2Exception("Global selector codebooks are not supported");
        if (s.get(1) != 0) throw new Ktx2Exception("Hybrid selector codebooks are not supported");
        selectors = new int[numSelectors * 4];
        if (s.get(1) != 0) { // raw
            for (int i = 0; i < numSelectors * 4; i++) selectors[i] = s.get(8);
        } else {
            BitReader.Huffman deltaModel = s.readHuffman();
            if (numSelectors > 1 && deltaModel.isEmpty()) throw new Ktx2Exception("Missing selector delta table");
            int[] prevBytes = new int[4];
            for (int i = 0; i < numSelectors; i++) {
                for (int j = 0; j < 4; j++) {
                    int cur = i == 0 ? s.get(8) : (s.decode(deltaModel) ^ prevBytes[j]) & 0xFF;
                    prevBytes[j] = cur;
                    selectors[i * 4 + j] = cur;
                }
            }
        }
    }

    /** One image's slices, offsets relative to the start of its mip level's data. */
    record ImageDesc(long flags, long rgbOffset, long rgbLength, long alphaOffset, long alphaLength) {
    }

    /**
     * Decode one slice into {@code rgba} ({@code width * height * 4}, row-major).
     *
     * @param alpha decode into the alpha channel (from the endpoints' green) instead of RGB
     */
    void decodeSlice(byte[] data, long offset, long length, int width, int height, boolean alpha, byte[] rgba) throws Ktx2Exception {
        if (offset < 0 || length <= 0 || offset + length > data.length) throw new Ktx2Exception("ETC1S slice exceeds the file");
        BitReader r = new BitReader(data, (int) offset, (int) length);
        int bx = (width + 3) >> 2, by = (height + 3) >> 2;
        int totalBlocks = bx * by;
        // two rows of predictor state: endpoint index and the pending predictor bits for the odd row
        int[][] predEndpoint = {new int[bx], new int[bx]};
        int[][] predBits = {new int[bx], new int[bx]};
        int[] history = new int[selectorHistorySize];
        int rover = selectorHistorySize / 2;
        int curSelectorRle = 0;
        int curPredBits = 0, prevPredSym = 0, predRepeat = 0, prevEndpoint = 0;
        int historyFirst = numSelectors, historyRle = selectorHistorySize + historyFirst;

        for (int y = 0; y < by; y++) {
            int cur = y & 1;
            for (int x = 0; x < bx; x++) {
                if ((x & 1) == 0) {
                    if ((y & 1) == 0) {
                        if (predRepeat > 0) {
                            predRepeat--;
                            curPredBits = prevPredSym;
                        } else {
                            curPredBits = r.decode(endpointPredModel);
                            if (curPredBits == ENDPOINT_PRED_REPEAT_LAST_SYMBOL) {
                                predRepeat = r.decodeVlc(ENDPOINT_PRED_COUNT_VLC_BITS) + ENDPOINT_PRED_MIN_REPEAT_COUNT - 1;
                                curPredBits = prevPredSym;
                            } else {
                                prevPredSym = curPredBits;
                            }
                        }
                        predBits[cur ^ 1][x] = curPredBits >>> 4;
                    } else {
                        curPredBits = predBits[cur][x];
                    }
                }
                int pred = curPredBits & 3;
                curPredBits >>>= 2;
                int endpoint;
                switch (pred) {
                    case 0 -> {
                        if (x == 0) throw new Ktx2Exception("Invalid ETC1S endpoint prediction");
                        endpoint = prevEndpoint;
                    }
                    case 1 -> {
                        if (y == 0) throw new Ktx2Exception("Invalid ETC1S endpoint prediction");
                        endpoint = predEndpoint[cur ^ 1][x];
                    }
                    case 2 -> {
                        if (x == 0 || y == 0) throw new Ktx2Exception("Invalid ETC1S endpoint prediction");
                        endpoint = predEndpoint[cur ^ 1][x - 1];
                    }
                    default -> {
                        endpoint = r.decode(deltaEndpointModel) + prevEndpoint;
                        if (endpoint >= numEndpoints) endpoint -= numEndpoints;
                    }
                }
                if (endpoint < 0 || endpoint >= numEndpoints) throw new Ktx2Exception("ETC1S endpoint index out of range");
                predEndpoint[cur][x] = endpoint;
                prevEndpoint = endpoint;

                int selectorSym;
                if (curSelectorRle > 0) {
                    curSelectorRle--;
                    selectorSym = numSelectors;
                } else {
                    selectorSym = r.decode(selectorModel);
                    if (selectorSym == historyRle) {
                        int run = r.decode(selectorHistoryRleModel);
                        curSelectorRle = run == SELECTOR_HISTORY_BUF_RLE_COUNT_TOTAL - 1
                                ? r.decodeVlc(7) + SELECTOR_HISTORY_BUF_RLE_COUNT_THRESH
                                : run + SELECTOR_HISTORY_BUF_RLE_COUNT_THRESH;
                        if (curSelectorRle > totalBlocks) throw new Ktx2Exception("Corrupt ETC1S selector run");
                        selectorSym = numSelectors;
                        curSelectorRle--;
                    }
                }
                int selector;
                if (selectorSym >= numSelectors) {
                    int index = selectorSym - numSelectors;
                    if (index >= selectorHistorySize) throw new Ktx2Exception("Corrupt ETC1S selector history index");
                    selector = history[index];
                    if (index != 0) { // move toward the front
                        int a = history[index / 2];
                        history[index / 2] = history[index];
                        history[index] = a;
                    }
                } else {
                    selector = selectorSym;
                    history[rover++] = selector;
                    if (rover == selectorHistorySize) rover = selectorHistorySize / 2;
                }
                if (selector < 0 || selector >= numSelectors) throw new Ktx2Exception("ETC1S selector index out of range");

                // write the block
                int maxX = Math.min(4, width - x * 4), maxY = Math.min(4, height - y * 4);
                int colors = endpoint * 4;
                for (int py = 0; py < maxY; py++) {
                    int row = selectors[selector * 4 + py];
                    int p = ((y * 4 + py) * width + x * 4) * 4;
                    for (int px = 0; px < maxX; px++, p += 4) {
                        int c = endpointColors[colors + ((row >> (px * 2)) & 3)];
                        if (alpha) {
                            rgba[p + 3] = (byte) (c >> 8);
                        } else {
                            rgba[p] = (byte) (c >> 16);
                            rgba[p + 1] = (byte) (c >> 8);
                            rgba[p + 2] = (byte) c;
                            rgba[p + 3] = (byte) 255;
                        }
                    }
                }
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8;
    }

    static long u32(byte[] d, int o) {
        return ((d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (long) (d[o + 3] & 0xFF) << 24);
    }
}
