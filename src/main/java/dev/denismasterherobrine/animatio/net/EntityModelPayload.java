package dev.denismasterherobrine.animatio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The runtime model of an entity changed (empty string: back to its normal model). */
public record EntityModelPayload(int entityId, String model) implements CustomPacketPayload {
    public static final Type<EntityModelPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animatio", "entity_model"));
    public static final StreamCodec<FriendlyByteBuf, EntityModelPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.entityId);
                buf.writeUtf(p.model, 512);
            },
            buf -> new EntityModelPayload(buf.readVarInt(), buf.readUtf(512)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
