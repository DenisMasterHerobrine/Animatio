package dev.denismasterherobrine.animatio.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.denismasterherobrine.animatio.anim.math.Mat4;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinKernelTest {
    private static final float EPS = 1e-5f;

    private static dev.denismasterherobrine.animatio.gltf.MeshData mesh(int[] joints, float[] weights, float[] colors) {
        float[] pos = {1, 2, 3, -1, 0.5f, 2};
        float[] nrm = {0, 0, 1, 0, 0.6f, 0.8f};
        float[] uv = {0.25f, 0.5f, 0.75f, 1f};
        return new dev.denismasterherobrine.animatio.gltf.MeshData("m", -1, 2, pos, nrm, uv, colors, joints, weights, new int[0]);
    }

    /** Two bones: a rotation with uniform scale 2, and a rotation without scale. */
    private static float[] matrices() {
        float[] m = new float[32];
        Mat4.trs(m, 0, 1, 2, 3, 0, (float) Math.sin(0.4), 0, (float) Math.cos(0.4), 2, 2, 2);
        Mat4.trs(m, 16, -1, 0, 0, (float) Math.sin(0.2), 0, 0, (float) Math.cos(0.2), 1, 1, 1);
        return m;
    }

    private static float[] scales(float[] m) {
        float[] identity = new float[16];
        Mat4.identity(identity, 0);
        float[] out = new float[32], scales = new float[2];
        SkinKernel.finals(identity, 0, m, 2, out, scales);
        return scales;
    }

    @Test
    void normalScaleDetectsUniformScale() {
        float[] s = scales(matrices());
        assertEquals(0.5f, s[0], EPS);
        assertEquals(1f, s[1], EPS);
        float[] stretched = new float[16];
        Mat4.trs(stretched, 0, 0, 0, 0, 0, 0, 0, 1, 1, 3, 1);
        float[] identity = new float[16], out = new float[16], scale = new float[1];
        Mat4.identity(identity, 0);
        SkinKernel.finals(identity, 0, stretched, 1, out, scale);
        assertTrue(Float.isNaN(scale[0]), "non-uniform scale falls back to exact normalisation");
    }

    @Test
    void rigidFastPathMatchesWeightedPath() {
        float[] m = matrices();
        float[] s = scales(m);
        var rigid = mesh(new int[]{0, 0, 0, 0, 1, 0, 0, 0}, new float[]{1, 0, 0, 0, 1, 0, 0, 0}, null);
        var split = mesh(new int[]{0, 0, 0, 0, 1, 1, 0, 0}, new float[]{0.5f, 0.5f, 0, 0, 0.5f, 0.5f, 0, 0}, null);
        float[] p1 = new float[6], n1 = new float[6], p2 = new float[6], n2 = new float[6];
        SkinKernel.skin(rigid, m, s, p1, n1, 0);
        SkinKernel.skin(split, m, s, p2, n2, 0);
        assertArrayEquals(p1, p2, EPS);
        assertArrayEquals(n1, n2, EPS);
        for (int v = 0; v < 2; v++) {
            int o = v * 3;
            assertEquals(1f, (float) Math.sqrt(n1[o] * n1[o] + n1[o + 1] * n1[o + 1] + n1[o + 2] * n1[o + 2]), EPS);
        }
    }

    @Test
    void blendsTwoJoints() {
        float[] m = matrices();
        var mesh = mesh(new int[]{0, 1, 0, 0, 0, 1, 0, 0}, new float[]{0.25f, 0.75f, 0, 0, 0.5f, 0.5f, 0, 0}, null);
        float[] p = new float[9], n = new float[9];
        SkinKernel.skin(mesh, m, scales(m), p, n, 3); // offset: writes floats 3..8
        float[] a = new float[3], b = new float[3];
        Mat4.transformPoint(m, 0, 1, 2, 3, a, 0);
        Mat4.transformPoint(m, 16, 1, 2, 3, b, 0);
        for (int c = 0; c < 3; c++) assertEquals(0.25f * a[c] + 0.75f * b[c], p[3 + c], EPS);
        assertEquals(0f, p[0]);
    }

    @Test
    void rawWriterMatchesBufferBuilder() {
        float[] m = matrices();
        var mesh = mesh(new int[]{0, 0, 0, 0, 1, 0, 0, 0}, new float[]{1, 0, 0, 0, 1, 0, 0, 0},
                new float[]{1, 0.5f, 0.25f, 1, 0.2f, 0.4f, 0.6f, 0.8f});
        float[] p = new float[6], n = new float[6];
        SkinKernel.skin(mesh, m, scales(m), p, n, 0);
        int[] quads = {0, 1, 1, 0};
        int color = 0xCC336699, overlay = 0x000A0003, light = 0x00F000A0;

        try (ByteBufferBuilder a = new ByteBufferBuilder(1024); ByteBufferBuilder b = new ByteBufferBuilder(1024)) {
            BufferBuilder builder = new BufferBuilder(a, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            SkinKernel.emitQuads(quads, mesh, p, n, 0, builder, color, overlay, light);
            MeshData expected = builder.buildOrThrow();

            long address = b.reserve(quads.length * SkinKernel.ENTITY_VERTEX_SIZE);
            SkinKernel.emitQuadsRaw(quads, mesh, p, n, 0, address, color, overlay, light);
            ByteBufferBuilder.Result actual = b.build();

            ByteBuffer e = expected.vertexBuffer(), r = actual.byteBuffer();
            assertEquals(e.remaining(), r.remaining());
            for (int i = 0; i < e.remaining(); i++) {
                if (i % SkinKernel.ENTITY_VERTEX_SIZE == 35) continue; // padding byte
                assertEquals(e.get(e.position() + i), r.get(r.position() + i), "byte " + i);
            }
            expected.close();
            actual.close();
        }
    }
}
