package dev.denismasterherobrine.animatio.api.event;

import java.util.Comparator;

/**
 * An event placed at a time (seconds, clip-local) inside an animation clip.
 */
public record EventMarker(float time, AnimationEvent event) {
    public static final Comparator<EventMarker> BY_TIME = Comparator.comparingDouble(EventMarker::time);

    public EventMarker {
        if (!(time >= 0f) || Float.isInfinite(time)) {
            throw new IllegalArgumentException("Event time must be a finite value >= 0, got " + time);
        }
        if (event == null) throw new NullPointerException("event");
    }
}
