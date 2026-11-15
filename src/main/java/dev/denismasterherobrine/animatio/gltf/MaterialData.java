package dev.denismasterherobrine.animatio.gltf;

/**
 * Material properties read from glTF.
 *
 * @param baseColorImage  image index of the base colour texture, or -1
 * @param baseColorFactor RGBA multiplier
 * @param emissiveImage   image index of the emissive texture, or -1
 * @param emissiveFactor  RGB emissive multiplier (already scaled by {@code KHR_materials_emissive_strength})
 * @param nearestFilter   true for pixel-art sampling (also the default when the file does not say)
 * @param unlit           {@code KHR_materials_unlit}
 */
public record MaterialData(String name, int baseColorImage, float[] baseColorFactor, int emissiveImage,
                           float[] emissiveFactor, AlphaMode alphaMode, float alphaCutoff, boolean doubleSided,
                           boolean nearestFilter, boolean unlit) {
    public enum AlphaMode {OPAQUE, MASK, BLEND}

    public static final MaterialData DEFAULT = new MaterialData("default", -1, new float[]{1, 1, 1, 1}, -1,
            new float[]{0, 0, 0}, AlphaMode.OPAQUE, 0.5f, false, true, false);

    public boolean isEmissive() {
        return emissiveFactor[0] > 0f || emissiveFactor[1] > 0f || emissiveFactor[2] > 0f;
    }
}
