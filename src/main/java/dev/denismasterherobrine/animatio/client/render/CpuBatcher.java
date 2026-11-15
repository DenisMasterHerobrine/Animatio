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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deferred CPU path for world rendering.
 * <p>
 * Entity render types share one vanilla buffer, and every switch between two of them ends a batch, so drawing
 * models one by one costs a batch per part per entity. This batcher collects the draws of a whole render phase,
 * groups them by render type and splits each group into chunks. Workers skin and write each chunk into its own
 * vertex buffer in parallel; the render thread then only uploads and draws the chunks, with the render type's usual
 * state (and quad sorting for translucent types, which are kept in a single chunk so sorting stays global).
 */
public final class CpuBatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final int MIN_CHUNK_VERTICES = 8192;
    private static final int INITIAL_BUFFER_BYTES = 256 * 1024;

    private record Draw(RenderType type, GltfModel.Part part, float[] palette, int entries, int matrix,
                        int color, int overlay, int light) {
        int vertices() {
            return part.quads().length;
        }
    }

    private record Chunk(RenderType type, List<Draw> draws) {
    }

    private final ArrayList<Draw> draws = new ArrayList<>();
    private float[] matrices = new float[16 * 64];
    private int matrixCount;
    private final ArrayList<ByteBufferBuilder> buffers = new ArrayList<>();
    /** Per thread: final matrices, skinned positions, skinned normals, normal scales. */
    private final ThreadLocal<float[][]> scratch = ThreadLocal.withInitial(
            () -> new float[][]{new float[16 * 64], new float[0], new float[0], new float[64]});
    private boolean warnedFailure;

    public boolean isEmpty() {
        return draws.isEmpty();
    }

    /**
     * Queue an instance. The palette array is referenced, not copied: it stays valid until the next frame's
     * evaluation, which starts after this phase is flushed.
     */
    public void add(GltfModel model, AnimatedInstance instance, PoseStack.Pose pose, int light, int overlay, int tint,
                    CpuSkinner.@Nullable DrawnElsewhere drawnElsewhere) {
        float[] palette = instance.palette();
        int entries = model.paletteSize();
        if (palette.length < entries * 16) return;
        int matrix = -1;
        for (GltfModel.Part part : model.parts()) {
            MaterialOverride override = instance.override(part.materialName());
            int color = MaterialResolver.color(part, override, tint);
            RenderType type = MaterialResolver.renderType(part, override, color);
            if (type == null) continue;
            boolean base = drawnElsewhere == null || !drawnElsewhere.test(part, color);
            if (!base && part.emissiveTexture() == null) continue;
            if (matrix < 0) matrix = addMatrix(pose);
            if (base) {
                draws.add(new Draw(type, part, palette, entries, matrix, color, overlay, MaterialResolver.light(part, override, light)));
            }
            if (part.emissiveTexture() != null) {
                draws.add(new Draw(RenderType.eyes(part.emissiveTexture()), part, palette, entries, matrix, 0xFFFFFFFF, overlay, LightTexture.FULL_BRIGHT));
            }
        }
        if (RenderStats.ENABLED && matrix >= 0) RenderStats.cpuInstances++;
    }

    private int addMatrix(PoseStack.Pose pose) {
        if (matrices.length < (matrixCount + 1) * 16) matrices = Arrays.copyOf(matrices, matrices.length * 2);
        pose.pose().get(matrices, matrixCount * 16);
        return matrixCount++;
    }

    /** Build every chunk in parallel, then draw them. Render thread, during level rendering. */
    public void flush() {
        if (draws.isEmpty()) {
            clear();
            return;
        }
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        List<Chunk> chunks = chunk();
        while (buffers.size() < chunks.size()) buffers.add(new ByteBufferBuilder(INITIAL_BUFFER_BYTES));
        MeshData[] meshes = new MeshData[chunks.size()];
        // largest chunks first, so a big unsplittable (translucent) chunk does not start last
        Integer[] order = new Integer[chunks.size()];
        int[] sizes = new int[chunks.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
            for (Draw d : chunks.get(i).draws) sizes[i] += d.part.mesh().vertexCount() + d.vertices();
        }
        Arrays.sort(order, (a, b) -> Integer.compare(sizes[b], sizes[a]));
        AnimatioWorkers.parallelFor(chunks.size(), 1, k -> {
            int i = order[k];
            meshes[i] = build(chunks.get(i), i);
        });
        long t1 = RenderStats.ENABLED ? System.nanoTime() : 0L;

        for (int i = 0; i < meshes.length; i++) {
            MeshData mesh = meshes[i];
            if (mesh == null) continue;
            RenderType type = chunks.get(i).type;
            if (type.sortOnUpload()) mesh.sortQuads(buffers.get(i), RenderSystem.getVertexSorting());
            type.draw(mesh); // uploads and closes the mesh, which frees the chunk's buffer for the next frame
        }
        if (RenderStats.ENABLED) {
            RenderStats.endCpuBuild();
            RenderStats.cpuSkinNanos += t1 - t0;
            RenderStats.cpuEmitNanos += System.nanoTime() - t1;
            RenderStats.cpuBatches += chunks.size();
        }
        clear();
    }

    /** Group by render type (first-seen order) and split large opaque groups so every worker gets a share. */
    private List<Chunk> chunk() {
        Map<RenderType, List<Draw>> groups = new LinkedHashMap<>();
        int total = 0;
        for (Draw d : draws) {
            groups.computeIfAbsent(d.type, t -> new ArrayList<>()).add(d);
            total += d.vertices();
        }
        int target = Math.max(MIN_CHUNK_VERTICES, total / Math.max(1, AnimatioWorkers.threads() * 2));
        List<Chunk> chunks = new ArrayList<>();
        for (Map.Entry<RenderType, List<Draw>> group : groups.entrySet()) {
            RenderType type = group.getKey();
            List<Draw> list = group.getValue();
            if (type.sortOnUpload()) {
                chunks.add(new Chunk(type, list));
                continue;
            }
            int from = 0, vertices = 0;
            for (int i = 0; i < list.size(); i++) {
                vertices += list.get(i).vertices();
                if (vertices >= target) {
                    chunks.add(new Chunk(type, list.subList(from, i + 1)));
                    from = i + 1;
                    vertices = 0;
                }
            }
            if (from < list.size()) chunks.add(new Chunk(type, list.subList(from, list.size())));
        }
        return chunks;
    }

    /** Worker: skin and write one chunk into its own buffer. */
    private @Nullable MeshData build(Chunk chunk, int index) {
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        try {
            return buildChunk(chunk, index);
        } finally {
            if (RenderStats.ENABLED) RenderStats.recordCpuBuild(System.nanoTime() - t0, Thread.currentThread());
        }
    }

    private @Nullable MeshData buildChunk(Chunk chunk, int index) {
        ByteBufferBuilder memory = buffers.get(index);
        try {
            RenderType type = chunk.type;
            boolean raw = SkinKernel.RAW_WRITES && type.format() == DefaultVertexFormat.NEW_ENTITY && type.mode() == VertexFormat.Mode.QUADS;
            BufferBuilder builder = raw ? null : new BufferBuilder(memory, type.mode(), type.format());
            int vertices = 0;
            if (raw) for (Draw d : chunk.draws) vertices += d.vertices();
            long address = raw && vertices > 0 ? memory.reserve(vertices * SkinKernel.ENTITY_VERTEX_SIZE) : 0L;
            float[][] s = scratch.get();
            for (Draw d : chunk.draws) {
                var mesh = d.part.mesh();
                if (s[0].length < d.entries * 16) {
                    s[0] = new float[d.entries * 16];
                    s[3] = new float[d.entries];
                }
                if (s[1].length < mesh.vertexCount() * 3) {
                    s[1] = new float[mesh.vertexCount() * 3];
                    s[2] = new float[mesh.vertexCount() * 3];
                }
                SkinKernel.finals(matrices, d.matrix * 16, d.palette, d.entries, s[0], s[3]);
                SkinKernel.skin(mesh, s[0], s[3], s[1], s[2], 0);
                if (raw) {
                    SkinKernel.emitQuadsRaw(d.part.quads(), mesh, s[1], s[2], 0, address, d.color, d.overlay, d.light);
                    address += (long) d.vertices() * SkinKernel.ENTITY_VERTEX_SIZE;
                } else {
                    SkinKernel.emitQuads(d.part.quads(), mesh, s[1], s[2], 0, builder, d.color, d.overlay, d.light);
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
        draws.clear();
        matrixCount = 0;
    }

    /** Free the chunk buffers (world unload). Render thread. */
    public void release() {
        clear();
        for (ByteBufferBuilder b : buffers) b.close();
        buffers.clear();
    }
}
