package dev.assetshelf.core;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Metadata for a stored asset (both local and server-side).
 */
public record AssetMeta(
    ResourceLocation typeId,
    UUID id,
    String name,
    String description,
    UUID authorUUID,
    String authorName,
    int widthPx,
    int heightPx,
    long createdAt,
    List<String> tags
) {
    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(typeId);
        buf.writeUUID(id);
        buf.writeUtf(name, 128);
        buf.writeUtf(description, 512);
        buf.writeUUID(authorUUID);
        buf.writeUtf(authorName, 64);
        buf.writeVarInt(widthPx);
        buf.writeVarInt(heightPx);
        buf.writeLong(createdAt);
        buf.writeVarInt(tags.size());
        for (String tag : tags) buf.writeUtf(tag, 32);
    }

    public static AssetMeta read(FriendlyByteBuf buf) {
        ResourceLocation typeId = buf.readResourceLocation();
        UUID id = buf.readUUID();
        String name = buf.readUtf(128);
        String description = buf.readUtf(512);
        UUID authorUUID = buf.readUUID();
        String authorName = buf.readUtf(64);
        int widthPx = buf.readVarInt();
        int heightPx = buf.readVarInt();
        long createdAt = buf.readLong();
        int tagCount = Math.min(buf.readVarInt(), 16);
        List<String> tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) tags.add(buf.readUtf(32));
        return new AssetMeta(typeId, id, name, description, authorUUID, authorName,
            widthPx, heightPx, createdAt, List.copyOf(tags));
    }
}
