package dev.paintcraft.client;

import dev.paintcraft.core.Decal;
import dev.paintcraft.projection.ProjectionResolver;
import dev.paintcraft.projection.ResolvedSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Batches and throttles decal re-resolution triggered by block changes.
 *
 * Instead of re-resolving each decal immediately when a block changes:
 * 1. Collect invalidated UUIDs during the tick
 * 2. Process them all at once at tick end (deduplication)
 * 3. Throttle: skip re-resolve if the decal was resolved within the last N ticks
 * 4. Re-tier overlapping decals once at the end (not per-decal)
 *
 * This reduces the cost of a door toggle from O(N * resolve + N^2 * retier)
 * to O(unique_decals * resolve + 1 * retier_batch).
 */
public final class DeferredInvalidator {

    private DeferredInvalidator() {}

    private static final Set<UUID> pendingInvalidations = new LinkedHashSet<>();
    private static final Map<UUID, Long> lastResolveTime = new HashMap<>();

    private static final int THROTTLE_TICKS = 4;
    private static long currentTick = 0;
    private static boolean scheduled = false;

    /**
     * Mark decals for deferred re-resolution. Called from packet handler.
     */
    public static void invalidate(Collection<UUID> decalIds) {
        pendingInvalidations.addAll(decalIds);
        scheduleFlush();
    }

    /**
     * Called every client tick from ClientEvents.
     */
    public static void tick() {
        currentTick++;
    }

    /**
     * Process all pending invalidations. Called from the scheduled task.
     */
    public static void flush() {
        scheduled = false;
        if (pendingInvalidations.isEmpty()) return;

        Level level = Minecraft.getInstance().level;
        if (level == null) {
            pendingInvalidations.clear();
            return;
        }

        Set<UUID> toProcess = new LinkedHashSet<>(pendingInvalidations);
        pendingInvalidations.clear();

        Set<UUID> resolved = new HashSet<>();
        Set<UUID> allAffected = new HashSet<>();

        for (UUID id : toProcess) {
            // Throttle: skip if recently resolved
            Long lastTime = lastResolveTime.get(id);
            if (lastTime != null && (currentTick - lastTime) < THROTTLE_TICKS) {
                // Re-queue for later
                pendingInvalidations.add(id);
                continue;
            }

            ClientDecalCache.Entry entry = ClientDecalCache.get(id);
            if (entry == null) continue;

            Decal decal = entry.decal();
            ResolvedSurface surface = ProjectionResolver.resolve(decal, level);
            ClientSpatialIndex.register(decal.id(), decal.zOrder(), surface.fragments());
            ResolvedSurface tiered = ClientSpatialIndex.assignTiers(decal.id(), surface);
            DecalRenderer.cacheResolved(decal.id(), decal, entry.texture(), tiered);

            lastResolveTime.put(id, currentTick);
            resolved.add(id);
            allAffected.addAll(ClientSpatialIndex.getOverlapping(id));
        }

        // Batch re-tier: only re-tier decals that overlap with ANY resolved decal,
        // and only the ones we didn't already resolve above
        allAffected.removeAll(resolved);
        for (UUID otherId : allAffected) {
            retierOnly(otherId, level);
        }

        // If we deferred any, schedule another flush
        if (!pendingInvalidations.isEmpty()) {
            scheduleFlush();
        }
    }

    private static void retierOnly(UUID decalId, Level level) {
        ClientDecalCache.Entry entry = ClientDecalCache.get(decalId);
        if (entry == null) return;

        // Re-tier without re-resolving: use existing fragments, just reassign tiers
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
