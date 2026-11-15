package dev.denismasterherobrine.animatio.client.attach;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * Something rendered at a bone or socket of an animated model: a held item, an armour piece, a cape, anything.
 * When called, the pose stack is positioned at the bone (model space × bone transform × socket offset).
 *
 * @param <T> the animated object type (entity, block entity, ...)
 */
@FunctionalInterface
public interface BoneAttachment<T> {
    void render(T owner, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay, float partialTick);
}
