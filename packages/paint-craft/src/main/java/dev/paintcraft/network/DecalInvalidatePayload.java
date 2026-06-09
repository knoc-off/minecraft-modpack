package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tells clients to re-resolve specific decals because blocks changed
 * in their projection volume. Carries only UUIDs (16 bytes each)
 * instead of full decal data (~1KB+ each).
 *
 * Also carries the set of changed BlockPos so the client can do
 * targeted shape-change detection instead of full re-resolve.
 */
public record DecalInvalidatePayload(
    List<UUID> decalIds,
    List<BlockPos> changedPositions
) implements CustomPacketPayload {

    public static final Type<DecalInvalidatePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "decal_invalidate"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DecalInvalidatePayload> STREAM_CODEC =
        StreamCodec.of(DecalInvalidatePayload::write, DecalInvalidatePayload::read);

    @Override
    public Type<DecalInvalidatePayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, DecalInvalidatePayload p) {
        buf.writeVarInt(p.decalIds.size());
        for (UUID id : p.decalIds) buf.writeUUID(id);
        buf.writeVarInt(p.changedPositions.size());
        for (BlockPos pos : p.changedPositions) buf.writeBlockPos(pos);
    }

    private static DecalInvalidatePayload read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buf.readUUID());
        int posCount = buf.readVarInt();
        List<BlockPos> positions = new ArrayList<>(posCount);
        for (int i = 0; i < posCount; i++) positions.add(buf.readBlockPos());
        return new DecalInvalidatePayload(ids, positions);
    }
}
