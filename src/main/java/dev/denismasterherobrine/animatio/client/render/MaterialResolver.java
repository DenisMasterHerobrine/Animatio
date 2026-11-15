package dev.denismasterherobrine.animatio.client.render;

import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.data.ModelDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a model part plus its per-instance override into what the vanilla-buffer path needs.
 */
public final class MaterialResolver {
    private MaterialResolver() {
    }

    /** The vanilla render type for a part, or {@code null} if the part is not drawn. */
    public static @Nullable RenderType renderType(GltfModel.Part part, MaterialOverride override, int color) {
        if (override.hidden() || part.kind() == ModelDefinition.RenderTypeKind.INVISIBLE) return null;
        ResourceLocation tex = override.texture() != null ? override.texture() : part.texture();
        boolean translucent = FastColor.ARGB32.alpha(color) < 255;
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

    public static int multiply(int a, int b) {
        if (b == -1) return a;
        if (a == -1) return b;
        return FastColor.ARGB32.color(
                FastColor.ARGB32.alpha(a) * FastColor.ARGB32.alpha(b) / 255,
                FastColor.ARGB32.red(a) * FastColor.ARGB32.red(b) / 255,
                FastColor.ARGB32.green(a) * FastColor.ARGB32.green(b) / 255,
                FastColor.ARGB32.blue(a) * FastColor.ARGB32.blue(b) / 255);
    }
}
