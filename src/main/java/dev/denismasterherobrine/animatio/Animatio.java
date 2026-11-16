package dev.denismasterherobrine.animatio;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.animatio.common.AnimatioAttachments;
import dev.denismasterherobrine.animatio.common.AnimatioCommand;
import dev.denismasterherobrine.animatio.common.ControllerTicker;
import dev.denismasterherobrine.animatio.common.EntityModels;
import dev.denismasterherobrine.animatio.common.SyncManager;
import dev.denismasterherobrine.animatio.data.ServerModelRepository;
import dev.denismasterherobrine.animatio.net.AnimatioNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Animatio: glTF animation engine. Public API: {@link dev.denismasterherobrine.animatio.api.AnimatioApi}.
 */
@Mod(Animatio.MODID)
public class Animatio {
    public static final String MODID = "animatio";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Animatio(IEventBus modBus) {
        AnimatioAttachments.register(modBus);
        dev.denismasterherobrine.animatio.common.AnimatioComponents.register(modBus);
        modBus.addListener(AnimatioNetwork::register);

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(Animatio::onServerTick);
        bus.addListener(Animatio::onStartTracking);
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> selfModel(e.getEntity()));
        bus.addListener((PlayerEvent.PlayerRespawnEvent e) -> selfModel(e.getEntity()));
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> selfModel(e.getEntity()));
        bus.addListener(Animatio::onChunkSent);
        bus.addListener(Animatio::onServerStopped);
        bus.addListener(Animatio::onAddReloadListeners);
        bus.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) -> AnimatioCommand.register(e.getDispatcher()));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        ControllerTicker.tickServer();
        SyncManager.flush();
    }

    private static void onStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (event.getEntity() instanceof ServerPlayer tracker) EntityModels.sendTo(tracker, target);
        if (event.getEntity() instanceof ServerPlayer player && target.hasData(AnimatioAttachments.CONTROLLER)) {
            SyncManager.sendSnapshot(target.getData(AnimatioAttachments.CONTROLLER), player);
        }
    }

    /** A player's own client learns its runtime model when it (re)enters a level. */
    private static void selfModel(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer sp) EntityModels.sendTo(sp, sp);
    }

    private static void onChunkSent(ChunkWatchEvent.Sent event) {
        for (BlockEntity be : event.getChunk().getBlockEntities().values()) {
            if (be.hasData(AnimatioAttachments.CONTROLLER)) {
                SyncManager.sendSnapshot(be.getData(AnimatioAttachments.CONTROLLER), event.getPlayer());
            }
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SyncManager.clear();
        ControllerTicker.clearServer();
        ServerModelRepository.clear();
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        // models live in mod files, not data packs, but /reload is the natural moment to pick up edits in dev
        event.addListener((ResourceManagerReloadListener) manager -> ServerModelRepository.clear());
    }
}
