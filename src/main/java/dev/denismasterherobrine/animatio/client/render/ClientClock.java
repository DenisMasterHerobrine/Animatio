package dev.denismasterherobrine.animatio.client.render;

import net.minecraft.world.level.Level;

/**
 * Client animation clock. Ticks run with partial tick 0 and frames with the frame's partial tick, so time read
 * from either context only moves forward: tick at G, frame at G + p, next tick at G + 1.
 */
public final class ClientClock {
    private static float partialTick;
    private static long frame;

    private ClientClock() {
    }

    public static void beginTick() {
        partialTick = 0f;
    }

    public static void beginFrame(float partial) {
        partialTick = partial;
        frame++;
    }

    public static double time(Level level) {
        return (level.getGameTime() + partialTick) / 20.0;
    }

    public static double time(Level level, float partial) {
        return (level.getGameTime() + partial) / 20.0;
    }

    public static float partialTick() {
        return partialTick;
    }

    /** Increments once per rendered frame; used to evaluate each pose at most once per frame. */
    public static long frame() {
        return frame;
    }
}
