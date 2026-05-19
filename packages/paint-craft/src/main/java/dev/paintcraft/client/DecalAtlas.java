package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.core.ColorFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs multiple decal textures into a single GPU texture using shelf-based bin packing.
 * Reduces draw calls from N (one per decal) to 1 per tier by sharing a texture.
 *
 * Thread safety: all methods must be called on the render thread.
 */
public final class DecalAtlas {

    public static final int ATLAS_SIZE = 2048;
    private static final int PADDING = 1;

    private static final List<DecalAtlas> atlases = new ArrayList<>();

    private final NativeImage image;
    private final DynamicTexture texture;
    private final ResourceLocation location;
    private final List<Shelf> shelves = new ArrayList<>();
    private int nextShelfY = 0;
    private boolean dirty = false;

    private DecalAtlas(int index) {
        this.image = new NativeImage(ATLAS_SIZE, ATLAS_SIZE, true);
        this.texture = new DynamicTexture(image);
        this.location = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_atlas_" + index, texture);
    }

    // --- Static API ---

    public static Slot allocate(int width, int height) {
        for (DecalAtlas atlas : atlases) {
            Slot slot = atlas.tryAllocate(width, height);
            if (slot != null) return slot;
        }
        DecalAtlas fresh = new DecalAtlas(atlases.size());
        atlases.add(fresh);
        Slot slot = fresh.tryAllocate(width, height);
        if (slot == null) {
            throw new IllegalArgumentException(
                "Decal too large for atlas: " + width + "x" + height);
        }
        return slot;
    }

    public static void uploadAll() {
        for (DecalAtlas atlas : atlases) {
            if (atlas.dirty) {
                atlas.texture.upload();
                atlas.dirty = false;
            }
        }
    }

    public static void destroyAll() {
        for (DecalAtlas atlas : atlases) {
            Minecraft.getInstance().getTextureManager().release(atlas.location);
            atlas.texture.close();
        }
        atlases.clear();
    }

    // --- Instance methods ---

    private Slot tryAllocate(int w, int h) {
        int pw = w + PADDING;
        int ph = h + PADDING;

        for (Shelf shelf : shelves) {
            if (shelf.height >= ph && shelf.remainingWidth() >= pw) {
                Slot slot = new Slot(this, shelf.cursorX, shelf.y, w, h);
                shelf.cursorX += pw;
                return slot;
            }
        }

        if (nextShelfY + ph <= ATLAS_SIZE) {
            Shelf shelf = new Shelf(nextShelfY, ph);
            shelves.add(shelf);
            nextShelfY += ph;
            Slot slot = new Slot(this, shelf.cursorX, shelf.y, w, h);
            shelf.cursorX += pw;
            return slot;
        }

        return null;
    }

    void writePixels(Slot slot, int[] argbPixels, int srcWidth) {
        for (int y = 0; y < slot.height; y++) {
            for (int x = 0; x < slot.width; x++) {
                int c = argbPixels[y * srcWidth + x];
                image.setPixelRGBA(slot.x + x, slot.y + y, ColorFormat.argbToAbgr(c));
            }
        }
        dirty = true;
    }

    void clearSlot(Slot slot) {
        for (int y = 0; y < slot.height; y++) {
            for (int x = 0; x < slot.width; x++) {
                image.setPixelRGBA(slot.x + x, slot.y + y, 0);
            }
        }
        dirty = true;
    }

    // --- Inner types ---

    private static class Shelf {
        final int y;
        final int height;
        int cursorX = 0;

        Shelf(int y, int height) {
            this.y = y;
            this.height = height;
        }

        int remainingWidth() {
            return ATLAS_SIZE - cursorX;
        }
    }

    public static final class Slot {
        private final DecalAtlas atlas;
        final int x, y, width, height;

        Slot(DecalAtlas atlas, int x, int y, int width, int height) {
            this.atlas = atlas;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /** Remap a decal-local U in [0,1] to atlas-space U. */
        public float atlasU(float localU) {
            return (x + localU * width) / ATLAS_SIZE;
        }

        /** Remap a decal-local V in [0,1] to atlas-space V. */
        public float atlasV(float localV) {
            return (y + localV * height) / ATLAS_SIZE;
        }

        public ResourceLocation atlasLocation() {
            return atlas.location;
        }

        public void write(int[] argbPixels, int srcWidth) {
            atlas.writePixels(this, argbPixels, srcWidth);
        }

        public void free() {
            atlas.clearSlot(this);
        }
    }
}
