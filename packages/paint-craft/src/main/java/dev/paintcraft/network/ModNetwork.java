package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.client.ClientBrushHandler;
import dev.paintcraft.client.ClientDecalCache;
import dev.paintcraft.client.DecalRenderer;
import dev.paintcraft.core.Decal;
import dev.paintcraft.projection.ProjectionResolver;
import dev.paintcraft.projection.ResolvedSurface;
import dev.paintcraft.storage.ChunkPaintStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    private ModNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PaintCraft.MODID)
            .versioned(PROTOCOL_VERSION);

        registrar.playBidirectional(
            DecalCreatePayload.TYPE,
            DecalCreatePayload.STREAM_CODEC,
            ModNetwork::handleDecalCreate
        );

        registrar.playBidirectional(
            DecalDeletePayload.TYPE,
            DecalDeletePayload.STREAM_CODEC,
            ModNetwork::handleDecalDelete
        );

        registrar.playToClient(
            OpenEditorPayload.TYPE,
            OpenEditorPayload.STREAM_CODEC,
            ModNetwork::handleOpenEditor
        );
    }

    private static void handleDecalCreate(DecalCreatePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                handleDecalCreateOnServer(payload, player);
            } else {
                handleDecalCreateOnClient(payload);
            }
        });
    }

    private static void handleDecalCreateOnServer(DecalCreatePayload payload, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ChunkPaintStorage storage = ChunkPaintStorage.get(level, new ChunkPos(payload.anchor()));

        long seqNo = payload.seqNo() > 0 ? payload.seqNo() : storage.nextSeqNo();
        Decal decal = new Decal(
            payload.id(), seqNo, payload.anchor(),
            payload.normal(), payload.up(),
            payload.widthPx(), payload.heightPx(), payload.depth(),
            payload.pixels(), payload.flags()
        );
        decal.setAuthor(player.getGameProfile().getName());
        storage.putDecal(decal);

        // Broadcast to all players tracking this chunk (including the sender)
        PacketDistributor.sendToPlayersTrackingChunk(
            level, new ChunkPos(decal.anchor()),
            DecalCreatePayload.fromDecal(decal)
        );

        PaintCraft.LOGGER.debug("Server stored and broadcast decal {} from {}", decal.id(), player.getName().getString());
    }

    private static void handleDecalCreateOnClient(DecalCreatePayload payload) {
        Decal decal = new Decal(
            payload.id(), payload.seqNo(), payload.anchor(),
            payload.normal(), payload.up(),
            payload.widthPx(), payload.heightPx(), payload.depth(),
            payload.pixels(), payload.flags()
        );

        ClientDecalCache.put(decal);

        Level level = Minecraft.getInstance().level;
        if (level != null) {
            ResolvedSurface resolved = ProjectionResolver.resolve(decal, level);
            ClientDecalCache.Entry entry = ClientDecalCache.get(decal.id());
            if (entry != null) {
                DecalRenderer.cacheResolved(decal.id(), decal, entry.texture(), resolved);
            }
        }

        PaintCraft.LOGGER.debug("Client resolved and cached decal {}", payload.id());
    }

    private static void handleDecalDelete(DecalDeletePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                // Server side: remove from storage, broadcast to others
                ServerLevel level = serverPlayer.serverLevel();
                ChunkPaintStorage storage = ChunkPaintStorage.get(level, new ChunkPos(serverPlayer.blockPosition()));
                storage.getDecal(payload.id()).ifPresent(decal -> {
                    storage.removeDecal(payload.id());
                    PacketDistributor.sendToPlayersTrackingChunk(
                        level, new ChunkPos(decal.anchor()), payload
                    );
                });
            } else {
                // Client side: remove from cache
                ClientDecalCache.remove(payload.id());
                DecalRenderer.invalidate(payload.id());
            }
        });
    }

    private static void handleOpenEditor(OpenEditorPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientBrushHandler.openExistingEditor(
                payload.anchor(), payload.normal(), payload.up(),
                payload.widthPx() / Decal.PX_PER_BLOCK,
                payload.heightPx() / Decal.PX_PER_BLOCK,
                payload.pixels(), payload.id()
            );
        });
    }
}
