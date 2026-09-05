package dev.tabsearch.mixin.accessor;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Duck-types Inventory Tabs' ItemTab. See WidgetPositionAccessor for the pattern. */
@Pseudo
@Mixin(targets = "folk.sisby.inventory_tabs.tabs.ItemTab", remap = false)
public interface ItemTabAccessor {
    @Accessor("stack")
    ItemStack getStack();
}
