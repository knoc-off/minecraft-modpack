package dev.assetshelf.network;

import dev.assetshelf.AssetShelf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → Server: use a server-side asset (give me stamps).
 */
public record UseServerAssetPayload(UUID assetId, int quantity) implements CustomPacketPayload {

    public static final Type<UseServerAssetPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AssetShelf.MODID, "use_server_asset"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UseServerAssetPayload> STREAM_CODEC =
        StreamCodec.of(UseServerAssetPayload::write, UseServerAssetPayload::read);

    @Override
    public Type<UseServerAssetPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, UseServerAssetPayload p) {
        buf.writeUUID(p.assetId);
        buf.writeVarInt(p.quantity);
    }

    private static UseServerAssetPayload read(FriendlyByteBuf buf) {
        return new UseServerAssetPayload(buf.readUUID(), buf.readVarInt());
    }
}
