package dev.paintcraft.compat.create;

import net.neoforged.fml.ModList;

/**
 * Detection gate for the optional Create integration.
 *
 * <p>This class intentionally references no Create types, so it is always safe to
 * load. Code that touches Create classes must be guarded by {@link #isLoaded()} so
 * those classes are never resolved when Create is absent.
 */
public final class CreateCompat {

    private static Boolean cached;

    private CreateCompat() {}

    public static boolean isLoaded() {
        Boolean c = cached;
        if (c == null) {
            c = ModList.get() != null && ModList.get().isLoaded("create");
            cached = c;
        }
        return c;
    }
}
