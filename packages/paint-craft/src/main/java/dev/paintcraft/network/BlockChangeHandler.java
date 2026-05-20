package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.Decal;
import dev.paintcraft.storage.ChunkPaintStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Handles block changes that affect decal projection surfaces.
 *
 * Performance strategy:
 * 1. Collect changed positions per-tick instead of handling each event immediately
 * 2. Deduplicate: multiple events on the same block in one tick = one check
 * 3. Send lightweight invalidation packets (just UUIDs) instead of full decal data
 * 4. Early exit: skip events when no decals could possibly overlap
 */
@EventBusSubscriber(modid = PaintCraft.MODID)
public final class BlockChangeHandler {

    private BlockChangeHandler() {}

    // Per-level pending changes, processed at end of tick
    private static final Map<ServerLevel, Set<BlockPos>> pendingChanges = new WeakHashMap<>();

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        enqueue(level, event.getPos());
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();

        // Early exit: don't schedule anything if no decals could overlap this block
        ChunkPaintStorage storage = ChunkPaintStorage.get(level, new ChunkPos(pos));
        boolean anyOverlap = false;
        for (Decal d : storage.allDecals()) {
            if (ChunkPaintStorage.couldOverlap(d, pos)) {
                anyOverlap = true;
                break;
            }
        }
        if (!anyOverlap) return;

        BlockState before = level.getBlockState(pos);

        // Check next tick if shape actually changed
        level.getServer().tell(new net.minecraft.server.TickTask(
            level.getServer().getTickCount() + 1,
            () -> {
                BlockState after = level.getBlockState(pos);
                if (!after.equals(before)) {
                    enqueue(level, pos);
                }
            }
        ));
    }

    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        if (pendingChanges.isEmpty()) return;

        for (var entry : pendingChanges.entrySet()) {
            ServerLevel level = entry.getKey();
            Set<BlockPos> positions = entry.getValue();
            if (positions.isEmpty()) continue;

            processBatch(level, positions);
        }
        pendingChanges.clear();
    }

    private static void enqueue(ServerLevel level, BlockPos pos) {
        pendingChanges.computeIfAbsent(level, k -> new HashSet<>()).add(pos.immutable());
    }

    private static void processBatch(ServerLevel level, Set<BlockPos> changedPositions) {
        // Group by chunk, since decals are stored per-chunk
        Map<ChunkPos, Set<BlockPos>> byChunk = new HashMap<>();
        for (BlockPos pos : changedPositions) {
            byChunk.computeIfAbsent(new ChunkPos(pos), k -> new HashSet<>()).add(pos);
        }

        for (var chunkEntry : byChunk.entrySet()) {
            ChunkPos chunkPos = chunkEntry.getKey();
            Set<BlockPos> positions = chunkEntry.getValue();
            ChunkPaintStorage storage = ChunkPaintStorage.get(level, chunkPos);

            List<Decal> toRemove = new ArrayList<>();
            Set<UUID> toInvalidate = new LinkedHashSet<>();

            for (Decal d : storage.allDecals()) {
                boolean overlapsAny = false;
                for (BlockPos pos : positions) {
                    if (ChunkPaintStorage.couldOverlap(d, pos)) {
                        overlapsAny = true;
                        break;
                    }
                }
                if (!overlapsAny) continue;

                // Check if anchor block lost its shape (decal should be removed)
                BlockState anchorState = level.getBlockState(d.anchor());
                if (anchorState.getShape(level, d.anchor(), CollisionContext.empty()).isEmpty()) {
                    toRemove.add(d);
                } else {
                    toInvalidate.add(d.id());
                }
            }

            // Remove decals whose anchor block was destroyed
            for (Decal d : toRemove) {
                storage.removeDecal(d.id());
                PacketDistributor.sendToPlayersTrackingChunk(
                    level, chunkPos,
                    new DecalDeletePayload(d.id())
                );
            }

            // Send lightweight invalidation for surviving decals
            if (!toInvalidate.isEmpty()) {
                PacketDistributor.sendToPlayersTrackingChunk(
                    level, chunkPos,
                    new DecalInvalidatePayload(
                        new ArrayList<>(toInvalidate),
                        new ArrayList<>(positions)
                    )
                );
            }
        }
    }
}
