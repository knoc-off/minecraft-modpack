package dev.paintcraft.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

public class Decal {
    public static final int PX_PER_BLOCK = 16;
    public static final float MAX_DEPTH = 3.0f;
    public static final byte FLAG_EMISSIVE = 1;

    private final UUID id;
    private long seqNo;
    private long zOverride;
    private BlockPos anchor;
    private Direction normal;
    private Direction up;
    private int widthPx;
    private int heightPx;
    private float depth;
    private int[] pixels;
    private int[] normals;   // packed LabPBR: (ao << 24) | (nx << 16) | (ny << 8) | height
    private int[] specular;  // packed LabPBR: (emission << 24) | (smoothness << 16) | (f0 << 8) | porosity
    private byte flags;
    private String author;

    public Decal(UUID id, long seqNo, BlockPos anchor, Direction normal, Direction up,
                 int widthPx, int heightPx, float depth, int[] pixels, byte flags) {
        this.id = id;
        this.seqNo = seqNo;
        this.zOverride = seqNo;
        this.anchor = anchor;
        this.normal = normal;
        this.up = up;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.depth = Math.min(depth, MAX_DEPTH);
        this.pixels = pixels;
        this.normals = new int[widthPx * heightPx];
        this.specular = new int[widthPx * heightPx];
        java.util.Arrays.fill(this.normals, MaterialSample.DEFAULT.packNormal());
        java.util.Arrays.fill(this.specular, MaterialSample.DEFAULT.packSpecular());
        this.flags = flags;
        this.author = "";
    }

    public static Decal singleFace(UUID id, long seqNo, BlockPos anchor, Direction normal) {
        Direction up = normal.getAxis().isVertical() ? Direction.NORTH : Direction.UP;
        int[] pixels = new int[PX_PER_BLOCK * PX_PER_BLOCK];
        return new Decal(id, seqNo, anchor, normal, up, PX_PER_BLOCK, PX_PER_BLOCK, 1.0f, pixels, (byte) 0);
    }

    public static Decal multiBlock(UUID id, long seqNo, BlockPos anchor, Direction normal,
                                   Direction up, int widthBlocks, int heightBlocks, float depth) {
        int w = widthBlocks * PX_PER_BLOCK;
        int h = heightBlocks * PX_PER_BLOCK;
        return new Decal(id, seqNo, anchor, normal, up, w, h, depth, new int[w * h], (byte) 0);
    }

    public int widthBlocks() { return widthPx / PX_PER_BLOCK; }
    public int heightBlocks() { return heightPx / PX_PER_BLOCK; }
    public UUID id() { return id; }
    public long seqNo() { return seqNo; }
    public long zOrder() { return zOverride; }
    public BlockPos anchor() { return anchor; }
    public Direction normal() { return normal; }
    public Direction up() { return up; }
    public int widthPx() { return widthPx; }
    public int heightPx() { return heightPx; }
    public float depth() { return depth; }
    public int[] pixels() { return pixels; }
    public int[] normals() { return normals; }
    public int[] specular() { return specular; }
    public byte flags() { return flags; }
    public String author() { return author; }
    public boolean isEmissive() { return (flags & FLAG_EMISSIVE) != 0; }

    public void setPixels(int[] pixels) {
        if (pixels.length != widthPx * heightPx)
            throw new IllegalArgumentException("Pixel array size mismatch: expected " + (widthPx * heightPx) + ", got " + pixels.length);
        this.pixels = pixels;
    }

    public void setNormals(int[] normals) {
        if (normals.length != widthPx * heightPx)
            throw new IllegalArgumentException("Normal array size mismatch");
        this.normals = normals;
    }

    public void setSpecular(int[] specular) {
        if (specular.length != widthPx * heightPx)
            throw new IllegalArgumentException("Specular array size mismatch");
        this.specular = specular;
    }

    public void setMaterialAt(int x, int y, MaterialSample mat) {
        int idx = y * widthPx + x;
        normals[idx] = mat.packNormal();
        specular[idx] = mat.packSpecular();
    }

    public void setPixel(int x, int y, int rgba) {
        pixels[y * widthPx + x] = rgba;
    }

    public int getPixel(int x, int y) {
        return pixels[y * widthPx + x];
    }

    public void setFlags(byte flags) { this.flags = flags; }
    public void setAuthor(String author) { this.author = author; }
    public void setZOverride(long z) { this.zOverride = z; }

    public Direction right() {
        return up.getClockWise(normal.getAxis());
    }

    public FaceFrame frame() {
        return new FaceFrame(normal, up);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putLong("seq", seqNo);
        tag.putLong("zOvr", zOverride);
        tag.putLong("anchor", anchor.asLong());
        tag.putByte("normal", (byte) normal.get3DDataValue());
        tag.putByte("up", (byte) up.get3DDataValue());
        tag.putInt("w", widthPx);
        tag.putInt("h", heightPx);
        tag.putFloat("depth", depth);
        tag.putByte("flags", flags);
        tag.putString("author", author);

        // palette compression for albedo
        int[] palette = PaletteCodec.buildPalette(pixels);
        if (palette != null && palette.length <= 256) {
            tag.putIntArray("palette", palette);
            byte[] indexed = PaletteCodec.encode(pixels, palette);
            tag.putByteArray("px", indexed);
        } else {
            tag.putIntArray("px_raw", pixels);
        }

        // material data (palette-compressed; material arrays compress very well
        // since large regions share the same block's material properties)
        savePalettized(tag, "norm", normals);
        savePalettized(tag, "spec", specular);

        return tag;
    }

    private static void savePalettized(CompoundTag tag, String prefix, int[] data) {
        int[] palette = PaletteCodec.buildPalette(data);
        if (palette != null && palette.length <= 256) {
            tag.putIntArray(prefix + "_pal", palette);
            tag.putByteArray(prefix + "_idx", PaletteCodec.encode(data, palette));
        } else {
            tag.putIntArray(prefix + "_raw", data);
        }
    }

    private static int[] loadPalettized(CompoundTag tag, String prefix, int defaultSize) {
        if (tag.contains(prefix + "_pal", Tag.TAG_INT_ARRAY)) {
            int[] palette = tag.getIntArray(prefix + "_pal");
            byte[] indexed = tag.getByteArray(prefix + "_idx");
            return PaletteCodec.decode(indexed, palette);
        } else if (tag.contains(prefix + "_raw", Tag.TAG_INT_ARRAY)) {
            return tag.getIntArray(prefix + "_raw");
        }
        return null; // not present (old format)
    }

    public static Decal load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        long seq = tag.getLong("seq");
        BlockPos anchor = BlockPos.of(tag.getLong("anchor"));
        Direction normal = Direction.from3DDataValue(tag.getByte("normal"));
        Direction up = Direction.from3DDataValue(tag.getByte("up"));
        int w = tag.getInt("w");
        int h = tag.getInt("h");
        float depth = tag.getFloat("depth");
        byte flags = tag.getByte("flags");

        int[] pixels;
        if (tag.contains("palette", Tag.TAG_INT_ARRAY)) {
            int[] palette = tag.getIntArray("palette");
            byte[] indexed = tag.getByteArray("px");
            pixels = PaletteCodec.decode(indexed, palette);
        } else {
            pixels = tag.getIntArray("px_raw");
        }

        Decal decal = new Decal(id, seq, anchor, normal, up, w, h, depth, pixels, flags);
        decal.zOverride = tag.getLong("zOvr");
        decal.author = tag.getString("author");

        // Load material data (backward compatible: old decals get DEFAULT)
        int[] loadedNormals = loadPalettized(tag, "norm", w * h);
        int[] loadedSpecular = loadPalettized(tag, "spec", w * h);
        if (loadedNormals != null && loadedNormals.length == w * h) {
            decal.normals = loadedNormals;
        }
        if (loadedSpecular != null && loadedSpecular.length == w * h) {
            decal.specular = loadedSpecular;
        }

        return decal;
    }
}
