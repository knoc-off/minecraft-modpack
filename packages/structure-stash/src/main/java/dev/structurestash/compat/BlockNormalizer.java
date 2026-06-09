package dev.structurestash.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Normalizes StructureTemplate NBT before saving or placing blueprints.
 *
 * <p>Three operations, applied to the raw palette in one pass:
 * <ol>
 *   <li><b>Replace with air</b> — exploitable/illegal blocks (portals, fire, fluids,
 *       broken mechanical states) become {@code minecraft:air}.</li>
 *   <li><b>Replace with base variant</b> — decorative compound states become their
 *       placeable root: potted plants → flower_pot; candle cakes → cake;
 *       vine body blocks → vine head at age 0; tall_seagrass → seagrass;
 *       bamboo_sapling → bamboo; attached stems → unattached stems at age 0.</li>
 *   <li><b>Age reset</b> — crop and vine blocks have their {@code age} property
 *       reset to {@code "0"}, preventing players from capturing pre-grown farms.</li>
 * </ol>
 *
 * <p>All operations are in-place mutations of the CompoundTag, working directly on
 * the {@code "palette"} list without round-tripping through StructureTemplate.
 *
 * <p>Also provides {@link #getSyntheticCost(Block)} for blocks that survive
 * normalization but still have no item form (e.g. farmland → dirt cost).
 */
public final class BlockNormalizer {

    // ── 1. Replace with air ───────────────────────────────────────────────

    private static final Set<String> REPLACE_WITH_AIR = Set.of(
        // Portal / gateway — create functional teleportation structures for free
        "minecraft:nether_portal",
        "minecraft:end_portal",
        "minecraft:end_gateway",

        // Fluids — free infinite sources
        "minecraft:water",
        "minecraft:lava",

        // Fire — griefing / traps
        "minecraft:fire",
        "minecraft:soul_fire",

        // Broken internal mechanical states
        "minecraft:moving_piston",
        "minecraft:piston_head",

        // Misc zero-cost blocks with no legitimate blueprint use
        "minecraft:bubble_column",
        "minecraft:frosted_ice"
    );

    // ── 2. Replace with base variant ─────────────────────────────────────

    /**
     * Exact replacement mapping for internal/compound block states.
     * Value = [targetName, property-key, property-value, ...]
     * An empty properties array means "remove Properties entirely".
     */
    private static final Map<String, Replacement> REPLACE_WITH_BASE;

    static {
        Map<String, Replacement> m = new LinkedHashMap<>();

        // Vine body blocks → vine head blocks at age 0
        // (body blocks have no item form; head blocks do)
        m.put("minecraft:kelp_plant",
            new Replacement("minecraft:kelp",
                "age", "0"));
        m.put("minecraft:cave_vines_plant",
            new Replacement("minecraft:cave_vines",
                "age", "0", "berries", "false"));
        m.put("minecraft:weeping_vines_plant",
            new Replacement("minecraft:weeping_vines",
                "age", "0"));
        m.put("minecraft:twisting_vines_plant",
            new Replacement("minecraft:twisting_vines",
                "age", "0"));

        // Tall seagrass → seagrass (seagrass has Items.SEAGRASS)
        m.put("minecraft:tall_seagrass",
            new Replacement("minecraft:seagrass"));

        // Farmland — Items.FARMLAND exists in 1.21 but is unobtainable in survival
        // (farmland drops dirt when broken). Normalize to dirt so the cost is honest.
        m.put("minecraft:farmland",
            new Replacement("minecraft:dirt"));

        // Bamboo sapling → bamboo at default state (Items.BAMBOO)
        m.put("minecraft:bamboo_sapling",
            new Replacement("minecraft:bamboo",
                "age", "0", "leaves", "none", "stage", "0"));

        // Attached stems (fruit harvested/grown) → unattached stem at age 0
        // ATTACHED_*_STEM has a FACING property that MELON/PUMPKIN_STEM don't
        m.put("minecraft:attached_melon_stem",
            new Replacement("minecraft:melon_stem",
                "age", "0"));
        m.put("minecraft:attached_pumpkin_stem",
            new Replacement("minecraft:pumpkin_stem",
                "age", "0"));

        REPLACE_WITH_BASE = Collections.unmodifiableMap(m);
    }

    // ── 3. Age-reset blocks ───────────────────────────────────────────────

    /**
     * Blocks that survive normalization with their item form intact, but whose
     * {@code age} property must be reset to {@code "0"} to prevent players from
     * capturing mature/fully-grown farms.
     *
     * <p>Vine head blocks are NOT in this set — they're already age-0 after the
     * base-replacement step above.
     */
    private static final Set<String> AGE_RESET_BLOCKS = Set.of(
        "minecraft:wheat",            // age 0-7, asItem = wheat_seeds
        "minecraft:carrots",          // age 0-7, asItem = carrot
        "minecraft:potatoes",         // age 0-7, asItem = potato
        "minecraft:beetroots",        // age 0-3, asItem = beetroot_seeds
        "minecraft:nether_wart",      // age 0-3, asItem = nether_wart
        "minecraft:cocoa",            // age 0-2 (has facing too — only age reset)
        "minecraft:sweet_berry_bush", // age 0-3, asItem = sweet_berries
        "minecraft:torchflower_crop", // age 0-1, asItem = torchflower_seeds
        "minecraft:pitcher_crop"      // age 0-4, asItem = pitcher_pod
    );

    // ── 4. Synthetic costs ────────────────────────────────────────────────

    /**
     * Blocks that have no item form but should cost something.
     * The map is lazily resolved against Blocks.* at first use.
     */
    private static volatile Map<Block, ItemStack> SYNTHETIC_COSTS_CACHE;

    private static Map<Block, ItemStack> syntheticCosts() {
        if (SYNTHETIC_COSTS_CACHE == null) {
            // Currently empty — all known zero-item blocks are handled by
            // REPLACE_WITH_AIR or REPLACE_WITH_BASE. Kept as infrastructure
            // for future additions.
            SYNTHETIC_COSTS_CACHE = new IdentityHashMap<>();
        }
        return SYNTHETIC_COSTS_CACHE;
    }

    /**
     * Returns the synthetic cost item for a block that has no item form,
     * or {@code null} if no synthetic cost is defined.
     */
    @Nullable
    public static ItemStack getSyntheticCost(Block block) {
        ItemStack proto = syntheticCosts().get(block);
        return proto != null ? proto.copy() : null;
    }

    // ── Normalization ─────────────────────────────────────────────────────

    /**
     * Result of a normalization pass.
     *
     * @param stripped           unique registry names of blocks replaced with air, with counts
     * @param normalized         number of palette entries replaced with a base variant
     * @param ageReset           number of palette entries whose age was reset to 0
     * @param waterloggedStripped number of palette entries whose waterlogged property was cleared
     */
    public record NormalizationResult(
        Map<String, Integer> stripped,
        int normalized,
        int ageReset,
        int waterloggedStripped
    ) {
        public boolean anyChanges() {
            return !stripped.isEmpty() || normalized > 0 || ageReset > 0 || waterloggedStripped > 0;
        }

        /**
         * Build a single-line player-facing summary, or empty string if no changes.
         */
        public String summary() {
            if (!anyChanges()) return "";
            List<String> parts = new ArrayList<>();
            if (!stripped.isEmpty()) {
                List<String> names = new ArrayList<>();
                stripped.forEach((name, count) -> {
                    String short_ = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
                    names.add(count > 1 ? short_ + " \u00D7" + count : short_);
                });
                parts.add("stripped: " + String.join(", ", names));
            }
            if (normalized > 0) {
                parts.add("normalized " + normalized + " decorative block" + (normalized != 1 ? "s" : ""));
            }
            if (ageReset > 0) {
                parts.add("reset " + ageReset + " crop" + (ageReset != 1 ? "s" : "") + " to stage 0");
            }
            if (waterloggedStripped > 0) {
                parts.add("de-waterlogged " + waterloggedStripped + " block" + (waterloggedStripped != 1 ? "s" : ""));
            }
            return String.join(" \u00B7 ", parts);
        }
    }

    /**
     * Normalize the {@code "palette"} (and associated {@code "blocks"}) of a
     * StructureTemplate NBT tag in-place.
     *
     * <p>This must be called <em>after</em> {@code template.save()} but <em>before</em>
     * final serialization. The tag is modified directly — no copy is made.
     *
     * @param structureNbt the root NBT tag as produced by {@code StructureTemplate.save()}
     * @return a {@link NormalizationResult} summarising what was changed
     */
    public static NormalizationResult normalize(CompoundTag structureNbt) {
        ListTag paletteList = structureNbt.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocksList  = structureNbt.getList("blocks",  Tag.TAG_COMPOUND);

        Map<String, Integer> stripped = new LinkedHashMap<>();
        int normalized = 0;
        int ageReset   = 0;
        int waterloggedStripped = 0;

        // Track which palette indices were replaced with air so we can
        // strip block-entity NBT from the corresponding block entries.
        boolean[] wasAired = new boolean[paletteList.size()];

        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag entry = paletteList.getCompound(i);
            String name = entry.getString("Name");

            // ── 1. Replace with air ──────────────────────────────────────
            if (REPLACE_WITH_AIR.contains(name)) {
                entry.putString("Name", "minecraft:air");
                entry.remove("Properties");
                stripped.merge(name, 1, Integer::sum);
                wasAired[i] = true;
                continue;
            }

            // ── 2a. Potted plants → flower_pot ──────────────────────────
            if (name.startsWith("minecraft:potted_") && !name.equals("minecraft:flower_pot")) {
                entry.putString("Name", "minecraft:flower_pot");
                entry.remove("Properties");
                normalized++;
                continue;
            }

            // ── 2b. Candle cakes → cake ──────────────────────────────────
            if (name.endsWith("_candle_cake") || name.equals("minecraft:candle_cake")) {
                entry.putString("Name", "minecraft:cake");
                CompoundTag props = new CompoundTag();
                props.putString("bites", "0");
                entry.put("Properties", props);
                normalized++;
                continue;
            }

            // ── 2c. Vine bodies / tall seagrass / bamboo sapling / attached stems ──
            Replacement rep = REPLACE_WITH_BASE.get(name);
            if (rep != null) {
                entry.putString("Name", rep.targetName);
                if (rep.properties.isEmpty()) {
                    entry.remove("Properties");
                } else {
                    CompoundTag props = new CompoundTag();
                    rep.properties.forEach(props::putString);
                    entry.put("Properties", props);
                }
                normalized++;
                continue;
            }

            // ── 3. Age reset ─────────────────────────────────────────────
            if (AGE_RESET_BLOCKS.contains(name) && entry.contains("Properties", Tag.TAG_COMPOUND)) {
                CompoundTag props = entry.getCompound("Properties");
                if (props.contains("age")) {
                    props.putString("age", "0");
                    ageReset++;
                }
            }

            // ── 4. Strip waterlogged ─────────────────────────────────────
            // Any block with waterlogged=true is a free water source when placed.
            // Normalize to the dry base state; the player can waterlog manually.
            if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
                CompoundTag props = entry.getCompound("Properties");
                if ("true".equals(props.getString("waterlogged"))) {
                    props.putString("waterlogged", "false");
                    waterloggedStripped++;
                }
            }
        }

        // ── Block entry cleanup ──────────────────────────────────────────
        // Remove block-entity NBT from entries that now reference an air palette slot.
        for (int i = 0; i < blocksList.size(); i++) {
            CompoundTag blockEntry = blocksList.getCompound(i);
            int stateIdx = blockEntry.getInt("state");
            if (stateIdx >= 0 && stateIdx < wasAired.length && wasAired[stateIdx]) {
                blockEntry.remove("nbt");
            }
        }

        return new NormalizationResult(
            Collections.unmodifiableMap(stripped), normalized, ageReset, waterloggedStripped);
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Describes how to replace a palette entry with a different block.
     * {@code properties} is an ordered map of property-key → value strings
     * to write into the {@code "Properties"} CompoundTag. Empty = no Properties.
     */
    private record Replacement(String targetName, Map<String, String> properties) {

        /** Convenience constructor — alternating key/value strings. */
        Replacement(String targetName, String... kvPairs) {
            this(targetName, buildProps(kvPairs));
        }

        /** Convenience constructor — no properties. */
        Replacement(String targetName) {
            this(targetName, Map.of());
        }

        private static Map<String, String> buildProps(String[] kvPairs) {
            if (kvPairs.length == 0) return Map.of();
            if (kvPairs.length % 2 != 0)
                throw new IllegalArgumentException("kvPairs must come in pairs");
            LinkedHashMap<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < kvPairs.length; i += 2) m.put(kvPairs[i], kvPairs[i + 1]);
            return Collections.unmodifiableMap(m);
        }
    }

    private BlockNormalizer() {}
}
