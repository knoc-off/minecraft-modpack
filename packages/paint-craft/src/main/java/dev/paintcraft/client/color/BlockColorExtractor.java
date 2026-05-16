package dev.paintcraft.client.color;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.*;

public final class BlockColorExtractor {

    private static final int MAX_COLORS_PER_BLOCK = 10;
    private static final int QUANTIZE_STEP = 16;

    private BlockColorExtractor() {}

    /**
     * Extract dominant colors from a block's texture, sorted by luminance (light to dark).
     * Returns ARGB packed ints.
     */
    public static int[] extractColors(Block block) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getBlockRenderer() == null) return new int[0];

        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        BakedModel model = shaper.getBlockModel(block.defaultBlockState());
        TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);

        if (sprite == null) return new int[0];

        int w = sprite.contents().width();
        int h = sprite.contents().height();
        Map<Integer, Integer> colorCounts = new HashMap<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = sprite.getPixelRGBA(0, x, y);
                int argb = abgrToArgb(abgr);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha <= 2) continue;

                int quantized = quantize(argb);
                colorCounts.merge(quantized, 1, Integer::sum);
            }
        }

        if (colorCounts.isEmpty()) return new int[0];

        // Sort by frequency (most common first), take top N, then sort by luminance
        return colorCounts.entrySet().stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
            .limit(MAX_COLORS_PER_BLOCK)
            .map(Map.Entry::getKey)
            .sorted(Comparator.comparingDouble(BlockColorExtractor::luminance).reversed())
            .mapToInt(c -> c | 0xFF000000) // ensure full alpha
            .toArray();
    }

    /**
     * Check if a block has a usable texture (not air, not missing texture).
     */
    public static boolean hasValidTexture(Block block) {
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getBlockRenderer() == null) return false;

        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        BakedModel model = shaper.getBlockModel(block.defaultBlockState());
        TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
        if (sprite == null) return false;

        // Check if it's the missing texture
        String name = sprite.contents().name().toString();
        return !name.contains("missingno") && !name.contains("missing");
    }

    private static int abgrToArgb(int abgr) {
        int a = (abgr >> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int quantize(int argb) {
        int r = ((((argb >> 16) & 0xFF) / QUANTIZE_STEP) * QUANTIZE_STEP);
        int g = ((((argb >> 8) & 0xFF) / QUANTIZE_STEP) * QUANTIZE_STEP);
        int b = (((argb & 0xFF) / QUANTIZE_STEP) * QUANTIZE_STEP);
        return (r << 16) | (g << 8) | b;
    }

    private static double luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }
}
