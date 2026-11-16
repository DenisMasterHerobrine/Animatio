package dev.denismasterherobrine.animatio.common;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * Keeps every live controller advancing once per tick, even when its owner is not rendered (so sequences,
 * {@code isPlaying} and events stay correct). Each side's list is touched only by that side's thread.
 * <p>
 * Holders are kept in a flat array of weak references (a holder whose owner was never added to a level is still
 * collected) and the array is compacted in place while ticking, so a tick allocates nothing.
 */
public final class ControllerTicker {
    private static final Registry SERVER = new Registry();
    private static final Registry CLIENT = new Registry();

    private ControllerTicker() {
    }

    private static final class Registry {
        @SuppressWarnings("unchecked")
        private WeakReference<ControllerHolder>[] refs = new WeakReference[64];
        private int size;

        /** Holders registered while ticking (an event spawning an entity); appended after the tick. */
        @SuppressWarnings("unchecked")
        private WeakReference<ControllerHolder>[] pending = new WeakReference[8];
        private int pendingSize;
        private boolean ticking;

        void add(ControllerHolder holder) {
            WeakReference<ControllerHolder> ref = new WeakReference<>(holder);
            if (ticking) {
                if (pendingSize == pending.length) pending = Arrays.copyOf(pending, pendingSize * 2);
                pending[pendingSize++] = ref;
                return;
            }
            if (size == refs.length) refs = Arrays.copyOf(refs, size * 2);
            refs[size++] = ref;
        }

        void tick() {
            WeakReference<ControllerHolder>[] r = refs;
            int n = size, live = 0;
            ticking = true;
            try {
                for (int i = 0; i < n; i++) {
                    ControllerHolder holder = r[i].get();
                    if (holder == null || !holder.isValid()) continue;
                    r[live++] = r[i];
                    holder.tick();
                }
                Arrays.fill(r, live, n, null);
                size = live;
            } finally {
                ticking = false;
            }
            for (int i = 0; i < pendingSize; i++) {
                if (pending[i].get() != null) {
                    if (size == refs.length) refs = Arrays.copyOf(refs, size * 2);
                    refs[size++] = pending[i];
                }
                pending[i] = null;
            }
            pendingSize = 0;
        }

        void clear() {
            Arrays.fill(refs, 0, size, null);
            size = 0;
        }
    }

    static void register(ControllerHolder holder) {
        (holder.isClient() ? CLIENT : SERVER).add(holder);
    }

    public static void tickServer() {
        SERVER.tick();
    }

    public static void tickClient() {
        CLIENT.tick();
    }

    public static void clearClient() {
        CLIENT.clear();
    }

    public static void clearServer() {
        SERVER.clear();
    }

    /** Registered client holders (including ones not yet found dead). */
    public static int clientCount() {
        return CLIENT.size;
    }
}
