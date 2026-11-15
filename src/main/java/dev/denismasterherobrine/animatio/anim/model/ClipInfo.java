package dev.denismasterherobrine.animatio.anim.model;

import dev.denismasterherobrine.animatio.api.event.EventMarker;

import java.util.ArrayList;
import java.util.List;

/**
 * A clip together with its model-level settings (from the JSON definition and code registration).
 *
 * @param loop    whether a plain {@code play(ref)} loops by default
 * @param speed   default speed multiplier
 * @param markers keyframe events, sorted by time
 */
public record ClipInfo(Clip clip, boolean loop, float speed, List<EventMarker> markers) {
    public ClipInfo {
        List<EventMarker> sorted = new ArrayList<>(markers);
        sorted.sort(EventMarker.BY_TIME);
        markers = List.copyOf(sorted);
        if (!(speed > 0f)) throw new IllegalArgumentException("Clip speed must be > 0");
    }

    public static ClipInfo of(Clip clip) {
        return new ClipInfo(clip, false, 1f, List.of());
    }

    public ClipInfo withLoop(boolean loop) {
        return new ClipInfo(clip, loop, speed, markers);
    }

    public ClipInfo withSpeed(float speed) {
        return new ClipInfo(clip, loop, speed, markers);
    }

    public ClipInfo withMarkers(List<EventMarker> extra) {
        if (extra.isEmpty()) return this;
        List<EventMarker> all = new ArrayList<>(markers);
        all.addAll(extra);
        return new ClipInfo(clip, loop, speed, all);
    }

    public String name() {
        return clip.name();
    }

    public float duration() {
        return clip.duration();
    }
}
