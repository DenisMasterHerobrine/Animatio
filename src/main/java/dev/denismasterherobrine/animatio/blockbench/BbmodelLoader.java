package dev.denismasterherobrine.animatio.blockbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.model.Channel;
import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.gltf.GltfException;
import dev.denismasterherobrine.animatio.gltf.ImageData;
import dev.denismasterherobrine.animatio.gltf.MaterialData;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Loads Blockbench projects ({@code .bbmodel}) directly, following Blockbench's own conventions so the model and its
 * animations look as they do in Blockbench:
 * <ul>
 *     <li>groups become bones (pivot = origin, rotation as Euler angles in ZYX order); cubes are baked into their
 *     group's space (inflate, stretch, cube rotation around its origin, the 1/64 box-UV bleed margin, per-face UV
 *     rotation, zero-thickness planes);</li>
 *     <li>locators and null objects become bones too, usable as sockets;</li>
 *     <li>animations are evaluated with Blockbench's interpolation rules (linear, step, Catmull-Rom with loop
 *     wrapping, Bézier, per-bone quaternion interpolation, two-sided keyframes), Molang values included, and
 *     baked at {@value #SAMPLE_RATE} samples per second; loop mode "loop" makes the clip loop by default;</li>
 *     <li>units are pixels (16 per block) and, since Minecraft-style models face north (-Z) in Blockbench, the model
 *     is turned to face +Z like every Animatio model (except block-format projects).</li>
 * </ul>
 * Not supported: mesh elements (use glTF export for those), texture animation, sound/particle keyframes.
 */
public final class BbmodelLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final float PIXEL = 1f / 16f;
    static final int SAMPLE_RATE = 60;
    private static final String[] FACES = {"east", "west", "up", "down", "south", "north"};
    private static final float[][] FACE_NORMALS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private BbmodelLoader() {
    }

    /** True if the bytes look like a Blockbench project (JSON with a {@code meta.format_version}). */
    public static boolean isBbmodel(byte[] data) {
        int i = 0;
        while (i < data.length && Character.isWhitespace(data[i])) i++;
        return i < data.length && data[i] == '{' && new String(data, 0, Math.min(data.length, 256), StandardCharsets.UTF_8).contains("\"meta\"");
    }

    /**
     * @param geometry load cubes, materials and textures; {@code false} loads only the skeleton and clips (server)
     * @param resolver loads textures stored as files next to the model (when the project does not embed them), or null
     */
    public static ModelData load(String name, byte[] data, boolean geometry, @Nullable Function<String, byte[]> resolver) throws GltfException {
        try {
            JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Builder(name, root, geometry, resolver).build();
        } catch (GltfException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GltfException("Malformed Blockbench model '" + name + "': " + e, e);
        }
    }

    // ------------------------------------------------------------------ JSON helpers

    static float num(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try {
            return e.getAsFloat();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    static float[] vec(JsonObject o, String key, float[] fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return fallback.clone();
        JsonArray a = e.getAsJsonArray();
        float[] out = fallback.clone();
        for (int i = 0; i < Math.min(a.size(), out.length); i++) {
            if (a.get(i).isJsonPrimitive()) out[i] = a.get(i).getAsFloat();
        }
        return out;
    }

    static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
    }

    /** Rotation quaternion of Blockbench Euler angles in degrees (order ZYX: R = Rz * Ry * Rx). */
    static Quaternionf euler(float x, float y, float z, Quaternionf out) {
        return out.rotationZYX((float) Math.toRadians(z), (float) Math.toRadians(y), (float) Math.toRadians(x));
    }

    // ------------------------------------------------------------------ builder

    private static final class Node {
        final String uuid;
        final String name;
        final int parent;
        final float[] origin;   // absolute, pixels
        final float[] rotation; // degrees

        Node(String uuid, String name, int parent, float[] origin, float[] rotation) {
            this.uuid = uuid;
            this.name = name;
            this.parent = parent;
            this.origin = origin;
            this.rotation = rotation;
        }
    }

    private record CubeRef(JsonObject cube, int node) {
    }

    private static final class Builder {
        private final String name;
        private final JsonObject root;
        private final boolean geometry;
        private final @Nullable Function<String, byte[]> resolver;
        private final String format;
        private final List<Node> nodes = new ArrayList<>();
        private final Map<String, Integer> nodeByUuid = new HashMap<>();
        private final List<CubeRef> cubes = new ArrayList<>();
        private final Map<String, JsonObject> groupsByUuid = new HashMap<>();
        private final Map<String, JsonObject> elementsByUuid = new HashMap<>();
        private Skeleton skeleton;

        Builder(String name, JsonObject root, boolean geometry, @Nullable Function<String, byte[]> resolver) {
            this.name = name;
            this.root = root;
            this.geometry = geometry;
            this.resolver = resolver;
            JsonObject meta = root.getAsJsonObject("meta");
            this.format = meta != null ? str(meta, "model_format", "free") : "free";
        }

        ModelData build() throws GltfException {
            for (JsonElement e : array(root, "groups")) {
                JsonObject g = e.getAsJsonObject();
                groupsByUuid.put(str(g, "uuid", ""), g);
            }
            for (JsonElement e : array(root, "elements")) {
                JsonObject el = e.getAsJsonObject();
                elementsByUuid.put(str(el, "uuid", ""), el);
            }

            // node 0: the model root, turning Minecraft-style (north-facing) models toward +Z
            nodes.add(new Node("", "bbmodel_root", -1, new float[3], new float[3]));
            for (JsonElement e : array(root, "outliner")) addOutliner(e, 0);
            buildSkeleton();

            List<Clip> clips = new ArrayList<>();
            Map<String, Boolean> loops = new LinkedHashMap<>();
            buildClips(clips, loops);

            List<MeshData> meshes = new ArrayList<>();
            List<MaterialData> materials = new ArrayList<>();
            List<ImageData> images = new ArrayList<>();
            IntArrayList palette = new IntArrayList();
            if (geometry) {
                buildTextures(materials, images);
                buildMeshes(meshes, palette, materials.size());
            }
            int[] pn = palette.toIntArray();
            float[] ibm = new float[pn.length * 16];
            for (int i = 0; i < pn.length; i++) Mat4.identity(ibm, i * 16);
            return new ModelData(name, skeleton, List.copyOf(clips), List.copyOf(meshes), List.copyOf(materials), List.copyOf(images),
                    pn, ibm, jointBounds(meshes, pn.length), List.of(), List.of(), loops);
        }

        private static JsonArray array(JsonObject o, String key) {
            JsonElement e = o.get(key);
            return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
        }

        // ------------------------------------------------------------------ hierarchy

        /** An outliner entry: a group (object, or uuid of a group) with children, or the uuid of an element. */
        private void addOutliner(JsonElement entry, int parent) {
            if (entry.isJsonPrimitive()) {
                String uuid = entry.getAsString();
                JsonObject el = elementsByUuid.get(uuid);
                if (el != null) {
                    addElement(el, parent);
                } else if (groupsByUuid.containsKey(uuid)) {
                    addGroup(groupsByUuid.get(uuid), new JsonArray(), parent);
                }
                return;
            }
            if (!entry.isJsonObject()) return;
            JsonObject obj = entry.getAsJsonObject();
            String uuid = str(obj, "uuid", "");
            // format 5 keeps group data in "groups"; older projects inline it in the outliner
            JsonObject data = groupsByUuid.getOrDefault(uuid, obj);
            addGroup(data, array(obj, "children"), parent);
        }

        private void addGroup(JsonObject group, JsonArray children, int parent) {
            if (!bool(group, "export", true)) return;
            String uuid = str(group, "uuid", "group_" + nodes.size());
            int index = nodes.size();
            nodes.add(new Node(uuid, str(group, "name", "group"), parent, vec(group, "origin", new float[3]), vec(group, "rotation", new float[3])));
            nodeByUuid.put(uuid, index);
            for (JsonElement child : children) addOutliner(child, index);
        }

        private void addElement(JsonObject el, int parent) {
            if (!bool(el, "export", true)) return;
            String type = str(el, "type", "cube");
            switch (type) {
                case "cube" -> cubes.add(new CubeRef(el, parent));
                case "locator", "null_object" -> {
                    String uuid = str(el, "uuid", "locator_" + nodes.size());
                    float[] position = vec(el, "position", vec(el, "from", new float[3]));
                    int index = nodes.size();
                    nodes.add(new Node(uuid, str(el, "name", type), parent, position, vec(el, "rotation", new float[3])));
                    nodeByUuid.put(uuid, index);
                }
                case "mesh" -> LOGGER.warn("Blockbench model '{}': mesh element '{}' is not supported (export the model as glTF to use meshes)",
                        name, str(el, "name", "?"));
                default -> {
                    // texture meshes, billboards, splines, cameras of the editor: nothing to render here
                }
            }
        }

        private void buildSkeleton() {
            int n = nodes.size();
            String[] names = new String[n];
            int[] parents = new int[n];
            float[] t = new float[n * 3], r = new float[n * 4], s = new float[n * 3];
            Set<String> used = new HashSet<>();
            Quaternionf q = new Quaternionf();
            for (int i = 0; i < n; i++) {
                Node node = nodes.get(i);
                String nodeName = node.name;
                if (!used.add(nodeName)) {
                    int k = 2;
                    while (!used.add(nodeName + "_" + k)) k++;
                    String unique = nodeName + "_" + k;
                    LOGGER.debug("Blockbench model '{}': duplicate bone name '{}' renamed to '{}'", name, nodeName, unique);
                    nodeName = unique;
                }
                names[i] = nodeName;
                parents[i] = node.parent;
                s[i * 3] = s[i * 3 + 1] = s[i * 3 + 2] = 1f;
                if (i == 0) {
                    // the model root: block projects keep their 0..16 placement, others turn around to face +Z
                    if (format.equals("java_block")) {
                        t[0] = -0.5f;
                        t[2] = -0.5f;
                        r[3] = 1f;
                    } else {
                        r[1] = 1f; // 180 degrees about Y
                    }
                    continue;
                }
                float[] parentOrigin = node.parent > 0 ? nodes.get(node.parent).origin : new float[3];
                for (int c = 0; c < 3; c++) t[i * 3 + c] = (node.origin[c] - parentOrigin[c]) * PIXEL;
                euler(node.rotation[0], node.rotation[1], node.rotation[2], q);
                r[i * 4] = q.x;
                r[i * 4 + 1] = q.y;
                r[i * 4 + 2] = q.z;
                r[i * 4 + 3] = q.w;
            }
            skeleton = new Skeleton(names, parents, t, r, s);
        }

        // ------------------------------------------------------------------ textures and materials

        private void buildTextures(List<MaterialData> materials, List<ImageData> images) {
            JsonArray textures = array(root, "textures");
            for (int i = 0; i < textures.size(); i++) {
                JsonObject tex = textures.get(i).getAsJsonObject();
                String texName = str(tex, "name", "texture_" + i);
                byte[] png = textureBytes(tex, texName);
                int image = -1;
                if (png != null) {
                    image = images.size();
                    images.add(new ImageData(texName, "image/png", png));
                }
                String material = texName.endsWith(".png") ? texName.substring(0, texName.length() - 4) : texName;
                boolean doubleSided = !"front".equals(str(tex, "render_sides", "auto"));
                materials.add(new MaterialData(material, image, new float[]{1, 1, 1, 1}, -1, new float[]{0, 0, 0},
                        MaterialData.AlphaMode.MASK, 0.05f, doubleSided, true, false));
            }
        }

        private byte @Nullable [] textureBytes(JsonObject tex, String texName) {
            String source = str(tex, "source", "");
            int comma = source.indexOf(',');
            if (source.startsWith("data:") && comma > 0) {
                try {
                    return Base64.getDecoder().decode(source.substring(comma + 1));
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Blockbench model '{}': texture '{}' has malformed embedded data", name, texName);
                }
            }
            if (resolver != null) {
                String file = texName.endsWith(".png") ? texName : texName + ".png";
                byte[] bytes = resolver.apply(file);
                if (bytes != null) return bytes;
            }
            LOGGER.warn("Blockbench model '{}': texture '{}' is not embedded and was not found next to the model", name, texName);
            return null;
        }

        // ------------------------------------------------------------------ cubes

        /** Vertex streams of one material. */
        private static final class Stream {
            final FloatArrayList pos = new FloatArrayList(), nrm = new FloatArrayList(), uv = new FloatArrayList();
            final IntArrayList joints = new IntArrayList(), idx = new IntArrayList();
        }

        private void buildMeshes(List<MeshData> out, IntArrayList palette, int materialCount) {
            JsonArray textures = array(root, "textures");
            Map<String, Integer> textureByUuid = new HashMap<>();
            for (int i = 0; i < textures.size(); i++) textureByUuid.put(str(textures.get(i).getAsJsonObject(), "uuid", ""), i);
            JsonObject resolution = root.getAsJsonObject("resolution");
            float projectW = resolution != null ? num(resolution, "width", 16) : 16, projectH = resolution != null ? num(resolution, "height", 16) : 16;

            Map<Integer, Stream> streams = new LinkedHashMap<>();
            int[] entryOfNode = new int[nodes.size()];
            java.util.Arrays.fill(entryOfNode, -1);
            Matrix4f m = new Matrix4f();
            Vector3f v = new Vector3f();
            Quaternionf q = new Quaternionf();
            for (CubeRef ref : cubes) {
                JsonObject cube = ref.cube;
                JsonObject faces = cube.getAsJsonObject("faces");
                if (faces == null) continue;
                float[] from = vec(cube, "from", new float[3]), to = vec(cube, "to", new float[3]);
                float[] origin = vec(cube, "origin", new float[3]), rotation = vec(cube, "rotation", new float[3]);
                float[] stretch = vec(cube, "stretch", new float[]{1, 1, 1});
                float inflate = num(cube, "inflate", 0);
                boolean boxUv = bool(cube, "box_uv", false);
                // inflate and stretch around the centre, then relative to the cube's origin
                float[] a = new float[3], b = new float[3];
                for (int c = 0; c < 3; c++) {
                    float half = (to[c] - from[c]) / 2f, center = from[c] + half;
                    a[c] = center - (half + inflate) * stretch[c] - origin[c];
                    b[c] = center + (half + inflate) * stretch[c] - origin[c];
                    if (a[c] == b[c]) b[c] += 0.001f;
                }
                // cube transform in its group's space: translate(origin - group origin) * R(rotation) * rescale
                float[] parentOrigin = ref.node > 0 ? nodes.get(ref.node).origin : new float[3];
                m.translation(origin[0] - parentOrigin[0], origin[1] - parentOrigin[1], origin[2] - parentOrigin[2]);
                euler(rotation[0], rotation[1], rotation[2], q);
                m.rotate(q);
                if (bool(cube, "rescale", false)) {
                    float rx = rescale(rotation[0]), ry = rescale(rotation[1]), rz = rescale(rotation[2]);
                    m.scale(ry * rz, rx * rz, rx * ry);
                }
                int entry = entryOfNode[ref.node];
                if (entry < 0) {
                    entry = entryOfNode[ref.node] = palette.size();
                    palette.add(ref.node);
                }

                float[][] corners = shape(a, b);
                for (int f = 0; f < 6; f++) {
                    JsonElement faceElement = faces.get(FACES[f]);
                    if (faceElement == null || !faceElement.isJsonObject()) continue;
                    JsonObject face = faceElement.getAsJsonObject();
                    JsonElement texRef = face.get("texture");
                    if (texRef == null || texRef.isJsonNull()) continue;
                    int texture = -1;
                    if (texRef.isJsonPrimitive() && texRef.getAsJsonPrimitive().isNumber()) texture = texRef.getAsInt();
                    else if (texRef.isJsonPrimitive()) texture = textureByUuid.getOrDefault(texRef.getAsString(), -1);
                    if (texture >= materialCount) texture = -1;
                    Stream s = streams.computeIfAbsent(texture, k -> new Stream());
                    float pw = projectW, ph = projectH;
                    if (texture >= 0) {
                        JsonObject tex = textures.get(texture).getAsJsonObject();
                        pw = num(tex, "uv_width", projectW);
                        ph = num(tex, "uv_height", projectH);
                    }
                    float[] uv = vec(face, "uv", new float[4]);
                    if (boxUv) { // Blockbench's margin against texture bleeding
                        for (int i = 0; i < 2; i++) {
                            float margin = uv[i] > uv[i + 2] ? -1f / 64f : 1f / 64f;
                            uv[i] += margin;
                            uv[i + 2] -= margin;
                        }
                    }
                    // vertex k of the face gets: 0 -> (u0,v0), 1 -> (u1,v0), 2 -> (u0,v1), 3 -> (u1,v1), then rotated
                    float[][] fuv = {{uv[0] / pw, uv[1] / ph}, {uv[2] / pw, uv[1] / ph}, {uv[0] / pw, uv[3] / ph}, {uv[2] / pw, uv[3] / ph}};
                    int rot = (int) num(face, "rotation", 0);
                    while (rot > 0) {
                        float[] t0 = fuv[0];
                        fuv[0] = fuv[2];
                        fuv[2] = fuv[3];
                        fuv[3] = fuv[1];
                        fuv[1] = t0;
                        rot -= 90;
                    }
                    int base = s.pos.size() / 3;
                    float[] fn = FACE_NORMALS[f];
                    v.set(fn[0], fn[1], fn[2]);
                    m.transformDirection(v).normalize();
                    float nx = v.x, ny = v.y, nz = v.z;
                    for (int k = 0; k < 4; k++) {
                        float[] c = corners[f * 4 + k];
                        m.transformPosition(v.set(c[0], c[1], c[2]));
                        s.pos.add(v.x * PIXEL);
                        s.pos.add(v.y * PIXEL);
                        s.pos.add(v.z * PIXEL);
                        s.nrm.add(nx);
                        s.nrm.add(ny);
                        s.nrm.add(nz);
                        s.uv.add(fuv[k][0]);
                        s.uv.add(fuv[k][1]);
                        s.joints.add(entry);
                    }
                    s.idx.add(base);
                    s.idx.add(base + 2);
                    s.idx.add(base + 1);
                    s.idx.add(base + 2);
                    s.idx.add(base + 3);
                    s.idx.add(base + 1);
                }
            }
            for (Map.Entry<Integer, Stream> e : streams.entrySet()) {
                Stream s = e.getValue();
                int count = s.pos.size() / 3;
                int[] joints = new int[count * 4];
                float[] weights = new float[count * 4];
                for (int i = 0; i < count; i++) {
                    java.util.Arrays.fill(joints, i * 4, i * 4 + 4, s.joints.getInt(i));
                    weights[i * 4] = 1f;
                }
                String meshName = e.getKey() >= 0 ? "bbmodel_" + e.getKey() : "bbmodel_untextured";
                out.add(new MeshData(meshName, e.getKey(), count, s.pos.toFloatArray(), s.nrm.toFloatArray(), s.uv.toFloatArray(),
                        null, joints, weights, s.idx.toIntArray()));
            }
        }

        /** Scale that keeps a rotated cube's footprint ("rescale"), per Blockbench. */
        private static float rescale(float angle) {
            angle = Math.abs(angle);
            if (angle > 45) angle = 90 - angle;
            return (float) (1.0 / Math.cos(Math.toRadians(angle)));
        }

        /** The 24 face corners of a box in Blockbench's order: east, west, up, down, south, north; 4 corners each. */
        private static float[][] shape(float[] f, float[] t) {
            return new float[][]{
                    {t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], t[2]}, {t[0], f[1], f[2]},
                    {f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], f[2]}, {f[0], f[1], t[2]},
                    {f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {f[0], t[1], t[2]}, {t[0], t[1], t[2]},
                    {f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], f[2]}, {t[0], f[1], f[2]},
                    {f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {f[0], f[1], t[2]}, {t[0], f[1], t[2]},
                    {t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {t[0], f[1], f[2]}, {f[0], f[1], f[2]}};
        }

        private static float[] jointBounds(List<MeshData> meshes, int paletteSize) {
            float[] b = new float[paletteSize * 6];
            for (int i = 0; i < paletteSize; i++) {
                b[i * 6] = b[i * 6 + 1] = b[i * 6 + 2] = Float.POSITIVE_INFINITY;
                b[i * 6 + 3] = b[i * 6 + 4] = b[i * 6 + 5] = Float.NEGATIVE_INFINITY;
            }
            for (MeshData mesh : meshes) {
                float[] p = mesh.positions();
                for (int v = 0; v < mesh.vertexCount(); v++) {
                    int o = mesh.joints()[v * 4] * 6;
                    for (int c = 0; c < 3; c++) {
                        b[o + c] = Math.min(b[o + c], p[v * 3 + c]);
                        b[o + 3 + c] = Math.max(b[o + 3 + c], p[v * 3 + c]);
                    }
                }
            }
            return b;
        }

        // ------------------------------------------------------------------ animations

        private void buildClips(List<Clip> clips, Map<String, Boolean> loops) throws GltfException {
            Set<String> names = new HashSet<>();
            for (JsonElement ae : array(root, "animations")) {
                JsonObject anim = ae.getAsJsonObject();
                String clipName = str(anim, "name", "animation_" + clips.size());
                if (!names.add(clipName)) {
                    int k = 2;
                    while (!names.add(clipName + "_" + k)) k++;
                    clipName = clipName + "_" + k;
                }
                String loopMode = str(anim, "loop", "once");
                boolean loop = loopMode.equals("loop");
                float length = Math.max(0f, num(anim, "length", 0f));
                List<Channel> channels = new ArrayList<>();
                JsonObject animators = anim.getAsJsonObject("animators");
                if (animators != null) {
                    for (Map.Entry<String, JsonElement> e : animators.entrySet()) {
                        Integer node = nodeByUuid.get(e.getKey());
                        if (node == null || !e.getValue().isJsonObject()) continue; // effects, missing bones
                        JsonObject animator = e.getValue().getAsJsonObject();
                        if (bool(animator, "rotation_global", false)) {
                            LOGGER.warn("Blockbench model '{}': global rotation on bone '{}' in '{}' is not supported; using local rotation",
                                    name, nodes.get(node).name, clipName);
                        }
                        BbAnimator bb = BbAnimator.parse(animator, loop, length);
                        if (bb.isEmpty()) continue;
                        bb.bake(node, nodes.get(node).origin, parentOrigin(node), nodes.get(node).rotation,
                                bool(animator, "quaternion_interpolation", false), channels);
                    }
                }
                try {
                    clips.add(new Clip(clipName, channels.toArray(new Channel[0]), skeleton, List.of()));
                } catch (IllegalArgumentException ex) {
                    throw new GltfException("Animation '" + clipName + "': " + ex.getMessage());
                }
                loops.put(clipName, loop);
                if (!loopMode.equals("loop") && !loopMode.equals("once") && !loopMode.equals("hold")) {
                    LOGGER.debug("Blockbench model '{}': unknown loop mode '{}' in '{}'", name, loopMode, clipName);
                }
            }
        }

        private float[] parentOrigin(int node) {
            int p = nodes.get(node).parent;
            return p > 0 ? nodes.get(p).origin : new float[3];
        }
    }
}
