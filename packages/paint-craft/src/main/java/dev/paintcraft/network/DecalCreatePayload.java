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
        StreamCodec.of(DecalCreatePayload::write, DecalCreatePayload::read);

    @Override
    public Type<DecalCreatePayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, DecalCreatePayload p) {
        buf.writeUUID(p.id);
        buf.writeLong(p.seqNo);
        buf.writeBlockPos(p.anchor);
        buf.writeByte(p.normal.get3DDataValue());
        buf.writeByte(p.up.get3DDataValue());
        buf.writeVarInt(p.widthPx);
        buf.writeVarInt(p.heightPx);
        buf.writeFloat(p.depth);
        buf.writeByte(p.flags);

        int[] palette = PaletteCodec.buildPalette(p.pixels);
        if (palette != null && palette.length <= 256) {
            buf.writeBoolean(true);
            buf.writeVarInt(palette.length);
            for (int c : palette) buf.writeInt(c);
            byte[] encoded = PaletteCodec.encode(p.pixels, palette);
            buf.writeByteArray(encoded);
        } else {
            buf.writeBoolean(false);
            buf.writeVarInt(p.pixels.length);
            for (int px : p.pixels) buf.writeInt(px);
        }
    }

    private static DecalCreatePayload read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        long seq = buf.readLong();
        BlockPos anchor = buf.readBlockPos();
        Direction normal = Direction.from3DDataValue(buf.readByte());
        Direction up = Direction.from3DDataValue(buf.readByte());
        int w = buf.readVarInt();
        int h = buf.readVarInt();
        float depth = buf.readFloat();
        byte flags = buf.readByte();

        int[] pixels;
        boolean hasPalette = buf.readBoolean();
        if (hasPalette) {
            int paletteLen = buf.readVarInt();
            int[] palette = new int[paletteLen];
            for (int i = 0; i < paletteLen; i++) palette[i] = buf.readInt();
            byte[] encoded = buf.readByteArray();
            pixels = PaletteCodec.decode(encoded, palette);
        } else {
            int len = buf.readVarInt();
            pixels = new int[len];
            for (int i = 0; i < len; i++) pixels[i] = buf.readInt();
        }

        return new DecalCreatePayload(id, seq, anchor, normal, up, w, h, depth, flags, pixels);
    }

    public static DecalCreatePayload fromDecal(Decal d) {
        return new DecalCreatePayload(
            d.id(), d.seqNo(), d.anchor(), d.normal(), d.up(),
            d.widthPx(), d.heightPx(), d.depth(), d.flags(), d.pixels()
        );
    }
}
