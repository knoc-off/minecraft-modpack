package dev.paintcraft.client;

import dev.paintcraft.core.Decal;
import dev.paintcraft.projection.ProjectionResolver;
import dev.paintcraft.projection.ResolvedSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Batches and throttles decal re-resolution triggered by block changes.
 *
 * Uses incremental re-resolve when previous resolved data is available:
 * only queries changed blocks, not the entire projection volume.
 * For a single block change in a 20x20 mural, this reduces block queries
 * from ~800 to 1.
 */
public final class DeferredInvalidator {

    private DeferredInvalidator() {}

    // Per-decal accumulated changed positions (multiple invalidation packets
    // can arrive in one tick, each with different changed blocks)
    private static final Map<UUID, Set<BlockPos>> pendingInvalidations = new LinkedHashMap<>();
    private static final Map<UUID, Long> lastResolveTime = new HashMap<>();

    private static final int THROTTLE_TICKS = 4;
    private static long currentTick = 0;
    private static boolean scheduled = false;

    /**
     * Mark decals for deferred re-resolution with the positions that changed.
     */
    public static void invalidate(Collection<UUID> decalIds, Collection<BlockPos> changedPositions) {
        Set<BlockPos> posSet = new HashSet<>(changedPositions);
        for (UUID id : decalIds) {
            pendingInvalidations.computeIfAbsent(id, k -> new HashSet<>()).addAll(posSet);
        }
        scheduleFlush();
    }

    public static void tick() {
        currentTick++;
    }

    public static void flush() {
        scheduled = false;
        if (pendingInvalidations.isEmpty()) return;

        Level level = Minecraft.getInstance().level;
        if (level == null) {
            pendingInvalidations.clear();
            return;
        }

        Map<UUID, Set<BlockPos>> toProcess = new LinkedHashMap<>(pendingInvalidations);
        pendingInvalidations.clear();

        Set<UUID> resolved = new HashSet<>();
        Set<UUID> allAffected = new HashSet<>();

        for (var entry : toProcess.entrySet()) {
            UUID id = entry.getKey();
            Set<BlockPos> changedBlocks = entry.getValue();

            // Throttle: skip if recently resolved
            Long lastTime = lastResolveTime.get(id);
            if (lastTime != null && (currentTick - lastTime) < THROTTLE_TICKS) {
                pendingInvalidations.computeIfAbsent(id, k -> new HashSet<>()).addAll(changedBlocks);
                continue;
            }

            ClientDecalCache.Entry cacheEntry = ClientDecalCache.get(id);
            if (cacheEntry == null) continue;

            Decal decal = cacheEntry.decal();

            // Try incremental resolve using previous data
            DecalRenderer.ResolvedEntry existing = DecalRenderer.getResolved(id);
            ResolvedSurface surface;
            if (existing != null && !existing.surface().candidates().isEmpty()) {
                surface = ProjectionResolver.resolveIncremental(
                    decal, level, existing.surface(), changedBlocks);
            } else {
                surface = ProjectionResolver.resolve(decal, level);
            }

            ClientSpatialIndex.register(decal.id(), decal.zOrder(), surface.fragments());
            ResolvedSurface tiered = ClientSpatialIndex.assignTiers(decal.id(), surface);
            DecalRenderer.cacheResolved(decal.id(), decal, cacheEntry.texture(), tiered);

            lastResolveTime.put(id, currentTick);
            resolved.add(id);
            allAffected.addAll(ClientSpatialIndex.getOverlapping(id));
        }

        allAffected.removeAll(resolved);
        for (UUID otherId : allAffected) {
            retierOnly(otherId);
        }

        if (!pendingInvalidations.isEmpty()) {
            scheduleFlush();
        }
    }

    private static void retierOnly(UUID decalId) {
        ClientDecalCache.Entry entry = ClientDecalCache.get(decalId);
        if (entry == null) return;

        DecalRenderer.ResolvedEntry existing = DecalRenderer.getResolved(decalId);
        if (existing == null) return;

        ResolvedSurface retiered = ClientSpatialIndex.assignTiers(decalId, existing.surface());
        DecalRenderer.cacheResolved(decalId, entry.decal(), entry.texture(), retiered);
    }

    private static void scheduleFlush() {
        if (scheduled) return;
        scheduled = true;
        Minecraft.getInstance().tell(DeferredInvalidator::flush);
    }

    public static void clear() {
        pendingInvalidations.clear();
        lastResolveTime.clear();
        scheduled = false;
    }
}
