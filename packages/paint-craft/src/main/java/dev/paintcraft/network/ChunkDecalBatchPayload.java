package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Batches multiple DecalCreatePayloads into a single packet.
 * Sent when a player enters tracking range of a chunk, replacing
 * one-packet-per-decal with a single batch.
 */
public record ChunkDecalBatchPayload(
    List<DecalCreatePayload> decals
) implements CustomPacketPayload {

    public static final Type<ChunkDecalBatchPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "chunk_decal_batch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkDecalBatchPayload> STREAM_CODEC =
        StreamCodec.of(ChunkDecalBatchPayload::write, ChunkDecalBatchPayload::read);

    @Override
    public Type<ChunkDecalBatchPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, ChunkDecalBatchPayload p) {
        buf.writeVarInt(p.decals.size());
        for (DecalCreatePayload decal : p.decals) {
            DecalCreatePayload.writeTo(buf, decal);
        }
    }

    private static ChunkDecalBatchPayload read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<DecalCreatePayload> decals = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            decals.add(DecalCreatePayload.readFrom(buf));
        }
        return new ChunkDecalBatchPayload(decals);
    }
}
