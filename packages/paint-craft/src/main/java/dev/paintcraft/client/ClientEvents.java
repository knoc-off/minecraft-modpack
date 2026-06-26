package dev.paintcraft.client;

import com.mojang.brigadier.Command;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.paintcraft.PaintCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.config.ModConfig;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;

import static net.minecraft.commands.Commands.literal;

@EventBusSubscriber(modid = PaintCraft.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    // RegisterShadersEvent implements IModBusEvent, so NeoForge auto-routes this
    // handler to the mod event bus (the `bus` element is ignored as of 1.21.1).
    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
            new ShaderInstance(
                event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "rendertype_decal"),
                DefaultVertexFormat.BLOCK
            ),
            shader -> DecalRenderType.decalShader = shader
        );
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 cameraPos = event.getCamera().getPosition();
        DecalRenderer.renderAll(
            cameraPos,
            event.getFrustum(),
            event.getModelViewMatrix(),
            event.getProjectionMatrix()
        );

        StampPreviewRenderer.render(
            event.getPoseStack(),
            mc.renderBuffers().bufferSource(),
            cameraPos
        );
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        DebugOverlay.render(event.getGuiGraphics());
    }

    // ModConfigEvent implements IModBusEvent, so NeoForge auto-routes these to the mod bus.
    // Re-mesh decals when the client config is loaded or edited so reliefEnabled toggles live.
    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getType() == ModConfig.Type.CLIENT) DecalRenderer.onConfigReloaded();
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.CLIENT) DecalRenderer.onConfigReloaded();
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
        // Dirty this chunk + neighbors — VBO rebuild is gated on lightOnInSection in renderAll()
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                DecalRenderer.markChunkDirty(new ChunkPos(pos.x + dx, pos.z + dz));
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;

        ChunkPos pos = event.getChunk().getPos();
        DecalRenderer.onChunkUnload(pos);
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
