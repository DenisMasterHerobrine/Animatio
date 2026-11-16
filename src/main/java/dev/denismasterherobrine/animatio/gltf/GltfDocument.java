package dev.denismasterherobrine.animatio.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;

/**
 * A parsed glTF 2.0 asset (binary {@code .glb} or JSON {@code .gltf}) with typed accessor reading.
 */
public final class GltfDocument {
    private static final int GLB_MAGIC = 0x46546C67;   // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;  // "JSON"
    private static final int CHUNK_BIN = 0x004E4942;   // "BIN\0"

    public static final int BYTE = 5120, UNSIGNED_BYTE = 5121, SHORT = 5122, UNSIGNED_SHORT = 5123,
            UNSIGNED_INT = 5125, FLOAT = 5126;

    private final JsonObject json;
    private final ByteBuffer[] buffers;
    private final @Nullable Function<String, byte[]> resolver;

    private GltfDocument(JsonObject json, ByteBuffer[] buffers, @Nullable Function<String, byte[]> resolver) {
        this.json = json;
        this.buffers = buffers;
        this.resolver = resolver;
    }

    /**
     * @param resolver loads external files referenced by relative URI ({@code .gltf} buffers and images);
     *                 may be {@code null} for self-contained files
     */
    public static GltfDocument read(byte[] data, @Nullable Function<String, byte[]> resolver) throws GltfException {
        try {
            JsonObject json;
            ByteBuffer bin = null;
            if (data.length >= 12 && le32(data, 0) == GLB_MAGIC) {
                int version = le32(data, 4);
                if (version != 2) throw new GltfException("Unsupported GLB version " + version + " (only glTF 2.0 is supported)");
                int length = le32(data, 8);
                if (length > data.length || length < 12) throw new GltfException("GLB length " + length + " does not match file size " + data.length);
                json = null;
                int offset = 12;
                while (offset + 8 <= length) {
                    int chunkLength = le32(data, offset);
                    int chunkType = le32(data, offset + 4);
                    int start = offset + 8;
                    if (chunkLength < 0 || start + chunkLength > length) throw new GltfException("GLB chunk exceeds file length");
                    if (chunkType == CHUNK_JSON && json == null) {
                        json = JsonParser.parseString(new String(data, start, chunkLength, StandardCharsets.UTF_8)).getAsJsonObject();
                    } else if (chunkType == CHUNK_BIN && bin == null) {
                        bin = ByteBuffer.wrap(data, start, chunkLength).slice().order(ByteOrder.LITTLE_ENDIAN);
                    }
                    offset = start + ((chunkLength + 3) & ~3);
                }
                if (json == null) throw new GltfException("GLB has no JSON chunk");
            } else {
                json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            }

            JsonObject asset = json.getAsJsonObject("asset");
            if (asset != null && asset.has("version") && !asset.get("version").getAsString().startsWith("2")) {
                throw new GltfException("Unsupported glTF version " + asset.get("version").getAsString());
            }

            JsonArray bufferDefs = array(json, "buffers");
            ByteBuffer[] buffers = new ByteBuffer[bufferDefs.size()];
            for (int i = 0; i < buffers.length; i++) {
                JsonObject def = bufferDefs.get(i).getAsJsonObject();
                byte[] bytes;
                if (!def.has("uri")) {
                    if (i != 0 || bin == null) throw new GltfException("Buffer " + i + " has no URI and there is no GLB binary chunk");
                    buffers[i] = bin;
                    continue;
                }
                bytes = loadUri(def.get("uri").getAsString(), resolver);
                buffers[i] = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            }
            return new GltfDocument(json, buffers, resolver);
        } catch (GltfException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GltfException("Malformed glTF: " + e.getMessage(), e);
        }
    }

    static byte[] loadUri(String uri, @Nullable Function<String, byte[]> resolver) throws GltfException {
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) throw new GltfException("Unsupported data URI");
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        }
        if (resolver == null) throw new GltfException("External file '" + uri + "' referenced but no resolver is available (use a .glb file)");
        byte[] bytes = resolver.apply(java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8));
        if (bytes == null) throw new GltfException("External file '" + uri + "' not found");
        return bytes;
    }

    private static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
    }

    // ------------------------------------------------------------------ JSON helpers

    public JsonObject json() {
        return json;
    }

    public @Nullable Function<String, byte[]> resolver() {
        return resolver;
    }

    public JsonArray array(String name) {
        return array(json, name);
    }

    static JsonArray array(JsonObject obj, String name) {
        JsonElement e = obj.get(name);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public JsonObject element(String arrayName, int index) throws GltfException {
        JsonArray arr = array(arrayName);
        if (index < 0 || index >= arr.size()) throw new GltfException("Reference to missing " + arrayName + "[" + index + "]");
        return arr.get(index).getAsJsonObject();
    }

    static int intOr(JsonObject obj, String name, int fallback) {
        JsonElement e = obj.get(name);
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
    }

    static float floatOr(JsonObject obj, String name, float fallback) {
        JsonElement e = obj.get(name);
        return e != null && e.isJsonPrimitive() ? e.getAsFloat() : fallback;
    }

    static String stringOr(JsonObject obj, String name, String fallback) {
        JsonElement e = obj.get(name);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    static boolean boolOr(JsonObject obj, String name, boolean fallback) {
        JsonElement e = obj.get(name);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
    }

    static float[] floats(JsonObject obj, String name, float[] fallback) {
        JsonElement e = obj.get(name);
        if (e == null || !e.isJsonArray()) return fallback.clone();
        JsonArray a = e.getAsJsonArray();
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    // ------------------------------------------------------------------ accessors

    public static int components(String type) throws GltfException {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4", "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new GltfException("Unknown accessor type " + type);
        };
    }

    private static int componentSize(int componentType) throws GltfException {
        return switch (componentType) {
            case BYTE, UNSIGNED_BYTE -> 1;
            case SHORT, UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT, FLOAT -> 4;
            default -> throw new GltfException("Unknown component type " + componentType);
        };
    }

    /** Number of components per element of an accessor. */
    public int accessorComponents(int accessor) throws GltfException {
        return components(element("accessors", accessor).get("type").getAsString());
    }

    public int accessorCount(int accessor) throws GltfException {
        return element("accessors", accessor).get("count").getAsInt();
    }

    /** Read an accessor as floats, applying normalisation for normalised integer types. */
    public float[] readFloats(int accessorIndex) throws GltfException {
        JsonObject acc = element("accessors", accessorIndex);
        int count = acc.get("count").getAsInt();
        int comps = components(acc.get("type").getAsString());
        int ct = acc.get("componentType").getAsInt();
        boolean normalized = boolOr(acc, "normalized", false);
        if (count < 0 || (long) count * comps > Integer.MAX_VALUE / 2) throw new GltfException("Accessor " + accessorIndex + " has an invalid count");
        float[] out = new float[count * comps];
        if (acc.has("bufferView")) {
            readViewFloats(acc.get("bufferView").getAsInt(), intOr(acc, "byteOffset", 0), count, comps, ct, normalized, out, 0);
        }
        JsonObject sparse = acc.getAsJsonObject("sparse");
        if (sparse != null) {
            int sparseCount = sparse.get("count").getAsInt();
            JsonObject indices = sparse.getAsJsonObject("indices");
            JsonObject values = sparse.getAsJsonObject("values");
            int[] idx = new int[sparseCount];
            readViewInts(indices.get("bufferView").getAsInt(), intOr(indices, "byteOffset", 0), sparseCount, 1,
                    indices.get("componentType").getAsInt(), idx);
            float[] vals = new float[sparseCount * comps];
            readViewFloats(values.get("bufferView").getAsInt(), intOr(values, "byteOffset", 0), sparseCount, comps, ct, normalized, vals, 0);
            for (int i = 0; i < sparseCount; i++) {
                int target = idx[i];
                if (target < 0 || target >= count) throw new GltfException("Sparse index out of range in accessor " + accessorIndex);
                System.arraycopy(vals, i * comps, out, target * comps, comps);
            }
        }
        return out;
    }

    /** Read an integer accessor (indices, joints). */
    public int[] readInts(int accessorIndex) throws GltfException {
        JsonObject acc = element("accessors", accessorIndex);
        int count = acc.get("count").getAsInt();
        int comps = components(acc.get("type").getAsString());
        int ct = acc.get("componentType").getAsInt();
        if (count < 0 || (long) count * comps > Integer.MAX_VALUE / 2) throw new GltfException("Accessor " + accessorIndex + " has an invalid count");
        int[] out = new int[count * comps];
        if (acc.has("bufferView")) {
            readViewInts(acc.get("bufferView").getAsInt(), intOr(acc, "byteOffset", 0), count, comps, ct, out);
        }
        JsonObject sparse = acc.getAsJsonObject("sparse");
        if (sparse != null) {
            int sparseCount = sparse.get("count").getAsInt();
            JsonObject indices = sparse.getAsJsonObject("indices");
            JsonObject values = sparse.getAsJsonObject("values");
            int[] idx = new int[sparseCount];
            readViewInts(indices.get("bufferView").getAsInt(), intOr(indices, "byteOffset", 0), sparseCount, 1,
                    indices.get("componentType").getAsInt(), idx);
            int[] vals = new int[sparseCount * comps];
            readViewInts(values.get("bufferView").getAsInt(), intOr(values, "byteOffset", 0), sparseCount, comps, ct, vals);
            for (int i = 0; i < sparseCount; i++) {
                int target = idx[i];
                if (target < 0 || target >= count) throw new GltfException("Sparse index out of range in accessor " + accessorIndex);
                System.arraycopy(vals, i * comps, out, target * comps, comps);
            }
        }
        return out;
    }

    /** Validated location of an accessor's elements: buffer, first byte and stride. */
    private record View(ByteBuffer buf, int base, int stride, int size) {
    }

    private View view(int viewIndex, int accessorOffset, int count, int comps, int ct) throws GltfException {
        JsonObject view = element("bufferViews", viewIndex);
        int bufferIndex = view.get("buffer").getAsInt();
        if (bufferIndex < 0 || bufferIndex >= buffers.length) throw new GltfException("Buffer view references missing buffer " + bufferIndex);
        ByteBuffer buf = buffers[bufferIndex];
        int viewOffset = intOr(view, "byteOffset", 0);
        int viewLength = view.get("byteLength").getAsInt();
        int size = componentSize(ct);
        int elementSize = size * comps;
        int stride = intOr(view, "byteStride", 0);
        if (stride == 0) stride = elementSize;
        if (viewOffset < 0 || viewLength < 0 || accessorOffset < 0 || stride < elementSize) throw new GltfException("Malformed buffer view " + viewIndex);
        int base = viewOffset + accessorOffset;
        if (count > 0) {
            long end = (long) base + (long) (count - 1) * stride + elementSize;
            if (end > (long) viewOffset + viewLength || (long) viewOffset + viewLength > buf.limit()) {
                throw new GltfException("Accessor data exceeds its buffer view");
            }
        }
        return new View(buf, base, stride, size);
    }

    private void readViewFloats(int viewIndex, int accessorOffset, int count, int comps, int ct, boolean normalized,
                                float[] out, int outOffset) throws GltfException {
        if (count == 0) return;
        View v = view(viewIndex, accessorOffset, count, comps, ct);
        ByteBuffer buf = v.buf;
        int stride = v.stride, size = v.size;
        if (ct == FLOAT && stride == comps * 4) {
            // tightly packed floats: one bulk copy
            buf.slice(v.base, count * stride).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out, outOffset, count * comps);
            return;
        }
        int o = outOffset;
        for (int i = 0, elem = v.base; i < count; i++, elem += stride) {
            for (int c = 0, at = elem; c < comps; c++, at += size) {
                out[o++] = switch (ct) {
                    case FLOAT -> buf.getFloat(at);
                    case UNSIGNED_BYTE -> normalized ? (buf.get(at) & 0xFF) / 255f : buf.get(at) & 0xFF;
                    case BYTE -> normalized ? Math.max(buf.get(at) / 127f, -1f) : buf.get(at);
                    case UNSIGNED_SHORT -> normalized ? (buf.getShort(at) & 0xFFFF) / 65535f : buf.getShort(at) & 0xFFFF;
                    case SHORT -> normalized ? Math.max(buf.getShort(at) / 32767f, -1f) : buf.getShort(at);
                    case UNSIGNED_INT -> (float) (buf.getInt(at) & 0xFFFFFFFFL);
                    default -> 0f;
                };
            }
        }
    }

    private void readViewInts(int viewIndex, int accessorOffset, int count, int comps, int ct, int[] out) throws GltfException {
        if (count == 0) return;
        View v = view(viewIndex, accessorOffset, count, comps, ct);
        ByteBuffer buf = v.buf;
        int stride = v.stride, size = v.size;
        if ((ct == UNSIGNED_INT) && stride == comps * 4) {
            buf.slice(v.base, count * stride).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out, 0, count * comps);
            for (int i = 0; i < out.length; i++) if (out[i] < 0) out[i] = Integer.MAX_VALUE; // > 2^31: out of range anyway
            return;
        }
        int o = 0;
        for (int i = 0, elem = v.base; i < count; i++, elem += stride) {
            for (int c = 0, at = elem; c < comps; c++, at += size) {
                out[o++] = switch (ct) {
                    case UNSIGNED_BYTE -> buf.get(at) & 0xFF;
                    case BYTE -> buf.get(at);
                    case UNSIGNED_SHORT -> buf.getShort(at) & 0xFFFF;
                    case SHORT -> buf.getShort(at);
                    case UNSIGNED_INT -> {
                        long u = buf.getInt(at) & 0xFFFFFFFFL;
                        yield u > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) u;
                    }
                    case FLOAT -> (int) buf.getFloat(at);
                    default -> 0;
                };
            }
        }
    }

    /** Raw bytes of a buffer view (embedded images). */
    public byte[] bufferViewBytes(int viewIndex) throws GltfException {
        JsonObject view = element("bufferViews", viewIndex);
        ByteBuffer buf = buffers[view.get("buffer").getAsInt()];
        int offset = intOr(view, "byteOffset", 0);
        int length = view.get("byteLength").getAsInt();
        if (offset + length > buf.limit()) throw new GltfException("Buffer view exceeds its buffer");
        byte[] out = new byte[length];
        buf.get(offset, out);
        return out;
    }
}
