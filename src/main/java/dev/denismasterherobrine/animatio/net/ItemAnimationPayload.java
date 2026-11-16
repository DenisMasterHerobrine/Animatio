package dev.denismasterherobrine.animatio.net;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A controller command for the item stack whose {@code animatio:animation_id} is {@code id}, issued at {@code time}. */
public record ItemAnimationPayload(long id, double time, ControllerCommand command) implements CustomPacketPayload {
    public static final Type<ItemAnimationPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animatio", "item_animation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemAnimationPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeLong(p.id);
                buf.writeDouble(p.time);
                AnimatioStreamCodecs.writeCommand(buf, p.command);
            },
            buf -> new ItemAnimationPayload(buf.readLong(), buf.readDouble(), AnimatioStreamCodecs.readCommand(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
