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

public record DecalSelectionPayload(List<Entry> entries, boolean eraseMode) implements CustomPacketPayload {

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
        return new DecalSelectionPayload(buildEntries(decals), false);
    }

    public static DecalSelectionPayload forErase(List<Decal> decals) {
        return new DecalSelectionPayload(buildEntries(decals), true);
    }

    private static List<Entry> buildEntries(List<Decal> decals) {
        List<Entry> entries = new ArrayList<>();
        for (Decal d : decals) {
            entries.add(new Entry(d.id(), d.anchor(), d.normal(), d.up(),
                d.widthPx(), d.heightPx(), d.depth(), d.pixels()));
        }
        return entries;
    }

    private static void write(FriendlyByteBuf buf, DecalSelectionPayload p) {
        buf.writeBoolean(p.eraseMode);
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeUUID(e.id);
            buf.writeBlockPos(e.anchor);
            buf.writeByte(e.normal.get3DDataValue());
            buf.writeByte(e.up.get3DDataValue());
            buf.writeVarInt(e.widthPx);
            buf.writeVarInt(e.heightPx);
            buf.writeFloat(e.depth);

            PaletteCodec.writePixels(buf, e.pixels);
        }
    }

    private static DecalSelectionPayload read(FriendlyByteBuf buf) {
        boolean eraseMode = buf.readBoolean();
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

            int[] pixels = PaletteCodec.readPixels(buf);

            entries.add(new Entry(id, anchor, normal, up, w, h, depth, pixels));
        }
        return new DecalSelectionPayload(entries, eraseMode);
    }
}
