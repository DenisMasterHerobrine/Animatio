package dev.denismasterherobrine.animatio.client.override;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.animatio.api.playable.AnimationRef;
import dev.denismasterherobrine.animatio.api.playback.PlayOptions;
import dev.denismasterherobrine.animatio.client.attach.Attachments;
import dev.denismasterherobrine.animatio.client.entity.GltfEntityRenderer;
import dev.denismasterherobrine.animatio.common.ControllerHolder;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resource-pack entity overrides: loading, the replacement renderers and the client-side animation driver.
 */
public final class EntityOverrides extends SimplePreparableReloadListener<Map<ResourceLocation, EntityOverride>> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final String DIRECTORY = "animatio/entity_overrides";
    public static final EntityOverrides INSTANCE = new EntityOverrides();

    private volatile Map<EntityType<?>, EntityOverride> byType = Map.of();
    private final Map<EntityType<?>, EntityRenderer<?>> renderers = new IdentityHashMap<>();
    private final Map<ControllerHolder, DriverState> driverStates = new WeakHashMap<>();
    private final AtomicInteger generation = new AtomicInteger(1);

    private static final class DriverState {
        boolean started, dead, swinging;
        int hurtTime;
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
            if (type.get() == EntityType.PLAYER) {
                LOGGER.warn("Entity overrides for players are not supported");
                continue;
            }
            map.put(type.get(), e.getValue());
        }
        byType = map;
        renderers.clear();
        driverStates.clear();
        generation.incrementAndGet();
        if (!map.isEmpty()) LOGGER.info("Loaded {} Animatio entity override(s)", map.size());
    }

    /** The replacement renderer for an entity, or {@code null} to keep the normal one. Render thread only. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public @Nullable EntityRenderer<?> renderer(Entity entity, EntityRenderDispatcher dispatcher) {
        if (entity instanceof Player) return null;
        EntityOverride o = byType.get(entity.getType());
        if (o == null) return null;
        return renderers.computeIfAbsent(entity.getType(), t -> {
            Minecraft mc = Minecraft.getInstance();
            EntityRendererProvider.Context ctx = new EntityRendererProvider.Context(dispatcher, mc.getItemRenderer(),
                    mc.getBlockRenderer(), dispatcher.getItemInHandRenderer(), mc.getResourceManager(), mc.getEntityModels(), mc.font);
            GltfEntityRenderer renderer = new GltfEntityRenderer<>(ctx, o.model()).deathFlip(o.deathFlip());
            for (EntityOverride.AttachmentDef a : o.attachments()) {
                switch (a.type()) {
                    case "held_item" -> renderer.withAttachment(a.bone(), Attachments.heldItem(a.armValue()));
                    case "armor" -> renderer.withAttachment(a.bone(), Attachments.armor(a.slotValue(),
                            Attachments.ArmorPiece.valueOf(a.piece().orElse("head").toUpperCase(java.util.Locale.ROOT))));
                    case "equipped" -> renderer.withAttachment(a.bone(), Attachments.equipped(a.slotValue(), a.displayValue()));
                    case "cape" -> a.texture().ifPresent(tex -> renderer.withAttachment(a.bone(), Attachments.cape(e -> tex)));
                    default -> LOGGER.warn("Unknown attachment type '{}' in entity override for {}", a.type(), BuiltInRegistries.ENTITY_TYPE.getKey(t));
                }
            }
            return renderer;
        });
    }

    /** Client tick: play clips from the entity's state. */
    public void drive(ControllerHolder holder, Entity entity) {
        EntityOverride o = byType.get(entity.getType());
        if (o == null) return;
        EntityOverride.Driver d = o.driver();
        DriverState s = driverStates.computeIfAbsent(holder, h -> new DriverState());
        var ctl = holder.controller();
        if (!s.started) {
            s.started = true;
            d.locomotion().ifPresent(name -> ctl.play(AnimationRef.of(name).looping(), PlayOptions.defaults().fadeIn(dev.denismasterherobrine.animatio.api.playback.TransitionConfig.NONE)));
        }
        if (!(entity instanceof LivingEntity living)) return;
        if (living.deathTime > 0) {
            if (!s.dead && d.death().isPresent()) {
                s.dead = true;
                ctl.play(AnimationRef.of(d.death().get()).once().thenHoldLastFrame(), PlayOptions.defaults().priority(10));
            }
            return;
        }
        if (living.hurtTime > 0 && s.hurtTime == 0 && d.hurt().isPresent()) {
            ctl.play(AnimationRef.of(d.hurt().get()).once(), PlayOptions.defaults().priority(5));
        }
        s.hurtTime = living.hurtTime;
        boolean swinging = living.swinging;
        if (swinging && !s.swinging && d.attack().isPresent()) {
            ctl.play(AnimationRef.of(d.attack().get()).once(), PlayOptions.defaults().priority(6));
        }
        s.swinging = swinging;
    }
}
