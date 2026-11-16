package dev.denismasterherobrine.animatio.dev;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand;
import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.AnimationListener;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import dev.denismasterherobrine.animatio.net.ControllerSyncPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side integration tests: run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(AnimatioDev.MODID)
@PrefixGameTestTemplate(false)
public final class AnimatioGameTests {
    private static final AnimationRef JUMP = AnimationRef.of("jump");
    private static final AnimationRef FALL = AnimationRef.of("fall");

    private AnimatioGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void robotLoadsModelAndAnimatesOnServer(GameTestHelper helper) {
        RobotEntity robot = helper.spawn(AnimatioDev.ROBOT.get(), 2, 1, 2);
        List<String> started = new ArrayList<>();
        robot.animationController().addListener(new AnimationListener() {
            @Override
            public void onStart(AnimationController c, int id, String key) {
                started.add(key);
            }
        });
        helper.runAfterDelay(5, () -> {
            ControllerHolder holder = AnimatioApi.holder(robot);
            helper.assertTrue(AnimatioDev.ROBOT_MODEL.equals(holder.model()), "robot is bound to its model");
            helper.assertTrue(holder.controller().animationSet() != null, "server loaded the model from the mod files");
            helper.assertTrue(holder.controller().animationSet().clip("walk") != null, "walk clip present");
            helper.assertTrue(holder.controller().animationSet().blendSpace("locomotion") != null, "blend space from robot.anim.json");
            helper.assertTrue(robot.animationController().isPlaying(RobotEntity.LOCOMOTION), "locomotion is playing");
        });
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(started.contains("blink"), "blink was scheduled every 30 ticks, got " + started);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void runtimeModelOnVanillaEntity(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Pig pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG, 2, 1, 2);
        AnimatioApi.setModel(pig, AnimatioDev.ROBOT_MODEL);
        ControllerHolder holder = AnimatioApi.holder(pig);
        holder.refreshBinding();
        helper.assertTrue(AnimatioDev.ROBOT_MODEL.equals(AnimatioApi.runtimeModel(pig)), "runtime model stored");
        helper.assertTrue(AnimatioDev.ROBOT_MODEL.equals(holder.model()), "pig is bound to the runtime model");
        var set = holder.controller().animationSet();
        helper.assertTrue(set != null && set.clip("antenna") != null, "server loaded the robot, weights clip included");
        helper.assertTrue(set.skeleton().findMorph("antenna").length == 1, "morph target names come from extras.targetNames");
        helper.assertTrue(set.socket("eye_camera") != null, "socket configured in code");
        AnimationController c = AnimatioApi.controller(pig);
        c.setMorphWeight("chest", 0.5f);
        helper.assertTrue(c.getParameter("morph:chest") == 0.5f, "morph override stored as a synced parameter");
        AnimatioApi.setModel(pig, null);
        holder.refreshBinding();
        helper.assertTrue(holder.model() == null, "back to no model");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sequenceTimingAndSnapshot(GameTestHelper helper) {
        RobotEntity robot = helper.spawn(AnimatioDev.ROBOT.get(), 2, 1, 2);
        AnimationController controller = robot.animationController();
        controller.play(JUMP.thenWaitSecs(1).thenPlay(FALL).thenHoldLastFrame());
        helper.runAfterDelay(10, () -> helper.assertTrue(controller.isPlaying(JUMP), "jump (0.6s) then waiting"));
        helper.runAfterDelay(36, () -> {
            helper.assertTrue(controller.isPlaying(FALL), "fall starts after jump + 1s wait");
            ControllerHolder holder = AnimatioApi.holder(robot);
            List<ControllerCommand> snapshot = holder.controller().snapshot();
            helper.assertTrue(snapshot.stream().anyMatch(c -> c instanceof ControllerCommand.Play p && p.playable().key().equals("jump")),
                    "snapshot contains the sequence");
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            ControllerSyncPayload payload = ControllerSyncPayload.forEntity(robot.getId(), holder.now(), snapshot);
            ControllerSyncPayload.STREAM_CODEC.encode(buf, payload);
            helper.assertTrue(ControllerSyncPayload.STREAM_CODEC.decode(buf).equals(payload), "snapshot survives the network codec");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void robotDefinitionParsesEvents(GameTestHelper helper) {
        var set = dev.denismasterherobrine.animatio.data.ServerModelRepository.get(AnimatioDev.ROBOT_MODEL);
        helper.assertTrue(set != null, "robot model loads on the server");
        var walk = set.clip("walk");
        helper.assertTrue(walk != null && walk.loop() && walk.markers().size() == 2, "walk loops with two footstep sounds");
        helper.assertTrue(set.clip("jump").markers().size() == 1, "jump has a particle event");
        helper.assertTrue(set.clip("wave").markers().getFirst().event() instanceof dev.denismasterherobrine.animatio.event.CustomAnimationEvent,
                "wave fires a custom event");
        var pair = set.transitions().findPair("fall", "get_up");
        helper.assertTrue(pair != null && Math.abs(pair.duration() - 0.35f) < 1e-6f, "fall -> get_up transition");
        helper.assertTrue(set.socket("right_hand") != null, "right_hand socket");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void interactionWavesInParallel(GameTestHelper helper) {
        RobotEntity robot = helper.spawn(AnimatioDev.ROBOT.get(), 2, 1, 2);
        helper.runAfterDelay(2, () -> {
            robot.animationController().play(RobotEntity.WAVE);
            helper.assertTrue(robot.animationController().isPlaying(RobotEntity.WAVE), "wave playing");
            helper.assertTrue(robot.animationController().isPlaying(RobotEntity.LOCOMOTION), "locomotion still playing");
            helper.succeed();
        });
    }
}
