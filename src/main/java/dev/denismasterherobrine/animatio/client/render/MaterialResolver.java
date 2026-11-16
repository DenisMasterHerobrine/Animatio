package dev.denismasterherobrine.animatio.client.render;

import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.data.ModelDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a model part plus its per-instance override into what the vanilla-buffer path needs.
 */
public final class MaterialResolver {
    private MaterialResolver() {
    }

    /**
     * The vanilla render type for a part, or {@code null} if the part is not drawn. Parts without a texture
     * override use a per-part cache, so the common case costs no render-type lookup.
     *
     * @param skin the owner's skin texture, used by parts whose texture is {@link ModelDefinition#PLAYER_SKIN}
     */
    public static @Nullable RenderType renderType(GltfModel.Part part, MaterialOverride override, int color, @Nullable ResourceLocation skin) {
        if (override.hidden() || part.kind() == ModelDefinition.RenderTypeKind.INVISIBLE) return null;
        boolean translucent = (color >>> 24) < 255;
        ResourceLocation tex = texture(part, override, skin);
        if (tex == part.texture()) {
            RenderType[] cache = part.renderTypeCache();
            int slot = translucent ? 1 : 0;
            RenderType cached = cache[slot];
            if (cached == null) cache[slot] = cached = create(part, tex, translucent);
            return cached;
        }
        return create(part, tex, translucent);
    }

    /** The base texture a part is drawn with for an instance. */
    public static ResourceLocation texture(GltfModel.Part part, MaterialOverride override, @Nullable ResourceLocation skin) {
        ResourceLocation tex = override.texture();
        if (tex == null) {
            if (!part.usesSkin()) return part.texture();
            return skin != null ? skin : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultTexture();
        }
        if (tex.equals(ModelDefinition.PLAYER_SKIN)) return skin != null ? skin : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultTexture();
        return tex;
    }

    private static RenderType create(GltfModel.Part part, ResourceLocation tex, boolean translucent) {
        return switch (part.kind()) {
            case TRANSLUCENT -> RenderType.entityTranslucent(tex);
            case EMISSIVE -> RenderType.entityTranslucentEmissive(tex);
            case SOLID -> translucent ? RenderType.entityTranslucent(tex) : part.doubleSided() ? RenderType.entityCutoutNoCull(tex) : RenderType.entitySolid(tex);
            default -> translucent ? RenderType.entityTranslucent(tex) : part.doubleSided() ? RenderType.entityCutoutNoCull(tex) : RenderType.entityCutout(tex);
        };
    }

    /** Part colour × override tint × instance tint, as ARGB. */
    public static int color(GltfModel.Part part, MaterialOverride override, int instanceTint) {
        return multiply(multiply(part.color(), override.tint()), instanceTint);
    }

    /** Packed light after full-bright materials and emissive overrides. */
    public static int light(GltfModel.Part part, MaterialOverride override, int light) {
        if (part.fullBright()) return LightTexture.FULL_BRIGHT;
        float e = override.emissive();
        if (e <= 0f) return light;
        int block = light & 0xFFFF, sky = (light >> 16) & 0xFFFF;
        block = Math.round(block + (240 - block) * e);
        sky = Math.round(sky + (240 - sky) * e);
        return block | (sky << 16);
    }

    /** Component-wise product of two ARGB colours ({@code -1} is white and returns the other unchanged). */
    public static int multiply(int a, int b) {
        if (b == -1) return a;
        if (a == -1) return b;
        int aa = (a >>> 24) * (b >>> 24) / 255;
        int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int bb = (a & 0xFF) * (b & 0xFF) / 255;
        return aa << 24 | r << 16 | g << 8 | bb;
    }
}
