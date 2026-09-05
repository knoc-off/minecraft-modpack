package dev.tabsearch.compat;

import net.neoforged.fml.ModList;

/**
 * Detection gate for the Inventory Tabs integration.
 *
 * <p>This class intentionally references no Inventory Tabs types, so it is always
 * safe to load. Code that touches Inventory Tabs classes must be guarded by
 * {@link #isLoaded()} so those classes are never resolved when it is absent.
 */
public final class InventoryTabsCompat {

    private static Boolean cached;

    private InventoryTabsCompat() {}

    public static boolean isLoaded() {
        Boolean c = cached;
        if (c == null) {
            c = ModList.get() != null && ModList.get().isLoaded("inventory_tabs");
            cached = c;
        }
        return c;
    }
}
