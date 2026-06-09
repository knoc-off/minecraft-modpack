package dev.structurestash.network;

import dev.structurestash.StructureStash;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: sends captured structure data for local library save.
 */
public record CapturedStructurePayload(
    byte[] data,
    String name,
    int sizeX,
    int sizeZ
) implements CustomPacketPayload {

    public static final Type<CapturedStructurePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "captured_structure"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CapturedStructurePayload> STREAM_CODEC =
        StreamCodec.of(CapturedStructurePayload::write, CapturedStructurePayload::read);

    @Override
    public Type<CapturedStructurePayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, CapturedStructurePayload p) {
        buf.writeByteArray(p.data);
        buf.writeUtf(p.name, 128);
        buf.writeVarInt(p.sizeX);
        buf.writeVarInt(p.sizeZ);
    }

    private static CapturedStructurePayload read(FriendlyByteBuf buf) {
        return new CapturedStructurePayload(
            buf.readByteArray(),
            buf.readUtf(128),
            buf.readVarInt(),
            buf.readVarInt()
        );
    }
}
