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
    private static final Map<ServerLevel, Set<BlockPos>> pendingChanges = new HashMap<>();
    // Right-click "before" states: checked at end of tick to see if block changed
    private static final Map<ServerLevel, Map<BlockPos, BlockState>> pendingRightClicks = new HashMap<>();

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

        // Early exit: check decals near this block using chunk index
        ChunkPaintStorage storage = ChunkPaintStorage.get(level);
        boolean anyOverlap = false;
        for (Decal d : storage.getDecalsNear(pos)) {
            if (ChunkPaintStorage.couldOverlap(d, pos)) {
                anyOverlap = true;
                break;
            }
        }
        if (!anyOverlap) return;

        // Record state before interaction — processBatch at end of this tick
        // will check if the block actually changed (no extra tick delay)
        BlockState before = level.getBlockState(pos);
        pendingRightClicks.computeIfAbsent(level, k -> new HashMap<>())
            .putIfAbsent(pos.immutable(), before);
    }

    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        // Process right-click checks: add positions where state actually changed
        if (!pendingRightClicks.isEmpty()) {
            for (var entry : pendingRightClicks.entrySet()) {
                ServerLevel level = entry.getKey();
                for (var posEntry : entry.getValue().entrySet()) {
                    BlockState after = level.getBlockState(posEntry.getKey());
                    if (!after.equals(posEntry.getValue())) {
                        pendingChanges.computeIfAbsent(level, k -> new HashSet<>())
                            .add(posEntry.getKey());
                    }
                }
            }
            pendingRightClicks.clear();
        }

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
        ChunkPaintStorage storage = ChunkPaintStorage.get(level);

        List<Decal> toRemove = new ArrayList<>();
        // Group invalidated decal IDs by their anchor chunk for packet sending
        Map<ChunkPos, Set<UUID>> invalidateByChunk = new HashMap<>();

        // Collect candidate decals from chunks near changed positions
        Set<UUID> seen = new HashSet<>();
        List<Decal> candidates = new ArrayList<>();
        for (BlockPos pos : changedPositions) {
            for (Decal d : storage.getDecalsNear(pos)) {
                if (seen.add(d.id())) {
                    candidates.add(d);
                }
            }
        }

        for (Decal d : candidates) {
            boolean overlapsAny = false;
            for (BlockPos pos : changedPositions) {
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
                ChunkPos chunk = new ChunkPos(d.anchor());
                invalidateByChunk.computeIfAbsent(chunk, k -> new LinkedHashSet<>()).add(d.id());
            }
        }

        // Remove decals whose anchor block was destroyed
        for (Decal d : toRemove) {
            storage.removeDecal(d.id());
            PacketDistributor.sendToPlayersTrackingChunk(
                level, new ChunkPos(d.anchor()),
                new DecalDeletePayload(d.id())
            );
        }

        // Send lightweight invalidation per chunk
        // Collect changed positions relevant to each chunk group
        for (var entry : invalidateByChunk.entrySet()) {
            PacketDistributor.sendToPlayersTrackingChunk(
                level, entry.getKey(),
                new DecalInvalidatePayload(
                    new ArrayList<>(entry.getValue()),
                    new ArrayList<>(changedPositions)
                )
            );
        }
    }
}
