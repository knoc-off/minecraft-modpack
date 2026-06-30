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
 *
 * Two short-circuit exits before any downstream work:
 *
 *  (null)   resolveIncremental returns null when the changed blocks don't
 *           intersect this decal's pixel region at all — skip entirely.
 *
 *  (no-op)  if the depth buffer is byte-identical to the previous resolve
 *           the geometry hasn't changed (e.g. block state changed but not
 *           shape) — skip register + cacheResolved, so no recomposite or
 *           VBO rebuild fires.  This makes ambient block updates (redstone,
 *           lighting, waterlogging, …) near a deep stack essentially free.
 */
public final class DeferredInvalidator {

    private DeferredInvalidator() {}

    // Per-decal accumulated changed positions (multiple events can arrive per tick)
    private static final Map<UUID, Set<BlockPos>> pendingInvalidations = new LinkedHashMap<>();

    // Max decals that do actual downstream work (spatial register + cacheResolved) per flush.
    // Cheap no-op exits (null or unchanged depth buffer) don't count against this budget.
    private static final int MAX_RESOLVES_PER_FLUSH = 16;

    public static void invalidate(Collection<UUID> decalIds, Collection<BlockPos> changedPositions) {
        for (UUID id : decalIds) {
            pendingInvalidations.computeIfAbsent(id, k -> new HashSet<>()).addAll(changedPositions);
        }
    }

    /**
     * Process pending invalidations. Called every render frame from DecalRenderer.update().
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

            Decal decal = ClientDecalCache.get(id);
            if (decal == null) continue;

            DecalRenderer.ResolvedEntry existing = DecalRenderer.getResolved(id);

            ProjectionResult result;
            if (existing != null && existing.projState() != null
                    && existing.projState().canResolveIncrementally()) {
                // (C) Incremental: only reprocesses pixels overlapping changedBlocks.
                // Returns null when no pixel region is dirty — changed block doesn't
                // touch this decal at all.  Skip without counting against budget.
                result = ProjectionResolver.resolveIncremental(
                        decal, level, existing.projState(),
                        existing.surface().fragments(), changedBlocks);

                if (result == null) continue; // (C) short-circuit: nothing to do
            } else {
                // No prior state — must do a full resolve.
                // Rate-limit only the expensive full resolves.
                if (resolveCount >= MAX_RESOLVES_PER_FLUSH) {
                    pendingInvalidations.computeIfAbsent(id, k -> new HashSet<>()).addAll(changedBlocks);
                    continue;
                }
                result = ProjectionResolver.resolve(decal, level);
                resolveCount++;
            }

            if (result == null) continue;

            // (A) No-op guard: if the depth buffer is identical the geometry hasn't changed
            // (block state changed but not shape — redstone, powered rails, etc.).
            // Skip downstream work entirely: no spatial re-register, no cell dirty, no VBO rebuild.
            if (existing != null && existing.projState() != null
                    && Arrays.equals(result.state().depthMap(), existing.projState().depthMap())) {
                continue;
            }

            ClientSpatialIndex.register(decal.id(), decal.zOrder(), result.surface().fragments());
            DecalRenderer.cacheResolved(decal.id(), decal,
                    result.surface(), result.state(), changedBlocks);
        }
    }

    public static void clear() {
        pendingInvalidations.clear();
    }
}
