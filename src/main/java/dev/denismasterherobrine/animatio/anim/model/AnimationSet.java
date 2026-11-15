package dev.denismasterherobrine.animatio.anim.model;

import dev.denismasterherobrine.animatio.api.playable.BlendSpace;
import dev.denismasterherobrine.animatio.api.playback.TransitionTable;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything a controller needs from one model: skeleton, clips with their settings, named blend spaces,
 * the transition table and sockets. Immutable once built. A new instance is created on every resource reload;
 * controllers notice the new {@link #version()} and re-resolve their tracks.
 */
public final class AnimationSet {
    private static final AtomicInteger NEXT_VERSION = new AtomicInteger(1);

    private final String name;
    private final Skeleton skeleton;
    private final Map<String, ClipInfo> clips;
    private final Map<String, BlendSpace> blendSpaces;
    private final TransitionTable transitions;
    private final Map<String, Socket> sockets;
    private final int version;

    private AnimationSet(Builder b) {
        this.name = b.name;
        this.skeleton = b.skeleton;
        this.clips = Collections.unmodifiableMap(new LinkedHashMap<>(b.clips));
        this.blendSpaces = Collections.unmodifiableMap(new LinkedHashMap<>(b.blendSpaces));
        this.transitions = new TransitionTable().putAll(b.transitions);
        this.sockets = Collections.unmodifiableMap(new LinkedHashMap<>(b.sockets));
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

        public AnimationSet build() {
            return new AnimationSet(this);
        }
    }
}
