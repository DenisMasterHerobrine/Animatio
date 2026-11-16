package dev.denismasterherobrine.animatio.anim.model;

/**
 * Keyframes for one property (translation, rotation, scale or morph weights) of one node.
 *
 * @param node          target node index
 * @param path          {@link #TRANSLATION}, {@link #ROTATION}, {@link #SCALE} or {@link #WEIGHTS}
 * @param interpolation {@link #STEP}, {@link #LINEAR} or {@link #CUBIC}
 * @param times         ascending key times in seconds
 * @param values        key values; for {@link #CUBIC} each key stores in-tangent, value, out-tangent
 * @param comps         values per key and element: 3, 4 (rotation) or the node's morph target count
 */
public record Channel(int node, int path, int interpolation, float[] times, float[] values, int comps) {
    public static final int TRANSLATION = 0, ROTATION = 1, SCALE = 2, WEIGHTS = 3;
    /** Paths per node in path masks. */
    public static final int PATHS = 4;
    public static final int STEP = 0, LINEAR = 1, CUBIC = 2;

    public Channel {
        if (path < 0 || path > 3) throw new IllegalArgumentException("Invalid path " + path);
        if (interpolation < 0 || interpolation > 2) throw new IllegalArgumentException("Invalid interpolation " + interpolation);
        if (path != WEIGHTS && comps != components(path)) throw new IllegalArgumentException("Wrong component count " + comps);
        if (comps <= 0) throw new IllegalArgumentException("Channel has no components");
        int perKey = interpolation == CUBIC ? comps * 3 : comps;
        if (times.length == 0) throw new IllegalArgumentException("Channel has no keyframes");
        if (values.length != times.length * perKey) {
            throw new IllegalArgumentException("Channel has " + values.length + " values for " + times.length + " keys");
        }
        for (int i = 1; i < times.length; i++) {
            if (!(times[i] >= times[i - 1])) throw new IllegalArgumentException("Keyframe times are not ascending");
        }
    }

    /** A translation, rotation or scale channel. */
    public Channel(int node, int path, int interpolation, float[] times, float[] values) {
        this(node, path, interpolation, times, values, components(path));
    }

    /** Components of a transform path (morph weight channels have one per target instead). */
    public static int components(int path) {
        return path == ROTATION ? 4 : 3;
    }

    public float startTime() {
        return times[0];
    }

    public float endTime() {
        return times[times.length - 1];
    }
}
