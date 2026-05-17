package dev.paintcraft.client.color;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.*;

public final class BlockColorExtractor {

    private BlockColorExtractor() {}

    /**
     * Extract all unique non-transparent colors from ALL textures used by a block's model.
     * Iterates over quads for every face direction + unculled quads to capture
     * multi-textured blocks (e.g., enchanting table: obsidian + diamonds + fabric).
     * Returns ARGB packed ints with full alpha.
     */
    public static int[] extractColors(Block block) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getBlockRenderer() == null) return new int[0];

        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        BlockState state = block.defaultBlockState();
        BakedModel model = shaper.getBlockModel(state);

        Set<Integer> colors = new LinkedHashSet<>();
        Set<String> seenSprites = new HashSet<>();
        RandomSource random = RandomSource.create(42);

        // Iterate all 6 face directions + null (unculled/general quads)
        for (Direction dir : Direction.values()) {
            extractFromQuads(model, state, dir, random, colors, seenSprites);
        }
        extractFromQuads(model, state, null, random, colors, seenSprites);

        // Fallback: if no quads produced colors, try particle icon
        if (colors.isEmpty()) {
            TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
            if (sprite != null) {
                readSpriteColors(sprite, colors);
            }
        }

        return colors.stream().mapToInt(c -> c).toArray();
    }

    private static void extractFromQuads(BakedModel model, BlockState state, Direction dir,
                                         RandomSource random, Set<Integer> colors,
                                         Set<String> seenSprites) {
        List<BakedQuad> quads = model.getQuads(state, dir, random);
        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.getSprite();
            String name = sprite.contents().name().toString();
            if (seenSprites.add(name)) {
                readSpriteColors(sprite, colors);
            }
        }
    }

    private static void readSpriteColors(TextureAtlasSprite sprite, Set<Integer> colors) {
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = sprite.getPixelRGBA(0, x, y);
                int argb = abgrToArgb(abgr);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha <= 2) continue;
                colors.add(argb | 0xFF000000);
            }
        }
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
