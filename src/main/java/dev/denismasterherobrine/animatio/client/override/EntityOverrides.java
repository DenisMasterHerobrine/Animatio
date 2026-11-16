package dev.denismasterherobrine.animatio.client.override;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.api.playback.TransitionConfig;
import dev.denismasterherobrine.animatio.client.attach.Attachments;
import dev.denismasterherobrine.animatio.client.entity.GltfEntityRenderer;
import dev.denismasterherobrine.animatio.client.player.GltfPlayerRenderer;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import dev.denismasterherobrine.animatio.common.EntityModels;
import dev.denismasterherobrine.animatio.common.ModelBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resource-pack entity overrides, the replacement renderers they (and runtime models and player models) need, and the
 * client-side animation driver.
 * <p>
 * Renderer choice for an entity: players with a model get a {@link GltfPlayerRenderer}; an entity with a runtime
 * model ({@code AnimatioApi.setModel}) whose type has no glTF renderer of its own gets a generic
 * {@link GltfEntityRenderer}; an entity type with a resource-pack override gets that override's renderer.
 */
public final class EntityOverrides extends SimplePreparableReloadListener<Map<ResourceLocation, EntityOverride>> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final String DIRECTORY = "animatio/entity_overrides";
    public static final EntityOverrides INSTANCE = new EntityOverrides();

    /** Attachments for players and generic runtime-model entities without an override file (sockets by convention). */
    private static final List<EntityOverride.AttachmentDef> DEFAULT_ATTACHMENTS = List.of(
            def("right_hand", "held_item", "right", null, null),
            def("left_hand", "held_item", "left", null, null),
            def("head", "armor", null, "head", "head"),
            def("body", "armor", null, "chest", "body"),
            def("right_arm", "armor", null, "chest", "right_arm"),
            def("left_arm", "armor", null, "chest", "left_arm"),
            def("right_leg", "armor", null, "legs", "right_leg"),
            def("left_leg", "armor", null, "legs", "left_leg"),
            def("right_foot", "armor", null, "feet", "right_leg"),
            def("left_foot", "armor", null, "feet", "left_leg"));

    private static EntityOverride.AttachmentDef def(String bone, String type, @Nullable String arm, @Nullable String slot, @Nullable String piece) {
        return new EntityOverride.AttachmentDef(bone, type, Optional.ofNullable(arm), Optional.ofNullable(slot), Optional.ofNullable(piece),
                Optional.empty(), Optional.empty());
    }

    private volatile Map<EntityType<?>, EntityOverride> byType = Map.of();
    private final Map<EntityType<?>, EntityRenderer<?>> renderers = new IdentityHashMap<>();
    private final Map<EntityType<?>, EntityRenderer<?>> runtimeRenderers = new IdentityHashMap<>();
    private @Nullable GltfPlayerRenderer widePlayer, slimPlayer;
    private final Map<ControllerHolder, DriverState> driverStates = new WeakHashMap<>();
    private final AtomicInteger generation = new AtomicInteger(1);

    private static final class DriverState {
        @Nullable ResourceLocation model;
        boolean started, dead, swinging;
        int hurtTime;
        final int[] stateTracks = new int[6];
        final boolean[] stateActive = new boolean[6];
    }

    private EntityOverrides() {
    }

    public int generation() {
        return generation.get();
    }

    public @Nullable ResourceLocation model(EntityType<?> type) {
        EntityOverride o = byType.get(type);
        return o == null ? null : o.model();
    }

    public boolean isEmpty() {
        return byType.isEmpty();
    }

    @Override
    protected Map<ResourceLocation, EntityOverride> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, EntityOverride> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, Resource> e : manager.listResources(DIRECTORY, rl -> rl.getPath().endsWith(".json")).entrySet()) {
            String rest = e.getKey().getPath().substring(DIRECTORY.length() + 1, e.getKey().getPath().length() - 5);
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                LOGGER.warn("Entity override {} must be at {}/<entity namespace>/<entity path>.json", e.getKey(), DIRECTORY);
                continue;
            }
            ResourceLocation entity = ResourceLocation.tryBuild(rest.substring(0, slash), rest.substring(slash + 1));
            if (entity == null) continue;
            try (Reader reader = new InputStreamReader(e.getValue().open(), StandardCharsets.UTF_8)) {
                EntityOverride.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                        .resultOrPartial(err -> LOGGER.error("Invalid entity override {}: {}", e.getKey(), err))
                        .ifPresent(o -> out.put(entity, o));
            } catch (Exception ex) {
                LOGGER.error("Could not read entity override {}", e.getKey(), ex);
            }
        }
        return out;
    }

    @Override
    protected void apply(Map<ResourceLocation, EntityOverride> prepared, ResourceManager manager, ProfilerFiller profiler) {
        Map<EntityType<?>, EntityOverride> map = new IdentityHashMap<>();
        for (Map.Entry<ResourceLocation, EntityOverride> e : prepared.entrySet()) {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(e.getKey());
            if (type.isEmpty()) {
                LOGGER.warn("Entity override for unknown entity type {}", e.getKey());
                continue;
            }
            map.put(type.get(), e.getValue());
        }
        byType = map;
        renderers.clear();
        runtimeRenderers.clear();
        widePlayer = slimPlayer = null;
        driverStates.clear();
        generation.incrementAndGet();
        if (!map.isEmpty()) LOGGER.info("Loaded {} Animatio entity override(s)", map.size());
    }

    // ------------------------------------------------------------------ renderers

    private static EntityRendererProvider.Context context(EntityRenderDispatcher dispatcher) {
        Minecraft mc = Minecraft.getInstance();
        return new EntityRendererProvider.Context(dispatcher, mc.getItemRenderer(), mc.getBlockRenderer(),
                dispatcher.getItemInHandRenderer(), mc.getResourceManager(), mc.getEntityModels(), mc.font);
    }

    /**
     * The replacement renderer for an entity, or {@code null} to keep the normal one. Render thread only.
     *
     * @param normal the renderer the dispatcher would use, or {@code null} if unknown
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public @Nullable EntityRenderer<?> renderer(Entity entity, EntityRenderDispatcher dispatcher, @Nullable EntityRenderer<?> normal) {
        if (entity instanceof AbstractClientPlayer player) return playerRenderer(player, dispatcher);
        EntityOverride o = byType.get(entity.getType());
        if (EntityModels.get(entity) != null && !(normal instanceof GltfEntityRenderer) && o == null) {
            return runtimeRenderers.computeIfAbsent(entity.getType(), t -> {
                GltfEntityRenderer r = new GltfEntityRenderer<>(context(dispatcher));
                addAttachments(r, DEFAULT_ATTACHMENTS, t);
                return r;
            });
        }
        if (o == null) return null;
        return renderers.computeIfAbsent(entity.getType(), t -> {
            GltfEntityRenderer renderer = new GltfEntityRenderer<>(context(dispatcher), o.model()).deathFlip(o.deathFlip());
            addAttachments(renderer, o.attachments(), t);
            return renderer;
        });
    }

    private @Nullable GltfPlayerRenderer playerRenderer(AbstractClientPlayer player, EntityRenderDispatcher dispatcher) {
        if (ModelBindings.modelFor(player, true) == null) return null;
        boolean slim = player.getSkin().model() == net.minecraft.client.resources.PlayerSkin.Model.SLIM;
        GltfPlayerRenderer r = slim ? slimPlayer : widePlayer;
        if (r == null) {
            r = new GltfPlayerRenderer(context(dispatcher), slim);
            EntityOverride o = byType.get(EntityType.PLAYER);
            addAttachments(r.body(), o != null && !o.attachments().isEmpty() ? o.attachments() : DEFAULT_ATTACHMENTS, EntityType.PLAYER);
            if (o == null || o.attachments().isEmpty()) {
                r.body().withAttachment("cape", Attachments.cape(p -> p.isModelPartShown(PlayerModelPart.CAPE) ? p.getSkin().capeTexture() : null));
            }
            if (o != null) r.body().deathFlip(o.deathFlip());
            if (slim) slimPlayer = r;
            else widePlayer = r;
        }
        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addAttachments(GltfEntityRenderer renderer, List<EntityOverride.AttachmentDef> defs, EntityType<?> type) {
        for (EntityOverride.AttachmentDef a : defs) {
            switch (a.type()) {
                case "held_item" -> renderer.withAttachment(a.bone(), Attachments.heldItem(a.armValue()));
                case "armor" -> renderer.withAttachment(a.bone(), Attachments.armor(a.slotValue(),
                        Attachments.ArmorPiece.valueOf(a.piece().orElse("head").toUpperCase(java.util.Locale.ROOT))));
                case "equipped" -> renderer.withAttachment(a.bone(), Attachments.equipped(a.slotValue(), a.displayValue()));
                case "cape" -> a.texture().ifPresent(tex -> renderer.withAttachment(a.bone(), Attachments.cape(e -> tex)));
                default -> LOGGER.warn("Unknown attachment type '{}' for {}", a.type(), BuiltInRegistries.ENTITY_TYPE.getKey(type));
            }
        }
    }

    // ------------------------------------------------------------------ driver

    /** The driver for an entity: its override's, the conventional one for players and runtime models, or none. */
    private EntityOverride.@Nullable Driver driver(Entity entity) {
        EntityOverride o = byType.get(entity.getType());
        if (o != null) return o.driver();
        if (entity instanceof net.minecraft.world.entity.player.Player) return EntityOverride.Driver.CONVENTIONAL;
        if (EntityModels.get(entity) != null && ModelBindings.entityTypeModel(entity.getType()) == null) return EntityOverride.Driver.CONVENTIONAL;
        return null;
    }

    private static boolean has(@Nullable AnimationSet set, Optional<String> name) {
        return set != null && name.isPresent() && (set.clip(name.get()) != null || set.blendSpace(name.get()) != null);
    }

    /** Client tick: play clips from the entity's state. */
    public void drive(ControllerHolder holder, Entity entity) {
        EntityOverride.Driver d = holder.model() != null ? driver(entity) : null;
        if (d == null) {
            driverStates.remove(holder);
            return;
        }
        ControllerImpl ctl = holder.controller();
        AnimationSet set = ctl.animationSet();
        if (set == null) return;
        DriverState s = driverStates.computeIfAbsent(holder, h -> new DriverState());
        if (!holder.model().equals(s.model)) {
            DriverState fresh = new DriverState();
            fresh.model = holder.model();
            driverStates.put(holder, fresh);
            s = fresh;
        }
        if (!s.started) {
            s.started = true;
            if (has(set, d.locomotion())) {
                ctl.play(AnimationRef.of(d.locomotion().get()).looping(), PlayOptions.defaults().fadeIn(TransitionConfig.NONE));
            }
        }
        if (!(entity instanceof LivingEntity living)) return;
        if (living.deathTime > 0) {
            if (!s.dead && has(set, d.death())) {
                s.dead = true;
                ctl.play(AnimationRef.of(d.death().get()).once().thenHoldLastFrame(), PlayOptions.defaults().priority(10));
            }
            return;
        }
        if (s.dead) { // respawned in place (players) or revived
            s.dead = false;
            if (has(set, d.death())) ctl.stop(AnimationRef.of(d.death().get()));
        }
        if (living.hurtTime > 0 && s.hurtTime == 0 && has(set, d.hurt())) {
            ctl.play(AnimationRef.of(d.hurt().get()).once(), PlayOptions.defaults().priority(5));
        }
        s.hurtTime = living.hurtTime;
        boolean swinging = living.swinging;
        if (swinging && !s.swinging && has(set, d.attack())) {
            ctl.play(AnimationRef.of(d.attack().get()).once(), PlayOptions.defaults().priority(6));
        }
        s.swinging = swinging;

        state(ctl, set, s, 0, d.crouch(), entity.isCrouching() && !living.isSleeping());
        state(ctl, set, s, 1, d.swim(), entity.isVisuallySwimming());
        state(ctl, set, s, 2, d.fly(), living.isFallFlying());
        state(ctl, set, s, 3, d.ride(), entity.isPassenger());
        state(ctl, set, s, 4, d.sleep(), living.isSleeping());
        state(ctl, set, s, 5, d.use(), living.isUsingItem());
    }

    /** A clip that loops while a state holds, on layer 3 above locomotion. */
    private static void state(ControllerImpl ctl, AnimationSet set, DriverState s, int index, Optional<String> clip, boolean active) {
        if (active == s.stateActive[index]) return;
        s.stateActive[index] = active;
        if (!has(set, clip)) return;
        if (active) s.stateTracks[index] = ctl.play(AnimationRef.of(clip.get()).looping(), PlayOptions.defaults().priority(3 + index));
        else ctl.stop(s.stateTracks[index]);
    }

}
