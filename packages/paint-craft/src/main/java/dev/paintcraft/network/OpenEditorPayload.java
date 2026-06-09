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

public record OpenEditorPayload(
    UUID id,
    BlockPos anchor,
    Direction normal,
    Direction up,
    int widthPx,
    int heightPx,
    float depth,
    int[] pixels
) implements CustomPacketPayload {

    public static final Type<OpenEditorPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "open_editor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenEditorPayload> STREAM_CODEC =
        StreamCodec.of(OpenEditorPayload::write, OpenEditorPayload::read);

    @Override
    public Type<OpenEditorPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, OpenEditorPayload p) {
        buf.writeUUID(p.id);
        buf.writeBlockPos(p.anchor);
        buf.writeByte(p.normal.get3DDataValue());
        buf.writeByte(p.up.get3DDataValue());
        buf.writeVarInt(p.widthPx);
        buf.writeVarInt(p.heightPx);
        buf.writeFloat(p.depth);

        PaletteCodec.writePixels(buf, p.pixels);
    }

    private static OpenEditorPayload read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        BlockPos anchor = buf.readBlockPos();
        Direction normal = Direction.from3DDataValue(buf.readByte());
        Direction up = Direction.from3DDataValue(buf.readByte());
        int w = buf.readVarInt();
        int h = buf.readVarInt();
        float depth = buf.readFloat();

        int[] pixels = PaletteCodec.readPixels(buf);

        return new OpenEditorPayload(id, anchor, normal, up, w, h, depth, pixels);
    }

    public static OpenEditorPayload fromDecal(Decal d) {
        return new OpenEditorPayload(
            d.id(), d.anchor(), d.normal(), d.up(),
            d.widthPx(), d.heightPx(), d.depth(), d.pixels()
        );
    }

    public static OpenEditorPayload blank(UUID id, BlockPos anchor, Direction normal, Direction up,
                                           int widthPx, int heightPx, float depth) {
        return new OpenEditorPayload(id, anchor, normal, up, widthPx, heightPx, depth, new int[widthPx * heightPx]);
    }
}
