package dev.denismasterherobrine.animatio.common;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand;
import dev.denismasterherobrine.animatio.net.ControllerSyncPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects server-side controller commands during a tick and sends one packet per owner at the end of the tick
 * to every player tracking it. Server thread only.
 */
public final class SyncManager {
    private record Batch(double time, List<ControllerCommand> commands) {
    }

    private static final Map<ControllerHolder, Batch> PENDING = new LinkedHashMap<>();

    private SyncManager() {
    }

    static void enqueue(ControllerHolder holder, ControllerCommand command) {
        Batch batch = PENDING.computeIfAbsent(holder, h -> new Batch(h.now(), new ArrayList<>()));
        if (command instanceof ControllerCommand.SetParameter p) {
            batch.commands.removeIf(c -> c instanceof ControllerCommand.SetParameter q && q.name().equals(p.name()));
        } else if (command instanceof ControllerCommand.Reset) {
            batch.commands.clear();
        }
        batch.commands.add(command);
    }

    public static void flush() {
        if (PENDING.isEmpty()) return;
        for (Map.Entry<ControllerHolder, Batch> e : PENDING.entrySet()) {
            ControllerHolder holder = e.getKey();
            if (!holder.isValid()) continue;
            ControllerSyncPayload payload = payload(holder, e.getValue().time, e.getValue().commands);
            if (payload == null) continue;
            if (holder.owner() instanceof Entity entity) {
                if (entity instanceof ServerPlayer) PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload);
                else PacketDistributor.sendToPlayersTrackingEntity(entity, payload);
            } else if (holder.owner() instanceof BlockEntity be && be.getLevel() instanceof ServerLevel level) {
                PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(be.getBlockPos()), payload);
            }
        }
        PENDING.clear();
    }

    public static void clear() {
        PENDING.clear();
    }

    /** Send the full state of {@code holder} to a player that just started tracking it. */
    public static void sendSnapshot(ControllerHolder holder, ServerPlayer player) {
        List<ControllerCommand> snapshot = holder.controller().snapshot();
        if (snapshot.size() <= 1 && !holder.controller().isAnyPlaying()) return; // only a Reset: nothing to send
        ControllerSyncPayload payload = payload(holder, holder.now(), snapshot);
        if (payload != null) PacketDistributor.sendToPlayer(player, payload);
    }

    private static @Nullable ControllerSyncPayload payload(ControllerHolder holder, double time, List<ControllerCommand> commands) {
        if (holder.owner() instanceof Entity entity) return ControllerSyncPayload.forEntity(entity.getId(), time, List.copyOf(commands));
        if (holder.owner() instanceof BlockEntity be) return ControllerSyncPayload.forBlockEntity(be.getBlockPos(), time, List.copyOf(commands));
        return null;
    }
}
