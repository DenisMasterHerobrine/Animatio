package dev.denismasterherobrine.animatio.common;

import dev.denismasterherobrine.animatio.api.GltfAnimatable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which model an animated object uses.
 */
public final class ModelBindings {
    private static final Map<EntityType<?>, ResourceLocation> ENTITIES = new ConcurrentHashMap<>();
    private static final Map<BlockEntityType<?>, ResourceLocation> BLOCK_ENTITIES = new ConcurrentHashMap<>();

    private ModelBindings() {
    }

    public static void bind(EntityType<?> type, ResourceLocation model) {
        ENTITIES.put(type, model);
    }

    public static void bind(BlockEntityType<?> type, ResourceLocation model) {
        BLOCK_ENTITIES.put(type, model);
    }

    public static @Nullable ResourceLocation entityTypeModel(EntityType<?> type) {
        return ENTITIES.get(type);
    }

    public static @Nullable ResourceLocation blockEntityTypeModel(BlockEntityType<?> type) {
        return BLOCK_ENTITIES.get(type);
    }

    /**
     * Resolution order: a runtime per-entity model ({@link EntityModels}), {@link GltfAnimatable}, the type binding,
     * then (client only) a client-side choice: a per-player model set through the client API or a resource-pack
     * entity override.
     */
    public static @Nullable ResourceLocation modelFor(Object owner, boolean client) {
        if (owner instanceof Entity e) {
            ResourceLocation id = EntityModels.get(e);
            if (id != null) return id;
        }
        if (owner instanceof GltfAnimatable a) {
            ResourceLocation id = a.animatioModel();
            if (id != null) return id;
        }
        if (owner instanceof Entity e) {
            ResourceLocation id = ENTITIES.get(e.getType());
            if (id != null) return id;
            if (client) {
                ClientBridge bridge = ClientBridge.Holder.get();
                if (bridge != null) return bridge.overrideModel(e);
            }
            return null;
        }
        if (owner instanceof BlockEntity be) return BLOCK_ENTITIES.get(be.getType());
        return null;
    }
}
