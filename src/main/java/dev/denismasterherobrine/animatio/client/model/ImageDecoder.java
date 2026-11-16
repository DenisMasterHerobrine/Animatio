package dev.denismasterherobrine.animatio.client.model;

import com.mojang.blaze3d.platform.NativeImage;
import dev.denismasterherobrine.animatio.gltf.ImageData;
import dev.denismasterherobrine.animatio.gltf.texture.Ktx2Decoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decodes glTF images. PNG goes through Minecraft's loader, KTX2 ({@code KHR_texture_basisu}) through
 * {@link Ktx2Decoder}, and JPEG (which Minecraft rejects) through ImageIO.
 */
final class ImageDecoder {
    private ImageDecoder() {
    }

    static NativeImage decode(ImageData image) throws IOException {
        byte[] data = image.data();
        boolean png = data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
        if (png) return NativeImage.read(data);
        if (image.isKtx2()) return fromRgba(Ktx2Decoder.decode(data));
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(data));
        if (buffered == null) throw new IOException("Unsupported image format " + image.mimeType() + " in '" + image.name() + "'");
        NativeImage out = new NativeImage(buffered.getWidth(), buffered.getHeight(), false);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                int argb = buffered.getRGB(x, y);
                // NativeImage stores ABGR
                int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                out.setPixelRGBA(x, y, abgr);
            }
        }
        return out;
    }

    private static NativeImage fromRgba(Ktx2Decoder.Image image) {
        NativeImage out = new NativeImage(image.width(), image.height(), false);
        byte[] p = image.rgba();
        for (int y = 0, i = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++, i += 4) {
                out.setPixelRGBA(x, y, (p[i + 3] & 0xFF) << 24 | (p[i + 2] & 0xFF) << 16 | (p[i + 1] & 0xFF) << 8 | (p[i] & 0xFF));
            }
        }
        return out;
    }
}
