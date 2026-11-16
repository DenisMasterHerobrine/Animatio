package dev.denismasterherobrine.animatio.gltf.texture;

import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes KTX2 textures ({@code KHR_texture_basisu} and plain KTX2) to RGBA8: the base mip level of layer 0, face 0.
 * <p>
 * Supported: Basis Universal ETC1S (BasisLZ supercompression, with an optional alpha slice), UASTC LDR 4x4, and the
 * uncompressed 8-bit formats R8, R8G8, R8G8B8, R8G8B8A8 and B8G8R8A8 (UNORM or sRGB), each optionally
 * supercompressed with Zstandard or ZLIB. {@code KTXorientation} metadata that stores rows bottom-up is honoured.
 * Thread-safe: every call works on its own state.
 */
public final class Ktx2Decoder {
    private static final byte[] IDENTIFIER = {(byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, '\r', '\n', 0x1A, '\n'};
    private static final int MAX_DIMENSION = 16384;
    private static final int SUPERCOMPRESSION_NONE = 0, SUPERCOMPRESSION_BASISLZ = 1, SUPERCOMPRESSION_ZSTD = 2, SUPERCOMPRESSION_ZLIB = 3;
    private static final int MODEL_ETC1S = 163, MODEL_UASTC = 166;

    private Ktx2Decoder() {
    }

    /** RGBA8, row-major, first row = top of the image (glTF convention), 4 bytes per pixel. */
    public record Image(int width, int height, byte[] rgba) {
    }

    /** True if {@code data} starts with the KTX2 file identifier. */
    public static boolean isKtx2(byte[] data) {
        if (data.length < IDENTIFIER.length) return false;
        for (int i = 0; i < IDENTIFIER.length; i++) if (data[i] != IDENTIFIER[i]) return false;
        return true;
    }

    public static Image decode(byte[] data) throws Ktx2Exception {
        try {
            return decodeUnchecked(data);
        } catch (Ktx2Exception e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Ktx2Exception("Malformed KTX2 file: " + e, e);
        }
    }

    private static Image decodeUnchecked(byte[] data) throws Ktx2Exception {
        if (!isKtx2(data)) throw new Ktx2Exception("Not a KTX2 file");
        if (data.length < 80) throw new Ktx2Exception("KTX2 header is truncated");
        int vkFormat = (int) u32(data, 12);
        long width = u32(data, 20), height = u32(data, 24), depth = u32(data, 28);
        long layers = u32(data, 32), faces = u32(data, 36), levels = u32(data, 40);
        int supercompression = (int) u32(data, 44);
        long dfdOffset = u32(data, 48), dfdLength = u32(data, 52), kvdOffset = u32(data, 56), kvdLength = u32(data, 60);
        long sgdOffset = u64(data, 64), sgdLength = u64(data, 72);
        if (width == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) throw new Ktx2Exception("Unsupported KTX2 size " + width + "x" + height);
        if (height == 0) height = 1; // 1D texture
        if (depth > 1) throw new Ktx2Exception("3D KTX2 textures are not supported");
        if (faces != 1 && faces != 6) throw new Ktx2Exception("Invalid KTX2 face count " + faces);
        if (levels > 32 || layers > 4096) throw new Ktx2Exception("Invalid KTX2 level or layer count");
        int levelCount = (int) Math.max(1, levels);
        if (80L + 24L * levelCount > data.length) throw new Ktx2Exception("KTX2 level index is truncated");
        long levelOffset = u64(data, 80), levelLength = u64(data, 88), levelUncompressed = u64(data, 96);
        if (levelOffset < 0 || levelLength < 0 || levelOffset + levelLength > data.length) throw new Ktx2Exception("KTX2 level 0 exceeds the file");
        if (dfdOffset + dfdLength > data.length || kvdOffset + kvdLength > data.length) throw new Ktx2Exception("KTX2 metadata exceeds the file");

        Dfd dfd = Dfd.parse(data, (int) dfdOffset, (int) dfdLength);
        boolean flipY = orientationUp(data, (int) kvdOffset, (int) kvdLength);
        int w = (int) width, h = (int) height;
        byte[] rgba = new byte[w * h * 4];

        if (dfd.model == MODEL_ETC1S) {
            if (supercompression != SUPERCOMPRESSION_BASISLZ) throw new Ktx2Exception("ETC1S data without BasisLZ supercompression");
            int imageCount = (int) (Math.max(1, layers) * faces * levelCount);
            Etc1sDecoder.ImageDesc[] descs = new Etc1sDecoder.ImageDesc[imageCount];
            Etc1sDecoder etc1s = new Etc1sDecoder(data, sgdOffset, sgdLength, imageCount, descs);
            Etc1sDecoder.ImageDesc d = descs[0]; // level 0, layer 0, face 0
            etc1s.decodeSlice(data, levelOffset + d.rgbOffset(), d.rgbLength(), w, h, false, rgba);
            if (dfd.samples >= 2 && d.alphaLength() > 0) {
                etc1s.decodeSlice(data, levelOffset + d.alphaOffset(), d.alphaLength(), w, h, true, rgba);
                if (dfd.channel1 == 4 /* GGG: two-channel data in R and G */) moveAlphaToGreen(rgba);
            }
        } else {
            byte[] level = levelData(data, supercompression, levelOffset, levelLength, levelUncompressed);
            if (dfd.model == MODEL_UASTC) decodeUastc(level, w, h, rgba);
            else decodeUncompressed(vkFormat, level, w, h, rgba);
        }
        if (flipY) flipRows(rgba, w, h);
        return new Image(w, h, rgba);
    }

    /** Level 0 bytes after undoing Zstandard or ZLIB supercompression. */
    private static byte[] levelData(byte[] data, int scheme, long offset, long length, long uncompressed) throws Ktx2Exception {
        int off = (int) offset, len = (int) length;
        switch (scheme) {
            case SUPERCOMPRESSION_NONE -> {
                byte[] out = new byte[len];
                System.arraycopy(data, off, out, 0, len);
                return out;
            }
            case SUPERCOMPRESSION_ZSTD -> {
                if (uncompressed <= 0 || uncompressed > (long) MAX_DIMENSION * MAX_DIMENSION * 4) throw new Ktx2Exception("Invalid uncompressed level size");
                byte[] out = new byte[(int) uncompressed];
                try {
                    int n = new io.airlift.compress.zstd.ZstdDecompressor().decompress(data, off, len, out, 0, out.length);
                    if (n != out.length) throw new Ktx2Exception("Zstandard level data has the wrong size");
                } catch (RuntimeException e) {
                    throw new Ktx2Exception("Corrupt Zstandard level data: " + e.getMessage(), e);
                }
                return out;
            }
            case SUPERCOMPRESSION_ZLIB -> {
                if (uncompressed <= 0 || uncompressed > (long) MAX_DIMENSION * MAX_DIMENSION * 4) throw new Ktx2Exception("Invalid uncompressed level size");
                byte[] out = new byte[(int) uncompressed];
                Inflater inflater = new Inflater();
                try {
                    inflater.setInput(data, off, len);
                    int n = inflater.inflate(out);
                    if (n != out.length) throw new Ktx2Exception("ZLIB level data has the wrong size");
                } catch (DataFormatException e) {
                    throw new Ktx2Exception("Corrupt ZLIB level data", e);
                } finally {
                    inflater.end();
                }
                return out;
            }
            default -> throw new Ktx2Exception("Unsupported KTX2 supercompression scheme " + scheme);
        }
    }

    private static void decodeUastc(byte[] level, int w, int h, byte[] rgba) throws Ktx2Exception {
        int bx = (w + 3) >> 2, by = (h + 3) >> 2;
        if ((long) bx * by * 16 > level.length) throw new Ktx2Exception("UASTC level data is truncated");
        UastcDecoder.Scratch scratch = new UastcDecoder.Scratch();
        byte[] block = new byte[64];
        for (int y = 0; y < by; y++) {
            for (int x = 0; x < bx; x++) {
                if (!UastcDecoder.decodeBlock(level, (y * bx + x) * 16, scratch, block)) {
                    // invalid block: magenta, like the reference transcoder's error colour
                    for (int i = 0; i < 16; i++) {
                        block[i * 4] = (byte) 255;
                        block[i * 4 + 1] = 0;
                        block[i * 4 + 2] = (byte) 255;
                        block[i * 4 + 3] = (byte) 255;
                    }
                }
                int maxX = Math.min(4, w - x * 4), maxY = Math.min(4, h - y * 4);
                for (int py = 0; py < maxY; py++) {
                    System.arraycopy(block, py * 16, rgba, ((y * 4 + py) * w + x * 4) * 4, maxX * 4);
                }
            }
        }
    }

    private static void decodeUncompressed(int vkFormat, byte[] level, int w, int h, byte[] rgba) throws Ktx2Exception {
        int comps;
        boolean bgr = false;
        switch (vkFormat) {
            case 9, 15 -> comps = 1;            // R8_UNORM, R8_SRGB
            case 16, 22 -> comps = 2;           // R8G8_UNORM, R8G8_SRGB
            case 23, 29 -> comps = 3;           // R8G8B8_UNORM, R8G8B8_SRGB
            case 30, 36 -> {                    // B8G8R8_UNORM, B8G8R8_SRGB
                comps = 3;
                bgr = true;
            }
            case 37, 43 -> comps = 4;           // R8G8B8A8_UNORM, R8G8B8A8_SRGB
            case 44, 50 -> {                    // B8G8R8A8_UNORM, B8G8R8A8_SRGB
                comps = 4;
                bgr = true;
            }
            default -> throw new Ktx2Exception("Unsupported KTX2 vkFormat " + vkFormat + " (supported: Basis Universal ETC1S/UASTC and 8-bit R/RG/RGB/RGBA)");
        }
        // rows are tightly packed for these formats (KTX2 requires no row padding beyond the element size)
        if ((long) w * h * comps > level.length) throw new Ktx2Exception("KTX2 level data is truncated");
        for (int i = 0, n = w * h; i < n; i++) {
            int s = i * comps, d = i * 4;
            byte r = level[s], g = comps > 1 ? level[s + 1] : 0, b = comps > 2 ? level[s + 2] : 0;
            if (comps == 1) g = b = r;
            if (bgr) {
                byte t = r;
                r = b;
                b = t;
            }
            rgba[d] = r;
            rgba[d + 1] = g;
            rgba[d + 2] = b;
            rgba[d + 3] = comps == 4 ? level[s + 3] : (byte) 255;
        }
    }

    private static void moveAlphaToGreen(byte[] rgba) {
        for (int p = 0; p < rgba.length; p += 4) {
            rgba[p + 1] = rgba[p + 3];
            rgba[p + 3] = (byte) 255;
        }
    }

    private static void flipRows(byte[] rgba, int w, int h) {
        int row = w * 4;
        byte[] tmp = new byte[row];
        for (int y = 0; y < h / 2; y++) {
            int a = y * row, b = (h - 1 - y) * row;
            System.arraycopy(rgba, a, tmp, 0, row);
            System.arraycopy(rgba, b, rgba, a, row);
            System.arraycopy(tmp, 0, rgba, b, row);
        }
    }

    /** True if {@code KTXorientation} says rows are stored bottom-up ("ru", "u" for the second axis). */
    private static boolean orientationUp(byte[] d, int offset, int length) {
        int p = offset, end = offset + length;
        while (p + 4 <= end) {
            int size = (int) u32(d, p);
            int start = p + 4;
            if (size <= 0 || start + size > end) break;
            int nul = start;
            while (nul < start + size && d[nul] != 0) nul++;
            String key = new String(d, start, nul - start, StandardCharsets.UTF_8);
            if (key.equals("KTXorientation") && nul + 1 < start + size) {
                String value = new String(d, nul + 1, start + size - nul - 1, StandardCharsets.UTF_8).replace("\0", "");
                return value.length() >= 2 && value.charAt(1) == 'u';
            }
            p = start + ((size + 3) & ~3);
        }
        return false;
    }

    /** The parts of the basic data format descriptor block the decoder needs. */
    private record Dfd(int model, int samples, int channel0, int channel1) {
        static Dfd parse(byte[] d, int offset, int length) throws Ktx2Exception {
            if (length < 28 + 16) throw new Ktx2Exception("KTX2 data format descriptor is truncated");
            int block = offset + 4; // skip dfdTotalSize
            int blockSize = (int) (u32(d, block + 4) >>> 16);
            int model = d[block + 8] & 0xFF;
            int samples = Math.max(0, (blockSize - 24) / 16);
            int channel0 = samples > 0 ? (d[block + 24 + 3] & 0x0F) : 0;
            int channel1 = samples > 1 && block + 40 + 3 < offset + length ? (d[block + 40 + 3] & 0x0F) : -1;
            return new Dfd(model, samples, channel0, channel1);
        }
    }

    private static long u32(byte[] d, int o) {
        return Etc1sDecoder.u32(d, o);
    }

    private static long u64(byte[] d, int o) {
        long lo = u32(d, o), hi = u32(d, o + 4);
        return hi > Integer.MAX_VALUE ? Long.MAX_VALUE : lo | hi << 32;
    }
}
