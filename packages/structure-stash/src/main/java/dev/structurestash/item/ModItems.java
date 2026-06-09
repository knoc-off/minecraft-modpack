package dev.structurestash.item;

import dev.structurestash.StructureStash;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, StructureStash.MODID);

    public static final Supplier<Item> BLUEPRINT_WAND = ITEMS.register("blueprint_wand",
        () -> new BlueprintWandItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<Item> BLUEPRINT = ITEMS.register("blueprint",
        () -> new BlueprintItem(new Item.Properties().stacksTo(64)));
}
