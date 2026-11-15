package dev.denismasterherobrine.animatio.testutil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Writes small GLB files for tests: accessors are appended to one binary buffer.
 */
public final class GlbBuilder {
    private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
    private final JsonArray bufferViews = new JsonArray();
    private final JsonArray accessors = new JsonArray();

    private int addView(byte[] data) {
        align();
        JsonObject view = new JsonObject();
        view.addProperty("buffer", 0);
        view.addProperty("byteOffset", bin.size());
        view.addProperty("byteLength", data.length);
        bin.writeBytes(data);
        bufferViews.add(view);
        return bufferViews.size() - 1;
    }

    private void align() {
        while (bin.size() % 4 != 0) bin.write(0);
    }

    private int addAccessor(int view, int componentType, int count, String type, boolean normalized) {
        JsonObject acc = new JsonObject();
        acc.addProperty("bufferView", view);
        acc.addProperty("componentType", componentType);
        acc.addProperty("count", count);
        acc.addProperty("type", type);
        if (normalized) acc.addProperty("normalized", true);
        accessors.add(acc);
        return accessors.size() - 1;
    }

    private static int components(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw new IllegalArgumentException(type);
        };
    }

    public int floats(String type, float... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) b.putFloat(v);
        int view = addView(b.array());
        return addAccessor(view, 5126, values.length / components(type), type, false);
    }

    public int ushorts(String type, int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) b.putShort((short) v);
        int view = addView(b.array());
        return addAccessor(view, 5123, values.length / components(type), type, false);
    }

    public int ubytes(String type, boolean normalized, int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; i++) data[i] = (byte) values[i];
        int view = addView(data);
        return addAccessor(view, 5121, values.length / components(type), type, normalized);
    }

    /** A float VEC3 accessor whose data is interleaved with padding (byteStride 16). */
    public int stridedVec3(float... values) {
        int count = values.length / 3;
        ByteBuffer b = ByteBuffer.allocate(count * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            b.putFloat(values[i * 3]).putFloat(values[i * 3 + 1]).putFloat(values[i * 3 + 2]).putFloat(-999f);
        }
        int view = addView(b.array());
        bufferViews.get(view).getAsJsonObject().addProperty("byteStride", 16);
        return addAccessor(view, 5126, count, "VEC3", false);
    }

    /** Raw bytes as a buffer view (for images). */
    public int bytesView(byte[] data) {
        return addView(data);
    }

    /** Make an accessor sparse: zero base, with the given (index, values) overrides. */
    public int sparseVec3(int count, int[] indices, float[] values) {
        ByteBuffer ib = ByteBuffer.allocate(indices.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : indices) ib.putShort((short) i);
        int iv = addView(ib.array());
        ByteBuffer vb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) vb.putFloat(v);
        int vv = addView(vb.array());
        JsonObject acc = new JsonObject();
        acc.addProperty("componentType", 5126);
        acc.addProperty("count", count);
        acc.addProperty("type", "VEC3");
        JsonObject sparse = new JsonObject();
        sparse.addProperty("count", indices.length);
        JsonObject si = new JsonObject();
        si.addProperty("bufferView", iv);
        si.addProperty("componentType", 5123);
        JsonObject sv = new JsonObject();
        sv.addProperty("bufferView", vv);
        sparse.add("indices", si);
        sparse.add("values", sv);
        acc.add("sparse", sparse);
        accessors.add(acc);
        return accessors.size() - 1;
    }

    /** Write the GLB. {@code json} must not contain buffers, bufferViews or accessors. */
    public byte[] build(JsonObject json) {
        align();
        byte[] binData = bin.toByteArray();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        json.add("asset", asset);
        JsonArray buffers = new JsonArray();
        JsonObject buffer = new JsonObject();
        buffer.addProperty("byteLength", binData.length);
        buffers.add(buffer);
        json.add("buffers", buffers);
        json.add("bufferViews", bufferViews);
        json.add("accessors", accessors);

        byte[] jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        int jsonPadded = (jsonBytes.length + 3) & ~3;
        int total = 12 + 8 + jsonPadded + 8 + binData.length;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(total);
        out.putInt(jsonPadded).putInt(0x4E4F534A).put(jsonBytes);
        for (int i = jsonBytes.length; i < jsonPadded; i++) out.put((byte) ' ');
        out.putInt(binData.length).putInt(0x004E4942).put(binData);
        return out.array();
    }
}
