package dev.denismasterherobrine.animatio.event;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of serialisable event types. Built-ins: {@code animatio:sound}, {@code animatio:particle},
 * {@code animatio:custom} and {@code animatio:none}.
 * <p>
 * Mods can register their own types during mod construction (on both sides).
 */
public final class EventTypes {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final Map<ResourceLocation, EventType<?>> TYPES = new ConcurrentHashMap<>();
    private static volatile boolean warnedLocal;

    public static final EventType<NoopEvent> NONE = register(new EventType<>(id("none"), MapCodec.unit(NoopEvent.INSTANCE),
            StreamCodec.unit(NoopEvent.INSTANCE)));
    public static final EventType<SoundAnimationEvent> SOUND = register(new EventType<>(id("sound"), SoundAnimationEvent.CODEC,
            SoundAnimationEvent.STREAM_CODEC));
    public static final EventType<ParticleAnimationEvent> PARTICLE = register(new EventType<>(id("particle"), ParticleAnimationEvent.CODEC,
            ParticleAnimationEvent.STREAM_CODEC));
    public static final EventType<CustomAnimationEvent> CUSTOM = register(new EventType<>(id("custom"), CustomAnimationEvent.CODEC,
            CustomAnimationEvent.STREAM_CODEC));

    private static final Codec<EventType<?>> TYPE_CODEC = ResourceLocation.CODEC.comapFlatMap(
            key -> {
                EventType<?> type = TYPES.get(key);
                return type != null ? DataResult.success(type) : DataResult.error(() -> "Unknown animation event type " + key);
            },
            EventType::id);

    /** JSON codec: {@code {"type": "animatio:sound", ...}}. Non-serialisable events encode as {@code animatio:none}. */
    public static final Codec<AnimationEvent> CODEC = TYPE_CODEC.<AnimationEvent>dispatch("type", EventTypes::typeOf, EventTypes::mapCodec)
            .xmap(e -> e, EventTypes::canonical);

    /** Inline form, for objects that carry other fields next to the event (such as {@code time}). */
    public static final MapCodec<AnimationEvent> MAP_CODEC = TYPE_CODEC.<AnimationEvent>dispatchMap("type", EventTypes::typeOf, EventTypes::mapCodec)
            .xmap(e -> e, EventTypes::canonical);

    /** {@code {"time": 0.25, "type": "animatio:sound", "sound": "..."}} */
    public static final Codec<dev.denismasterherobrine.animatio.api.event.EventMarker> MARKER_CODEC =
            com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
                    Codec.floatRange(0f, Float.MAX_VALUE).fieldOf("time").forGetter(dev.denismasterherobrine.animatio.api.event.EventMarker::time),
                    MAP_CODEC.forGetter(dev.denismasterherobrine.animatio.api.event.EventMarker::event)
            ).apply(i, dev.denismasterherobrine.animatio.api.event.EventMarker::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimationEvent> STREAM_CODEC = StreamCodec.of(
            EventTypes::encode, EventTypes::decode);

    private EventTypes() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("animatio", path);
    }

    public static <T extends AnimationEvent> EventType<T> register(EventType<T> type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) throw new IllegalStateException("Duplicate animation event type " + type.id());
        return type;
    }

    public static EventType<?> typeOf(AnimationEvent event) {
        if (event instanceof SerializableEvent s) return s.type();
        if (!warnedLocal) {
            warnedLocal = true;
            LOGGER.warn("A local (lambda) animation event cannot be sent to clients and is dropped; use Events.custom(...) "
                    + "with a registered handler for events in server-side plays (event: {})", event);
        }
        return NONE;
    }

    @SuppressWarnings("unchecked")
    private static MapCodec<AnimationEvent> mapCodec(EventType<?> type) {
        return (MapCodec<AnimationEvent>) type.codec();
    }

    /** The instance the event's type codecs can encode ({@link NoopEvent} for local events). */
    public static AnimationEvent canonical(AnimationEvent event) {
        return event instanceof SerializableEvent s ? s.canonical() : NoopEvent.INSTANCE;
    }

    @SuppressWarnings("unchecked")
    private static void encode(RegistryFriendlyByteBuf buf, AnimationEvent event) {
        EventType<?> type = typeOf(event);
        AnimationEvent value = canonical(event);
        buf.writeResourceLocation(type.id());
        ((StreamCodec<RegistryFriendlyByteBuf, AnimationEvent>) (StreamCodec<?, ?>) type.streamCodec()).encode(buf, value);
    }

    private static AnimationEvent decode(RegistryFriendlyByteBuf buf) {
        ResourceLocation key = buf.readResourceLocation();
        EventType<?> type = TYPES.get(key);
        if (type == null) throw new IllegalArgumentException("Unknown animation event type " + key);
        return type.streamCodec().decode(buf);
    }
}
