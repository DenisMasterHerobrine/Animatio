package dev.denismasterherobrine.animatio.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.model.Channel;
import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.model.ClipInfo;
import dev.denismasterherobrine.animatio.anim.model.LookConfig;
import dev.denismasterherobrine.animatio.anim.model.ModelCamera;
import dev.denismasterherobrine.animatio.anim.model.ModelLight;
import dev.denismasterherobrine.animatio.anim.model.Pose;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import dev.denismasterherobrine.animatio.client.render.SkinKernel;
import dev.denismasterherobrine.animatio.testutil.GlbBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Draco geometry, morph targets, cameras, lights and the look-at bone. */
class GltfExtensionsTest {
    private static final float EPS = 1e-5f;

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = GltfExtensionsTest.class.getResourceAsStream("/gltf/" + name)) {
            if (in == null) throw new IOException("missing test resource " + name);
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ Draco

    /** Triangles as sorted position triples, rounded to the Draco quantisation step. */
    private static List<String> triangles(MeshData m) {
        List<String> out = new ArrayList<>();
        int[] idx = m.indices();
        float[] p = m.positions();
        for (int t = 0; t < idx.length; t += 3) {
            List<String> corners = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                int v = idx[t + k] * 3;
                corners.add(Math.round(p[v] * 100) + "," + Math.round(p[v + 1] * 100) + "," + Math.round(p[v + 2] * 100));
            }
            corners.sort(String::compareTo);
            out.add(String.join(";", corners));
        }
        out.sort(String::compareTo);
        return out;
    }

    @Test
    void dracoBoxMatchesUncompressedBox() throws Exception {
        byte[] bin = resource("Box.bin");
        ModelData draco = GltfLoader.load("box_draco", resource("BoxDraco.gltf"), true, uri -> uri.equals("Box.bin") ? bin : null);
        ModelData plain = GltfLoader.load("box", resource("Box.glb"), true, null);
        assertEquals(1, draco.meshes().size());
        MeshData d = draco.meshes().getFirst(), p = plain.meshes().getFirst();
        assertEquals(p.triangleCount(), d.triangleCount());
        assertEquals(12, d.triangleCount());
        assertEquals(triangles(p), triangles(d));
        // normals are unit length and axis-aligned for a box
        for (int v = 0; v < d.vertexCount(); v++) {
            float x = d.normals()[v * 3], y = d.normals()[v * 3 + 1], z = d.normals()[v * 3 + 2];
            assertEquals(1f, (float) Math.sqrt(x * x + y * y + z * z), 1e-2f);
        }
        assertEquals("Red", draco.material(d.material()).name());
    }

    // ------------------------------------------------------------------ morph targets

    @Test
    void animatedMorphCube() throws Exception {
        ModelData model = GltfLoader.load("cube", resource("AnimatedMorphCube.glb"), true, null);
        Skeleton sk = model.skeleton();
        assertEquals(2, sk.morphSlots());
        assertEquals(2, sk.morphCount(0));
        assertEquals("target_0", sk.morphName(0));
        assertArrayEquals(new int[]{1}, sk.findMorph("target_1"));
        MeshData mesh = model.meshes().getFirst();
        assertTrue(mesh.hasMorphs());
        assertEquals(2, mesh.morphTargetCount());
        assertTrue(mesh.morphStart()[2] > 0, "targets move vertices");
        Clip square = model.clip("Square");
        assertNotNull(square);
        assertTrue(square.animates(0, Channel.WEIGHTS));
        Pose pose = new Pose(sk);
        float min = 1f, max = 0f;
        for (int i = 0; i <= 20; i++) {
            pose.setRest(sk);
            square.sample(square.duration() * i / 20f, pose);
            for (float w : pose.m) {
                min = Math.min(min, w);
                max = Math.max(max, w);
            }
        }
        assertTrue(max > 0.9f && min < 0.1f, "weights animate between 0 and 1: " + min + ".." + max);
    }

    /**
     * One triangle on node "face" (rigid) with two targets: "smile" moves vertex 2 up by 1, "blink" moves vertex 0
     * right by 2. Mesh default weights (0.5, 0). Clip "grin" holds smile at 1 (STEP).
     */
    private static ModelData morphModel() throws GltfException {
        GlbBuilder b = new GlbBuilder();
        JsonObject json = new JsonObject();
        JsonArray nodes = new JsonArray();
        JsonObject head = new JsonObject();
        head.addProperty("name", "head");
        head.add("children", ints(1, 2, 3));
        nodes.add(head);
        JsonObject face = new JsonObject();
        face.addProperty("name", "face");
        face.addProperty("mesh", 0);
        nodes.add(face);
        JsonObject cam = new JsonObject();
        cam.addProperty("name", "cutscene");
        cam.addProperty("camera", 0);
        cam.add("translation", floats(0, 1, 5));
        nodes.add(cam);
        JsonObject lamp = new JsonObject();
        lamp.addProperty("name", "lamp");
        JsonObject lampExt = new JsonObject();
        JsonObject lampRef = new JsonObject();
        lampRef.addProperty("light", 0);
        lampExt.add("KHR_lights_punctual", lampRef);
        lamp.add("extensions", lampExt);
        nodes.add(lamp);
        json.add("nodes", nodes);

        int pos = b.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0);
        int smile = b.floats("VEC3", 0, 0, 0, 0, 0, 0, 0, 1, 0);
        int blink = b.floats("VEC3", 2, 0, 0, 0, 0, 0, 0, 0, 0);
        JsonObject prim = new JsonObject();
        JsonObject attrs = new JsonObject();
        attrs.addProperty("POSITION", pos);
        prim.add("attributes", attrs);
        JsonArray targets = new JsonArray();
        JsonObject t0 = new JsonObject();
        t0.addProperty("POSITION", smile);
        JsonObject t1 = new JsonObject();
        t1.addProperty("POSITION", blink);
        targets.add(t0);
        targets.add(t1);
        prim.add("targets", targets);
        JsonObject mesh = new JsonObject();
        JsonArray prims = new JsonArray();
        prims.add(prim);
        mesh.add("primitives", prims);
        mesh.add("weights", floats(0.5f, 0f));
        JsonObject extras = new JsonObject();
        JsonArray names = new JsonArray();
        names.add(new JsonPrimitive("smile"));
        names.add(new JsonPrimitive("blink"));
        extras.add("targetNames", names);
        mesh.add("extras", extras);
        JsonArray meshes = new JsonArray();
        meshes.add(mesh);
        json.add("meshes", meshes);

        int times = b.floats("SCALAR", 0, 1);
        int values = b.floats("SCALAR", 1, 0, 1, 0);
        JsonObject sampler = new JsonObject();
        sampler.addProperty("input", times);
        sampler.addProperty("output", values);
        sampler.addProperty("interpolation", "STEP");
        JsonObject channel = new JsonObject();
        channel.addProperty("sampler", 0);
        JsonObject target = new JsonObject();
        target.addProperty("node", 1);
        target.addProperty("path", "weights");
        channel.add("target", target);
        JsonObject anim = new JsonObject();
        anim.addProperty("name", "grin");
        JsonArray samplers = new JsonArray();
        samplers.add(sampler);
        anim.add("samplers", samplers);
        JsonArray channels = new JsonArray();
        channels.add(channel);
        anim.add("channels", channels);
        JsonArray anims = new JsonArray();
        anims.add(anim);
        json.add("animations", anims);

        JsonObject persp = new JsonObject();
        persp.addProperty("yfov", 0.8f);
        persp.addProperty("znear", 0.1f);
        JsonObject camera = new JsonObject();
        camera.addProperty("type", "perspective");
        camera.add("perspective", persp);
        JsonArray cameras = new JsonArray();
        cameras.add(camera);
        json.add("cameras", cameras);

        JsonObject light = new JsonObject();
        light.addProperty("type", "spot");
        light.add("color", floats(1f, 0.5f, 0.25f));
        light.addProperty("intensity", 20f);
        light.addProperty("range", 8f);
        JsonObject spot = new JsonObject();
        spot.addProperty("outerConeAngle", 0.5f);
        light.add("spot", spot);
        JsonArray lights = new JsonArray();
        lights.add(light);
        JsonObject lightsExt = new JsonObject();
        lightsExt.add("lights", lights);
        JsonObject rootExt = new JsonObject();
        rootExt.add("KHR_lights_punctual", lightsExt);
        json.add("extensions", rootExt);
        return GltfLoader.load("morph", b.build(json), true, null);
    }

    private static JsonArray ints(int... v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    private static JsonArray floats(float... v) {
        JsonArray a = new JsonArray();
        for (float f : v) a.add(f);
        return a;
    }

    @Test
    void morphTargetsLoadWithNamesAndDefaults() throws Exception {
        ModelData model = morphModel();
        Skeleton sk = model.skeleton();
        assertEquals(2, sk.morphSlots());
        assertEquals(1, sk.morphNodes().length);
        assertEquals("smile", sk.morphName(0));
        assertArrayEquals(new int[]{1}, sk.findMorph("blink"));
        assertArrayEquals(new int[]{0}, sk.findMorph("face/smile"));
        assertArrayEquals(new float[]{0.5f, 0f}, sk.restMorph(), EPS);
        MeshData mesh = model.meshes().getFirst();
        assertEquals(0, mesh.morphWeightOffset());
        // sparse lists: each target moves one vertex
        assertArrayEquals(new int[]{0, 1, 2}, mesh.morphStart());
        // bounds include where the targets can reach
        int entry = mesh.joints()[0];
        float[] jb = model.jointBounds();
        assertEquals(2f, jb[entry * 6 + 3], EPS); // blink can move vertex 0 to x = 2, past vertex 1 at x = 1
    }

    @Test
    void cpuMorphMatchesExpectedDeltas() throws Exception {
        MeshData mesh = morphModel().meshes().getFirst();
        float[] p = new float[9], n = new float[9];
        assertTrue(SkinKernel.morph(mesh, new float[]{1f, 0.5f}, p, n));
        assertArrayEquals(new float[]{1, 0, 0, 1, 0, 0, 0, 2, 0}, p, EPS);
        assertTrue(!SkinKernel.morph(mesh, new float[]{0f, 0f}, p, n), "all-zero weights skip the morph pass");
    }

    @Test
    void weightsChannelsBlendAndOverride() throws Exception {
        ModelData model = morphModel();
        AnimationSet.Builder builder = AnimationSet.builder("morph", model.skeleton());
        for (Clip clip : model.clips()) builder.clip(ClipInfo.of(clip).withLoop(true));
        AnimationSet set = builder.build();
        ControllerImpl c = new ControllerImpl(ControllerImpl.Side.CLIENT);
        c.bind(set);
        c.update(0);
        assertTrue(c.evaluate());
        assertArrayEquals(new float[]{0.5f, 0f}, c.pose().m, EPS);

        c.play(AnimationRef.of("grin"), TransitionConfig.NONE);
        c.update(0.1);
        assertTrue(c.evaluate());
        assertEquals(1f, c.pose().m[0], EPS);
        assertEquals(0f, c.pose().m[1], EPS);

        // a half-way fade-out blends the weights linearly back toward the defaults
        c.stop(AnimationRef.of("grin"), new TransitionConfig(1f, dev.denismasterherobrine.animatio.api.playback.Easing.LINEAR));
        c.update(0.6);
        assertTrue(c.evaluate());
        assertEquals(0.75f, c.pose().m[0], 1e-3f);

        // code overrides win over animations until cleared
        c.setMorphWeight("blink", 0.25f);
        assertTrue(c.evaluate());
        assertEquals(0.25f, c.pose().m[1], EPS);
        c.clearMorphWeight("blink");
        assertTrue(c.evaluate());
        assertEquals(0f, c.pose().m[1], EPS);
    }

    // ------------------------------------------------------------------ cameras, lights, look-at

    @Test
    void camerasAndLights() throws Exception {
        ModelData model = morphModel();
        assertEquals(1, model.cameras().size());
        ModelCamera cam = model.cameras().getFirst();
        assertEquals("cutscene", cam.name());
        assertEquals(2, cam.node());
        assertTrue(cam.perspective());
        assertEquals(0.8f, cam.yfov(), EPS);
        assertEquals(0.1f, cam.znear(), EPS);
        assertEquals(Float.POSITIVE_INFINITY, cam.zfar());

        assertEquals(1, model.lights().size());
        ModelLight light = model.lights().getFirst();
        assertEquals("lamp", light.name());
        assertEquals(ModelLight.Type.SPOT, light.type());
        assertArrayEquals(new float[]{1f, 0.5f, 0.25f}, light.color(), EPS);
        assertEquals(20f, light.intensity(), EPS);
        assertEquals(8f, light.range(), EPS);
        assertEquals(0.5f, light.outerCone(), EPS);
    }

    @Test
    void lookRotatesBoneInModelSpace() throws Exception {
        ModelData model = morphModel();
        Skeleton sk = model.skeleton();
        AnimationSet set = AnimationSet.builder("look", sk).look(new LookConfig(sk.find("head"), 60f, 45f, 1f)).build();
        ControllerImpl c = new ControllerImpl(ControllerImpl.Side.CLIENT);
        c.bind(set);
        c.update(0);
        c.setLocalParameter(LookConfig.YAW_PARAMETER, 90f); // clamped to 60
        assertTrue(c.evaluate());
        // the camera child (at +5 z) swings with the head: like vanilla head yaw, positive turns right (toward -x)
        float[] g = c.globals();
        int cam = sk.find("cutscene");
        float[] p = new float[3];
        Mat4.transformPoint(g, cam * 16, 0, 0, 0, p, 0);
        assertEquals(-5f * (float) Math.sin(Math.toRadians(60)), p[0], 1e-4f);
        assertEquals(5f * (float) Math.cos(Math.toRadians(60)), p[2], 1e-4f);

        c.setLocalParameter(LookConfig.YAW_PARAMETER, 0f);
        c.setLocalParameter(LookConfig.PITCH_PARAMETER, 30f); // looking down: +z forward tips toward -y
        assertTrue(c.evaluate());
        Mat4.transformPoint(c.globals(), cam * 16, 0, 0, 0, p, 0);
        assertEquals(1f * (float) Math.cos(Math.toRadians(30)) - 5f * (float) Math.sin(Math.toRadians(30)), p[1], 1e-4f);
    }
}
