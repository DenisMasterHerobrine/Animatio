package dev.denismasterherobrine.animatio.client;

import dev.denismasterherobrine.animatio.Animatio;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.override.EntityOverrides;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.client.render.ClientClock;
import dev.denismasterherobrine.animatio.client.render.FramePipeline;
import dev.denismasterherobrine.animatio.client.render.PoseScheduler;
import dev.denismasterherobrine.animatio.client.render.gpu.GpuRenderer;
import dev.denismasterherobrine.animatio.client.render.gpu.GpuShaders;
import dev.denismasterherobrine.animatio.common.ClientBridge;
import dev.denismasterherobrine.animatio.common.ControllerTicker;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client entry point.
 */
@Mod(value = Animatio.MODID, dist = Dist.CLIENT)
public final class AnimatioClient {
    public AnimatioClient(IEventBus modBus, ModContainer container) {
        ClientBridge.Holder.set(new ClientBridgeImpl());
        container.registerConfig(ModConfig.Type.CLIENT, AnimatioClientConfig.SPEC);
        modBus.addListener(AnimatioClient::onRegisterReloadListeners);
        modBus.addListener(AnimatioClient::onRegisterShaders);
        modBus.addListener((ModConfigEvent.Loading e) -> onConfig(e.getConfig()));
        modBus.addListener((ModConfigEvent.Reloading e) -> onConfig(e.getConfig()));

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(AnimatioClient::onClientTickPre);
        bus.addListener(AnimatioClient::onClientTickPost);
        bus.addListener(AnimatioClient::onRenderFrame);
        bus.addListener(AnimatioClient::onLoggingOut);
        bus.addListener((RenderLevelStageEvent e) -> FramePipeline.onRenderStage(e));

        // render-thread controller calls wait for background pose evaluation first
        ControllerImpl.setConcurrencyGuard(PoseScheduler::awaitPending);
    }

    private static void onConfig(ModConfig config) {
        if (config.getSpec() == AnimatioClientConfig.SPEC) AnimatioClientConfig.reload();
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(ClientModelManager.INSTANCE);
        event.registerReloadListener(EntityOverrides.INSTANCE);
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {
        GpuShaders.onRegisterShaders(event);
        GpuRenderer.onShadersReloaded();
    }

    private static void onClientTickPre(ClientTickEvent.Pre event) {
        ClientClock.beginTick();
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        ControllerTicker.tickClient();
    }

    private static void onRenderFrame(RenderFrameEvent.Pre event) {
        ClientClock.beginFrame(event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FramePipeline.release();
        ControllerTicker.clearClient();
    }
}
