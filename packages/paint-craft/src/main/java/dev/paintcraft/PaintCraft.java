package dev.paintcraft;

import com.mojang.logging.LogUtils;
import dev.paintcraft.item.BrushItem;
import dev.paintcraft.item.StampItem;
import dev.paintcraft.network.ModNetwork;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
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

        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, ModConfig.CONFIG_SPEC);

        NeoForge.EVENT_BUS.addListener(PaintCraft::onRightClickBlock);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        ModNetwork.register(event);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.BRUSH);
            event.accept(ModItems.STAMP);
        }
    }

    /**
     * Suppress block interaction (crafting table, chest, etc.) when holding the brush.
     * This ensures useOn fires instead of the block's use method.
     */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity().getItemInHand(event.getHand()).getItem() instanceof BrushItem
            || event.getEntity().getItemInHand(event.getHand()).getItem() instanceof StampItem) {
            event.setUseBlock(TriState.FALSE);
        }
    }
}
