package dev.paintcraft.client;

import dev.paintcraft.core.Decal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientDecalCache {

    private static final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    private ClientDecalCache() {}

    public static void put(Decal decal) {
        Entry old = cache.remove(decal.id());
        if (old != null) old.texture.close();
        DecalTexture tex = new DecalTexture(decal);
        cache.put(decal.id(), new Entry(decal, tex));
    }

    public static void remove(UUID id) {
        Entry old = cache.remove(id);
        if (old != null) old.texture.close();
    }

    public static Entry get(UUID id) {
        return cache.get(id);
    }

    public static Collection<Entry> all() {
        return cache.values();
    }

    public static void removeChunk(ChunkPos pos) {
        cache.entrySet().removeIf(e -> {
            if (new ChunkPos(e.getValue().decal.anchor()).equals(pos)) {
                e.getValue().texture.close();
                DecalRenderer.invalidate(e.getKey());
                return true;
            }
            return false;
        });
    }

    public static void clear() {
        cache.values().forEach(e -> e.texture.close());
        cache.clear();
    }

    public static boolean couldOverlap(Decal decal, BlockPos pos) {
        BlockPos anchor = decal.anchor();
        int range = Math.max(decal.widthBlocks(), decal.heightBlocks())
            + (int) Math.ceil(decal.depth());
        return Math.abs(pos.getX() - anchor.getX()) <= range
            && Math.abs(pos.getY() - anchor.getY()) <= range
            && Math.abs(pos.getZ() - anchor.getZ()) <= range;
    }

    public record Entry(Decal decal, DecalTexture texture) {}
}
