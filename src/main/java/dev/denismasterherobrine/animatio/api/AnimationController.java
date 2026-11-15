package dev.denismasterherobrine.animatio.api;

import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import dev.denismasterherobrine.animatio.api.playback.TransitionTable;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Plays animations on one animated object (entity, block entity or item).
 * <p>
 * Every {@code play} creates an independent track, so animations run in parallel:
 * <pre>{@code
 * if (!controller.isPlaying(BLINK)) controller.play(BLINK);   // keeps blinking...
 * controller.play(GREET);                                      // ...while greeting
 * }</pre>
 * Tracks are layered by priority, then by play order: a later track overrides earlier ones on the bones it
 * animates, and leaves other bones alone. When a track replaces another (same key played again,
 * {@link #crossFade}, or a {@code stop} followed by a {@code play} in the same tick) the two cross-fade.
 * <p>
 * Methods that take a {@link Playable} match tracks by {@link Playable#key()}: a track matches if its own key or
 * the clip it is currently showing has that name. Methods taking an {@code int} address one track by the id
 * returned from {@code play}.
 * <p>
 * Calls made on the logical server are sent to every client tracking the object. Calls made on the client affect
 * only that client.
 */
public interface AnimationController {

    // --- playing ---

    /** Play with default options. Returns the new track's id. */
    default int play(Playable playable) {
        return play(playable, PlayOptions.defaults());
    }

    int play(Playable playable, PlayOptions options);

    /** Play starting {@code startSecs} into the track (no events fire for the skipped part). */
    default int playAt(Playable playable, float startSecs) {
        return play(playable, PlayOptions.defaults().startAt(startSecs));
    }

    /** Play with an explicit fade-in. */
    default int play(Playable playable, TransitionConfig fadeIn) {
        return play(playable, PlayOptions.defaults().fadeIn(fadeIn));
    }

    /** Stop {@code from} and play {@code to}, cross-fading between them over {@code transition}. */
    default int crossFade(Playable from, Playable to, TransitionConfig transition) {
        stop(from, transition);
        return play(to, PlayOptions.defaults().fadeIn(transition));
    }

    // --- queries ---

    /** True while a matching track is active (playing, paused or holding its last frame; not fading out). */
    boolean isPlaying(Playable playable);

    boolean isPlaying(int trackId);

    /** True if a matching active track is paused. */
    boolean isPaused(Playable playable);

    /** True if any track is active. */
    boolean isAnyPlaying();

    /**
     * Time in seconds into the clip that a matching track is currently showing, or {@code -1} if none.
     */
    float clipTime(Playable playable);

    // --- stopping ---

    /** Fade out matching tracks using the transition table / default fade. */
    void stop(Playable playable);

    void stop(Playable playable, TransitionConfig fadeOut);

    void stop(int trackId);

    void stop(int trackId, TransitionConfig fadeOut);

    void stopAll();

    void stopAll(TransitionConfig fadeOut);

    /** Remove every track immediately, with no fade, and reset the global speed. Parameters are kept. */
    void reset();

    // --- pausing ---

    void pause(Playable playable);

    void pause(int trackId);

    void pauseAll();

    void resume(Playable playable);

    void resume(int trackId);

    void resumeAll();

    // --- speed and parameters ---

    void setSpeed(Playable playable, float speed);

    void setSpeed(int trackId, float speed);

    /** Multiplier applied to every track. */
    void setGlobalSpeed(float speed);

    float globalSpeed();

    /**
     * Set a blend-space parameter. On the server the value is synced to clients (throttled).
     * Client-side parameter providers (such as the built-in {@code speed}) overwrite values every frame.
     */
    void setParameter(String name, float value);

    float getParameter(String name);

    // --- configuration ---

    /** Per-controller transition overrides, consulted before the model's table. Not synced. */
    TransitionTable transitions();

    void addListener(AnimationListener listener);

    void removeListener(AnimationListener listener);

    // --- pose access (client) ---

    /**
     * Model-space transform of a bone or socket from the last evaluated pose (client only).
     *
     * @return {@code false} if unknown or nothing has been evaluated yet
     */
    boolean boneTransform(String boneOrSocket, Matrix4f out);

    /** The model this controller is bound to, or {@code null}. */
    @Nullable String modelName();
}
