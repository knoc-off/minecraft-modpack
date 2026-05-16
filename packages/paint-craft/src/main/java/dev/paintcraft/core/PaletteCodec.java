package dev.paintcraft.core;

import it.unimi.dsi.fastutil.ints.Int2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import javax.annotation.Nullable;

public final class PaletteCodec {

    private PaletteCodec() {}

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
            out[i] = palette[Byte.toUnsignedInt(indexed[i])];
        }
        return out;
    }
}
