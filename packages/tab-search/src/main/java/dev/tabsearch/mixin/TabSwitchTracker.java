package dev.tabsearch.mixin;

import dev.tabsearch.TabSwitchState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the tab Inventory Tabs is switching AWAY FROM, for the duration of a
 * programmatic tab switch, into {@link TabSwitchState}.
 *
 * <p>Switching to a {@code BlockTab} performs a real block-interact ({@code useItemOn})
 * to reopen a server-side container screen for the new position -- Inventory Tabs sends
 * a {@code ServerboundContainerClose} first, then {@code tab.open(...)} triggers the new
 * interaction. Chest Tracker records a closed screen's items under whatever position its
 * {@code InteractionTracker} last saw a block-interact at. Because the synthetic
 * interaction for the INCOMING tab runs before the OUTGOING screen's removal event fires,
 * Chest Tracker attributes the outgoing chest's contents to the incoming chest's memory
 * (see {@link dev.tabsearch.compat.ChestTrackerRedirect}).
 *
 * <p>Capture is two-phase to survive buffered (non-unique {@code ItemTab}) switches,
 * which bounce through a transient {@code PlayerInventoryTab} via a second
 * {@code openTabImmediate} call before the real target opens:
 * <ol>
 *   <li>{@code openTab(Tab)} HEAD -- the single external entry point, called once per
 *       user-driven switch -- stashes {@code currentTab} as a pending candidate.</li>
 *   <li>The FIRST {@code openTabImmediate} HEAD reached afterward commits that candidate
 *       as the outgoing tab and stops watching, so a second (bounce) call can't stomp it
 *       with the transient {@code PlayerInventoryTab}.</li>
 * </ol>
 * If {@code openTab} bails out early (e.g. {@code shouldBeRemoved} rejects the target),
 * {@code openTabImmediate} never runs, so nothing is ever committed and no stale state
 * leaks into an unrelated future screen close.
 *
 * <p>This class holds ONLY the two private injects, and both take NO target arguments
 * (just {@code CallbackInfo}) -- Mixin's {@code @Inject} explicitly supports this
 * "simple" descriptor form. Earlier attempts to declare {@code Object}-typed params
 * for {@code Tab}/{@code ClientPlayerEntity}/etc. (arguments this class never uses)
 * failed at apply time with "Invalid descriptor ... Expected (LTab;...)V but found
 * (LObject;...)V" -- Mixin validates the FULL descriptor, not just param count, so
 * there is no way to widen a target's own parameter types to {@code Object}. Taking
 * no arguments sidesteps the whole class of bug: nothing to get wrong.
 *
 * <p>This class holds ONLY the two private injects. Mixin merges every non-private
 * member of a mixin into its target class, so any public API (the actual
 * {@code consumeOutgoingTab} accessor other code needs to call) lives in
 * {@link TabSwitchState} instead -- a public method here makes Mixin reject the whole
 * mixin with "contains non-private static method".
 */
@Pseudo
@Mixin(targets = "folk.sisby.inventory_tabs.TabManager", remap = false)
public class TabSwitchTracker {

    @Inject(method = "openTab", at = @At("HEAD"), remap = false, require = 1)
    private static void tabsearch$stashCandidate(CallbackInfo ci) {
        // openTab's own body guards `if (tab != currentTab)` before ever reaching
        // openTabImmediate, so re-checking that here would be redundant -- whatever
        // is current right now IS the tab about to be replaced.
        TabSwitchState.stashCandidate();
    }

    @Inject(method = "openTabImmediate", at = @At("HEAD"), remap = false, require = 1)
    private static void tabsearch$commitCandidate(CallbackInfo ci) {
        TabSwitchState.commitCandidate();
    }
}
