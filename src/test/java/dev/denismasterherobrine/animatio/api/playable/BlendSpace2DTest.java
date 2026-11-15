package dev.denismasterherobrine.animatio.api.playable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlendSpace2DTest {
    private static final float EPS = 1e-4f;
    private static final AnimationRef IDLE = AnimationRef.of("idle");
    private static final AnimationRef FWD = AnimationRef.of("forward");
    private static final AnimationRef BACK = AnimationRef.of("back");
    private static final AnimationRef LEFT = AnimationRef.of("left");
    private static final AnimationRef RIGHT = AnimationRef.of("right");

    private final BlendSpace2D space = BlendSpace2D.builder("move", "x", "z")
            .sample(0, 0, IDLE)
            .sample(0, 4, FWD).sample(0, -3, BACK)
            .sample(-3, 0, LEFT).sample(3, 0, RIGHT)
            .build();

    private float[] weights(float x, float z) {
        float[] w = new float[space.size()];
        space.weights(x, z, w);
        float sum = 0f;
        for (float v : w) {
            assertTrue(v >= 0f);
            sum += v;
        }
        assertEquals(1f, sum, EPS);
        return w;
    }

    @Test
    void triangulatesDiamond() {
        assertEquals(4, space.triangleCount());
    }

    @Test
    void exactSamplesGetFullWeight() {
        assertEquals(1f, weights(0, 0)[0], EPS);
        assertEquals(1f, weights(0, 4)[1], EPS);
        assertEquals(1f, weights(3, 0)[4], EPS);
    }

    @Test
    void insideTriangleIsBarycentric() {
        float[] w = weights(0, 2); // half-way from idle to forward
        assertEquals(0.5f, w[0], EPS);
        assertEquals(0.5f, w[1], EPS);
        float[] diag = weights(1, 1);
        assertTrue(diag[0] > 0 && diag[1] > 0 && diag[4] > 0, "forward-right quadrant blends idle, forward and right");
    }

    @Test
    void outsideHullClampsToEdge() {
        float[] w = weights(0, 10);
        assertEquals(1f, w[1], EPS);
        float[] corner = weights(10, 10); // beyond the forward-right edge
        assertEquals(0f, corner[0], EPS);
        assertTrue(corner[1] > 0 && corner[4] > 0);
    }

    @Test
    void collinearSamplesFallBackToSegments() {
        BlendSpace2D line = BlendSpace2D.builder("line", "x", "z")
                .sample(0, 0, IDLE).sample(1, 0, FWD).sample(2, 0, BACK).build();
        assertEquals(0, line.triangleCount());
        float[] w = new float[3];
        line.weights(1.5f, 0.3f, w);
        assertEquals(0.5f, w[1], EPS);
        assertEquals(0.5f, w[2], EPS);
    }
}
