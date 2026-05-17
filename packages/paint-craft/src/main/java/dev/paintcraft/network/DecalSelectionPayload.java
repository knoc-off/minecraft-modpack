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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record DecalSelectionPayload(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(
        UUID id, BlockPos anchor, Direction normal, Direction up,
        int widthPx, int heightPx, float depth, int[] pixels
    ) {}

    public static final Type<DecalSelectionPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "decal_selection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DecalSelectionPayload> STREAM_CODEC =
        StreamCodec.of(DecalSelectionPayload::write, DecalSelectionPayload::read);

    @Override
    public Type<DecalSelectionPayload> type() {
        return TYPE;
    }

    public static DecalSelectionPayload from(List<Decal> decals) {
        List<Entry> entries = new ArrayList<>();
        for (Decal d : decals) {
            entries.add(new Entry(d.id(), d.anchor(), d.normal(), d.up(),
                d.widthPx(), d.heightPx(), d.depth(), d.pixels()));
        }
        return new DecalSelectionPayload(entries);
    }

    private static void write(FriendlyByteBuf buf, DecalSelectionPayload p) {
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeUUID(e.id);
            buf.writeBlockPos(e.anchor);
            buf.writeByte(e.normal.get3DDataValue());
            buf.writeByte(e.up.get3DDataValue());
            buf.writeVarInt(e.widthPx);
            buf.writeVarInt(e.heightPx);
            buf.writeFloat(e.depth);

            int[] palette = PaletteCodec.buildPalette(e.pixels);
            if (palette != null && palette.length <= 256) {
                buf.writeBoolean(true);
                buf.writeVarInt(palette.length);
                for (int c : palette) buf.writeInt(c);
                byte[] encoded = PaletteCodec.encode(e.pixels, palette);
                buf.writeByteArray(encoded);
            } else {
                buf.writeBoolean(false);
                buf.writeVarInt(e.pixels.length);
                for (int px : e.pixels) buf.writeInt(px);
            }
        }
    }

    private static DecalSelectionPayload read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID();
            BlockPos anchor = buf.readBlockPos();
            Direction normal = Direction.from3DDataValue(buf.readByte());
            Direction up = Direction.from3DDataValue(buf.readByte());
            int w = buf.readVarInt();
            int h = buf.readVarInt();
            float depth = buf.readFloat();

            int[] pixels;
            if (buf.readBoolean()) {
                int paletteLen = buf.readVarInt();
                int[] palette = new int[paletteLen];
                for (int j = 0; j < paletteLen; j++) palette[j] = buf.readInt();
                byte[] encoded = buf.readByteArray();
                pixels = PaletteCodec.decode(encoded, palette);
            } else {
                int len = buf.readVarInt();
                pixels = new int[len];
                for (int j = 0; j < len; j++) pixels[j] = buf.readInt();
            }

            entries.add(new Entry(id, anchor, normal, up, w, h, depth, pixels));
        }
        return new DecalSelectionPayload(entries);
    }
}
