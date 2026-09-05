package dev.tabsearch.mixin.accessor;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Duck-types Inventory Tabs' BlockTab. See WidgetPositionAccessor for the pattern. */
@Pseudo
@Mixin(targets = "folk.sisby.inventory_tabs.tabs.BlockTab", remap = false)
public interface BlockTabAccessor {
    @Accessor("pos")
    BlockPos getPos();

    @Accessor("multiblockPositions")
    List<BlockPos> getMultiblockPositions();
}
