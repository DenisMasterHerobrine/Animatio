package dev.denismasterherobrine.animatio.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.client.attach.AttachmentPoint;
import dev.denismasterherobrine.animatio.client.attach.BoneAttachment;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.ClientClock;
import dev.denismasterherobrine.animatio.client.render.ModelLights;
import dev.denismasterherobrine.animatio.client.render.ModelRenderer;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an entity with its glTF model and animation controller.
 * <pre>{@code
 * event.registerEntityRenderer(MY_NPC.get(), ctx -> new GltfEntityRenderer<MyNpc>(ctx)
 *         .withAttachment("hand_r", Attachments.heldItem(HumanoidArm.RIGHT))
 *         .withAttachment("head", Attachments.armor(EquipmentSlot.HEAD, Attachments.ArmorPiece.HEAD)));
 * }</pre>
 * The model comes from {@link AnimatioApi#bindModel}, {@link dev.denismasterherobrine.animatio.api.GltfAnimatable}, or
 * the model passed to the constructor. Frustum culling uses the model's animated bounds instead of the hitbox.
 */
public class GltfEntityRenderer<T extends Entity> extends EntityRenderer<T> {
    private final @Nullable ResourceLocation fixedModel;
    private final List<AttachmentPoint<T>> attachments = new ArrayList<>();
    private final Matrix4f root = new Matrix4f();
    private boolean deathFlip = true;

    public GltfEntityRenderer(EntityRendererProvider.Context context) {
        this(context, null);
    }

    /** @param model model to use for every entity this renderer draws, overriding bindings */
    public GltfEntityRenderer(EntityRendererProvider.Context context, @Nullable ResourceLocation model) {
        super(context);
        this.fixedModel = model;
        this.shadowRadius = 0.5f;
    }

    public GltfEntityRenderer<T> withAttachment(String boneOrSocket, BoneAttachment<? super T> attachment) {
        return withAttachment(boneOrSocket, new Matrix4f(), attachment);
    }

    public GltfEntityRenderer<T> withAttachment(String boneOrSocket, Matrix4fc offset, BoneAttachment<? super T> attachment) {
        attachments.add(new AttachmentPoint<>(boneOrSocket, new Matrix4f(offset), attachment));
        return this;
    }

    /** Whether dying living entities tip over like vanilla mobs (disable when the model has its own death clip). */
    public GltfEntityRenderer<T> deathFlip(boolean enabled) {
        this.deathFlip = enabled;
        return this;
    }

    protected @Nullable GltfModel model(T entity, ControllerHolder holder) {
        if (fixedModel != null) return ClientModelManager.INSTANCE.get(fixedModel);
        holder.refreshBinding();
        return ClientModelManager.INSTANCE.get(holder.model());
    }

    /** ARGB tint for the whole model. Override for effects such as fading out. */
    protected int tint(T entity, float partialTick) {
        return 0xFFFFFFFF;
    }

    /** Hook to set per-frame material overrides on the instance before drawing. */
    protected void beforeRender(T entity, AnimatedInstance instance, float partialTick) {
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        renderModel(entity, partialTick, poseStack, buffers, light);
        super.render(entity, entityYaw, partialTick, poseStack, buffers, light);
    }

    /** The skin texture for materials textured {@code animatio:player_skin}, or {@code null} (players: their skin). */
    protected @Nullable ResourceLocation skinTexture(T entity) {
        return entity instanceof net.minecraft.client.player.AbstractClientPlayer player ? player.getSkin().texture() : null;
    }

    /**
     * Draw the model and its attachments (everything except vanilla extras such as the name tag). Also updates the
     * shadow radius and feeds the look-at parameters.
     *
     * @return false if the entity has no model
     */
    public boolean renderModel(T entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        ControllerHolder holder = AnimatioApi.holder(entity);
        GltfModel model = model(entity, holder);
        if (model == null) return false;
        AnimatedInstance instance = AnimatedInstance.of(holder);
        double distance = Math.sqrt(entityRenderDispatcher.distanceToSqr(entity));
        instance.prepare(holder, model, ClientClock.time(entity.level(), partialTick), distance);
        if (entity instanceof LivingEntity living && model.animations().look() != null) provideLook(holder, living, partialTick);

        computeRoot(entity, partialTick, model, root);
        Vec3 pos = entity.getPosition(partialTick);
        instance.setWorldTransform(pos.x, pos.y, pos.z, root);
        instance.setSkinTexture(skinTexture(entity));

        int tint = tint(entity, partialTick);
        Minecraft mc = Minecraft.getInstance();
        boolean invisible = entity.isInvisible();
        boolean ghost = invisible && mc.player != null && !entity.isInvisibleTo(mc.player);
        if (!invisible || ghost) {
            if (ghost) tint = (tint & 0x00FFFFFF) | (0x26 << 24);
            beforeRender(entity, instance, partialTick);
            int overlay = entity instanceof LivingEntity le ? LivingEntityRenderer.getOverlayCoords(le, 0f) : OverlayTexture.NO_OVERLAY;
            int lit = ModelLights.light(model, instance, light);
            poseStack.pushPose();
            poseStack.mulPose(root);
            ModelRenderer.render(model, instance, poseStack, buffers, lit, overlay, tint, ModelRenderer.Context.ENTITY);
            if (!attachments.isEmpty()) {
                AttachmentPoint.renderAll(attachments, entity, holder.controller(), poseStack, buffers, lit, overlay, partialTick);
            }
            poseStack.popPose();
        }
        float scale = entity instanceof LivingEntity le ? le.getScale() : 1f;
        this.shadowRadius = model.definition().render().shadowRadius() * scale;
        return true;
    }

    /** Head direction relative to the body, for models with a look-at bone (used by the next evaluation). */
    private static void provideLook(ControllerHolder holder, LivingEntity living, float partialTick) {
        float bodyYaw = Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot);
        float headYaw = Mth.rotLerp(partialTick, living.yHeadRotO, living.yHeadRot);
        var controller = holder.controller();
        controller.provideParameter(dev.denismasterherobrine.animatio.anim.model.LookConfig.YAW_PARAMETER, Mth.wrapDegrees(headYaw - bodyYaw));
        controller.provideParameter(dev.denismasterherobrine.animatio.anim.model.LookConfig.PITCH_PARAMETER, living.getViewXRot(partialTick));
    }

    /** This frame's model-to-world transform: the entity's interpolated position times {@link #computeRoot}. */
    public void modelToWorld(T entity, float partialTick, GltfModel model, Matrix4f out) {
        computeRoot(entity, partialTick, model, out);
        Vec3 pos = entity.getPosition(partialTick);
        out.translateLocal((float) pos.x, (float) pos.y, (float) pos.z);
    }

    /** Entity-relative transform applied before the model: yaw, death tilt, scale and the model's root transform. */
    protected void computeRoot(T entity, float partialTick, GltfModel model, Matrix4f out) {
        out.identity();
        if (entity instanceof LivingEntity living) {
            float bodyYaw = Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot);
            out.rotateY((float) Math.toRadians(-bodyYaw));
            if (deathFlip && living.deathTime > 0) {
                float f = Math.min(1f, Mth.sqrt((living.deathTime + partialTick - 1f) / 20f * 1.6f));
                out.rotateZ((float) Math.toRadians(f * 90f));
            }
            float scale = living.getScale();
            if (scale != 1f) out.scale(scale);
        } else {
            float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
            out.rotateY((float) Math.toRadians(-yaw));
        }
        out.mul(model.rootTransform());
    }

    @Override
    public boolean shouldRender(T entity, Frustum frustum, double camX, double camY, double camZ) {
        if (!entity.shouldRender(camX, camY, camZ)) return false;
        if (entity.noCulling) return true;
        GltfModel model = model(entity, AnimatioApi.holder(entity));
        if (model == null) return super.shouldRender(entity, frustum, camX, camY, camZ);
        float[] c = model.cullCylinder();
        float scale = entity instanceof LivingEntity le ? le.getScale() : 1f;
        float r = c[2] * scale;
        AABB box = new AABB(entity.getX() - r, entity.getY() + Math.min(c[0] * scale, 0f), entity.getZ() - r,
                entity.getX() + r, entity.getY() + c[1] * scale, entity.getZ() + r);
        if (entity instanceof LivingEntity living && living.deathTime > 0) box = box.inflate(c[1] * scale); // tipping over
        return frustum.isVisible(box);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return ClientModelManager.WHITE_TEXTURE;
    }
}
