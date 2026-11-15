package dev.denismasterherobrine.animatio.anim.runtime;

import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import org.jetbrains.annotations.Nullable;

/**
 * A state change of a controller. Server-side controllers emit these to be replayed on clients.
 */
public sealed interface ControllerCommand {

    /** Which tracks a command applies to. */
    record Target(int kind, int id, String key) {
        public static final int ALL = 0, ID = 1, KEY = 2;
        public static final Target ALL_TRACKS = new Target(ALL, 0, "");

        public static Target id(int id) {
            return new Target(ID, id, "");
        }

        public static Target key(String key) {
            return new Target(KEY, 0, key);
        }
    }

    /** Options always carry a resolved (non-null) fade-in. */
    record Play(int id, Playable playable, PlayOptions options) implements ControllerCommand {
    }

    /** {@code fade == null} means each side resolves the fade from the track's options and transition tables. */
    record Stop(Target target, @Nullable TransitionConfig fade) implements ControllerCommand {
    }

    record Pause(Target target) implements ControllerCommand {
    }

    record Resume(Target target) implements ControllerCommand {
    }

    record SetSpeed(Target target, float speed) implements ControllerCommand {
    }

    record Reset() implements ControllerCommand {
    }

    record SetParameter(String name, float value) implements ControllerCommand {
    }

    record SetGlobalSpeed(float speed) implements ControllerCommand {
    }
}
