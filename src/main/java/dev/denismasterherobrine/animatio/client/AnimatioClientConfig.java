package dev.denismasterherobrine.animatio.client;

import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client settings ({@code config/animatio-client.toml}).
 */
public final class AnimatioClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue GPU_SKINNING;
    public static final ModConfigSpec.DoubleValue LOD_DISTANCE_SCALE;
    public static final ModConfigSpec.BooleanValue PARALLEL_POSES;
    public static final ModConfigSpec.BooleanValue BATCH_CPU_PATH;
    public static final ModConfigSpec.IntValue WORKER_THREADS;

    private static volatile boolean gpuSkinning = true;
    private static volatile boolean parallelPoses = true;
    private static volatile boolean batchCpuPath = true;
    private static volatile int workerThreads = -1;
    private static volatile boolean forceFeedback = Boolean.getBoolean("animatio.forceFeedbackSkinning");

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        GPU_SKINNING = b.comment("Skin and draw models on the GPU with instancing. Turned off automatically while a shader pack is active.")
                .define("gpuSkinning", true);
        LOD_DISTANCE_SCALE = b.comment("Multiplier for the distances at which distant models update their pose less often (0 = always every frame).")
                .defineInRange("lodDistanceScale", 1.0, 0.0, 16.0);
        PARALLEL_POSES = b.comment("Evaluate the poses of visible models on worker threads while terrain renders.")
                .define("parallelPoses", true);
        BATCH_CPU_PATH = b.comment("On the CPU (compatibility) path, collect models and draw them grouped by render type, skinning in parallel.",
                        "Not used while a shader pack is active.")
                .define("batchCpuPath", true);
        WORKER_THREADS = b.comment("Worker threads for pose evaluation and CPU skinning: -1 = half the CPU cores (at most 8), 0 = none.")
                .defineInRange("workerThreads", -1, -1, 32);
        SPEC = b.build();
    }

    private AnimatioClientConfig() {
    }

    static void reload() {
        gpuSkinning = GPU_SKINNING.get();
        parallelPoses = PARALLEL_POSES.get();
        batchCpuPath = BATCH_CPU_PATH.get();
        workerThreads = WORKER_THREADS.get();
        AnimatedInstance.lodDistanceScale = LOD_DISTANCE_SCALE.get().floatValue();
    }

    public static boolean gpuSkinning() {
        return gpuSkinning;
    }

    /** Runtime switch (not saved), e.g. for A/B comparisons in tests. */
    public static void setGpuSkinning(boolean enabled) {
        gpuSkinning = enabled;
    }

    public static boolean parallelPoses() {
        return parallelPoses;
    }

    public static void setParallelPoses(boolean enabled) {
        parallelPoses = enabled;
    }

    public static boolean batchCpuPath() {
        return batchCpuPath;
    }

    public static void setBatchCpuPath(boolean enabled) {
        batchCpuPath = enabled;
    }

    /**
     * Use transform-feedback GPU skinning (the shader-pack path) even without a shader pack, instead of instanced
     * batches. For testing; runtime only ({@code -Danimatio.forceFeedbackSkinning=true}).
     */
    public static boolean forceFeedbackSkinning() {
        return forceFeedback;
    }

    public static void setForceFeedbackSkinning(boolean force) {
        forceFeedback = force;
    }

    /** -1 = automatic. */
    public static int workerThreads() {
        return workerThreads;
    }
}
