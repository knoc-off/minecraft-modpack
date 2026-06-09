package dev.assetshelf.network;

import dev.assetshelf.AssetShelf;
import dev.assetshelf.core.AssetMeta;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: a page of public assets (metadata + full data for thumbnails).
 */
public record BrowseResponsePayload(
    List<Entry> entries,
    int totalCount,
    int page
) implements CustomPacketPayload {

    public record Entry(AssetMeta meta, byte[] data) {}

    public static final Type<BrowseResponsePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AssetShelf.MODID, "browse_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BrowseResponsePayload> STREAM_CODEC =
        StreamCodec.of(BrowseResponsePayload::write, BrowseResponsePayload::read);

    @Override
    public Type<BrowseResponsePayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, BrowseResponsePayload p) {
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            e.meta.write(buf);
            buf.writeByteArray(e.data);
        }
        buf.writeVarInt(p.totalCount);
        buf.writeVarInt(p.page);
    }

    private static BrowseResponsePayload read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AssetMeta meta = AssetMeta.read(buf);
            byte[] data = buf.readByteArray();
            entries.add(new Entry(meta, data));
        }
        int total = buf.readVarInt();
        int page = buf.readVarInt();
        return new BrowseResponsePayload(entries, total, page);
    }
}
