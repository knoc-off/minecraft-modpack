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
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = PaintCraft.MODID)
public final class BlockChangeHandler {

    private BlockChangeHandler() {}

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos changed = event.getPos();
        ChunkPaintStorage storage = ChunkPaintStorage.get(level, new ChunkPos(changed));

        List<Decal> toRemove = new ArrayList<>();
        List<Decal> toRebroadcast = new ArrayList<>();

        for (Decal d : storage.allDecals()) {
            if (!ChunkPaintStorage.couldOverlap(d, changed)) continue;

            BlockState anchorState = level.getBlockState(d.anchor());
            if (anchorState.getShape(level, d.anchor(), CollisionContext.empty()).isEmpty()) {
                toRemove.add(d);
            } else {
                toRebroadcast.add(d);
            }
        }

        for (Decal d : toRemove) {
            storage.removeDecal(d.id());
            PacketDistributor.sendToPlayersTrackingChunk(
                level, new ChunkPos(d.anchor()),
                new DecalDeletePayload(d.id())
            );
        }

        for (Decal d : toRebroadcast) {
            PacketDistributor.sendToPlayersTrackingChunk(
                level, new ChunkPos(d.anchor()),
                DecalCreatePayload.fromDecal(d)
            );
        }
    }
}
