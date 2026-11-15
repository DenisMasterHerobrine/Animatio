package dev.denismasterherobrine.animatio.anim.runtime;

import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.model.ClipInfo;
import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import dev.denismasterherobrine.animatio.api.AnimationListener;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace1D;
import dev.denismasterherobrine.animatio.api.playback.BoneMask;
import dev.denismasterherobrine.animatio.api.playback.Easing;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playable.Sequence;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import dev.denismasterherobrine.animatio.testutil.TestModel;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.denismasterherobrine.animatio.testutil.TestModel.BODY;
import static dev.denismasterherobrine.animatio.testutil.TestModel.HEAD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerTest {
    private static final float EPS = 1e-3f;
    private static final AnimationRef JUMP = AnimationRef.of("jump");
    private static final AnimationRef FALL = AnimationRef.of("fall");
    private static final AnimationRef BLINK = AnimationRef.of("blink");
    private static final AnimationRef SPIN = AnimationRef.of("spin");
    private static final AnimationRef POSE_A = AnimationRef.of("pose_a");
    private static final AnimationRef POSE_B = AnimationRef.of("pose_b");
    private static final AnimationRef IDLE = AnimationRef.of("idle");
    private static final AnimationRef WALK = AnimationRef.of("walk");
    private static final AnimationRef RUN = AnimationRef.of("run");
    private static final TransitionConfig LINEAR_1S = TransitionConfig.of(1f, Easing.LINEAR);

    private AnimationSet set;
    private ControllerImpl ctl;

    @BeforeEach
    void setUp() {
        set = TestModel.set();
        ctl = client(set);
    }

    private static ControllerImpl client(AnimationSet set) {
        ControllerImpl c = new ControllerImpl(ControllerImpl.Side.CLIENT);
        c.bind(set);
        c.update(0);
        return c;
    }

    private float bodyY(ControllerImpl c) {
        assertTrue(c.evaluate());
        return c.pose().t[BODY * 3 + 1];
    }

    private float bodyX(ControllerImpl c) {
        assertTrue(c.evaluate());
        return c.pose().t[BODY * 3];
    }

    private float headScaleY(ControllerImpl c) {
        assertTrue(c.evaluate());
        return c.pose().s[HEAD * 3 + 1];
    }

    private static void stepTo(ControllerImpl c, double from, double to, double step) {
        for (double t = from + step; t < to + 1e-9; t += step) c.update(t);
        c.update(to);
    }

    // ------------------------------------------------------------------ sequences

    @Test
    void jumpWaitFallHold() {
        ctl.play(JUMP.thenWaitSecs(10).thenPlay(FALL).thenHoldLastFrame());
        ctl.update(0.5);
        assertEquals(1.5f, bodyY(ctl), EPS);
        ctl.update(5);
        assertEquals(2f, bodyY(ctl), EPS, "waiting holds the last pose of the jump");
        assertTrue(ctl.isPlaying(JUMP));
        ctl.update(11.25);
        assertEquals(1f, bodyY(ctl), EPS, "fall is 0.25s in");
        assertTrue(ctl.isPlaying(FALL), "a track matches the clip it is currently showing");
        ctl.update(20);
        assertEquals(0f, bodyY(ctl), EPS, "holds the last frame of the fall");
        assertTrue(ctl.isPlaying(FALL));
    }

    @Test
    void sequenceWithoutHoldFadesOutAndIsRemoved() {
        List<String> finished = new ArrayList<>();
        ctl.addListener(new AnimationListener() {
            @Override
            public void onFinish(AnimationController c, int id, String key) {
                finished.add(key);
            }
        });
        ctl.play(JUMP.thenPlay(FALL));
        ctl.update(1.6);
        assertEquals(List.of("jump"), finished);
        assertFalse(ctl.isPlaying(JUMP), "fading out counts as not playing");
        ctl.update(3);
        assertEquals(0, ctl.trackCount());
        assertEquals(1f, bodyY(ctl), EPS, "back to rest pose");
    }

    @Test
    void stepsBlendIntoEachOther() {
        // pose_a loops by default; times(1) makes the sequence move on after one play
        ctl.play(Sequence.of(POSE_A.times(1)).thenPlay(POSE_B.once(), LINEAR_1S),
                PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1.5);
        assertEquals(3f, bodyY(ctl), EPS, "halfway through a 1s linear step blend from y=2 to y=4");
    }

    @Test
    void repeatLoopsWholeSequence() {
        ctl.play(JUMP.thenPlay(FALL).thenRepeat(), PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1.5 + 0.5);
        // second pass: jump is 0.5s in
        assertEquals(1.5f, bodyY(ctl), 0.02f);
        assertTrue(ctl.isPlaying(JUMP));
    }

    @Test
    void fireStepsInSequence() {
        List<String> fired = new ArrayList<>();
        ctl.addListener(new AnimationListener() {
            @Override
            public void onEvent(AnimationController c, int id, String clip, AnimationEvent event) {
                fired.add("fire");
            }
        });
        ctl.play(JUMP.thenFire(AnimationEvent.local(c -> { })).thenHoldLastFrame());
        ctl.update(0.9);
        assertEquals(0, fired.size());
        ctl.update(1.1);
        assertEquals(1, fired.size());
    }

    @Test
    void sequenceBuilderRejectsStepsAfterHold() {
        assertThrows(IllegalStateException.class, () -> JUMP.thenHoldLastFrame().thenPlay(FALL));
    }

    // ------------------------------------------------------------------ parallel tracks

    @Test
    void parallelTracksAffectTheirOwnBones() {
        ctl.play(BLINK.looping(), PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.15);
        assertEquals(0.1f, headScaleY(ctl), EPS, "blink still runs");
        assertEquals(1.15f, bodyY(ctl), EPS, "jump runs alongside");
        assertTrue(ctl.isPlaying(BLINK));
        assertTrue(ctl.isPlaying(JUMP));
    }

    @Test
    void laterTrackOverridesSharedBones() {
        ctl.play(WALK, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1);
        ctl.play(POSE_B, PlayOptions.defaults().fadeIn(LINEAR_1S));
        ctl.update(1.5);
        assertEquals(0.5f, bodyX(ctl), EPS, "half-way fade over walk: x 1 -> 0");
        assertEquals(2.5f, bodyY(ctl), EPS, "half-way fade over walk: y 1 -> 4");
        ctl.update(3);
        assertEquals(4f, bodyY(ctl), EPS);
        assertTrue(ctl.isPlaying(WALK), "walk keeps playing underneath");
    }

    @Test
    void priorityBeatsPlayOrder() {
        ctl.play(POSE_B, PlayOptions.defaults().fadeIn(TransitionConfig.NONE).priority(5));
        ctl.play(POSE_A, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.1);
        assertEquals(4f, bodyY(ctl), EPS);
    }

    @Test
    void boneMaskLimitsTrack() {
        ctl.play(SPIN, PlayOptions.defaults().fadeIn(TransitionConfig.NONE).mask(BoneMask.of("body")));
        ctl.update(0.5);
        ctl.evaluate();
        assertEquals(1f, ctl.pose().r[HEAD * 4 + 3], EPS, "head is outside the mask");
    }

    @Test
    void additiveTrackAddsOffset() {
        ctl.play(POSE_A, PlayOptions.defaults().fadeIn(TransitionConfig.NONE)); // y = 2
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE).additive(true)); // +0.5 at 0.5s
        ctl.update(0.5);
        assertEquals(2.5f, bodyY(ctl), EPS);
    }

    // ------------------------------------------------------------------ transitions

    @Test
    void stopThenPlaySameTickCrossFadesWithoutRestDrift() {
        ctl.play(POSE_A, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1);
        ctl.stop(POSE_A, LINEAR_1S);
        ctl.play(POSE_B, LINEAR_1S);
        ctl.update(1.5);
        // a pure cross-fade between y=2 and y=4; layering over the rest pose (y=1) would give 2.75
        assertEquals(3f, bodyY(ctl), EPS);
        ctl.update(2.5);
        assertEquals(4f, bodyY(ctl), EPS);
        assertFalse(ctl.isPlaying(POSE_A));
        assertEquals(1, ctl.trackCount());
    }

    @Test
    void crossFadeHelper() {
        ctl.play(POSE_A, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1);
        ctl.crossFade(POSE_A, POSE_B, LINEAR_1S);
        ctl.update(1.25);
        assertEquals(2.5f, bodyY(ctl), EPS);
    }

    @Test
    void transitionTableResolvesPairDuration() {
        ctl.transitions().set(POSE_A, POSE_B, TransitionConfig.of(2f, Easing.LINEAR));
        ctl.play(POSE_A, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1);
        ctl.stop(POSE_A);
        ctl.play(POSE_B);
        ctl.update(2);
        assertEquals(3f, bodyY(ctl), EPS, "half-way through the 2s pair transition");
    }

    @Test
    void replayingSameKeyRestarts() {
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.8);
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.9);
        assertEquals(1.1f, bodyY(ctl), EPS);
        assertEquals(1, ctl.trackCount());
    }

    @Test
    void stopFadesToRest() {
        ctl.play(POSE_B, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1);
        ctl.stop(POSE_B, LINEAR_1S);
        ctl.update(1.5);
        assertEquals(2.5f, bodyY(ctl), EPS);
        ctl.update(2.1);
        assertEquals(0, ctl.trackCount());
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void pauseAndResume() {
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.25);
        ctl.pause(JUMP);
        assertTrue(ctl.isPaused(JUMP));
        ctl.update(10);
        assertEquals(1.25f, bodyY(ctl), EPS);
        assertTrue(ctl.isPlaying(JUMP));
        ctl.resume(JUMP);
        ctl.update(10.25);
        assertEquals(1.5f, bodyY(ctl), EPS);
    }

    @Test
    void pauseAllResumeAll() {
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.play(BLINK.looping(), PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.15);
        ctl.pauseAll();
        ctl.update(5);
        assertEquals(1.15f, bodyY(ctl), EPS);
        assertEquals(0.1f, headScaleY(ctl), EPS);
        ctl.resumeAll();
        ctl.update(5.25);
        assertEquals(1.4f, bodyY(ctl), EPS);
    }

    @Test
    void playAtSkipsAheadWithoutEvents() {
        List<Float> fired = new ArrayList<>();
        ctl.addListener(new AnimationListener() {
            @Override
            public void onEvent(AnimationController c, int id, String clip, AnimationEvent event) {
                fired.add(0f);
            }
        });
        AnimationRef jump = JUMP.withEvent(0.25f, AnimationEvent.local(c -> { }));
        ctl.play(jump, PlayOptions.defaults().fadeIn(TransitionConfig.NONE).startAt(0.5f));
        ctl.update(0.01);
        assertEquals(1.51f, bodyY(ctl), EPS);
        assertEquals(0.51f, ctl.clipTime(JUMP), EPS);
        assertTrue(fired.isEmpty());
    }

    @Test
    void resetClearsEverythingInstantly() {
        ctl.play(JUMP);
        ctl.play(BLINK.looping());
        ctl.setGlobalSpeed(2f);
        ctl.reset();
        assertEquals(0, ctl.trackCount());
        assertEquals(1f, ctl.globalSpeed());
        assertFalse(ctl.isAnyPlaying());
    }

    @Test
    void speedScalesPlayback() {
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.setSpeed(JUMP, 0.5f);
        ctl.update(0.5);
        assertEquals(1.25f, bodyY(ctl), EPS);
        ctl.setGlobalSpeed(2f);
        ctl.update(0.75);
        assertEquals(1.5f, bodyY(ctl), EPS);
    }

    @Test
    void stopByIdOnlyStopsThatTrack() {
        int a = ctl.play(BLINK.looping());
        int b = ctl.play(JUMP);
        assertNotEquals(a, b);
        ctl.stop(a, TransitionConfig.NONE);
        assertFalse(ctl.isPlaying(a));
        assertTrue(ctl.isPlaying(b));
    }

    // ------------------------------------------------------------------ events

    @Test
    void eventsFireOnEveryLoop() {
        List<String> fired = new ArrayList<>();
        List<String> loops = new ArrayList<>();
        ctl.addListener(new AnimationListener() {
            @Override
            public void onEvent(AnimationController c, int id, String clip, AnimationEvent event) {
                fired.add(clip);
            }

            @Override
            public void onLoop(AnimationController c, int id, String clip) {
                loops.add(clip);
            }
        });
        ctl.play(JUMP.looping().withEvent(0.25f, AnimationEvent.local(c -> { })));
        stepTo(ctl, 0, 2.6, 0.05);
        assertEquals(3, fired.size());
        assertEquals(2, loops.size());
        assertEquals("jump", fired.getFirst());
    }

    @Test
    void eventsFireThroughEventContext() {
        int[] count = {0};
        ctl.setEventContext(new BaseEventContext() {
            @Override
            public net.minecraft.world.level.Level level() {
                return null;
            }

            @Override
            public Object owner() {
                return null;
            }

            @Override
            public boolean bonePosition(String bone, org.joml.Vector3f out) {
                return false;
            }

            @Override
            public void origin(org.joml.Vector3f out) {
            }
        });
        ctl.play(JUMP.withEvent(0f, AnimationEvent.local(c -> count[0]++)).withEvent(1f, AnimationEvent.local(c -> {
            assertEquals("jump", c.clip());
            count[0]++;
        })));
        assertEquals(1, count[0], "events at time 0 fire when the step starts");
        ctl.update(0.5);
        ctl.update(1);
        assertEquals(2, count[0], "events at the very end of a non-looping clip fire");
        ctl.update(5);
        ctl.play(JUMP.withEvent(0.5f, AnimationEvent.local(c -> count[0]++)));
        ctl.update(7);
        assertEquals(2, count[0], "a long catch-up jump advances silently");
    }

    @Test
    void serverSideDoesNotFireEvents() {
        ControllerImpl server = new ControllerImpl(ControllerImpl.Side.SERVER);
        server.bind(set);
        server.update(0);
        int[] count = {0};
        server.addListener(new AnimationListener() {
            @Override
            public void onEvent(AnimationController c, int id, String clip, AnimationEvent event) {
                count[0]++;
            }
        });
        server.play(JUMP.withEvent(0.5f, AnimationEvent.local(c -> { })));
        server.update(1);
        assertEquals(0, count[0]);
    }

    // ------------------------------------------------------------------ blend spaces

    private static final BlendSpace1D LOCOMOTION = BlendSpace1D.builder("locomotion", "speed")
            .sample(0, IDLE).sample(2, WALK).sample(6, RUN).build();

    @Test
    void blendSpace1DWeights() {
        float[] w = new float[3];
        LOCOMOTION.weights(3.5f, 0, w);
        assertEquals(0f, w[0], EPS);
        assertEquals(0.625f, w[1], EPS);
        assertEquals(0.375f, w[2], EPS);
        LOCOMOTION.weights(-5f, 0, w);
        assertEquals(1f, w[0], EPS);
        LOCOMOTION.weights(100f, 0, w);
        assertEquals(1f, w[2], EPS);
    }

    @Test
    void blendSpacePlaysBlendedPose() {
        ctl.setParameter("speed", 3.5f);
        ctl.play(LOCOMOTION, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.3);
        assertEquals(1f * 0.625f + 3f * 0.375f, bodyX(ctl), EPS);
        ctl.setParameter("speed", 2f);
        ctl.update(0.4);
        assertEquals(1f, bodyX(ctl), EPS);
        assertTrue(ctl.isPlaying(LOCOMOTION));
    }

    @Test
    void blendSpaceIsPhaseSynchronised() {
        ctl.setParameter("speed", 4f); // walk (1s) and run (2s) at 50% each: cycle 1.5s
        ctl.play(LOCOMOTION, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.75);
        assertEquals(0.5f, ctl.clipTime(LOCOMOTION), EPS, "half a cycle");
    }

    @Test
    void blendSpaceInSequence() {
        ctl.setParameter("speed", 2f);
        ctl.play(JUMP.thenPlayFor(LOCOMOTION, 1f).thenPlay(FALL), PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1.5);
        assertEquals(1f, bodyX(ctl), EPS);
        ctl.update(2.25);
        assertEquals(1f, bodyY(ctl), EPS, "fall started after 1s of locomotion");
    }

    @Test
    void namedBlendSpaceFromModelDefinition() {
        AnimationSet.Builder b = AnimationSet.builder("test", set.skeleton());
        for (ClipInfo ci : set.clips()) b.clip(ci);
        b.blendSpace(LOCOMOTION);
        ControllerImpl c = client(b.build());
        c.setParameter("speed", 6f);
        c.play(AnimationRef.of("locomotion"), PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        c.update(0.1);
        assertEquals(3f, bodyX(c), EPS);
    }

    // ------------------------------------------------------------------ replication

    @Test
    void serverCommandsReplicateToClient() {
        ControllerImpl server = new ControllerImpl(ControllerImpl.Side.SERVER);
        server.bind(set);
        List<ControllerCommand> sent = new ArrayList<>();
        server.setSink(sent::add);
        server.update(0);
        ControllerImpl remote = client(set);

        int id = server.play(JUMP.thenWaitSecs(10).thenPlay(FALL).thenHoldLastFrame());
        assertTrue(id > 0);
        server.setParameter("speed", 2f);
        sent.forEach(c -> remote.applyRemote(c, 0f));
        sent.clear();

        server.update(5);
        remote.update(5);
        assertEquals(bodyY(server), bodyY(remote), EPS);
        assertEquals(2f, remote.getParameter("speed"));

        // a client that starts tracking late rebuilds the state from a snapshot
        ControllerImpl late = new ControllerImpl(ControllerImpl.Side.CLIENT);
        late.bind(set);
        late.update(5);
        server.snapshot().forEach(c -> late.applyRemote(c, 0f));
        server.update(11.25);
        remote.update(11.25);
        late.update(11.25);
        assertEquals(1f, bodyY(server), EPS);
        assertEquals(1f, bodyY(remote), EPS);
        assertEquals(1f, bodyY(late), EPS);

        server.stop(FALL);
        sent.forEach(c -> remote.applyRemote(c, 0f));
        assertFalse(remote.isPlaying(FALL));
    }

    @Test
    void commandsArrivingBeforeModelBindAreReplayedOnBind() {
        ControllerImpl c = new ControllerImpl(ControllerImpl.Side.CLIENT);
        c.update(0);
        c.play(JUMP.thenWaitSecs(10).thenPlay(FALL).thenHoldLastFrame(),
                PlayOptions.defaults().startAt(11.1f).fadeIn(TransitionConfig.NONE));
        assertFalse(c.evaluate());
        c.bind(set);
        assertEquals(1.6f, bodyY(c), EPS, "fall is 0.1s in");
    }

    @Test
    void modelReloadKeepsTracks() {
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(0.5);
        ctl.bind(TestModel.set());
        ctl.update(0.75);
        assertEquals(1.75f, bodyY(ctl), EPS);
    }

    @Test
    void boneTransformReportsModelSpace() {
        ctl.play(JUMP, PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
        ctl.update(1);
        ctl.evaluate();
        Matrix4f m = new Matrix4f();
        assertTrue(ctl.boneTransform("head", m));
        assertEquals(2.5f, m.m31(), EPS);
        assertFalse(ctl.boneTransform("tail", m));
    }
}
