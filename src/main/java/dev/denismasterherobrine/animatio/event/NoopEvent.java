package dev.denismasterherobrine.animatio.event;

import dev.denismasterherobrine.animatio.api.event.EventContext;

/**
 * Does nothing. Stands in for events that could not be sent to the client.
 */
public final class NoopEvent implements SerializableEvent {
    public static final NoopEvent INSTANCE = new NoopEvent();

    private NoopEvent() {
    }

    @Override
    public void fire(EventContext context) {
    }

    @Override
    public EventType<?> type() {
        return EventTypes.NONE;
    }

    @Override
    public String toString() {
        return "NoopEvent";
    }
}
