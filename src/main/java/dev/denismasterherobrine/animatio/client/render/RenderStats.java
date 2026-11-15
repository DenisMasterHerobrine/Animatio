package dev.denismasterherobrine.animatio.client.render;

import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in render timing ({@code -Danimatio.profile=true}). Counters accumulate until {@link #reset()}.
 */
public final class RenderStats {
    public static final boolean ENABLED = Boolean.getBoolean("animatio.profile");

    /** Pose evaluations and their summed CPU time over all threads. */
    private static final LongAdder EVALUATIONS = new LongAdder();
    private static final LongAdder EVALUATE_NANOS = new LongAdder();
    /** Evaluations that ran in the parallel batch. */
    public static long scheduledEvaluations;
    /** Render-thread time spent scheduling (advancing controllers, culling) at the start of frames. */
    public static long scheduleNanos;
    /** Render-thread time spent waiting for (and helping) background evaluation. */
    public static long poseWaitNanos;
    /** Time in the CPU path: skinning (wall time, possibly parallel) and writing vertices. */
    public static long cpuSkinNanos;
    public static long cpuEmitNanos;
    /** Instances drawn on the CPU path and the render-type batches they used. */
    public static long cpuInstances;
    public static long cpuBatches;
    public static long gpuFlushNanos;

    private RenderStats() {
    }

    /** CPU-path chunk builds: summed CPU time over all threads. */
    private static final LongAdder CPU_BUILD_NANOS = new LongAdder();

    private static final java.util.Set<Thread> BUILD_THREADS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong BUILD_MAX_CHUNK = new java.util.concurrent.atomic.AtomicLong();
    /** Sum over flushes of: distinct threads that built chunks, longest chunk. */
    public static long cpuBuildThreads, cpuBuildMaxChunkNanos, cpuBuildFlushes;

    static void recordCpuBuild(long nanos, Thread thread) {
        CPU_BUILD_NANOS.add(nanos);
        BUILD_THREADS.add(thread);
        BUILD_MAX_CHUNK.accumulateAndGet(nanos, Math::max);
    }

    static void endCpuBuild() {
        cpuBuildThreads += BUILD_THREADS.size();
        cpuBuildMaxChunkNanos += BUILD_MAX_CHUNK.getAndSet(0);
        cpuBuildFlushes++;
        BUILD_THREADS.clear();
    }

    public static long cpuBuildNanos() {
        return CPU_BUILD_NANOS.sum();
    }

    static void recordEvaluation(long nanos) {
        EVALUATIONS.increment();
        EVALUATE_NANOS.add(nanos);
    }

    public static long evaluations() {
        return EVALUATIONS.sum();
    }

    public static long evaluateNanos() {
        return EVALUATE_NANOS.sum();
    }

    public static void reset() {
        EVALUATIONS.reset();
        EVALUATE_NANOS.reset();
        CPU_BUILD_NANOS.reset();
        cpuBuildThreads = 0;
        cpuBuildMaxChunkNanos = 0;
        cpuBuildFlushes = 0;
        scheduledEvaluations = 0;
        scheduleNanos = 0;
        poseWaitNanos = 0;
        cpuSkinNanos = 0;
        cpuEmitNanos = 0;
        cpuInstances = 0;
        cpuBatches = 0;
        gpuFlushNanos = 0;
    }
}
