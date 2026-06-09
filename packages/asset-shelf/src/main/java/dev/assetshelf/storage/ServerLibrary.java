package dev.assetshelf.storage;

import dev.assetshelf.AssetShelf;
import dev.assetshelf.core.AssetMeta;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Server-side public asset library. Metadata stored as SavedData (persists with the world).
 * Data blobs stored as individual files in {@code data/asset_shelf_blobs/}.
 */
public class ServerLibrary extends SavedData {

    private static final String DATA_NAME = "asset_shelf";

    // Metadata only — no blobs in RAM
    private final Map<UUID, AssetMeta> metadata = new LinkedHashMap<>();
    // Type index for fast filtered listing
    private final Map<ResourceLocation, List<AssetMeta>> byType = new HashMap<>();

    private Path blobDir;

    public record ServerAsset(AssetMeta meta, byte[] data) {}

    public ServerLibrary() {}

    public static ServerLibrary get(ServerLevel level) {
        ServerLibrary lib = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new Factory<>(ServerLibrary::new, ServerLibrary::load),
            DATA_NAME
        );
        lib.initBlobDir(level);
        return lib;
    }

    private void initBlobDir(ServerLevel level) {
        if (blobDir != null) return;
        blobDir = level.getServer().getWorldPath(LevelResource.ROOT)
            .resolve("data").resolve("asset_shelf_blobs");
        try {
            Files.createDirectories(blobDir);
        } catch (IOException e) {
            AssetShelf.LOGGER.error("Failed to create blob directory: {}", blobDir, e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Operations
    // ═══════════════════════════════════════════════════════════════

    public void put(AssetMeta meta, byte[] data) {
        // Write blob to disk
        if (blobDir != null) {
            try {
                Files.write(blobDir.resolve(meta.id() + ".dat"), data);
            } catch (IOException e) {
                AssetShelf.LOGGER.error("Failed to write blob for {}", meta.id(), e);
            }
        }

        // Remove old entry from type index if replacing
        AssetMeta old = metadata.remove(meta.id());
        if (old != null) {
            List<AssetMeta> list = byType.get(old.typeId());
            if (list != null) list.remove(old);
        }

        // Add to metadata + type index
        metadata.put(meta.id(), meta);
        byType.computeIfAbsent(meta.typeId(), k -> new ArrayList<>()).add(meta);
        setDirty();
    }

    /** Get full asset (metadata + data blob from disk). */
    public Optional<ServerAsset> get(UUID id) {
        AssetMeta meta = metadata.get(id);
        if (meta == null) return Optional.empty();
        byte[] data = readBlob(id);
        return Optional.of(new ServerAsset(meta, data));
    }

    /** Get metadata only (no disk I/O). */
    public Optional<AssetMeta> getMetadata(UUID id) {
        return Optional.ofNullable(metadata.get(id));
    }

    /** Read a data blob from disk. */
    public byte[] getData(UUID id) {
        return readBlob(id);
    }

    public void remove(UUID id) {
        AssetMeta removed = metadata.remove(id);
        if (removed != null) {
            List<AssetMeta> list = byType.get(removed.typeId());
            if (list != null) list.remove(removed);

            // Delete blob file
            if (blobDir != null) {
                try {
                    Files.deleteIfExists(blobDir.resolve(id + ".dat"));
                } catch (IOException e) {
                    AssetShelf.LOGGER.error("Failed to delete blob for {}", id, e);
                }
            }
            setDirty();
        }
    }

    /** Get a paginated list of metadata filtered by type, name, and tags, newest first. */
    public List<AssetMeta> list(ResourceLocation typeId, int page, int pageSize,
                                String filter, List<String> tagFilters) {
        List<AssetMeta> filtered = filterByType(typeId, filter, tagFilters);
        filtered.sort(Comparator.comparingLong(AssetMeta::createdAt).reversed());
        int start = page * pageSize;
        if (start >= filtered.size()) return List.of();
        int end = Math.min(start + pageSize, filtered.size());
        return filtered.subList(start, end);
    }

    public int totalCount(ResourceLocation typeId, String filter, List<String> tagFilters) {
        return filterByType(typeId, filter, tagFilters).size();
    }

    private List<AssetMeta> filterByType(ResourceLocation typeId, String filter, List<String> tagFilters) {
        String lowerFilter = filter != null ? filter.trim().toLowerCase() : "";
        List<AssetMeta> candidates = byType.getOrDefault(typeId, List.of());
        List<AssetMeta> result = new ArrayList<>();
        for (AssetMeta meta : candidates) {
            if (!lowerFilter.isEmpty() && !meta.name().toLowerCase().contains(lowerFilter)) continue;
            if (!matchesTags(meta, tagFilters)) continue;
            result.add(meta);
        }
        return result;
    }

    private static boolean matchesTags(AssetMeta meta, List<String> required) {
        if (required == null || required.isEmpty()) return true;
        for (String req : required) {
            if (!meta.tags().contains(req)) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Blob I/O
    // ═══════════════════════════════════════════════════════════════

    private byte[] readBlob(UUID id) {
        if (blobDir == null) return new byte[0];
        Path file = blobDir.resolve(id + ".dat");
        try {
            if (Files.exists(file)) return Files.readAllBytes(file);
        } catch (IOException e) {
            AssetShelf.LOGGER.error("Failed to read blob for {}", id, e);
        }
        return new byte[0];
    }

    // ═══════════════════════════════════════════════════════════════
    //  Serialization — metadata only, no blobs
    // ═══════════════════════════════════════════════════════════════

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (AssetMeta m : metadata.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", m.typeId().toString());
            tag.putUUID("id", m.id());
            tag.putString("name", m.name());
            tag.putString("desc", m.description());
            tag.putUUID("author", m.authorUUID());
            tag.putString("authorName", m.authorName());
            tag.putInt("w", m.widthPx());
            tag.putInt("h", m.heightPx());
            tag.putLong("created", m.createdAt());

            if (!m.tags().isEmpty()) {
                ListTag tagsList = new ListTag();
                for (String t : m.tags()) tagsList.add(StringTag.valueOf(t));
                tag.put("tags", tagsList);
            }

            list.add(tag);
        }
        root.put("assets", list);
        return root;
    }

    private static ServerLibrary load(CompoundTag root, HolderLookup.Provider registries) {
        ServerLibrary lib = new ServerLibrary();
        ListTag list = root.getList("assets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            ResourceLocation typeId = tag.contains("type")
                ? ResourceLocation.parse(tag.getString("type"))
                : ResourceLocation.fromNamespaceAndPath("paintcraft", "painting");

            List<String> tags = new ArrayList<>();
            if (tag.contains("tags", Tag.TAG_LIST)) {
                ListTag tagsList = tag.getList("tags", Tag.TAG_STRING);
                for (int j = 0; j < tagsList.size(); j++) {
                    tags.add(tagsList.getString(j));
                }
            }

            AssetMeta meta = new AssetMeta(
                typeId,
                tag.getUUID("id"),
                tag.getString("name"),
                tag.contains("desc") ? tag.getString("desc") : "",
                tag.getUUID("author"),
                tag.getString("authorName"),
                tag.getInt("w"),
                tag.getInt("h"),
                tag.getLong("created"),
                List.copyOf(tags)
            );
            lib.metadata.put(meta.id(), meta);
        }
        lib.rebuildTypeIndex();
        AssetShelf.LOGGER.info("Loaded {} public assets (metadata only, blobs on disk)", lib.metadata.size());
        return lib;
    }

    private void rebuildTypeIndex() {
        byType.clear();
        for (AssetMeta meta : metadata.values()) {
            byType.computeIfAbsent(meta.typeId(), k -> new ArrayList<>()).add(meta);
        }
    }
}
