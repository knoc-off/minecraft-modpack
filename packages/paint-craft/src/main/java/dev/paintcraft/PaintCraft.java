package dev.paintcraft;

import com.mojang.logging.LogUtils;
import dev.paintcraft.item.BrushItem;
import dev.paintcraft.item.EraserItem;
import dev.paintcraft.item.StampItem;
import dev.paintcraft.network.ModNetwork;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(PaintCraft.MODID)
public class PaintCraft {
    public static final String MODID = "paintcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PaintCraft(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.ITEMS.register(modEventBus);

        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::addCreative);

        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ModConfig.CONFIG_SPEC);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ModServerConfig.CONFIG_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.paintcraft.client.PaintCraftClientConfig.register(modContainer);
        }

        NeoForge.EVENT_BUS.addListener(PaintCraft::onRightClickBlock);

        // Register with Asset Shelf if present
        if (ModList.get().isLoaded("assetshelf")) {
            dev.paintcraft.compat.AssetShelfCompat.register();
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        ModNetwork.register(event);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.BRUSH);
            event.accept(ModItems.STAMP);
            event.accept(ModItems.ERASER);
        }
    }

    /**
     * Suppress block interaction (crafting table, chest, etc.) when holding the brush.
     * This ensures useOn fires instead of the block's use method.
     */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity().getItemInHand(event.getHand()).getItem() instanceof BrushItem
            || event.getEntity().getItemInHand(event.getHand()).getItem() instanceof StampItem
            || event.getEntity().getItemInHand(event.getHand()).getItem() instanceof EraserItem) {
            event.setUseBlock(TriState.FALSE);
        }
    }
}
