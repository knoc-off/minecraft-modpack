package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.PaletteCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record DecalCreatePayload(
    UUID id,
    long seqNo,
    long zOverride,
    BlockPos anchor,
    Direction normal,
    Direction up,
    int widthPx,
    int heightPx,
    float depth,
    byte flags,
    int[] pixels
) implements CustomPacketPayload {

    public static final Type<DecalCreatePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "decal_create"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DecalCreatePayload> STREAM_CODEC =
        StreamCodec.of(DecalCreatePayload::writeTo, DecalCreatePayload::readFrom);

    @Override
    public Type<DecalCreatePayload> type() {
        return TYPE;
    }

    static void writeTo(FriendlyByteBuf buf, DecalCreatePayload p) {
        buf.writeUUID(p.id);
        buf.writeLong(p.seqNo);
        buf.writeLong(p.zOverride);
        buf.writeBlockPos(p.anchor);
        buf.writeByte(p.normal.get3DDataValue());
        buf.writeByte(p.up.get3DDataValue());
        buf.writeVarInt(p.widthPx);
        buf.writeVarInt(p.heightPx);
        buf.writeFloat(p.depth);
        buf.writeByte(p.flags);

        PaletteCodec.writePixels(buf, p.pixels);
    }

    static DecalCreatePayload readFrom(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        long seq = buf.readLong();
        long zOvr = buf.readLong();
        BlockPos anchor = buf.readBlockPos();
        Direction normal = Direction.from3DDataValue(buf.readByte());
        Direction up = Direction.from3DDataValue(buf.readByte());
        int w = buf.readVarInt();
        int h = buf.readVarInt();
        float depth = buf.readFloat();
        byte flags = buf.readByte();

        int[] pixels = PaletteCodec.readPixels(buf);

        return new DecalCreatePayload(id, seq, zOvr, anchor, normal, up, w, h, depth, flags, pixels);
    }

    public static DecalCreatePayload fromDecal(Decal d) {
        return new DecalCreatePayload(
            d.id(), d.seqNo(), d.zOrder(), d.anchor(), d.normal(), d.up(),
            d.widthPx(), d.heightPx(), d.depth(), d.flags(), d.pixels()
        );
    }
}
