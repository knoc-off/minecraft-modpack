package dev.paintcraft.client.color;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class BlockColorCache {

    private static final Map<String, Map<Block, int[]>> CACHES = new HashMap<>();
    private static String lastHash = "";

    private BlockColorCache() {}

    /**
     * Get the color map for the current resource pack configuration.
     * Lazily computed and cached by resource pack hash.
     */
    public static Map<Block, int[]> get() {
        String hash = computeResourcePackHash();
        if (!hash.equals(lastHash)) {
            lastHash = hash;
        }
        return CACHES.computeIfAbsent(hash, k -> new HashMap<>());
    }

    /**
     * Get colors for a specific block, computing if not cached.
     */
    public static int[] getColors(Block block) {
        Map<Block, int[]> cache = get();
        return cache.computeIfAbsent(block, BlockColorExtractor::extractColors);
    }

    /**
     * Clear all caches (e.g., on major resource reload).
     */
    public static void clearAll() {
        CACHES.clear();
        lastHash = "";
    }

    private static String computeResourcePackHash() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getResourcePackRepository() == null) return "default";
        return mc.getResourcePackRepository().getSelectedPacks().stream()
            .map(Pack::getId)
            .sorted()
            .collect(Collectors.joining("|"));
    }
}
