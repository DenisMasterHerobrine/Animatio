package dev.denismasterherobrine.animatio.api.playback;

import org.jetbrains.annotations.Nullable;

/**
 * Per-play settings. Immutable; every modifier returns a copy.
 * <pre>{@code
 * controller.play(GREET, PlayOptions.defaults().fadeIn(TransitionConfig.of(0.4f)).priority(10));
 * }</pre>
 *
 * @param priority tracks with a higher priority are layered above lower ones; equal priorities layer by play order
 * @param fadeIn   fade-in, or {@code null} to use the transition table / controller default
 * @param fadeOut  fade-out when the animation finishes on its own, or {@code null} for the default
 * @param startAt  seconds to skip at the start (no events fire for the skipped part)
 * @param speed    track speed multiplier
 * @param weight   overall weight of the track in {@code [0, 1]}
 * @param mask     bones the track may affect, or {@code null} for all bones its clips animate
 * @param additive add the clip's offset from the rest pose on top of the layers below instead of replacing them
 */
public record PlayOptions(int priority, @Nullable TransitionConfig fadeIn, @Nullable TransitionConfig fadeOut,
                          float startAt, float speed, float weight, @Nullable BoneMask mask, boolean additive) {
    private static final PlayOptions DEFAULTS = new PlayOptions(0, null, null, 0f, 1f, 1f, null, false);

    public PlayOptions {
        if (!(startAt >= 0f) || Float.isInfinite(startAt)) throw new IllegalArgumentException("Invalid startAt " + startAt);
        if (!(speed >= 0f) || Float.isInfinite(speed)) throw new IllegalArgumentException("Invalid speed " + speed);
        if (!(weight >= 0f && weight <= 1f)) throw new IllegalArgumentException("Weight must be in [0, 1], got " + weight);
    }

    public static PlayOptions defaults() {
        return DEFAULTS;
    }

    public PlayOptions priority(int priority) {
        return new PlayOptions(priority, fadeIn, fadeOut, startAt, speed, weight, mask, additive);
    }

    public PlayOptions fadeIn(@Nullable TransitionConfig fadeIn) {
        return new PlayOptions(priority, fadeIn, fadeOut, startAt, speed, weight, mask, additive);
    }

    public PlayOptions fadeOut(@Nullable TransitionConfig fadeOut) {
        return new PlayOptions(priority, fadeIn, fadeOut, startAt, speed, weight, mask, additive);
    }

    public PlayOptions startAt(float seconds) {
        return new PlayOptions(priority, fadeIn, fadeOut, seconds, speed, weight, mask, additive);
    }

    public PlayOptions speed(float speed) {
        return new PlayOptions(priority, fadeIn, fadeOut, startAt, speed, weight, mask, additive);
    }

    public PlayOptions weight(float weight) {
        return new PlayOptions(priority, fadeIn, fadeOut, startAt, speed, weight, mask, additive);
    }

    public PlayOptions mask(@Nullable BoneMask mask) {
        return new PlayOptions(priority, fadeIn, fadeOut, startAt, speed, weight, mask, additive);
    }

    public PlayOptions additive(boolean additive) {
        return new PlayOptions(priority, fadeIn, fadeOut, startAt, speed, weight, mask, additive);
    }
}
