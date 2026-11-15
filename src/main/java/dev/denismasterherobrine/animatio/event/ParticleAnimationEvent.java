package dev.denismasterherobrine.animatio.event;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.denismasterherobrine.animatio.api.event.EventContext;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.Optional;

/**
 * Spawns client-side particles at the model origin or at a bone.
 *
 * @param spread random offset range per axis (blocks)
 * @param speed  random velocity per axis, in {@code [-speed, +speed]}
 */
public record ParticleAnimationEvent(ParticleOptions particle, int count, Vector3f spread, float speed,
                                     Optional<String> bone) implements SerializableEvent {
    public static final MapCodec<ParticleAnimationEvent> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ParticleTypes.CODEC.fieldOf("particle").forGetter(ParticleAnimationEvent::particle),
            Codec.intRange(1, 1000).optionalFieldOf("count", 1).forGetter(ParticleAnimationEvent::count),
            ExtraCodecs.VECTOR3F.optionalFieldOf("spread", new Vector3f()).forGetter(ParticleAnimationEvent::spread),
            Codec.FLOAT.optionalFieldOf("speed", 0f).forGetter(ParticleAnimationEvent::speed),
            Codec.STRING.optionalFieldOf("bone").forGetter(ParticleAnimationEvent::bone)
    ).apply(i, ParticleAnimationEvent::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ParticleAnimationEvent> STREAM_CODEC = StreamCodec.composite(
            ParticleTypes.STREAM_CODEC, ParticleAnimationEvent::particle,
            ByteBufCodecs.VAR_INT, ParticleAnimationEvent::count,
            ByteBufCodecs.VECTOR3F, ParticleAnimationEvent::spread,
            ByteBufCodecs.FLOAT, ParticleAnimationEvent::speed,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), ParticleAnimationEvent::bone,
            ParticleAnimationEvent::new);

    @Override
    public void fire(EventContext context) {
        Level level = context.level();
        if (level == null || !level.isClientSide()) return;
        Vector3f pos = new Vector3f();
        if (bone.isPresent()) context.bonePosition(bone.get(), pos);
        else context.origin(pos);
        RandomSource random = level.random;
        for (int i = 0; i < count; i++) {
            double x = pos.x + (random.nextDouble() * 2 - 1) * spread.x;
            double y = pos.y + (random.nextDouble() * 2 - 1) * spread.y;
            double z = pos.z + (random.nextDouble() * 2 - 1) * spread.z;
            double vx = (random.nextDouble() * 2 - 1) * speed;
            double vy = (random.nextDouble() * 2 - 1) * speed;
            double vz = (random.nextDouble() * 2 - 1) * speed;
            level.addParticle(particle, x, y, z, vx, vy, vz);
        }
    }

    @Override
    public EventType<?> type() {
        return EventTypes.PARTICLE;
    }
}
