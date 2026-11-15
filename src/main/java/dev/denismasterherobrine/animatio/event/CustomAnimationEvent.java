package dev.denismasterherobrine.animatio.event;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.denismasterherobrine.animatio.api.event.EventContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls a handler registered under {@code id} on the client, with an optional string payload.
 * This is how mod code reacts to events that are played from the server or declared in JSON.
 */
public record CustomAnimationEvent(ResourceLocation id, String data) implements SerializableEvent {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final Map<ResourceLocation, Handler> HANDLERS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    public static final MapCodec<CustomAnimationEvent> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(CustomAnimationEvent::id),
            Codec.STRING.optionalFieldOf("data", "").forGetter(CustomAnimationEvent::data)
    ).apply(i, CustomAnimationEvent::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CustomAnimationEvent> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, CustomAnimationEvent::id,
            ByteBufCodecs.STRING_UTF8, CustomAnimationEvent::data,
            CustomAnimationEvent::new);

    @FunctionalInterface
    public interface Handler {
        void handle(EventContext context, String data);
    }

    public static void register(ResourceLocation id, Handler handler) {
        HANDLERS.put(id, handler);
    }

    public static @Nullable Handler handler(ResourceLocation id) {
        return HANDLERS.get(id);
    }

    @Override
    public void fire(EventContext context) {
        Handler handler = HANDLERS.get(id);
        if (handler == null) {
            if (WARNED.add(id)) LOGGER.warn("No handler registered for custom animation event {}", id);
            return;
        }
        handler.handle(context, data);
    }

    @Override
    public EventType<?> type() {
        return EventTypes.CUSTOM;
    }
}
