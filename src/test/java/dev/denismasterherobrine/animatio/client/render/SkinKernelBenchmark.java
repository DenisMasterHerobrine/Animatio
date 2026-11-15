package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.client.model.QuadPacker;
import dev.denismasterherobrine.animatio.gltf.GltfLoader;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single-thread throughput of the CPU skinning kernel on the 59-bone dev robot. Not a correctness test:
 * run explicitly with {@code ./gradlew test -PincludeBenchmarks --tests "*SkinKernelBenchmark*" -i}.
 */
@Tag("benchmark")
class SkinKernelBenchmark {
    @Test
    void throughput() throws Exception {
        ModelData model = GltfLoader.load("heavy", Files.readAllBytes(Path.of("src/dev/resources/assets/animatio_dev/animatio/models/robot_heavy.glb")), true, null);
        var mesh = model.meshes().getFirst();
        int[] quads = QuadPacker.pack(mesh.indices());
        int entries = model.paletteSize();
        float[] palette = new float[entries * 16];
        for (int i = 0; i < entries; i++) Mat4.trs(palette, i * 16, 0, 0, 0, 0, (float) Math.sin(i * 0.1), 0, (float) Math.cos(i * 0.1), 1, 1, 1);
        float[] pose = new float[16];
        Mat4.identity(pose, 0);
        float[] finals = new float[palette.length], scales = new float[entries];
        float[] p = new float[mesh.vertexCount() * 3], n = new float[mesh.vertexCount() * 3];
        int instances = 1000;
        try (ByteBufferBuilder memory = new ByteBufferBuilder(64 << 20)) {
            for (int round = 0; round < 10; round++) {
                long skin = 0, emit = 0, raw = 0;
                BufferBuilder builder = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
                for (int i = 0; i < instances; i++) {
                    long a = System.nanoTime();
                    SkinKernel.finals(pose, 0, palette, entries, finals, scales);
                    SkinKernel.skin(mesh, finals, scales, p, n, 0);
                    long b = System.nanoTime();
                    SkinKernel.emitQuads(quads, mesh, p, n, 0, builder, -1, 0, 0xF000F0);
                    emit += System.nanoTime() - b;
                    skin += b - a;
                }
                MeshData data = builder.build();
                if (data != null) data.close();
                long address = memory.reserve(instances * quads.length * SkinKernel.ENTITY_VERTEX_SIZE);
                long c = System.nanoTime();
                for (int i = 0; i < instances; i++) {
                    SkinKernel.emitQuadsRaw(quads, mesh, p, n, 0, address + (long) i * quads.length * SkinKernel.ENTITY_VERTEX_SIZE, -1, 0, 0xF000F0);
                }
                raw += System.nanoTime() - c;
                ByteBufferBuilder.Result result = memory.build();
                if (result != null) result.close();
                long verts = (long) instances * quads.length;
                System.out.printf("round %d: skin %.2f ns/vertex, emit via BufferBuilder %.2f ns/vertex, raw emit %.2f ns/vertex%n",
                        round, skin / (double) (instances * (long) mesh.vertexCount()), emit / (double) verts, raw / (double) verts);
            }
        }
    }
}
