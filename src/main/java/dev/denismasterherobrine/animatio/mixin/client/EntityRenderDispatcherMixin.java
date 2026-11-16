package dev.denismasterherobrine.animatio.mixin.client;

import dev.denismasterherobrine.animatio.client.override.EntityOverrides;
import dev.denismasterherobrine.animatio.common.AnimatioAttachments;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Swaps in glTF renderers for players with a model, entities with a runtime model, and entity types that a resource
 * pack overrides.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Shadow
    private Map<EntityType<?>, EntityRenderer<?>> renderers;

    @Inject(method = "getRenderer", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void animatio$overrideRenderer(T entity, CallbackInfoReturnable<EntityRenderer<? super T>> cir) {
        // cheap exits for the common case: no override files, not a player, no runtime model
        if (EntityOverrides.INSTANCE.isEmpty() && !(entity instanceof AbstractClientPlayer) && !entity.hasData(AnimatioAttachments.ENTITY_MODEL)) return;
        @SuppressWarnings("unchecked")
        EntityRenderer<? super T> renderer = (EntityRenderer<? super T>) EntityOverrides.INSTANCE.renderer(entity,
                (EntityRenderDispatcher) (Object) this, renderers.get(entity.getType()));
        if (renderer != null) cir.setReturnValue(renderer);
    }
}
