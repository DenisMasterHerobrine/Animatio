package dev.denismasterherobrine.animatio.anim.model;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyframe data of one animation, as loaded from glTF.
 * <p>
 * For sampling, the channels are flattened into parallel arrays and grouped by their key times: exporters give the
 * translation, rotation and scale (and often every bone) of a clip the same time track, so the key lookup runs once
 * per distinct track instead of once per channel. Evenly spaced (baked) tracks find their key in constant time.
 */
public final class Clip {
    /** An event declared in the glTF file ({@code extras}), decoded later by the data layer. */
    public record RawEvent(float time, JsonObject data) {
    }

    private static final int T = 0, R = 1, S = 2;

    private final String name;
    private final float duration;
    private final Channel[] channels;
    /** Per node and path ({@link Channel#PATHS} per node): 1 if this clip animates it, else 0. */
    private final float[] pathMask;
    /** {@code node * PATHS + path} of every animated property, ascending. */
    private final int[] animated;
    private final List<RawEvent> rawEvents;

    // flattened sampling data: key-time tracks, each followed by the channels that use it
    private final float[][] keyTimes;
    private final float[] keyStart;
    private final float[] keyInvStep;
    private final int[] trackFirst;
    private final byte[] chTarget;
    private final int[] chOffset;
    private final int[] chComps;
    private final byte[] chInterp;
    private final int[] chBase;
    private final float[] values;

    /** A clip without morph weight channels. */
    public Clip(String name, Channel[] channels, int nodeCount, List<RawEvent> rawEvents) {
        this(name, channels, nodeCount, null, rawEvents);
    }

    /** A clip for {@code skeleton}; weight channels write the slots of their node. */
    public Clip(String name, Channel[] channels, Skeleton skeleton, List<RawEvent> rawEvents) {
        this(name, channels, skeleton.size(), skeleton, rawEvents);
    }

    private Clip(String name, Channel[] channels, int nodeCount, @Nullable Skeleton skeleton, List<RawEvent> rawEvents) {
        this.name = name;
        this.channels = channels;
        this.rawEvents = List.copyOf(rawEvents);
        this.pathMask = new float[nodeCount * Channel.PATHS];
        float end = 0f;
        for (Channel ch : channels) {
            if (ch.node() < 0 || ch.node() >= nodeCount) throw new IllegalArgumentException("Channel targets missing node " + ch.node());
            if (ch.path() == Channel.WEIGHTS) {
                int expected = skeleton != null ? skeleton.morphCount(ch.node()) : 0;
                if (ch.comps() != expected) {
                    throw new IllegalArgumentException("Weights channel of node " + ch.node() + " has " + ch.comps()
                            + " weights, the node has " + expected + " morph targets");
                }
            }
            pathMask[ch.node() * Channel.PATHS + ch.path()] = 1f;
            end = Math.max(end, ch.endTime());
        }
        this.duration = end;
        int animatedCount = 0;
        for (float v : pathMask) if (v > 0f) animatedCount++;
        this.animated = new int[animatedCount];
        for (int i = 0, k = 0; i < pathMask.length; i++) if (pathMask[i] > 0f) animated[k++] = i;

        // group channels by key times (content-equal arrays share a track)
        Map<TimesKey, List<Channel>> groups = new HashMap<>();
        List<TimesKey> trackOrder = new ArrayList<>();
        for (Channel ch : channels) {
            TimesKey key = new TimesKey(ch.times());
            List<Channel> list = groups.get(key);
            if (list == null) {
                groups.put(key, list = new ArrayList<>());
                trackOrder.add(key);
            }
            list.add(ch);
        }
        int tracks = trackOrder.size();
        this.keyTimes = new float[tracks][];
        this.keyStart = new float[tracks];
        this.keyInvStep = new float[tracks];
        this.trackFirst = new int[tracks + 1];
        int n = channels.length;
        this.chTarget = new byte[n];
        this.chOffset = new int[n];
        this.chComps = new int[n];
        this.chInterp = new byte[n];
        this.chBase = new int[n];
        int totalValues = 0;
        for (Channel ch : channels) totalValues += ch.values().length;
        this.values = new float[totalValues];
        int c = 0, v = 0;
        for (int tr = 0; tr < tracks; tr++) {
            float[] times = trackOrder.get(tr).times;
            keyTimes[tr] = times;
            keyStart[tr] = times[0];
            keyInvStep[tr] = uniformInverseStep(times);
            trackFirst[tr] = c;
            for (Channel ch : groups.get(trackOrder.get(tr))) {
                int path = ch.path();
                chTarget[c] = (byte) path;
                chOffset[c] = switch (path) {
                    case Channel.TRANSLATION, Channel.SCALE -> ch.node() * 3;
                    case Channel.ROTATION -> ch.node() * 4;
                    default -> skeleton.morphOffset(ch.node());
                };
                chComps[c] = ch.comps();
                chInterp[c] = (byte) ch.interpolation();
                chBase[c] = v;
                System.arraycopy(ch.values(), 0, values, v, ch.values().length);
                v += ch.values().length;
                c++;
            }
        }
        trackFirst[tracks] = c;
    }

    /** {@code 1 / step} for evenly spaced keys (three or more), else 0. */
    private static float uniformInverseStep(float[] times) {
        int n = times.length;
        if (n < 3) return 0f;
        float t0 = times[0];
        float step = (times[n - 1] - t0) / (n - 1);
        if (!(step > 0f)) return 0f;
        float tolerance = step * 1e-3f;
        for (int i = 1; i < n - 1; i++) {
            if (Math.abs(times[i] - (t0 + i * step)) > tolerance) return 0f;
        }
        return 1f / step;
    }

    private record TimesKey(float[] times) {
        @Override
        public boolean equals(Object o) {
            return o instanceof TimesKey k && (k.times == times || Arrays.equals(k.times, times));
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(times);
        }
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

    /** Per node: {@link Channel#PATHS} entries (translation, rotation, scale, weights), 1 if animated. Do not modify. */
    public float[] pathMask() {
        return pathMask;
    }

    /** {@code node * PATHS + path} of every animated property, ascending. Do not modify. */
    public int[] animated() {
        return animated;
    }

    public boolean animates(int node, int path) {
        return pathMask[node * Channel.PATHS + path] > 0f;
    }

    public List<RawEvent> rawEvents() {
        return rawEvents;
    }

    /** Write the values of every animated property at {@code time} into {@code out}. Other values are untouched. */
    public void sample(float time, Pose out) {
        float[] vals = values;
        for (int tr = 0, tracks = keyTimes.length; tr < tracks; tr++) {
            float[] times = keyTimes[tr];
            int n = times.length;
            int k, mode;
            float u = 0f, dt = 0f;
            if (n == 1 || time <= times[0]) {
                k = 0;
                mode = ClipSampler.CLAMP;
            } else if (time >= times[n - 1]) {
                k = n - 1;
                mode = ClipSampler.CLAMP;
            } else {
                float inv = keyInvStep[tr];
                if (inv > 0f) {
                    k = (int) ((time - keyStart[tr]) * inv);
                    if (k > n - 2) k = n - 2;
                    else if (k < 0) k = 0;
                    while (k < n - 2 && times[k + 1] <= time) k++;
                    while (k > 0 && times[k] > time) k--;
                } else {
                    k = ClipSampler.findKey(times, time);
                }
                dt = times[k + 1] - times[k];
                u = dt > 0f ? (time - times[k]) / dt : 0f;
                mode = ClipSampler.BETWEEN;
            }
            for (int c = trackFirst[tr], last = trackFirst[tr + 1]; c < last; c++) {
                int target = chTarget[c];
                float[] dst = target == T ? out.t : target == R ? out.r : target == S ? out.s : out.m;
                ClipSampler.interpolate(vals, chBase[c], chComps[c], chInterp[c], target == R, k, u, dt, mode, dst, chOffset[c]);
            }
        }
    }
}
