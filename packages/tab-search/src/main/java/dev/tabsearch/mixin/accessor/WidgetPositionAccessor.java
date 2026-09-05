package dev.tabsearch.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Duck-types Inventory Tabs' WidgetPosition so TabManagerMixin can read tab screen
 * coordinates without the target class on the compile classpath. See
 * TabManagerMixin for why @Pseudo + remap=false works across the Connector boundary.
 */
@Pseudo
@Mixin(targets = "folk.sisby.inventory_tabs.util.WidgetPosition", remap = false)
public interface WidgetPositionAccessor {
    @Accessor("x")
    int getX();

    @Accessor("y")
    int getY();

    @Accessor("up")
    boolean isUp();
}
