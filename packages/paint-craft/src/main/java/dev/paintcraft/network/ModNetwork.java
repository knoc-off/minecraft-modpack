package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.ModServerConfig;
import dev.paintcraft.client.ClientPayloadHandlers;
import dev.paintcraft.core.Decal;
import dev.paintcraft.item.EraserItem;
import dev.paintcraft.storage.ChunkPaintStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {

    private static final String PROTOCOL_VERSION = "2";

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

        registrar.playToClient(
            ChunkDecalBatchPayload.TYPE,
            ChunkDecalBatchPayload.STREAM_CODEC,
            ModNetwork::handleChunkDecalBatch
        );

        registrar.playToServer(
            DecalErasePayload.TYPE,
            DecalErasePayload.STREAM_CODEC,
            ModNetwork::handleDecalErase
        );

        registrar.playToServer(
            PasteChargeRequestPayload.TYPE,
            PasteChargeRequestPayload.STREAM_CODEC,
            ModNetwork::handlePasteChargeRequest
        );

        registrar.playToClient(
            PasteChargeResultPayload.TYPE,
            PasteChargeResultPayload.STREAM_CODEC,
            ModNetwork::handlePasteChargeResult
        );
    }

    private static void handleDecalCreate(DecalCreatePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                handleDecalCreateOnServer(payload, player);
            } else {
                ClientPayloadHandlers.handleDecalCreate(payload);
            }
        });
    }

    private static int maxDecalPx() {
        return Decal.PX_PER_BLOCK * ModServerConfig.CONFIG.maxCanvasSize.get();
    }

    private static double maxPlaceDistSq() {
        double d = ModServerConfig.CONFIG.maxPlacementDistance.get();
        return d * d;
    }

    private static int opLevel() {
        return ModServerConfig.CONFIG.opPermissionLevel.get();
    }

    private static float maxDepth() {
        return ModServerConfig.CONFIG.maxDepth.get().floatValue();
    }

    private static void handleDecalCreateOnServer(DecalCreatePayload payload, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ChunkPaintStorage storage = ChunkPaintStorage.get(level);

        // Shared validation: dimension bounds
        int w = payload.widthPx();
        int h = payload.heightPx();
        int maxPx = maxDecalPx();
        if (w < 1 || w > maxPx || h < 1 || h > maxPx) return;

        // Shared validation: pixel array must match declared dimensions
        if (payload.pixels().length != w * h) return;

        // Shared validation: normal and up must be on different axes
        if (payload.normal().getAxis() == payload.up().getAxis()) return;

        // --- EDIT path: if payload ID matches an existing decal, update it ---
        java.util.Optional<Decal> existing = storage.getDecal(payload.id());
        if (existing.isPresent()) {
            Decal old = existing.get();

            // Ownership check — same pattern as delete/reorder
            String author = old.author();
            String playerName = player.getGameProfile().getName();
            if (author != null && !author.isEmpty()
                    && !playerName.equals(author)
                    && !player.hasPermissions(opLevel())) {
                player.displayClientMessage(
                    Component.literal("You can only edit your own decals"), true);
                return;
            }

            // Distance check using stored anchor (not client-provided)
            if (player.blockPosition().distSqr(old.anchor()) > maxPlaceDistSq()) return;

            // Pixel array must match the stored decal's dimensions (no resizing via edit)
            if (payload.pixels().length != old.widthPx() * old.heightPx()) return;

            // If all pixels are blank, delete the decal instead of updating
            if (isBlank(payload.pixels())) {
                storage.removeDecal(old.id());
                ChunkPos chunk = new ChunkPos(old.anchor());
                PacketDistributor.sendToPlayersTrackingChunk(
                    level, chunk, new DecalDeletePayload(old.id())
                );
                PaintCraft.LOGGER.debug("Server deleted blank decal {} from {}", old.id(), player.getName().getString());
                return;
            }

            // Clamp depth
            float depth = Math.max(0f, Math.min(payload.depth(), maxDepth()));

            // Build updated decal — preserve id, seqNo, anchor, normal, up, dimensions, author
            Decal updated = new Decal(
                old.id(), old.seqNo(), old.anchor(),
                old.normal(), old.up(),
                old.widthPx(), old.heightPx(), depth,
                payload.pixels(), payload.flags()
            );
            updated.setAuthor(old.author());
            updated.setZOverride(old.zOrder());
            storage.putDecal(updated);

            ChunkPos chunk = new ChunkPos(old.anchor());
            PacketDistributor.sendToPlayersTrackingChunk(
                level, chunk,
                DecalCreatePayload.fromDecal(updated)
            );

            PaintCraft.LOGGER.debug("Server updated decal {} from {}", updated.id(), player.getName().getString());
            return;
        }

        // --- CREATE path: new decal ---

        // Distance check — anchor must be within reach
        if (player.blockPosition().distSqr(payload.anchor()) > maxPlaceDistSq()) {
            return;
        }

        // Per-chunk decal limit
        ChunkPos chunk = new ChunkPos(payload.anchor());
        if (storage.countDecalsInChunk(chunk) >= ModServerConfig.CONFIG.maxDecalsPerChunk.get()) {
            player.displayClientMessage(
                Component.literal("Too many decals in this area"), true);
            return;
        }

        // Server-assigned seqNo and UUID (never trust client values for new decals)
        long seqNo = storage.nextSeqNo();
        java.util.UUID id = java.util.UUID.randomUUID();

        // Don't create blank decals
        if (isBlank(payload.pixels())) return;

        // Clamp depth
        float depth = Math.max(0f, Math.min(payload.depth(), maxDepth()));

        Decal decal = new Decal(
            id, seqNo, payload.anchor(),
            payload.normal(), payload.up(),
            w, h, depth,
            payload.pixels(), payload.flags()
        );
        decal.setAuthor(player.getGameProfile().getName());
        storage.putDecal(decal);

        // Broadcast to all players tracking this chunk (including the sender)
        PacketDistributor.sendToPlayersTrackingChunk(
            level, chunk,
            DecalCreatePayload.fromDecal(decal)
        );

        PaintCraft.LOGGER.debug("Server stored and broadcast decal {} from {}", decal.id(), player.getName().getString());
    }

    private static void handleDecalDelete(DecalDeletePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                // Server side: verify ownership, remove from storage, broadcast
                ServerLevel level = serverPlayer.serverLevel();
                ChunkPaintStorage storage = ChunkPaintStorage.get(level);
                storage.getDecal(payload.id()).ifPresent(decal -> {
                    String author = decal.author();
                    String playerName = serverPlayer.getGameProfile().getName();
                    if (author != null && !author.isEmpty()
                            && !playerName.equals(author)
                            && !serverPlayer.hasPermissions(opLevel())) {
                        serverPlayer.displayClientMessage(
                            Component.literal("You can only delete your own decals"), true);
                        return;
                    }
                    storage.removeDecal(payload.id());
                    PacketDistributor.sendToPlayersTrackingChunk(
                        level, new ChunkPos(decal.anchor()), payload
                    );
                });
            } else {
                ClientPayloadHandlers.handleDecalDelete(payload);
            }
        });
    }

    private static void handleOpenEditor(OpenEditorPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPayloadHandlers.handleOpenEditor(payload));
    }

    private static void handleDecalSelection(DecalSelectionPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPayloadHandlers.handleDecalSelection(payload));
    }

    private static void handleDecalReorder(DecalReorderPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                ServerLevel level = player.serverLevel();
                ChunkPaintStorage storage = ChunkPaintStorage.get(level);
                storage.getDecal(payload.id()).ifPresent(decal -> {
                    // Ownership check
                    String author = decal.author();
                    String playerName = player.getGameProfile().getName();
                    if (author != null && !author.isEmpty()
                            && !playerName.equals(author)
                            && !player.hasPermissions(opLevel())) {
                        return;
                    }

                    long newZ;
                    if (payload.bringToFront()) {
                        newZ = storage.nextSeqNo();
                    } else {
                        long min = Long.MAX_VALUE;
                        for (Decal d : storage.allDecals()) {
                            min = Math.min(min, d.zOrder());
                        }
                        newZ = min - 1;
                    }
                    decal.setZOverride(newZ);
                    storage.setDirty();

                    PacketDistributor.sendToPlayersTrackingChunk(
                        level, new ChunkPos(decal.anchor()),
                        DecalCreatePayload.fromDecal(decal)
                    );
                });
            }
        });
    }

    private static void handleDecalInvalidate(DecalInvalidatePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPayloadHandlers.handleDecalInvalidate(payload));
    }

    private static void handleChunkDecalBatch(ChunkDecalBatchPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPayloadHandlers.handleChunkDecalBatch(payload));
    }

    private static void handleDecalErase(DecalErasePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                // Verify player is holding an eraser item
                boolean holdingEraser =
                    player.getMainHandItem().getItem() instanceof EraserItem
                    || player.getOffhandItem().getItem() instanceof EraserItem;
                if (!holdingEraser) return;

                ServerLevel level = player.serverLevel();
                ChunkPaintStorage storage = ChunkPaintStorage.get(level);
                storage.getDecal(payload.id()).ifPresent(decal -> {
                    // Distance check
                    if (player.blockPosition().distSqr(decal.anchor()) > maxPlaceDistSq()) return;

                    // No ownership check — eraser erases regardless of author
                    storage.removeDecal(payload.id());
                    PacketDistributor.sendToPlayersTrackingChunk(
                        level, new ChunkPos(decal.anchor()),
                        new DecalDeletePayload(payload.id())
                    );
                    player.displayClientMessage(Component.literal("Erased decal"), true);
                });
            }
        });
    }

    private static void handlePasteChargeRequest(PasteChargeRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            int[] pixels = payload.pixels();
            boolean success;
            if (pixels.length > dev.paintcraft.core.PaletteCodec.MAX_PIXELS) {
                success = false;
            } else if (player.getAbilities().instabuild) {
                success = true; // creative: paste is free
            } else {
                success = dev.paintcraft.core.cost.PaintCost.consume(player, pixels);
            }

            if (!success) {
                player.displayClientMessage(
                    Component.literal("Not enough dye to paste this image"), true);
            }
            PacketDistributor.sendToPlayer(player,
                new PasteChargeResultPayload(payload.requestId(), success));
        });
    }

    private static void handlePasteChargeResult(PasteChargeResultPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPayloadHandlers.handlePasteChargeResult(payload));
    }

    /** Returns true if every pixel in the array is fully transparent (alpha == 0). */
    private static boolean isBlank(int[] pixels) {
        for (int px : pixels) {
            if ((px >>> 24) != 0) return false;
        }
        return true;
    }
}
