package dev.denismasterherobrine.animatio.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps every live controller advancing once per tick, even when its owner is not rendered (so sequences,
 * {@code isPlaying} and events stay correct). Each side's set is touched only by that side's thread.
 */
public final class ControllerTicker {
    private static final Set<ControllerHolder> SERVER = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<ControllerHolder> CLIENT = Collections.newSetFromMap(new WeakHashMap<>());
    private static final List<ControllerHolder> SERVER_SCRATCH = new ArrayList<>();
    private static final List<ControllerHolder> CLIENT_SCRATCH = new ArrayList<>();

    private ControllerTicker() {
    }

    static void register(ControllerHolder holder) {
        if (holder.isClient()) CLIENT.add(holder);
        else SERVER.add(holder);
    }

    public static void tickServer() {
        tick(SERVER, SERVER_SCRATCH);
    }

    public static void tickClient() {
        tick(CLIENT, CLIENT_SCRATCH);
    }

    public static void clearClient() {
        CLIENT.clear();
    }

    public static void clearServer() {
        SERVER.clear();
    }

    public static int clientCount() {
        return CLIENT.size();
    }

    private static void tick(Set<ControllerHolder> set, List<ControllerHolder> list) {
        if (set.isEmpty()) return;
        list.clear();
        list.addAll(set);
        for (ControllerHolder holder : list) {
            if (!holder.isValid()) {
                set.remove(holder);
                continue;
            }
            holder.tick();
        }
        list.clear();
    }
}
