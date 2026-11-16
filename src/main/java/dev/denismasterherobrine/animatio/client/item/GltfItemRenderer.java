package dev.denismasterherobrine.animatio.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.playable.Playable;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.client.render.ModelLights;
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
 * Renders an item with a glTF model. Every stack has its own animation state ({@link #controller(ItemStack)}): stacks
 * carrying an {@code animatio:animation_id} component keep it wherever they go and receive animations played from
 * the server ({@code AnimatioApi.playOnItem}); other stacks get state tied to the stack object. An ambient animation
 * starts on each stack's first render. {@link #sharedState(boolean) sharedState(true)} restores one state for every stack.
 * <p>
 * Register through {@code IClientItemExtensions#getCustomRenderer}; the item model JSON must have the parent
 * {@code minecraft:builtin/entity} (or one that inherits it, such as {@code minecraft:item/chest}).
 */
public class GltfItemRenderer extends BlockEntityWithoutLevelRenderer {
    private final ResourceLocation modelId;
    private final ItemAnimations.State shared = new ItemAnimations.State();
    private final @Nullable Playable ambient;
    private final Matrix4f root = new Matrix4f();
    private boolean fitToBlock = true;
    private boolean sharedState;

    /** @param ambient played (looping if the clip loops) when a stack is first rendered, or {@code null} */
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

    /** One animation state for all stacks of the item instead of one per stack. */
    public GltfItemRenderer sharedState(boolean shared) {
        this.sharedState = shared;
        return this;
    }

    /** The animation controller of one stack (client side). */
    public AnimationController controller(ItemStack stack) {
        return sharedState ? shared.controller() : ItemAnimations.state(stack).controller();
    }

    /** The controller shared by every stack when {@link #sharedState(boolean)} is on. */
    public AnimationController controller() {
        return shared.controller();
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        GltfModel model = ClientModelManager.INSTANCE.get(modelId);
        if (model == null) return;
        ItemAnimations.State state = sharedState ? shared : ItemAnimations.state(stack);
        ControllerImpl controller = state.controller;
        if (state.boundModel != model) {
            state.boundModel = model;
            controller.bind(ClientModelManager.INSTANCE.animationSet(modelId));
        }
        double now = ItemAnimations.now();
        controller.update(now);
        if (!state.ambientStarted && ambient != null) {
            state.ambientStarted = true;
            controller.play(ambient);
        }
        AnimatedInstance instance = state.instance;
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
        ModelRenderer.render(model, instance, poseStack, buffers, ModelLights.light(model, instance, light), overlay, 0xFFFFFFFF,
                ModelRenderer.Context.OTHER);
        poseStack.popPose();
    }
}
