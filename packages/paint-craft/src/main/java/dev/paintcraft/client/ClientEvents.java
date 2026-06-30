package dev.paintcraft.client;

import com.mojang.brigadier.Command;
import dev.paintcraft.PaintCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import dev.paintcraft.client.compat.iris.IrisCompat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.config.ModConfig;
import org.lwjgl.glfw.GLFW;

import static net.minecraft.commands.Commands.literal;

@EventBusSubscriber(modid = PaintCraft.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        boolean irisActive = IrisCompat.isShadersActive();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 cameraPos = event.getCamera().getPosition();
        var frustum = event.getFrustum();
        var mv = event.getModelViewMatrix();
        var proj = event.getProjectionMatrix();

        if (irisActive) {
            // Iris deferred pipeline handles water compositing itself — render all sets
            // in the translucent stage so geometry lands in gbuffers_water.
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
            DecalRenderer.update(cameraPos, true);
            DecalRenderer.drawEarly(cameraPos, frustum, mv, proj, true);
            DecalRenderer.drawLate(cameraPos, frustum, mv, proj, true);
            StampPreviewRenderer.render(event.getPoseStack(), mc.renderBuffers().bufferSource(), cameraPos);
        } else {
            // Vanilla two-stage split:
            //   Early (AFTER_BLOCK_ENTITIES):  opaque + translucent-beside-water — water blends over.
            //   Late  (AFTER_TRANSLUCENT_BLOCKS): translucent-in-air — drawn after water, blends over it.
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
                DecalRenderer.update(cameraPos, false);
                DecalRenderer.drawEarly(cameraPos, frustum, mv, proj, false);
                StampPreviewRenderer.render(event.getPoseStack(), mc.renderBuffers().bufferSource(), cameraPos);
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                DecalRenderer.drawLate(cameraPos, frustum, mv, proj, false);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        DebugOverlay.render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getType() == ModConfig.Type.CLIENT) DecalRenderer.onConfigReloaded();
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.CLIENT) DecalRenderer.onConfigReloaded();
    }

    @SubscribeEvent
    public static void onRegisterTooltipFactories(
            net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(dev.paintcraft.item.StampTooltipComponent.class,
                ClientStampTooltipComponent::new);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            literal("paintcraft")
                .then(literal("debug")
                    .executes(ctx -> {
                        DebugOverlay.toggle();
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("PaintCraft debug overlay: " +
                                (DebugOverlay.isEnabled() ? "ON" : "OFF")),
                            false);
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(literal("copy")
                        .executes(ctx -> {
                            String text = DebugOverlay.getStatsText();
                            long window = Minecraft.getInstance().getWindow().getWindow();
                            GLFW.glfwSetClipboardString(window, text);
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("Debug stats copied to clipboard"),
                                false);
                            return Command.SINGLE_SUCCESS;
                        })))
        );
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof Level level)) return;

        ChunkPos pos = event.getChunk().getPos();
        ClientDecalResolver.onChunkLoaded(pos, level);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                DecalRenderer.markChunkDirty(new ChunkPos(pos.x + dx, pos.z + dz));
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        DecalRenderer.onChunkUnload(event.getChunk().getPos());
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDecalCache.clear();
        DecalRenderer.invalidateAll();
        CellCompositor.destroyAll();
        ClientBrushHandler.clearPending();
        ClientSpatialIndex.clear();
        ClientDecalResolver.clear();
        DeferredInvalidator.clear();
        if (dev.paintcraft.compat.create.CreateCompat.isLoaded()) {
            dev.paintcraft.client.compat.create.ContraptionDecalRenderer.clear();
        }
    }
}
