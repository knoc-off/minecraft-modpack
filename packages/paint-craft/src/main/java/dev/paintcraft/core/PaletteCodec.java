package dev.paintcraft.core;

import it.unimi.dsi.fastutil.ints.Int2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;

public final class PaletteCodec {

    private PaletteCodec() {}

    /** Max pixels per decal: 512×512 = 262144 (16 blocks × 32 px/block per axis). */
    public static final int MAX_PIXELS = 512 * 512;

    /** Write a pixel array to a network buffer with palette compression. */
    public static void writePixels(FriendlyByteBuf buf, int[] pixels) {
        int[] palette = buildPalette(pixels);
        if (palette != null && palette.length <= 256) {
            buf.writeBoolean(true);
            buf.writeVarInt(palette.length);
            for (int c : palette) buf.writeInt(c);
            byte[] encoded = encode(pixels, palette);
            buf.writeByteArray(encoded);
        } else {
            buf.writeBoolean(false);
            buf.writeVarInt(pixels.length);
            for (int px : pixels) buf.writeInt(px);
        }
    }

    /** Read a pixel array from a network buffer (palette-compressed or raw). */
    public static int[] readPixels(FriendlyByteBuf buf) {
        if (buf.readBoolean()) {
            // Palette path
            int paletteLen = buf.readVarInt();
            if (paletteLen < 1 || paletteLen > 256) {
                throw new io.netty.handler.codec.DecoderException(
                    "Invalid palette length: " + paletteLen);
            }
            int[] palette = new int[paletteLen];
            for (int i = 0; i < paletteLen; i++) palette[i] = buf.readInt();
            byte[] encoded = buf.readByteArray(MAX_PIXELS);
            return decode(encoded, palette);
        } else {
            // Raw path
            int len = buf.readVarInt();
            if (len < 0 || len > MAX_PIXELS) {
                throw new io.netty.handler.codec.DecoderException(
                    "Invalid pixel count: " + len);
            }
            int[] pixels = new int[len];
            for (int i = 0; i < len; i++) pixels[i] = buf.readInt();
            return pixels;
        }
    }

    @Nullable
    public static int[] buildPalette(int[] pixels) {
        IntSet seen = new IntLinkedOpenHashSet();
        for (int px : pixels) {
            seen.add(px);
            if (seen.size() > 256) return null;
        }
        return seen.toIntArray();
    }

    public static byte[] encode(int[] pixels, int[] palette) {
        Int2ByteLinkedOpenHashMap reverse = new Int2ByteLinkedOpenHashMap(palette.length);
        for (int i = 0; i < palette.length; i++) {
            reverse.put(palette[i], (byte) i);
        }
        byte[] out = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            out[i] = reverse.get(pixels[i]);
        }
        return out;
    }

    public static int[] decode(byte[] indexed, int[] palette) {
        int[] out = new int[indexed.length];
        for (int i = 0; i < indexed.length; i++) {
            int idx = Byte.toUnsignedInt(indexed[i]);
            out[i] = idx < palette.length ? palette[idx] : 0;
        }
        return out;
    }
}
