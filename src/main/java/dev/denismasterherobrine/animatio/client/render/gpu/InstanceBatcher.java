package dev.denismasterherobrine.animatio.client.render.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects GPU-skinned instances during a render phase and draws them at the end of it: all bone palettes go into
 * one texture buffer, all instance records into another, and every (shader, model part, texture, culling) group
 * becomes one instanced draw call. Render thread only.
 */
final class InstanceBatcher {
    static final int RECORD_FLOATS = 32; // 8 RGBA texels
    private static final int BONES_UNIT = 10, INSTANCES_UNIT = 11;

    private record Draw(ShaderInstance shader, GltfModel model, GpuModelData gpu, int part, ResourceLocation texture,
                        boolean cull, int record) {
    }

    private static final Comparator<Draw> ORDER = Comparator
            .comparingInt((Draw d) -> d.shader.getId())
            .thenComparingInt(d -> d.model.version())
            .thenComparingInt(d -> d.part)
            .thenComparing(d -> d.texture)
            .thenComparing(d -> d.cull);

    private float[] bones = new float[12 * 256];
    private int boneCount;
    private float[] records = new float[RECORD_FLOATS * 64];
    private int recordCount;
    private final List<Draw> draws = new ArrayList<>();

    private static final boolean GL_DEBUG = Boolean.getBoolean("animatio.glDebug");
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Animatio");
    private static int glErrorsLogged;
    /** Statistics of the last flush: instance records and draw calls. */
    int lastRecords, lastDrawCalls;

    private static int maxTexels = -1;
    private static int bonesBuffer, bonesTexture, instancesBuffer, instancesTexture;
    private static FloatBuffer upload = MemoryUtil.memAllocFloat(4096);
    private static final Map<ShaderInstance, int[]> SAMPLER_LOCATIONS = new IdentityHashMap<>();

    boolean isEmpty() {
        return draws.isEmpty();
    }

    /** Whether {@code bones} more bones and {@code parts} more records fit in one draw batch. */
    boolean hasRoom(int bones, int parts) {
        int limit = maxTexels();
        return (boneCount + bones) * 3L <= limit && (recordCount + parts) * 8L <= limit;
    }

    /** Append a palette (16 floats per entry, column-major) and return its first bone index. */
    int addBones(float[] palette, int entries) {
        int needed = (boneCount + entries) * 12;
        if (bones.length < needed) bones = Arrays.copyOf(bones, Math.max(needed, bones.length * 2));
        int offset = boneCount;
        int o = boneCount * 12;
        for (int i = 0; i < entries; i++) {
            int m = i * 16;
            // rows of the affine matrix
            bones[o] = palette[m];
            bones[o + 1] = palette[m + 4];
            bones[o + 2] = palette[m + 8];
            bones[o + 3] = palette[m + 12];
            bones[o + 4] = palette[m + 1];
            bones[o + 5] = palette[m + 5];
            bones[o + 6] = palette[m + 9];
            bones[o + 7] = palette[m + 13];
            bones[o + 8] = palette[m + 2];
            bones[o + 9] = palette[m + 6];
            bones[o + 10] = palette[m + 10];
            bones[o + 11] = palette[m + 14];
            o += 12;
        }
        boneCount += entries;
        return offset;
    }

    void add(ShaderInstance shader, GltfModel model, GpuModelData gpu, int part, ResourceLocation texture, boolean cull,
             Matrix4f pose, int light, int overlay, int argb, float emissive, float p0, float p1, float p2, float p3, int boneOffset) {
        int needed = (recordCount + 1) * RECORD_FLOATS;
        if (records.length < needed) records = Arrays.copyOf(records, Math.max(needed, records.length * 2));
        int o = recordCount * RECORD_FLOATS;
        pose.get(records, o);
        records[o + 16] = light & 0xFFFF;
        records[o + 17] = (light >> 16) & 0xFFFF;
        records[o + 18] = overlay & 0xFFFF;
        records[o + 19] = (overlay >> 16) & 0xFFFF;
        records[o + 20] = ((argb >> 16) & 0xFF) / 255f;
        records[o + 21] = ((argb >> 8) & 0xFF) / 255f;
        records[o + 22] = (argb & 0xFF) / 255f;
        records[o + 23] = ((argb >>> 24) & 0xFF) / 255f;
        records[o + 24] = boneOffset;
        records[o + 25] = emissive;
        records[o + 26] = 0f;
        records[o + 27] = 0f;
        records[o + 28] = p0;
        records[o + 29] = p1;
        records[o + 30] = p2;
        records[o + 31] = p3;
        draws.add(new Draw(shader, model, gpu, part, texture, cull, recordCount));
        recordCount++;
    }

    void clear() {
        draws.clear();
        boneCount = 0;
        recordCount = 0;
    }

    /** Draw everything collected, with the level's view and projection matrices. */
    void flush(Matrix4f modelView, Matrix4f projection) {
        if (draws.isEmpty()) return;
        long t0 = dev.denismasterherobrine.animatio.client.render.RenderStats.ENABLED ? System.nanoTime() : 0L;
        flushDraws(modelView, projection);
        if (dev.denismasterherobrine.animatio.client.render.RenderStats.ENABLED) {
            dev.denismasterherobrine.animatio.client.render.RenderStats.gpuFlushNanos += System.nanoTime() - t0;
        }
    }

    private void flushDraws(Matrix4f modelView, Matrix4f projection) {
        RenderSystem.assertOnRenderThread();
        ensureBuffers();
        draws.sort(ORDER);

        // instance records in draw order, so that each group is contiguous
        float[] sorted = new float[draws.size() * RECORD_FLOATS];
        for (int i = 0; i < draws.size(); i++) {
            System.arraycopy(records, draws.get(i).record * RECORD_FLOATS, sorted, i * RECORD_FLOATS, RECORD_FLOATS);
        }
        uploadTo(bonesBuffer, bones, boneCount * 12);
        uploadTo(instancesBuffer, sorted, sorted.length);

        Minecraft mc = Minecraft.getInstance();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        mc.gameRenderer.overlayTexture().setupOverlayColor();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        ShaderInstance current = null;
        int i = 0;
        int calls = 0;
        while (i < draws.size()) {
            Draw first = draws.get(i);
            int j = i + 1;
            while (j < draws.size() && ORDER.compare(first, draws.get(j)) == 0) j++;

            RenderSystem.setShaderTexture(0, first.texture);
            ShaderInstance shader = first.shader;
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, mc.getWindow());
            Uniform base = shader.getUniform("AnimatioInstanceBase");
            if (base != null) base.set(i);
            shader.apply();
            if (shader != current) {
                bindBufferTextures(shader);
                current = shader;
            }
            if (first.cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();

            GL30.glBindVertexArray(first.gpu.vao);
            GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, first.gpu.indexCount[first.part], GL11.GL_UNSIGNED_INT,
                    (long) first.gpu.firstIndex[first.part] * Integer.BYTES, j - i);
            calls++;
            i = j;
        }
        lastRecords = draws.size();
        lastDrawCalls = calls;
        if (GL_DEBUG) {
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR && glErrorsLogged++ < 20) LOGGER.error("GL error 0x{} after GPU skinning flush", Integer.toHexString(error));
        }

        GL30.glBindVertexArray(0);
        BufferUploader.invalidate();
        if (current != null) current.clear();
        unbindBufferTextures();
        RenderSystem.enableCull();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        mc.gameRenderer.overlayTexture().teardownOverlayColor();
        clear();
    }

    private static int maxTexels() {
        if (maxTexels < 0) maxTexels = Math.max(65536, GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE));
        return maxTexels;
    }

    private static void ensureBuffers() {
        if (bonesBuffer != 0) return;
        bonesBuffer = GL15.glGenBuffers();
        instancesBuffer = GL15.glGenBuffers();
        bonesTexture = GlStateManager._genTexture();
        instancesTexture = GlStateManager._genTexture();
        attach(bonesTexture, bonesBuffer);
        attach(instancesTexture, instancesBuffer);
    }

    private static void attach(int texture, int buffer) {
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, 16L, GL15.GL_STREAM_DRAW);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
    }

    private static void uploadTo(int buffer, float[] data, int count) {
        if (upload.capacity() < count) {
            MemoryUtil.memFree(upload);
            upload = MemoryUtil.memAllocFloat(Math.max(count, upload.capacity() * 2));
        }
        upload.clear();
        upload.put(data, 0, count).flip();
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, upload, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
    }

    private static void bindBufferTextures(ShaderInstance shader) {
        int[] loc = SAMPLER_LOCATIONS.computeIfAbsent(shader, s -> new int[]{
                GL20.glGetUniformLocation(s.getId(), "AnimatioBones"),
                GL20.glGetUniformLocation(s.getId(), "AnimatioInstances")});
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + BONES_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, bonesTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + INSTANCES_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, instancesTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(loc[0], BONES_UNIT);
        GL20.glUniform1i(loc[1], INSTANCES_UNIT);
    }

    private static void unbindBufferTextures() {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + BONES_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + INSTANCES_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** Forget cached uniform locations (shaders were reloaded). */
    static void invalidateShaders() {
        SAMPLER_LOCATIONS.clear();
    }
}
