package dev.denismasterherobrine.animatio.client.player;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.denismasterherobrine.animatio.api.AnimatioApi;
import dev.denismasterherobrine.animatio.client.entity.GltfEntityRenderer;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.model.GltfModel;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Draws a player with a glTF model (runtime model, {@code bindModel(EntityType.PLAYER, …)}, a client-side choice or a
 * resource-pack override). It is a {@link PlayerRenderer}, so first-person arms, the name tag with scores, the
 * {@link RenderPlayerEvent}s and code that expects a player renderer keep working; the body in the world is the
 * glTF model with its attachments.
 */
public class GltfPlayerRenderer extends PlayerRenderer {
    private final GltfEntityRenderer<AbstractClientPlayer> body;

    public GltfPlayerRenderer(EntityRendererProvider.Context context, boolean slim) {
        super(context, slim);
        this.body = new GltfEntityRenderer<>(context);
        this.body.deathFlip(true);
    }

    /** The glTF part of this renderer: add attachments here. */
    public GltfEntityRenderer<AbstractClientPlayer> body() {
        return body;
    }

    private static boolean hasModel(AbstractClientPlayer player) {
        ControllerHolder holder = AnimatioApi.holder(player);
        holder.refreshBinding();
        return ClientModelManager.INSTANCE.get(holder.model()) != null;
    }

    @Override
    public void render(AbstractClientPlayer player, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (!hasModel(player)) {
            super.render(player, yaw, partialTick, poseStack, buffers, light); // model not loaded (yet): vanilla
            return;
        }
        if (NeoForge.EVENT_BUS.post(new RenderPlayerEvent.Pre(player, this, partialTick, poseStack, buffers, light)).isCanceled()) return;
        body.renderModel(player, partialTick, poseStack, buffers, light);
        GltfModel model = ClientModelManager.INSTANCE.get(AnimatioApi.holder(player).model());
        if (model != null) this.shadowRadius = model.definition().render().shadowRadius() * player.getScale();

        var event = new RenderNameTagEvent(player, player.getDisplayName(), this, poseStack, buffers, light, partialTick);
        NeoForge.EVENT_BUS.post(event);
        if (event.canRender().isTrue() || event.canRender().isDefault() && shouldShowName(player)) {
            renderNameTag(player, event.getContent(), poseStack, buffers, light, partialTick);
        }
        NeoForge.EVENT_BUS.post(new RenderPlayerEvent.Post(player, this, partialTick, poseStack, buffers, light));
    }

    /** No vanilla crouch offset: the model's crouch clip lowers it. */
    @Override
    public Vec3 getRenderOffset(AbstractClientPlayer player, float partialTick) {
        return hasModel(player) ? Vec3.ZERO : super.getRenderOffset(player, partialTick);
    }

    @Override
    public boolean shouldRender(AbstractClientPlayer player, Frustum frustum, double camX, double camY, double camZ) {
        return hasModel(player) ? body.shouldRender(player, frustum, camX, camY, camZ) : super.shouldRender(player, frustum, camX, camY, camZ);
    }
}
