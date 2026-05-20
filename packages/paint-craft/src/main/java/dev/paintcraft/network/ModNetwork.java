package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.client.ClientBrushHandler;
import dev.paintcraft.client.ClientDecalCache;
import dev.paintcraft.client.ClientDecalResolver;
import dev.paintcraft.client.ClientSpatialIndex;
import dev.paintcraft.client.DecalRenderer;
import dev.paintcraft.client.DeferredInvalidator;
import dev.paintcraft.client.gui.DecalSelectionScreen;
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

import java.util.Set;
import java.util.UUID;

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

        registrar.playToClient(
            DecalSelectionPayload.TYPE,
            DecalSelectionPayload.STREAM_CODEC,
            ModNetwork::handleDecalSelection
        );

        registrar.playToServer(
            DecalReorderPayload.TYPE,
            DecalReorderPayload.STREAM_CODEC,
            ModNetwork::handleDecalReorder
        );

        registrar.playToClient(
            DecalInvalidatePayload.TYPE,
            DecalInvalidatePayload.STREAM_CODEC,
            ModNetwork::handleDecalInvalidate
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

            // Register in spatial index and assign z-tiers
            ClientSpatialIndex.register(decal.id(), decal.zOrder(), resolved.fragments());
            ResolvedSurface tiered = ClientSpatialIndex.assignTiers(decal.id(), resolved);

            ClientDecalCache.Entry entry = ClientDecalCache.get(decal.id());
            if (entry != null) {
                DecalRenderer.cacheResolved(decal.id(), decal, entry.texture(), tiered);
            }

            // Track for re-resolution if not all chunks in the decal's volume are loaded yet
            ClientDecalResolver.markPendingIfIncomplete(decal, level);

            // Re-tier any other decals that now overlap with this one
            retierOverlapping(decal.id());
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
                // Client side: find overlapping before removing
                Set<UUID> affected = ClientSpatialIndex.getOverlapping(payload.id());
                ClientSpatialIndex.unregister(payload.id());
                ClientDecalCache.remove(payload.id());
                DecalRenderer.invalidate(payload.id());

                // Re-tier decals that were overlapping with the deleted one
                for (UUID otherId : affected) {
                    retierDecal(otherId);
                }
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

    private static void handleDecalSelection(DecalSelectionPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new DecalSelectionScreen(payload.entries()));
        });
    }

    private static void handleDecalReorder(DecalReorderPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                ServerLevel level = player.serverLevel();
                ChunkPaintStorage storage = ChunkPaintStorage.get(level, new ChunkPos(player.blockPosition()));
                storage.getDecal(payload.id()).ifPresent(decal -> {
                    long newZ;
                    if (payload.bringToFront()) {
                        newZ = storage.nextSeqNo(); // higher than any existing
                    } else {
                        // Send to back: find minimum zOverride and go below it
                        long min = Long.MAX_VALUE;
                        for (Decal d : storage.allDecals()) {
                            min = Math.min(min, d.zOrder());
                        }
                        newZ = min - 1;
                    }
                    decal.setZOverride(newZ);
                    storage.setDirty();

                    // Broadcast the updated decal
                    PacketDistributor.sendToPlayersTrackingChunk(
                        level, new ChunkPos(decal.anchor()),
                        DecalCreatePayload.fromDecal(decal)
                    );
                });
            }
        });
    }

    private static void handleDecalInvalidate(DecalInvalidatePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DeferredInvalidator.invalidate(payload.decalIds()));
    }

    /**
     * Re-tier all decals that overlap with the given decal.
     */
    private static void retierOverlapping(UUID decalId) {
        Set<UUID> affected = ClientSpatialIndex.getOverlapping(decalId);
        for (UUID otherId : affected) {
            retierDecal(otherId);
        }
    }

    /**
     * Re-assign z-tiers for a single decal using its existing resolved surface.
     * Does NOT re-resolve geometry (which is expensive) -- only updates tier indices
     * in the spatial index based on current overlap state.
     */
    private static void retierDecal(UUID decalId) {
        ClientDecalCache.Entry entry = ClientDecalCache.get(decalId);
        if (entry == null) return;

        DecalRenderer.ResolvedEntry existing = DecalRenderer.getResolved(decalId);
        if (existing == null) return;

        ResolvedSurface retiered = ClientSpatialIndex.assignTiers(decalId, existing.surface());
        DecalRenderer.cacheResolved(decalId, entry.decal(), entry.texture(), retiered);
    }
}
