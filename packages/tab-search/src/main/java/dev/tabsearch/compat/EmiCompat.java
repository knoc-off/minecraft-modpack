package dev.tabsearch.compat;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.search.EmiSearch;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Detection gate and query bridge for the EMI integration.
 *
 * <p>Guard every call other than {@link #isLoaded()} with {@code isLoaded()} first --
 * the JVM resolves EMI types the first time a method touching them is executed, so an
 * unguarded call would throw NoClassDefFoundError when EMI is absent.
 */
public final class EmiCompat {

    private static Boolean cached;

    private EmiCompat() {}

    public static boolean isLoaded() {
        Boolean c = cached;
        if (c == null) {
            c = ModList.get() != null && ModList.get().isLoaded("emi");
            cached = c;
        }
        return c;
    }

    /** True only when the user has toggled EMI's highlight mode and typed a query. */
    public static boolean isHighlightActive() {
        return EmiScreenManager.search != null
            && EmiScreenManager.search.highlight
            && EmiSearch.compiledQuery != null
            && !EmiSearch.compiledQuery.isEmpty();
    }

    public static boolean matches(ItemStack stack) {
        return EmiSearch.compiledQuery.test(EmiStack.of(stack));
    }
}
