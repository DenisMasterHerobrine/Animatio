package dev.denismasterherobrine.animatio.client.render.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Detects Iris/Oculus shader packs through Iris' public API, without a compile-time dependency.
 * While a pack is active, models go through vanilla entity buffers so the pack's programs (shadows, G-buffers)
 * render them correctly.
 */
public final class IrisCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static final Object API;
    private static final MethodHandle IN_USE;
    private static final MethodHandle SHADOW_PASS;

    static {
        Object api = null;
        MethodHandle inUse = null, shadow = null;
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
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
}
