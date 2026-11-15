package dev.denismasterherobrine.animatio.api.playable;

import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import dev.denismasterherobrine.animatio.api.event.EventMarker;
import dev.denismasterherobrine.animatio.api.event.Events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A reference to a named animation clip (or a blend space declared in a model's JSON definition).
 * <p>
 * References are resolved against the model of the controller that plays them, so one constant such as
 * {@code AnimationRef.of("idle")} works for every model that has an {@code idle} clip.
 * Instances are immutable; the modifier methods return new references with the same name.
 */
public final class AnimationRef implements Playable {
    /** Use the clip's own default (from the model definition; plays once if nothing is declared). */
    public static final int LOOPS_DEFAULT = 0;
    /** Loop until stopped. */
    public static final int LOOPS_FOREVER = -1;

    private final String name;
    private final int loops;
    private final float speed;
    private final List<EventMarker> events;

    private AnimationRef(String name, int loops, float speed, List<EventMarker> events) {
        this.name = name;
        this.loops = loops;
        this.speed = speed;
        this.events = events;
    }

    public static AnimationRef of(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("Animation name must not be empty");
        return new AnimationRef(name, LOOPS_DEFAULT, 1f, List.of());
    }

    /** Internal factory used by deserialisation. */
    public static AnimationRef create(String name, int loops, float speed, List<EventMarker> events) {
        AnimationRef ref = of(name);
        if (loops < LOOPS_FOREVER) throw new IllegalArgumentException("Invalid loop count " + loops);
        if (!(speed > 0f) || Float.isInfinite(speed)) throw new IllegalArgumentException("Speed must be > 0, got " + speed);
        List<EventMarker> sorted = new ArrayList<>(events);
        sorted.sort(EventMarker.BY_TIME);
        return new AnimationRef(ref.name, loops, speed, Collections.unmodifiableList(sorted));
    }

    public String name() {
        return name;
    }

    @Override
    public String key() {
        return name;
    }

    /** Loop count: {@link #LOOPS_DEFAULT}, {@link #LOOPS_FOREVER}, or a positive number of plays. */
    public int loops() {
        return loops;
    }

    /** Playback speed multiplier, applied on top of the clip's default speed. */
    public float speed() {
        return speed;
    }

    /** Events attached to this reference in code, in addition to the clip's own events. */
    public List<EventMarker> events() {
        return events;
    }

    public AnimationRef looping() {
        return new AnimationRef(name, LOOPS_FOREVER, speed, events);
    }

    public AnimationRef once() {
        return new AnimationRef(name, 1, speed, events);
    }

    public AnimationRef times(int count) {
        if (count < 1) throw new IllegalArgumentException("Play count must be >= 1, got " + count);
        return new AnimationRef(name, count, speed, events);
    }

    public AnimationRef withSpeed(float speed) {
        if (!(speed > 0f) || Float.isInfinite(speed)) throw new IllegalArgumentException("Speed must be > 0, got " + speed);
        return new AnimationRef(name, loops, speed, events);
    }

    /** Fire {@code event} each time playback of this clip passes {@code time} seconds. */
    public AnimationRef withEvent(float time, AnimationEvent event) {
        List<EventMarker> list = new ArrayList<>(events);
        list.add(new EventMarker(time, event));
        list.sort(EventMarker.BY_TIME);
        return new AnimationRef(name, loops, speed, Collections.unmodifiableList(list));
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof AnimationRef r && loops == r.loops && Float.compare(speed, r.speed) == 0
                && name.equals(r.name) && events.equals(r.events);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, loops, speed, events);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AnimationRef[").append(name);
        if (loops == LOOPS_FOREVER) sb.append(", looping");
        else if (loops > 0) sb.append(", x").append(loops);
        if (speed != 1f) sb.append(", speed=").append(speed);
        if (!events.isEmpty()) sb.append(", events=").append(events.size());
        return sb.append(']').toString();
    }
}
