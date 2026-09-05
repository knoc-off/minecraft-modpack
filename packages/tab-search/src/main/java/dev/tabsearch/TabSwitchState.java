package dev.tabsearch;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Holds the tab Inventory Tabs is switching AWAY FROM, for the duration of a
 * programmatic tab switch. Written by {@link dev.tabsearch.mixin.TabSwitchTracker},
 * read by {@link dev.tabsearch.compat.ChestTrackerRedirect}.
 *
 * <p>Deliberately NOT part of the mixin class: Mixin merges every non-private member
 * of a mixin into its target class, so a public API method here (like
 * {@code consumeOutgoingTab}) makes Mixin reject the whole mixin with
 * "contains non-private static method" -- it can't be reasoned about as a stray
 * addition to folk.sisby.inventory_tabs.TabManager. This class is plain Java, never
 * merged into anything, so it's free to expose a public API.
 */
public final class TabSwitchState {

    private static MethodHandle currentTabGetter;
    private static boolean resolveFailed;

    private static Object pendingOutgoing;
    private static boolean pending;
    private static Object outgoingTab;

    private TabSwitchState() {}

    /**
     * Inventory Tabs' {@code TabManager.currentTab}, read via reflection rather than
     * a mixin {@code @Shadow}: it's declared as {@code Tab currentTab} in the real
     * class, and {@code Tab} isn't on our compile classpath. A bare
     * {@code @Shadow Object currentTab} fails to match -- Mixin matches shadow fields
     * by name AND erased descriptor, and {@code Ljava/lang/Object;} doesn't match
     * {@code Lfolk/sisby/inventory_tabs/tabs/Tab;}.
     */
    public static Object currentTab() {
        if (resolveFailed) {
            return null;
        }
        try {
            if (currentTabGetter == null) {
                Class<?> tabManager = Class.forName("folk.sisby.inventory_tabs.TabManager");
                currentTabGetter = MethodHandles.publicLookup()
                    .findStaticGetter(tabManager, "currentTab", Class.forName("folk.sisby.inventory_tabs.tabs.Tab"));
            }
            return currentTabGetter.invoke();
        } catch (Throwable t) {
            resolveFailed = true;
            TabSearch.LOGGER.warn("Inventory Tabs tab-switch tracking disabled: incompatible API", t);
            return null;
        }
    }

    public static void stashCandidate() {
        pendingOutgoing = currentTab();
        pending = true;
    }

    public static void commitCandidate() {
        if (pending) {
            outgoingTab = pendingOutgoing;
            pending = false;
        }
    }

    /** The tab that was current immediately before the in-flight programmatic switch, or null if none is in flight. */
    public static Object consumeOutgoingTab() {
        Object tab = outgoingTab;
        outgoingTab = null;
        return tab;
    }
}
