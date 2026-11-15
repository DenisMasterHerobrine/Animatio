package dev.denismasterherobrine.animatio.event;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.denismasterherobrine.animatio.api.event.EventContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.Optional;

/**
 * Plays a sound at the model origin or at a bone. Client-side only (heard by the local player).
 *
 * @param pitchVariance random pitch offset in {@code [-variance, +variance]}
 * @param bone          bone or socket to play at, or empty for the model origin
 */
public record SoundAnimationEvent(ResourceLocation sound, float volume, float pitch, float pitchVariance,
                                  Optional<String> bone) implements SerializableEvent {
    public static final MapCodec<SoundAnimationEvent> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ResourceLocation.CODEC.fieldOf("sound").forGetter(SoundAnimationEvent::sound),
            Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(SoundAnimationEvent::volume),
            Codec.FLOAT.optionalFieldOf("pitch", 1f).forGetter(SoundAnimationEvent::pitch),
            Codec.FLOAT.optionalFieldOf("pitch_variance", 0f).forGetter(SoundAnimationEvent::pitchVariance),
            Codec.STRING.optionalFieldOf("bone").forGetter(SoundAnimationEvent::bone)
    ).apply(i, SoundAnimationEvent::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, SoundAnimationEvent> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, SoundAnimationEvent::sound,
            ByteBufCodecs.FLOAT, SoundAnimationEvent::volume,
            ByteBufCodecs.FLOAT, SoundAnimationEvent::pitch,
            ByteBufCodecs.FLOAT, SoundAnimationEvent::pitchVariance,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), SoundAnimationEvent::bone,
            SoundAnimationEvent::new);

    @Override
    public void fire(EventContext context) {
        Level level = context.level();
        if (level == null || !level.isClientSide()) return;
        Vector3f pos = new Vector3f();
        if (bone.isPresent()) context.bonePosition(bone.get(), pos);
        else context.origin(pos);
        float p = pitch;
        if (pitchVariance > 0f) p += (level.random.nextFloat() * 2f - 1f) * pitchVariance;
        SoundSource source = context.owner() instanceof Entity e ? e.getSoundSource() : SoundSource.BLOCKS;
        level.playLocalSound(pos.x, pos.y, pos.z, SoundEvent.createVariableRangeEvent(sound), source, volume, p, false);
    }

    @Override
    public EventType<?> type() {
        return EventTypes.SOUND;
    }
}
