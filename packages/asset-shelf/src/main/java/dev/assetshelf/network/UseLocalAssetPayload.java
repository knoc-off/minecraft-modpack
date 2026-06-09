package dev.assetshelf.network;

import dev.assetshelf.AssetShelf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server: use a local asset (give me items with these bytes).
 */
public record UseLocalAssetPayload(ResourceLocation typeId, byte[] data, int quantity) implements CustomPacketPayload {

    private static final int MAX_DATA_SIZE = 512 * 1024; // 512KB

    public static final Type<UseLocalAssetPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AssetShelf.MODID, "use_local_asset"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UseLocalAssetPayload> STREAM_CODEC =
        StreamCodec.of(UseLocalAssetPayload::write, UseLocalAssetPayload::read);

    @Override
    public Type<UseLocalAssetPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, UseLocalAssetPayload p) {
        buf.writeResourceLocation(p.typeId);
        buf.writeByteArray(p.data);
        buf.writeVarInt(p.quantity);
    }

    private static UseLocalAssetPayload read(FriendlyByteBuf buf) {
        return new UseLocalAssetPayload(buf.readResourceLocation(), buf.readByteArray(MAX_DATA_SIZE), buf.readVarInt());
    }
}
