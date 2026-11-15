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
