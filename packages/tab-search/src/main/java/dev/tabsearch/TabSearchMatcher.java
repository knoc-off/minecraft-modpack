package dev.tabsearch;

import dev.tabsearch.compat.ChestTrackerCompat;
import dev.tabsearch.compat.EmiCompat;
import dev.tabsearch.mixin.accessor.BlockTabAccessor;
import dev.tabsearch.mixin.accessor.ItemTabAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether a single Inventory Tabs tab's contents match EMI's active search.
 *
 * <p>Called from {@link dev.tabsearch.mixin.TabManagerMixin} once per visible tab.
 * Only invoked while {@link EmiCompat#isHighlightActive()} is true, so EMI is always
 * loaded by the time this runs.
 */
public final class TabSearchMatcher {

    private TabSearchMatcher() {}

    /**
     * @return true if the tab should render normally, false if it should be dimmed.
     *         A block tab with no Chest Tracker memory is always "true" (neutral) --
     *         dimming would falsely claim the chest is confirmed not to match.
     */
    public static boolean matches(Object tab, Level level) {
        if (tab instanceof ItemTabAccessor itemTab) {
            ItemStack stack = itemTab.getStack();
            return itemsMatch(containerItems(stack));
        }
        if (tab instanceof BlockTabAccessor blockTab) {
            if (!ChestTrackerCompat.isLoaded() || level == null) {
                return true;
            }
            BlockPos pos = blockTab.getPos();
            List<BlockPos> multiblock = blockTab.getMultiblockPositions();
            Optional<List<ItemStack>> memory = ChestTrackerCompat.memoryItems(level, pos, multiblock);
            if (memory.isEmpty()) {
                return true;
            }
            return itemsMatch(memory.get());
        }
        return true;
    }

    private static Iterable<ItemStack> containerItems(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return List.of(stack);
        }
        return contents.nonEmptyItems();
    }

    private static boolean itemsMatch(Iterable<ItemStack> items) {
        for (ItemStack item : items) {
            if (!item.isEmpty() && EmiCompat.matches(item)) {
                return true;
            }
        }
        return false;
    }
}
