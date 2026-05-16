package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.Decal;
import dev.paintcraft.storage.ChunkPaintStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = PaintCraft.MODID)
public final class ChunkSyncHandler {

    private ChunkSyncHandler() {}

    @SubscribeEvent
    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        ServerLevel level = event.getLevel();
        ChunkPos pos = event.getPos();
        ChunkPaintStorage storage = ChunkPaintStorage.get(level, pos);

        for (Decal d : storage.getDecalsInChunk(pos)) {
            PacketDistributor.sendToPlayer(event.getPlayer(), DecalCreatePayload.fromDecal(d));
        }
    }
}
