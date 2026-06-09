package dev.assetshelf.api;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Represents a material cost entry. Wraps an ItemStack so component-aware
 * matching is supported (e.g., C&B bits with BLOCK_INFORMATION component).
 */
public record ItemCost(ItemStack stack) {

    /** Convenience factory for simple item+count costs (no components). */
    public static ItemCost of(Item item, int count) {
        return new ItemCost(new ItemStack(item, count));
    }

    /** The item type. */
    public Item item() { return stack.getItem(); }

    /** Required count. */
    public int count() { return stack.getCount(); }

    /** Whether this cost requires component matching (vs just item type). */
    public boolean hasComponents() {
        return !stack.getComponentsPatch().isEmpty();
    }
}
