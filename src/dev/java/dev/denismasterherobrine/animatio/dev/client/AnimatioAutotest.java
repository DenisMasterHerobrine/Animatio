package dev.denismasterherobrine.animatio.dev.client;

import dev.denismasterherobrine.animatio.client.AnimatioClientConfig;
import dev.denismasterherobrine.animatio.client.render.RenderStats;
import dev.denismasterherobrine.animatio.client.render.gpu.GpuRenderer;
import dev.denismasterherobrine.animatio.common.ControllerTicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unattended visual check ({@code ./gradlew runAutotestClient}): creates a flat world, spawns robots and a statue,
 * takes screenshots on the GPU path, the CPU path and during the dissolve death effect, then quits.
 * Screenshots land in {@code run/screenshots/}.
 */
final class AnimatioAutotest {
    private static final Logger LOGGER = LoggerFactory.getLogger("AnimatioAutotest");
    private static boolean worldCreated;
    private static int ticks;

    private AnimatioAutotest() {
    }

    static void install() {
        if (!Boolean.getBoolean("animatio.autotest")) return;
        LOGGER.info("Animatio autotest enabled");
        NeoForge.EVENT_BUS.addListener(AnimatioAutotest::onScreen);
        NeoForge.EVENT_BUS.addListener(AnimatioAutotest::onTick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Pre e) -> onFrame());
    }

    private static void onScreen(ScreenEvent.Opening event) {
        if (worldCreated || !(event.getNewScreen() instanceof TitleScreen)) return;
        worldCreated = true;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            LevelSettings settings = new LevelSettings("animatio_autotest", GameType.CREATIVE, false, Difficulty.EASY, true,
                    rules, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("animatio_autotest_" + System.currentTimeMillis(), settings,
                    new WorldOptions(1234L, false, false),
                    access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    new TitleScreen());
        });
    }

    private static void command(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(command);
    }

    private static void screenshot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> LOGGER.info("{}", msg.getString()));
        LOGGER.info("Screenshot {} | GPU active: {} | {} | client controllers: {}", name,
                GpuRenderer.isActive(), GpuRenderer.stats(), ControllerTicker.clientCount());
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null && ticks == 0) return;
        ticks++;
        switch (ticks) {
            case 20 -> {
                mc.options.hideGui = true;
                command("time set 6000");
                command("tp @s 0.5 -60 0.5 0 12");
                command("animatio_dev spawn 9");
                command("setblock 3 -60 10 animatio_dev:robot_statue[facing=north]");
            }
            case 30 -> {
                command("item replace entity @e[type=animatio_dev:robot,limit=3,sort=nearest] armor.head with minecraft:golden_helmet");
                command("item replace entity @e[type=animatio_dev:robot,limit=5,sort=nearest] weapon.mainhand with minecraft:diamond_sword");
                // the three nearest stand still facing the camera, to check attachments from the front
                command("data merge entity @e[type=animatio_dev:robot,limit=3,sort=nearest] {NoAI:1b}");
                command("execute as @e[type=animatio_dev:robot,limit=3,sort=nearest] at @s run tp @s ~ ~ ~ 180 0");
                command("animatio play @e[type=animatio_dev:robot,limit=2,sort=furthest] wave");
                // vanilla mob replaced by a resource-pack entity override (entity_overrides/minecraft/husk.json)
                command("summon minecraft:husk -3 -60 5 {NoAI:1b,Rotation:[200f,0f],"
                        + "ArmorItems:[{},{},{},{id:\"minecraft:iron_helmet\",count:1}],HandItems:[{id:\"minecraft:iron_axe\",count:1},{}]}");
            }
            case 50 -> screenshot("animatio_autotest_gpu.png");
            case 51 -> AnimatioClientConfig.setGpuSkinning(false);
            case 56 -> screenshot("animatio_autotest_cpu.png");
            case 57 -> {
                AnimatioClientConfig.setGpuSkinning(true);
                command("kill @e[type=animatio_dev:robot]");
            }
            case 66 -> screenshot("animatio_autotest_dissolve.png");
            case 68 -> {
                // held-item reference: a vanilla zombie and a robot side by side, both facing screen-right (west)
                String gear = "ArmorItems:[{},{},{},{id:\"minecraft:iron_helmet\",count:1}],HandItems:[{id:\"minecraft:diamond_sword\",count:1},{}]";
                command("summon minecraft:zombie -1.5 -60 4 {NoAI:1b,Silent:1b,Rotation:[90f,0f]," + gear + "}");
                command("summon animatio_dev:robot 2 -60 4 {NoAI:1b,Silent:1b,Rotation:[90f,0f]," + gear + "}");
            }
            case 78 -> screenshot("animatio_autotest_hold.png");
            case 80 -> {
                command("kill @e[type=minecraft:zombie]");
                if (PERF_COUNT <= 0) {
                    finish();
                    return;
                }
                command("kill @e[type=animatio_dev:robot]");
                command("kill @e[type=minecraft:husk]");
                command("setblock 3 -60 10 minecraft:air");
                command("tp @s 0.5 -55 -6 0 25");
                command("animatio_dev spawn " + PERF_COUNT);
                mc.options.enableVsync().set(false);
                mc.getWindow().updateVsync(false);
                mc.options.framerateLimit().set(260);
            }
            case 90 -> {
                command("data merge entity @e[type=animatio_dev:robot] {NoAI:1b}");
                perfStage = 0;
                perfTick = ticks + 100; // warm-up: JIT, chunk loading, entities settling
            }
            default -> {
                if (ticks >= 90 && PERF_COUNT > 0) perfTick();
            }
        }
    }

    private static final int PERF_COUNT = Integer.getInteger("animatio.autotest.perf", 0);
    private static final int MEASURE_TICKS = 60, SETTLE_TICKS = 15;

    /** name, GPU skinning, parallel poses, batched CPU path */
    private record PerfConfig(String name, boolean gpu, boolean parallel, boolean batchCpu) {
    }

    private static final PerfConfig[] PERF_CONFIGS = {
            new PerfConfig("GPU path, parallel poses", true, true, true),
            new PerfConfig("GPU path, serial poses", true, false, true),
            new PerfConfig("GPU path, parallel poses (repeat)", true, true, true),
            new PerfConfig("GPU path, serial poses (repeat)", true, false, true),
            new PerfConfig("CPU path, batched + parallel", false, true, true),
            new PerfConfig("CPU path, immediate + serial (old)", false, false, false),
    };
    private static final double[] perfResults = new double[PERF_CONFIGS.length];
    private static int perfStage = -1;
    private static int perfTick;
    private static boolean measuring;
    private static long lastFrame, frameNanos;
    private static int frames;

    private static void perfTick() {
        if (perfStage < 0 || ticks != perfTick) return;
        if (perfStage >= PERF_CONFIGS.length * 2) return;
        PerfConfig config = PERF_CONFIGS[perfStage / 2];
        if (perfStage % 2 == 0) {
            AnimatioClientConfig.setGpuSkinning(config.gpu);
            AnimatioClientConfig.setParallelPoses(config.parallel);
            AnimatioClientConfig.setBatchCpuPath(config.batchCpu);
            perfStage++;
            perfTick = ticks + SETTLE_TICKS;
            return;
        }
        if (!measuring) {
            startMeasuring();
            perfTick = ticks + MEASURE_TICKS;
            return;
        }
        double ms = stopMeasuring();
        perfResults[perfStage / 2] = ms;
        LOGGER.info("PERF [{}] {} robots: {} ms/frame ({} fps) | {}", config.name, PERF_COUNT, fmt(ms), fmt(1000 / ms), GpuRenderer.stats());
        logBreakdown(config.name);
        if (perfStage == 1) screenshot("animatio_autotest_perf.png");
        perfStage++;
        perfTick = ticks + 1;
        if (perfStage >= PERF_CONFIGS.length * 2) {
            StringBuilder sb = new StringBuilder("PERF summary with " + PERF_COUNT + " robots:");
            for (int i = 0; i < PERF_CONFIGS.length; i++) sb.append(" | ").append(PERF_CONFIGS[i].name).append(": ").append(fmt(perfResults[i])).append(" ms");
            LOGGER.info(sb.toString());
            AnimatioClientConfig.setGpuSkinning(true);
            AnimatioClientConfig.setParallelPoses(true);
            AnimatioClientConfig.setBatchCpuPath(true);
            finish();
        }
    }

    private static void logBreakdown(String path) {
        if (!RenderStats.ENABLED || frames == 0) return;
        double f = frames;
        var server = Minecraft.getInstance().getSingleplayerServer();
        float serverTick = server != null ? server.getCurrentSmoothedTickTime() : -1f;
        double flushes = Math.max(1, RenderStats.cpuBuildFlushes);
        LOGGER.info("PERF [{}] average evaluation cost {} us, CPU-path build CPU time {} ms/frame over all threads, "
                        + "{} threads per flush, longest chunk {} ms", path,
                fmt(dev.denismasterherobrine.animatio.client.render.PoseScheduler.averageEvaluationNanos() / 1000),
                fmt(RenderStats.cpuBuildNanos() / 1e6 / frames), fmt(RenderStats.cpuBuildThreads / flushes),
                fmt(RenderStats.cpuBuildMaxChunkNanos / 1e6 / flushes));
        LOGGER.info("PERF [{}] per frame: {} pose evaluations ({} in parallel), {} ms evaluation CPU time (all threads), "
                        + "{} ms scheduling, {} ms waiting for workers | CPU path: {} instances, {} batches, {} ms building, {} ms upload+draw "
                        + "| GPU submit {} ms | server tick {} ms",
                path, fmt(RenderStats.evaluations() / f), fmt(RenderStats.scheduledEvaluations / f), fmt(RenderStats.evaluateNanos() / 1e6 / f),
                fmt(RenderStats.scheduleNanos / 1e6 / f), fmt(RenderStats.poseWaitNanos / 1e6 / f),
                fmt(RenderStats.cpuInstances / f), fmt(RenderStats.cpuBatches / f), fmt(RenderStats.cpuSkinNanos / 1e6 / f),
                fmt(RenderStats.cpuEmitNanos / 1e6 / f), fmt(RenderStats.gpuFlushNanos / 1e6 / f), fmt(serverTick));
    }

    private static void startMeasuring() {
        RenderStats.reset();
        measuring = true;
        lastFrame = 0;
        frameNanos = 0;
        frames = 0;
    }

    private static double stopMeasuring() {
        measuring = false;
        return frames == 0 ? 0 : frameNanos / 1e6 / frames;
    }

    static void onFrame() {
        if (!measuring) return;
        long now = System.nanoTime();
        if (lastFrame != 0) {
            frameNanos += now - lastFrame;
            frames++;
        }
        lastFrame = now;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static void finish() {
        LOGGER.info("Animatio autotest finished");
        Minecraft.getInstance().stop();
    }
}
