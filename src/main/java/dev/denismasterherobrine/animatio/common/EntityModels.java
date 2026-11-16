package dev.denismasterherobrine.animatio.common;

import dev.denismasterherobrine.animatio.net.EntityModelPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Per-entity model replacement at runtime, for any entity including players. The choice is an entity attachment:
 * saved with the entity, kept by players through death, and synced to every client that tracks the entity.
 */
public final class EntityModels {
    private EntityModels() {
    }

    /** The entity's runtime model, or {@code null}. Either side; never creates the attachment. */
    public static @Nullable ResourceLocation get(Entity entity) {
        if (!entity.hasData(AnimatioAttachments.ENTITY_MODEL)) return null;
        String id = entity.getData(AnimatioAttachments.ENTITY_MODEL);
        return id.isEmpty() ? null : ResourceLocation.tryParse(id);
    }

    /**
     * Set or clear ({@code null}) the entity's model. On the server the change is saved and sent to every tracking
     * client; on a client it only changes that client's view until the server says otherwise.
     */
    public static void set(Entity entity, @Nullable ResourceLocation model) {
        String id = model == null ? "" : model.toString();
        if (id.isEmpty() && !entity.hasData(AnimatioAttachments.ENTITY_MODEL)) return;
        entity.setData(AnimatioAttachments.ENTITY_MODEL, id);
        if (!entity.level().isClientSide()) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new EntityModelPayload(entity.getId(), id));
        }
    }

    /** Tell {@code receiver} about {@code entity}'s runtime model, if it has one (start of tracking, login, respawn). */
    public static void sendTo(ServerPlayer receiver, Entity entity) {
        ResourceLocation model = get(entity);
        if (model != null) PacketDistributor.sendToPlayer(receiver, new EntityModelPayload(entity.getId(), model.toString()));
    }

    /** Client: apply a received change. */
    public static void applyRemote(Entity entity, String model) {
        entity.setData(AnimatioAttachments.ENTITY_MODEL, model);
    }
}
