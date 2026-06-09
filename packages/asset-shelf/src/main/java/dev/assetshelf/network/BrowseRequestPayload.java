package dev.assetshelf.network;

import dev.assetshelf.AssetShelf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → Server: request a page of public assets for a specific type.
 */
public record BrowseRequestPayload(ResourceLocation typeId, int page, int pageSize,
                                    String filter, List<String> tagFilters) implements CustomPacketPayload {

    public static final Type<BrowseRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AssetShelf.MODID, "browse_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BrowseRequestPayload> STREAM_CODEC =
        StreamCodec.of(BrowseRequestPayload::write, BrowseRequestPayload::read);

    @Override
    public Type<BrowseRequestPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, BrowseRequestPayload p) {
        buf.writeResourceLocation(p.typeId);
        buf.writeVarInt(p.page);
        buf.writeVarInt(p.pageSize);
        buf.writeUtf(p.filter, 64);
        buf.writeVarInt(p.tagFilters.size());
        for (String tag : p.tagFilters) buf.writeUtf(tag, 32);
    }

    private static BrowseRequestPayload read(FriendlyByteBuf buf) {
        ResourceLocation typeId = buf.readResourceLocation();
        int page = buf.readVarInt();
        int pageSize = buf.readVarInt();
        String filter = buf.readUtf(64);
        int tagCount = Math.min(buf.readVarInt(), 16);
        List<String> tagFilters = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) tagFilters.add(buf.readUtf(32));
        return new BrowseRequestPayload(typeId, page, pageSize, filter, List.copyOf(tagFilters));
    }
}
