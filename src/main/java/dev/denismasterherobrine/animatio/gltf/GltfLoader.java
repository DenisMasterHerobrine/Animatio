package dev.denismasterherobrine.animatio.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.model.Channel;
import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.model.ModelCamera;
import dev.denismasterherobrine.animatio.anim.model.ModelLight;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds {@link ModelData} from a glTF document.
 * <p>
 * Supported: node hierarchy (TRS or matrix), skins, triangle/strip/fan primitives with POSITION, NORMAL,
 * TEXCOORD_0, COLOR_0, JOINTS_0 and WEIGHTS_0, morph targets (POSITION and NORMAL deltas, default and animated
 * weights, {@code extras.targetNames}), {@code KHR_draco_mesh_compression}, PBR base colour and emissive textures,
 * PNG/JPEG/KTX2 ({@code KHR_texture_basisu}) images, alpha modes, cameras, {@code KHR_lights_punctual}, and
 * translation/rotation/scale/weights animation channels with STEP, LINEAR and CUBICSPLINE interpolation.
 */
public final class GltfLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final int MODE_TRIANGLES = 4, MODE_STRIP = 5, MODE_FAN = 6;
    private static final int FILTER_LINEAR = 9729;
    /** Extensions this loader understands; files that require anything else load with a warning. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("KHR_draco_mesh_compression", "KHR_texture_basisu",
            "KHR_lights_punctual", "KHR_materials_unlit", "KHR_materials_emissive_strength", "KHR_mesh_quantization");

    private GltfLoader() {
    }

    /**
     * @param geometry load meshes, materials and images; {@code false} loads only the skeleton and clips (server)
     */
    public static ModelData load(String name, byte[] data, boolean geometry, @Nullable Function<String, byte[]> resolver) throws GltfException {
        GltfDocument doc = GltfDocument.read(data, resolver);
        try {
            return new GltfLoader.Builder(name, doc, geometry).build();
        } catch (GltfException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GltfException("Malformed glTF '" + name + "': " + e, e);
        }
    }

    static @Nullable JsonObject extension(JsonObject obj, String name) {
        JsonObject ext = obj.getAsJsonObject("extensions");
        if (ext == null) return null;
        JsonElement e = ext.get(name);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static final class Builder {
        private final String name;
        private final GltfDocument doc;
        private final boolean geometry;
        private Skeleton skeleton;
        private final IntArrayList paletteNodes = new IntArrayList();
        private final List<float[]> paletteIbm = new ArrayList<>();
        private final Int2IntOpenHashMap skinOffsets = new Int2IntOpenHashMap();
        private final Int2IntOpenHashMap rigidEntries = new Int2IntOpenHashMap();
        /** Key times by accessor, so channels sharing an input accessor share one array (and one key lookup). */
        private final Int2ObjectOpenHashMap<float[]> timesByAccessor = new Int2ObjectOpenHashMap<>();

        Builder(String name, GltfDocument doc, boolean geometry) {
            this.name = name;
            this.doc = doc;
            this.geometry = geometry;
            skinOffsets.defaultReturnValue(-1);
            rigidEntries.defaultReturnValue(-1);
        }

        ModelData build() throws GltfException {
            for (JsonElement e : doc.array("extensionsRequired")) {
                String ext = e.getAsString();
                if (!SUPPORTED_EXTENSIONS.contains(ext)) {
                    LOGGER.warn("Model '{}' requires extension {}, which Animatio does not support; it may render incorrectly", name, ext);
                }
            }
            JsonArray nodes = doc.array("nodes");
            skeleton = buildSkeleton(nodes);
            List<Clip> clips = buildClips(nodes.size());
            List<ModelCamera> cameras = buildCameras(nodes);
            List<ModelLight> lights = buildLights(nodes);

            List<MeshData> meshes = new ArrayList<>();
            List<MaterialData> materials = new ArrayList<>();
            List<ImageData> images = new ArrayList<>();
            if (geometry) {
                for (int i = 0; i < nodes.size(); i++) {
                    JsonObject node = nodes.get(i).getAsJsonObject();
                    if (node.has("mesh")) buildMeshes(i, node, meshes);
                }
                materials = buildMaterials();
                images = buildImages();
            }

            int paletteSize = paletteNodes.size();
            int[] pn = paletteNodes.toIntArray();
            float[] ibm = new float[paletteSize * 16];
            for (int i = 0; i < paletteSize; i++) System.arraycopy(paletteIbm.get(i), 0, ibm, i * 16, 16);
            float[] bounds = jointBounds(meshes, paletteSize);
            return new ModelData(name, skeleton, List.copyOf(clips), List.copyOf(meshes), List.copyOf(materials),
                    List.copyOf(images), pn, ibm, bounds, cameras, lights);
        }

        // ------------------------------------------------------------------ skeleton

        private Skeleton buildSkeleton(JsonArray nodes) throws GltfException {
            int n = nodes.size();
            String[] names = new String[n];
            int[] parents = new int[n];
            Arrays.fill(parents, -1);
            float[] t = new float[n * 3], r = new float[n * 4], s = new float[n * 3];
            int[] morphCount = new int[n];
            List<float[]> morphDefaults = new ArrayList<>();
            List<String> morphNames = new ArrayList<>();
            Set<String> used = new HashSet<>();
            Matrix4f m = new Matrix4f();
            Vector3f vt = new Vector3f(), vs = new Vector3f();
            Quaternionf q = new Quaternionf();
            for (int i = 0; i < n; i++) {
                JsonObject node = nodes.get(i).getAsJsonObject();
                String nodeName = GltfDocument.stringOr(node, "name", "node_" + i);
                if (!used.add(nodeName)) {
                    String unique = nodeName + "_" + i;
                    LOGGER.warn("Model '{}' has duplicate node name '{}', renamed to '{}'", name, nodeName, unique);
                    nodeName = unique;
                    used.add(nodeName);
                }
                names[i] = nodeName;
                for (JsonElement c : GltfDocument.array(node, "children")) {
                    int child = c.getAsInt();
                    if (child < 0 || child >= n) throw new GltfException("Node " + i + " has missing child " + child);
                    if (parents[child] >= 0) throw new GltfException("Node " + child + " has more than one parent");
                    parents[child] = i;
                }
                if (node.has("matrix")) {
                    float[] mat = GltfDocument.floats(node, "matrix", new float[16]);
                    if (mat.length != 16) throw new GltfException("Node " + i + " matrix must have 16 values");
                    m.set(mat);
                    m.getTranslation(vt);
                    m.getScale(vs);
                    if (m.determinant3x3() < 0f) { // mirrored: move the reflection into the scale
                        vs.x = -vs.x;
                        m.scale(-1f, 1f, 1f);
                    }
                    m.getNormalizedRotation(q);
                    t[i * 3] = vt.x;
                    t[i * 3 + 1] = vt.y;
                    t[i * 3 + 2] = vt.z;
                    r[i * 4] = q.x;
                    r[i * 4 + 1] = q.y;
                    r[i * 4 + 2] = q.z;
                    r[i * 4 + 3] = q.w;
                    s[i * 3] = vs.x;
                    s[i * 3 + 1] = vs.y;
                    s[i * 3 + 2] = vs.z;
                } else {
                    float[] tr = GltfDocument.floats(node, "translation", new float[]{0, 0, 0});
                    float[] rot = GltfDocument.floats(node, "rotation", new float[]{0, 0, 0, 1});
                    float[] sc = GltfDocument.floats(node, "scale", new float[]{1, 1, 1});
                    if (tr.length != 3 || rot.length != 4 || sc.length != 3) throw new GltfException("Node " + i + " has a malformed transform");
                    System.arraycopy(tr, 0, t, i * 3, 3);
                    q.set(rot[0], rot[1], rot[2], rot[3]).normalize();
                    r[i * 4] = q.x;
                    r[i * 4 + 1] = q.y;
                    r[i * 4 + 2] = q.z;
                    r[i * 4 + 3] = q.w;
                    System.arraycopy(sc, 0, s, i * 3, 3);
                }
                if (node.has("mesh")) {
                    JsonObject mesh = doc.element("meshes", node.get("mesh").getAsInt());
                    int targets = targetCount(mesh);
                    if (targets > 0) {
                        morphCount[i] = targets;
                        float[] w = GltfDocument.floats(node, "weights", GltfDocument.floats(mesh, "weights", new float[targets]));
                        if (w.length != targets) w = GltfDocument.floats(mesh, "weights", new float[targets]);
                        if (w.length != targets) w = new float[targets];
                        morphDefaults.add(w);
                        String[] targetNames = targetNames(mesh, targets);
                        morphNames.addAll(Arrays.asList(targetNames));
                    }
                }
            }
            float[] restMorph = new float[morphNames.size()];
            int o = 0;
            for (float[] w : morphDefaults) {
                System.arraycopy(w, 0, restMorph, o, w.length);
                o += w.length;
            }
            try {
                return new Skeleton(names, parents, t, r, s, morphCount, restMorph, morphNames.toArray(new String[0]));
            } catch (IllegalArgumentException e) {
                throw new GltfException(e.getMessage());
            }
        }

        /** Morph targets of a mesh: all its primitives must have the same number; the smallest is used. */
        private static int targetCount(JsonObject mesh) {
            int count = -1;
            for (JsonElement p : GltfDocument.array(mesh, "primitives")) {
                int c = GltfDocument.array(p.getAsJsonObject(), "targets").size();
                count = count < 0 ? c : Math.min(count, c);
            }
            return Math.max(0, count);
        }

        private static String[] targetNames(JsonObject mesh, int targets) {
            String[] out = new String[targets];
            JsonObject extras = mesh.getAsJsonObject("extras");
            JsonArray names = extras != null ? GltfDocument.array(extras, "targetNames") : new JsonArray();
            for (int k = 0; k < targets; k++) {
                JsonElement e = k < names.size() ? names.get(k) : null;
                out[k] = e != null && e.isJsonPrimitive() ? e.getAsString() : "target_" + k;
            }
            return out;
        }

        // ------------------------------------------------------------------ cameras and lights

        private List<ModelCamera> buildCameras(JsonArray nodes) throws GltfException {
            List<ModelCamera> out = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                JsonObject node = nodes.get(i).getAsJsonObject();
                if (!node.has("camera")) continue;
                JsonObject cam = doc.element("cameras", node.get("camera").getAsInt());
                boolean perspective = !"orthographic".equals(GltfDocument.stringOr(cam, "type", "perspective"));
                JsonObject p = cam.getAsJsonObject(perspective ? "perspective" : "orthographic");
                if (p == null) p = new JsonObject();
                out.add(new ModelCamera(skeleton.name(i), i, perspective,
                        GltfDocument.floatOr(p, "yfov", (float) Math.toRadians(70)),
                        GltfDocument.floatOr(p, "aspectRatio", 0f),
                        GltfDocument.floatOr(p, "znear", 0.05f),
                        GltfDocument.floatOr(p, "zfar", Float.POSITIVE_INFINITY),
                        GltfDocument.floatOr(p, "xmag", 1f),
                        GltfDocument.floatOr(p, "ymag", 1f)));
            }
            return out;
        }

        private List<ModelLight> buildLights(JsonArray nodes) throws GltfException {
            JsonObject root = extension(doc.json(), "KHR_lights_punctual");
            if (root == null) return List.of();
            JsonArray defs = GltfDocument.array(root, "lights");
            List<ModelLight> out = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                JsonObject ext = extension(nodes.get(i).getAsJsonObject(), "KHR_lights_punctual");
                if (ext == null || !ext.has("light")) continue;
                int index = ext.get("light").getAsInt();
                if (index < 0 || index >= defs.size()) throw new GltfException("Node " + i + " references missing light " + index);
                JsonObject l = defs.get(index).getAsJsonObject();
                ModelLight.Type type = switch (GltfDocument.stringOr(l, "type", "point")) {
                    case "spot" -> ModelLight.Type.SPOT;
                    case "directional" -> ModelLight.Type.DIRECTIONAL;
                    default -> ModelLight.Type.POINT;
                };
                float[] color = GltfDocument.floats(l, "color", new float[]{1, 1, 1});
                if (color.length != 3) color = new float[]{1, 1, 1};
                JsonObject spot = l.getAsJsonObject("spot");
                float inner = spot != null ? GltfDocument.floatOr(spot, "innerConeAngle", 0f) : 0f;
                float outer = spot != null ? GltfDocument.floatOr(spot, "outerConeAngle", (float) (Math.PI / 4)) : (float) (Math.PI / 4);
                float range = GltfDocument.floatOr(l, "range", Float.POSITIVE_INFINITY);
                out.add(new ModelLight(skeleton.name(i), i, type, color, GltfDocument.floatOr(l, "intensity", 1f),
                        range > 0f ? range : Float.POSITIVE_INFINITY, inner, outer));
            }
            return out;
        }

        // ------------------------------------------------------------------ animations

        private float[] keyTimes(int accessor) throws GltfException {
            float[] times = timesByAccessor.get(accessor);
            if (times == null) {
                times = doc.readFloats(accessor);
                timesByAccessor.put(accessor, times);
            }
            return times;
        }

        private List<Clip> buildClips(int nodeCount) throws GltfException {
            JsonArray anims = doc.array("animations");
            List<Clip> clips = new ArrayList<>(anims.size());
            Set<String> names = new HashSet<>();
            for (int a = 0; a < anims.size(); a++) {
                JsonObject anim = anims.get(a).getAsJsonObject();
                String clipName = GltfDocument.stringOr(anim, "name", "animation_" + a);
                if (!names.add(clipName)) {
                    int k = 2;
                    while (!names.add(clipName + "_" + k)) k++;
                    clipName = clipName + "_" + k;
                }
                JsonArray samplers = GltfDocument.array(anim, "samplers");
                Map<Long, Channel> channels = new LinkedHashMap<>();
                for (JsonElement ce : GltfDocument.array(anim, "channels")) {
                    JsonObject ch = ce.getAsJsonObject();
                    JsonObject target = ch.getAsJsonObject("target");
                    if (target == null || !target.has("node")) continue;
                    int node = target.get("node").getAsInt();
                    if (node < 0 || node >= nodeCount) throw new GltfException("Animation '" + clipName + "' targets missing node " + node);
                    int path = switch (GltfDocument.stringOr(target, "path", "")) {
                        case "translation" -> Channel.TRANSLATION;
                        case "rotation" -> Channel.ROTATION;
                        case "scale" -> Channel.SCALE;
                        case "weights" -> Channel.WEIGHTS;
                        default -> -1; // extensions (KHR_animation_pointer) are not supported
                    };
                    if (path < 0) continue;
                    if (path == Channel.WEIGHTS && skeleton.morphCount(node) == 0) {
                        LOGGER.warn("Model '{}': animation '{}' animates weights of node '{}', which has no morph targets", name, clipName, skeleton.name(node));
                        continue;
                    }
                    int samplerIndex = ch.get("sampler").getAsInt();
                    if (samplerIndex < 0 || samplerIndex >= samplers.size()) throw new GltfException("Animation '" + clipName + "' references missing sampler");
                    JsonObject sampler = samplers.get(samplerIndex).getAsJsonObject();
                    int interpolation = switch (GltfDocument.stringOr(sampler, "interpolation", "LINEAR")) {
                        case "STEP" -> Channel.STEP;
                        case "CUBICSPLINE" -> Channel.CUBIC;
                        default -> Channel.LINEAR;
                    };
                    float[] times = keyTimes(sampler.get("input").getAsInt());
                    float[] values = doc.readFloats(sampler.get("output").getAsInt());
                    if (times.length == 0) continue;
                    int comps = path == Channel.WEIGHTS ? skeleton.morphCount(node) : Channel.components(path);
                    int expected = times.length * comps * (interpolation == Channel.CUBIC ? 3 : 1);
                    if (values.length != expected) {
                        throw new GltfException("Animation '" + clipName + "' sampler has " + values.length + " values, expected " + expected);
                    }
                    try {
                        channels.put(((long) node << 2) | path, new Channel(node, path, interpolation, times, values, comps));
                    } catch (IllegalArgumentException e) {
                        throw new GltfException("Animation '" + clipName + "': " + e.getMessage());
                    }
                }
                try {
                    clips.add(new Clip(clipName, channels.values().toArray(new Channel[0]), skeleton, rawEvents(anim, clipName)));
                } catch (IllegalArgumentException e) {
                    throw new GltfException("Animation '" + clipName + "': " + e.getMessage());
                }
            }
            return clips;
        }

        private List<Clip.RawEvent> rawEvents(JsonObject anim, String clipName) {
            JsonObject extras = anim.getAsJsonObject("extras");
            if (extras == null) return List.of();
            JsonElement events = extras.has("animatio:events") ? extras.get("animatio:events") : extras.get("events");
            if (events == null || !events.isJsonArray()) return List.of();
            List<Clip.RawEvent> out = new ArrayList<>();
            for (JsonElement e : events.getAsJsonArray()) {
                if (!e.isJsonObject() || !e.getAsJsonObject().has("time")) {
                    LOGGER.warn("Ignoring malformed event in extras of animation '{}' in model '{}'", clipName, name);
                    continue;
                }
                JsonObject obj = e.getAsJsonObject();
                out.add(new Clip.RawEvent(obj.get("time").getAsFloat(), obj));
            }
            return out;
        }

        // ------------------------------------------------------------------ meshes

        private void buildMeshes(int nodeIndex, JsonObject node, List<MeshData> out) throws GltfException {
            int meshIndex = node.get("mesh").getAsInt();
            JsonObject mesh = doc.element("meshes", meshIndex);
            String meshName = GltfDocument.stringOr(mesh, "name", "mesh_" + meshIndex);
            int skin = node.has("skin") ? node.get("skin").getAsInt() : -1;
            JsonArray primitives = GltfDocument.array(mesh, "primitives");
            for (int p = 0; p < primitives.size(); p++) {
                JsonObject prim = primitives.get(p).getAsJsonObject();
                MeshData data = buildPrimitive(nodeIndex, skin, prim, primitives.size() > 1 ? meshName + "_" + p : meshName);
                if (data != null) out.add(data);
            }
        }

        /** Vertex attributes of one primitive before conversion to {@link MeshData}. */
        private static final class Attributes {
            float[] positions, normals, uvs, colors, weights;
            int colorComps;
            int[] joints, indices;
        }

        private @Nullable MeshData buildPrimitive(int nodeIndex, int skin, JsonObject prim, String primName) throws GltfException {
            int mode = GltfDocument.intOr(prim, "mode", MODE_TRIANGLES);
            if (mode != MODE_TRIANGLES && mode != MODE_STRIP && mode != MODE_FAN) {
                LOGGER.warn("Model '{}': skipping primitive '{}' with unsupported mode {} (only triangles are rendered)", name, primName, mode);
                return null;
            }
            JsonObject attrs = prim.getAsJsonObject("attributes");
            if (attrs == null || !attrs.has("POSITION")) {
                LOGGER.warn("Model '{}': skipping primitive '{}' without positions", name, primName);
                return null;
            }
            JsonObject draco = extension(prim, "KHR_draco_mesh_compression");
            Attributes a = draco != null ? DracoReader.read(doc, draco, attrs, primName) : readAttributes(attrs, prim);
            float[] positions = a.positions;
            int vertexCount = positions.length / 3;
            if (positions.length % 3 != 0) throw new GltfException("POSITION must be VEC3");
            int[] indices = triangleIndices(a.indices, mode, vertexCount);

            float[] normals = a.normals;
            if (normals == null || normals.length != vertexCount * 3) normals = computeNormals(positions, indices);
            float[] uvs = a.uvs;
            if (uvs == null || uvs.length != vertexCount * 2) uvs = new float[vertexCount * 2];
            float[] colors = null;
            if (a.colors != null && (a.colorComps == 3 || a.colorComps == 4) && a.colors.length == vertexCount * a.colorComps) {
                int comps = a.colorComps;
                colors = new float[vertexCount * 4];
                for (int v = 0; v < vertexCount; v++) {
                    colors[v * 4] = a.colors[v * comps];
                    colors[v * 4 + 1] = a.colors[v * comps + 1];
                    colors[v * 4 + 2] = a.colors[v * comps + 2];
                    colors[v * 4 + 3] = comps == 4 ? a.colors[v * comps + 3] : 1f;
                }
            }

            int[] joints = new int[vertexCount * 4];
            float[] weights = new float[vertexCount * 4];
            boolean skinned = skin >= 0 && a.joints != null && a.weights != null;
            if (skinned) {
                int offset = skinOffset(skin);
                int jointCount = GltfDocument.array(doc.element("skins", skin), "joints").size();
                if (a.joints.length != vertexCount * 4 || a.weights.length != vertexCount * 4) {
                    throw new GltfException("Primitive '" + primName + "' JOINTS_0/WEIGHTS_0 must be VEC4 per vertex");
                }
                for (int v = 0; v < vertexCount; v++) {
                    float sum = 0f;
                    for (int k = 0; k < 4; k++) {
                        int j = a.joints[v * 4 + k];
                        float w = a.weights[v * 4 + k];
                        if (j < 0 || j >= jointCount) {
                            if (w > 0f) throw new GltfException("Primitive '" + primName + "' uses joint " + j + " but the skin has " + jointCount);
                            j = 0;
                            w = 0f;
                        }
                        joints[v * 4 + k] = offset + j;
                        weights[v * 4 + k] = Math.max(0f, w);
                        sum += weights[v * 4 + k];
                    }
                    if (sum > 1e-6f) {
                        for (int k = 0; k < 4; k++) weights[v * 4 + k] /= sum;
                        sortInfluences(joints, weights, v * 4);
                    } else {
                        Arrays.fill(weights, v * 4, v * 4 + 4, 0f);
                        weights[v * 4] = 1f;
                        joints[v * 4] = offset;
                    }
                }
            } else {
                if (skin >= 0) LOGGER.warn("Model '{}': primitive '{}' is on a skinned node but has no joints; drawing it rigidly", name, primName);
                int entry = rigidEntry(nodeIndex);
                Arrays.fill(joints, entry);
                for (int v = 0; v < vertexCount; v++) weights[v * 4] = 1f;
            }

            // morph targets
            int targets = skeleton.morphCount(nodeIndex);
            float[] morphPositions = null, morphNormals = null;
            if (targets > 0) {
                JsonArray defs = GltfDocument.array(prim, "targets");
                morphPositions = new float[targets * vertexCount * 3];
                for (int k = 0; k < targets && k < defs.size(); k++) {
                    JsonObject target = defs.get(k).getAsJsonObject();
                    if (target.has("POSITION")) {
                        float[] d = doc.readFloats(target.get("POSITION").getAsInt());
                        if (d.length == vertexCount * 3) System.arraycopy(d, 0, morphPositions, k * vertexCount * 3, d.length);
                        else LOGGER.warn("Model '{}': morph target {} of '{}' has the wrong vertex count", name, k, primName);
                    }
                    if (target.has("NORMAL") && a.normals != null) {
                        float[] d = doc.readFloats(target.get("NORMAL").getAsInt());
                        if (d.length == vertexCount * 3) {
                            if (morphNormals == null) morphNormals = new float[targets * vertexCount * 3];
                            System.arraycopy(d, 0, morphNormals, k * vertexCount * 3, d.length);
                        }
                    }
                }
            }

            int material = prim.has("material") ? prim.get("material").getAsInt() : -1;
            return compact(new MeshData(primName, material, vertexCount, positions, normals, uvs, colors, joints, weights, indices,
                    targets, targets > 0 ? skeleton.morphOffset(nodeIndex) : 0, morphPositions, morphNormals));
        }

        private Attributes readAttributes(JsonObject attrs, JsonObject prim) throws GltfException {
            Attributes a = new Attributes();
            int pos = attrs.get("POSITION").getAsInt();
            if (doc.accessorComponents(pos) != 3) throw new GltfException("POSITION must be VEC3");
            a.positions = doc.readFloats(pos);
            if (attrs.has("NORMAL")) a.normals = doc.readFloats(attrs.get("NORMAL").getAsInt());
            if (attrs.has("TEXCOORD_0")) a.uvs = doc.readFloats(attrs.get("TEXCOORD_0").getAsInt());
            if (attrs.has("COLOR_0")) {
                int acc = attrs.get("COLOR_0").getAsInt();
                a.colorComps = doc.accessorComponents(acc);
                a.colors = doc.readFloats(acc);
            }
            if (attrs.has("JOINTS_0")) a.joints = doc.readInts(attrs.get("JOINTS_0").getAsInt());
            if (attrs.has("WEIGHTS_0")) a.weights = doc.readFloats(attrs.get("WEIGHTS_0").getAsInt());
            if (prim.has("indices")) a.indices = doc.readInts(prim.get("indices").getAsInt());
            return a;
        }

        /** Sort one vertex's four influences by descending weight (insertion sort on 4 elements). */
        private static void sortInfluences(int[] joints, float[] weights, int o) {
            for (int i = 1; i < 4; i++) {
                float w = weights[o + i];
                int j = joints[o + i];
                int k = i - 1;
                while (k >= 0 && weights[o + k] < w) {
                    weights[o + k + 1] = weights[o + k];
                    joints[o + k + 1] = joints[o + k];
                    k--;
                }
                weights[o + k + 1] = w;
                joints[o + k + 1] = j;
            }
            for (int i = 0; i < 4; i++) if (weights[o + i] <= 0f) joints[o + i] = joints[o]; // unused slots: no stray indices
        }

        /**
         * Keep only the vertices the primitive's indices use, in first-use order. Primitives may share one vertex
         * accessor (one primitive per material over a common vertex list); without this every primitive would carry,
         * and be skinned with, all vertices of the shared list. First-use order also keeps vertices that are drawn
         * together close in memory.
         */
        private static MeshData compact(MeshData m) {
            int n = m.vertexCount();
            int[] remap = new int[n];
            Arrays.fill(remap, -1);
            int[] indices = m.indices().clone();
            int used = 0;
            for (int i = 0; i < indices.length; i++) {
                int v = indices[i];
                if (remap[v] < 0) remap[v] = used++;
                indices[i] = remap[v];
            }
            boolean identity = used == n;
            for (int v = 0; identity && v < n; v++) identity = remap[v] == v;
            if (identity) return m;
            float[] pos = new float[used * 3], nrm = new float[used * 3], uv = new float[used * 2], w = new float[used * 4];
            int[] j = new int[used * 4];
            float[] col = m.colors() != null ? new float[used * 4] : null;
            int targets = m.morphTargetCount();
            float[] mp = targets > 0 ? new float[targets * used * 3] : null;
            float[] mn = targets > 0 && m.morphNormals() != null ? new float[targets * used * 3] : null;
            for (int v = 0; v < n; v++) {
                int t = remap[v];
                if (t < 0) continue;
                System.arraycopy(m.positions(), v * 3, pos, t * 3, 3);
                System.arraycopy(m.normals(), v * 3, nrm, t * 3, 3);
                System.arraycopy(m.uvs(), v * 2, uv, t * 2, 2);
                System.arraycopy(m.weights(), v * 4, w, t * 4, 4);
                System.arraycopy(m.joints(), v * 4, j, t * 4, 4);
                if (col != null) System.arraycopy(m.colors(), v * 4, col, t * 4, 4);
                for (int k = 0; k < targets; k++) {
                    System.arraycopy(m.morphPositions(), (k * n + v) * 3, mp, (k * used + t) * 3, 3);
                    if (mn != null) System.arraycopy(m.morphNormals(), (k * n + v) * 3, mn, (k * used + t) * 3, 3);
                }
            }
            return new MeshData(m.name(), m.material(), used, pos, nrm, uv, col, j, w, indices, targets, m.morphWeightOffset(), mp, mn);
        }

        private static int[] triangleIndices(@Nullable int[] source, int mode, int vertexCount) throws GltfException {
            int[] raw = source;
            if (raw == null) {
                raw = new int[vertexCount];
                for (int i = 0; i < vertexCount; i++) raw[i] = i;
            }
            for (int idx : raw) {
                if (idx < 0 || idx >= vertexCount) throw new GltfException("Index " + idx + " out of range (" + vertexCount + " vertices)");
            }
            if (mode == MODE_TRIANGLES) {
                return raw.length % 3 == 0 ? raw : Arrays.copyOf(raw, raw.length - raw.length % 3);
            }
            int tris = Math.max(0, raw.length - 2);
            int[] out = new int[tris * 3];
            for (int i = 0; i < tris; i++) {
                if (mode == MODE_STRIP) {
                    boolean even = (i & 1) == 0;
                    out[i * 3] = raw[i];
                    out[i * 3 + 1] = even ? raw[i + 1] : raw[i + 2];
                    out[i * 3 + 2] = even ? raw[i + 2] : raw[i + 1];
                } else {
                    out[i * 3] = raw[0];
                    out[i * 3 + 1] = raw[i + 1];
                    out[i * 3 + 2] = raw[i + 2];
                }
            }
            return out;
        }

        private static float[] computeNormals(float[] pos, int[] indices) {
            float[] n = new float[pos.length];
            for (int i = 0; i + 2 < indices.length; i += 3) {
                int a = indices[i] * 3, b = indices[i + 1] * 3, c = indices[i + 2] * 3;
                float ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
                float vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                n[a] += nx;
                n[a + 1] += ny;
                n[a + 2] += nz;
                n[b] += nx;
                n[b + 1] += ny;
                n[b + 2] += nz;
                n[c] += nx;
                n[c + 1] += ny;
                n[c + 2] += nz;
            }
            for (int v = 0; v < n.length; v += 3) {
                float len = (float) Math.sqrt(n[v] * n[v] + n[v + 1] * n[v + 1] + n[v + 2] * n[v + 2]);
                if (len > 1e-12f) {
                    n[v] /= len;
                    n[v + 1] /= len;
                    n[v + 2] /= len;
                } else {
                    n[v + 1] = 1f;
                }
            }
            return n;
        }

        private int skinOffset(int skin) throws GltfException {
            int existing = skinOffsets.get(skin);
            if (existing >= 0) return existing;
            JsonObject skinDef = doc.element("skins", skin);
            JsonArray joints = GltfDocument.array(skinDef, "joints");
            float[] ibm = skinDef.has("inverseBindMatrices") ? doc.readFloats(skinDef.get("inverseBindMatrices").getAsInt()) : null;
            if (ibm != null && ibm.length != joints.size() * 16) throw new GltfException("Skin " + skin + " has a wrong number of inverse bind matrices");
            int offset = paletteNodes.size();
            for (int j = 0; j < joints.size(); j++) {
                int node = joints.get(j).getAsInt();
                if (node < 0 || node >= skeleton.size()) throw new GltfException("Skin " + skin + " references missing node " + node);
                float[] m = new float[16];
                if (ibm != null) System.arraycopy(ibm, j * 16, m, 0, 16);
                else Mat4.identity(m, 0);
                paletteNodes.add(node);
                paletteIbm.add(m);
            }
            skinOffsets.put(skin, offset);
            return offset;
        }

        private int rigidEntry(int node) {
            int existing = rigidEntries.get(node);
            if (existing >= 0) return existing;
            float[] m = new float[16];
            Mat4.identity(m, 0);
            paletteNodes.add(node);
            paletteIbm.add(m);
            int entry = paletteNodes.size() - 1;
            rigidEntries.put(node, entry);
            return entry;
        }

        /** Per palette entry, the box of the vertices it influences, widened by what morph targets can reach. */
        private static float[] jointBounds(List<MeshData> meshes, int paletteSize) {
            float[] b = new float[paletteSize * 6];
            for (int i = 0; i < paletteSize; i++) {
                b[i * 6] = b[i * 6 + 1] = b[i * 6 + 2] = Float.POSITIVE_INFINITY;
                b[i * 6 + 3] = b[i * 6 + 4] = b[i * 6 + 5] = Float.NEGATIVE_INFINITY;
            }
            for (MeshData mesh : meshes) {
                int n = mesh.vertexCount();
                float[] p = mesh.positions();
                float[] lo = p, hi = p;
                if (mesh.hasMorphs()) {
                    // weights in 0..1: each target can add its negative and its positive components
                    lo = p.clone();
                    hi = p.clone();
                    float[] mp = mesh.morphPositions();
                    for (int k = 0; k < mesh.morphTargetCount(); k++) {
                        for (int i = 0, o = k * n * 3; i < n * 3; i++) {
                            float d = mp[o + i];
                            if (d < 0f) lo[i] += d;
                            else hi[i] += d;
                        }
                    }
                }
                float[] w = mesh.weights();
                int[] j = mesh.joints();
                for (int v = 0; v < n; v++) {
                    for (int k = 0; k < 4; k++) {
                        if (w[v * 4 + k] <= 0f) continue;
                        int o = j[v * 4 + k] * 6;
                        for (int c = 0; c < 3; c++) {
                            b[o + c] = Math.min(b[o + c], lo[v * 3 + c]);
                            b[o + 3 + c] = Math.max(b[o + 3 + c], hi[v * 3 + c]);
                        }
                    }
                }
            }
            return b;
        }

        // ------------------------------------------------------------------ materials & images

        private List<MaterialData> buildMaterials() throws GltfException {
            JsonArray mats = doc.array("materials");
            List<MaterialData> out = new ArrayList<>(mats.size());
            for (int i = 0; i < mats.size(); i++) {
                JsonObject mat = mats.get(i).getAsJsonObject();
                String matName = GltfDocument.stringOr(mat, "name", "material_" + i);
                JsonObject pbr = mat.getAsJsonObject("pbrMetallicRoughness");
                float[] baseFactor = pbr != null ? GltfDocument.floats(pbr, "baseColorFactor", new float[]{1, 1, 1, 1}) : new float[]{1, 1, 1, 1};
                int baseTexture = pbr != null && pbr.has("baseColorTexture") ? pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt() : -1;
                int emissiveTexture = mat.has("emissiveTexture") ? mat.getAsJsonObject("emissiveTexture").get("index").getAsInt() : -1;
                float[] emissive = GltfDocument.floats(mat, "emissiveFactor", new float[]{0, 0, 0});
                JsonObject strength = extension(mat, "KHR_materials_emissive_strength");
                if (strength != null) {
                    float k = GltfDocument.floatOr(strength, "emissiveStrength", 1f);
                    for (int c = 0; c < emissive.length; c++) emissive[c] *= k;
                }
                boolean unlit = extension(mat, "KHR_materials_unlit") != null;
                MaterialData.AlphaMode alpha = switch (GltfDocument.stringOr(mat, "alphaMode", "OPAQUE")) {
                    case "MASK" -> MaterialData.AlphaMode.MASK;
                    case "BLEND" -> MaterialData.AlphaMode.BLEND;
                    default -> MaterialData.AlphaMode.OPAQUE;
                };
                if (baseFactor.length != 4) baseFactor = new float[]{1, 1, 1, 1};
                if (emissive.length != 3) emissive = new float[]{0, 0, 0};
                out.add(new MaterialData(matName, textureImage(baseTexture), baseFactor, textureImage(emissiveTexture), emissive,
                        alpha, GltfDocument.floatOr(mat, "alphaCutoff", 0.5f), GltfDocument.boolOr(mat, "doubleSided", false),
                        textureNearest(baseTexture), unlit));
            }
            return out;
        }

        /** The image of a texture; a {@code KHR_texture_basisu} (KTX2) source wins over the fallback {@code source}. */
        private int textureImage(int texture) throws GltfException {
            if (texture < 0) return -1;
            JsonObject tex = doc.element("textures", texture);
            JsonObject basisu = extension(tex, "KHR_texture_basisu");
            if (basisu != null && basisu.has("source")) return basisu.get("source").getAsInt();
            return tex.has("source") ? tex.get("source").getAsInt() : -1;
        }

        private boolean textureNearest(int texture) throws GltfException {
            if (texture < 0) return true;
            JsonObject tex = doc.element("textures", texture);
            if (!tex.has("sampler")) return true;
            JsonObject sampler = doc.element("samplers", tex.get("sampler").getAsInt());
            return GltfDocument.intOr(sampler, "magFilter", 0) != FILTER_LINEAR;
        }

        private List<ImageData> buildImages() throws GltfException {
            JsonArray imgs = doc.array("images");
            List<ImageData> out = new ArrayList<>(imgs.size());
            for (int i = 0; i < imgs.size(); i++) {
                JsonObject img = imgs.get(i).getAsJsonObject();
                String imgName = GltfDocument.stringOr(img, "name", "image_" + i);
                String mime = GltfDocument.stringOr(img, "mimeType", "image/png");
                byte[] bytes;
                if (img.has("bufferView")) {
                    bytes = doc.bufferViewBytes(img.get("bufferView").getAsInt());
                } else if (img.has("uri")) {
                    String uri = img.get("uri").getAsString();
                    bytes = GltfDocument.loadUri(uri, doc.resolver());
                    if (uri.endsWith(".jpg") || uri.endsWith(".jpeg") || uri.startsWith("data:image/jpeg")) mime = "image/jpeg";
                    else if (uri.endsWith(".ktx2") || uri.startsWith("data:image/ktx2")) mime = ImageData.KTX2;
                } else {
                    throw new GltfException("Image " + i + " has no data");
                }
                out.add(new ImageData(imgName, mime, bytes));
            }
            return out;
        }
    }

    /** {@code KHR_draco_mesh_compression}: decodes the compressed buffer view into plain attribute arrays. */
    private static final class DracoReader {
        private DracoReader() {
        }

        static Builder.Attributes read(GltfDocument doc, JsonObject ext, JsonObject attrs, String primName) throws GltfException {
            if (!ext.has("bufferView")) throw new GltfException("Draco primitive '" + primName + "' has no bufferView");
            byte[] bytes = doc.bufferViewBytes(ext.get("bufferView").getAsInt());
            com.openize.drako.DracoPointCloud cloud;
            try {
                cloud = com.openize.drako.Draco.decode(bytes);
            } catch (Exception | LinkageError e) {
                throw new GltfException("Could not decode Draco data of primitive '" + primName + "': " + e, e);
            }
            if (!(cloud instanceof com.openize.drako.DracoMesh mesh)) throw new GltfException("Draco data of '" + primName + "' is a point cloud, not a mesh");
            JsonObject ids = ext.getAsJsonObject("attributes");
            if (ids == null || !ids.has("POSITION")) throw new GltfException("Draco primitive '" + primName + "' has no POSITION attribute");
            int points = mesh.getNumPoints();
            Builder.Attributes a = new Builder.Attributes();
            a.positions = floats(mesh, ids, "POSITION", points, 3, primName);
            a.normals = has(ids, attrs, "NORMAL") ? floats(mesh, ids, "NORMAL", points, 3, primName) : null;
            a.uvs = has(ids, attrs, "TEXCOORD_0") ? floats(mesh, ids, "TEXCOORD_0", points, 2, primName) : null;
            if (has(ids, attrs, "COLOR_0")) {
                com.openize.drako.PointAttribute col = attribute(mesh, ids, "COLOR_0", primName);
                a.colorComps = col.getComponentsCount();
                a.colors = floats(mesh, ids, "COLOR_0", points, a.colorComps, primName);
            }
            if (has(ids, attrs, "WEIGHTS_0")) a.weights = floats(mesh, ids, "WEIGHTS_0", points, 4, primName);
            if (has(ids, attrs, "JOINTS_0")) {
                float[] j = floats(mesh, ids, "JOINTS_0", points, 4, primName);
                a.joints = new int[j.length];
                for (int i = 0; i < j.length; i++) a.joints[i] = Math.round(j[i]);
            }
            int faces = mesh.getNumFaces();
            int[] indices = new int[faces * 3];
            int[] face = new int[3];
            for (int f = 0; f < faces; f++) {
                mesh.readFace(f, face);
                System.arraycopy(face, 0, indices, f * 3, 3);
            }
            a.indices = indices;
            return a;
        }

        private static boolean has(JsonObject ids, JsonObject attrs, String semantic) {
            return ids.has(semantic) && attrs.has(semantic);
        }

        private static com.openize.drako.PointAttribute attribute(com.openize.drako.DracoMesh mesh, JsonObject ids, String semantic,
                                                                  String primName) throws GltfException {
            int id = ids.get(semantic).getAsInt();
            for (int i = 0; i < mesh.getNumAttributes(); i++) {
                com.openize.drako.PointAttribute attr = mesh.attribute(i);
                if (attr.getUniqueId() == id) return attr;
            }
            throw new GltfException("Draco primitive '" + primName + "' is missing attribute " + semantic + " (id " + id + ")");
        }

        /** Read an attribute as floats, normalising integer data the way glTF accessors would. */
        private static float[] floats(com.openize.drako.DracoMesh mesh, JsonObject ids, String semantic, int points, int comps,
                                      String primName) throws GltfException {
            com.openize.drako.PointAttribute attr = attribute(mesh, ids, semantic, primName);
            int n = Math.min(comps, attr.getComponentsCount());
            int type = attr.getDataType();
            boolean normalized = attr.getNormalized() || semantic.equals("COLOR_0") || semantic.equals("WEIGHTS_0");
            int size = switch (type) {
                case com.openize.drako.DataType.INT8, com.openize.drako.DataType.UINT8, com.openize.drako.DataType.BOOL -> 1;
                case com.openize.drako.DataType.INT16, com.openize.drako.DataType.UINT16 -> 2;
                case com.openize.drako.DataType.INT32, com.openize.drako.DataType.UINT32, com.openize.drako.DataType.FLOAT32 -> 4;
                default -> throw new GltfException("Draco attribute " + semantic + " of '" + primName + "' has unsupported data type " + type);
            };
            byte[] raw = new byte[Math.max(attr.getByteStride(), attr.getComponentsCount() * size)];
            float[] out = new float[points * comps];
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int p = 0; p < points; p++) {
                attr.getValue(attr.mappedIndex(p), raw);
                for (int c = 0; c < n; c++) {
                    int at = c * size;
                    float v = switch (type) {
                        case com.openize.drako.DataType.FLOAT32 -> bb.getFloat(at);
                        case com.openize.drako.DataType.INT8 -> normalized ? Math.max(bb.get(at) / 127f, -1f) : bb.get(at);
                        case com.openize.drako.DataType.UINT8, com.openize.drako.DataType.BOOL -> normalized ? (bb.get(at) & 0xFF) / 255f : bb.get(at) & 0xFF;
                        case com.openize.drako.DataType.INT16 -> normalized ? Math.max(bb.getShort(at) / 32767f, -1f) : bb.getShort(at);
                        case com.openize.drako.DataType.UINT16 -> normalized ? (bb.getShort(at) & 0xFFFF) / 65535f : bb.getShort(at) & 0xFFFF;
                        case com.openize.drako.DataType.INT32 -> bb.getInt(at);
                        default -> (float) (bb.getInt(at) & 0xFFFFFFFFL);
                    };
                    out[p * comps + c] = v;
                }
            }
            return out;
        }
    }
}
