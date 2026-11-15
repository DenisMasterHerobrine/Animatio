package dev.denismasterherobrine.animatio.client.attach;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;

/**
 * An attachment bound to a bone or socket, with an extra local offset.
 */
public record AttachmentPoint<T>(String boneOrSocket, Matrix4fc offset, BoneAttachment<? super T> attachment) {
    private static final Matrix4f BONE = new Matrix4f();
    private static final Matrix4f OFFSET = new Matrix4f();

    /** Render every attachment whose bone exists in the controller's last evaluated pose. Render thread only. */
    public static <T> void renderAll(List<AttachmentPoint<T>> points, T owner, ControllerImpl controller, PoseStack poseStack,
                                     MultiBufferSource buffers, int light, int overlay, float partialTick) {
        for (AttachmentPoint<T> point : points) {
            if (!controller.boneTransform(point.boneOrSocket(), BONE)) continue;
            poseStack.pushPose();
            poseStack.mulPose(BONE);
            poseStack.mulPose(OFFSET.set(point.offset()));
            point.attachment().render(owner, poseStack, buffers, light, overlay, partialTick);
            poseStack.popPose();
        }
    }
}
