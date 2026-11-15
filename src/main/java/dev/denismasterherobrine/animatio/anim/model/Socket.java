package dev.denismasterherobrine.animatio.anim.model;

/**
 * A named attachment point: a bone plus a local offset (16-float column-major matrix).
 */
public record Socket(String name, int bone, float[] offset) {
    public Socket {
        if (offset.length != 16) throw new IllegalArgumentException("Socket offset must be a 4x4 matrix");
    }
}
