package dev.denismasterherobrine.animatio.blockbench;

import dev.denismasterherobrine.animatio.anim.model.Channel;
import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.model.Pose;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Blockbench projects, checked against Blockbench's own rules (expectations computed by hand from its source). */
class BbmodelLoaderTest {
    private static final float EPS = 1e-5f;

    /**
     * Format 5 project: group "body" (origin 0,8,0) containing group "arm" (origin 4,12,0, rotated 30 degrees about Z)
     * containing one box-UV cube (from 3,4,-1 to 5,12,1, origin 4,12,0). Animation "swing" (loop, 1 s) keys the arm's
     * Z rotation with Catmull-Rom: 0 at 0 s, 10 at 0.5 s, 0 at 1 s; and its position with a Molang expression.
     */
    private static final String PROJECT = """
            {"meta":{"format_version":"5.0","model_format":"free","box_uv":true},"name":"test",
             "resolution":{"width":64,"height":32},
             "elements":[{"name":"arm_cube","type":"cube","box_uv":true,"from":[3,4,-1],"to":[5,12,1],"origin":[4,12,0],
               "rotation":[0,0,0],"uv_offset":[0,0],"uuid":"c1",
               "faces":{"north":{"uv":[2,2,4,10],"texture":0},"east":{"uv":[0,2,2,10],"texture":0},
                        "south":{"uv":[6,2,8,10],"texture":0},"west":{"uv":[4,2,6,10],"texture":0},
                        "up":{"uv":[4,2,2,0],"texture":0},"down":{"uv":[6,0,4,2],"texture":null}}}],
             "groups":[{"name":"body","uuid":"g1","origin":[0,8,0],"rotation":[0,0,0]},
                       {"name":"arm","uuid":"g2","origin":[4,12,0],"rotation":[0,0,30]}],
             "outliner":[{"uuid":"g1","children":[{"uuid":"g2","children":["c1"]}]}],
             "textures":[{"name":"skin.png","uuid":"t1","uv_width":64,"uv_height":32,"render_sides":"auto",
                          "source":"data:image/png;base64,iVBORw0KGgo="}],
             "animations":[{"name":"swing","loop":"loop","length":1,"animators":{"g2":{"name":"arm","type":"bone",
               "keyframes":[
                 {"channel":"rotation","time":0,"interpolation":"catmullrom","data_points":[{"x":"0","y":"0","z":"0"}]},
                 {"channel":"rotation","time":0.5,"interpolation":"catmullrom","data_points":[{"x":"0","y":"0","z":"10"}]},
                 {"channel":"rotation","time":1,"interpolation":"catmullrom","data_points":[{"x":"0","y":"0","z":"0"}]},
                 {"channel":"position","time":0,"interpolation":"linear","data_points":[{"x":"math.sin(90) * 2 + query.anim_time","y":"0","z":"0"}]}
               ]}}},
              {"name":"once","loop":"once","length":0.5,"animators":{}}]}
            """;

    private static ModelData project() throws Exception {
        return BbmodelLoader.load("test", PROJECT.getBytes(StandardCharsets.UTF_8), true, null);
    }

    @Test
    void groupsBecomeBonesWithBlockbenchTransforms() throws Exception {
        Skeleton sk = project().skeleton();
        assertEquals(3, sk.size());
        assertEquals("bbmodel_root", sk.name(0));
        int body = sk.find("body"), arm = sk.find("arm");
        assertEquals(0, sk.parent(body));
        assertEquals(body, sk.parent(arm));
        // root turns the north-facing model to +Z
        assertEquals(1f, Math.abs(sk.restR()[1]), EPS);
        // translations: origin minus the parent's origin, in blocks
        assertEquals(0.5f, sk.restT()[body * 3 + 1], EPS);
        assertEquals(4f / 16f, sk.restT()[arm * 3], EPS);
        assertEquals(4f / 16f, sk.restT()[arm * 3 + 1], EPS);
        Quaternionf expected = new Quaternionf().rotationZ((float) Math.toRadians(30));
        assertEquals(expected.z, sk.restR()[arm * 4 + 2], EPS);
        assertEquals(expected.w, sk.restR()[arm * 4 + 3], EPS);
    }

    @Test
    void cubesAreBakedIntoTheirGroupWithFaceUvs() throws Exception {
        ModelData model = project();
        assertEquals(1, model.meshes().size());
        MeshData mesh = model.meshes().getFirst();
        assertEquals(5 * 4, mesh.vertexCount(), "five textured faces (down has no texture)");
        assertEquals(10, mesh.triangleCount());
        // east face, first corner: (to.x, to.y, to.z) relative to the arm's origin (4,12,0), in blocks
        float[] p = mesh.positions();
        assertEquals(1f / 16f, p[0], EPS);
        assertEquals(0f, p[1], EPS);
        assertEquals(1f / 16f, p[2], EPS);
        // its uv: (u0, v0) of the east face moved inward by the 1/64 px box-UV margin, over the 64x32 texture
        assertEquals((0f + 1f / 64f) / 64f, mesh.uvs()[0], EPS);
        assertEquals((2f + 1f / 64f) / 32f, mesh.uvs()[1], EPS);
        // second corner gets (u1, v0)
        assertEquals((2f - 1f / 64f) / 64f, mesh.uvs()[2], EPS);
        // every vertex follows the arm bone rigidly
        int armEntry = mesh.joints()[0];
        assertEquals(model.skeleton().find("arm"), model.paletteNodes()[armEntry]);
        assertEquals(1f, mesh.normals()[0], EPS);
        assertTrue(model.material(0).doubleSided(), "render_sides auto is double-sided in Blockbench");
        assertEquals("skin", model.material(0).name());
    }

    @Test
    void catmullRomLoopWrapsLikeBlockbench() throws Exception {
        ModelData model = project();
        Clip swing = model.clip("swing");
        assertNotNull(swing);
        assertEquals(1f, swing.duration(), EPS);
        assertEquals(Boolean.TRUE, model.loopHints().get("swing"));
        assertEquals(Boolean.FALSE, model.loopHints().get("once"));
        Skeleton sk = model.skeleton();
        int arm = sk.find("arm");
        Pose pose = new Pose(sk);
        pose.setRest(sk);
        swing.sample(0.25f, pose);
        // Blockbench at 0.25 s: before_plus wraps to the key at 0.5 s (10), after_plus is the key at 1 s (0);
        // the spline through [10, 0, 10, 0] at the midpoint of the middle segment gives 5 degrees
        Quaternionf expected = new Quaternionf().rotationZ((float) Math.toRadians(30 + 5));
        assertEquals(expected.z, pose.r[arm * 4 + 2], 1e-4f);
        assertEquals(expected.w, pose.r[arm * 4 + 3], 1e-4f);
        // Molang position: sin(90 deg) * 2 + anim_time, in pixels, added to the rest translation
        swing.sample(0.5f, pose);
        assertEquals((4f + 2f + 0.5f) / 16f, pose.t[arm * 3], 1e-4f);
        assertTrue(swing.animates(arm, Channel.TRANSLATION));
    }

    @Test
    void molang() {
        assertEquals(3.0, Molang.eval("math.sin(90) * 2 + query.anim_time", 1.0), 1e-9);
        assertEquals(-4.5, Molang.eval("-4.5", 0), 1e-9);
        assertEquals(7.0, Molang.eval("q.anim_time > 1 ? 7 : 8", 2.0), 1e-9);
        assertEquals(2.0, Molang.eval("math.clamp(5, 0, 2)", 0), 1e-9);
        assertEquals(0.0, Molang.eval("variable.unknown", 0), 1e-9);
        assertEquals(0.0, Molang.eval("", 0), 1e-9);
    }
}
