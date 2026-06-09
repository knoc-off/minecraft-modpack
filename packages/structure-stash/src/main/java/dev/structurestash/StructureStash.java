package dev.structurestash;

import dev.assetshelf.api.AssetShelfApi;
import dev.structurestash.compat.ChiseledAssetType;
import dev.structurestash.item.ModDataComponents;
import dev.structurestash.item.ModItems;
import dev.structurestash.network.StashNetwork;
import dev.structurestash.stash.ModAttachments;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(StructureStash.MODID)
public class StructureStash {

    public static final String MODID = "structurestash";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public StructureStash(IEventBus modBus) {
        ModAttachments.ATTACHMENTS.register(modBus);
        ModDataComponents.COMPONENTS.register(modBus);
        ModItems.ITEMS.register(modBus);
        modBus.addListener(this::registerNetworking);

        AssetShelfApi.register(new ChiseledAssetType());
        LOGGER.info("Structure Stash loaded — registered chiseled block asset type");
    }

    private void registerNetworking(RegisterPayloadHandlersEvent event) {
        StashNetwork.register(event);
    }
}
