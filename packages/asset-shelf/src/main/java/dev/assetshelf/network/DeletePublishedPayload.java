package dev.assetshelf.network;

import dev.assetshelf.AssetShelf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → Server: delete a published asset (author or op only).
 */
public record DeletePublishedPayload(UUID assetId) implements CustomPacketPayload {

    public static final Type<DeletePublishedPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AssetShelf.MODID, "delete_published"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeletePublishedPayload> STREAM_CODEC =
        StreamCodec.of(DeletePublishedPayload::write, DeletePublishedPayload::read);

    @Override
    public Type<DeletePublishedPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, DeletePublishedPayload p) {
        buf.writeUUID(p.assetId);
    }

    private static DeletePublishedPayload read(FriendlyByteBuf buf) {
        return new DeletePublishedPayload(buf.readUUID());
    }
}
