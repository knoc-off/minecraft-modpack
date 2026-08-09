package dev.paintcraft.item;

import dev.paintcraft.core.Decal;
import dev.paintcraft.core.DisplayTransform;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.core.PaletteCodec;
import dev.paintcraft.core.PixelGrid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Holds the pixel data and dimensions of a copied decal, for storage on a stamp item.
 *
 * <p>Pixels are held in <em>canonical</em> (viewer-relative) orientation — the same form the
 * editor and the Asset Shelf library use — not in a decal's stored orientation. Stored
 * orientation is frame-dependent: its handedness varies with the face normal (see
 * {@link FaceFrame#needsHFlip()}), so pixels copied off one face and written verbatim to another
 * come out mirrored. Normalising on copy and re-deriving on place makes a stamp independent of
 * both the face it came from and the face it lands on, which is why this record carries no
 * orientation of its own.
 */
public record StampData(
    int widthPx,
    int heightPx,
    int[] pixels
) {
    public int widthBlocks() { return widthPx / Decal.PX_PER_BLOCK; }
    public int heightBlocks() { return heightPx / Decal.PX_PER_BLOCK; }

    /** Captures a decal's pixels, normalised out of its stored frame into canonical orientation. */
    public static StampData fromDecal(Decal decal) {
        FaceFrame stored = new FaceFrame(decal.normal(), decal.up());
        PixelGrid canonical = DisplayTransform
            .between(stored, FaceFrame.canonical(decal.normal()))
            .toDisplay(PixelGrid.wrap(decal.widthPx(), decal.heightPx(), decal.pixels().clone()));
        return new StampData(canonical.width(), canonical.height(), canonical.data());
    }

    /**
     * Re-derives stored-orientation pixels for a destination frame. Inverse of the normalisation
     * in {@link #fromDecal}; a rotation may swap the axes, so callers must take dimensions from
     * the returned grid rather than from {@link #widthPx()}/{@link #heightPx()}.
     */
    public PixelGrid toStoredFor(FaceFrame destination) {
        return DisplayTransform
            .between(destination, FaceFrame.canonical(destination.normal()))
            .toStored(PixelGrid.wrap(widthPx, heightPx, pixels.clone()));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", Decal.FORMAT_VERSION);
        tag.putInt("w", widthPx);
        tag.putInt("h", heightPx);

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

        int[] pixels;
        if (tag.contains("palette", Tag.TAG_INT_ARRAY)) {
            int[] palette = tag.getIntArray("palette");
            byte[] encoded = tag.getByteArray("px");
            pixels = PaletteCodec.decode(encoded, palette);
        } else {
            pixels = tag.getIntArray("px_raw");
        }

        return new StampData(w, h, pixels);
    }
}
