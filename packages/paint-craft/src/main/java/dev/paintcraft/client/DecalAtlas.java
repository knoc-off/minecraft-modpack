package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.core.ColorFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs multiple decal textures into shared GPU textures using shelf-based bin packing.
 * Maintains three parallel atlases: albedo, normals (LabPBR _n), specular (LabPBR _s).
 * All three share the same slot layout so UVs are identical across atlases.
 *
 * The albedo atlas uses {@link PBRDynamicTexture} so Iris/NeOculus can discover
 * the normal and specular companions via the registered PBR texture loader.
 */
public final class DecalAtlas {

    public static final int ATLAS_SIZE = 2048;
    private static final int PADDING = 1;

    private static final List<DecalAtlas> atlases = new ArrayList<>();

    private final NativeImage albedoImage;
    private final NativeImage normalImage;
    private final NativeImage specularImage;
    private final PBRDynamicTexture albedoTexture;
    private final DynamicTexture normalTexture;
    private final DynamicTexture specularTexture;
    private final ResourceLocation albedoLocation;
    private final List<Shelf> shelves = new ArrayList<>();
    private int nextShelfY = 0;
    private boolean albedoDirty = false;
    private boolean normalDirty = false;
    private boolean specularDirty = false;

    private DecalAtlas(int index) {
        this.albedoImage = new NativeImage(ATLAS_SIZE, ATLAS_SIZE, true);
        this.normalImage = new NativeImage(ATLAS_SIZE, ATLAS_SIZE, true);
        this.specularImage = new NativeImage(ATLAS_SIZE, ATLAS_SIZE, true);

        // Fill normal atlas with default flat normal (128, 128, 255, 0) in ABGR
        // LabPBR default: normalX=128, normalY=128, ao=255, height=0
        // Packed as ARGB: (255 << 24) | (128 << 16) | (128 << 8) | 0 = 0xFF808000
        // In ABGR for NativeImage: (0 << 24) | (128 << 16) | (128 << 8) | 255 = 0x008080FF
        fillImage(normalImage, ColorFormat.argbToAbgr(0xFF808000));

        this.normalTexture = new DynamicTexture(normalImage);
        this.specularTexture = new DynamicTexture(specularImage);
        this.albedoTexture = new PBRDynamicTexture(albedoImage);
        this.albedoTexture.setNormalCompanion(normalTexture);
        this.albedoTexture.setSpecularCompanion(specularTexture);

        var texManager = Minecraft.getInstance().getTextureManager();
        this.albedoLocation = texManager.register("paintcraft_atlas_" + index, albedoTexture);
        // Companion textures don't need their own locations; Iris accesses them
        // through the PBRDynamicTexture's getters, not by ResourceLocation lookup.
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
            if (atlas.albedoDirty) { atlas.albedoTexture.upload(); atlas.albedoDirty = false; }
            if (atlas.normalDirty) { atlas.normalTexture.upload(); atlas.normalDirty = false; }
            if (atlas.specularDirty) { atlas.specularTexture.upload(); atlas.specularDirty = false; }
        }
    }

    public static void destroyAll() {
        for (DecalAtlas atlas : atlases) {
            Minecraft.getInstance().getTextureManager().release(atlas.albedoLocation);
            atlas.albedoTexture.close(); // also closes normal + specular companions
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

    void writeAlbedo(Slot slot, int[] argbPixels, int srcWidth) {
        writeToImage(albedoImage, slot, argbPixels, srcWidth);
        albedoDirty = true;
    }

    void writeNormals(Slot slot, int[] packedNormals, int srcWidth) {
        writeToImage(normalImage, slot, packedNormals, srcWidth);
        normalDirty = true;
    }

    void writeSpecular(Slot slot, int[] packedSpecular, int srcWidth) {
        writeToImage(specularImage, slot, packedSpecular, srcWidth);
        specularDirty = true;
    }

    void clearSlot(Slot slot) {
        clearRegion(albedoImage, slot);
        clearRegion(normalImage, slot);
        clearRegion(specularImage, slot);
        albedoDirty = true;
        normalDirty = true;
        specularDirty = true;
    }

    private static void writeToImage(NativeImage img, Slot slot, int[] argb, int srcWidth) {
        for (int y = 0; y < slot.height; y++) {
            for (int x = 0; x < slot.width; x++) {
                int c = argb[y * srcWidth + x];
                img.setPixelRGBA(slot.x + x, slot.y + y, ColorFormat.argbToAbgr(c));
            }
        }
    }

    private static void clearRegion(NativeImage img, Slot slot) {
        for (int y = 0; y < slot.height; y++) {
            for (int x = 0; x < slot.width; x++) {
                img.setPixelRGBA(slot.x + x, slot.y + y, 0);
            }
        }
    }

    private static void fillImage(NativeImage img, int abgr) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setPixelRGBA(x, y, abgr);
            }
        }
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

        public float atlasU(float localU) {
            return (x + localU * width) / ATLAS_SIZE;
        }

        public float atlasV(float localV) {
            return (y + localV * height) / ATLAS_SIZE;
        }

        public ResourceLocation atlasLocation() {
            return atlas.albedoLocation;
        }

        public void writeAlbedo(int[] argbPixels, int srcWidth) {
            atlas.writeAlbedo(this, argbPixels, srcWidth);
        }

        public void writeNormals(int[] packedNormals, int srcWidth) {
            atlas.writeNormals(this, packedNormals, srcWidth);
        }

        public void writeSpecular(int[] packedSpecular, int srcWidth) {
            atlas.writeSpecular(this, packedSpecular, srcWidth);
        }

        public void free() {
            atlas.clearSlot(this);
        }
    }
}
