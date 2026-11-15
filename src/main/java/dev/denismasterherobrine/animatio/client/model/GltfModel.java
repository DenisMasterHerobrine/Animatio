package dev.denismasterherobrine.animatio.client.model;

import dev.denismasterherobrine.animatio.anim.math.Mat4;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.model.Pose;
import dev.denismasterherobrine.animatio.anim.model.Skeleton;
import dev.denismasterherobrine.animatio.data.ModelDefinition;
import dev.denismasterherobrine.animatio.gltf.MaterialData;
import dev.denismasterherobrine.animatio.gltf.MeshData;
import dev.denismasterherobrine.animatio.gltf.ModelData;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * A model ready to render on the client: geometry, materials with uploaded textures, animation data and bounds.
 * Instances are replaced, never mutated, on resource reload. Everything except {@link #gpuData()} is immutable and
 * safe to read from pose-evaluation worker threads.
 */
public final class GltfModel {
    /**
     * One mesh primitive with its resolved material.
     *
     * @param texture         base colour texture (a white texture when the material has none)
     * @param emissiveTexture emissive map drawn as an extra full-bright layer, or {@code null}
     * @param color           ARGB base colour factor
     * @param quads           the mesh's triangles as Minecraft quads, 4 indices each (see {@link QuadPacker})
     */
    public record Part(MeshData mesh, String materialName, int materialIndex, ModelDefinition.RenderTypeKind kind,
                       ResourceLocation texture, @Nullable ResourceLocation emissiveTexture, int color,
                       boolean doubleSided, boolean fullBright, @Nullable ResourceLocation shader, int[] quads) {
    }

    private final ResourceLocation id;
    private final ModelData data;
    private final AnimationSet animations;
    private final ModelDefinition definition;
    private final List<Part> parts;
    private final List<ResourceLocation> ownedTextures;
    private final float[] bounds;
    private final Matrix4f rootTransform;
    private final int maxVertices;
    private final int version;
    private final float[] restPalette;
    private final float[] cullCylinder;
    private @Nullable Object gpuData;

    GltfModel(ResourceLocation id, ModelData data, AnimationSet animations, ModelDefinition definition, List<Part> parts,
              List<ResourceLocation> ownedTextures, float[] bounds, int version) {
        this.id = id;
        this.data = data;
        this.animations = animations;
        this.definition = definition;
        this.parts = List.copyOf(parts);
        this.ownedTextures = List.copyOf(ownedTextures);
        this.bounds = bounds;
        this.version = version;
        ModelDefinition.RenderDef r = definition.render();
        this.rootTransform = new Matrix4f()
                .translate(r.offset())
                .rotateZYX((float) Math.toRadians(r.rotation().z), (float) Math.toRadians(r.rotation().y), (float) Math.toRadians(r.rotation().x))
                .scale(r.scale());
        int max = 0;
        for (Part p : parts) max = Math.max(max, p.mesh().vertexCount());
        this.maxVertices = max;
        this.restPalette = computeRestPalette(data);
        this.cullCylinder = computeCullCylinder(bounds, rootTransform);
    }

    private static float[] computeRestPalette(ModelData data) {
        Skeleton sk = data.skeleton();
        Pose pose = new Pose(sk.size());
        pose.setRest(sk);
        float[] globals = new float[sk.size() * 16];
        Mat4.computeGlobals(sk, pose, globals);
        float[] palette = new float[data.paletteSize() * 16];
        data.computePalette(globals, palette);
        return palette;
    }

    private static float[] computeCullCylinder(float[] bounds, Matrix4f root) {
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, r2 = 0f;
        Vector3f v = new Vector3f();
        for (int corner = 0; corner < 8; corner++) {
            v.set((corner & 1) == 0 ? bounds[0] : bounds[3], (corner & 2) == 0 ? bounds[1] : bounds[4], (corner & 4) == 0 ? bounds[2] : bounds[5]);
            root.transformPosition(v);
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
            r2 = Math.max(r2, v.x * v.x + v.z * v.z);
        }
        return new float[]{minY, maxY, (float) Math.sqrt(r2)};
    }

    public ResourceLocation id() {
        return id;
    }

    public ModelData data() {
        return data;
    }

    public AnimationSet animations() {
        return animations;
    }

    public ModelDefinition definition() {
        return definition;
    }

    public List<Part> parts() {
        return parts;
    }

    List<ResourceLocation> ownedTextures() {
        return ownedTextures;
    }

    /** Conservative model-space bounds over every clip: min xyz, max xyz (before the root transform). */
    public float[] bounds() {
        return bounds;
    }

    /** Offset, rotation and scale from the model definition, applied before the skeleton. */
    public Matrix4f rootTransform() {
        return rootTransform;
    }

    public int maxVertices() {
        return maxVertices;
    }

    public int paletteSize() {
        return data.paletteSize();
    }

    /** Unique per load; render caches compare it to notice reloads. */
    public int version() {
        return version;
    }

    public MaterialData material(int index) {
        return data.material(index);
    }

    /** Palette of the rest pose (used before a controller has a pose). Do not modify. */
    public float[] restPalette() {
        return restPalette;
    }

    /**
     * Bounds after the root transform as a vertical cylinder around the origin, valid for any yaw:
     * {@code [minY, maxY, radius]}. Do not modify.
     */
    public float[] cullCylinder() {
        return cullCylinder;
    }

    /** GPU-side buffers created lazily by the GPU renderer (render thread only). */
    public @Nullable Object gpuData() {
        return gpuData;
    }

    public void setGpuData(@Nullable Object gpuData) {
        this.gpuData = gpuData;
    }
}
