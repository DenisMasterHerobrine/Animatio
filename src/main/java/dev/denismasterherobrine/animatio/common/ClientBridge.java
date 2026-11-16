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

    /** Client frame counter (increments once per rendered frame). */
    long frame();

    /** Model that replaces a vanilla (or other mod's) entity's renderer, if a resource pack defines one. */
    @Nullable ResourceLocation overrideModel(Entity entity);

    /** Set up client-only parts of a new client-side holder (event context, parameter providers). */
    void onCreated(ControllerHolder holder);

    /** A server command for the item stack animation state with this id. */
    void handleItemCommand(long id, double time, dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand command);

    /** Start ({@code owner} non-null) or stop looking through a model camera. */
    void cameraView(@Nullable Entity owner, String camera);

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
