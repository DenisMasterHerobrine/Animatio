package dev.denismasterherobrine.animatio.client.render.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.denismasterherobrine.animatio.client.render.RenderStats;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
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
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Collects GPU-skinned instances during a render phase and draws them at the end of it: all bone palettes go into
 * one texture buffer, all morph weights into another, all instance records into a third, and every (shader, model
 * part, texture, culling) group becomes one instanced draw call. Render thread only.
 * <p>
 * Each record gets a 64-bit sort key: per-frame ids of its shader, model, part, texture and culling flag above the
 * record index. Sorting the key array (a primitive sort) both groups the records and gives the upload order, so a
 * frame allocates nothing once the arrays have grown.
 */
final class InstanceBatcher {
    static final int RECORD_FLOATS = 32; // 8 RGBA texels
    private static final int BONES_UNIT = 10, INSTANCES_UNIT = 11, MORPH_WEIGHTS_UNIT = 12, MORPHS_UNIT = 13;
    private static final int RECORD_BITS = 20;
    private static final int MAX_RECORDS = 1 << RECORD_BITS;
    private static final long RECORD_MASK = MAX_RECORDS - 1;

    private float[] bones = new float[12 * 256];
    private int boneCount;
    private float[] morphWeights = new float[256];
    private int morphWeightCount;
    private float[] records = new float[RECORD_FLOATS * 64];
    private float[] sorted = new float[RECORD_FLOATS * 64];
    private int recordCount;
    private long[] keys = new long[64];

    // per-frame ids for the sort keys
    private final Reference2IntOpenHashMap<ShaderInstance> shaderIds = new Reference2IntOpenHashMap<>();
    private final ArrayList<ShaderInstance> shaders = new ArrayList<>();
    private final Reference2IntOpenHashMap<GpuModelData> modelIds = new Reference2IntOpenHashMap<>();
    private final ArrayList<GpuModelData> models = new ArrayList<>();
    private final Object2IntOpenHashMap<ResourceLocation> textureIds = new Object2IntOpenHashMap<>();
    private final ArrayList<ResourceLocation> textures = new ArrayList<>();

    private static final boolean GL_DEBUG = Boolean.getBoolean("animatio.glDebug");
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Animatio");
    private static int glErrorsLogged;
    /** Statistics of the last flush: instance records and draw calls. */
    int lastRecords, lastDrawCalls;

    private static int maxTexels = -1;
    private static int bonesBuffer, bonesTexture, instancesBuffer, instancesTexture, weightsBuffer, weightsTexture;
    private static FloatBuffer upload = MemoryUtil.memAllocFloat(4096);
    private static final Map<ShaderInstance, int[]> LOCATIONS = new IdentityHashMap<>();
    private static final int LOC_BONES = 0, LOC_INSTANCES = 1, LOC_WEIGHTS = 2, LOC_MORPHS = 3, LOC_MORPH_BASE = 4,
            LOC_MORPH_TARGETS = 5, LOC_MORPH_VERTICES = 6, LOC_MORPH_FIRST = 7, LOC_MORPH_WEIGHT_OFFSET = 8;

    InstanceBatcher() {
        shaderIds.defaultReturnValue(-1);
        modelIds.defaultReturnValue(-1);
        textureIds.defaultReturnValue(-1);
    }

    boolean isEmpty() {
        return recordCount == 0;
    }

    /** Whether {@code bones} more bones, {@code weights} more morph weights and {@code parts} more records fit in one batch. */
    boolean hasRoom(int bones, int weights, int parts) {
        int limit = maxTexels();
        return (boneCount + bones) * 3L <= limit && (recordCount + parts) * 8L <= limit && recordCount + parts <= MAX_RECORDS
                && (morphWeightCount + weights + 3L) / 4 <= limit && shaders.size() < 255 && models.size() < 16383
                && textures.size() < 2047;
    }

    /** Append a palette (16 floats per entry, column-major) and return its first bone index. */
    int addBones(float[] palette, int entries) {
        int needed = (boneCount + entries) * 12;
        if (bones.length < needed) bones = Arrays.copyOf(bones, Math.max(needed, bones.length * 2));
        int offset = boneCount;
        float[] b = bones;
        int o = boneCount * 12;
        for (int i = 0, m = 0; i < entries; i++, m += 16, o += 12) {
            // rows of the affine matrix
            b[o] = palette[m];
            b[o + 1] = palette[m + 4];
            b[o + 2] = palette[m + 8];
            b[o + 3] = palette[m + 12];
            b[o + 4] = palette[m + 1];
            b[o + 5] = palette[m + 5];
            b[o + 6] = palette[m + 9];
            b[o + 7] = palette[m + 13];
            b[o + 8] = palette[m + 2];
            b[o + 9] = palette[m + 6];
            b[o + 10] = palette[m + 10];
            b[o + 11] = palette[m + 14];
        }
        boneCount += entries;
        return offset;
    }

    /** Append an instance's morph weights and return the index of its first weight. */
    int addMorphWeights(float[] weights) {
        int needed = morphWeightCount + weights.length;
        if (morphWeights.length < needed) morphWeights = Arrays.copyOf(morphWeights, Math.max(needed, morphWeights.length * 2));
        int offset = morphWeightCount;
        System.arraycopy(weights, 0, morphWeights, offset, weights.length);
        morphWeightCount = needed;
        return offset;
    }

    void add(ShaderInstance shader, GpuModelData gpu, int part, ResourceLocation texture, boolean cull,
             Matrix4f pose, int light, int overlay, int argb, float emissive, float p0, float p1, float p2, float p3,
             int boneOffset, int morphWeightOffset) {
        int r = recordCount;
        int needed = (r + 1) * RECORD_FLOATS;
        if (records.length < needed) records = Arrays.copyOf(records, Math.max(needed, records.length * 2));
        if (keys.length <= r) keys = Arrays.copyOf(keys, keys.length * 2);
        float[] rec = records;
        int o = r * RECORD_FLOATS;
        pose.get(rec, o);
        rec[o + 16] = light & 0xFFFF;
        rec[o + 17] = (light >> 16) & 0xFFFF;
        rec[o + 18] = overlay & 0xFFFF;
        rec[o + 19] = (overlay >> 16) & 0xFFFF;
        rec[o + 20] = ((argb >> 16) & 0xFF) / 255f;
        rec[o + 21] = ((argb >> 8) & 0xFF) / 255f;
        rec[o + 22] = (argb & 0xFF) / 255f;
        rec[o + 23] = ((argb >>> 24) & 0xFF) / 255f;
        rec[o + 24] = boneOffset;
        rec[o + 25] = emissive;
        rec[o + 26] = morphWeightOffset;
        rec[o + 27] = 0f;
        rec[o + 28] = p0;
        rec[o + 29] = p1;
        rec[o + 30] = p2;
        rec[o + 31] = p3;
        long group = (long) id(shaderIds, shaders, shader) << 36 | (long) id(modelIds, models, gpu) << 22
                | (long) Math.min(part, 1023) << 12 | (long) textureId(texture) << 1 | (cull ? 1 : 0);
        keys[r] = group << RECORD_BITS | r;
        recordCount = r + 1;
    }

    private static <T> int id(Reference2IntOpenHashMap<T> ids, ArrayList<T> list, T value) {
        int id = ids.getInt(value);
        if (id < 0) {
            id = list.size();
            list.add(value);
            ids.put(value, id);
        }
        return id;
    }

    private int textureId(ResourceLocation texture) {
        int id = textureIds.getInt(texture);
        if (id < 0) {
            id = textures.size();
            textures.add(texture);
            textureIds.put(texture, id);
        }
        return id;
    }

    void clear() {
        boneCount = 0;
        morphWeightCount = 0;
        recordCount = 0;
        shaderIds.clear();
        shaders.clear();
        modelIds.clear();
        models.clear();
        textureIds.clear();
        textures.clear();
    }

    /** Draw everything collected, with the level's view and projection matrices. */
    void flush(Matrix4f modelView, Matrix4f projection) {
        if (recordCount == 0) return;
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        flushDraws(modelView, projection);
        if (RenderStats.ENABLED) RenderStats.gpuFlushNanos += System.nanoTime() - t0;
    }

    private void flushDraws(Matrix4f modelView, Matrix4f projection) {
        RenderSystem.assertOnRenderThread();
        ensureBuffers();
        int n = recordCount;
        long[] k = keys;
        Arrays.sort(k, 0, n);

        // instance records in key order, so that each group is contiguous
        if (sorted.length < n * RECORD_FLOATS) sorted = new float[Math.max(n * RECORD_FLOATS, sorted.length * 2)];
        for (int i = 0; i < n; i++) {
            System.arraycopy(records, (int) (k[i] & RECORD_MASK) * RECORD_FLOATS, sorted, i * RECORD_FLOATS, RECORD_FLOATS);
        }
        uploadTo(bonesBuffer, bones, boneCount * 12);
        uploadTo(instancesBuffer, sorted, n * RECORD_FLOATS);
        uploadTo(weightsBuffer, morphWeights, (morphWeightCount + 3) & ~3);

        Minecraft mc = Minecraft.getInstance();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        mc.gameRenderer.overlayTexture().setupOverlayColor();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        ShaderInstance current = null;
        int[] loc = null;
        int calls = 0;
        int i = 0;
        while (i < n) {
            long group = k[i] >>> RECORD_BITS;
            int j = i + 1;
            while (j < n && k[j] >>> RECORD_BITS == group) j++;

            ShaderInstance shader = shaders.get((int) (group >>> 36));
            GpuModelData gpu = models.get((int) (group >>> 22) & 0x3FFF);
            int part = (int) (group >>> 12) & 0x3FF;
            ResourceLocation texture = textures.get((int) (group >>> 1) & 0x7FF);
            boolean cull = (group & 1) != 0;

            RenderSystem.setShaderTexture(0, texture);
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, mc.getWindow());
            Uniform base = shader.getUniform("AnimatioInstanceBase");
            if (base != null) base.set(i);
            shader.apply();
            if (shader != current) {
                loc = bindBufferTextures(shader);
                current = shader;
            }
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            bindMorphs(loc, gpu, part);

            GL30.glBindVertexArray(gpu.vao);
            GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, gpu.indexCount[part], GL11.GL_UNSIGNED_INT,
                    (long) gpu.firstIndex[part] * Integer.BYTES, j - i);
            calls++;
            i = j;
        }
        lastRecords = n;
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

    /** Per-draw morph uniforms (targets 0 disables morphing for the part). */
    private static void bindMorphs(int[] loc, GpuModelData gpu, int part) {
        if (loc[LOC_MORPH_TARGETS] < 0) return;
        if (!gpu.hasMorphs(part)) {
            GL20.glUniform1i(loc[LOC_MORPH_TARGETS], 0);
            return;
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MORPHS_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gpu.morphTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(loc[LOC_MORPH_BASE], gpu.morphBase[part]);
        GL20.glUniform1i(loc[LOC_MORPH_TARGETS], gpu.morphTargets[part]);
        GL20.glUniform1i(loc[LOC_MORPH_VERTICES], gpu.morphVertices[part]);
        GL20.glUniform1i(loc[LOC_MORPH_FIRST], gpu.firstVertex[part]);
        GL20.glUniform1i(loc[LOC_MORPH_WEIGHT_OFFSET], gpu.morphWeightOffset[part]);
    }

    private static int maxTexels() {
        if (maxTexels < 0) maxTexels = Math.max(65536, GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE));
        return maxTexels;
    }

    private static void ensureBuffers() {
        if (bonesBuffer != 0) return;
        bonesBuffer = GL15.glGenBuffers();
        instancesBuffer = GL15.glGenBuffers();
        weightsBuffer = GL15.glGenBuffers();
        bonesTexture = GlStateManager._genTexture();
        instancesTexture = GlStateManager._genTexture();
        weightsTexture = GlStateManager._genTexture();
        attach(bonesTexture, bonesBuffer);
        attach(instancesTexture, instancesBuffer);
        attach(weightsTexture, weightsBuffer);
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
        if (count == 0) return;
        if (upload.capacity() < count) {
            MemoryUtil.memFree(upload);
            upload = MemoryUtil.memAllocFloat(Math.max(count, upload.capacity() * 2));
        }
        upload.clear();
        upload.put(data, 0, Math.min(count, data.length));
        while (upload.position() < count) upload.put(0f);
        upload.flip();
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, upload, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
    }

    private static int[] bindBufferTextures(ShaderInstance shader) {
        int[] loc = LOCATIONS.computeIfAbsent(shader, s -> {
            int p = s.getId();
            return new int[]{
                    GL20.glGetUniformLocation(p, "AnimatioBones"),
                    GL20.glGetUniformLocation(p, "AnimatioInstances"),
                    GL20.glGetUniformLocation(p, "AnimatioMorphWeights"),
                    GL20.glGetUniformLocation(p, "AnimatioMorphs"),
                    GL20.glGetUniformLocation(p, "AnimatioMorphBase"),
                    GL20.glGetUniformLocation(p, "AnimatioMorphTargets"),
                    GL20.glGetUniformLocation(p, "AnimatioMorphVertices"),
                    GL20.glGetUniformLocation(p, "AnimatioMorphFirstVertex"),
                    GL20.glGetUniformLocation(p, "AnimatioMorphWeightOffset")};
        });
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + BONES_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, bonesTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + INSTANCES_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, instancesTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MORPH_WEIGHTS_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, weightsTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(loc[LOC_BONES], BONES_UNIT);
        GL20.glUniform1i(loc[LOC_INSTANCES], INSTANCES_UNIT);
        if (loc[LOC_WEIGHTS] >= 0) GL20.glUniform1i(loc[LOC_WEIGHTS], MORPH_WEIGHTS_UNIT);
        if (loc[LOC_MORPHS] >= 0) GL20.glUniform1i(loc[LOC_MORPHS], MORPHS_UNIT);
        return loc;
    }

    private static void unbindBufferTextures() {
        for (int unit : new int[]{BONES_UNIT, INSTANCES_UNIT, MORPH_WEIGHTS_UNIT, MORPHS_UNIT}) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** Forget cached uniform locations (shaders were reloaded). */
    static void invalidateShaders() {
        LOCATIONS.clear();
    }
}
