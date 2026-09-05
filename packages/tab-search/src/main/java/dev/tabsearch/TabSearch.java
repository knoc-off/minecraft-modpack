package dev.tabsearch;

import com.mojang.logging.LogUtils;
import dev.tabsearch.compat.ChestTrackerRedirect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

@Mod(TabSearch.MODID)
public class TabSearch {
    public static final String MODID = "tabsearch";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TabSearch(IEventBus modEventBus, ModContainer modContainer) {
        // No registries, no config, no network -- this mod is a pure client-side
        // rendering hook. Most work happens in TabManagerMixin, gated at runtime by
        // ModList.isLoaded() checks in the compat/ classes. ChestTrackerRedirect needs
        // an explicit registration call, deferred to FMLClientSetupEvent since mod
        // construction order between tabsearch and chesttracker isn't guaranteed.
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        if (ChestTrackerRedirect.isLoaded()) {
            ChestTrackerRedirect.register();
        }
    }
}
