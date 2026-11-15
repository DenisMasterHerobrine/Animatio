package dev.denismasterherobrine.animatio.anim.model;

/**
 * Keyframes for one property (translation, rotation or scale) of one node.
 *
 * @param node          target node index
 * @param path          {@link #TRANSLATION}, {@link #ROTATION} or {@link #SCALE}
 * @param interpolation {@link #STEP}, {@link #LINEAR} or {@link #CUBIC}
 * @param times         ascending key times in seconds
 * @param values        key values; for {@link #CUBIC} each key stores in-tangent, value, out-tangent
 */
public record Channel(int node, int path, int interpolation, float[] times, float[] values) {
    public static final int TRANSLATION = 0, ROTATION = 1, SCALE = 2;
    public static final int STEP = 0, LINEAR = 1, CUBIC = 2;

    public Channel {
        if (path < 0 || path > 2) throw new IllegalArgumentException("Invalid path " + path);
        if (interpolation < 0 || interpolation > 2) throw new IllegalArgumentException("Invalid interpolation " + interpolation);
        int comps = components(path);
        int perKey = interpolation == CUBIC ? comps * 3 : comps;
        if (times.length == 0) throw new IllegalArgumentException("Channel has no keyframes");
        if (values.length != times.length * perKey) {
            throw new IllegalArgumentException("Channel has " + values.length + " values for " + times.length + " keys");
        }
        for (int i = 1; i < times.length; i++) {
            if (times[i] < times[i - 1]) throw new IllegalArgumentException("Keyframe times are not ascending");
        }
    }

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
