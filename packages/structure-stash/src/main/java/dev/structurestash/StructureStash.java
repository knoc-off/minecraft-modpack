package dev.structurestash;

import dev.assetshelf.api.AssetShelfApi;
import dev.structurestash.compat.ChiseledAssetType;
import dev.structurestash.compat.CnBCompat;
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

        // Chisels & Bits is an optional dependency. The chiseled-block asset type
        // touches C&B types throughout, so only register it when C&B is present.
        // When absent, the type is never registered — which also makes the server
        // reject any attempt to publish a chiseled blueprint (Asset Shelf rejects
        // unknown asset types in its publish handler).
        if (CnBCompat.isLoaded()) {
            AssetShelfApi.register(new ChiseledAssetType());
            LOGGER.info("Structure Stash loaded — registered chiseled block asset type");
        } else {
            LOGGER.info("Structure Stash loaded — Chisels & Bits absent, chiseled block asset type disabled");
        }
    }

    private void registerNetworking(RegisterPayloadHandlersEvent event) {
        StashNetwork.register(event);
    }
}
