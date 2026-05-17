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
    public byte flags() { return flags; }
    public String author() { return author; }
    public boolean isEmissive() { return (flags & FLAG_EMISSIVE) != 0; }

    public void setPixels(int[] pixels) {
        if (pixels.length != widthPx * heightPx)
            throw new IllegalArgumentException("Pixel array size mismatch: expected " + (widthPx * heightPx) + ", got " + pixels.length);
        this.pixels = pixels;
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

        // palette compression
        int[] palette = PaletteCodec.buildPalette(pixels);
        if (palette != null && palette.length <= 256) {
            tag.putIntArray("palette", palette);
            byte[] indexed = PaletteCodec.encode(pixels, palette);
            tag.putByteArray("px", indexed);
        } else {
            tag.putIntArray("px_raw", pixels);
        }
        return tag;
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
        return decal;
    }
}
