package dev.denismasterherobrine.animatio.event;

import com.mojang.serialization.MapCodec;
import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * A serialisable kind of {@link AnimationEvent}: how it is written in JSON definitions and over the network.
 */
public record EventType<T extends AnimationEvent>(ResourceLocation id, MapCodec<T> codec,
                                                  StreamCodec<RegistryFriendlyByteBuf, T> streamCodec) {
}
