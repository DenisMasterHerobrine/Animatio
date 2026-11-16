package dev.denismasterherobrine.animatio.anim.model;

/**
 * Local transforms of every node: translation (3 floats), rotation quaternion x,y,z,w (4) and scale (3), plus the
 * morph target weights of the skeleton's morph slots ({@link Skeleton#morphSlots()}).
 */
public final class Pose {
    public final float[] t;
    public final float[] r;
    public final float[] s;
    /** Morph target weights, laid out as described by {@link Skeleton#morphOffset(int)}. */
    public final float[] m;
    private final int size;

    public Pose(int nodes) {
        this(nodes, 0);
    }

    public Pose(int nodes, int morphSlots) {
        this.size = nodes;
        this.t = new float[nodes * 3];
        this.r = new float[nodes * 4];
        this.s = new float[nodes * 3];
        this.m = new float[morphSlots];
    }

    public Pose(Skeleton skeleton) {
        this(skeleton.size(), skeleton.morphSlots());
    }

    public int size() {
        return size;
    }

    public void setRest(Skeleton skeleton) {
        System.arraycopy(skeleton.restT(), 0, t, 0, t.length);
        System.arraycopy(skeleton.restR(), 0, r, 0, r.length);
        System.arraycopy(skeleton.restS(), 0, s, 0, s.length);
        if (m.length > 0) System.arraycopy(skeleton.restMorph(), 0, m, 0, m.length);
    }

    public void copyFrom(Pose other) {
        System.arraycopy(other.t, 0, t, 0, t.length);
        System.arraycopy(other.r, 0, r, 0, r.length);
        System.arraycopy(other.s, 0, s, 0, s.length);
        if (m.length > 0) System.arraycopy(other.m, 0, m, 0, m.length);
    }
}
