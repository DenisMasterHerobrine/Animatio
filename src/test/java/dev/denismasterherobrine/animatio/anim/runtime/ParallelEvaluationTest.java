package dev.denismasterherobrine.animatio.anim.runtime;

import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playable.BlendSpace1D;
import dev.denismasterherobrine.animatio.api.playback.BoneMask;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.testutil.TestModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Evaluating many controllers concurrently (each on one thread at a time, as the pose scheduler does) must give
 * exactly the same poses as evaluating them one by one.
 */
class ParallelEvaluationTest {
    private static final AnimationRef JUMP = AnimationRef.of("jump"), FALL = AnimationRef.of("fall"),
            BLINK = AnimationRef.of("blink"), SPIN = AnimationRef.of("spin"), WALK = AnimationRef.of("walk"),
            RUN = AnimationRef.of("run"), IDLE = AnimationRef.of("idle");
    private static final BlendSpace1D LOCO = BlendSpace1D.builder("loco", "speed").sample(0, IDLE).sample(2, WALK).sample(6, RUN).build();
    private static final BoneMask HEAD_ONLY = BoneMask.subtree("head"); // shared cache, written by every thread

    private static List<ControllerImpl> controllers(AnimationSet set, int count) {
        List<ControllerImpl> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ControllerImpl c = new ControllerImpl(ControllerImpl.Side.CLIENT);
            c.bind(set);
            c.update(0);
            c.setParameter("speed", (i % 7) * 0.9f);
            c.play(LOCO);
            switch (i % 5) {
                case 0 -> c.play(JUMP.thenWaitSecs(0.3f).thenPlay(FALL).thenRepeat());
                case 1 -> c.play(BLINK.looping(), PlayOptions.defaults().mask(HEAD_ONLY));
                case 2 -> c.play(SPIN.looping(), PlayOptions.defaults().additive(true));
                case 3 -> c.play(JUMP.looping(), PlayOptions.defaults().speed(1.7f).weight(0.6f));
                default -> {
                }
            }
            list.add(c);
        }
        return list;
    }

    @Test
    void parallelMatchesSerial() throws Exception {
        AnimationSet set = TestModel.set();
        int count = 300;
        List<ControllerImpl> serial = controllers(set, count);
        List<ControllerImpl> parallel = controllers(set, count);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int frame = 1; frame <= 40; frame++) {
                double now = frame / 30.0;
                for (ControllerImpl c : serial) {
                    c.update(now);
                    c.evaluate();
                }
                for (ControllerImpl c : parallel) {
                    c.update(now);
                    c.prepareForEvaluation();
                }
                List<Future<?>> futures = new ArrayList<>();
                for (int chunk = 0; chunk < count; chunk += 7) {
                    int from = chunk, to = Math.min(count, chunk + 7);
                    futures.add(pool.submit(() -> {
                        for (int i = from; i < to; i++) parallel.get(i).evaluate();
                    }));
                }
                for (Future<?> f : futures) f.get();
                for (int i = 0; i < count; i++) {
                    assertArrayEquals(serial.get(i).globals(), parallel.get(i).globals(), "controller " + i + " frame " + frame);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
