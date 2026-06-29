package dev.paintcraft.client.compat.iris;

import dev.paintcraft.PaintCraft;

import java.lang.reflect.Method;

/**
 * Thin reflection wrapper around the Iris API.
 * Keeps paint-craft free of a compile-time Iris dependency while still letting us
 * detect when a shader pack is active and switch to a compatible render path.
 */
public final class IrisCompat {

    /** Cached reflective handles — null if Iris is not installed or API changed. */
    private static final Method GET_INSTANCE;
    private static final Method IS_SHADER_PACK_IN_USE;

    static {
        Method getInstance = null;
        Method isShaderPackInUse = null;
        try {
            Class<?> cls = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            getInstance = cls.getMethod("getInstance");
            isShaderPackInUse = cls.getMethod("isShaderPackInUse");
            PaintCraft.LOGGER.info("[PaintCraft] Iris API detected — shader compatibility active");
        } catch (ClassNotFoundException ignored) {
            // Iris not installed, no compat needed
        } catch (Exception e) {
            PaintCraft.LOGGER.warn("[PaintCraft] Iris present but API mismatch: {}", e.getMessage());
        }
        GET_INSTANCE = getInstance;
        IS_SHADER_PACK_IN_USE = isShaderPackInUse;
    }

    /**
     * Returns true when Iris is installed AND a shader pack is currently loaded.
     * Fast: cached Method objects, one reflective invoke per call.
     */
    public static boolean isShadersActive() {
        if (GET_INSTANCE == null) return false;
        try {
            Object instance = GET_INSTANCE.invoke(null);
            return (boolean) IS_SHADER_PACK_IN_USE.invoke(instance);
        } catch (Exception e) {
            return false;
        }
    }

    private IrisCompat() {}
}
