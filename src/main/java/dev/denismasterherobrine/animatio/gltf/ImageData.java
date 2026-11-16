package dev.denismasterherobrine.animatio.gltf;

/**
 * An encoded image embedded in or referenced by a glTF file: PNG or JPEG, or KTX2 ({@code KHR_texture_basisu}).
 */
public record ImageData(String name, String mimeType, byte[] data) {
    public static final String KTX2 = "image/ktx2";

    public boolean isKtx2() {
        return KTX2.equals(mimeType) || data.length >= 12 && (data[0] & 0xFF) == 0xAB && data[1] == 'K' && data[2] == 'T'
                && data[3] == 'X' && data[4] == ' ' && data[5] == '2' && data[6] == '0';
    }
}
