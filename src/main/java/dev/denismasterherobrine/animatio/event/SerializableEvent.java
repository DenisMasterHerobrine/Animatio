package dev.denismasterherobrine.animatio.event;

import dev.denismasterherobrine.animatio.api.event.AnimationEvent;

/**
 * An event that can be written to JSON and sent to clients.
 */
public interface SerializableEvent extends AnimationEvent {
    EventType<?> type();

    /** The instance the type's codecs encode. Builders return the built record. */
    default SerializableEvent canonical() {
        return this;
    }
}
