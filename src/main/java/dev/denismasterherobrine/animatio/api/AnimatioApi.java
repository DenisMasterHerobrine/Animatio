package dev.denismasterherobrine.animatio.api;

import dev.denismasterherobrine.animatio.api.event.AnimationEvent;
import dev.denismasterherobrine.animatio.common.AnimatioAttachments;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import dev.denismasterherobrine.animatio.common.ModelBindings;
import dev.denismasterherobrine.animatio.data.ModelConfigs;
import dev.denismasterherobrine.animatio.event.CustomAnimationEvent;
import dev.denismasterherobrine.animatio.event.EventType;
import dev.denismasterherobrine.animatio.event.EventTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Entry point for mods using Animatio.
 * <pre>{@code
 * // mod constructor (common code, runs on both sides)
 * AnimatioApi.bindModel(MY_NPC.get(), ResourceLocation.fromNamespaceAndPath("mymod", "npc"));
 *
 * // anywhere in game logic, on either side
 * AnimatioApi.controller(npc).play(JUMP.thenWaitSecs(10).thenPlay(FALL).thenHoldLastFrame());
 * }</pre>
 * The model {@code mymod:npc} is loaded from {@code assets/mymod/animatio/models/npc.glb}.
 */
public final class AnimatioApi {
    private AnimatioApi() {
    }

    /** The animation controller of an entity (created on first use). */
    public static AnimationController controller(Entity entity) {
        return entity.getData(AnimatioAttachments.CONTROLLER).controller();
    }

    /** The animation controller of a block entity (created on first use). */
    public static AnimationController controller(BlockEntity blockEntity) {
        return blockEntity.getData(AnimatioAttachments.CONTROLLER).controller();
    }

    /** True if the object already has a controller. */
    public static boolean hasController(IAttachmentHolder owner) {
        return owner.hasData(AnimatioAttachments.CONTROLLER);
    }

    /** Internal state behind a controller (model binding, render state). */
    public static ControllerHolder holder(IAttachmentHolder owner) {
        return owner.getData(AnimatioAttachments.CONTROLLER);
    }

    /** Use {@code model} for every entity of {@code type}. Call from common code during mod construction. */
    public static void bindModel(EntityType<?> type, ResourceLocation model) {
        ModelBindings.bind(type, model);
    }

    /** Use {@code model} for every block entity of {@code type}. Call from common code during mod construction. */
    public static void bindModel(BlockEntityType<?> type, ResourceLocation model) {
        ModelBindings.bind(type, model);
    }

    /**
     * Give one entity a different model at runtime, or {@code null} to return to its normal model (vanilla rendering,
     * a type binding or a resource-pack override). Works for any entity, including players: call on the server, the
     * choice is saved with the entity, kept by players through death and synced to every client that sees it.
     * Entities without an Animatio renderer of their own are drawn by a generic glTF renderer (players keep vanilla
     * first-person arms).
     * <pre>{@code
     * AnimatioApi.setModel(serverPlayer, ResourceLocation.fromNamespaceAndPath("mymod", "knight"));
     * }</pre>
     */
    public static void setModel(Entity entity, @Nullable ResourceLocation model) {
        dev.denismasterherobrine.animatio.common.EntityModels.set(entity, model);
    }

    // ------------------------------------------------------------------ cameras

    /**
     * Server: make {@code viewer} look through a camera of {@code owner}'s model (a glTF camera's node name, or any
     * bone or socket) until {@link #stopCameraView}. The camera follows the animation, so a cutscene is a clip that
     * animates the camera node, played on the owner as usual.
     */
    public static void startCameraView(net.minecraft.server.level.ServerPlayer viewer, Entity owner, String camera) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer, new dev.denismasterherobrine.animatio.net.CameraViewPayload(owner.getId(), camera));
    }

    public static void stopCameraView(net.minecraft.server.level.ServerPlayer viewer) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer, new dev.denismasterherobrine.animatio.net.CameraViewPayload(-1, ""));
    }

    // ------------------------------------------------------------------ item stacks

    private static final java.util.concurrent.atomic.AtomicInteger ITEM_TRACK_IDS = new java.util.concurrent.atomic.AtomicInteger(1);

    /**
     * The stack's animation identity ({@code animatio:animation_id}), assigned on first call. Stacks with an identity
     * keep their animation state wherever they go and can be animated from the server. Note that stacks with
     * different identities no longer stack with each other, so use this for tools, weapons and other unstackables.
     */
    public static long itemAnimationId(net.minecraft.world.item.ItemStack stack) {
        Long id = stack.get(dev.denismasterherobrine.animatio.common.AnimatioComponents.ANIMATION_ID.get());
        if (id != null) return id;
        long fresh = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        stack.set(dev.denismasterherobrine.animatio.common.AnimatioComponents.ANIMATION_ID.get(), fresh);
        return fresh;
    }

    /**
     * Server: play an animation on one item stack held or worn by {@code holder}, for every client that sees the
     * holder. The stack gets an animation identity if it has none ({@link #itemAnimationId}).
     */
    public static void playOnItem(Entity holder, net.minecraft.world.item.ItemStack stack, dev.denismasterherobrine.animatio.api.playable.Playable playable,
                                  dev.denismasterherobrine.animatio.api.playback.PlayOptions options) {
        sendItemCommand(holder, stack, new dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand.Play(ITEM_TRACK_IDS.getAndIncrement(), playable, options));
    }

    public static void playOnItem(Entity holder, net.minecraft.world.item.ItemStack stack, dev.denismasterherobrine.animatio.api.playable.Playable playable) {
        playOnItem(holder, stack, playable, dev.denismasterherobrine.animatio.api.playback.PlayOptions.defaults());
    }

    /** Server: stop an animation on one item stack (see {@link #playOnItem}). */
    public static void stopOnItem(Entity holder, net.minecraft.world.item.ItemStack stack, dev.denismasterherobrine.animatio.api.playable.Playable playable) {
        sendItemCommand(holder, stack, new dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand.Stop(
                dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand.Target.key(playable.key()), null));
    }

    private static void sendItemCommand(Entity holder, net.minecraft.world.item.ItemStack stack, dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand command) {
        if (holder.level().isClientSide()) throw new IllegalStateException("playOnItem/stopOnItem are server-side; on the client use AnimatioClientApi.itemController");
        long id = itemAnimationId(stack);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(holder,
                new dev.denismasterherobrine.animatio.net.ItemAnimationPayload(id, holder.level().getGameTime() / 20.0, command));
    }

    /** The runtime model set with {@link #setModel}, or {@code null}. */
    public static @Nullable ResourceLocation runtimeModel(Entity entity) {
        return dev.denismasterherobrine.animatio.common.EntityModels.get(entity);
    }

    /** The model an entity is drawn with right now (runtime model, binding or override), or {@code null}. */
    public static @Nullable ResourceLocation currentModel(Entity entity) {
        return ModelBindings.modelFor(entity, entity.level().isClientSide());
    }

    /** The model bound to an entity type, or {@code null}. */
    public static @Nullable ResourceLocation boundModel(EntityType<?> type) {
        return ModelBindings.entityTypeModel(type);
    }

    /**
     * Configure a model in code (clip loop flags, events, blend spaces, transitions, sockets). Merged over the
     * model's JSON definition; call from common code so both sides agree.
     */
    public static void configureModel(ResourceLocation model, Consumer<ModelConfig> config) {
        ModelConfigs.configure(model, config);
    }

    /**
     * Handle {@link Events#custom(ResourceLocation)} events (and {@code "type": "animatio:custom"} events in JSON).
     * Handlers run on the client.
     */
    public static void registerEventHandler(ResourceLocation id, CustomAnimationEvent.Handler handler) {
        CustomAnimationEvent.register(id, handler);
    }

    /** Register a serialisable event type of your own (on both sides, during mod construction). */
    public static <T extends AnimationEvent> EventType<T> registerEventType(EventType<T> type) {
        return EventTypes.register(type);
    }
}
