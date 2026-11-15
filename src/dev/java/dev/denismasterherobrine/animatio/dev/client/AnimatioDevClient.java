package dev.denismasterherobrine.animatio.dev.client;

import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.material.MaterialOverride;
import dev.denismasterherobrine.animatio.client.attach.Attachments;
import dev.denismasterherobrine.animatio.client.blockentity.GltfBlockEntityRenderer;
import dev.denismasterherobrine.animatio.client.entity.GltfEntityRenderer;
import dev.denismasterherobrine.animatio.client.item.GltfItemRenderer;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.gpu.GpuShaders;
import dev.denismasterherobrine.animatio.dev.AnimatioDev;
import dev.denismasterherobrine.animatio.dev.RobotEntity;
import dev.denismasterherobrine.animatio.dev.RobotStatueBlockEntity;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@Mod(value = AnimatioDev.MODID, dist = Dist.CLIENT)
public final class AnimatioDevClient {
    public AnimatioDevClient(IEventBus modBus) {
        modBus.addListener(AnimatioDevClient::onRegisterRenderers);
        modBus.addListener(AnimatioDevClient::onClientExtensions);
        AnimatioAutotest.install();
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AnimatioDev.ROBOT.get(), ctx -> new GltfEntityRenderer<RobotEntity>(ctx) {
                    @Override
                    protected void beforeRender(RobotEntity robot, AnimatedInstance instance, float partialTick) {
                        // custom shader demo: dissolve the body while the death clip plays
                        if (robot.deathTime > 0) {
                            float progress = Math.min(1f, (robot.deathTime + partialTick) / 20f);
                            instance.setOverride("body", MaterialOverride.none().withShader(GpuShaders.DISSOLVE)
                                    .withParams(progress * progress, 0.3f, 0.9f, 1f));
                        }
                    }
                }
                .deathFlip(false)
                .withAttachment("right_hand", Attachments.heldItem(HumanoidArm.RIGHT))
                .withAttachment("left_hand", Attachments.heldItem(HumanoidArm.LEFT))
                .withAttachment("head", Attachments.armor(EquipmentSlot.HEAD, Attachments.ArmorPiece.HEAD)));
        event.registerBlockEntityRenderer(AnimatioDev.STATUE_BE.get(), ctx -> new GltfBlockEntityRenderer<RobotStatueBlockEntity>(ctx));
    }

    private static void onClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private GltfItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new GltfItemRenderer(AnimatioDev.ROBOT_MODEL, AnimationRef.of("idle").looping());
                return renderer;
            }
        }, AnimatioDev.STATUE_ITEM.get());
    }
}
