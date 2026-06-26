package dev.paintcraft.item;

import dev.paintcraft.core.Decal;
import dev.paintcraft.core.PaletteCodec;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Holds the pixel data and dimensions of a copied decal, for storage on a stamp item.
 */
public record StampData(
    int widthPx,
    int heightPx,
    Direction up,
    int[] pixels
) {
    public int widthBlocks() { return widthPx / Decal.PX_PER_BLOCK; }
    public int heightBlocks() { return heightPx / Decal.PX_PER_BLOCK; }

    public static StampData fromDecal(Decal decal) {
        return new StampData(decal.widthPx(), decal.heightPx(), decal.up(), decal.pixels().clone());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", Decal.FORMAT_VERSION);
        tag.putInt("w", widthPx);
        tag.putInt("h", heightPx);
        tag.putByte("up", (byte) up.get3DDataValue());

        int[] palette = PaletteCodec.buildPalette(pixels);
        if (palette != null && palette.length <= 256) {
            tag.putIntArray("palette", palette);
            tag.putByteArray("px", PaletteCodec.encode(pixels, palette));
        } else {
            tag.putIntArray("px_raw", pixels);
        }
        return tag;
    }

    public static StampData load(CompoundTag tag) {
        // Legacy stamps (pre-32px) are not migrated — skip so stale 16px data can't
        // produce a zero-size stamp.
        if (tag.getInt("v") < Decal.FORMAT_VERSION) return null;
        int w = tag.getInt("w");
        int h = tag.getInt("h");
        Direction up = Direction.from3DDataValue(tag.getByte("up"));

        int[] pixels;
        if (tag.contains("palette", Tag.TAG_INT_ARRAY)) {
            int[] palette = tag.getIntArray("palette");
            byte[] encoded = tag.getByteArray("px");
            pixels = PaletteCodec.decode(encoded, palette);
        } else {
            pixels = tag.getIntArray("px_raw");
        }

        return new StampData(w, h, up, pixels);
    }
}
