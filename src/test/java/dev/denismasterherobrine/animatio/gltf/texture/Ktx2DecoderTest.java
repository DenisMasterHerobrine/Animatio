package dev.denismasterherobrine.animatio.gltf.texture;

import io.airlift.compress.zstd.ZstdCompressor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KTX2 decoding against real files from KTX-Software (see {@code resources/ktx2/SOURCES.md}): the colour grid is
 * stored losslessly (RGBA8 + Zstandard), as ETC1S and as UASTC. The lossy decodes must reproduce the lossless
 * image with the quality those codecs reach (a wrong bit anywhere in the bitstream parsing yields garbage instead).
 */
class Ktx2DecoderTest {
    private static byte[] resource(String name) throws IOException {
        try (InputStream in = Ktx2DecoderTest.class.getResourceAsStream("/ktx2/" + name)) {
            if (in == null) throw new IOException("missing test resource " + name);
            return in.readAllBytes();
        }
    }

    private static double psnr(Ktx2Decoder.Image a, Ktx2Decoder.Image b) {
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        double se = 0;
        long n = 0;
        for (int i = 0; i < a.rgba().length; i += 4) {
            for (int c = 0; c < 3; c++) {
                int d = (a.rgba()[i + c] & 0xFF) - (b.rgba()[i + c] & 0xFF);
                se += d * d;
                n++;
            }
        }
        return se == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / (se / n));
    }

    @Test
    void decodesLosslessZstdReference() throws IOException {
        byte[] file = resource("color_grid_zstd_5.ktx2");
        assertTrue(Ktx2Decoder.isKtx2(file));
        Ktx2Decoder.Image img = Ktx2Decoder.decode(file);
        assertEquals(1024, img.width());
        assertEquals(1024, img.height());
        assertEquals(1024 * 1024 * 4, img.rgba().length);
        for (int i = 3; i < img.rgba().length; i += 4) assertEquals((byte) 255, img.rgba()[i]);
    }

    @Test
    void etc1sMatchesReference() throws IOException {
        Ktx2Decoder.Image ref = Ktx2Decoder.decode(resource("color_grid_zstd_5.ktx2"));
        Ktx2Decoder.Image etc1s = Ktx2Decoder.decode(resource("color_grid_blze.ktx2"));
        double db = psnr(etc1s, ref);
        assertTrue(db > 30, "ETC1S PSNR " + db);
    }

    @Test
    void uastcMatchesReference() throws IOException {
        Ktx2Decoder.Image ref = Ktx2Decoder.decode(resource("color_grid_zstd_5.ktx2"));
        Ktx2Decoder.Image uastc = Ktx2Decoder.decode(resource("color_grid_uastc_zstd_5.ktx2"));
        double db = psnr(uastc, ref);
        assertTrue(db > 42, "UASTC PSNR " + db);
    }

    @Test
    void etc1sAlphaSlice() throws IOException {
        Ktx2Decoder.Image img = Ktx2Decoder.decode(resource("alpha_simple_blze.ktx2"));
        assertEquals(8, img.width());
        assertEquals(8, img.height());
        boolean translucent = false;
        for (int i = 3; i < img.rgba().length; i += 4) translucent |= img.rgba()[i] != (byte) 255;
        assertTrue(translucent, "alpha slice decoded into the alpha channel");
    }

    /** A 3x2 RGBA8 KTX2 with optional Zstandard supercompression and orientation metadata. */
    private static byte[] plain(byte[] pixels, boolean zstd, String orientation) {
        byte[] level = pixels;
        if (zstd) {
            ZstdCompressor c = new ZstdCompressor();
            byte[] out = new byte[c.maxCompressedLength(pixels.length)];
            int n = c.compress(pixels, 0, pixels.length, out, 0, out.length);
            level = Arrays.copyOf(out, n);
        }
        // DFD: total size + basic block with 4 samples (RGBA, 8 bits each)
        ByteBuffer dfd = ByteBuffer.allocate(4 + 24 + 64).order(ByteOrder.LITTLE_ENDIAN);
        dfd.putInt(92).putInt(0).putInt(2 | 88 << 16).put((byte) 1).put((byte) 1).put((byte) 2).put((byte) 0)
                .putInt(0).putLong(4);
        for (int c = 0; c < 4; c++) dfd.putShort((short) (c * 8)).put((byte) 7).put((byte) (c == 3 ? 15 : c)).putInt(0).putInt(0).putInt(255);
        byte[] key = ("KTXorientation\0" + orientation + "\0").getBytes(StandardCharsets.UTF_8);
        int kvdLength = 4 + key.length;
        int kvdPadded = (kvdLength + 3) & ~3;
        int dfdOffset = 80 + 24, kvdOffset = dfdOffset + 92, dataOffset = kvdOffset + kvdPadded;
        ByteBuffer b = ByteBuffer.allocate(dataOffset + level.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{(byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, '\r', '\n', 0x1A, '\n'});
        b.putInt(37).putInt(1).putInt(3).putInt(2).putInt(0).putInt(0).putInt(1).putInt(1).putInt(zstd ? 2 : 0);
        b.putInt(dfdOffset).putInt(92).putInt(kvdOffset).putInt(kvdLength).putLong(0).putLong(0);
        b.putLong(dataOffset).putLong(level.length).putLong(pixels.length);
        b.put(dfd.array());
        b.putInt(key.length).put(key);
        while (b.position() < dataOffset) b.put((byte) 0);
        b.put(level);
        return b.array();
    }

    @Test
    void uncompressedZstdAndOrientation() throws IOException {
        byte[] pixels = new byte[3 * 2 * 4];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (i * 10);
        Ktx2Decoder.Image down = Ktx2Decoder.decode(plain(pixels, true, "rd"));
        assertArrayEquals(pixels, down.rgba());
        Ktx2Decoder.Image up = Ktx2Decoder.decode(plain(pixels, false, "ru"));
        byte[] flipped = new byte[pixels.length];
        System.arraycopy(pixels, 12, flipped, 0, 12);
        System.arraycopy(pixels, 0, flipped, 12, 12);
        assertArrayEquals(flipped, up.rgba());
    }

    @Test
    void rejectsMalformedFiles() throws IOException {
        assertFalse(Ktx2Decoder.isKtx2(new byte[]{1, 2, 3}));
        assertThrows(Ktx2Exception.class, () -> Ktx2Decoder.decode(new byte[100]));
        byte[] etc1s = resource("color_grid_blze.ktx2");
        assertThrows(Ktx2Exception.class, () -> Ktx2Decoder.decode(Arrays.copyOf(etc1s, 5000)));
        byte[] corrupt = etc1s.clone();
        for (int i = corrupt.length / 2; i < corrupt.length; i += 7) corrupt[i] ^= 0x5A;
        try {
            Ktx2Decoder.decode(corrupt); // garbage in, garbage (or an exception) out - but never a crash or hang
        } catch (Ktx2Exception expected) {
            // fine
        }
        byte[] uastc = resource("color_grid_uastc_zstd_5.ktx2");
        assertThrows(Ktx2Exception.class, () -> Ktx2Decoder.decode(Arrays.copyOf(uastc, uastc.length - 1000)));
    }
}
