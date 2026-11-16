package dev.denismasterherobrine.animatio.client.item;

import dev.denismasterherobrine.animatio.anim.runtime.ControllerCommand;
import dev.denismasterherobrine.animatio.anim.runtime.ControllerImpl;
import dev.denismasterherobrine.animatio.client.render.AnimatedInstance;
import dev.denismasterherobrine.animatio.common.AnimatioComponents;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Animation state per item stack, on the client. A stack with an {@code animatio:animation_id} component (assigned by
 * {@code AnimatioApi.itemAnimationId} or when the server plays an animation on it) keeps its state wherever it goes
 * and receives server commands; any other stack gets state tied to that stack object. Entries nobody rendered for
 * {@link #EXPIRY_SECONDS} are dropped. Render thread only.
 */
public final class ItemAnimations {
    private static final double EXPIRY_SECONDS = 30;
    private static final Long2ObjectOpenHashMap<State> BY_ID = new Long2ObjectOpenHashMap<>();
    private static final Map<ItemStack, State> BY_STACK = new WeakHashMap<>();
    private static double lastSweep;

    private ItemAnimations() {
    }

    /** One stack's controller and render state. */
    public static final class State {
        final ControllerImpl controller = new ControllerImpl(ControllerImpl.Side.CLIENT);
        final AnimatedInstance instance = new AnimatedInstance();
        @Nullable Object boundModel;
        boolean ambientStarted;
        double lastUsed;

        public ControllerImpl controller() {
            return controller;
        }
    }

    /** Seconds on the client's clock (level time, or wall time without a level). */
    static double now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false)) / 20.0 : System.nanoTime() / 1e9;
    }

    /** The state of a stack, created on first use. */
    public static State state(ItemStack stack) {
        double now = now();
        sweep(now);
        Long id = stack.get(AnimatioComponents.ANIMATION_ID.get());
        State s = id != null ? BY_ID.computeIfAbsent(id.longValue(), k -> new State()) : BY_STACK.computeIfAbsent(stack, k -> new State());
        s.lastUsed = now;
        return s;
    }

    /** A command the server issued for the stack with this id (it may arrive before the stack is ever rendered). */
    public static void applyRemote(long id, double time, ControllerCommand command) {
        double now = now();
        State s = BY_ID.computeIfAbsent(id, k -> new State());
        s.lastUsed = now;
        s.controller.update(now);
        s.controller.applyRemote(command, (float) Math.max(0, Math.min(5, now - time)));
    }

    private static void sweep(double now) {
        if (Math.abs(now - lastSweep) < 5) return;
        lastSweep = now;
        for (var it = BY_ID.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<State> e = it.next();
            if (Math.abs(now - e.getValue().lastUsed) > EXPIRY_SECONDS) it.remove();
        }
    }

    /** Forget everything (leaving a world). */
    public static void clear() {
        BY_ID.clear();
        BY_STACK.clear();
    }
}
