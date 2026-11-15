package dev.denismasterherobrine.animatio.testutil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.model.ClipInfo;
import dev.denismasterherobrine.animatio.gltf.GltfLoader;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;

/**
 * A small skinned test character:
 * <pre>
 * root (0,0,0)
 * ├─ body (0,1,0)
 * │  └─ head (0,0.5,0)
 * │     └─ prop (0,0.25,0)  rigid triangle (sparse positions)
 * └─ skinned_mesh            quad skinned to root/body/head
 * </pre>
 * Clips (body translation unless noted): jump 0→1s y 1→2, fall 0→0.5s y 2→0, blink (head scale, STEP, 0.2s),
 * spin (head rotation 90° about Y over 1s), cubic (CUBICSPLINE y 0→4), pose_a (y=2), pose_b (y=4),
 * idle (x=0, 1s), walk (x=1, 1s), run (x=3, 2s).
 */
public final class TestModel {
    public static final int ROOT = 0, BODY = 1, HEAD = 2, MESH_NODE = 3, PROP = 4;
    private static final float S45 = (float) Math.sin(Math.PI / 4);
    private static final Set<String> LOOPING = Set.of("idle", "walk", "run", "pose_a", "pose_b");

    private TestModel() {
    }

    public static byte[] glb() {
        GlbBuilder b = new GlbBuilder();
        JsonObject json = new JsonObject();

        JsonArray nodes = new JsonArray();
        nodes.add(node("root", null, new int[]{BODY, MESH_NODE}));
        nodes.add(node("body", new float[]{0, 1, 0}, new int[]{HEAD}));
        nodes.add(node("head", new float[]{0, 0.5f, 0}, new int[]{PROP}));
        JsonObject meshNode = node("skinned_mesh", null, null);
        meshNode.addProperty("mesh", 0);
        meshNode.addProperty("skin", 0);
        nodes.add(meshNode);
        JsonObject prop = node("prop", new float[]{0, 0.25f, 0}, null);
        prop.addProperty("mesh", 1);
        nodes.add(prop);
        json.add("nodes", nodes);

        JsonObject scene = new JsonObject();
        scene.add("nodes", ints(ROOT));
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        json.add("scenes", scenes);
        json.addProperty("scene", 0);

        // skin: root, body, head with inverse bind matrices of their rest globals
        int ibm = b.floats("MAT4",
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -1, 0, 1,
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -1.5f, 0, 1);
        JsonObject skin = new JsonObject();
        skin.add("joints", ints(ROOT, BODY, HEAD));
        skin.addProperty("inverseBindMatrices", ibm);
        JsonArray skins = new JsonArray();
        skins.add(skin);
        json.add("skins", skins);

        // mesh 0: skinned quad, strided positions, normalised-byte weights
        int pos = b.stridedVec3(-0.5f, 0, 0, 0.5f, 0, 0, 0.5f, 2, 0, -0.5f, 2, 0);
        int uv = b.floats("VEC2", 0, 1, 1, 1, 1, 0, 0, 0);
        int joints = b.ubytes("VEC4", false, 1, 0, 0, 0, 1, 0, 0, 0, 2, 1, 0, 0, 2, 1, 0, 0);
        int weights = b.ubytes("VEC4", true, 255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0);
        int idx = b.ushorts("SCALAR", 0, 1, 2, 0, 2, 3);
        JsonObject attrs0 = new JsonObject();
        attrs0.addProperty("POSITION", pos);
        attrs0.addProperty("TEXCOORD_0", uv);
        attrs0.addProperty("JOINTS_0", joints);
        attrs0.addProperty("WEIGHTS_0", weights);
        JsonObject prim0 = new JsonObject();
        prim0.add("attributes", attrs0);
        prim0.addProperty("indices", idx);
        prim0.addProperty("material", 0);

        // mesh 1: rigid triangle with sparse positions, no indices, no material
        int sparsePos = b.sparseVec3(3, new int[]{1, 2}, new float[]{1, 0, 0, 0, 1, 0});
        JsonObject attrs1 = new JsonObject();
        attrs1.addProperty("POSITION", sparsePos);
        JsonObject prim1 = new JsonObject();
        prim1.add("attributes", attrs1);

        JsonArray meshes = new JsonArray();
        meshes.add(mesh("quad", prim0));
        meshes.add(mesh("prop_mesh", prim1));
        json.add("meshes", meshes);

        // material with an embedded PNG
        int imageView = b.bytesView(png());
        JsonObject image = new JsonObject();
        image.addProperty("bufferView", imageView);
        image.addProperty("mimeType", "image/png");
        JsonArray images = new JsonArray();
        images.add(image);
        json.add("images", images);
        JsonObject sampler = new JsonObject();
        sampler.addProperty("magFilter", 9728);
        JsonArray samplers = new JsonArray();
        samplers.add(sampler);
        json.add("samplers", samplers);
        JsonObject texture = new JsonObject();
        texture.addProperty("source", 0);
        texture.addProperty("sampler", 0);
        JsonArray textures = new JsonArray();
        textures.add(texture);
        json.add("textures", textures);
        JsonObject material = new JsonObject();
        material.addProperty("name", "skin");
        JsonObject pbr = new JsonObject();
        JsonObject baseTex = new JsonObject();
        baseTex.addProperty("index", 0);
        pbr.add("baseColorTexture", baseTex);
        material.add("pbrMetallicRoughness", pbr);
        material.addProperty("alphaMode", "MASK");
        material.addProperty("doubleSided", true);
        JsonArray materials = new JsonArray();
        materials.add(material);
        json.add("materials", materials);

        JsonArray anims = new JsonArray();
        JsonObject jump = linear(b, "jump", BODY, "translation", new float[]{0, 1}, 0, 1, 0, 0, 2, 0);
        JsonObject extras = new JsonObject();
        JsonArray events = new JsonArray();
        JsonObject ev = new JsonObject();
        ev.addProperty("time", 0.5f);
        ev.addProperty("type", "animatio:sound");
        ev.addProperty("sound", "minecraft:block.note_block.bell");
        events.add(ev);
        extras.add("animatio:events", events);
        jump.add("extras", extras);
        anims.add(jump);
        anims.add(linear(b, "fall", BODY, "translation", new float[]{0, 0.5f}, 0, 2, 0, 0, 0, 0));
        anims.add(anim(b, "blink", HEAD, "scale", "STEP", new float[]{0, 0.1f, 0.2f}, 1, 1, 1, 1, 0.1f, 1, 1, 1, 1));
        anims.add(linear(b, "spin", HEAD, "rotation", new float[]{0, 1}, 0, 0, 0, 1, 0, S45, 0, S45));
        anims.add(anim(b, "cubic", BODY, "translation", "CUBICSPLINE", new float[]{0, 1},
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 4, 0, 0, 0, 0));
        anims.add(linear(b, "pose_a", BODY, "translation", new float[]{0, 1}, 0, 2, 0, 0, 2, 0));
        anims.add(linear(b, "pose_b", BODY, "translation", new float[]{0, 1}, 0, 4, 0, 0, 4, 0));
        anims.add(linear(b, "idle", BODY, "translation", new float[]{0, 1}, 0, 1, 0, 0, 1, 0));
        anims.add(linear(b, "walk", BODY, "translation", new float[]{0, 1}, 1, 1, 0, 1, 1, 0));
        anims.add(linear(b, "run", BODY, "translation", new float[]{0, 2}, 3, 1, 0, 3, 1, 0));
        json.add("animations", anims);

        return b.build(json);
    }

    public static ModelData load() {
        try {
            return GltfLoader.load("test", glb(), true, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Animation set with idle/walk/run/pose_a/pose_b looping by default. */
    public static AnimationSet set() {
        ModelData model = load();
        AnimationSet.Builder builder = AnimationSet.builder("test", model.skeleton());
        for (Clip clip : model.clips()) builder.clip(ClipInfo.of(clip).withLoop(LOOPING.contains(clip.name())));
        return builder.build();
    }

    private static JsonObject node(String name, float[] translation, int[] children) {
        JsonObject n = new JsonObject();
        n.addProperty("name", name);
        if (translation != null) n.add("translation", floats(translation));
        if (children != null) n.add("children", ints(children));
        return n;
    }

    private static JsonObject mesh(String name, JsonObject primitive) {
        JsonObject m = new JsonObject();
        m.addProperty("name", name);
        JsonArray prims = new JsonArray();
        prims.add(primitive);
        m.add("primitives", prims);
        return m;
    }

    private static JsonObject linear(GlbBuilder b, String name, int node, String path, float[] times, float... values) {
        return anim(b, name, node, path, "LINEAR", times, values);
    }

    private static JsonObject anim(GlbBuilder b, String name, int node, String path, String interp, float[] times, float... values) {
        int input = b.floats("SCALAR", times);
        int output = b.floats(path.equals("rotation") ? "VEC4" : "VEC3", values);
        JsonObject sampler = new JsonObject();
        sampler.addProperty("input", input);
        sampler.addProperty("output", output);
        sampler.addProperty("interpolation", interp);
        JsonObject target = new JsonObject();
        target.addProperty("node", node);
        target.addProperty("path", path);
        JsonObject channel = new JsonObject();
        channel.addProperty("sampler", 0);
        channel.add("target", target);
        JsonObject anim = new JsonObject();
        anim.addProperty("name", name);
        JsonArray samplers = new JsonArray();
        samplers.add(sampler);
        JsonArray channels = new JsonArray();
        channels.add(channel);
        anim.add("samplers", samplers);
        anim.add("channels", channels);
        return anim;
    }

    private static JsonArray ints(int... values) {
        JsonArray a = new JsonArray();
        for (int v : values) a.add(v);
        return a;
    }

    private static JsonArray floats(float... values) {
        JsonArray a = new JsonArray();
        for (float v : values) a.add(v);
        return a;
    }

    private static byte[] png() {
        try {
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, 0xFFFF0000);
            img.setRGB(1, 1, 0xFF00FF00);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
