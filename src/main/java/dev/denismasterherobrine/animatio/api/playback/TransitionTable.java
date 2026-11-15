package dev.denismasterherobrine.animatio.api.playback;

import dev.denismasterherobrine.animatio.api.playable.Playable;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Transition durations between pairs of animations, looked up when one animation replaces another
 * (for example {@code stop(FALL)} followed by {@code play(GET_UP)} in the same tick).
 * <p>
 * Lookup order: exact pair, then {@code (from, any)}, then {@code (any, to)}, then the table default.
 * Not thread-safe; configure during setup.
 */
public final class TransitionTable {
    public static final String ANY = "*";

    private final Map<String, TransitionConfig> pairs = new HashMap<>();
    private @Nullable TransitionConfig defaultConfig;

    public TransitionTable set(Playable from, Playable to, TransitionConfig config) {
        return set(from.key(), to.key(), config);
    }

    public TransitionTable set(String from, String to, TransitionConfig config) {
        pairs.put(from + '\u0000' + to, config);
        return this;
    }

    /** Transition used whenever {@code to} starts, unless a more specific pair exists. */
    public TransitionTable setTo(Playable to, TransitionConfig config) {
        return set(ANY, to.key(), config);
    }

    /** Transition used whenever {@code from} is replaced, unless a more specific pair exists. */
    public TransitionTable setFrom(Playable from, TransitionConfig config) {
        return set(from.key(), ANY, config);
    }

    public TransitionTable setDefault(@Nullable TransitionConfig config) {
        this.defaultConfig = config;
        return this;
    }

    public @Nullable TransitionConfig defaultConfig() {
        return defaultConfig;
    }

    public TransitionTable putAll(TransitionTable other) {
        pairs.putAll(other.pairs);
        if (other.defaultConfig != null) defaultConfig = other.defaultConfig;
        return this;
    }

    /** Pair lookup without the default. {@code from} may be {@code null} when nothing is being replaced. */
    public @Nullable TransitionConfig findPair(@Nullable String from, String to) {
        if (pairs.isEmpty()) return null;
        TransitionConfig c;
        if (from != null) {
            if ((c = pairs.get(from + '\u0000' + to)) != null) return c;
            if ((c = pairs.get(from + '\u0000' + ANY)) != null) return c;
        }
        return pairs.get(ANY + '\u0000' + to);
    }

    public boolean isEmpty() {
        return pairs.isEmpty() && defaultConfig == null;
    }

    public Map<String, TransitionConfig> rawPairs() {
        return pairs;
    }
}
