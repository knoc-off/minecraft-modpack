package dev.paintcraft.client;

import dev.paintcraft.core.Decal;
import dev.paintcraft.projection.ProjectionResult;
import dev.paintcraft.projection.ProjectionResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Batches decal re-resolution triggered by block changes.
 *
 * Uses incremental re-resolve when previous resolved data is available:
 * only queries changed blocks, not the entire projection volume.
 * Compositing is handled separately — this only updates projection geometry.
 */
public final class DeferredInvalidator {

    private DeferredInvalidator() {}

    // Per-decal accumulated changed positions (multiple invalidation packets
    // can arrive in one tick, each with different changed blocks)
    private static final Map<UUID, Set<BlockPos>> pendingInvalidations = new LinkedHashMap<>();

    // Decals that must use full resolve (not incremental from potentially-stale state)
    private static final Set<UUID> forceFullResolve = new HashSet<>();

    private static final int MAX_RESOLVES_PER_FLUSH = 16;

    /**
     * Mark decals for deferred re-resolution with the positions that changed.
     */
    public static void invalidate(Collection<UUID> decalIds, Collection<BlockPos> changedPositions) {
        for (UUID id : decalIds) {
            pendingInvalidations.computeIfAbsent(id, k -> new HashSet<>()).addAll(changedPositions);
        }
    }

    /**
     * Mark decals for deferred re-resolution with FULL resolve forced.
     * Used when the client-side block shape genuinely changed (e.g., after
     * async BlockEntity deserialization completes) and incremental state may be stale.
     */
    public static void invalidateFullResolve(Collection<UUID> decalIds, Collection<BlockPos> changedPositions) {
        invalidate(decalIds, changedPositions);
        forceFullResolve.addAll(decalIds);
    }

    /**
     * Process pending invalidations. Called every render frame from DecalRenderer.renderAll()
     * to minimize latency between server packet arrival and visual update.
     */
    public static void flush() {
        if (pendingInvalidations.isEmpty()) return;

        Level level = Minecraft.getInstance().level;
        if (level == null) {
            pendingInvalidations.clear();
            return;
        }

        Map<UUID, Set<BlockPos>> toProcess = new LinkedHashMap<>(pendingInvalidations);
        pendingInvalidations.clear();

        int resolveCount = 0;

        for (var entry : toProcess.entrySet()) {
            UUID id = entry.getKey();
            Set<BlockPos> changedBlocks = entry.getValue();

            // Rate-limit: defer remaining to next frame
            if (resolveCount >= MAX_RESOLVES_PER_FLUSH) {
                pendingInvalidations.computeIfAbsent(id, k -> new HashSet<>()).addAll(changedBlocks);
                continue;
            }

            Decal decal = ClientDecalCache.get(id);
            if (decal == null) continue;

            // Try incremental resolve using previous data
            DecalRenderer.ResolvedEntry existing = DecalRenderer.getResolved(id);
            ProjectionResult result;
            if (existing != null && existing.projState() != null
                    && existing.projState().canResolveIncrementally()
                    && !forceFullResolve.remove(id)) {
                result = ProjectionResolver.resolveIncremental(
                    decal, level, existing.projState(),
                    existing.surface().fragments(), changedBlocks);
            } else {
                result = ProjectionResolver.resolve(decal, level);
            }

            // If incremental couldn't detect the change, fall back to full resolve
            if (result == null) {
                result = ProjectionResolver.resolve(decal, level);
            }

            if (result != null) {
                ClientSpatialIndex.register(decal.id(), decal.zOrder(), result.surface().fragments());
                DecalRenderer.cacheResolved(decal.id(), decal,
                    result.surface(), result.state(), changedBlocks);
            }

            resolveCount++;
        }
    }

    public static void clear() {
        pendingInvalidations.clear();
        forceFullResolve.clear();
    }
}
