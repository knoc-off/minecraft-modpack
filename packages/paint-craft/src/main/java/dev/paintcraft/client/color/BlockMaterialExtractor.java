package dev.paintcraft.client.color;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.MaterialSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;

/**
 * Extracts per-texel material data (normals + specular) from block textures.
 *
 * Strategy:
 * 1. Check if PBR companion sprites (_n, _s) exist in the block atlas (from a PBR resource pack)
 * 2. If yes, sample them directly
 * 3. If no, auto-generate normals from albedo luminance and derive specular from block heuristics
 *
 * Results are cached alongside color data in BlockColorCache.
 */
public final class BlockMaterialExtractor {

    private BlockMaterialExtractor() {}

    /**
     * Extract packed normal and specular arrays for a block's primary texture.
     * Returns int[2][] where [0] = normals (LabPBR packed), [1] = specular (LabPBR packed).
     * Array dimensions match the sprite size.
     */
    public static int[][] extractMaterials(Block block) {
        TextureAtlasSprite sprite = getPrimarySprite(block);
        if (sprite == null) {
            return new int[][] { new int[0], new int[0] };
        }

        int w = sprite.contents().width();
        int h = sprite.contents().height();
        ResourceLocation spriteName = sprite.contents().name();

        // Try to find PBR companion sprites
        TextureAtlas blockAtlas = Minecraft.getInstance()
            .getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);

        int[] normals = tryLoadPBRSprite(blockAtlas, spriteName, "_n", w, h);
        int[] specular = tryLoadPBRSprite(blockAtlas, spriteName, "_s", w, h);

        boolean hasPBR = normals != null || specular != null;

        if (normals == null) {
            // Auto-generate normals from albedo luminance
            int[] albedo = readSpriteArgb(sprite, w, h);
            normals = NormalMapGenerator.generate(albedo, w, h);
        }

        if (specular == null) {
            // Derive from block heuristics (uniform across all texels)
            MaterialSample blockMat = MaterialHeuristics.forBlock(block);
            int packed = blockMat.packSpecular();
            specular = new int[w * h];
            java.util.Arrays.fill(specular, packed);
        }

        return new int[][] { normals, specular };
    }

    /**
     * Check if a PBR resource pack is providing companion textures for the block atlas.
     * Useful for UI indication ("PBR pack detected").
     */
    public static boolean hasPBRPack() {
        TextureAtlas blockAtlas = Minecraft.getInstance()
            .getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        // Check if stone has a _n companion as a quick probe
        ResourceLocation stoneN = ResourceLocation.withDefaultNamespace("block/stone_n");
        TextureAtlasSprite sprite = blockAtlas.getSprite(stoneN);
        return sprite != null && !MissingTextureAtlasSprite.isAtlasMissing(sprite.contents().name());
    }

    private static TextureAtlasSprite getPrimarySprite(Block block) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getBlockRenderer() == null) return null;
        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        BlockState state = block.defaultBlockState();
        BakedModel model = shaper.getBlockModel(state);

        // Try south face first (common for most blocks), then null quads
        for (Direction dir : new Direction[]{ Direction.SOUTH, Direction.UP, null }) {
            List<BakedQuad> quads = model.getQuads(state, dir, RandomSource.create(42));
            if (!quads.isEmpty()) {
                return quads.get(0).getSprite();
            }
        }

        TextureAtlasSprite particle = model.getParticleIcon(ModelData.EMPTY);
        return particle;
    }

    /**
     * Try to load a PBR companion sprite from the block atlas.
     * Returns null if the companion doesn't exist (no PBR pack loaded).
     */
    private static int[] tryLoadPBRSprite(TextureAtlas atlas, ResourceLocation baseName,
                                           String suffix, int expectedW, int expectedH) {
        ResourceLocation companionName = ResourceLocation.fromNamespaceAndPath(
            baseName.getNamespace(), baseName.getPath() + suffix);
        TextureAtlasSprite companion = atlas.getSprite(companionName);

        // getSprite returns MissingTexture if not found
        if (companion == null || MissingTextureAtlasSprite.isAtlasMissing(companion.contents().name())) {
            return null;
        }

        int w = companion.contents().width();
        int h = companion.contents().height();
        if (w != expectedW || h != expectedH) {
            return null; // size mismatch, can't use it
        }

        return readSpriteAbgrAsArgb(companion, w, h);
    }

    private static int[] readSpriteArgb(TextureAtlasSprite sprite, int w, int h) {
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = sprite.getPixelRGBA(0, x, y);
                argb[y * w + x] = ColorFormat.abgrToArgb(abgr);
            }
        }
        return argb;
    }

    private static int[] readSpriteAbgrAsArgb(TextureAtlasSprite sprite, int w, int h) {
        int[] result = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = sprite.getPixelRGBA(0, x, y);
                result[y * w + x] = ColorFormat.abgrToArgb(abgr);
            }
        }
        return result;
    }
}
