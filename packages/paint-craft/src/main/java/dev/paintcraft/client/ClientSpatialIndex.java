package dev.paintcraft.client;

import dev.paintcraft.projection.SurfaceFragment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Client-side spatial index for overlapping decals.
 * Maps (BlockPos, Direction) to sorted list of decal refs.
 * Used by the compositor to merge overlapping decal pixels.
 */
public final class ClientSpatialIndex {

    private ClientSpatialIndex() {}

    record DecalRef(UUID decalId, long zOverride) implements Comparable<DecalRef> {
        @Override
        public int compareTo(DecalRef o) {
            return Long.compare(zOverride, o.zOverride);
        }
    }

    /** Pos+face pair, used by the compositor to recover cell identity from a key. */
    public record CellLocation(BlockPos pos, Direction face) {}

    private static final Map<Long, List<DecalRef>> index = new HashMap<>();
    private static final Map<UUID, Set<Long>> decalCells = new HashMap<>();
    private static final Map<ChunkPos, Set<Long>> chunkIndex = new HashMap<>();

    // Reverse index: cell key → pos+face, so findCellId() is O(1) instead of O(all-decals).
    private static final Map<Long, CellLocation> keyToLocation = new HashMap<>();

    // Projection-volume index: maps each decal's full projection AABB to the chunks it spans,
    // so block updates anywhere inside the volume can re-resolve the decal even when it has no
    // fragment at the changed position (e.g., disassembly / late-block cases).
    private static final Map<UUID, AABB> volumes = new HashMap<>();
    private static final Map<ChunkPos, Set<UUID>> volumeByChunk = new HashMap<>();

    // Union of all chunks that contain any fragment key or projection volume.
    // ShapeWatcher uses this for a single allocation-free pre-filter before doing
    // any full lookups — most world block-updates are nowhere near a decal.
    private static final Set<Long> activeChunkKeys = new HashSet<>();

    public static void register(UUID decalId, long zOverride, List<SurfaceFragment> fragments) {
        unregister(decalId);

        Set<Long> cells = new HashSet<>();
        DecalRef ref = new DecalRef(decalId, zOverride);

        for (SurfaceFragment frag : fragments) {
            long key = packKey(frag.pos(), frag.faceNormal());
            cells.add(key);
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
            ChunkPos cp = new ChunkPos(frag.pos());
            chunkIndex.computeIfAbsent(cp, k -> new HashSet<>()).add(key);
            keyToLocation.putIfAbsent(key, new CellLocation(frag.pos(), frag.faceNormal()));
            activeChunkKeys.add(cp.toLong());
        }

        for (long key : cells) {
            List<DecalRef> refs = index.get(key);
            if (refs != null) Collections.sort(refs);
        }

        decalCells.put(decalId, cells);
    }

    public static void unregister(UUID decalId) {
        Set<Long> cells = decalCells.remove(decalId);
        if (cells == null) return;

        for (long key : cells) {
            List<DecalRef> refs = index.get(key);
            if (refs != null) {
                refs.removeIf(r -> r.decalId().equals(decalId));
                if (refs.isEmpty()) {
                    index.remove(key);
                    keyToLocation.remove(key);
                    for (Map.Entry<ChunkPos, Set<Long>> e : chunkIndex.entrySet()) {
                        e.getValue().remove(key);
                    }
                }
            }
        }
        chunkIndex.entrySet().removeIf(e -> e.getValue().isEmpty());

        // Rebuild activeChunkKeys from remaining index + volumes
        rebuildActiveChunkKeys();
    }

    public static List<DecalRef> getRefsAt(BlockPos pos, Direction face) {
        return index.getOrDefault(packKey(pos, face), List.of());
    }

    /** Get all decal IDs that have fragments at this position (any face). */
    public static Set<UUID> getDecalIdsAt(BlockPos pos) {
        Set<UUID> result = null;
        for (Direction face : Direction.values()) {
            List<DecalRef> refs = index.get(packKey(pos, face));
            if (refs != null && !refs.isEmpty()) {
                if (result == null) result = new HashSet<>();
                for (DecalRef ref : refs) result.add(ref.decalId());
            }
        }
        return result != null ? result : Set.of();
    }

    /** Get all cell keys that have spatial index entries in a given chunk. */
    public static Set<Long> getCellKeysInChunk(ChunkPos chunk) {
        return chunkIndex.getOrDefault(chunk, Set.of());
    }

    /** O(1) pos+face lookup for a cell key — avoids scanning all resolved entries. */
    public static CellLocation getCellLocation(long key) {
        return keyToLocation.get(key);
    }

    /**
     * Fast pre-filter: returns true if the chunk containing {@code pos} holds any
     * decal fragment or projection volume.  One allocation-free check; lets
     * {@link ShapeWatcher} skip the full per-face + volume lookups for the vast
     * majority of world block-updates that are nowhere near a decal.
     */
    public static boolean hasDecalsInChunk(BlockPos pos) {
        return activeChunkKeys.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    // --- Projection-volume index ---

    public static void registerVolume(UUID decalId, AABB aabb) {
        removeVolume(decalId);
        volumes.put(decalId, aabb);
        int minCX = SectionPos.blockToSectionCoord((int) Math.floor(aabb.minX));
        int maxCX = SectionPos.blockToSectionCoord((int) Math.floor(aabb.maxX));
        int minCZ = SectionPos.blockToSectionCoord((int) Math.floor(aabb.minZ));
        int maxCZ = SectionPos.blockToSectionCoord((int) Math.floor(aabb.maxZ));
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                volumeByChunk.computeIfAbsent(cp, k -> new HashSet<>()).add(decalId);
                activeChunkKeys.add(cp.toLong());
            }
        }
    }

    public static void removeVolume(UUID decalId) {
        AABB aabb = volumes.remove(decalId);
        if (aabb == null) return;
        int minCX = SectionPos.blockToSectionCoord((int) Math.floor(aabb.minX));
        int maxCX = SectionPos.blockToSectionCoord((int) Math.floor(aabb.maxX));
        int minCZ = SectionPos.blockToSectionCoord((int) Math.floor(aabb.minZ));
        int maxCZ = SectionPos.blockToSectionCoord((int) Math.floor(aabb.maxZ));
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                Set<UUID> ids = volumeByChunk.get(cp);
                if (ids != null) {
                    ids.remove(decalId);
                    if (ids.isEmpty()) volumeByChunk.remove(cp);
                }
            }
        }
        rebuildActiveChunkKeys();
    }

    /** Get all decal IDs whose projection volume contains this position. */
    public static Set<UUID> getDecalIdsInVolumeAt(BlockPos pos) {
        Set<UUID> candidates = volumeByChunk.get(new ChunkPos(pos));
        if (candidates == null || candidates.isEmpty()) return Set.of();
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        Set<UUID> result = null;
        for (UUID id : candidates) {
            AABB aabb = volumes.get(id);
            if (aabb != null && aabb.contains(x, y, z)) {
                if (result == null) result = new HashSet<>();
                result.add(id);
            }
        }
        return result != null ? result : Set.of();
    }

    public static void clear() {
        index.clear();
        decalCells.clear();
        chunkIndex.clear();
        keyToLocation.clear();
        volumes.clear();
        volumeByChunk.clear();
        activeChunkKeys.clear();
    }

    public static void fillStats(DebugOverlay.Stats stats) {
        stats.spatialCells = index.size();
        int hottest = 0;
        for (List<DecalRef> refs : index.values()) {
            hottest = Math.max(hottest, refs.size());
        }
        stats.hottestCell = hottest;
    }

    static long packKey(BlockPos pos, Direction face) {
        return pos.asLong() ^ ((long) face.ordinal() << 60);
    }

    private static void rebuildActiveChunkKeys() {
        activeChunkKeys.clear();
        for (ChunkPos cp : chunkIndex.keySet()) activeChunkKeys.add(cp.toLong());
        for (ChunkPos cp : volumeByChunk.keySet()) activeChunkKeys.add(cp.toLong());
    }
}
