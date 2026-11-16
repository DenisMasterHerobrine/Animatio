package dev.denismasterherobrine.animatio.anim.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The flattened clip sampler (channels grouped by shared key times, constant-time lookup for evenly spaced keys)
 * must give exactly what sampling each channel on its own gives.
 */
class ClipLayoutTest {
    private static float[] times(Random rnd, boolean uniform, int n) {
        float[] t = new float[n];
        float step = 0.05f + rnd.nextFloat() * 0.2f;
        for (int i = 0; i < n; i++) t[i] = uniform ? i * step : (i == 0 ? rnd.nextFloat() * 0.1f : t[i - 1] + 0.01f + rnd.nextFloat() * 0.3f);
        return t;
    }

    private static float[] values(Random rnd, int keys, int comps, int interp, boolean quat) {
        int perKey = interp == Channel.CUBIC ? comps * 3 : comps;
        float[] v = new float[keys * perKey];
        for (int i = 0; i < v.length; i++) v[i] = rnd.nextFloat() * 4 - 2;
        if (quat && interp != Channel.CUBIC) {
            for (int k = 0; k < keys; k++) {
                int o = k * 4;
                float len = (float) Math.sqrt(v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2] + v[o + 3] * v[o + 3]);
                for (int c = 0; c < 4; c++) v[o + c] /= len;
            }
        }
        return v;
    }

    @Test
    void flattenedSamplingMatchesPerChannelSampling() {
        Random rnd = new Random(42);
        int nodes = 12;
        int[] morphCount = new int[nodes];
        morphCount[3] = 2;
        morphCount[7] = 5;
        String[] names = new String[nodes];
        for (int i = 0; i < nodes; i++) names[i] = "n" + i;
        int[] parents = new int[nodes];
        for (int i = 0; i < nodes; i++) parents[i] = i - 1;
        float[] r = new float[nodes * 4];
        for (int i = 0; i < nodes; i++) r[i * 4 + 3] = 1f;
        String[] morphNames = new String[7];
        for (int i = 0; i < 7; i++) morphNames[i] = "m" + i;
        Skeleton sk = new Skeleton(names, parents, new float[nodes * 3], r, new float[nodes * 3], morphCount, new float[7], morphNames);

        for (int round = 0; round < 50; round++) {
            List<Channel> channels = new ArrayList<>();
            float[] shared = times(rnd, true, 3 + rnd.nextInt(30));
            float[] sharedCopy = shared.clone(); // content-equal arrays must share a track too
            for (int node = 0; node < nodes; node++) {
                for (int path = 0; path < Channel.PATHS; path++) {
                    if (path == Channel.WEIGHTS && morphCount[node] == 0) continue;
                    if (rnd.nextInt(3) == 0) continue;
                    int interp = rnd.nextInt(3);
                    float[] t = switch (rnd.nextInt(4)) {
                        case 0 -> shared;
                        case 1 -> sharedCopy;
                        case 2 -> times(rnd, true, 1 + rnd.nextInt(20));
                        default -> times(rnd, false, 1 + rnd.nextInt(20));
                    };
                    int comps = path == Channel.WEIGHTS ? morphCount[node] : Channel.components(path);
                    channels.add(new Channel(node, path, interp, t, values(rnd, t.length, comps, interp, path == Channel.ROTATION), comps));
                }
            }
            Clip clip = new Clip("c" + round, channels.toArray(new Channel[0]), sk, List.of());
            Pose a = new Pose(sk), b = new Pose(sk);
            float[] tmp = new float[8];
            for (int s = 0; s < 40; s++) {
                float time = rnd.nextFloat() * (clip.duration() + 0.5f) - 0.2f;
                if (s == 0) time = 0f;
                if (s == 1) time = clip.duration();
                a.setRest(sk);
                clip.sample(time, a);
                b.setRest(sk);
                for (Channel ch : channels) {
                    ClipSampler.sample(ch, time, tmp, 0);
                    float[] target = switch (ch.path()) {
                        case Channel.TRANSLATION -> b.t;
                        case Channel.ROTATION -> b.r;
                        case Channel.SCALE -> b.s;
                        default -> b.m;
                    };
                    int o = switch (ch.path()) {
                        case Channel.TRANSLATION, Channel.SCALE -> ch.node() * 3;
                        case Channel.ROTATION -> ch.node() * 4;
                        default -> sk.morphOffset(ch.node());
                    };
                    System.arraycopy(tmp, 0, target, o, ch.comps());
                }
                assertArrayEqualsExact(b.t, a.t, round, time);
                assertArrayEqualsExact(b.r, a.r, round, time);
                assertArrayEqualsExact(b.s, a.s, round, time);
                assertArrayEqualsExact(b.m, a.m, round, time);
            }
        }
    }

    private static void assertArrayEqualsExact(float[] expected, float[] actual, int round, float time) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 0f, "round " + round + " t=" + time + " index " + i);
        }
    }
}
