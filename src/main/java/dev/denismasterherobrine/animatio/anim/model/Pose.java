package dev.denismasterherobrine.animatio.anim.model;

/**
 * Local transforms of every node: translation (3 floats), rotation quaternion x,y,z,w (4) and scale (3).
 */
public final class Pose {
    public final float[] t;
    public final float[] r;
    public final float[] s;
    private final int size;

    public Pose(int nodes) {
        this.size = nodes;
        this.t = new float[nodes * 3];
        this.r = new float[nodes * 4];
        this.s = new float[nodes * 3];
    }

    public int size() {
        return size;
    }

    public void setRest(Skeleton skeleton) {
        System.arraycopy(skeleton.restT(), 0, t, 0, t.length);
        System.arraycopy(skeleton.restR(), 0, r, 0, r.length);
        System.arraycopy(skeleton.restS(), 0, s, 0, s.length);
    }

    public void copyFrom(Pose other) {
        System.arraycopy(other.t, 0, t, 0, t.length);
        System.arraycopy(other.r, 0, r, 0, r.length);
        System.arraycopy(other.s, 0, s, 0, s.length);
    }
}
