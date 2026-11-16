package dev.denismasterherobrine.animatio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Look through camera {@code camera} of entity {@code entityId}'s model; an empty name stops. */
public record CameraViewPayload(int entityId, String camera) implements CustomPacketPayload {
    public static final Type<CameraViewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animatio", "camera_view"));
    public static final StreamCodec<FriendlyByteBuf, CameraViewPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.entityId);
                buf.writeUtf(p.camera, 256);
            },
            buf -> new CameraViewPayload(buf.readVarInt(), buf.readUtf(256)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
