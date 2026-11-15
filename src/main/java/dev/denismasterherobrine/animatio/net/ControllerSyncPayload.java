package dev.denismasterherobrine.animatio.net;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller commands for one entity or block entity, issued on the server at {@code time} (seconds).
 */
public record ControllerSyncPayload(boolean entity, int entityId, BlockPos pos, double time,
                                    List<ControllerCommand> commands) implements CustomPacketPayload {
    public static final Type<ControllerSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animatio", "controller_sync"));
    private static final int MAX_COMMANDS = 1024;

    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerSyncPayload> STREAM_CODEC = StreamCodec.of(
            ControllerSyncPayload::write, ControllerSyncPayload::read);

    public static ControllerSyncPayload forEntity(int id, double time, List<ControllerCommand> commands) {
        return new ControllerSyncPayload(true, id, BlockPos.ZERO, time, commands);
    }

    public static ControllerSyncPayload forBlockEntity(BlockPos pos, double time, List<ControllerCommand> commands) {
        return new ControllerSyncPayload(false, 0, pos, time, commands);
    }

    private static void write(RegistryFriendlyByteBuf buf, ControllerSyncPayload p) {
        buf.writeBoolean(p.entity);
        if (p.entity) buf.writeVarInt(p.entityId);
        else buf.writeBlockPos(p.pos);
        buf.writeDouble(p.time);
        buf.writeVarInt(p.commands.size());
        for (ControllerCommand c : p.commands) AnimatioStreamCodecs.writeCommand(buf, c);
    }

    private static ControllerSyncPayload read(RegistryFriendlyByteBuf buf) {
        boolean entity = buf.readBoolean();
        int id = entity ? buf.readVarInt() : 0;
        BlockPos pos = entity ? BlockPos.ZERO : buf.readBlockPos();
        double time = buf.readDouble();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_COMMANDS) throw new IllegalArgumentException("Too many controller commands: " + n);
        List<ControllerCommand> commands = new ArrayList<>(n);
        for (int i = 0; i < n; i++) commands.add(AnimatioStreamCodecs.readCommand(buf));
        return new ControllerSyncPayload(entity, id, pos, time, commands);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
