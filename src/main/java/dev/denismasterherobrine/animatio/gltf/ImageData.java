package dev.denismasterherobrine.animatio.gltf;

/**
 * An encoded image (PNG or JPEG) embedded in or referenced by a glTF file.
 */
public record ImageData(String name, String mimeType, byte[] data) {
}
