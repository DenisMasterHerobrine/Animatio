package dev.denismasterherobrine.animatio.api.playable;

import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable list of steps played one after another on a single track.
 * <pre>{@code
 * JUMP.thenWaitSecs(10).thenPlay(FALL).thenHoldLastFrame()
 * }</pre>
 * <ul>
 *     <li>A wait holds the pose of the last played step.</li>
 *     <li>Moving from one played step to the next blends over the step's transition
 *     ({@link #DEFAULT_STEP_BLEND} unless given).</li>
 *     <li>A sequence that ends without {@link #thenHoldLastFrame()} or {@link #thenRepeat()} fades out.</li>
 * </ul>
 */
public final class Sequence implements Playable {
    public static final TransitionConfig DEFAULT_STEP_BLEND = TransitionConfig.of(0.1f);

    public sealed interface Step permits Play, Wait, Hold, Fire, Repeat {
    }

    /**
     * Play a clip or blend space.
     *
     * @param target   an {@link AnimationRef} or a {@link BlendSpace}
     * @param blendIn  blend from the previous step's pose, or {@code null} for {@link #DEFAULT_STEP_BLEND}
     * @param duration seconds to play for (looping as needed), or NaN to play the target's natural length
     */
    public record Play(Playable target, @Nullable TransitionConfig blendIn, float duration) implements Step {
        public Play {
            Objects.requireNonNull(target, "target");
            if (target instanceof Sequence) throw new IllegalArgumentException("Nested sequences must be flattened");
            if (!Float.isNaN(duration) && !(duration >= 0f)) throw new IllegalArgumentException("Invalid duration " + duration);
        }

        public boolean hasFixedDuration() {
            return !Float.isNaN(duration);
        }
    }

    public record Wait(float seconds) implements Step {
        public Wait {
            if (!(seconds >= 0f) || Float.isInfinite(seconds)) throw new IllegalArgumentException("Invalid wait " + seconds);
        }
    }

    /** Terminal step: keep showing the last pose until stopped. */
    public record Hold() implements Step {
    }

    public record Fire(AnimationEvent event) implements Step {
        public Fire {
            Objects.requireNonNull(event, "event");
        }
    }

    /** Terminal step: start the sequence again from its first step. */
    public record Repeat() implements Step {
    }

    private final List<Step> steps;
    private final @Nullable String name;

    private Sequence(List<Step> steps, @Nullable String name) {
        this.steps = steps;
        this.name = name;
    }

    /** Wrap a playable as a one-step sequence (sequences are returned unchanged). */
    public static Sequence of(Playable playable) {
        if (playable instanceof Sequence s) return s;
        return new Sequence(List.of(new Play(playable, null, Float.NaN)), null);
    }

    /** A sequence that starts by waiting, e.g. {@code Sequence.delay(2).thenPlay(WAVE)}. */
    public static Sequence delay(float seconds) {
        return new Sequence(List.of(new Wait(seconds)), null);
    }

    /** Internal factory used by deserialisation. */
    public static Sequence create(List<Step> steps, @Nullable String name) {
        if (steps.isEmpty()) throw new IllegalArgumentException("A sequence needs at least one step");
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i) instanceof Hold || steps.get(i) instanceof Repeat) {
                throw new IllegalArgumentException("Hold and Repeat must be the last step");
            }
        }
        return new Sequence(List.copyOf(steps), name);
    }

    public List<Step> steps() {
        return steps;
    }

    public @Nullable String name() {
        return name;
    }

    @Override
    public String key() {
        if (name != null) return name;
        for (Step step : steps) {
            if (step instanceof Play p) return p.target().key();
        }
        return "sequence";
    }

    /** Give the sequence an explicit key for {@code isPlaying}/{@code stop}. */
    public Sequence named(String name) {
        Objects.requireNonNull(name, "name");
        return new Sequence(steps, name);
    }

    @Override
    public Sequence thenPlay(Playable next) {
        if (next instanceof Sequence s) return append(s.steps);
        return append(List.of(new Play(next, null, Float.NaN)));
    }

    @Override
    public Sequence thenPlay(Playable next, TransitionConfig blendIn) {
        Objects.requireNonNull(blendIn, "blendIn");
        if (next instanceof Sequence s) {
            List<Step> copy = new ArrayList<>(s.steps);
            if (copy.getFirst() instanceof Play p) copy.set(0, new Play(p.target(), blendIn, p.duration()));
            return append(copy);
        }
        return append(List.of(new Play(next, blendIn, Float.NaN)));
    }

    @Override
    public Sequence thenPlayFor(Playable next, float seconds) {
        if (next instanceof Sequence) throw new IllegalArgumentException("thenPlayFor takes a clip or blend space");
        return append(List.of(new Play(next, null, seconds)));
    }

    @Override
    public Sequence thenLoop(Playable next) {
        if (next instanceof AnimationRef ref) return thenPlay(ref.looping());
        if (next instanceof BlendSpace) return thenPlay(next); // blend spaces loop by default
        throw new IllegalArgumentException("thenLoop takes a clip or blend space; use thenRepeat() for sequences");
    }

    @Override
    public Sequence thenWaitSecs(float seconds) {
        return append(List.of(new Wait(seconds)));
    }

    @Override
    public Sequence thenWaitTicks(int ticks) {
        return thenWaitSecs(ticks / 20f);
    }

    @Override
    public Sequence thenFire(AnimationEvent event) {
        return append(List.of(new Fire(event)));
    }

    @Override
    public Sequence thenHoldLastFrame() {
        return append(List.of(new Hold()));
    }

    @Override
    public Sequence thenRepeat() {
        return append(List.of(new Repeat()));
    }

    private Sequence append(List<Step> more) {
        Step last = steps.getLast();
        if (last instanceof Hold || last instanceof Repeat) {
            throw new IllegalStateException("Cannot add steps after thenHoldLastFrame() or thenRepeat()");
        }
        List<Step> list = new ArrayList<>(steps.size() + more.size());
        list.addAll(steps);
        list.addAll(more);
        return create(Collections.unmodifiableList(list), name);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Sequence s && steps.equals(s.steps) && Objects.equals(name, s.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(steps, name);
    }

    @Override
    public String toString() {
        return "Sequence[" + key() + ", " + steps + "]";
    }
}
