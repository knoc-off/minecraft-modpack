package dev.paintcraft.storage;

import dev.paintcraft.core.Decal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public class ChunkPaintStorage extends SavedData {

    private static final String DATA_NAME = "paintcraft_decals";
    public static final int MAX_DECALS_PER_CHUNK = 64;

    private final Map<UUID, Decal> decals = new HashMap<>();
    private final Map<Long, Set<UUID>> chunkIndex = new HashMap<>();
    private long maxSeqNo = 0;

    public ChunkPaintStorage() {}

    public static ChunkPaintStorage get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new Factory<>(ChunkPaintStorage::new, ChunkPaintStorage::load),
            DATA_NAME
        );
    }

    public void putDecal(Decal decal) {
        Decal old = decals.put(decal.id(), decal);
        if (old != null) {
            removeFromChunkIndex(old);
        }
        chunkIndex.computeIfAbsent(chunkKey(decal.anchor()), k -> new HashSet<>()).add(decal.id());
        maxSeqNo = Math.max(maxSeqNo, decal.seqNo());
        setDirty();
    }

    public void removeDecal(UUID id) {
        Decal removed = decals.remove(id);
        if (removed != null) {
            removeFromChunkIndex(removed);
            setDirty();
        }
    }

    private void removeFromChunkIndex(Decal decal) {
        long key = chunkKey(decal.anchor());
        Set<UUID> set = chunkIndex.get(key);
        if (set != null) {
            set.remove(decal.id());
            if (set.isEmpty()) chunkIndex.remove(key);
        }
    }

    private static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public Optional<Decal> getDecal(UUID id) {
        return Optional.ofNullable(decals.get(id));
    }

    /** Find all decals whose anchor is in the given chunk. */
    public List<Decal> getDecalsInChunk(ChunkPos pos) {
        Set<UUID> ids = chunkIndex.get(pos.toLong());
        if (ids == null || ids.isEmpty()) return List.of();
        List<Decal> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Decal d = decals.get(id);
            if (d != null) result.add(d);
        }
        return result;
    }

    /** Find the topmost (highest zOrder) decal at a specific face. */
    public Optional<Decal> getTopmostDecalAt(BlockPos pos, Direction face) {
        Decal best = null;
        for (Decal d : getDecalsNear(pos)) {
            if (d.normal() != face) continue;
            if (!isWithinDecal(d, pos)) continue;
            if (best == null || d.zOrder() > best.zOrder()) {
                best = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Find ALL decals covering a specific block face, sorted by zOrder (highest first). */
    public List<Decal> getAllDecalsAt(BlockPos pos, Direction face) {
        List<Decal> result = new ArrayList<>();
        for (Decal d : getDecalsNear(pos)) {
            if (d.normal() != face) continue;
            if (!isWithinDecal(d, pos)) continue;
            result.add(d);
        }
        result.sort(java.util.Comparator.comparingLong(Decal::zOrder).reversed());
        return result;
    }

    /**
     * Get all decals whose anchor is within ±1 chunks of the given position.
     * Much faster than scanning all decals for point queries.
     */
    public List<Decal> getDecalsNear(BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        List<Decal> result = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Set<UUID> ids = chunkIndex.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (ids == null) continue;
                for (UUID id : ids) {
                    Decal d = decals.get(id);
                    if (d != null) result.add(d);
                }
            }
        }
        return result;
    }

    private static boolean isWithinDecal(Decal d, BlockPos pos) {
        BlockPos anchor = d.anchor();
        Direction normal = d.normal();
        Direction right = d.frame().right();
        Direction up = d.up();

        int dx = pos.getX() - anchor.getX();
        int dy = pos.getY() - anchor.getY();
        int dz = pos.getZ() - anchor.getZ();

        // Check depth: clicked pos must be within the decal's projection depth
        int normalDist = dx * normal.getStepX() + dy * normal.getStepY() + dz * normal.getStepZ();
        if (normalDist > 0 || normalDist < -((int) Math.ceil(d.depth()))) return false;

        // Project offset onto the decal's right and up axes
        int rightDist = dx * right.getStepX() + dy * right.getStepY() + dz * right.getStepZ();
        int upDist = dx * up.getStepX() + dy * up.getStepY() + dz * up.getStepZ();

        return rightDist >= 0 && rightDist < d.widthBlocks()
            && upDist >= 0 && upDist < d.heightBlocks();
    }

    public int countDecalsInChunk(ChunkPos chunk) {
        Set<UUID> ids = chunkIndex.get(chunk.toLong());
        return ids != null ? ids.size() : 0;
    }

    public Collection<Decal> allDecals() {
        return List.copyOf(decals.values());
    }

    public long nextSeqNo() {
        return ++maxSeqNo;
    }

    public static boolean couldOverlap(Decal decal, BlockPos pos) {
        BlockPos anchor = decal.anchor();
        int range = Math.max(decal.widthBlocks(), decal.heightBlocks()) + (int) Math.ceil(decal.depth());
        return Math.abs(pos.getX() - anchor.getX()) <= range
            && Math.abs(pos.getY() - anchor.getY()) <= range
            && Math.abs(pos.getZ() - anchor.getZ()) <= range;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Decal d : decals.values()) {
            list.add(d.save());
        }
        tag.put("decals", list);
        return tag;
    }

    public static ChunkPaintStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        ChunkPaintStorage storage = new ChunkPaintStorage();
        ListTag list = tag.getList("decals", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Decal d = Decal.load(list.getCompound(i));
            if (d == null) continue; // legacy/unsupported decal — skipped
            storage.decals.put(d.id(), d);
            storage.chunkIndex.computeIfAbsent(chunkKey(d.anchor()), k -> new HashSet<>()).add(d.id());
            storage.maxSeqNo = Math.max(storage.maxSeqNo, d.seqNo());
        }
        return storage;
    }
}
