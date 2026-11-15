package dev.denismasterherobrine.animatio.client.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * Static GPU geometry of a model: one VAO with an interleaved vertex buffer and an index buffer holding every part.
 * <p>
 * Vertex layout (40 bytes): position 3×float, uv 2×float, normal 3×byte (+1 pad), joints 4×ushort,
 * weights 4×ubyte (normalised), colour 4×ubyte (normalised).
 */
public final class GpuModelData implements AutoCloseable {
    private static final int STRIDE = 40;

    final int vao;
    private final int vbo;
    private final int ebo;
    /** Per part: first index (in indices) and index count. */
    final int[] firstIndex;
    final int[] indexCount;
    private boolean closed;

    private GpuModelData(int vao, int vbo, int ebo, int[] firstIndex, int[] indexCount) {
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
        this.firstIndex = firstIndex;
        this.indexCount = indexCount;
    }

    /** The model's GPU data, created on first use (render thread). */
    static @Nullable GpuModelData get(GltfModel model) {
        if (model.gpuData() instanceof GpuModelData data) return data.closed ? null : data;
        GpuModelData data = create(model.parts());
        model.setGpuData(data);
        return data;
    }

    /** Free a model's GPU buffers (render thread). */
    public static void release(GltfModel model) {
        if (model.gpuData() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
        model.setGpuData(null);
    }

    private static GpuModelData create(List<GltfModel.Part> parts) {
        RenderSystem.assertOnRenderThread();
        int vertices = 0, indices = 0;
        for (GltfModel.Part p : parts) {
            vertices += p.mesh().vertexCount();
            indices += p.mesh().indices().length;
        }
        ByteBuffer vb = MemoryUtil.memAlloc(Math.max(1, vertices) * STRIDE);
        IntBuffer ib = MemoryUtil.memAllocInt(Math.max(1, indices));
        int[] first = new int[parts.size()];
        int[] count = new int[parts.size()];
        try {
            int baseVertex = 0, index = 0;
            for (int pi = 0; pi < parts.size(); pi++) {
                MeshData m = parts.get(pi).mesh();
                float[] pos = m.positions(), nrm = m.normals(), uv = m.uvs(), w = m.weights(), col = m.colors();
                int[] j = m.joints();
                for (int v = 0; v < m.vertexCount(); v++) {
                    vb.putFloat(pos[v * 3]).putFloat(pos[v * 3 + 1]).putFloat(pos[v * 3 + 2]);
                    vb.putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]);
                    vb.put(normalByte(nrm[v * 3])).put(normalByte(nrm[v * 3 + 1])).put(normalByte(nrm[v * 3 + 2])).put((byte) 0);
                    for (int k = 0; k < 4; k++) vb.putShort((short) j[v * 4 + k]);
                    for (int k = 0; k < 4; k++) vb.put(unorm(w[v * 4 + k]));
                    if (col != null) {
                        for (int k = 0; k < 4; k++) vb.put(unorm(col[v * 4 + k]));
                    } else {
                        vb.putInt(-1);
                    }
                }
                first[pi] = index;
                count[pi] = m.indices().length;
                for (int idx : m.indices()) ib.put(baseVertex + idx);
                index += m.indices().length;
                baseVertex += m.vertexCount();
            }
            vb.flip();
            ib.flip();

            int vao = GL30.glGenVertexArrays();
            int vbo = GL15.glGenBuffers();
            int ebo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
            enable(GpuShaders.ATTR_POSITION);
            GL20.glVertexAttribPointer(GpuShaders.ATTR_POSITION, 3, GL11.GL_FLOAT, false, STRIDE, 0);
            enable(GpuShaders.ATTR_UV0);
            GL20.glVertexAttribPointer(GpuShaders.ATTR_UV0, 2, GL11.GL_FLOAT, false, STRIDE, 12);
            enable(GpuShaders.ATTR_NORMAL);
            GL20.glVertexAttribPointer(GpuShaders.ATTR_NORMAL, 3, GL11.GL_BYTE, true, STRIDE, 20);
            enable(GpuShaders.ATTR_JOINTS);
            GL30.glVertexAttribIPointer(GpuShaders.ATTR_JOINTS, 4, GL11.GL_UNSIGNED_SHORT, STRIDE, 24);
            enable(GpuShaders.ATTR_WEIGHTS);
            GL20.glVertexAttribPointer(GpuShaders.ATTR_WEIGHTS, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 32);
            enable(GpuShaders.ATTR_COLOR);
            GL20.glVertexAttribPointer(GpuShaders.ATTR_COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 36);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            BufferUploader.invalidate();
            return new GpuModelData(vao, vbo, ebo, first, count);
        } finally {
            MemoryUtil.memFree(vb);
            MemoryUtil.memFree(ib);
        }
    }

    private static void enable(int attribute) {
        GL20.glEnableVertexAttribArray(attribute);
    }

    private static byte normalByte(float v) {
        return (byte) Math.round(Math.max(-1f, Math.min(1f, v)) * 127f);
    }

    private static byte unorm(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RenderSystem.recordRenderCall(() -> {
            GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(vbo);
            GL15.glDeleteBuffers(ebo);
        });
    }
}
