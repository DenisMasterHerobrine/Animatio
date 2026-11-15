package dev.denismasterherobrine.animatio.api.playback;

/**
 * How long a fade or cross-fade takes and how it is shaped.
 *
 * @param duration seconds, {@code 0} means an instant switch
 * @param easing   curve applied to the fade progress
 */
public record TransitionConfig(float duration, Easing easing) {
    public static final TransitionConfig NONE = new TransitionConfig(0f, Easing.LINEAR);
    public static final TransitionConfig DEFAULT = new TransitionConfig(0.2f, Easing.EASE_IN_OUT);

    public TransitionConfig {
        if (!(duration >= 0f) || Float.isInfinite(duration)) {
            throw new IllegalArgumentException("Transition duration must be a finite value >= 0, got " + duration);
        }
        if (easing == null) easing = Easing.EASE_IN_OUT;
    }

    public static TransitionConfig of(float seconds) {
        return new TransitionConfig(seconds, Easing.EASE_IN_OUT);
    }

    public static TransitionConfig of(float seconds, Easing easing) {
        return new TransitionConfig(seconds, easing);
    }

    public static TransitionConfig ofTicks(int ticks) {
        return new TransitionConfig(ticks / 20f, Easing.EASE_IN_OUT);
    }

    /** Eased progress of a fade that has run for {@code elapsed} seconds. Instant fades return 1. */
    public float progress(float elapsed) {
        return duration <= 0f ? 1f : easing.apply(elapsed / duration);
    }
}
