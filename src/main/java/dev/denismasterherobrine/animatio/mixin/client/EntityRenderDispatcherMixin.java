package dev.denismasterherobrine.animatio.mixin.client;

import dev.denismasterherobrine.animatio.client.override.EntityOverrides;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps in glTF renderers for entity types that a resource pack overrides.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "getRenderer", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void animatio$overrideRenderer(T entity, CallbackInfoReturnable<EntityRenderer<? super T>> cir) {
        if (EntityOverrides.INSTANCE.isEmpty()) return;
        @SuppressWarnings("unchecked")
        EntityRenderer<? super T> renderer = (EntityRenderer<? super T>) EntityOverrides.INSTANCE.renderer(entity, (EntityRenderDispatcher) (Object) this);
        if (renderer != null) cir.setReturnValue(renderer);
    }
}
