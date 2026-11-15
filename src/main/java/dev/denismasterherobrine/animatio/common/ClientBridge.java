package dev.denismasterherobrine.animatio.common;

import dev.denismasterherobrine.animatio.anim.model.AnimationSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Client services used by common code. Installed by the client entry point; absent on dedicated servers,
 * so common code never loads client classes directly.
 */
public interface ClientBridge {
    /** Client animation clock in seconds: game time plus partial tick. */
    double clientTime(Level level);

    @Nullable AnimationSet animationSet(ResourceLocation model);

    /** Changes on every client resource reload. */
    int generation();

    /** Model that replaces a vanilla (or other mod's) entity's renderer, if a resource pack defines one. */
    @Nullable ResourceLocation overrideModel(Entity entity);

    /** Set up client-only parts of a new client-side holder (event context, parameter providers). */
    void onCreated(ControllerHolder holder);

    final class Holder {
        private static volatile @Nullable ClientBridge instance;

        private Holder() {
        }

        public static @Nullable ClientBridge get() {
            return instance;
        }

        public static void set(ClientBridge bridge) {
            instance = bridge;
        }
    }
}
