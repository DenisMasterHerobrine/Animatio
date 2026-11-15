package dev.denismasterherobrine.animatio.api.material;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Per-instance changes to one material of a model, e.g. a damage flash, a team colour or a dissolve effect.
 * Immutable; build with {@link #none()} and the modifiers.
 *
 * @param texture  replacement base texture, or {@code null} to keep the model's
 * @param tint     ARGB multiplier ({@code 0xFFFFFFFF} = unchanged; alpha below 255 needs a translucent material)
 * @param emissive 0..1: how far the material is pushed to full brightness
 * @param shader   custom shader for the GPU path (ignored on the compatibility path), or {@code null}
 * @param params   four free values passed to custom shaders per instance ({@code AnimatioParams})
 * @param hidden   skip this material entirely
 */
public record MaterialOverride(@Nullable ResourceLocation texture, int tint, float emissive, @Nullable ResourceLocation shader,
                               float param0, float param1, float param2, float param3, boolean hidden) {
    private static final MaterialOverride NONE = new MaterialOverride(null, 0xFFFFFFFF, 0f, null, 0, 0, 0, 0, false);

    public static MaterialOverride none() {
        return NONE;
    }

    public MaterialOverride withTexture(@Nullable ResourceLocation texture) {
        return new MaterialOverride(texture, tint, emissive, shader, param0, param1, param2, param3, hidden);
    }

    public MaterialOverride withTint(int argb) {
        return new MaterialOverride(texture, argb, emissive, shader, param0, param1, param2, param3, hidden);
    }

    public MaterialOverride withTint(float r, float g, float b, float a) {
        int argb = (Math.round(clamp(a) * 255) << 24) | (Math.round(clamp(r) * 255) << 16) | (Math.round(clamp(g) * 255) << 8) | Math.round(clamp(b) * 255);
        return withTint(argb);
    }

    public MaterialOverride withEmissive(float emissive) {
        return new MaterialOverride(texture, tint, clamp(emissive), shader, param0, param1, param2, param3, hidden);
    }

    public MaterialOverride withShader(@Nullable ResourceLocation shader) {
        return new MaterialOverride(texture, tint, emissive, shader, param0, param1, param2, param3, hidden);
    }

    public MaterialOverride withParams(float p0, float p1, float p2, float p3) {
        return new MaterialOverride(texture, tint, emissive, shader, p0, p1, p2, p3, hidden);
    }

    public MaterialOverride hidden(boolean hidden) {
        return new MaterialOverride(texture, tint, emissive, shader, param0, param1, param2, param3, hidden);
    }

    public boolean isNone() {
        return equals(NONE);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
