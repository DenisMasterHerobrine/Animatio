package dev.denismasterherobrine.animatio.client.render.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.ClientClock;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * GPU skinning that produces ordinary entity vertices, for shader packs.
 * <p>
 * Each part is drawn with its normal render type: its render state is set up, and the draw goes through whatever
 * program {@link RenderSystem#getShader()} returns, which with Iris is the shader pack's own entity program (the
 * shadow pass included). A transform-feedback pass first skins the part straight into a buffer laid out for that
 * program's vertex format: vanilla's {@code NEW_ENTITY} (position, colour, uv, overlay, light, normal), or Iris'
 * extended entity format, which adds the entity/block-entity/item ids ({@code iris_Entity}), the primitive's mid
 * texture coordinate ({@code mc_midTexCoord}) and a tangent ({@code at_tangent}), the data packs use for per-entity
 * effects, parallax and normal mapping. The layout follows the program, not {@code NEW_ENTITY}'s own buffer setup,
 * which Iris redirects to its extended format while a pack is active. The draw happens inside the object's render
 * call, where the pack expects entity geometry.
 * <p>
 * Translucent materials (they need vanilla's quad sorting) and emissive texture layers stay on the CPU path.
 * Render thread only.
 */
public final class FeedbackSkinner {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final int BONES_UNIT = 10, MORPH_WEIGHTS_UNIT = 12, MORPHS_UNIT = 13, FRAMES_UNIT = 14;

    private static int state = -1; // -1 = not set up, 0 = failed, 1 = ready
    private static final Map<VertexFormat, Layout> LAYOUTS = new IdentityHashMap<>();
    private static final List<Layout> LAYOUT_LIST = new ArrayList<>();
    private static int bonesBuffer, bonesTexture, weightsBuffer, weightsTexture, outputBuffer;
    private static long bonesCapacity, weightsCapacity, outputCapacity;
    private static long bonesUsed, weightsUsed, outputUsed;
    private static FloatBuffer upload = MemoryUtil.memAllocFloat(1024);
    private static final float[] POSE = new float[16];
    private static final int[] RENDER_IDS = new int[3];
    private static boolean warnedFailure;
    private static long lastFrame = -1;
    private static final boolean GL_DEBUG = Boolean.getBoolean("animatio.glDebug");
    private static int glErrorsLogged;
    /** Parts drawn in the current frame and in the previous frame that drew any (diagnostics). */
    private static int framePartDraws, lastPartDraws;

    private FeedbackSkinner() {
    }

    /** Diagnostics: part draws of the last frame that used this path, and the vertex layouts in use. */
    public static String stats() {
        StringBuilder sb = new StringBuilder("transform feedback: ").append(lastPartDraws).append(" part draws/frame, layouts [");
        for (int i = 0; i < LAYOUT_LIST.size(); i++) {
            Layout l = LAYOUT_LIST.get(i);
            sb.append(i == 0 ? "" : ", ").append(l.stride).append(" B").append(l.frames ? " +tangent/midTexCoord" : "")
                    .append(l.renderIds ? " +ids" : "");
        }
        return sb.append(']').toString();
    }

    /** True once the shared buffers exist and the vanilla-layout program linked (set up on first use). */
    public static boolean isAvailable() {
        if (state < 0) init();
        return state > 0;
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
            state = 0;
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
            any = true;
            if (type == null) continue;
            if (bonesAt < 0) {
                bonesAt = uploadBones(palette, entries);
                if (weights.length > 0) weightsAt = uploadWeights(weights);
                pose.pose().get(POSE);
            }
            type.setupRenderState();
            try {
                // the program decides the vertex layout: with a pack this is Iris' program for the current pass
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null) continue;
                Layout layout = layout(shader.getVertexFormat());
                if (layout == null) continue;
                int vertices = part.mesh().vertexCount();
                long outVertex = reserveOutput(vertices, layout.stride);
                skin(layout, gpu, i, vertices, outVertex, bonesAt, weightsAt, color, MaterialResolver.light(part, override, light), overlay);
                drawPart(shader, layout, gpu, i, outVertex);
                framePartDraws++;
                if (GL_DEBUG) {
                    int error = GL11.glGetError();
                    if (error != GL11.GL_NO_ERROR && glErrorsLogged++ < 20) {
                        LOGGER.error("GL error 0x{} after transform-feedback draw (layout {})", Integer.toHexString(error), layout.id);
                    }
                }
            } finally {
                type.clearRenderState();
                BufferUploader.invalidate();
            }
        }
        return any;
    }

    // ------------------------------------------------------------------ passes

    private static void skin(Layout layout, GpuModelData gpu, int part, int vertices, long outVertex, long bonesAt, long weightsAt,
                             int argb, int light, int overlay) {
        GlStateManager._glUseProgram(layout.program);
        GL20.glUniformMatrix4fv(layout.locPose, false, POSE);
        GL20.glUniform4f(layout.locTint, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
        GL20.glUniform1i(layout.locOverlay, overlay);
        GL20.glUniform1i(layout.locLight, light);
        GL20.glUniform1i(layout.locPalette, (int) (bonesAt / 3));
        GL20.glUniform1i(layout.locMorphWeightsAt, (int) Math.max(0, weightsAt));
        if (gpu.hasMorphs(part) && weightsAt >= 0) {
            GL20.glUniform1i(layout.locMorphBase, gpu.morphBase[part]);
            GL20.glUniform1i(layout.locMorphTargets, gpu.morphTargets[part]);
            GL20.glUniform1i(layout.locMorphVertices, gpu.morphVertices[part]);
            GL20.glUniform1i(layout.locMorphFirst, gpu.firstVertex[part]);
            GL20.glUniform1i(layout.locMorphWeightOffset, gpu.morphWeightOffset[part]);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MORPHS_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gpu.morphTexture);
        } else {
            GL20.glUniform1i(layout.locMorphTargets, 0);
        }
        if (layout.frames) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + FRAMES_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gpu.framesTexture());
        }
        if (layout.renderIds) {
            IrisCompat.currentRenderIds(RENDER_IDS);
            GL20.glUniform3i(layout.locRenderIds, RENDER_IDS[0], RENDER_IDS[1], RENDER_IDS[2]);
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + BONES_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, bonesTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MORPH_WEIGHTS_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, weightsTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);

        GL30.glBindVertexArray(gpu.vao);
        GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, outputBuffer, outVertex * layout.stride, (long) vertices * layout.stride);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        GL30.glBeginTransformFeedback(GL11.GL_POINTS);
        GL11.glDrawArrays(GL11.GL_POINTS, gpu.firstVertex[part], vertices);
        GL30.glEndTransformFeedback();
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
        GL30.glBindVertexArray(0);
        GlStateManager._glUseProgram(0);
    }

    /** Draw the skinned vertices through the render type's state and program (the shader pack's, with Iris). */
    private static void drawPart(ShaderInstance shader, Layout layout, GpuModelData gpu, int part, long outVertex) {
        Minecraft mc = Minecraft.getInstance();
        shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), mc.getWindow());
        shader.apply();
        GL30.glBindVertexArray(drawVao(gpu, layout));
        int baseVertex = (int) (outVertex - gpu.firstVertex[part]);
        GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, gpu.indexCount[part], GL11.GL_UNSIGNED_INT,
                (long) gpu.firstIndex[part] * Integer.BYTES, baseVertex);
        GL30.glBindVertexArray(0);
        shader.clear();
    }

    /**
     * A VAO reading the output buffer in a layout, with the model's index buffer. Attribute {@code i} is the
     * {@code i}-th element of the program's vertex format, the location {@link ShaderInstance} binds it to.
     */
    private static int drawVao(GpuModelData gpu, Layout layout) {
        int[] vaos = gpu.feedbackVaos;
        if (layout.id < vaos.length && vaos[layout.id] != 0) return vaos[layout.id];
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outputBuffer);
        for (int k = 0; k < layout.attributes.length; k++) {
            GlStateManager._enableVertexAttribArray(layout.attributes[k]);
            layout.elements[k].setupBufferState(layout.attributes[k], layout.offsets[k], layout.stride);
        }
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        if (layout.id >= vaos.length) gpu.feedbackVaos = vaos = Arrays.copyOf(vaos, layout.id + 1);
        vaos[layout.id] = vao;
        return vao;
    }

    // ------------------------------------------------------------------ buffers

    /** Buffers are rings reset once per frame; growing orphans the old storage (nothing pending reads it later). */
    private static void beginFrame() {
        long frame = ClientClock.frame();
        if (frame == lastFrame) return;
        if (lastFrame >= 0) lastPartDraws = framePartDraws;
        framePartDraws = 0;
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

    /** @return the first output vertex, in units of {@code stride} bytes */
    private static long reserveOutput(int vertices, int stride) {
        long bytes = (long) vertices * stride;
        long first = (outputUsed + stride - 1) / stride;
        if (first * stride + bytes > outputCapacity) {
            outputCapacity = Math.max(outputCapacity * 2, Math.max(bytes, 1L << 22));
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outputBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, outputCapacity, GL15.GL_STREAM_COPY);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            first = 0;
        }
        outputUsed = first * stride + bytes;
        return first;
    }

    // ------------------------------------------------------------------ layouts

    /**
     * Where the skinned vertices of one vertex format go: the format's elements this pass can fill, each with its
     * attribute location, its offset and the transform-feedback varying that writes it. Elements it cannot fill are
     * left out (their attributes keep the default value). Every varying is a whole number of 32-bit words, so
     * offsets stay aligned even where the format packs elements tighter.
     */
    private static final class Layout {
        final int id;
        final int stride;
        final int[] attributes;
        final VertexFormatElement[] elements;
        final long[] offsets;
        final boolean frames, renderIds;
        final int program;
        final int locPose, locTint, locOverlay, locLight, locPalette, locMorphWeightsAt, locMorphBase, locMorphTargets,
                locMorphVertices, locMorphFirst, locMorphWeightOffset, locRenderIds;

        Layout(int id, int stride, int[] attributes, VertexFormatElement[] elements, long[] offsets, boolean frames,
               boolean renderIds, int program) {
            this.id = id;
            this.stride = stride;
            this.attributes = attributes;
            this.elements = elements;
            this.offsets = offsets;
            this.frames = frames;
            this.renderIds = renderIds;
            this.program = program;
            locPose = GL20.glGetUniformLocation(program, "Pose");
            locTint = GL20.glGetUniformLocation(program, "Tint");
            locOverlay = GL20.glGetUniformLocation(program, "Overlay");
            locLight = GL20.glGetUniformLocation(program, "Light");
            locPalette = GL20.glGetUniformLocation(program, "Palette");
            locMorphWeightsAt = GL20.glGetUniformLocation(program, "MorphWeightsAt");
            locMorphBase = GL20.glGetUniformLocation(program, "MorphBase");
            locMorphTargets = GL20.glGetUniformLocation(program, "MorphTargets");
            locMorphVertices = GL20.glGetUniformLocation(program, "MorphVertices");
            locMorphFirst = GL20.glGetUniformLocation(program, "MorphFirstVertex");
            locMorphWeightOffset = GL20.glGetUniformLocation(program, "MorphWeightOffset");
            locRenderIds = GL20.glGetUniformLocation(program, "RenderIds");
        }
    }

    /** One element this pass can write: its varying and the bytes the varying takes in the output. */
    private record Output(String varying, int bytes, boolean frames, boolean renderIds) {
    }

    private static @Nullable Output output(VertexFormatElement e, String name) {
        if (e.equals(VertexFormatElement.POSITION)) return new Output("tf_Position", 12, false, false);
        if (e.equals(VertexFormatElement.COLOR)) return new Output("tf_Color", 4, false, false);
        if (e.equals(VertexFormatElement.UV0)) return new Output("tf_UV0", 8, false, false);
        if (e.equals(VertexFormatElement.UV1)) return new Output("tf_Overlay", 4, false, false);
        if (e.equals(VertexFormatElement.UV2)) return new Output("tf_Light", 4, false, false);
        if (e.equals(VertexFormatElement.NORMAL)) return new Output("tf_Normal", 4, false, false);
        // Iris' extended entity format (IrisVertexFormats.ENTITY)
        VertexFormatElement.Type type = e.type();
        if (name.equals("iris_Entity") && e.count() == 3
                && (type == VertexFormatElement.Type.USHORT || type == VertexFormatElement.Type.SHORT)) {
            return new Output("tf_Entity", 8, false, true);
        }
        if (name.equals("mc_midTexCoord") && e.count() == 2 && type == VertexFormatElement.Type.FLOAT) {
            return new Output("tf_MidTexCoord", 8, true, false);
        }
        if (name.equals("at_tangent") && e.count() == 4 && type == VertexFormatElement.Type.BYTE) {
            return new Output("tf_Tangent", 4, true, false);
        }
        return null;
    }

    /** The layout for a program's vertex format (linked on first use), or {@code null} if it has no position. */
    private static @Nullable Layout layout(VertexFormat format) {
        Layout cached = LAYOUTS.get(format);
        if (cached != null || LAYOUTS.containsKey(format)) return cached;
        Layout layout = createLayout(format);
        LAYOUTS.put(format, layout);
        return layout;
    }

    private static @Nullable Layout createLayout(VertexFormat format) {
        List<VertexFormatElement> elements = format.getElements();
        List<String> names = format.getElementAttributeNames();
        List<String> varyings = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int[] attributes = new int[elements.size()];
        VertexFormatElement[] used = new VertexFormatElement[elements.size()];
        long[] offsets = new long[elements.size()];
        int count = 0, offset = 0;
        boolean position = false, frames = false, renderIds = false;
        for (int i = 0; i < elements.size(); i++) {
            Output out = output(elements.get(i), names.get(i));
            if (out == null) {
                skipped.add(names.get(i));
                continue;
            }
            position |= elements.get(i).equals(VertexFormatElement.POSITION);
            frames |= out.frames;
            renderIds |= out.renderIds;
            varyings.add(out.varying);
            attributes[count] = i;
            used[count] = elements.get(i);
            offsets[count] = offset;
            offset += out.bytes;
            count++;
        }
        if (!position) {
            LOGGER.warn("Animatio cannot skin into vertex format {} (no position); models drawn with it are skipped", format);
            return null;
        }
        int program = link(varyings.toArray(String[]::new));
        if (program == 0) throw new IllegalStateException("transform-feedback program for " + format + " did not link");
        Layout layout = new Layout(LAYOUT_LIST.size(), offset, Arrays.copyOf(attributes, count), Arrays.copyOf(used, count),
                Arrays.copyOf(offsets, count), frames, renderIds, program);
        LAYOUT_LIST.add(layout);
        LOGGER.info("Animatio transform-feedback layout {}: {} bytes per vertex, attributes {}{}", layout.id, offset, varyings,
                skipped.isEmpty() ? "" : " (left at defaults: " + skipped + ")");
        return layout;
    }

    // ------------------------------------------------------------------ program

    private static void init() {
        state = 0;
        try {
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
            state = 1;
            layout(DefaultVertexFormat.NEW_ENTITY); // compile check: fall back to the CPU path early if this fails
            LOGGER.info("Animatio transform-feedback skinning ready (GPU skinning with shader packs)");
        } catch (RuntimeException e) {
            LOGGER.error("Could not set up transform-feedback skinning", e);
            state = 0;
        }
    }

    /** Compile and link the skinning program capturing {@code varyings}, interleaved in that order; 0 on failure. */
    private static int link(String[] varyings) {
        int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vs, SOURCE);
        GL20.glCompileShader(vs);
        if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Animatio transform-feedback shader failed to compile: {}", GL20.glGetShaderInfoLog(vs));
            GL20.glDeleteShader(vs);
            return 0;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glBindAttribLocation(p, GpuShaders.ATTR_POSITION, "Position");
        GL20.glBindAttribLocation(p, GpuShaders.ATTR_UV0, "UV0");
        GL20.glBindAttribLocation(p, GpuShaders.ATTR_NORMAL, "Normal");
        GL20.glBindAttribLocation(p, GpuShaders.ATTR_JOINTS, "Joints");
        GL20.glBindAttribLocation(p, GpuShaders.ATTR_WEIGHTS, "Weights");
        GL20.glBindAttribLocation(p, GpuShaders.ATTR_COLOR, "Color");
        GL30.glTransformFeedbackVaryings(p, varyings, GL30.GL_INTERLEAVED_ATTRIBS);
        GL20.glLinkProgram(p);
        GL20.glDetachShader(p, vs);
        GL20.glDeleteShader(vs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Animatio transform-feedback program failed to link: {}", GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            return 0;
        }
        GlStateManager._glUseProgram(p);
        setSampler(p, "Bones", BONES_UNIT);
        setSampler(p, "MorphWeights", MORPH_WEIGHTS_UNIT);
        setSampler(p, "Morphs", MORPHS_UNIT);
        setSampler(p, "Frames", FRAMES_UNIT);
        GlStateManager._glUseProgram(0);
        return p;
    }

    private static void setSampler(int program, String name, int unit) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location >= 0) GL20.glUniform1i(location, unit);
    }

    /** Forget per-frame ring positions. */
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
            // per vertex: tangent (xyz, handedness), then the mid texture coordinate (xy)
            uniform samplerBuffer Frames;
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
            // entity, block entity and item ids for Iris' iris_Entity attribute
            uniform ivec3 RenderIds;

            out vec3 tf_Position;
            flat out uint tf_Color;
            out vec2 tf_UV0;
            flat out uint tf_Overlay;
            flat out uint tf_Light;
            flat out uint tf_Normal;
            flat out uvec2 tf_Entity;
            out vec2 tf_MidTexCoord;
            flat out uint tf_Tangent;

            mat4 bone(int index) {
                int b = (Palette + index) * 3;
                return transpose(mat4(texelFetch(Bones, b), texelFetch(Bones, b + 1), texelFetch(Bones, b + 2), vec4(0.0, 0.0, 0.0, 1.0)));
            }

            uint unorm8(float v) {
                return uint(clamp(v, 0.0, 1.0) * 255.0 + 0.5);
            }

            // as Minecraft and Iris pack normals: truncated towards zero
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
                mat3 m3 = mat3(m);
                tf_Position = (m * vec4(position, 1.0)).xyz;
                vec3 n = m3 * normal;
                float len = length(n);
                n = len > 1e-12 ? n / len : vec3(0.0, 1.0, 0.0);
                vec4 c = Color * Tint;
                tf_Color = unorm8(c.r) | (unorm8(c.g) << 8) | (unorm8(c.b) << 16) | (unorm8(c.a) << 24);
                tf_UV0 = UV0;
                tf_Overlay = uint(Overlay);
                tf_Light = uint(Light);
                tf_Normal = snorm8(n.x) | (snorm8(n.y) << 8) | (snorm8(n.z) << 16);

                tf_Entity = uvec2((uint(RenderIds.x) & 0xFFFFu) | ((uint(RenderIds.y) & 0xFFFFu) << 16), uint(RenderIds.z) & 0xFFFFu);
                vec4 frame = texelFetch(Frames, gl_VertexID * 2);
                tf_MidTexCoord = texelFetch(Frames, gl_VertexID * 2 + 1).xy;
                // skin the bind-pose tangent like the normal, then keep it in the (possibly morphed) surface plane
                vec3 t = m3 * frame.xyz;
                t -= n * dot(n, t);
                float tl = length(t);
                if (tl > 1e-6) t /= tl;
                else t = normalize(abs(n.x) < 0.9 ? vec3(0.0, n.z, -n.y) : vec3(-n.z, 0.0, n.x));
                float handedness = determinant(m3) < 0.0 ? -frame.w : frame.w;
                tf_Tangent = snorm8(t.x) | (snorm8(t.y) << 8) | (snorm8(t.z) << 16) | (snorm8(handedness) << 24);
            }
            """;
}
