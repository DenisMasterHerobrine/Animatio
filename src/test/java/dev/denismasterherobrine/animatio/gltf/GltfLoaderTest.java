package dev.denismasterherobrine.animatio.gltf;

import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.model.Pose;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.testutil.TestModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static dev.denismasterherobrine.animatio.testutil.TestModel.BODY;
import static dev.denismasterherobrine.animatio.testutil.TestModel.HEAD;
import static dev.denismasterherobrine.animatio.testutil.TestModel.PROP;
import static dev.denismasterherobrine.animatio.testutil.TestModel.ROOT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfLoaderTest {
    private static final float EPS = 1e-5f;

    @Test
    void readsNodeHierarchy() {
        ModelData model = TestModel.load();
        Skeleton sk = model.skeleton();
        assertEquals(5, sk.size());
        assertEquals("body", sk.name(BODY));
        assertEquals(ROOT, sk.parent(BODY));
        assertEquals(BODY, sk.parent(HEAD));
        assertEquals(HEAD, sk.parent(PROP));
        assertEquals(-1, sk.parent(ROOT));
        assertEquals(HEAD, sk.find("head"));
        assertEquals(1f, sk.restT()[BODY * 3 + 1], EPS);
        // parents come before children in evaluation order
        int[] order = sk.order();
        int[] position = new int[order.length];
        for (int i = 0; i < order.length; i++) position[order[i]] = i;
        for (int i = 0; i < sk.size(); i++) {
            if (sk.parent(i) >= 0) assertTrue(position[sk.parent(i)] < position[i]);
        }
    }

    @Test
    void readsClips() {
        ModelData model = TestModel.load();
        assertEquals(10, model.clips().size());
        assertEquals(1f, model.clip("jump").duration(), EPS);
        assertEquals(0.5f, model.clip("fall").duration(), EPS);
        assertEquals(2f, model.clip("run").duration(), EPS);
        Clip jump = model.clip("jump");
        assertTrue(jump.animates(BODY, 0));
        assertEquals(1, jump.rawEvents().size());
        assertEquals(0.5f, jump.rawEvents().getFirst().time(), EPS);
        assertEquals("animatio:sound", jump.rawEvents().getFirst().data().get("type").getAsString());
    }

    @Test
    void readsSkinnedMeshWithStrideAndNormalisedWeights() {
        ModelData model = TestModel.load();
        assertEquals(2, model.meshes().size());
        MeshData quad = model.meshes().getFirst();
        assertEquals(4, quad.vertexCount());
        assertEquals(2, quad.triangleCount());
        assertArrayEquals(new float[]{-0.5f, 0, 0, 0.5f, 0, 0, 0.5f, 2, 0, -0.5f, 2, 0}, quad.positions(), EPS);
        // palette: skin joints come first (root, body, head), then the rigid prop node
        assertEquals(4, model.paletteSize());
        assertArrayEquals(new int[]{ROOT, BODY, HEAD, PROP}, model.paletteNodes());
        assertEquals(1, quad.joints()[0]);
        assertEquals(1f, quad.weights()[0], EPS);
        assertEquals(2, quad.joints()[8]);
        assertEquals(0.5f, quad.weights()[8], 1e-3f);
        assertEquals(0.5f, quad.weights()[9], 1e-3f);
        // normals were computed: the quad faces +Z
        assertEquals(1f, quad.normals()[2], EPS);
        assertEquals(0, quad.material());
    }

    @Test
    void readsSparseRigidMesh() {
        ModelData model = TestModel.load();
        MeshData prop = model.meshes().get(1);
        assertArrayEquals(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, prop.positions(), EPS);
        assertArrayEquals(new int[]{0, 1, 2}, prop.indices());
        assertEquals(3, prop.joints()[0]);
        assertEquals(1f, prop.weights()[0], EPS);
        assertEquals(-1, prop.material());
    }

    @Test
    void readsMaterialAndImage() {
        ModelData model = TestModel.load();
        MaterialData mat = model.material(0);
        assertEquals("skin", mat.name());
        assertEquals(0, mat.baseColorImage());
        assertEquals(MaterialData.AlphaMode.MASK, mat.alphaMode());
        assertTrue(mat.doubleSided());
        assertTrue(mat.nearestFilter());
        assertEquals(1, model.images().size());
        byte[] png = model.images().getFirst().data();
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals(MaterialData.DEFAULT, model.material(-1));
    }

    @Test
    void skinnedPaletteIsIdentityInRestPose() {
        ModelData model = TestModel.load();
        Pose pose = new Pose(model.skeleton().size());
        pose.setRest(model.skeleton());
        float[] globals = new float[model.skeleton().size() * 16];
        Mat4.computeGlobals(model.skeleton(), pose, globals);
        float[] palette = new float[model.paletteSize() * 16];
        model.computePalette(globals, palette);
        float[] identity = new float[16];
        Mat4.identity(identity, 0);
        for (int j = 0; j < 3; j++) {
            assertArrayEquals(identity, Arrays.copyOfRange(palette, j * 16, j * 16 + 16), EPS, "joint " + j);
        }
        // rigid prop entry = the prop node's global transform: (0, 1.75, 0)
        assertEquals(1.75f, palette[3 * 16 + 13], EPS);
    }

    @Test
    void jointBoundsCoverInfluencedVertices() {
        ModelData model = TestModel.load();
        float[] b = model.jointBounds();
        // body (entry 1) influences all four quad vertices
        assertEquals(-0.5f, b[6], EPS);
        assertEquals(2f, b[6 + 4], EPS);
        // root (entry 0) influences nothing
        assertTrue(b[0] > b[3]);
    }

    @Test
    void primitivesSharingVerticesAreCompacted() throws Exception {
        dev.denismasterherobrine.animatio.testutil.GlbBuilder b = new dev.denismasterherobrine.animatio.testutil.GlbBuilder();
        int pos = b.floats("VEC3", 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 5, 5, 5, 6, 5, 5, 6, 6, 5);
        int first = b.ushorts("SCALAR", 0, 1, 2, 0, 2, 3);
        int second = b.ushorts("SCALAR", 6, 5, 4);
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString("""
                { "nodes": [ { "mesh": 0 } ],
                  "meshes": [ { "primitives": [
                      { "attributes": { "POSITION": %d }, "indices": %d },
                      { "attributes": { "POSITION": %d }, "indices": %d } ] } ] }"""
                .formatted(pos, first, pos, second)).getAsJsonObject();
        ModelData model = GltfLoader.load("shared", b.build(json), true, null);
        MeshData a = model.meshes().get(0), c = model.meshes().get(1);
        assertEquals(4, a.vertexCount());
        assertArrayEquals(new int[]{0, 1, 2, 0, 2, 3}, a.indices());
        assertEquals(3, c.vertexCount());
        assertArrayEquals(new int[]{0, 1, 2}, c.indices(), "renumbered in first-use order");
        assertArrayEquals(new float[]{6, 6, 5, 6, 5, 5, 5, 5, 5}, c.positions(), EPS);
    }

    @Test
    void serverLoadSkipsGeometry() throws Exception {
        ModelData model = GltfLoader.load("test", TestModel.glb(), false, null);
        assertEquals(0, model.meshes().size());
        assertEquals(0, model.images().size());
        assertNotNull(model.clip("jump"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(GltfException.class, () -> GltfLoader.load("bad", new byte[]{1, 2, 3}, true, null));
        byte[] glb = TestModel.glb();
        byte[] truncated = Arrays.copyOf(glb, glb.length / 2);
        assertThrows(GltfException.class, () -> GltfLoader.load("bad", truncated, true, null));
    }
}
