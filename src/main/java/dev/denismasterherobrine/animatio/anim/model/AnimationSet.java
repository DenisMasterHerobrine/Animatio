package dev.denismasterherobrine.animatio.anim.model;

import dev.denismasterherobrine.animatio.api.playable.BlendSpace;
import dev.denismasterherobrine.animatio.api.playback.TransitionTable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything a controller needs from one model: skeleton, clips with their settings, named blend spaces,
 * the transition table, sockets, cameras, lights and the look-at bone. Immutable once built. A new instance is
 * created on every resource reload; controllers notice the new {@link #version()} and re-resolve their tracks.
 */
public final class AnimationSet {
    private static final AtomicInteger NEXT_VERSION = new AtomicInteger(1);

    private final String name;
    private final Skeleton skeleton;
    private final Map<String, ClipInfo> clips;
    private final Map<String, BlendSpace> blendSpaces;
    private final TransitionTable transitions;
    private final Map<String, Socket> sockets;
    private final List<ModelCamera> cameras;
    private final List<ModelLight> lights;
    private final @Nullable LookConfig look;
    private final int version;

    private AnimationSet(Builder b) {
        this.name = b.name;
        this.skeleton = b.skeleton;
        this.clips = Collections.unmodifiableMap(new LinkedHashMap<>(b.clips));
        this.blendSpaces = Collections.unmodifiableMap(new LinkedHashMap<>(b.blendSpaces));
        this.transitions = new TransitionTable().putAll(b.transitions);
        this.sockets = Collections.unmodifiableMap(new LinkedHashMap<>(b.sockets));
        this.cameras = List.copyOf(b.cameras);
        this.lights = List.copyOf(b.lights);
        this.look = b.look;
        this.version = NEXT_VERSION.getAndIncrement();
    }

    public static Builder builder(String name, Skeleton skeleton) {
        return new Builder(name, skeleton);
    }

    public String name() {
        return name;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public @Nullable ClipInfo clip(String name) {
        return clips.get(name);
    }

    public Collection<ClipInfo> clips() {
        return clips.values();
    }

    public @Nullable BlendSpace blendSpace(String name) {
        return blendSpaces.get(name);
    }

    public Map<String, BlendSpace> blendSpaces() {
        return blendSpaces;
    }

    /** Model-level transition table. Treat as read-only. */
    public TransitionTable transitions() {
        return transitions;
    }

    public @Nullable Socket socket(String name) {
        return sockets.get(name);
    }

    public Map<String, Socket> sockets() {
        return sockets;
    }

    /** Cameras in the model (glTF {@code cameras} instanced by nodes). */
    public List<ModelCamera> cameras() {
        return cameras;
    }

    public @Nullable ModelCamera camera(String name) {
        for (ModelCamera c : cameras) if (c.name().equals(name)) return c;
        return null;
    }

    /** Punctual lights in the model ({@code KHR_lights_punctual}). */
    public List<ModelLight> lights() {
        return lights;
    }

    /** The procedural look-at bone, or {@code null}. */
    public @Nullable LookConfig look() {
        return look;
    }

    public int version() {
        return version;
    }

    public static final class Builder {
        private final String name;
        private final Skeleton skeleton;
        private final Map<String, ClipInfo> clips = new LinkedHashMap<>();
        private final Map<String, BlendSpace> blendSpaces = new LinkedHashMap<>();
        private final TransitionTable transitions = new TransitionTable();
        private final Map<String, Socket> sockets = new LinkedHashMap<>();
        private final List<ModelCamera> cameras = new ArrayList<>();
        private final List<ModelLight> lights = new ArrayList<>();
        private @Nullable LookConfig look;

        private Builder(String name, Skeleton skeleton) {
            this.name = name;
            this.skeleton = skeleton;
        }

        public Builder clip(ClipInfo info) {
            clips.put(info.name(), info);
            return this;
        }

        public @Nullable ClipInfo getClip(String name) {
            return clips.get(name);
        }

        public Builder blendSpace(BlendSpace space) {
            blendSpaces.put(space.name(), space);
            return this;
        }

        public TransitionTable transitions() {
            return transitions;
        }

        public Builder socket(Socket socket) {
            sockets.put(socket.name(), socket);
            return this;
        }

        public Builder cameras(List<ModelCamera> list) {
            cameras.addAll(list);
            return this;
        }

        public Builder lights(List<ModelLight> list) {
            lights.addAll(list);
            return this;
        }

        public Builder look(@Nullable LookConfig look) {
            this.look = look;
            return this;
        }

        public AnimationSet build() {
            return new AnimationSet(this);
        }
    }
}
