package dev.paintcraft.core.cost;

import dev.paintcraft.ModServerConfig;
import dev.paintcraft.core.color.OkLab;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Dye cost model for painted pixels, shared by the Asset Shelf integration and the
 * editor's paste-charge flow. Depends only on vanilla types + paint-craft's own
 * {@link OkLab}, so it works whether or not Asset Shelf is installed and runs
 * identically on client and server.
 *
 * <p>Each non-transparent pixel votes for its perceptually nearest vanilla dye
 * (OkLab distance). A color costs {@code round(pixelsOfColor / pixelsPerDye)} dye;
 * a color amounting to less than half a dye rounds to 0 (free).
 */
public final class PaintCost {

    /**
     * Perceptual (OkLab cylindrical) coordinates of the 16 vanilla dyes, indexed by ordinal:
     * lightness, chroma, and hue angle (radians).
     */
    private static final float[] DYE_L = new float[DyeColor.values().length];
    private static final float[] DYE_C = new float[DyeColor.values().length];
    private static final float[] DYE_H = new float[DyeColor.values().length];
    static {
        for (DyeColor dc : DyeColor.values()) {
            OkLab.Lab lab = OkLab.fromArgb(dc.getTextureDiffuseColor());
            int i = dc.ordinal();
            DYE_L[i] = lab.L();
            DYE_C[i] = (float) Math.hypot(lab.a(), lab.b());
            DYE_H[i] = (float) Math.atan2(lab.b(), lab.a());
        }
    }

    // OkLab's a/b axes only span ~±0.25 while L spans 0..1, so plain (L,a,b) distance is
    // dominated by lightness and effectively ignores hue. Weight chroma and hue up so color
    // matters as much as brightness: low-saturation pixels then prefer the neutral (gray) dyes
    // instead of an accidental brown, and saturated pixels match by actual hue.
    private static final float W_CHROMA = 2.5f;
    private static final float W_HUE = 2.5f;

    private PaintCost() {}

    /** Per-dye counts (indexed by {@link DyeColor#ordinal()}) required for the given ARGB pixels. */
    public static int[] dyeCounts(int[] pixels) {
        int[] votes = new int[16];
        for (int pixel : pixels) {
            if ((pixel >>> 24) == 0) continue; // only fully transparent pixels are free
            votes[nearestDye(pixel)]++;
        }
        int pixelsPerDye = ModServerConfig.CONFIG.pixelsPerDye.get();
        int[] out = new int[16];
        for (int i = 0; i < 16; i++) {
            if (votes[i] == 0) continue;
            out[i] = Math.round((float) votes[i] / pixelsPerDye);
        }
        return out;
    }

    /** Required dye stacks — one entry per dye color with a non-zero cost. */
    public static List<ItemStack> dyeCost(int[] pixels) {
        int[] counts = dyeCounts(pixels);
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            if (counts[i] >= 1) {
                stacks.add(new ItemStack(DyeItem.byColor(DyeColor.values()[i]), counts[i]));
            }
        }
        return stacks;
    }

    /** Whether the player's main inventory holds enough dye to pay for these pixels. */
    public static boolean canAfford(Player player, int[] pixels) {
        int[] counts = dyeCounts(pixels);
        for (int i = 0; i < 16; i++) {
            if (counts[i] < 1) continue;
            Item dye = DyeItem.byColor(DyeColor.values()[i]);
            if (countItem(player, dye) < counts[i]) return false;
        }
        return true;
    }

    /**
     * Deduct the dye cost from the player's main inventory. Returns false without
     * modifying anything if the player can't afford it.
     */
    public static boolean consume(Player player, int[] pixels) {
        if (!canAfford(player, pixels)) return false;
        int[] counts = dyeCounts(pixels);
        for (int i = 0; i < 16; i++) {
            if (counts[i] < 1) continue;
            Item dye = DyeItem.byColor(DyeColor.values()[i]);
            int remaining = counts[i];
            var items = player.getInventory().items;
            for (int s = 0; s < items.size() && remaining > 0; s++) {
                ItemStack slot = items.get(s);
                if (slot.is(dye)) {
                    int take = Math.min(slot.getCount(), remaining);
                    slot.shrink(take);
                    remaining -= take;
                }
            }
        }
        return true;
    }

    private static int countItem(Player player, Item item) {
        int have = 0;
        for (ItemStack slot : player.getInventory().items) {
            if (slot.is(item)) have += slot.getCount();
        }
        return have;
    }

    /** Index (dye ordinal) of the perceptually nearest vanilla dye to a packed ARGB color. */
    public static int nearestDye(int argb) {
        OkLab.Lab lab = OkLab.fromArgb(argb);
        float L = lab.L();
        float C = (float) Math.hypot(lab.a(), lab.b());
        float h = (float) Math.atan2(lab.b(), lab.a());

        float bestDist = Float.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < DYE_L.length; i++) {
            float dL = L - DYE_L[i];
            float dC = C - DYE_C[i];

            // Cylindrical hue difference, scaled by both chromas so it vanishes for near-neutral
            // colors (where hue is meaningless) — see CIEDE-style ΔH.
            float dh = h - DYE_H[i];
            if (dh > (float) Math.PI) dh -= (float) (2 * Math.PI);
            else if (dh < (float) -Math.PI) dh += (float) (2 * Math.PI);
            float dH = 2f * (float) Math.sqrt(Math.max(0f, C * DYE_C[i]))
                * (float) Math.sin(dh * 0.5f);

            float wc = W_CHROMA * dC;
            float wh = W_HUE * dH;
            float dist = dL * dL + wc * wc + wh * wh;
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
