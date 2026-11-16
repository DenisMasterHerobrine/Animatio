package dev.denismasterherobrine.animatio.gltf.texture;

import java.io.IOException;

/** A KTX2 file that is malformed or uses a feature the decoder does not support. */
public class Ktx2Exception extends IOException {
    public Ktx2Exception(String message) {
        super(message);
    }

    public Ktx2Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
