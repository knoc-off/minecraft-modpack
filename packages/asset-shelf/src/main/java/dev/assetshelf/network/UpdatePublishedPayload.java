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
 * Client → Server: update metadata of a published asset (name, description, tags).
 */
public record UpdatePublishedPayload(
    UUID assetId,
    String name,
    String description,
    List<String> tags
) implements CustomPacketPayload {

    public static final Type<UpdatePublishedPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AssetShelf.MODID, "update_published"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdatePublishedPayload> STREAM_CODEC =
        StreamCodec.of(UpdatePublishedPayload::write, UpdatePublishedPayload::read);

    @Override
    public Type<UpdatePublishedPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, UpdatePublishedPayload p) {
        buf.writeUUID(p.assetId);
        buf.writeUtf(p.name, 128);
        buf.writeUtf(p.description, 512);
        buf.writeVarInt(p.tags.size());
        for (String tag : p.tags) buf.writeUtf(tag, 32);
    }

    private static UpdatePublishedPayload read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf(128);
        String desc = buf.readUtf(512);
        int tagCount = Math.min(buf.readVarInt(), 16);
        List<String> tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) tags.add(buf.readUtf(32));
        return new UpdatePublishedPayload(id, name, desc, List.copyOf(tags));
    }
}
