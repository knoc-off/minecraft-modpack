package dev.assetshelf.network;

import dev.assetshelf.AssetShelf;
import dev.assetshelf.api.AssetShelfApi;
import dev.assetshelf.api.AssetType;
import dev.assetshelf.client.ClientShelfHandlers;
import dev.assetshelf.core.AssetMeta;
import dev.assetshelf.storage.ServerLibrary;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

public final class ShelfNetwork {

    private static final String PROTOCOL_VERSION = "4";

    private ShelfNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(AssetShelf.MODID)
            .versioned(PROTOCOL_VERSION);

        registrar.playToServer(
            BrowseRequestPayload.TYPE,
            BrowseRequestPayload.STREAM_CODEC,
            ShelfNetwork::handleBrowseRequest
        );

        registrar.playToClient(
            BrowseResponsePayload.TYPE,
            BrowseResponsePayload.STREAM_CODEC,
            ShelfNetwork::handleBrowseResponse
        );

        registrar.playToServer(
            PublishPayload.TYPE,
            PublishPayload.STREAM_CODEC,
            ShelfNetwork::handlePublish
        );

        registrar.playToServer(
            DeletePublishedPayload.TYPE,
            DeletePublishedPayload.STREAM_CODEC,
            ShelfNetwork::handleDeletePublished
        );

        registrar.playToServer(
            UseServerAssetPayload.TYPE,
            UseServerAssetPayload.STREAM_CODEC,
            ShelfNetwork::handleUseServerAsset
        );

        registrar.playToServer(
            UseLocalAssetPayload.TYPE,
            UseLocalAssetPayload.STREAM_CODEC,
            ShelfNetwork::handleUseLocalAsset
        );

        registrar.playToServer(
            UpdatePublishedPayload.TYPE,
            UpdatePublishedPayload.STREAM_CODEC,
            ShelfNetwork::handleUpdatePublished
        );
    }

    // --- Server-side handlers ---

    private static void handleBrowseRequest(BrowseRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                ServerLevel level = player.serverLevel();
                ServerLibrary lib = ServerLibrary.get(level);

                int page = Math.max(0, payload.page());
                int pageSize = Math.min(20, Math.max(1, payload.pageSize()));

                List<AssetMeta> page_assets = lib.list(payload.typeId(), page, pageSize,
                    payload.filter(), payload.tagFilters());
                List<BrowseResponsePayload.Entry> entries = new ArrayList<>(page_assets.size());
                for (AssetMeta meta : page_assets) {
                    entries.add(new BrowseResponsePayload.Entry(meta, lib.getData(meta.id())));
                }

                ctx.reply(new BrowseResponsePayload(entries,
                    lib.totalCount(payload.typeId(), payload.filter(), payload.tagFilters()), page));
            }
        });
    }

    private static void handlePublish(PublishPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                // Validate typeId is registered
                if (AssetShelfApi.getType(payload.typeId()) == null) {
                    player.displayClientMessage(
                        Component.literal("Unknown asset type: " + payload.typeId()), true);
                    return;
                }

                // Validate name
                String name = payload.name().trim();
                if (name.isEmpty()) name = "Unnamed";
                if (name.length() > 128) name = name.substring(0, 128);

                // Validate description
                String desc = payload.description() != null ? payload.description().trim() : "";
                if (desc.length() > 512) desc = desc.substring(0, 512);

                // Clamp dimensions
                int w = Math.max(1, Math.min(4096, payload.widthPx()));
                int h = Math.max(1, Math.min(4096, payload.heightPx()));

                // Cap tags
                List<String> tags = payload.tags();
                if (tags.size() > 16) tags = tags.subList(0, 16);

                // Validate data is non-empty
                if (payload.data() == null || payload.data().length == 0) {
                    player.displayClientMessage(
                        Component.literal("Empty asset data"), true);
                    return;
                }

                ServerLevel level = player.serverLevel();
                ServerLibrary lib = ServerLibrary.get(level);

                // Reject if UUID already exists (prevents overwrite/duplicate)
                if (lib.getMetadata(payload.assetId()).isPresent()) {
                    player.displayClientMessage(
                        Component.literal("Asset already published"), true);
                    return;
                }

                AssetMeta meta = new AssetMeta(
                    payload.typeId(),
                    payload.assetId(),
                    name,
                    desc,
                    player.getUUID(),
                    player.getGameProfile().getName(),
                    w, h,
                    System.currentTimeMillis(),
                    tags
                );
                lib.put(meta, payload.data());

                player.displayClientMessage(
                    Component.literal("Published '" + name + "' to server library"), true);
                AssetShelf.LOGGER.info("Player {} published asset '{}'",
                    player.getName().getString(), name);
            }
        });
    }

    private static void handleDeletePublished(DeletePublishedPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                ServerLevel level = player.serverLevel();
                ServerLibrary lib = ServerLibrary.get(level);

                lib.getMetadata(payload.assetId()).ifPresent(meta -> {
                    boolean isAuthor = meta.authorUUID().equals(player.getUUID());
                    boolean isOp = player.hasPermissions(2);
                    if (isAuthor || isOp) {
                        lib.remove(payload.assetId());
                        player.displayClientMessage(
                            Component.literal("Deleted '" + meta.name() + "'"), true);
                    } else {
                        player.displayClientMessage(
                            Component.literal("You can only delete your own assets"), true);
                    }
                });
            }
        });
    }

    private static void handleUpdatePublished(UpdatePublishedPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                ServerLevel level = player.serverLevel();
                ServerLibrary lib = ServerLibrary.get(level);

                lib.getMetadata(payload.assetId()).ifPresent(existing -> {
                    boolean isAuthor = existing.authorUUID().equals(player.getUUID());
                    boolean isOp = player.hasPermissions(2);
                    if (!isAuthor && !isOp) {
                        player.displayClientMessage(
                            Component.literal("You can only edit your own assets"), true);
                        return;
                    }

                    String name = payload.name().trim();
                    if (name.isEmpty()) name = existing.name();
                    if (name.length() > 128) name = name.substring(0, 128);
                    String desc = payload.description() != null ? payload.description().trim() : "";
                    if (desc.length() > 512) desc = desc.substring(0, 512);
                    List<String> tags = payload.tags();
                    if (tags.size() > 16) tags = tags.subList(0, 16);

                    AssetMeta updated = new AssetMeta(
                        existing.typeId(), existing.id(),
                        name, desc,
                        existing.authorUUID(), existing.authorName(),
                        existing.widthPx(), existing.heightPx(),
                        existing.createdAt(), tags
                    );
                    // Re-read existing blob to pass to put() (which writes it back)
                    byte[] data = lib.getData(existing.id());
                    lib.put(updated, data);

                    player.displayClientMessage(
                        Component.literal("Updated '" + name + "'"), true);
                });
            }
        });
    }

    private static void handleUseServerAsset(UseServerAssetPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                ServerLevel level = player.serverLevel();
                ServerLibrary lib = ServerLibrary.get(level);
                int qty = Math.max(1, Math.min(64, payload.quantity()));

                lib.get(payload.assetId()).ifPresent(asset -> {
                    giveAssetToPlayer(player, asset.meta().typeId(), asset.data(), qty);
                });
            }
        });
    }

    private static void handleUseLocalAsset(UseLocalAssetPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                int qty = Math.max(1, Math.min(64, payload.quantity()));
                giveAssetToPlayer(player, payload.typeId(), payload.data(), qty);
            }
        });
    }

    private static void giveAssetToPlayer(ServerPlayer player, ResourceLocation typeId, byte[] data, int quantity) {
        AssetType type = AssetShelfApi.getType(typeId);
        if (type == null) {
            player.displayClientMessage(
                Component.literal("Unknown asset type: " + typeId), true);
            return;
        }

        if (!player.getAbilities().instabuild) {
            if (!type.consumeCost(player, data, quantity)) {
                return;
            }
        }

        for (int i = 0; i < quantity; i++) {
            type.onUse(player, data);
        }
    }

    // --- Client-side handlers ---

    private static void handleBrowseResponse(BrowseResponsePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientShelfHandlers.handleBrowseResponse(payload));
    }
}
