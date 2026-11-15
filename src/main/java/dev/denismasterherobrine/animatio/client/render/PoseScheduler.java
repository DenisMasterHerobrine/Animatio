package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.denismasterherobrine.animatio.client.AnimatioClientConfig;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * Multithreaded pose evaluation.
 * <p>
 * At the start of level rendering (after the sky), every instance rendered in the previous frame that is still in
 * the view frustum has its controller advanced on the render thread (events fire there, as always), then its pose
 * is evaluated on {@link AnimatioWorkers} while the render thread draws terrain. The batch is joined right before
 * entities render, or earlier if anything touches a controller on the render thread. Instances that come into view
 * this frame are evaluated inline when they render.
 */
public final class PoseScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    /** Below this many poses the batch is evaluated inline: waking workers would cost more than it saves. */
    private static final int MIN_PARALLEL = 16;
    /** Below this much estimated work per frame the batch is evaluated inline as well. */
    private static final double MIN_PARALLEL_NANOS = 500_000;
    private static final int GRAIN = 4;
    /** Moving average of one evaluation's cost; starts optimistic so the first frames try the parallel path. */
    private static double averageNanos = 20_000;
    private static final java.util.concurrent.atomic.LongAdder BATCH_NANOS = new java.util.concurrent.atomic.LongAdder();
    private static int batchSize;

    private static ArrayList<AnimatedInstance> rendered = new ArrayList<>();
    private static ArrayList<AnimatedInstance> candidates = new ArrayList<>();
    private static final ArrayList<AnimatedInstance> work = new ArrayList<>();
    private static volatile AnimatioWorkers.@Nullable Job pending;
    private static boolean joining;
    private static boolean warnedScheduleFailure;

    private PoseScheduler() {
    }

    /** Called when an instance is prepared for rendering (once per frame per instance). Render thread. */
    static void markRendered(AnimatedInstance instance) {
        rendered.add(instance);
    }

    /** Start evaluating this frame's poses in the background. Render thread, once per frame. */
    public static void startFrame(Frustum frustum, Vec3 camera, float partialTick) {
        awaitPending();
        ArrayList<AnimatedInstance> previous = rendered;
        rendered = candidates;
        rendered.clear();
        candidates = previous;
        if (!AnimatioClientConfig.parallelPoses() || AnimatioWorkers.threads() == 0) {
            candidates.clear();
            return;
        }

        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        work.clear();
        for (int i = 0; i < candidates.size(); i++) {
            AnimatedInstance instance = candidates.get(i);
            try {
                if (instance.schedule(partialTick, frustum, camera)) work.add(instance);
            } catch (RuntimeException e) {
                if (!warnedScheduleFailure) {
                    warnedScheduleFailure = true;
                    LOGGER.error("Could not schedule a pose evaluation (further failures are not logged)", e);
                }
            }
        }
        candidates.clear();
        int n = work.size();
        if (n < MIN_PARALLEL || n * averageNanos < MIN_PARALLEL_NANOS) {
            long s = System.nanoTime();
            for (AnimatedInstance instance : work) instance.evaluateScheduled();
            if (n > 0) learn((System.nanoTime() - s) / (double) n);
            work.clear();
        } else {
            if (RenderStats.ENABLED) RenderStats.scheduledEvaluations += n;
            ArrayList<AnimatedInstance> batch = work;
            BATCH_NANOS.reset();
            batchSize = n;
            pending = AnimatioWorkers.start(n, GRAIN, i -> {
                long s = System.nanoTime();
                batch.get(i).evaluateScheduled();
                BATCH_NANOS.add(System.nanoTime() - s);
            });
        }
        if (RenderStats.ENABLED) RenderStats.scheduleNanos += System.nanoTime() - t0;
    }

    private static void learn(double nanosPerEvaluation) {
        averageNanos += (nanosPerEvaluation - averageNanos) * 0.1;
    }

    /**
     * Wait for background evaluation to finish (helping with what is left). Returns at once when nothing is running
     * or when called off the render thread; installed as the controllers' concurrency guard.
     */
    public static void awaitPending() {
        AnimatioWorkers.Job job = pending;
        if (job == null || joining || !RenderSystem.isOnRenderThread()) return;
        joining = true;
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        try {
            job.join();
        } finally {
            pending = null;
            joining = false;
            if (batchSize > 0) learn(BATCH_NANOS.sum() / (double) batchSize);
            batchSize = 0;
            if (RenderStats.ENABLED) RenderStats.poseWaitNanos += System.nanoTime() - t0;
        }
    }

    /** Current estimate of one pose evaluation's cost, in nanoseconds. */
    public static double averageEvaluationNanos() {
        return averageNanos;
    }

    /** Forget everything (world change). Render thread. */
    public static void clear() {
        awaitPending();
        rendered.clear();
        candidates.clear();
        work.clear();
    }
}
