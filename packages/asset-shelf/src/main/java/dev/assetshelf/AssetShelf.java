package dev.assetshelf;

import com.mojang.logging.LogUtils;
import dev.assetshelf.client.ShelfClientEvents;
import dev.assetshelf.network.ShelfNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(AssetShelf.MODID)
public class AssetShelf {
    public static final String MODID = "assetshelf";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AssetShelf(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::registerPayloads);

        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(ShelfClientEvents::registerKeys);
            NeoForge.EVENT_BUS.addListener(ShelfClientEvents::onClientTick);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        ShelfNetwork.register(event);
    }
}
