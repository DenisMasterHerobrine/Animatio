package dev.denismasterherobrine.animatio.api.playback;

/**
 * Easing curves used by fades and transitions. Input and output are both in {@code [0, 1]}.
 */
public enum Easing {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    /** Smoothstep: slow start and end. The default for transitions. */
    EASE_IN_OUT;

    public float apply(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return switch (this) {
            case LINEAR -> t;
            case EASE_IN -> t * t;
            case EASE_OUT -> t * (2f - t);
            case EASE_IN_OUT -> t * t * (3f - 2f * t);
        };
    }
}
