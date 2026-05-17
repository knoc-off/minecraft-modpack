package dev.paintcraft.client;

import dev.paintcraft.PaintCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

@EventBusSubscriber(modid = PaintCraft.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 cameraPos = event.getCamera().getPosition();
        DecalRenderer.renderAll(
            event.getPoseStack(),
            mc.renderBuffers().bufferSource(),
            cameraPos
        );

        // Stamp ghost preview
        StampPreviewRenderer.render(
            event.getPoseStack(),
            mc.renderBuffers().bufferSource(),
            cameraPos
        );
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof Level level)) return;

        ChunkPos pos = event.getChunk().getPos();
        ClientDecalResolver.onChunkLoaded(pos, level);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDecalCache.clear();
        DecalRenderer.invalidateAll();
        ClientBrushHandler.clearPending();
        ClientSpatialIndex.clear();
        ClientDecalResolver.clear();
    }
}
