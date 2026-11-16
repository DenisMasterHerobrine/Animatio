package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Deferred CPU path for world rendering.
 * <p>
 * Entity render types share one vanilla buffer, and every switch between two of them ends a batch, so drawing
 * models one by one costs a batch per part per entity. This batcher collects the draws of a whole render phase,
 * groups them by render type and splits each group into chunks. Workers skin and write each chunk into its own
 * vertex buffer in parallel; the render thread then only uploads and draws the chunks, with the render type's usual
 * state (and quad sorting for translucent types, which are kept in a single chunk so sorting stays global).
 * <p>
 * Draws are kept in parallel arrays and grouped with a counting sort on per-frame render type ids, so a frame with
 * thousands of parts allocates nothing once the arrays have grown.
 */
public final class CpuBatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final int MIN_CHUNK_VERTICES = 8192;
    private static final int INITIAL_BUFFER_BYTES = 256 * 1024;

    // draws, structure of arrays
    private int drawCount;
    private int[] dType = new int[256];
    private GltfModel.Part[] dPart = new GltfModel.Part[256];
    private float[][] dPalette = new float[256][];
    private float[][] dMorph = new float[256][];
    private int[] dEntries = new int[256];
    private int[] dMatrix = new int[256];
    private int[] dColor = new int[256];
    private int[] dOverlay = new int[256];
    private int[] dLight = new int[256];

    // render types seen this frame
    private final Reference2IntOpenHashMap<RenderType> typeIds = new Reference2IntOpenHashMap<>();
    private final ArrayList<RenderType> types = new ArrayList<>();
    private int[] typeVertices = new int[16];

    // chunks: ranges of `order`
    private int[] order = new int[256];
    private int chunkCount;
    private int[] chunkType = new int[64], chunkStart = new int[64], chunkEnd = new int[64], chunkWork = new int[64];
    private Integer[] chunkOrder = new Integer[0];

    private float[] matrices = new float[16 * 64];
    private int matrixCount;
    private final ArrayList<ByteBufferBuilder> buffers = new ArrayList<>();
    private MeshData[] meshes = new MeshData[0];
    private final ThreadLocal<CpuSkinner.Scratch> scratch = ThreadLocal.withInitial(CpuSkinner.Scratch::new);
    private volatile boolean warnedFailure;

    public CpuBatcher() {
        typeIds.defaultReturnValue(-1);
    }

    public boolean isEmpty() {
        return drawCount == 0;
    }

    /**
     * Queue an instance. The palette and morph weight arrays are referenced, not copied: they stay valid until the
     * next frame's evaluation, which starts after this phase is flushed.
     */
    public void add(GltfModel model, AnimatedInstance instance, PoseStack.Pose pose, int light, int overlay, int tint,
                    CpuSkinner.@Nullable DrawnElsewhere drawnElsewhere) {
        float[] palette = instance.palette();
        int entries = model.paletteSize();
        if (palette.length < entries * 16) return;
        float[] morph = instance.morphWeights();
        int matrix = -1;
        List<GltfModel.Part> parts = model.parts();
        for (int i = 0, size = parts.size(); i < size; i++) {
            GltfModel.Part part = parts.get(i);
            MaterialOverride override = instance.override(part.materialName());
            int color = MaterialResolver.color(part, override, tint);
            RenderType type = MaterialResolver.renderType(part, override, color, instance.skinTexture());
            if (type == null) continue;
            boolean base = drawnElsewhere == null || !drawnElsewhere.test(part, color);
            if (!base && part.emissiveTexture() == null) continue;
            if (matrix < 0) matrix = addMatrix(pose);
            if (base) push(type, part, palette, morph, entries, matrix, color, overlay, MaterialResolver.light(part, override, light));
            if (part.emissiveTexture() != null) {
                push(RenderType.eyes(part.emissiveTexture()), part, palette, morph, entries, matrix, 0xFFFFFFFF, overlay, LightTexture.FULL_BRIGHT);
            }
        }
        if (RenderStats.ENABLED && matrix >= 0) RenderStats.cpuInstances++;
    }

    private void push(RenderType type, GltfModel.Part part, float[] palette, float[] morph, int entries, int matrix,
                      int color, int overlay, int light) {
        int id = typeIds.getInt(type);
        if (id < 0) {
            id = types.size();
            types.add(type);
            typeIds.put(type, id);
        }
        int i = drawCount;
        if (i == dType.length) grow(i * 2);
        dType[i] = id;
        dPart[i] = part;
        dPalette[i] = palette;
        dMorph[i] = morph;
        dEntries[i] = entries;
        dMatrix[i] = matrix;
        dColor[i] = color;
        dOverlay[i] = overlay;
        dLight[i] = light;
        drawCount = i + 1;
    }

    private void grow(int size) {
        dType = Arrays.copyOf(dType, size);
        dPart = Arrays.copyOf(dPart, size);
        dPalette = Arrays.copyOf(dPalette, size);
        dMorph = Arrays.copyOf(dMorph, size);
        dEntries = Arrays.copyOf(dEntries, size);
        dMatrix = Arrays.copyOf(dMatrix, size);
        dColor = Arrays.copyOf(dColor, size);
        dOverlay = Arrays.copyOf(dOverlay, size);
        dLight = Arrays.copyOf(dLight, size);
    }

    private int addMatrix(PoseStack.Pose pose) {
        if (matrices.length < (matrixCount + 1) * 16) matrices = Arrays.copyOf(matrices, matrices.length * 2);
        pose.pose().get(matrices, matrixCount * 16);
        return matrixCount++;
    }

    /** Build every chunk in parallel, then draw them. Render thread, during level rendering. */
    public void flush() {
        if (drawCount == 0) {
            clear();
            return;
        }
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        chunk();
        int chunks = chunkCount;
        while (buffers.size() < chunks) buffers.add(new ByteBufferBuilder(INITIAL_BUFFER_BYTES));
        if (meshes.length < chunks) meshes = new MeshData[chunks];
        // largest chunks first, so a big unsplittable (translucent) chunk does not start last
        if (chunkOrder.length < chunks) chunkOrder = new Integer[chunks];
        for (int i = 0; i < chunks; i++) chunkOrder[i] = i;
        int[] work = chunkWork;
        Arrays.sort(chunkOrder, 0, chunks, (a, b) -> Integer.compare(work[b], work[a]));
        Integer[] sorted = chunkOrder;
        MeshData[] out = meshes;
        AnimatioWorkers.parallelFor(chunks, 1, k -> {
            int i = sorted[k];
            out[i] = build(i);
        });
        long t1 = RenderStats.ENABLED ? System.nanoTime() : 0L;

        for (int i = 0; i < chunks; i++) {
            MeshData mesh = out[i];
            out[i] = null;
            if (mesh == null) continue;
            RenderType type = types.get(chunkType[i]);
            if (type.sortOnUpload()) mesh.sortQuads(buffers.get(i), RenderSystem.getVertexSorting());
            type.draw(mesh); // uploads and closes the mesh, which frees the chunk's buffer for the next frame
        }
        if (RenderStats.ENABLED) {
            RenderStats.endCpuBuild();
            RenderStats.cpuSkinNanos += t1 - t0;
            RenderStats.cpuEmitNanos += System.nanoTime() - t1;
            RenderStats.cpuBatches += chunks;
        }
        clear();
    }

    /**
     * Group draws by render type (first-seen order, stable within a type) with a counting sort, then split large
     * opaque groups so every worker gets a share.
     */
    private void chunk() {
        int n = drawCount, typeCount = types.size();
        if (typeVertices.length < typeCount + 1) typeVertices = new int[typeCount + 1];
        int[] counts = new int[typeCount + 1];
        Arrays.fill(typeVertices, 0, typeCount, 0);
        int total = 0;
        for (int i = 0; i < n; i++) {
            counts[dType[i] + 1]++;
            int v = dPart[i].quads().length;
            typeVertices[dType[i]] += v;
            total += v;
        }
        for (int t = 0; t < typeCount; t++) counts[t + 1] += counts[t];
        int[] typeStart = Arrays.copyOf(counts, typeCount + 1);
        if (order.length < n) order = new int[Math.max(n, order.length * 2)];
        for (int i = 0; i < n; i++) order[counts[dType[i]]++] = i;

        int target = Math.max(MIN_CHUNK_VERTICES, total / Math.max(1, AnimatioWorkers.threads() * 2));
        chunkCount = 0;
        for (int t = 0; t < typeCount; t++) {
            int from = typeStart[t], to = typeStart[t + 1];
            if (from == to) continue;
            if (types.get(t).sortOnUpload()) {
                addChunk(t, from, to);
                continue;
            }
            int start = from, vertices = 0;
            for (int k = from; k < to; k++) {
                vertices += dPart[order[k]].quads().length;
                if (vertices >= target) {
                    addChunk(t, start, k + 1);
                    start = k + 1;
                    vertices = 0;
                }
            }
            if (start < to) addChunk(t, start, to);
        }
    }

    private void addChunk(int type, int start, int end) {
        int c = chunkCount;
        if (c == chunkType.length) {
            chunkType = Arrays.copyOf(chunkType, c * 2);
            chunkStart = Arrays.copyOf(chunkStart, c * 2);
            chunkEnd = Arrays.copyOf(chunkEnd, c * 2);
            chunkWork = Arrays.copyOf(chunkWork, c * 2);
        }
        chunkType[c] = type;
        chunkStart[c] = start;
        chunkEnd[c] = end;
        int work = 0;
        for (int k = start; k < end; k++) work += dPart[order[k]].mesh().vertexCount() + dPart[order[k]].quads().length;
        chunkWork[c] = work;
        chunkCount = c + 1;
    }

    /** Worker: skin and write one chunk into its own buffer. */
    private @Nullable MeshData build(int index) {
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        try {
            return buildChunk(index);
        } finally {
            if (RenderStats.ENABLED) RenderStats.recordCpuBuild(System.nanoTime() - t0, Thread.currentThread());
        }
    }

    private @Nullable MeshData buildChunk(int index) {
        ByteBufferBuilder memory = buffers.get(index);
        try {
            RenderType type = types.get(chunkType[index]);
            int start = chunkStart[index], end = chunkEnd[index];
            boolean raw = SkinKernel.RAW_WRITES && type.format() == DefaultVertexFormat.NEW_ENTITY && type.mode() == VertexFormat.Mode.QUADS;
            BufferBuilder builder = raw ? null : new BufferBuilder(memory, type.mode(), type.format());
            int vertices = 0;
            if (raw) for (int k = start; k < end; k++) vertices += dPart[order[k]].quads().length;
            long address = raw && vertices > 0 ? memory.reserve(vertices * SkinKernel.ENTITY_VERTEX_SIZE) : 0L;
            CpuSkinner.Scratch s = scratch.get();
            int maxEntries = 0, maxVertices = 0;
            for (int k = start; k < end; k++) {
                int d = order[k];
                maxEntries = Math.max(maxEntries, dEntries[d]);
                maxVertices = Math.max(maxVertices, dPart[d].mesh().vertexCount());
            }
            s.ensure(maxEntries, maxVertices);
            float[] lastPalette = null;
            int lastMatrix = -1;
            for (int k = start; k < end; k++) {
                int d = order[k];
                GltfModel.Part part = dPart[d];
                var mesh = part.mesh();
                // parts of one instance share their final matrices
                if (dPalette[d] != lastPalette || dMatrix[d] != lastMatrix) {
                    SkinKernel.finals(matrices, dMatrix[d] * 16, dPalette[d], dEntries[d], s.finals, s.normalScales);
                    lastPalette = dPalette[d];
                    lastMatrix = dMatrix[d];
                }
                s.skin(mesh, dMorph[d]);
                int[] quads = part.quads();
                if (raw) {
                    SkinKernel.emitQuadsRaw(quads, mesh, s.positions, s.normals, 0, address, dColor[d], dOverlay[d], dLight[d]);
                    address += (long) quads.length * SkinKernel.ENTITY_VERTEX_SIZE;
                } else {
                    SkinKernel.emitQuads(quads, mesh, s.positions, s.normals, 0, builder, dColor[d], dOverlay[d], dLight[d]);
                }
            }
            if (!raw) return builder.build();
            if (vertices == 0) return null;
            ByteBufferBuilder.Result result = memory.build();
            if (result == null) return null;
            VertexFormat.Mode mode = type.mode();
            return new MeshData(result, new MeshData.DrawState(type.format(), vertices, mode.indexCount(vertices), mode,
                    VertexFormat.IndexType.least(vertices)));
        } catch (RuntimeException e) {
            // the buffer may hold a half-written batch: replace it so the next frame starts clean
            memory.close();
            buffers.set(index, new ByteBufferBuilder(INITIAL_BUFFER_BYTES));
            if (!warnedFailure) {
                warnedFailure = true;
                LOGGER.error("Building a CPU-skinned batch failed (further failures are not logged)", e);
            }
            return null;
        }
    }

    public void clear() {
        Arrays.fill(dPart, 0, drawCount, null);
        Arrays.fill(dPalette, 0, drawCount, null);
        Arrays.fill(dMorph, 0, drawCount, null);
        drawCount = 0;
        matrixCount = 0;
        chunkCount = 0;
        types.clear();
        typeIds.clear();
    }

    /** Free the chunk buffers (world unload). Render thread. */
    public void release() {
        clear();
        for (ByteBufferBuilder b : buffers) b.close();
        buffers.clear();
    }
}
