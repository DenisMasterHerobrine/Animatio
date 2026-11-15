package dev.denismasterherobrine.animatio.api;

import dev.denismasterherobrine.animatio.api.event.AnimationEvent;

/**
 * Lifecycle callbacks for tracks of an {@link AnimationController}. All methods are optional.
 * <p>
 * {@link #onEvent} is called on the client only; the other callbacks run on whichever side the controller lives.
 */
public interface AnimationListener {
    /** A track started playing. */
    default void onStart(AnimationController controller, int trackId, String key) {
    }

    /** A clip of the track wrapped around to its start. */
    default void onLoop(AnimationController controller, int trackId, String clip) {
    }

    /** A track reached the end of its sequence (it now fades out, unless it holds its last frame). */
    default void onFinish(AnimationController controller, int trackId, String key) {
    }

    /** A track was removed (stopped and faded out, finished, replaced or reset). */
    default void onRemoved(AnimationController controller, int trackId, String key) {
    }

    /** An event fired on a track. */
    default void onEvent(AnimationController controller, int trackId, String clip, AnimationEvent event) {
    }
}
