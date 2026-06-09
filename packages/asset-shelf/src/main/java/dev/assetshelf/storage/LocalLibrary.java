package dev.assetshelf.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.assetshelf.AssetShelf;
import dev.assetshelf.core.AssetMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Client-side local asset library. Stores assets as files in .minecraft/asset-shelf/<type-id>/.
 * Thread-safe for the main client thread (all calls from render/tick thread).
 */
public final class LocalLibrary {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type META_LIST_TYPE = new TypeToken<List<MetaEntry>>() {}.getType();

    private LocalLibrary() {}

    /** Save an asset to the local library. Returns the generated UUID, or null on failure. */
    public static UUID save(ResourceLocation typeId, byte[] data, String name,
                            int widthPx, int heightPx, List<String> tags) {
        return save(typeId, data, name, "", widthPx, heightPx, tags);
    }

    /** Save an asset to the local library with a description. Returns the generated UUID, or null on failure. */
    public static UUID save(ResourceLocation typeId, byte[] data, String name, String description,
                            int widthPx, int heightPx, List<String> tags) {
        UUID id = UUID.randomUUID();
        Path dir = getTypeDir(typeId);
        try {
            Files.createDirectories(dir.resolve("assets"));

            // Write asset data
            Files.write(dir.resolve("assets/" + id + ".dat"), data);

            // Update index
            List<MetaEntry> index = loadIndex(dir);
            MetaEntry entry = new MetaEntry();
            entry.id = id.toString();
            entry.name = name;
            entry.description = description != null ? description : "";
            entry.widthPx = widthPx;
            entry.heightPx = heightPx;
            entry.createdAt = System.currentTimeMillis();
            entry.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
            index.add(entry);
            saveIndex(dir, index);

            AssetShelf.LOGGER.debug("Saved local asset '{}' ({})", name, id);
            return id;
        } catch (IOException e) {
            AssetShelf.LOGGER.error("Failed to save local asset '{}'", name, e);
            return null;
        }
    }

    /** List all local assets for a given type. */
    public static List<AssetMeta> list(ResourceLocation typeId) {
        Path dir = getTypeDir(typeId);
        List<MetaEntry> index = loadIndex(dir);
        List<AssetMeta> result = new ArrayList<>(index.size());
        UUID localPlayer = getLocalPlayerUUID();
        for (MetaEntry e : index) {
            result.add(new AssetMeta(
                typeId,
                UUID.fromString(e.id), e.name,
                e.description != null ? e.description : "",
                localPlayer, "Me",
                e.widthPx, e.heightPx, e.createdAt,
                e.tags != null ? List.copyOf(e.tags) : List.of()
            ));
        }
        return result;
    }

    /** Load the raw bytes of a local asset. */
    public static byte[] loadData(ResourceLocation typeId, UUID assetId) {
        Path file = getTypeDir(typeId).resolve("assets/" + assetId + ".dat");
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            AssetShelf.LOGGER.error("Failed to load local asset {}", assetId, e);
            return new byte[0];
        }
    }

    /** Delete a local asset. */
    public static void delete(ResourceLocation typeId, UUID assetId) {
        Path dir = getTypeDir(typeId);
        try {
            Files.deleteIfExists(dir.resolve("assets/" + assetId + ".dat"));
            List<MetaEntry> index = loadIndex(dir);
            index.removeIf(e -> e.id.equals(assetId.toString()));
            saveIndex(dir, index);
        } catch (IOException e) {
            AssetShelf.LOGGER.error("Failed to delete local asset {}", assetId, e);
        }
    }

    /** Rename a local asset. */
    public static void rename(ResourceLocation typeId, UUID assetId, String newName) {
        Path dir = getTypeDir(typeId);
        List<MetaEntry> index = loadIndex(dir);
        for (MetaEntry e : index) {
            if (e.id.equals(assetId.toString())) {
                e.name = newName;
                break;
            }
        }
        saveIndex(dir, index);
    }

    /** Update tags for a local asset. */
    public static void updateTags(ResourceLocation typeId, UUID assetId, List<String> newTags) {
        Path dir = getTypeDir(typeId);
        List<MetaEntry> index = loadIndex(dir);
        for (MetaEntry e : index) {
            if (e.id.equals(assetId.toString())) {
                e.tags = new ArrayList<>(newTags);
                break;
            }
        }
        saveIndex(dir, index);
    }

    /** Update all metadata for a local asset in a single index write. */
    public static void updateMeta(ResourceLocation typeId, UUID assetId,
                                   String name, String description, List<String> tags) {
        Path dir = getTypeDir(typeId);
        List<MetaEntry> index = loadIndex(dir);
        for (MetaEntry e : index) {
            if (e.id.equals(assetId.toString())) {
                e.name = name;
                e.description = description != null ? description : "";
                e.tags = new ArrayList<>(tags);
                break;
            }
        }
        saveIndex(dir, index);
    }

    /** Get the set of asset IDs that are marked as published for a type. */
    public static Set<UUID> getPublishedIds(ResourceLocation typeId) {
        Path dir = getTypeDir(typeId);
        List<MetaEntry> index = loadIndex(dir);
        Set<UUID> result = new HashSet<>();
        for (MetaEntry e : index) {
            if (e.published) result.add(UUID.fromString(e.id));
        }
        return result;
    }

    /** Mark an asset as published or unpublished. */
    public static void setPublished(ResourceLocation typeId, UUID assetId, boolean published) {
        Path dir = getTypeDir(typeId);
        List<MetaEntry> index = loadIndex(dir);
        for (MetaEntry e : index) {
            if (e.id.equals(assetId.toString())) {
                e.published = published;
                break;
            }
        }
        saveIndex(dir, index);
    }

    /** Update metadata AND mark as published in a single index write. */
    public static void updateMetaAndPublish(ResourceLocation typeId, UUID assetId,
                                             String name, String description, List<String> tags) {
        Path dir = getTypeDir(typeId);
        List<MetaEntry> index = loadIndex(dir);
        for (MetaEntry e : index) {
            if (e.id.equals(assetId.toString())) {
                e.name = name;
                e.description = description != null ? description : "";
                e.tags = new ArrayList<>(tags);
                e.published = true;
                break;
            }
        }
        saveIndex(dir, index);
    }

    private static Path getTypeDir(ResourceLocation typeId) {
        String folder = typeId.getNamespace() + "/" + typeId.getPath();
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("asset-shelf").resolve(folder);
    }

    private static UUID getLocalPlayerUUID() {
        var player = Minecraft.getInstance().player;
        return player != null ? player.getUUID() : new UUID(0, 0);
    }

    private static List<MetaEntry> loadIndex(Path dir) {
        Path indexFile = dir.resolve("index.json");
        if (!Files.exists(indexFile)) return new ArrayList<>();
        try (Reader r = Files.newBufferedReader(indexFile)) {
            List<MetaEntry> list = GSON.fromJson(r, META_LIST_TYPE);
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        } catch (Exception e) {
            AssetShelf.LOGGER.error("Failed to load index at {}", indexFile, e);
            return new ArrayList<>();
        }
    }

    private static void saveIndex(Path dir, List<MetaEntry> index) {
        Path indexFile = dir.resolve("index.json");
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(indexFile)) {
                GSON.toJson(index, w);
            }
        } catch (IOException e) {
            AssetShelf.LOGGER.error("Failed to save index at {}", indexFile, e);
        }
    }

    /** JSON-serializable index entry. */
    private static class MetaEntry {
        String id;
        String name;
        String description;
        int widthPx;
        int heightPx;
        long createdAt;
        List<String> tags;
        boolean published;
    }
}
