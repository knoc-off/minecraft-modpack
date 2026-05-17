package dev.paintcraft.client;

import dev.paintcraft.PaintCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

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
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDecalCache.clear();
        DecalRenderer.invalidateAll();
        ClientBrushHandler.clearPending();
        ClientSpatialIndex.clear();
    }
}
