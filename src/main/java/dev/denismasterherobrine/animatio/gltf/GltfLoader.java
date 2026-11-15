package dev.denismasterherobrine.animatio.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.denismasterherobrine.animatio.anim.model.Channel;
import dev.denismasterherobrine.animatio.anim.model.Clip;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
 * TEXCOORD_0, COLOR_0, JOINTS_0 and WEIGHTS_0, PBR base colour and emissive textures, alpha modes, and
 * translation/rotation/scale animation channels with STEP, LINEAR and CUBICSPLINE interpolation.
 * Not supported: morph targets, cameras, lights, compressed geometry or textures.
 */
public final class GltfLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final int MODE_TRIANGLES = 4, MODE_STRIP = 5, MODE_FAN = 6;
    private static final int FILTER_LINEAR = 9729;

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

    private static final class Builder {
        private final String name;
        private final GltfDocument doc;
        private final boolean geometry;
        private Skeleton skeleton;
        private final List<Integer> paletteNodes = new ArrayList<>();
        private final List<float[]> paletteIbm = new ArrayList<>();
        private final Map<Integer, Integer> skinOffsets = new HashMap<>();
        private final Map<Integer, Integer> rigidEntries = new HashMap<>();

        Builder(String name, GltfDocument doc, boolean geometry) {
            this.name = name;
            this.doc = doc;
            this.geometry = geometry;
        }

        ModelData build() throws GltfException {
            JsonArray nodes = doc.array("nodes");
            skeleton = buildSkeleton(nodes);
            List<Clip> clips = buildClips(nodes.size());

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
            int[] pn = new int[paletteSize];
            float[] ibm = new float[paletteSize * 16];
            for (int i = 0; i < paletteSize; i++) {
                pn[i] = paletteNodes.get(i);
                System.arraycopy(paletteIbm.get(i), 0, ibm, i * 16, 16);
            }
            float[] bounds = jointBounds(meshes, paletteSize);
            return new ModelData(name, skeleton, List.copyOf(clips), List.copyOf(meshes), List.copyOf(materials),
                    List.copyOf(images), pn, ibm, bounds);
        }

        // ------------------------------------------------------------------ skeleton

        private Skeleton buildSkeleton(JsonArray nodes) throws GltfException {
            int n = nodes.size();
            String[] names = new String[n];
            int[] parents = new int[n];
            Arrays.fill(parents, -1);
            float[] t = new float[n * 3], r = new float[n * 4], s = new float[n * 3];
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
            }
            try {
                return new Skeleton(names, parents, t, r, s);
            } catch (IllegalArgumentException e) {
                throw new GltfException(e.getMessage());
            }
        }

        // ------------------------------------------------------------------ animations

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
                        default -> -1; // "weights" (morph targets) and extensions are not supported
                    };
                    if (path < 0) continue;
                    int samplerIndex = ch.get("sampler").getAsInt();
                    if (samplerIndex < 0 || samplerIndex >= samplers.size()) throw new GltfException("Animation '" + clipName + "' references missing sampler");
                    JsonObject sampler = samplers.get(samplerIndex).getAsJsonObject();
                    int interpolation = switch (GltfDocument.stringOr(sampler, "interpolation", "LINEAR")) {
                        case "STEP" -> Channel.STEP;
                        case "CUBICSPLINE" -> Channel.CUBIC;
                        default -> Channel.LINEAR;
                    };
                    float[] times = doc.readFloats(sampler.get("input").getAsInt());
                    float[] values = doc.readFloats(sampler.get("output").getAsInt());
                    if (times.length == 0) continue;
                    int comps = Channel.components(path);
                    int expected = times.length * comps * (interpolation == Channel.CUBIC ? 3 : 1);
                    if (values.length != expected) {
                        throw new GltfException("Animation '" + clipName + "' sampler has " + values.length + " values, expected " + expected);
                    }
                    try {
                        channels.put(((long) node << 2) | path, new Channel(node, path, interpolation, times, values));
                    } catch (IllegalArgumentException e) {
                        throw new GltfException("Animation '" + clipName + "': " + e.getMessage());
                    }
                }
                clips.add(new Clip(clipName, channels.values().toArray(new Channel[0]), nodeCount, rawEvents(anim, clipName)));
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
            float[] positions = doc.readFloats(attrs.get("POSITION").getAsInt());
            int vertexCount = positions.length / 3;
            if (doc.accessorComponents(attrs.get("POSITION").getAsInt()) != 3) throw new GltfException("POSITION must be VEC3");

            int[] indices = triangleIndices(prim, mode, vertexCount);

            float[] normals = attrs.has("NORMAL") ? doc.readFloats(attrs.get("NORMAL").getAsInt()) : null;
            if (normals == null || normals.length != vertexCount * 3) normals = computeNormals(positions, indices);

            float[] uvs = attrs.has("TEXCOORD_0") ? doc.readFloats(attrs.get("TEXCOORD_0").getAsInt()) : null;
            if (uvs == null || uvs.length != vertexCount * 2) uvs = new float[vertexCount * 2];

            float[] colors = null;
            if (attrs.has("COLOR_0")) {
                int acc = attrs.get("COLOR_0").getAsInt();
                int comps = doc.accessorComponents(acc);
                float[] raw = doc.readFloats(acc);
                if (raw.length == vertexCount * comps && (comps == 3 || comps == 4)) {
                    colors = new float[vertexCount * 4];
                    for (int v = 0; v < vertexCount; v++) {
                        colors[v * 4] = raw[v * comps];
                        colors[v * 4 + 1] = raw[v * comps + 1];
                        colors[v * 4 + 2] = raw[v * comps + 2];
                        colors[v * 4 + 3] = comps == 4 ? raw[v * comps + 3] : 1f;
                    }
                }
            }

            int[] joints = new int[vertexCount * 4];
            float[] weights = new float[vertexCount * 4];
            boolean skinned = skin >= 0 && attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0");
            if (skinned) {
                int offset = skinOffset(skin);
                int jointCount = GltfDocument.array(doc.element("skins", skin), "joints").size();
                int[] rawJoints = doc.readInts(attrs.get("JOINTS_0").getAsInt());
                float[] rawWeights = doc.readFloats(attrs.get("WEIGHTS_0").getAsInt());
                if (rawJoints.length != vertexCount * 4 || rawWeights.length != vertexCount * 4) {
                    throw new GltfException("Primitive '" + primName + "' JOINTS_0/WEIGHTS_0 must be VEC4 per vertex");
                }
                for (int v = 0; v < vertexCount; v++) {
                    float sum = 0f;
                    for (int k = 0; k < 4; k++) {
                        int j = rawJoints[v * 4 + k];
                        float w = rawWeights[v * 4 + k];
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
                    } else {
                        weights[v * 4] = 1f;
                        joints[v * 4] = offset;
                    }
                }
            } else {
                if (skin >= 0) LOGGER.warn("Model '{}': primitive '{}' is on a skinned node but has no joints; drawing it rigidly", name, primName);
                int entry = rigidEntry(nodeIndex);
                for (int v = 0; v < vertexCount; v++) {
                    joints[v * 4] = entry;
                    joints[v * 4 + 1] = entry;
                    joints[v * 4 + 2] = entry;
                    joints[v * 4 + 3] = entry;
                    weights[v * 4] = 1f;
                }
            }

            int material = prim.has("material") ? prim.get("material").getAsInt() : -1;
            return compact(new MeshData(primName, material, vertexCount, positions, normals, uvs, colors, joints, weights, indices));
        }

        /**
         * Keep only the vertices the primitive's indices use, in first-use order. Primitives may share one vertex
         * accessor (one primitive per material over a common vertex list); without this every primitive would carry,
         * and be skinned with, all vertices of the shared list.
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
            for (int v = 0; v < n; v++) {
                int t = remap[v];
                if (t < 0) continue;
                System.arraycopy(m.positions(), v * 3, pos, t * 3, 3);
                System.arraycopy(m.normals(), v * 3, nrm, t * 3, 3);
                System.arraycopy(m.uvs(), v * 2, uv, t * 2, 2);
                System.arraycopy(m.weights(), v * 4, w, t * 4, 4);
                System.arraycopy(m.joints(), v * 4, j, t * 4, 4);
                if (col != null) System.arraycopy(m.colors(), v * 4, col, t * 4, 4);
            }
            return new MeshData(m.name(), m.material(), used, pos, nrm, uv, col, j, w, indices);
        }

        private int[] triangleIndices(JsonObject prim, int mode, int vertexCount) throws GltfException {
            int[] raw;
            if (prim.has("indices")) {
                raw = doc.readInts(prim.get("indices").getAsInt());
            } else {
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
                for (int k : new int[]{a, b, c}) {
                    n[k] += nx;
                    n[k + 1] += ny;
                    n[k + 2] += nz;
                }
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
            Integer existing = skinOffsets.get(skin);
            if (existing != null) return existing;
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
            return rigidEntries.computeIfAbsent(node, n -> {
                float[] m = new float[16];
                Mat4.identity(m, 0);
                paletteNodes.add(n);
                paletteIbm.add(m);
                return paletteNodes.size() - 1;
            });
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
                    for (int k = 0; k < 4; k++) {
                        if (mesh.weights()[v * 4 + k] <= 0f) continue;
                        int o = mesh.joints()[v * 4 + k] * 6;
                        for (int c = 0; c < 3; c++) {
                            b[o + c] = Math.min(b[o + c], p[v * 3 + c]);
                            b[o + 3 + c] = Math.max(b[o + 3 + c], p[v * 3 + c]);
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
                JsonObject ext = mat.getAsJsonObject("extensions");
                boolean unlit = false;
                if (ext != null) {
                    JsonObject strength = ext.getAsJsonObject("KHR_materials_emissive_strength");
                    if (strength != null) {
                        float k = GltfDocument.floatOr(strength, "emissiveStrength", 1f);
                        for (int c = 0; c < emissive.length; c++) emissive[c] *= k;
                    }
                    unlit = ext.has("KHR_materials_unlit");
                }
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

        private int textureImage(int texture) throws GltfException {
            if (texture < 0) return -1;
            JsonObject tex = doc.element("textures", texture);
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
                } else {
                    throw new GltfException("Image " + i + " has no data");
                }
                out.add(new ImageData(imgName, mime, bytes));
            }
            return out;
        }
    }
}
