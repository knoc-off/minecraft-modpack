package dev.structurestash.compat;

import net.neoforged.fml.ModList;

/**
 * Runtime detection of the Chisels &amp; Bits mod.
 * <p>
 * This class deliberately contains <b>no</b> references to any
 * {@code mod.chiselsandbits.*} type, so it can always be loaded — even when
 * C&amp;B is absent. All structure-stash code that would touch a C&amp;B class
 * must first check {@link #isLoaded()} so the C&amp;B-referencing code path is
 * never executed (and the C&amp;B classes never linked) when the mod is gone.
 */
public final class CnBCompat {

    private CnBCompat() {}

    private static Boolean loaded;

    /** True if Chisels &amp; Bits is installed. Result is cached after first call. */
    public static boolean isLoaded() {
        Boolean l = loaded;
        if (l == null) {
            l = ModList.get().isLoaded("chiselsandbits");
            loaded = l;
        }
        return l;
    }
}
