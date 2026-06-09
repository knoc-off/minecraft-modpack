package dev.paintcraft.client;

import dev.paintcraft.core.Decal;
import dev.paintcraft.projection.Projection;
import dev.paintcraft.projection.ProjectionResult;
import dev.paintcraft.projection.ProjectionResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks decals whose projection volumes span chunks that weren't loaded at resolve time,
 * and re-resolves them when those chunks arrive.
 */
public final class ClientDecalResolver {

    private static final Set<UUID> pendingReResolve = ConcurrentHashMap.newKeySet();

    private ClientDecalResolver() {}

    /**
     * After initial resolution, check if all chunks in the decal's volume are loaded.
     * If not, mark the decal for re-resolution when the missing chunks arrive.
     */
    public static void markPendingIfIncomplete(Decal decal, Level level) {
        if (!allChunksLoaded(decal, level)) {
            pendingReResolve.add(decal.id());
        }
    }

    /**
     * Called from ChunkEvent.Load on the client. Checks if any pending decals overlap
     * the newly loaded chunk and schedules deferred re-resolution.
     */
    public static void onChunkLoaded(ChunkPos loadedChunk, Level level) {
        if (pendingReResolve.isEmpty()) return;

        List<UUID> toReResolve = new ArrayList<>();
        for (UUID id : pendingReResolve) {
            Decal decal = ClientDecalCache.get(id);
            if (decal == null) {
                pendingReResolve.remove(id);
                continue;
            }

            AABB bounds = Projection.fromDecal(decal).toBoundingBox();
            if (chunkOverlapsAABB(loadedChunk, bounds)) {
                toReResolve.add(id);
            }
        }

        if (!toReResolve.isEmpty()) {
            // Defer to next tick — ChunkEvent.Load can fire before chunk is fully usable
            Minecraft.getInstance().tell(() -> {
                for (UUID id : toReResolve) {
                    reResolve(id, level);
                }
            });
        }
    }

    private static void reResolve(UUID decalId, Level level) {
        Decal decal = ClientDecalCache.get(decalId);
        if (decal == null) {
            pendingReResolve.remove(decalId);
            return;
        }

        ProjectionResult result = ProjectionResolver.resolve(decal, level);
        ClientSpatialIndex.register(decal.id(), decal.zOrder(), result.surface().fragments());
        DecalRenderer.cacheResolved(decal.id(), decal,
            result.surface(), result.state());

        // Check if fully resolved now
        if (allChunksLoaded(decal, level)) {
            pendingReResolve.remove(decalId);
        }
    }

    /**
     * Checks whether all chunks overlapping the decal's projection volume are actually
     * present in the client chunk cache. ClientLevel.hasChunk() always returns true,
     * so we must query the ChunkSource directly with requireChunk=false.
     */
    private static boolean allChunksLoaded(Decal decal, Level level) {
        AABB bounds = Projection.fromDecal(decal).toBoundingBox();
        int minCX = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minX));
        int maxCX = SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxX));
        int minCZ = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minZ));
        int maxCZ = SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxZ));

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean chunkOverlapsAABB(ChunkPos chunk, AABB bounds) {
        int chunkMinX = chunk.getMinBlockX();
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunk.getMinBlockZ();
        int chunkMaxZ = chunkMinZ + 15;
        return bounds.maxX >= chunkMinX && bounds.minX <= chunkMaxX
            && bounds.maxZ >= chunkMinZ && bounds.minZ <= chunkMaxZ;
    }

    public static void remove(UUID id) {
        pendingReResolve.remove(id);
    }

    public static void clear() {
        pendingReResolve.clear();
    }
}
