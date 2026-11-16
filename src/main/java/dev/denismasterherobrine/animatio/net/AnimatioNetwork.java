package dev.denismasterherobrine.animatio.net;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand;
import dev.denismasterherobrine.animatio.common.AnimatioAttachments;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class AnimatioNetwork {
    /** Seconds of catch-up applied at most when a packet arrives late. */
    private static final float MAX_LAG = 5f;

    private AnimatioNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToClient(ControllerSyncPayload.TYPE, ControllerSyncPayload.STREAM_CODEC, AnimatioNetwork::handle);
        registrar.playToClient(EntityModelPayload.TYPE, EntityModelPayload.STREAM_CODEC, AnimatioNetwork::handleModel);
        registrar.playToClient(CameraViewPayload.TYPE, CameraViewPayload.STREAM_CODEC, (payload, context) -> {
            dev.denismasterherobrine.animatio.common.ClientBridge bridge = dev.denismasterherobrine.animatio.common.ClientBridge.Holder.get();
            if (bridge != null) bridge.cameraView(context.player().level().getEntity(payload.entityId()), payload.camera());
        });
        registrar.playToClient(ItemAnimationPayload.TYPE, ItemAnimationPayload.STREAM_CODEC, (payload, context) -> {
            dev.denismasterherobrine.animatio.common.ClientBridge bridge = dev.denismasterherobrine.animatio.common.ClientBridge.Holder.get();
            if (bridge != null) bridge.handleItemCommand(payload.id(), payload.time(), payload.command());
        });
    }

    private static void handleModel(EntityModelPayload payload, IPayloadContext context) {
        net.minecraft.world.entity.Entity entity = context.player().level().getEntity(payload.entityId());
        if (entity != null) dev.denismasterherobrine.animatio.common.EntityModels.applyRemote(entity, payload.model());
    }

    private static void handle(ControllerSyncPayload payload, IPayloadContext context) {
        Level level = context.player().level();
        IAttachmentHolder owner = payload.entity() ? level.getEntity(payload.entityId()) : level.getBlockEntity(payload.pos());
        if (owner == null) return;
        ControllerHolder holder = owner.getData(AnimatioAttachments.CONTROLLER);
        holder.refreshBinding();
        double now = holder.now();
        holder.controller().update(now);
        float lag = (float) Math.min(MAX_LAG, Math.max(0.0, now - payload.time()));
        for (ControllerCommand command : payload.commands()) holder.controller().applyRemote(command, lag);
    }
}
