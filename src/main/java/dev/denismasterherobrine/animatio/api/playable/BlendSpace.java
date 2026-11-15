package dev.denismasterherobrine.animatio.api.playable;

import java.util.Objects;

/**
 * A playable that blends several clips according to one or two named controller parameters.
 * <p>
 * Clips are phase-synchronised: all of them run at the same normalised time, and the cycle length is the
 * weighted average of their durations, so feet stay in step when blending walk and run.
 * Keyframe events fire only from the clip with the highest weight.
 * <p>
 * A blend space loops until stopped (or for the duration given to {@link Sequence#thenPlayFor}).
 *
 * @see BlendSpace1D
 * @see BlendSpace2D
 */
public abstract sealed class BlendSpace implements Playable permits BlendSpace1D, BlendSpace2D {
    protected final String name;
    protected final AnimationRef[] clips;
    protected final float smoothing;

    protected BlendSpace(String name, AnimationRef[] clips, float smoothing) {
        this.name = Objects.requireNonNull(name, "name");
        if (clips.length == 0) throw new IllegalArgumentException("Blend space '" + name + "' has no samples");
        if (!(smoothing >= 0f) || Float.isInfinite(smoothing)) throw new IllegalArgumentException("Invalid smoothing " + smoothing);
        this.clips = clips;
        this.smoothing = smoothing;
    }

    public String name() {
        return name;
    }

    @Override
    public String key() {
        return name;
    }

    public int size() {
        return clips.length;
    }

    public AnimationRef clip(int index) {
        return clips[index];
    }

    /**
     * Time constant (seconds) for smoothing parameter changes, so a sudden jump in speed does not snap the pose.
     * {@code 0} disables smoothing.
     */
    public float smoothing() {
        return smoothing;
    }

    public abstract String parameterX();

    /** The second parameter for 2D blend spaces, or {@code null}. */
    public abstract String parameterY();

    /**
     * Compute the weight of each sample for the given parameter values. Weights are non-negative and sum to 1.
     *
     * @param out array of length {@link #size()}
     */
    public abstract void weights(float x, float y, float[] out);
}
