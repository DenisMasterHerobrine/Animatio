package dev.denismasterherobrine.animatio.anim.model;

import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import dev.denismasterherobrine.animatio.testutil.TestModel;
import org.junit.jupiter.api.Test;

import static dev.denismasterherobrine.animatio.testutil.TestModel.BODY;
import static dev.denismasterherobrine.animatio.testutil.TestModel.HEAD;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClipSamplerTest {
    private static final float EPS = 1e-4f;
    private final ModelData model = TestModel.load();

    private Pose sample(String clip, float time) {
        Pose pose = new Pose(model.skeleton().size());
        pose.setRest(model.skeleton());
        model.clip(clip).sample(time, pose);
        return pose;
    }

    @Test
    void linearTranslation() {
        assertEquals(1.5f, sample("jump", 0.5f).t[BODY * 3 + 1], EPS);
        assertEquals(1f, sample("jump", -1f).t[BODY * 3 + 1], EPS);
        assertEquals(2f, sample("jump", 5f).t[BODY * 3 + 1], EPS);
    }

    @Test
    void stepScale() {
        assertEquals(1f, sample("blink", 0.05f).s[HEAD * 3 + 1], EPS);
        assertEquals(0.1f, sample("blink", 0.15f).s[HEAD * 3 + 1], EPS);
        assertEquals(1f, sample("blink", 0.2f).s[HEAD * 3 + 1], EPS);
    }

    @Test
    void slerpRotation() {
        Pose p = sample("spin", 0.5f);
        double half = Math.PI / 8; // 45 degrees about Y -> half angle 22.5 degrees
        assertEquals(0f, p.r[HEAD * 4], EPS);
        assertEquals((float) Math.sin(half), p.r[HEAD * 4 + 1], EPS);
        assertEquals((float) Math.cos(half), p.r[HEAD * 4 + 3], EPS);
    }

    @Test
    void cubicSpline() {
        // zero tangents: y = 4 * (-2u^3 + 3u^2); at u = 0.25 that is 0.625
        assertEquals(0.625f, sample("cubic", 0.25f).t[BODY * 3 + 1], EPS);
        assertEquals(2f, sample("cubic", 0.5f).t[BODY * 3 + 1], EPS);
    }

    @Test
    void untouchedNodesKeepRestPose() {
        Pose p = sample("jump", 0.5f);
        assertEquals(0.5f, p.t[HEAD * 3 + 1], EPS);
    }

    @Test
    void globalsComposeHierarchy() {
        Pose p = sample("jump", 1f); // body at y = 2, head at body + 0.5
        float[] globals = new float[model.skeleton().size() * 16];
        Mat4.computeGlobals(model.skeleton(), p, globals);
        assertEquals(2.5f, globals[HEAD * 16 + 13], EPS);
    }
}
