package dev.denismasterherobrine.animatio.client.render.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Detects Iris/Oculus shader packs through Iris' public API, without a compile-time dependency.
 * While a pack is active, models go through vanilla entity render types so the pack's programs (shadows, G-buffers)
 * render them correctly.
 */
public final class IrisCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final Object API;
    private static final MethodHandle IN_USE;
    private static final MethodHandle SHADOW_PASS;
    /** Iris' captured render state (internal): the ids it writes into the {@code iris_Entity} vertex attribute. */
    private static final Object CAPTURED;
    private static final MethodHandle ENTITY_ID, BLOCK_ENTITY_ID, ITEM_ID;

    static {
        Object api = null;
        MethodHandle inUse = null, shadow = null;
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = lookup.findStatic(c, "getInstance", MethodType.methodType(c)).invoke();
            inUse = lookup.findVirtual(c, "isShaderPackInUse", MethodType.methodType(boolean.class));
            shadow = lookup.findVirtual(c, "isRenderingShadowPass", MethodType.methodType(boolean.class));
            LOGGER.info("Iris API found: models will use the compatibility path while a shader pack is active");
        } catch (ClassNotFoundException e) {
            // Iris not installed
        } catch (Throwable t) {
            LOGGER.warn("Iris is installed but its API could not be used; assuming no shader pack", t);
            api = null;
        }
        API = api;
        IN_USE = inUse;
        SHADOW_PASS = shadow;

        Object captured = null;
        MethodHandle entity = null, blockEntity = null, item = null;
        if (api != null) {
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                captured = c.getField("INSTANCE").get(null);
                MethodType getter = MethodType.methodType(int.class);
                entity = lookup.findVirtual(c, "getCurrentRenderedEntity", getter);
                blockEntity = lookup.findVirtual(c, "getCurrentRenderedBlockEntity", getter);
                item = lookup.findVirtual(c, "getCurrentRenderedItem", getter);
            } catch (Throwable t) {
                LOGGER.warn("Iris' render state could not be read; GPU-skinned models report entity id 0 to shader packs", t);
                captured = null;
            }
        }
        CAPTURED = captured;
        ENTITY_ID = entity;
        BLOCK_ENTITY_ID = blockEntity;
        ITEM_ID = item;
    }

    private IrisCompat() {
    }

    public static boolean isPresent() {
        return API != null;
    }

    public static boolean isShaderPackInUse() {
        if (API == null) return false;
        try {
            return (boolean) IN_USE.invoke(API);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRenderingShadowPass() {
        if (API == null) return false;
        try {
            return (boolean) SHADOW_PASS.invoke(API);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The ids Iris tags vertices with right now (from the pack's {@code entity.properties}, {@code block.properties}
     * and {@code item.properties}): entity, block entity and item, written to {@code out[0..2]}. Zeros without Iris.
     */
    public static void currentRenderIds(int[] out) {
        if (CAPTURED == null) {
            out[0] = out[1] = out[2] = 0;
            return;
        }
        try {
            out[0] = (int) ENTITY_ID.invoke(CAPTURED);
            out[1] = (int) BLOCK_ENTITY_ID.invoke(CAPTURED);
            out[2] = (int) ITEM_ID.invoke(CAPTURED);
        } catch (Throwable t) {
            out[0] = out[1] = out[2] = 0;
        }
    }
}
