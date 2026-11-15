package dev.denismasterherobrine.animatio.api.event;

/**
 * Something that happens at a point in an animation: a footstep sound, a particle burst, a callback.
 * <p>
 * Events only fire on the client, when playback crosses their time (including across loop boundaries).
 * Seeking ({@code playAt}, late-join catch-up) never fires events.
 * <p>
 * Events attached to animations played from the server must be serialisable so they can be sent to clients.
 * The built-in events in {@link Events} are; {@link #local(Handler)} events are not and are dropped (with a
 * warning) when a server-side play is synced.
 */
@FunctionalInterface
public interface AnimationEvent {
    void fire(EventContext context);

    /** An event backed by a lambda. Works for client-side plays and client-registered model events. */
    static AnimationEvent local(Handler handler) {
        return new LocalEvent(handler);
    }

    @FunctionalInterface
    interface Handler {
        void handle(EventContext context);
    }

    record LocalEvent(Handler handler) implements AnimationEvent {
        @Override
        public void fire(EventContext context) {
            handler.handle(context);
        }
    }
}
