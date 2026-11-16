package dev.denismasterherobrine.animatio.client.render.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.MaterialResolver;
import dev.denismasterherobrine.animatio.client.render.RenderStats;
import dev.denismasterherobrine.animatio.data.ModelDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * GPU skinning that produces ordinary entity vertices, for shader packs.
 * <p>
 * A transform-feedback pass skins a part on the GPU and writes the result straight into a buffer in the
 * {@code NEW_ENTITY} vertex layout (position, colour, uv, overlay, light, normal: 36 bytes). That buffer is then
 * drawn with the part's normal render type: its render state is set up, and the draw goes through whatever program
 * {@link RenderSystem#getShader()} returns, which with Iris/Oculus is the shader pack's own entity program (including
 * shadow passes). The draw happens inside the object's render call, where the pack expects entity geometry.
 * <p>
 * Translucent materials (they need vanilla's quad sorting) and emissive texture layers stay on the CPU path.
 * Render thread only.
 */
public final class FeedbackSkinner {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final int VERTEX_SIZE = 36;
    private static final int BONES_UNIT = 10, MORPH_WEIGHTS_UNIT = 12, MORPHS_UNIT = 13;
    private static final String[] VARYINGS = {"tf_Position", "tf_Color", "tf_UV0", "tf_Overlay", "tf_Light", "tf_Normal"};

    private static int program = -1; // -1 = not tried, 0 = failed
    private static int locBones, locWeights, locMorphs, locPose, locTint, locOverlay, locLight, locPalette, locMorphWeightsAt,
            locMorphBase, locMorphTargets, locMorphVertices, locMorphFirst, locMorphWeightOffset;
    private static int bonesBuffer, bonesTexture, weightsBuffer, weightsTexture, outputBuffer;
    private static long bonesCapacity, weightsCapacity, outputCapacity;
    private static long bonesUsed, weightsUsed, outputUsed;
    private static FloatBuffer upload = MemoryUtil.memAllocFloat(1024);
    private static final float[] POSE = new float[16];
    private static boolean warnedFailure;
    private static long lastFrame = -1;

    private FeedbackSkinner() {
    }

    /** True once the program compiled (compiled on first use). */
    public static boolean isAvailable() {
        if (program < 0) init();
        return program > 0;
    }

    /** Whether the feedback path draws a part's base layer (translucent materials stay on the CPU). */
    public static boolean drawsBase(GltfModel.Part part, int color) {
        ModelDefinition.RenderTypeKind kind = part.kind();
        if (kind == ModelDefinition.RenderTypeKind.TRANSLUCENT) return false;
        return kind == ModelDefinition.RenderTypeKind.EMISSIVE || (color >>> 24) == 255;
    }

    /**
     * Skin and draw the opaque and cutout parts of an instance.
     *
     * @return false if nothing was drawn here (the caller draws everything on the CPU)
     */
    public static boolean render(GltfModel model, AnimatedInstance instance, PoseStack.Pose pose, int light, int overlay, int tint) {
        if (!isAvailable()) return false;
        GpuModelData gpu = GpuModelData.get(model);
        if (gpu == null) return false;
        float[] palette = instance.palette();
        int entries = model.paletteSize();
        if (palette.length < entries * 16) return false;
        long t0 = RenderStats.ENABLED ? System.nanoTime() : 0L;
        beginFrame();
        try {
            return draw(model, gpu, instance, pose, palette, entries, light, overlay, tint);
        } catch (RuntimeException e) {
            if (!warnedFailure) {
                warnedFailure = true;
                LOGGER.error("Transform-feedback skinning failed; using the CPU path from now on", e);
            }
            program = 0;
            return false;
        } finally {
            if (RenderStats.ENABLED) RenderStats.gpuFlushNanos += System.nanoTime() - t0;
        }
    }

    private static boolean draw(GltfModel model, GpuModelData gpu, AnimatedInstance instance, PoseStack.Pose pose, float[] palette,
                                int entries, int light, int overlay, int tint) {
        List<GltfModel.Part> parts = model.parts();
        boolean any = false;
        long bonesAt = -1, weightsAt = -1;
        float[] weights = instance.morphWeights();
        for (int i = 0, n = parts.size(); i < n; i++) {
            GltfModel.Part part = parts.get(i);
            MaterialOverride override = instance.override(part.materialName());
            int color = MaterialResolver.color(part, override, tint);
            if (!drawsBase(part, color)) continue;
            RenderType type = MaterialResolver.renderType(part, override, color, instance.skinTexture());
            if (type == null) {
                any = true;
                continue;
            }
            if (bonesAt < 0) {
                bonesAt = uploadBones(palette, entries);
                if (weights.length > 0) weightsAt = uploadWeights(weights);
                pose.pose().get(POSE);
            }
            int vertices = part.mesh().vertexCount();
            long outVertex = reserveOutput(vertices);
            skin(gpu, i, vertices, outVertex, bonesAt, weightsAt, color, MaterialResolver.light(part, override, light), overlay);
            drawPart(type, gpu, i, outVertex);
            any = true;
        }
        return any;
    }

    // ------------------------------------------------------------------ passes

    private static void skin(GpuModelData gpu, int part, int vertices, long outVertex, long bonesAt, long weightsAt,
                             int argb, int light, int overlay) {
        GlStateManager._glUseProgram(program);
        GL20.glUniformMatrix4fv(locPose, false, POSE);
        GL20.glUniform4f(locTint, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
        GL20.glUniform1i(locOverlay, overlay);
        GL20.glUniform1i(locLight, light);
        GL20.glUniform1i(locPalette, (int) (bonesAt / 3));
        GL20.glUniform1i(locMorphWeightsAt, (int) Math.max(0, weightsAt));
        if (gpu.hasMorphs(part) && weightsAt >= 0) {
            GL20.glUniform1i(locMorphBase, gpu.morphBase[part]);
            GL20.glUniform1i(locMorphTargets, gpu.morphTargets[part]);
            GL20.glUniform1i(locMorphVertices, gpu.morphVertices[part]);
            GL20.glUniform1i(locMorphFirst, gpu.firstVertex[part]);
            GL20.glUniform1i(locMorphWeightOffset, gpu.morphWeightOffset[part]);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MORPHS_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gpu.morphTexture);
        } else {
            GL20.glUniform1i(locMorphTargets, 0);
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + BONES_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, bonesTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MORPH_WEIGHTS_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, weightsTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);

        GL30.glBindVertexArray(gpu.vao);
        GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, outputBuffer, outVertex * VERTEX_SIZE, (long) vertices * VERTEX_SIZE);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        GL30.glBeginTransformFeedback(GL11.GL_POINTS);
        GL11.glDrawArrays(GL11.GL_POINTS, gpu.firstVertex[part], vertices);
        GL30.glEndTransformFeedback();
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
        GL30.glBindVertexArray(0);
        GlStateManager._glUseProgram(0);
        BufferUploader.invalidate();
    }

    /** Draw the skinned vertices through the render type's state and current program (the shader pack's, with Iris). */
    private static void drawPart(RenderType type, GpuModelData gpu, int part, long outVertex) {
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            Minecraft mc = Minecraft.getInstance();
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), mc.getWindow());
            shader.apply();
            GL30.glBindVertexArray(drawVao(gpu));
            int baseVertex = (int) (outVertex - gpu.firstVertex[part]);
            GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, gpu.indexCount[part], GL11.GL_UNSIGNED_INT,
                    (long) gpu.firstIndex[part] * Integer.BYTES, baseVertex);
            GL30.glBindVertexArray(0);
            shader.clear();
        }
        type.clearRenderState();
        BufferUploader.invalidate();
    }

    /** A VAO reading NEW_ENTITY vertices from the output buffer, with the model's index buffer. */
    private static int drawVao(GpuModelData gpu) {
        if (gpu.feedbackVao != 0) return gpu.feedbackVao;
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outputBuffer);
        DefaultVertexFormat.NEW_ENTITY.setupBufferState();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        gpu.feedbackVao = vao;
        return vao;
    }

    // ------------------------------------------------------------------ buffers

    /** Buffers are rings reset once per frame; growing orphans the old storage (nothing pending reads it later). */
    private static void beginFrame() {
        long frame = dev.denismasterherobrine.animatio.client.render.ClientClock.frame();
        if (frame == lastFrame) return;
        lastFrame = frame;
        bonesUsed = weightsUsed = outputUsed = 0;
    }

    /** @return the first texel of the palette (3 texels per bone) */
    private static long uploadBones(float[] palette, int entries) {
        int floats = entries * 12;
        if (upload.capacity() < floats) {
            MemoryUtil.memFree(upload);
            upload = MemoryUtil.memAllocFloat(Math.max(floats, upload.capacity() * 2));
        }
        upload.clear();
        for (int i = 0, m = 0; i < entries; i++, m += 16) {
            upload.put(palette[m]).put(palette[m + 4]).put(palette[m + 8]).put(palette[m + 12]);
            upload.put(palette[m + 1]).put(palette[m + 5]).put(palette[m + 9]).put(palette[m + 13]);
            upload.put(palette[m + 2]).put(palette[m + 6]).put(palette[m + 10]).put(palette[m + 14]);
        }
        upload.flip();
        long bytes = (long) floats * 4;
        if (bonesUsed + bytes > bonesCapacity) {
            bonesCapacity = Math.max(bonesCapacity * 2, Math.max(bytes, 1 << 20));
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, bonesBuffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, bonesCapacity, GL15.GL_STREAM_DRAW);
            bonesUsed = 0;
        }
        long at = bonesUsed;
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, bonesBuffer);
        GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, at, upload);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        bonesUsed += bytes;
        return at / 16; // texels
    }

    /** @return the index of the first weight */
    private static long uploadWeights(float[] weights) {
        int floats = (weights.length + 3) & ~3;
        if (upload.capacity() < floats) {
            MemoryUtil.memFree(upload);
            upload = MemoryUtil.memAllocFloat(Math.max(floats, upload.capacity() * 2));
        }
        upload.clear();
        upload.put(weights);
        while (upload.position() < floats) upload.put(0f);
        upload.flip();
        long bytes = (long) floats * 4;
        if (weightsUsed + bytes > weightsCapacity) {
            weightsCapacity = Math.max(weightsCapacity * 2, Math.max(bytes, 1 << 16));
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, weightsBuffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, weightsCapacity, GL15.GL_STREAM_DRAW);
            weightsUsed = 0;
        }
        long at = weightsUsed;
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, weightsBuffer);
        GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, at, upload);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        weightsUsed += bytes;
        return at / 4;
    }

    /** @return the first output vertex */
    private static long reserveOutput(int vertices) {
        long bytes = (long) vertices * VERTEX_SIZE;
        if (outputUsed + bytes > outputCapacity) {
            outputCapacity = Math.max(outputCapacity * 2, Math.max(bytes, (long) VERTEX_SIZE << 16));
            outputCapacity -= outputCapacity % VERTEX_SIZE;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outputBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, outputCapacity, GL15.GL_STREAM_COPY);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            outputUsed = 0;
        }
        long at = outputUsed / VERTEX_SIZE;
        outputUsed += bytes;
        return at;
    }

    // ------------------------------------------------------------------ program

    private static void init() {
        program = 0;
        try {
            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, SOURCE);
            GL20.glCompileShader(vs);
            if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                LOGGER.error("Animatio transform-feedback shader failed to compile: {}", GL20.glGetShaderInfoLog(vs));
                GL20.glDeleteShader(vs);
                return;
            }
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glBindAttribLocation(p, GpuShaders.ATTR_POSITION, "Position");
            GL20.glBindAttribLocation(p, GpuShaders.ATTR_UV0, "UV0");
            GL20.glBindAttribLocation(p, GpuShaders.ATTR_NORMAL, "Normal");
            GL20.glBindAttribLocation(p, GpuShaders.ATTR_JOINTS, "Joints");
            GL20.glBindAttribLocation(p, GpuShaders.ATTR_WEIGHTS, "Weights");
            GL20.glBindAttribLocation(p, GpuShaders.ATTR_COLOR, "Color");

            GL30.glTransformFeedbackVaryings(p, VARYINGS, GL30.GL_INTERLEAVED_ATTRIBS);
            GL20.glLinkProgram(p);
            GL20.glDetachShader(p, vs);
            GL20.glDeleteShader(vs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                LOGGER.error("Animatio transform-feedback program failed to link: {}", GL20.glGetProgramInfoLog(p));
                GL20.glDeleteProgram(p);
                return;
            }
            locBones = GL20.glGetUniformLocation(p, "Bones");
            locWeights = GL20.glGetUniformLocation(p, "MorphWeights");
            locMorphs = GL20.glGetUniformLocation(p, "Morphs");
            locPose = GL20.glGetUniformLocation(p, "Pose");
            locTint = GL20.glGetUniformLocation(p, "Tint");
            locOverlay = GL20.glGetUniformLocation(p, "Overlay");
            locLight = GL20.glGetUniformLocation(p, "Light");
            locPalette = GL20.glGetUniformLocation(p, "Palette");
            locMorphWeightsAt = GL20.glGetUniformLocation(p, "MorphWeightsAt");
            locMorphBase = GL20.glGetUniformLocation(p, "MorphBase");
            locMorphTargets = GL20.glGetUniformLocation(p, "MorphTargets");
            locMorphVertices = GL20.glGetUniformLocation(p, "MorphVertices");
            locMorphFirst = GL20.glGetUniformLocation(p, "MorphFirstVertex");
            locMorphWeightOffset = GL20.glGetUniformLocation(p, "MorphWeightOffset");
            GlStateManager._glUseProgram(p);
            GL20.glUniform1i(locBones, BONES_UNIT);
            if (locWeights >= 0) GL20.glUniform1i(locWeights, MORPH_WEIGHTS_UNIT);
            if (locMorphs >= 0) GL20.glUniform1i(locMorphs, MORPHS_UNIT);
            GlStateManager._glUseProgram(0);

            bonesBuffer = GL15.glGenBuffers();
            weightsBuffer = GL15.glGenBuffers();
            outputBuffer = GL15.glGenBuffers();
            bonesTexture = GlStateManager._genTexture();
            weightsTexture = GlStateManager._genTexture();
            for (int[] pair : new int[][]{{bonesTexture, bonesBuffer}, {weightsTexture, weightsBuffer}}) {
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, pair[1]);
                GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, 1 << 16, GL15.GL_STREAM_DRAW);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, pair[0]);
                GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, pair[1]);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            }
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
            bonesCapacity = weightsCapacity = 1 << 16;
            program = p;
            LOGGER.info("Animatio transform-feedback skinning ready (GPU skinning with shader packs)");
        } catch (RuntimeException e) {
            LOGGER.error("Could not set up transform-feedback skinning", e);
            program = 0;
        }
    }

    /** Forget GL objects (context loss is not expected; used on resource reload of shaders). */
    public static void reset() {
        lastFrame = -1;
    }

    private static final String SOURCE = """
            #version 150

            in vec3 Position;
            in vec2 UV0;
            in vec3 Normal;
            in ivec4 Joints;
            in vec4 Weights;
            in vec4 Color;

            uniform samplerBuffer Bones;
            uniform samplerBuffer MorphWeights;
            uniform samplerBuffer Morphs;
            uniform mat4 Pose;
            uniform vec4 Tint;
            uniform int Overlay;
            uniform int Light;
            uniform int Palette;
            uniform int MorphWeightsAt;
            uniform int MorphBase;
            uniform int MorphTargets;
            uniform int MorphVertices;
            uniform int MorphFirstVertex;
            uniform int MorphWeightOffset;

            out vec3 tf_Position;
            flat out uint tf_Color;
            out vec2 tf_UV0;
            flat out uint tf_Overlay;
            flat out uint tf_Light;
            flat out uint tf_Normal;

            mat4 bone(int index) {
                int b = (Palette + index) * 3;
                return transpose(mat4(texelFetch(Bones, b), texelFetch(Bones, b + 1), texelFetch(Bones, b + 2), vec4(0.0, 0.0, 0.0, 1.0)));
            }

            uint unorm8(float v) {
                return uint(clamp(v, 0.0, 1.0) * 255.0 + 0.5);
            }

            uint snorm8(float v) {
                return uint(int(clamp(v, -1.0, 1.0) * 127.0)) & 255u;
            }

            void main() {
                vec3 position = Position;
                vec3 normal = Normal;
                if (MorphTargets > 0) {
                    int vertex = gl_VertexID - MorphFirstVertex;
                    int first = MorphWeightsAt + MorphWeightOffset;
                    for (int t = 0; t < MorphTargets; t++) {
                        int wi = first + t;
                        float w = texelFetch(MorphWeights, wi / 4)[wi % 4];
                        if (w == 0.0) continue;
                        int texel = MorphBase + (t * MorphVertices + vertex) * 2;
                        position += w * texelFetch(Morphs, texel).xyz;
                        normal += w * texelFetch(Morphs, texel + 1).xyz;
                    }
                }
                mat4 skin = bone(Joints.x) * Weights.x;
                if (Weights.y > 0.0) skin += bone(Joints.y) * Weights.y;
                if (Weights.z > 0.0) skin += bone(Joints.z) * Weights.z;
                if (Weights.w > 0.0) skin += bone(Joints.w) * Weights.w;
                mat4 m = Pose * skin;
                tf_Position = (m * vec4(position, 1.0)).xyz;
                vec3 n = mat3(m) * normal;
                float len = length(n);
                n = len > 1e-12 ? n / len : vec3(0.0, 1.0, 0.0);
                vec4 c = Color * Tint;
                tf_Color = unorm8(c.r) | (unorm8(c.g) << 8) | (unorm8(c.b) << 16) | (unorm8(c.a) << 24);
                tf_UV0 = UV0;
                tf_Overlay = uint(Overlay);
                tf_Light = uint(Light);
                tf_Normal = snorm8(n.x) | (snorm8(n.y) << 8) | (snorm8(n.z) << 16);
            }
            """;
}
