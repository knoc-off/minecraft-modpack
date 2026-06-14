package dev.paintcraft.client;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.client.gui.DecalSelectionScreen;
import dev.paintcraft.core.Decal;
import dev.paintcraft.network.ChunkDecalBatchPayload;
import dev.paintcraft.network.DecalCreatePayload;
import dev.paintcraft.network.DecalDeletePayload;
import dev.paintcraft.network.DecalInvalidatePayload;
import dev.paintcraft.network.DecalSelectionPayload;
import dev.paintcraft.network.OpenEditorPayload;
import dev.paintcraft.projection.ProjectionResolver;
import dev.paintcraft.projection.ProjectionResult;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Client-side payload handler bodies for PaintCraft's network.
 * <p>
 * These methods touch client-only types ({@link Minecraft}, {@code ClientLevel},
 * {@link DecalSelectionScreen}, the client decal caches, …). They are isolated in
 * this client-package class so that {@code ModNetwork} — which is loaded and
 * verified on the dedicated server during payload registration — never contains a
 * reference to a client-only type. {@code ModNetwork} delegates here via plain
 * static calls, which are only resolved/executed on the physical client.
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {}

    public static void handleDecalCreate(DecalCreatePayload payload) {
        Decal decal = new Decal(
            payload.id(), payload.seqNo(), payload.anchor(),
            payload.normal(), payload.up(),
            payload.widthPx(), payload.heightPx(), payload.depth(),
            payload.pixels(), payload.flags()
        );

        ClientDecalCache.put(decal);

        Level level = Minecraft.getInstance().level;
        if (level != null) {
            ProjectionResult result = ProjectionResolver.resolve(decal, level);
            ClientSpatialIndex.register(decal.id(), decal.zOrder(), result.surface().fragments());
            DecalRenderer.cacheResolved(decal.id(), decal,
                result.surface(), result.state());

            // Track for re-resolution if not all chunks in the decal's volume are loaded yet
            ClientDecalResolver.markPendingIfIncomplete(decal, level);
        }

        PaintCraft.LOGGER.debug("Client resolved and cached decal {}", payload.id());
    }

    public static void handleDecalDelete(DecalDeletePayload payload) {
        // Mark compositor dirty BEFORE unregistering spatial index
        CellCompositor.markDecalDirty(payload.id());
        ClientSpatialIndex.unregister(payload.id());
        ClientDecalCache.remove(payload.id());
        DecalRenderer.invalidate(payload.id());
    }

    public static void handleOpenEditor(OpenEditorPayload payload) {
        ClientBrushHandler.openExistingEditor(
            payload.anchor(), payload.normal(), payload.up(),
            payload.widthPx() / Decal.PX_PER_BLOCK,
            payload.heightPx() / Decal.PX_PER_BLOCK,
            payload.depth(), payload.pixels(), payload.id()
        );
    }

    public static void handleDecalSelection(DecalSelectionPayload payload) {
        Minecraft.getInstance().setScreen(
            new DecalSelectionScreen(payload.entries(), payload.eraseMode()));
    }

    public static void handleDecalInvalidate(DecalInvalidatePayload payload) {
        DeferredInvalidator.invalidate(payload.decalIds(), payload.changedPositions());
    }

    public static void handleChunkDecalBatch(ChunkDecalBatchPayload payload) {
        for (DecalCreatePayload decal : payload.decals()) {
            handleDecalCreate(decal);
        }
    }
}
