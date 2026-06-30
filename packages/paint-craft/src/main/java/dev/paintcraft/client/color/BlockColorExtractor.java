package dev.paintcraft.client.color;

import dev.paintcraft.client.AtlasImageCache;
import dev.paintcraft.core.ColorFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
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
        TextureAtlas blocksAtlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);

        Set<Integer> colors = new LinkedHashSet<>();
        Set<String> seenSprites = new HashSet<>();
        RandomSource random = RandomSource.create(42);

        // Iterate all 6 face directions + null (unculled/general quads)
        for (Direction dir : Direction.values()) {
            extractFromQuads(model, state, dir, random, colors, seenSprites, blocksAtlas);
        }
        extractFromQuads(model, state, null, random, colors, seenSprites, blocksAtlas);

        // Fallback: if no quads produced colors, try particle icon
        if (colors.isEmpty()) {
            TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
            if (sprite != null) {
                TextureAtlasSprite live = blocksAtlas.getSprite(sprite.contents().name());
                readSpriteColors(live, colors);
            }
        }

        return colors.stream().mapToInt(c -> c).toArray();
    }

    private static void extractFromQuads(BakedModel model, BlockState state, Direction dir,
                                         RandomSource random, Set<Integer> colors,
                                         Set<String> seenSprites, TextureAtlas blocksAtlas) {
        List<BakedQuad> quads = model.getQuads(state, dir, random);
        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.getSprite();
            // Re-resolve from the current live atlas — same stale-reference issue as BackgroundCapture.
            TextureAtlasSprite liveSprite = blocksAtlas.getSprite(sprite.contents().name());
            String name = liveSprite.contents().name().toString();
            if (seenSprites.add(name)) {
                readSpriteColors(liveSprite, colors);
            }
        }
    }

    private static void readSpriteColors(TextureAtlasSprite sprite, Set<Integer> colors) {
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        float u0 = sprite.getU0(), v0 = sprite.getV0();
        float uRange = sprite.getU1() - u0, vRange = sprite.getV1() - v0;
        // Sample the live GPU atlas snapshot (what the world renders) rather than
        // SpriteContents.getOriginalImage(), which can be a stale/HD pre-stitch source.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float u = u0 + (x + 0.5f) / w * uRange;
                float v = v0 + (y + 0.5f) / h * vRange;
                int abgr = AtlasImageCache.sampleABGR(u, v);
                int argb = ColorFormat.abgrToArgb(abgr);
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

}
