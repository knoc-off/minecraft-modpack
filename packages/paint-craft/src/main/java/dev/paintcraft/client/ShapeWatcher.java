package dev.paintcraft.client;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Detects client-side visual block updates (via sendBlockUpdated mixin)
 * and triggers decal re-resolution for affected blocks.
 * Handles async shape loading from mods like Chisel & Bits.
 */
public final class ShapeWatcher {

    private ShapeWatcher() {}

    public static void onBlockVisualUpdate(BlockPos pos) {
        // Pre-filter: skip immediately if no decal has a fragment or volume in this chunk.
        // One allocation-free lookup vs. up to 7 map lookups + a new ChunkPos allocation.
        if (!ClientSpatialIndex.hasDecalsInChunk(pos)) return;

        Set<UUID> byFragment = ClientSpatialIndex.getDecalIdsAt(pos);
        Set<UUID> byVolume = ClientSpatialIndex.getDecalIdsInVolumeAt(pos);
        if (byFragment.isEmpty() && byVolume.isEmpty()) return;

        Set<UUID> affected = new HashSet<>(byFragment);
        affected.addAll(byVolume);
        DeferredInvalidator.invalidate(affected, Set.of(pos.immutable()));
    }
}
