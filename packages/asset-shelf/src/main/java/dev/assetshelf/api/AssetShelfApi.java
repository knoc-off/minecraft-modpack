package dev.assetshelf.api;

import dev.assetshelf.storage.LocalLibrary;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public API for content mods to interact with Asset Shelf.
 * Content mods call register() at init time, then use saveLocal() and openBrowser() as needed.
 */
public final class AssetShelfApi {

    private static final Map<ResourceLocation, AssetType> TYPES = new ConcurrentHashMap<>();

    private AssetShelfApi() {}

    /** Register an asset type. Call this during mod initialization. */
    public static void register(AssetType type) {
        TYPES.put(type.id(), type);
    }

    /** Look up a registered asset type by ID. */
    @Nullable
    public static AssetType getType(ResourceLocation id) {
        return TYPES.get(id);
    }

    /** Get all registered asset types. */
    public static Iterable<AssetType> allTypes() {
        return TYPES.values();
    }

    /**
     * Save an asset to the player's local (private) library.
     * Call from the client side.
     *
     * @return the generated UUID for the saved asset
     */
    public static UUID saveLocal(ResourceLocation typeId, byte[] data, String name,
                                  int widthPx, int heightPx, List<String> tags) {
        return LocalLibrary.save(typeId, data, name, widthPx, heightPx, tags);
    }
}
