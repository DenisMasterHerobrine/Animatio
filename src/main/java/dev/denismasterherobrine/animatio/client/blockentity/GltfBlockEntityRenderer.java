package dev.denismasterherobrine.animatio.client.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.client.attach.AttachmentPoint;
import dev.denismasterherobrine.animatio.client.attach.BoneAttachment;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.ClientClock;
import dev.denismasterherobrine.animatio.client.render.ModelRenderer;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a block entity with its glTF model and animation controller. The model stands on the bottom centre of
 * the block and faces the block's {@code facing} / {@code horizontal_facing} property when it has one.
 * <pre>{@code
 * event.registerBlockEntityRenderer(MY_BE.get(), GltfBlockEntityRenderer::new);
 * }</pre>
 */
public class GltfBlockEntityRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
    private final @Nullable ResourceLocation fixedModel;
    private final List<AttachmentPoint<T>> attachments = new ArrayList<>();
    private final Matrix4f root = new Matrix4f();

    public GltfBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this(context, null);
    }

    public GltfBlockEntityRenderer(BlockEntityRendererProvider.Context context, @Nullable ResourceLocation model) {
        this.fixedModel = model;
    }

    public GltfBlockEntityRenderer<T> withAttachment(String boneOrSocket, BoneAttachment<? super T> attachment) {
        return withAttachment(boneOrSocket, new Matrix4f(), attachment);
    }

    public GltfBlockEntityRenderer<T> withAttachment(String boneOrSocket, Matrix4fc offset, BoneAttachment<? super T> attachment) {
        attachments.add(new AttachmentPoint<>(boneOrSocket, new Matrix4f(offset), attachment));
        return this;
    }

    protected @Nullable GltfModel model(T blockEntity, ControllerHolder holder) {
        if (fixedModel != null) return ClientModelManager.INSTANCE.get(fixedModel);
        holder.refreshBinding();
        return ClientModelManager.INSTANCE.get(holder.model());
    }

    @Override
    public void render(T blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        if (blockEntity.getLevel() == null) return;
        ControllerHolder holder = AnimatioApi.holder(blockEntity);
        GltfModel model = model(blockEntity, holder);
        if (model == null) return;
        AnimatedInstance instance = AnimatedInstance.of(holder);
        BlockPos pos = blockEntity.getBlockPos();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double distance = Math.sqrt(camera.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        instance.prepare(holder, model, ClientClock.time(blockEntity.getLevel(), partialTick), distance);

        computeRoot(blockEntity, model, root);
        instance.setWorldTransform(pos.getX(), pos.getY(), pos.getZ(), root);
        poseStack.pushPose();
        poseStack.mulPose(root);
        ModelRenderer.render(model, instance, poseStack, buffers, light, overlay, 0xFFFFFFFF, ModelRenderer.Context.BLOCK_ENTITY);
        if (!attachments.isEmpty()) {
            AttachmentPoint.renderAll(attachments, blockEntity, holder.controller(), poseStack, buffers, light, overlay, partialTick);
        }
        poseStack.popPose();
    }

    /** Block-relative transform: bottom centre, facing rotation, then the model's root transform. */
    protected void computeRoot(T blockEntity, GltfModel model, Matrix4f out) {
        out.identity().translate(0.5f, 0f, 0.5f);
        Direction facing = facing(blockEntity.getBlockState());
        if (facing != null) out.rotateY((float) Math.toRadians(-facing.toYRot()));
        out.mul(model.rootTransform());
    }

    private static @Nullable Direction facing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        if (state.hasProperty(BlockStateProperties.FACING)) {
            Direction d = state.getValue(BlockStateProperties.FACING);
            return d.getAxis().isHorizontal() ? d : null;
        }
        return null;
    }

    @Override
    public AABB getRenderBoundingBox(T blockEntity) {
        ControllerHolder holder = AnimatioApi.holder(blockEntity);
        GltfModel model = model(blockEntity, holder);
        BlockPos p = blockEntity.getBlockPos();
        if (model == null) return new AABB(p);
        float[] c = model.cullCylinder();
        double cx = p.getX() + 0.5, cz = p.getZ() + 0.5;
        return new AABB(cx - c[2], p.getY() + Math.min(0f, c[0]), cz - c[2], cx + c[2], p.getY() + c[1], cz + c[2]);
    }
}
