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

    private BlockColorExtractor() {}

    /**
     * Extract all unique non-transparent colors from a block's particle texture.
     * Returns ARGB packed ints with full alpha.
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
        Set<Integer> colors = new LinkedHashSet<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = sprite.getPixelRGBA(0, x, y);
                int argb = abgrToArgb(abgr);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha <= 2) continue;
                colors.add(argb | 0xFF000000);
            }
        }

        return colors.stream().mapToInt(c -> c).toArray();
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
}
