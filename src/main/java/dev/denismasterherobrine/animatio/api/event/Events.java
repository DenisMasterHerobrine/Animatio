package dev.denismasterherobrine.animatio.api.event;

import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.event.CustomAnimationEvent;
import dev.denismasterherobrine.animatio.event.EventType;
import dev.denismasterherobrine.animatio.event.EventTypes;
import dev.denismasterherobrine.animatio.event.ParticleAnimationEvent;
import dev.denismasterherobrine.animatio.event.SerializableEvent;
import dev.denismasterherobrine.animatio.event.SoundAnimationEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Optional;

/**
 * Factories for the built-in, network-safe animation events. The returned builders are immutable and are
 * themselves usable as events.
 * <pre>{@code
 * AnimationRef WALK = AnimationRef.of("walk")
 *         .withEvent(0.25f, Events.sound(SoundEvents.ZOMBIE_STEP).atBone("foot_l"))
 *         .withEvent(0.75f, Events.sound(SoundEvents.ZOMBIE_STEP).atBone("foot_r"));
 * }</pre>
 */
public final class Events {
    private Events() {
    }

    public static SoundBuilder sound(SoundEvent sound) {
        return new SoundBuilder(sound.getLocation(), 1f, 1f, 0f, null);
    }

    public static SoundBuilder sound(Holder<SoundEvent> sound) {
        return sound(sound.value());
    }

    public static SoundBuilder sound(ResourceLocation sound) {
        return new SoundBuilder(sound, 1f, 1f, 0f, null);
    }

    public static ParticleBuilder particle(ParticleOptions particle) {
        return new ParticleBuilder(particle, 1, new Vector3f(), 0f, null);
    }

    /** An event handled by a handler registered with {@link AnimatioApi#registerEventHandler}. Fires on the client. */
    public static AnimationEvent custom(ResourceLocation id) {
        return new CustomAnimationEvent(id, "");
    }

    public static AnimationEvent custom(ResourceLocation id, String data) {
        return new CustomAnimationEvent(id, data);
    }

    public record SoundBuilder(ResourceLocation sound, float volume, float pitch, float pitchVariance,
                               @Nullable String bone) implements SerializableEvent {
        public SoundBuilder volume(float volume) {
            return new SoundBuilder(sound, volume, pitch, pitchVariance, bone);
        }

        public SoundBuilder pitch(float pitch) {
            return new SoundBuilder(sound, volume, pitch, pitchVariance, bone);
        }

        public SoundBuilder pitchVariance(float variance) {
            return new SoundBuilder(sound, volume, pitch, variance, bone);
        }

        public SoundBuilder atBone(String bone) {
            return new SoundBuilder(sound, volume, pitch, pitchVariance, bone);
        }

        public SoundAnimationEvent build() {
            return new SoundAnimationEvent(sound, volume, pitch, pitchVariance, Optional.ofNullable(bone));
        }

        @Override
        public void fire(EventContext context) {
            build().fire(context);
        }

        @Override
        public EventType<?> type() {
            return EventTypes.SOUND;
        }

        @Override
        public SerializableEvent canonical() {
            return build();
        }
    }

    public record ParticleBuilder(ParticleOptions particle, int count, Vector3f spread, float speed,
                                  @Nullable String bone) implements SerializableEvent {
        public ParticleBuilder count(int count) {
            return new ParticleBuilder(particle, count, spread, speed, bone);
        }

        public ParticleBuilder spread(float x, float y, float z) {
            return new ParticleBuilder(particle, count, new Vector3f(x, y, z), speed, bone);
        }

        public ParticleBuilder speed(float speed) {
            return new ParticleBuilder(particle, count, spread, speed, bone);
        }

        public ParticleBuilder atBone(String bone) {
            return new ParticleBuilder(particle, count, spread, speed, bone);
        }

        public ParticleAnimationEvent build() {
            return new ParticleAnimationEvent(particle, count, new Vector3f(spread), speed, Optional.ofNullable(bone));
        }

        @Override
        public void fire(EventContext context) {
            build().fire(context);
        }

        @Override
        public EventType<?> type() {
            return EventTypes.PARTICLE;
        }

        @Override
        public SerializableEvent canonical() {
            return build();
        }
    }
}
