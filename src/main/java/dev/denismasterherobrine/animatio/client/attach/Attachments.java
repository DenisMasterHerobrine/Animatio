package dev.denismasterherobrine.animatio.client.attach;

import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Built-in {@link BoneAttachment}s for vanilla features.
 * <p>
 * Orientation: attachments assume the bone's local axes match model space in the rest pose (+Y up, +Z forward).
 * Bones exported with another orientation (Blender bones point +Y along the bone) can be corrected with a socket
 * rotation in the model's JSON definition, or with the offset argument of {@code addAttachment}.
 * <p>
 * Vanilla model parts live in a space that is ours rotated 180° about X (+Y down, +Z backward); vanilla-model
 * attachments apply that rotation first.
 */
public final class Attachments {
    private static @Nullable HumanoidArmorModel<LivingEntity> innerArmor, outerArmor;
    private static @Nullable ModelPart capePart;

    private Attachments() {
    }

    /** Turn the pose stack from glTF model space into vanilla model-part space. */
    private static void toVanillaModelSpace(com.mojang.blaze3d.vertex.PoseStack ps) {
        ps.mulPose(Axis.XP.rotationDegrees(180f));
    }

    /** The item in the main or off hand, held like vanilla's (blade pointing forward), for a bone at the hand. */
    public static <T extends LivingEntity> BoneAttachment<T> heldItem(HumanoidArm arm) {
        return (entity, ps, buffers, light, overlay, pt) -> {
            boolean left = arm == HumanoidArm.LEFT;
            ItemStack stack = arm == entity.getMainArm() ? entity.getMainHandItem() : entity.getOffhandItem();
            if (stack.isEmpty()) return;
            // vanilla ItemInHandLayer, minus the shoulder-to-hand offset (we are already at the hand)
            toVanillaModelSpace(ps);
            ps.translate((left ? 1f : -1f) / 16f, 0f, -0.125f);
            ps.mulPose(Axis.XP.rotationDegrees(-90f));
            ps.mulPose(Axis.YP.rotationDegrees(180f));
            Minecraft.getInstance().getEntityRenderDispatcher().getItemInHandRenderer().renderItem(entity, stack,
                    left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, left, ps, buffers, light);
        };
    }

    /** Any item, with a display context (e.g. {@code HEAD} for a pumpkin on the head bone). */
    public static <T> BoneAttachment<T> item(Function<T, ItemStack> stack, ItemDisplayContext context) {
        return (owner, ps, buffers, light, overlay, pt) -> {
            ItemStack s = stack.apply(owner);
            if (s == null || s.isEmpty()) return;
            LivingEntity living = owner instanceof LivingEntity le ? le : null;
            Minecraft mc = Minecraft.getInstance();
            mc.getItemRenderer().renderStatic(living, s, context, false, ps, buffers, mc.level, light, OverlayTexture.NO_OVERLAY,
                    living != null ? living.getId() : 0);
        };
    }

    /** The item in an equipment slot (for example a non-armour block worn on the head). */
    public static <T extends LivingEntity> BoneAttachment<T> equipped(EquipmentSlot slot, ItemDisplayContext context) {
        return item(e -> e.getItemBySlot(slot), context);
    }

    /** A block, rendered with its model at the bone (centred on X/Z, standing on the bone). */
    public static <T> BoneAttachment<T> block(Function<T, BlockState> state) {
        return (owner, ps, buffers, light, overlay, pt) -> {
            BlockState s = state.apply(owner);
            if (s == null || s.isAir()) return;
            ps.translate(-0.5f, 0f, -0.5f);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(s, ps, buffers, light, OverlayTexture.NO_OVERLAY);
        };
    }

    /** Pieces of the vanilla armour model, each placed with its pivot at the bone. */
    public enum ArmorPiece {
        HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG
    }

    /**
     * Render the armour equipped in {@code slot} (dyed layers and enchantment glint; trims are not drawn) as one piece
     * of the vanilla armour model. Typical setup: HEAD on the head bone, CHEST as BODY + both ARMs, LEGS as BODY + both
     * LEGs, FEET as both LEGs.
     */
    public static <T extends LivingEntity> BoneAttachment<T> armor(EquipmentSlot slot, ArmorPiece piece) {
        return (entity, ps, buffers, light, overlay, pt) -> {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!(stack.getItem() instanceof ArmorItem armor) || armor.getEquipmentSlot() != slot) return;
            boolean inner = slot == EquipmentSlot.LEGS;
            HumanoidArmorModel<LivingEntity> model = armorModel(inner);
            ModelPart part = switch (piece) {
                case HEAD -> model.head;
                case BODY -> model.body;
                case RIGHT_ARM -> model.rightArm;
                case LEFT_ARM -> model.leftArm;
                case RIGHT_LEG -> model.rightLeg;
                case LEFT_LEG -> model.leftLeg;
            };
            toVanillaModelSpace(ps);
            Holder<ArmorMaterial> material = armor.getMaterial();
            IClientItemExtensions ext = IClientItemExtensions.of(stack);
            int fallback = ext.getDefaultDyeColor(stack);
            for (int i = 0; i < material.value().layers().size(); i++) {
                ArmorMaterial.Layer layer = material.value().layers().get(i);
                int color = ext.getArmorLayerTintColor(stack, entity, layer, i, fallback);
                if (color == 0) continue;
                ResourceLocation texture = ClientHooks.getArmorTexture(entity, stack, layer, inner, slot);
                renderPart(part, piece, ps, buffers.getBuffer(RenderType.armorCutoutNoCull(texture)), light, color);
                if (piece == ArmorPiece.HEAD) renderPart(model.hat, piece, ps, buffers.getBuffer(RenderType.armorCutoutNoCull(texture)), light, color);
            }
            if (stack.hasFoil()) renderPart(part, piece, ps, buffers.getBuffer(RenderType.armorEntityGlint()), light, -1);
        };
    }

    private static void renderPart(ModelPart part, ArmorPiece piece, com.mojang.blaze3d.vertex.PoseStack ps,
                                   com.mojang.blaze3d.vertex.VertexConsumer vc, int light, int color) {
        part.x = 0f;
        part.y = 0f;
        part.z = 0f;
        part.xRot = 0f;
        part.yRot = 0f;
        part.zRot = 0f;
        part.visible = true;
        part.render(ps, vc, light, OverlayTexture.NO_OVERLAY, color);
    }

    private static HumanoidArmorModel<LivingEntity> armorModel(boolean inner) {
        if (inner) {
            if (innerArmor == null) innerArmor = new HumanoidArmorModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            return innerArmor;
        }
        if (outerArmor == null) outerArmor = new HumanoidArmorModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        return outerArmor;
    }

    /**
     * A cape using the vanilla cape texture layout (64×32), hanging from the bone and swinging back with movement.
     */
    public static <T extends LivingEntity> BoneAttachment<T> cape(Function<T, ResourceLocation> texture) {
        return (entity, ps, buffers, light, overlay, pt) -> {
            ResourceLocation tex = texture.apply(entity);
            if (tex == null) return;
            double dx = entity.getX() - entity.xo, dz = entity.getZ() - entity.zo;
            float bodyYaw = (float) Math.toRadians(entity.yBodyRot);
            double forward = -dx * Math.sin(bodyYaw) + dz * Math.cos(bodyYaw);
            float swing = (float) Math.max(0, Math.min(60, forward * 20 * 25)) + 6f;
            ps.translate(0f, 0f, -0.15f);
            ps.mulPose(Axis.XP.rotationDegrees(swing)); // lower edge swings backward (-Z)
            toVanillaModelSpace(ps);
            ModelPart cape = capePart();
            cape.render(ps, buffers.getBuffer(RenderType.entitySolid(tex)), light, overlay);
        };
    }

    private static ModelPart capePart() {
        if (capePart == null) {
            MeshDefinition mesh = new MeshDefinition();
            mesh.getRoot().addOrReplaceChild("cape", CubeListBuilder.create().texOffs(0, 0).addBox(-5f, 0f, -1f, 10f, 16f, 1f), PartPose.ZERO);
            capePart = LayerDefinition.create(mesh, 64, 32).bakeRoot().getChild("cape");
        }
        return capePart;
    }
}
