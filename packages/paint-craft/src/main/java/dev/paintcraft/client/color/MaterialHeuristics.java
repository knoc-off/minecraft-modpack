package dev.paintcraft.client.color;

import dev.paintcraft.core.MaterialSample;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Derives LabPBR specular properties from block characteristics when
 * no PBR resource pack is loaded. Uses block tags, registry IDs,
 * and light emission to produce reasonable defaults.
 */
public final class MaterialHeuristics {

    private MaterialHeuristics() {}

    /**
     * Produce a base MaterialSample for a block (not per-texel, just the block's overall material).
     * Normal X/Y are left at 128 (flat) since per-texel normals come from NormalMapGenerator.
     */
    public static MaterialSample forBlock(Block block) {
        BlockState state = block.defaultBlockState();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String path = id.getPath();

        int emission = Math.min(254, state.getLightEmission() * 18);

        if (isMetal(path)) {
            return new MaterialSample(128, 128, 255, 0,
                200, 230, 0, emission);
        }
        if (isGlass(path)) {
            return new MaterialSample(128, 128, 255, 0,
                240, 40, 0, emission);
        }
        if (isPolished(path)) {
            return new MaterialSample(128, 128, 255, 0,
                160, 15, 0, emission);
        }
        if (isWood(path)) {
            return new MaterialSample(128, 128, 255, 0,
                40, 10, 0, emission);
        }
        if (isOrganic(path)) {
            return new MaterialSample(128, 128, 255, 0,
                30, 10, 20, emission);
        }

        // Default: generic stone-like rough dielectric
        return new MaterialSample(128, 128, 255, 0,
            64, 10, 0, emission);
    }

    /**
     * Combine block-level specular with per-texel auto-generated normals.
     * normalPacked comes from NormalMapGenerator, specular from forBlock().
     */
    public static int applySpecular(MaterialSample blockMaterial) {
        return blockMaterial.packSpecular();
    }

    private static boolean isMetal(String path) {
        return path.contains("iron") || path.contains("gold") || path.contains("copper")
            || path.contains("netherite") || path.contains("diamond") || path.contains("emerald")
            || path.contains("lapis") || path.contains("anvil") || path.contains("chain")
            || path.contains("lantern") || path.contains("rail") || path.contains("hopper")
            || path.contains("cauldron") || path.contains("bell");
    }

    private static boolean isGlass(String path) {
        return path.contains("glass") || path.contains("ice") || path.contains("slime");
    }

    private static boolean isPolished(String path) {
        return path.contains("polished") || path.contains("smooth") || path.contains("glazed")
            || path.contains("quartz") || path.contains("prismarine") || path.contains("purpur");
    }

    private static boolean isWood(String path) {
        return path.contains("plank") || path.contains("log") || path.contains("wood")
            || path.contains("stripped") || path.contains("bamboo") || path.contains("stem");
    }

    private static boolean isOrganic(String path) {
        return path.contains("dirt") || path.contains("grass") || path.contains("sand")
            || path.contains("gravel") || path.contains("mud") || path.contains("soul")
            || path.contains("moss") || path.contains("leaves") || path.contains("wool")
            || path.contains("hay") || path.contains("sponge");
    }
}
