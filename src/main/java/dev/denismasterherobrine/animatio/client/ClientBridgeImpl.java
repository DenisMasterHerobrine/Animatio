package dev.denismasterherobrine.animatio.client;

import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.client.model.ClientModelManager;
import dev.denismasterherobrine.animatio.client.override.EntityOverrides;
import dev.denismasterherobrine.animatio.client.render.ClientClock;
import dev.denismasterherobrine.animatio.common.ClientBridge;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

final class ClientBridgeImpl implements ClientBridge {
    @Override
    public double clientTime(Level level) {
        return ClientClock.time(level);
    }

    @Override
    public @Nullable AnimationSet animationSet(ResourceLocation model) {
        return ClientModelManager.INSTANCE.animationSet(model);
    }

    @Override
    public long frame() {
        return ClientClock.frame();
    }

    @Override
    public int generation() {
        return ClientModelManager.INSTANCE.generation() * 31 + EntityOverrides.INSTANCE.generation();
    }

    @Override
    public @Nullable ResourceLocation overrideModel(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            ResourceLocation local = AnimatioClientApi.localPlayerModel(player.getUUID());
            if (local != null) return local;
        }
        return EntityOverrides.INSTANCE.model(entity.getType());
    }

    @Override
    public void cameraView(@Nullable Entity owner, String camera) {
        if (owner == null || camera.isEmpty()) dev.denismasterherobrine.animatio.client.camera.CinematicCamera.stop();
        else dev.denismasterherobrine.animatio.client.camera.CinematicCamera.start(owner, camera);
    }

    @Override
    public void handleItemCommand(long id, double time, dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand command) {
        dev.denismasterherobrine.animatio.client.item.ItemAnimations.applyRemote(id, time, command);
    }

    @Override
    public void onCreated(ControllerHolder holder) {
        holder.controller().setEventContext(new ClientEventContext(holder));
        if (holder.owner() instanceof Entity entity) {
            holder.clientTickHook = h -> {
                ParameterProviders.updateEntity(h);
                EntityOverrides.INSTANCE.drive(h, entity);
            };
        }
    }
}
