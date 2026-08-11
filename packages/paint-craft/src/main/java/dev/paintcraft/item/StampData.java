package dev.paintcraft.item;

import dev.paintcraft.core.Decal;
import dev.paintcraft.core.DisplayTransform;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.core.PaletteCodec;
import dev.paintcraft.core.PixelGrid;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Holds the pixel data and dimensions of a copied decal, for storage on a stamp item.
 *
 * <p>Pixels are held in <em>display</em> orientation — exactly what the copying player saw, which
 * is the same grid the editor canvas and the Asset Shelf library hold. A decal's <em>stored</em>
 * orientation is frame-dependent (its handedness varies with the face normal, see
 * {@link FaceFrame#needsHFlip()}), so pixels copied off one face and written verbatim to another
 * come out mirrored.
 *
 * <p>The reference frame for that normalisation is {@link FaceFrame#displayReference()}, never
 * {@link FaceFrame#cellFrame} — the latter pins vertical faces to world NORTH, which would cancel
 * out the placing player's view rotation and leave floor and ceiling stamps world-locked.
 * Normalising on copy and re-deriving on place makes a stamp independent of both the face it came
 * from and the face it lands on, which is why this record carries no orientation of its own.
 */
public record StampData(
    int widthPx,
    int heightPx,
    int[] pixels
) {
    /**
     * Versioned independently of {@link Decal#FORMAT_VERSION}: this is a different format with a
     * different orientation contract, and the two need to be invalidated independently.
     * v3 = display orientation (v2 and earlier were world-NORTH-locked on vertical faces).
     */
    public static final int FORMAT_VERSION = 3;

    public int widthBlocks() { return widthPx / Decal.PX_PER_BLOCK; }
    public int heightBlocks() { return heightPx / Decal.PX_PER_BLOCK; }

    /**
     * Captures what a player facing {@code viewerFacing} currently sees of this decal — the same
     * grid {@code ClientBrushHandler.openExistingEditor} would put on the canvas.
     */
    public static StampData fromDecal(Decal decal, Direction viewerFacing) {
        FaceFrame stored = decal.frame();
        FaceFrame display = FaceFrame.displayFrameFor(decal.normal(), viewerFacing);
        PixelGrid grid = DisplayTransform
            .between(stored, display)
            .toDisplay(PixelGrid.wrap(decal.widthPx(), decal.heightPx(), decal.pixels().clone()));
        return new StampData(grid.width(), grid.height(), grid.data());
    }

    /**
     * Re-derives stored-orientation pixels for a destination frame, so the image reads to the
     * placing player exactly as it read to the copying one. Inverse of the normalisation in
     * {@link #fromDecal}.
     *
     * <p>For any frame produced by {@link FaceFrame#displayFrameFor} — which is every frame a
     * decal is created in — this reduces to a flip with no rotation, so the returned dimensions
     * match {@link #widthPx()}/{@link #heightPx()}. A rolled wall frame would rotate and swap the
     * axes, so callers should still take dimensions from the returned grid.
     */
    public PixelGrid toStoredFor(FaceFrame destination) {
        return DisplayTransform
            .between(destination, destination.displayReference())
            .toStored(PixelGrid.wrap(widthPx, heightPx, pixels.clone()));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", FORMAT_VERSION);
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
        // Pre-v3 stamps and library assets hold world-NORTH-locked pixels on vertical faces and
        // would place rotated; reject them rather than silently misorienting the image.
        if (tag.getInt("v") < FORMAT_VERSION) return null;
        int w = tag.getInt("w");
        int h = tag.getInt("h");

        int[] pixels;
        if (tag.contains("palette", Tag.TAG_INT_ARRAY)) {
            int[] palette = tag.getIntArray("palette");
            byte[] indexed = tag.getByteArray("px");
            pixels = PaletteCodec.decode(indexed, palette);
        } else {
            pixels = tag.getIntArray("px_raw");
        }

        return new StampData(w, h, pixels);
    }
}
