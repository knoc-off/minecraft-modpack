package dev.assetshelf.network;

import dev.assetshelf.AssetShelf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: publish a local asset to the public library.
 */
public record PublishPayload(
    ResourceLocation typeId,
    UUID assetId,
    String name,
    String description,
    int widthPx,
    int heightPx,
    byte[] data,
    List<String> tags
) implements CustomPacketPayload {

    private static final int MAX_DATA_SIZE = 512 * 1024; // 512KB

    public static final Type<PublishPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AssetShelf.MODID, "publish"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PublishPayload> STREAM_CODEC =
        StreamCodec.of(PublishPayload::write, PublishPayload::read);

    @Override
    public Type<PublishPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, PublishPayload p) {
        buf.writeResourceLocation(p.typeId);
        buf.writeUUID(p.assetId);
        buf.writeUtf(p.name, 128);
        buf.writeUtf(p.description, 512);
        buf.writeVarInt(p.widthPx);
        buf.writeVarInt(p.heightPx);
        buf.writeByteArray(p.data);
        buf.writeVarInt(p.tags.size());
        for (String tag : p.tags) buf.writeUtf(tag, 32);
    }

    private static PublishPayload read(FriendlyByteBuf buf) {
        ResourceLocation typeId = buf.readResourceLocation();
        UUID assetId = buf.readUUID();
        String name = buf.readUtf(128);
        String description = buf.readUtf(512);
        int w = buf.readVarInt();
        int h = buf.readVarInt();
        byte[] data = buf.readByteArray(MAX_DATA_SIZE);
        int tagCount = Math.min(buf.readVarInt(), 16);
        List<String> tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) tags.add(buf.readUtf(32));
        return new PublishPayload(typeId, assetId, name, description, w, h, data, List.copyOf(tags));
    }
}
