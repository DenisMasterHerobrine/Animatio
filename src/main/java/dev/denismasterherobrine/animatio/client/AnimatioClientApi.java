package dev.denismasterherobrine.animatio.client;

import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.gpu.GpuRenderer;
import dev.denismasterherobrine.animatio.client.render.gpu.GpuShaders;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Client-only API: material overrides, custom shaders, model access and bone positions.
 * <pre>{@code
 * // red damage flash on every material
 * AnimatioClientApi.setMaterialOverride(entity, MaterialOverride.none().withTint(1f, 0.4f, 0.4f, 1f));
 * // dissolve one material with the built-in example shader (param0 = progress 0..1)
 * AnimatioClientApi.setMaterialOverride(entity, "body",
 *         MaterialOverride.none().withShader(GpuShaders.DISSOLVE).withParams(progress, 1f, 0.5f, 0.1f));
 * }</pre>
 */
public final class AnimatioClientApi {
    private AnimatioClientApi() {
    }

    /** Override one material (by glTF material name) of a client-side entity or block entity. {@code null} clears it. */
    public static void setMaterialOverride(IAttachmentHolder owner, String material, @Nullable MaterialOverride override) {
        AnimatedInstance.of(AnimatioApi.holder(owner)).setOverride(material, override);
    }

    /** Override every material that has no material-specific override. {@code null} clears it. */
    public static void setMaterialOverride(IAttachmentHolder owner, @Nullable MaterialOverride override) {
        AnimatedInstance.of(AnimatioApi.holder(owner)).setOverrideAll(override);
    }

    public static void clearMaterialOverrides(IAttachmentHolder owner) {
        AnimatedInstance.of(AnimatioApi.holder(owner)).clearOverrides();
    }

    /**
     * Register a custom skinning shader ({@code assets/<ns>/shaders/core/<path>.json}, typically importing
     * {@code <animatio:skinning.glsl>}). Call from your client mod constructor. Custom shaders apply on the GPU path
     * only; with a shader pack active, materials fall back to vanilla shading (texture, tint and emissive still apply).
     */
    public static void registerShader(ResourceLocation shader) {
        GpuShaders.register(shader);
    }

    /** True if models are currently drawn with GPU skinning (false with shader packs or when disabled in config). */
    public static boolean isGpuSkinningActive() {
        return GpuRenderer.isActive();
    }

    /** A loaded model, or {@code null}. */
    public static @Nullable GltfModel model(ResourceLocation id) {
        return ClientModelManager.INSTANCE.get(id);
    }

    /** World position of a bone or socket as of the last rendered frame. */
    public static boolean bonePosition(IAttachmentHolder owner, String boneOrSocket, Vector3f out) {
        ControllerHolder holder = AnimatioApi.holder(owner);
        AnimatedInstance instance = AnimatedInstance.getIfPresent(holder);
        return instance != null && instance.bonePosition(holder, boneOrSocket, out, new Matrix4f());
    }
}
