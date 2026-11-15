package dev.denismasterherobrine.animatio.gltf;

/**
 * A glTF file could not be read.
 */
public class GltfException extends Exception {
    public GltfException(String message) {
        super(message);
    }

    public GltfException(String message, Throwable cause) {
        super(message, cause);
    }
}
