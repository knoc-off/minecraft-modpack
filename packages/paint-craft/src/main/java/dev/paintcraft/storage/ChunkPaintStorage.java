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
    private final Map<UUID, Decal> decals = new HashMap<>();

    public ChunkPaintStorage() {}

    public static ChunkPaintStorage get(ServerLevel level, ChunkPos chunkPos) {
        // SavedData is per-level; we key decals by their anchor chunk internally.
        // For simplicity, one SavedData per level stores all decals, keyed by UUID.
        return level.getDataStorage().computeIfAbsent(
            new Factory<>(ChunkPaintStorage::new, ChunkPaintStorage::load),
            DATA_NAME
        );
    }

    public void putDecal(Decal decal) {
        decals.put(decal.id(), decal);
        setDirty();
    }

    public void removeDecal(UUID id) {
        if (decals.remove(id) != null) {
            setDirty();
        }
    }

    public Optional<Decal> getDecal(UUID id) {
        return Optional.ofNullable(decals.get(id));
    }

    /** Find all decals whose anchor is in the given chunk. */
    public List<Decal> getDecalsInChunk(ChunkPos pos) {
        List<Decal> result = new ArrayList<>();
        for (Decal d : decals.values()) {
            if (chunkOf(d.anchor()).equals(pos)) {
                result.add(d);
            }
        }
        return result;
    }

    /** Find all decals whose projection volume might overlap the given block position. */
    public List<Decal> getDecalsOverlapping(BlockPos pos) {
        List<Decal> result = new ArrayList<>();
        for (Decal d : decals.values()) {
            if (couldOverlap(d, pos)) {
                result.add(d);
            }
        }
        return result;
    }

    /** Find the topmost (highest seqNo) decal at a specific face. */
    public Optional<Decal> getTopmostDecalAt(BlockPos pos, Direction face) {
        Decal best = null;
        for (Decal d : decals.values()) {
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
        for (Decal d : decals.values()) {
            if (d.normal() != face) continue;
            if (!isWithinDecal(d, pos)) continue;
            result.add(d);
        }
        result.sort(java.util.Comparator.comparingLong(Decal::zOrder).reversed());
        return result;
    }

    private static boolean isWithinDecal(Decal d, BlockPos pos) {
        BlockPos anchor = d.anchor();
        Direction normal = d.normal();
        Direction right = d.right();
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

    public Collection<Decal> allDecals() {
        return Collections.unmodifiableCollection(decals.values());
    }

    public long nextSeqNo() {
        long max = 0;
        for (Decal d : decals.values()) {
            max = Math.max(max, d.seqNo());
        }
        return max + 1;
    }

    public static boolean couldOverlap(Decal decal, BlockPos pos) {
        BlockPos anchor = decal.anchor();
        int range = Math.max(decal.widthBlocks(), decal.heightBlocks()) + (int) Math.ceil(decal.depth());
        return Math.abs(pos.getX() - anchor.getX()) <= range
            && Math.abs(pos.getY() - anchor.getY()) <= range
            && Math.abs(pos.getZ() - anchor.getZ()) <= range;
    }

    private static ChunkPos chunkOf(BlockPos pos) {
        return new ChunkPos(pos);
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
            storage.decals.put(d.id(), d);
        }
        return storage;
    }
}
