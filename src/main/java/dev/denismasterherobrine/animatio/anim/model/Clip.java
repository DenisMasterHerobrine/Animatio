package dev.denismasterherobrine.animatio.anim.model;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * Keyframe data of one animation, as loaded from glTF.
 */
public final class Clip {
    /** An event declared in the glTF file ({@code extras}), decoded later by the data layer. */
    public record RawEvent(float time, JsonObject data) {
    }

    private final String name;
    private final float duration;
    private final Channel[] channels;
    /** Per node and path (3 per node): 1 if this clip animates it, else 0. */
    private final float[] pathMask;
    private final List<RawEvent> rawEvents;

    public Clip(String name, Channel[] channels, int nodeCount, List<RawEvent> rawEvents) {
        this.name = name;
        this.channels = channels;
        this.rawEvents = List.copyOf(rawEvents);
        this.pathMask = new float[nodeCount * 3];
        float end = 0f;
        for (Channel ch : channels) {
            if (ch.node() < 0 || ch.node() >= nodeCount) throw new IllegalArgumentException("Channel targets missing node " + ch.node());
            pathMask[ch.node() * 3 + ch.path()] = 1f;
            end = Math.max(end, ch.endTime());
        }
        this.duration = end;
    }

    public String name() {
        return name;
    }

    /** Length in seconds (time of the last keyframe). */
    public float duration() {
        return duration;
    }

    public Channel[] channels() {
        return channels;
    }

    public float[] pathMask() {
        return pathMask;
    }

    public boolean animates(int node, int path) {
        return pathMask[node * 3 + path] > 0f;
    }

    public List<RawEvent> rawEvents() {
        return rawEvents;
    }

    /** Write the values of every animated property at {@code time} into {@code out}. Other values are untouched. */
    public void sample(float time, Pose out) {
        for (Channel ch : channels) {
            float[] target = switch (ch.path()) {
                case Channel.TRANSLATION -> out.t;
                case Channel.ROTATION -> out.r;
                default -> out.s;
            };
            ClipSampler.sample(ch, time, target, ch.node() * Channel.components(ch.path()));
        }
    }
}
