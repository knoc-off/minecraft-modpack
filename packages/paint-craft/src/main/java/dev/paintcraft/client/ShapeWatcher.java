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
        // Decals with a fragment at this position...
        Set<UUID> byFragment = ClientSpatialIndex.getDecalIdsAt(pos);
        // ...plus decals whose projection volume contains it but that have no fragment here yet
        // (e.g. resolved empty/too-deep before their blocks were applied — contraption disassembly).
        Set<UUID> byVolume = ClientSpatialIndex.getDecalIdsInVolumeAt(pos);
        if (byFragment.isEmpty() && byVolume.isEmpty()) return;

        Set<UUID> affected = new HashSet<>(byFragment);
        affected.addAll(byVolume);
        DeferredInvalidator.invalidateFullResolve(affected, Set.of(pos.immutable()));
    }
}
