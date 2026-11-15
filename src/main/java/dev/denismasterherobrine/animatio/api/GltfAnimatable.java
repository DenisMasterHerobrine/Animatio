package dev.denismasterherobrine.animatio.api;

import net.minecraft.resources.ResourceLocation;

/**
 * Implement on an entity or block entity to choose its model per instance (for example from synced entity data).
 * Takes precedence over {@link AnimatioApi#bindModel}. The value is re-read every tick; when it changes, the
 * controller rebinds and keeps its tracks.
 */
public interface GltfAnimatable {
    ResourceLocation animatioModel();
}
