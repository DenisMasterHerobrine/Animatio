package dev.denismasterherobrine.animatio.api.playable;

import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;

/**
 * Anything an {@link AnimationController} can play: a single clip ({@link AnimationRef}),
 * a {@link Sequence} of steps, or a {@link BlendSpace}.
 * <p>
 * Every playable can be chained into a sequence with the {@code then...} methods:
 * <pre>{@code
 * controller.play(JUMP.thenWaitSecs(10).thenPlay(FALL).thenHoldLastFrame());
 * }</pre>
 */
public sealed interface Playable permits AnimationRef, Sequence, BlendSpace {

    /**
     * The name used to match this playable in {@code isPlaying}, {@code stop}, {@code pause} and so on.
     * For a clip it is the clip name, for a blend space its name, for a sequence its explicit name or
     * else the key of its first played step.
     */
    String key();

    default Sequence thenPlay(Playable next) {
        return Sequence.of(this).thenPlay(next);
    }

    default Sequence thenPlay(Playable next, TransitionConfig blendIn) {
        return Sequence.of(this).thenPlay(next, blendIn);
    }

    default Sequence thenPlayFor(Playable next, float seconds) {
        return Sequence.of(this).thenPlayFor(next, seconds);
    }

    default Sequence thenLoop(Playable next) {
        return Sequence.of(this).thenLoop(next);
    }

    default Sequence thenWaitSecs(float seconds) {
        return Sequence.of(this).thenWaitSecs(seconds);
    }

    default Sequence thenWaitTicks(int ticks) {
        return Sequence.of(this).thenWaitTicks(ticks);
    }

    default Sequence thenFire(AnimationEvent event) {
        return Sequence.of(this).thenFire(event);
    }

    default Sequence thenHoldLastFrame() {
        return Sequence.of(this).thenHoldLastFrame();
    }

    default Sequence thenRepeat() {
        return Sequence.of(this).thenRepeat();
    }
}
