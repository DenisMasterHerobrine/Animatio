package dev.denismasterherobrine.animatio.client.render.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * Static GPU geometry of a model: one VAO with an interleaved vertex buffer and an index buffer holding every part,
 * plus (for models with morph targets) a texture buffer of morph deltas.
 * <p>
 * Vertex layout (40 bytes): position 3×float, uv 2×float, normal 3×byte (+1 pad), joints 4×ushort,
 * weights 4×ubyte (normalised), colour 4×ubyte (normalised).
 * <p>
 * Morph deltas: for a part with morph targets, texel {@code morphBase + (target * vertices + vertex) * 2} holds the
 * position delta and the next texel the normal delta (RGBA32F, w unused).
 */
public final class GpuModelData implements AutoCloseable {
    private static final int STRIDE = 40;

    final int vao;
    private final int vbo;
    final int ebo;
    /** VAO over the transform-feedback output with this model's index buffer ({@link FeedbackSkinner}), 0 until used. */
    int feedbackVao;
    /** Per part: first index (in indices) and index count. */
    final int[] firstIndex;
    final int[] indexCount;
    /** Per part: first vertex in the vertex buffer (for {@code gl_VertexID - firstVertex}). */
    final int[] firstVertex;
    /** Per part: first morph texel, or -1 without morph targets. */
    final int[] morphBase;
    final int[] morphTargets;
    final int[] morphVertices;
    final int[] morphWeightOffset;
    /** Texture buffer with the morph deltas (0 if the model has none) and its storage. */
    final int morphTexture;
    private final int morphBuffer;
    private boolean closed;

    private GpuModelData(int vao, int vbo, int ebo, int[] firstIndex, int[] indexCount, int[] firstVertex, int[] morphBase,
                         int[] morphTargets, int[] morphVertices, int[] morphWeightOffset, int morphTexture, int morphBuffer) {
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
        this.firstIndex = firstIndex;
        this.indexCount = indexCount;
        this.firstVertex = firstVertex;
        this.morphBase = morphBase;
        this.morphTargets = morphTargets;
        this.morphVertices = morphVertices;
        this.morphWeightOffset = morphWeightOffset;
        this.morphTexture = morphTexture;
        this.morphBuffer = morphBuffer;
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
        long morphTexels = 0;
        for (GltfModel.Part p : parts) {
            MeshData m = p.mesh();
            vertices += m.vertexCount();
            indices += m.indices().length;
            morphTexels += (long) m.morphTargetCount() * m.vertexCount() * 2;
        }
        ByteBuffer vb = MemoryUtil.memAlloc(Math.max(1, vertices) * STRIDE);
        IntBuffer ib = MemoryUtil.memAllocInt(Math.max(1, indices));
        FloatBuffer mb = morphTexels > 0 ? MemoryUtil.memAllocFloat((int) Math.min(Integer.MAX_VALUE / 4, morphTexels * 4)) : null;
        int n = parts.size();
        int[] first = new int[n], count = new int[n], firstVertex = new int[n];
        int[] morphBase = new int[n], morphTargets = new int[n], morphVertices = new int[n], morphWeightOffset = new int[n];
        try {
            int baseVertex = 0, index = 0, texel = 0;
            for (int pi = 0; pi < n; pi++) {
                MeshData m = parts.get(pi).mesh();
                float[] pos = m.positions(), nrm = m.normals(), uv = m.uvs(), w = m.weights();
                int[] j = m.joints();
                int[] colors = m.packedColors();
                for (int v = 0; v < m.vertexCount(); v++) {
                    vb.putFloat(pos[v * 3]).putFloat(pos[v * 3 + 1]).putFloat(pos[v * 3 + 2]);
                    vb.putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]);
                    vb.put(normalByte(nrm[v * 3])).put(normalByte(nrm[v * 3 + 1])).put(normalByte(nrm[v * 3 + 2])).put((byte) 0);
                    for (int k = 0; k < 4; k++) vb.putShort((short) j[v * 4 + k]);
                    for (int k = 0; k < 4; k++) vb.put(unorm(w[v * 4 + k]));
                    if (colors != null) {
                        int c = colors[v]; // ARGB -> bytes R, G, B, A
                        vb.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
                    } else {
                        vb.putInt(-1);
                    }
                }
                first[pi] = index;
                count[pi] = m.indices().length;
                firstVertex[pi] = baseVertex;
                for (int idx : m.indices()) ib.put(baseVertex + idx);
                index += m.indices().length;
                baseVertex += m.vertexCount();

                int targets = m.morphTargetCount();
                if (targets > 0 && mb != null) {
                    morphBase[pi] = texel;
                    morphTargets[pi] = targets;
                    morphVertices[pi] = m.vertexCount();
                    morphWeightOffset[pi] = m.morphWeightOffset();
                    float[] dp = m.morphPositions(), dn = m.morphNormals();
                    int total = targets * m.vertexCount();
                    for (int e = 0; e < total; e++) {
                        mb.put(dp[e * 3]).put(dp[e * 3 + 1]).put(dp[e * 3 + 2]).put(0f);
                        if (dn != null) mb.put(dn[e * 3]).put(dn[e * 3 + 1]).put(dn[e * 3 + 2]).put(0f);
                        else mb.put(0f).put(0f).put(0f).put(0f);
                    }
                    texel += total * 2;
                } else {
                    morphBase[pi] = -1;
                }
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

            int morphTexture = 0, morphBuffer = 0;
            if (mb != null) {
                mb.flip();
                morphBuffer = GL15.glGenBuffers();
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, morphBuffer);
                GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, mb, GL15.GL_STATIC_DRAW);
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
                morphTexture = GlStateManager._genTexture();
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, morphTexture);
                GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, morphBuffer);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            }
            return new GpuModelData(vao, vbo, ebo, first, count, firstVertex, morphBase, morphTargets, morphVertices,
                    morphWeightOffset, morphTexture, morphBuffer);
        } finally {
            MemoryUtil.memFree(vb);
            MemoryUtil.memFree(ib);
            if (mb != null) MemoryUtil.memFree(mb);
        }
    }

    /** True if the part has morph targets on the GPU. */
    boolean hasMorphs(int part) {
        return morphBase[part] >= 0;
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
        int texture = morphTexture, buffer = morphBuffer, feedback = feedbackVao;
        RenderSystem.recordRenderCall(() -> {
            GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(vbo);
            GL15.glDeleteBuffers(ebo);
            if (texture != 0) GlStateManager._deleteTexture(texture);
            if (buffer != 0) GL15.glDeleteBuffers(buffer);
            if (feedback != 0) GL30.glDeleteVertexArrays(feedback);
        });
    }
}
