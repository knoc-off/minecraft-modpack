package dev.paintcraft;

import dev.paintcraft.item.BrushItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PaintCraft.MODID);

    public static final DeferredItem<BrushItem> BRUSH = ITEMS.register("brush",
        () -> new BrushItem(new Item.Properties().stacksTo(1).durability(0)));

    private ModItems() {}
}
