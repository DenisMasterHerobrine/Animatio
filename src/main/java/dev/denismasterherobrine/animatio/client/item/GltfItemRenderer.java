package dev.denismasterherobrine.animatio.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.ModelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Renders an item with a glTF model. Item stacks have nowhere to keep per-stack state, so all stacks of the item
 * share one client-side controller ({@link #controller()}): an ambient animation loops by default, and client code
 * can play clips on it (for example when the item is used).
 * <p>
 * Register through {@code IClientItemExtensions#getCustomRenderer}; the item model JSON must have the parent
 * {@code minecraft:builtin/entity} (or one that inherits it, such as {@code minecraft:item/chest}).
 */
public class GltfItemRenderer extends BlockEntityWithoutLevelRenderer {
    private final ResourceLocation modelId;
    private final ControllerImpl controller = new ControllerImpl(ControllerImpl.Side.CLIENT);
    private final AnimatedInstance instance = new AnimatedInstance();
    private final @Nullable Playable ambient;
    private final Matrix4f root = new Matrix4f();
    private boolean fitToBlock = true;
    private boolean started;
    private @Nullable GltfModel bound;

    /** @param ambient played (looping if the clip loops) when first rendered, or {@code null} */
    public GltfItemRenderer(ResourceLocation model, @Nullable Playable ambient) {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        this.modelId = model;
        this.ambient = ambient;
    }

    /** Scale the model to fit a one-block cube (default on). */
    public GltfItemRenderer fitToBlock(boolean fit) {
        this.fitToBlock = fit;
        return this;
    }

    /** The controller shared by every stack of this item. Client side only. */
    public AnimationController controller() {
        return controller;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        GltfModel model = ClientModelManager.INSTANCE.get(modelId);
        if (model == null) return;
        if (bound != model) {
            bound = model;
            controller.bind(ClientModelManager.INSTANCE.animationSet(modelId));
        }
        Minecraft mc = Minecraft.getInstance();
        double now = mc.level != null ? (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false)) / 20.0
                : System.nanoTime() / 1e9;
        controller.update(now);
        if (!started && ambient != null) {
            started = true;
            controller.play(ambient);
        }
        instance.prepare(controller, model, now, 0);

        root.identity().translate(0.5f, 0f, 0.5f);
        if (fitToBlock) {
            float[] c = model.cullCylinder();
            float size = Math.max(c[1] - Math.min(0f, c[0]), c[2] * 2f);
            if (size > 1f) root.scale(1f / size);
        }
        root.mul(model.rootTransform());
        poseStack.pushPose();
        poseStack.mulPose(root);
        ModelRenderer.render(model, instance, poseStack, buffers, light, overlay, 0xFFFFFFFF, ModelRenderer.Context.OTHER);
        poseStack.popPose();
    }
}
