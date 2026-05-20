package dev.paintcraft.client;

import dev.paintcraft.projection.ResolvedSurface;
import dev.paintcraft.projection.SurfaceFragment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.*;

/**
 * Client-side spatial index for overlapping decals.
 * Maps (BlockPos, Direction) → sorted list of decal refs.
 * Used to assign z-tiers so overlapping decals render without z-fighting.
 */
public final class ClientSpatialIndex {

    private ClientSpatialIndex() {}

    private record DecalRef(UUID decalId, long zOverride) implements Comparable<DecalRef> {
        @Override
        public int compareTo(DecalRef o) {
            return Long.compare(zOverride, o.zOverride);
        }
    }

    // Key: packKey(BlockPos, Direction) → sorted list of decal refs at that cell
    private static final Map<Long, List<DecalRef>> index = new HashMap<>();

    // Reverse lookup: decalId → set of keys it occupies (for fast unregister)
    private static final Map<UUID, Set<Long>> decalCells = new HashMap<>();

    /**
     * Register a decal's fragment positions in the spatial index.
     */
    public static void register(UUID decalId, long zOverride, List<SurfaceFragment> fragments) {
        // First remove any existing entries for this decal (in case of re-resolve)
        unregister(decalId);

        Set<Long> cells = new HashSet<>();
        DecalRef ref = new DecalRef(decalId, zOverride);

        for (SurfaceFragment frag : fragments) {
            long key = packKey(frag.pos(), frag.faceNormal());
            cells.add(key);
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
        }

        // Sort each affected cell
        for (long key : cells) {
            List<DecalRef> refs = index.get(key);
            if (refs != null) {
                Collections.sort(refs);
            }
        }

        decalCells.put(decalId, cells);
    }

    /**
     * Remove a decal from the spatial index.
     */
    public static void unregister(UUID decalId) {
        Set<Long> cells = decalCells.remove(decalId);
        if (cells == null) return;

        for (long key : cells) {
            List<DecalRef> refs = index.get(key);
            if (refs != null) {
                refs.removeIf(r -> r.decalId().equals(decalId));
                if (refs.isEmpty()) {
                    index.remove(key);
                }
            }
        }
    }

    /**
     * Get the z-tier for a specific decal at a specific (pos, face).
     * Returns the rank (0-based) in the sorted list at that cell.
     */
    public static int getTier(UUID decalId, BlockPos pos, Direction face) {
        long key = packKey(pos, face);
        List<DecalRef> refs = index.get(key);
        if (refs == null) return 0;
        for (int i = 0; i < refs.size(); i++) {
            if (refs.get(i).decalId().equals(decalId)) return i;
        }
        return 0;
    }

    /**
     * Assign correct z-tiers to all fragments of a resolved surface.
     */
    public static ResolvedSurface assignTiers(UUID decalId, ResolvedSurface resolved) {
        List<SurfaceFragment> tiered = new ArrayList<>(resolved.fragments().size());
        for (SurfaceFragment frag : resolved.fragments()) {
            int tier = getTier(decalId, frag.pos(), frag.faceNormal());
            tiered.add(frag.withZTier(tier));
        }
        return new ResolvedSurface(tiered, resolved.backgroundPixels(), resolved.depthMap(),
            resolved.minDepth(), resolved.candidates());
    }

    /**
     * Get all decal IDs that share any cells with the given decal.
     * Excludes the given decal itself.
     */
    public static Set<UUID> getOverlapping(UUID decalId) {
        Set<Long> cells = decalCells.get(decalId);
        if (cells == null) return Set.of();

        Set<UUID> overlapping = new HashSet<>();
        for (long key : cells) {
            List<DecalRef> refs = index.get(key);
            if (refs != null && refs.size() > 1) {
                for (DecalRef ref : refs) {
                    if (!ref.decalId().equals(decalId)) {
                        overlapping.add(ref.decalId());
                    }
                }
            }
        }
        return overlapping;
    }

    /**
     * Check if there are any overlapping decals at the cells occupied by the given fragments.
     */
    public static boolean hasOverlaps(List<SurfaceFragment> fragments) {
        for (SurfaceFragment frag : fragments) {
            long key = packKey(frag.pos(), frag.faceNormal());
            List<DecalRef> refs = index.get(key);
            if (refs != null && refs.size() > 1) return true;
        }
        return false;
    }

    public static void clear() {
        index.clear();
        decalCells.clear();
    }

    private static long packKey(BlockPos pos, Direction face) {
        return pos.asLong() ^ ((long) face.ordinal() << 60);
    }
}
