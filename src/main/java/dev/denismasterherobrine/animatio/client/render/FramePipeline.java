package dev.denismasterherobrine.animatio.client.render;

import dev.denismasterherobrine.animatio.client.AnimatioClientConfig;
import dev.denismasterherobrine.animatio.client.render.gpu.GpuRenderer;
import dev.denismasterherobrine.animatio.client.render.gpu.IrisCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Per-frame orchestration, driven by the level render stages:
 * <ol>
 *     <li>after the sky: start background pose evaluation; open the entity phase;</li>
 *     <li>after cutout terrain (just before entities): join pose evaluation;</li>
 *     <li>after entities: draw the GPU batch, then the CPU batch; open the block-entity phase;</li>
 *     <li>after block entities: same for block entities.</li>
 * </ol>
 */
public final class FramePipeline {
    private static final CpuBatcher CPU_ENTITIES = new CpuBatcher();
    private static final CpuBatcher CPU_BLOCK_ENTITIES = new CpuBatcher();
    private static boolean entityPhase, blockEntityPhase;

    private FramePipeline() {
    }

    /** True while instances rendered in this context are being collected for a batched draw. */
    public static boolean inPhase(ModelRenderer.Context context) {
        return switch (context) {
            case ENTITY -> entityPhase;
            case BLOCK_ENTITY -> blockEntityPhase;
            case OTHER -> false;
        };
    }

    /**
     * The CPU batcher to defer into, or {@code null} to write immediately: outside world phases, with shader packs
     * (they need geometry inside each entity's render call), for outline buffers and for other buffer sources.
     */
    static @Nullable CpuBatcher cpuBatcher(ModelRenderer.Context context, MultiBufferSource buffers) {
        if (!inPhase(context) || !AnimatioClientConfig.batchCpuPath() || IrisCompat.isShaderPackInUse()) return null;
        if (buffers != Minecraft.getInstance().renderBuffers().bufferSource()) return null;
        return context == ModelRenderer.Context.ENTITY ? CPU_ENTITIES : CPU_BLOCK_ENTITIES;
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            clearBatches();
            entityPhase = true;
            blockEntityPhase = false;
            PoseScheduler.startFrame(event.getFrustum(), event.getCamera().getPosition(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            PoseScheduler.awaitPending();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            PoseScheduler.awaitPending();
            entityPhase = false;
            GpuRenderer.flush(ModelRenderer.Context.ENTITY, event.getModelViewMatrix(), event.getProjectionMatrix());
            CPU_ENTITIES.flush();
            blockEntityPhase = true;
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            blockEntityPhase = false;
            GpuRenderer.flush(ModelRenderer.Context.BLOCK_ENTITY, event.getModelViewMatrix(), event.getProjectionMatrix());
            CPU_BLOCK_ENTITIES.flush();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            PoseScheduler.awaitPending();
            entityPhase = false;
            blockEntityPhase = false;
            clearBatches();
        }
    }

    /** Free batch buffers (world unload). Render thread. */
    public static void release() {
        PoseScheduler.clear();
        GpuRenderer.clear();
        CPU_ENTITIES.release();
        CPU_BLOCK_ENTITIES.release();
    }

    private static void clearBatches() {
        GpuRenderer.clear();
        CPU_ENTITIES.clear();
        CPU_BLOCK_ENTITIES.clear();
    }
}
