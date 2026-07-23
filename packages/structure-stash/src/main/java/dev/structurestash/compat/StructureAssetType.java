package dev.structurestash.compat;

import com.mojang.blaze3d.platform.NativeImage;
import dev.assetshelf.api.AssetType;
import dev.assetshelf.api.ItemCost;
import dev.structurestash.StructureStash;
import dev.structurestash.client.DeferredThumbnailPipeline;
import dev.structurestash.client.StructureThumbnailRenderer;
import dev.structurestash.item.ModDataComponents;
import dev.structurestash.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.io.ByteArrayInputStream;
import java.util.*;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Asset Shelf integration for captured multi-block structures (blueprints).
 * Structures are stored/rendered in the vanilla {@code StructureTemplate} NBT format.
 */
public class StructureAssetType implements AssetType {

    public static final ResourceLocation TYPE_ID =
        ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "structure");

    // ── Caches ──

    /** Cached preview stacks: keyed on data content hash. */
    private final Map<Integer, Optional<ItemStack>> previewCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Optional<ItemStack>> e) { return size() > 64; }
    };

    /** Cached rendered thumbnails: key = (dataHash << 32 | size), value = ABGR pixel array. */
    private final Map<Long, int[]> thumbCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, int[]> e) { return size() > 128; }
    };

    /** Cached cost breakdowns: avoids per-frame NBT decompression + block counting. */
    private final Map<Integer, CostBreakdown> costCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, CostBreakdown> e) { return size() > 64; }
    };

    // ── Deferred thumbnail pipeline ──

    /**
     * Lazily created on first client-side use. Instantiating {@link DeferredThumbnailPipeline}
     * eagerly would load a {@code @OnlyIn(Dist.CLIENT)} class during {@code <init>}, which
     * crashes the dedicated server (this AssetType is registered on both dists).
     */
    private DeferredThumbnailPipeline pipeline;
    private final List<PendingThumb> pendingCallbacks = new ArrayList<>();

    private DeferredThumbnailPipeline pipeline() {
        if (pipeline == null) pipeline = new DeferredThumbnailPipeline();
        return pipeline;
    }

    private record PendingThumb(long key, NativeImage target, int size, Runnable onReady) {}

    @Override
    public ResourceLocation id() { return TYPE_ID; }

    @Override
    public Component displayName() { return Component.literal("Structures"); }

    @Override
    public int accentColor() { return 0xFF6AAFCF; }

    @Override
    public ResourceLocation icon() {
        return ResourceLocation.fromNamespaceAndPath("structurestash", "textures/gui/icon.png");
    }

    @Override
    public Optional<ItemStack> getPreviewStack(byte[] data) {
        // Cache the preview result — avoids re-decompressing NBT
        // on every buildThumbs/rebuildDetailTex call for the same asset
        int key = Arrays.hashCode(data);
        return previewCache.computeIfAbsent(key, k -> extractSingleBlockPreview(data));
    }

    @Override
    public void renderThumbnail(byte[] data, NativeImage target, int size) {
        renderMultiBlockThumbnail(data, target, size);
    }

    @Override
    public List<ItemCost> computeCost(byte[] data) {
        return computeMultiBlockCost(data);
    }

    @Override
    public boolean consumeCost(ServerPlayer player, byte[] data, int quantity) {
        return consumeMultiBlockCost(player, data, quantity);
    }

    @Override
    public boolean canAffordClient(byte[] data, int quantity) {
        return canAffordMultiBlockClient(data, quantity);
    }

    @Override
    public void onUse(ServerPlayer player, byte[] data) {
        // Give a Blueprint item with the structure data
        ItemStack blueprint = new ItemStack(ModItems.BLUEPRINT.get());
        blueprint.set(ModDataComponents.BLUEPRINT_DATA.get(), new dev.structurestash.item.BlueprintData(data));
        if (!player.getInventory().add(blueprint)) {
            player.drop(blueprint, false);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Preview (1x1x1 structures)
    // ═══════════════════════════════════════════════════════════════

    /**
     * For 1x1x1 structures, extract the single block and return a
     * preview ItemStack for native 3D rendering.
     */
    private Optional<ItemStack> extractSingleBlockPreview(byte[] data) {
        if (data == null || data.length == 0) return Optional.empty();
        try {
            CompoundTag root = NbtIo.readCompressed(
                new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            ListTag sizeTag = root.getList("size", Tag.TAG_INT);
            if (sizeTag.size() != 3) return Optional.empty();
            int sx = sizeTag.getInt(0), sy = sizeTag.getInt(1), sz = sizeTag.getInt(2);
            if (sx != 1 || sy != 1 || sz != 1) return Optional.empty();

            HolderLookup.Provider registries = getRegistries();
            if (registries == null) return Optional.empty();
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);

            // Resolve the single block's state from the palette
            ListTag paletteList = root.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocksList = root.getList("blocks", Tag.TAG_COMPOUND);
            if (blocksList.isEmpty()) return Optional.empty();

            CompoundTag entry = blocksList.getCompound(0);
            int stateIdx = entry.getInt("state");
            if (stateIdx < 0 || stateIdx >= paletteList.size()) return Optional.empty();

            BlockState state = BlockState.CODEC.parse(ops, paletteList.getCompound(stateIdx))
                .result().orElse(null);
            if (state == null || state.isAir()) return Optional.empty();

            Item item = state.getBlock().asItem();
            if (item == Items.AIR) return Optional.empty();
            return Optional.of(new ItemStack(item));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cost computation
    // ═══════════════════════════════════════════════════════════════

    private record CostBreakdown(Map<Item, Integer> blockCosts) {}

    private CostBreakdown computeDetailedMultiBlockCost(byte[] data) {
        int key = Arrays.hashCode(data);
        CostBreakdown cached = costCache.get(key);
        if (cached != null) return cached;
        CostBreakdown result = computeDetailedMultiBlockCost(data, getRegistries());
        costCache.put(key, result);
        return result;
    }

    private CostBreakdown computeDetailedMultiBlockCost(byte[] data, HolderLookup.Provider registries) {
        Map<Item, Integer> blockCosts = new LinkedHashMap<>();
        if (registries == null) return new CostBreakdown(blockCosts);

        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());

            // Parse the StructureTemplate palette to identify blocks
            // The template format: "blocks" list entries reference "palette" by index
            ListTag paletteList = root.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocksList = root.getList("blocks", Tag.TAG_COMPOUND);

            // Build palette: index → BlockState
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < paletteList.size(); i++) {
                CompoundTag stateTag = paletteList.getCompound(i);
                BlockState state = net.minecraft.world.level.block.state.BlockState.CODEC
                    .parse(ops, stateTag).result()
                    .orElse(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                palette.add(state);
            }

            // Process each block
            for (int i = 0; i < blocksList.size(); i++) {
                CompoundTag blockEntry = blocksList.getCompound(i);
                int stateIdx = blockEntry.getInt("state");
                if (stateIdx < 0 || stateIdx >= palette.size()) continue;

                BlockState state = palette.get(stateIdx);
                if (state.isAir()) continue;

                // Skip non-canonical halves of multi-position blocks to avoid double-counting.
                // Beds: HEAD is canonical (placing head also places foot).
                // Doors, tall flowers/grass: LOWER is canonical (placing lower also places upper).
                if (state.hasProperty(BlockStateProperties.BED_PART)
                        && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) continue;
                if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;

                // Normal block: costs the block item
                Item blockItem = state.getBlock().asItem();
                if (blockItem != Items.AIR) {
                    blockCosts.merge(blockItem, 1, Integer::sum);
                } else {
                    // No item form — check synthetic cost map (e.g. farmland → dirt)
                    ItemStack synth = BlockNormalizer.getSyntheticCost(state.getBlock());
                    if (synth != null) {
                        blockCosts.merge(synth.getItem(), synth.getCount(), Integer::sum);
                    }
                }
                // Framed blocks: also cost the camo material(s)
                if (blockEntry.contains("nbt")) {
                    CompoundTag beNbt = blockEntry.getCompound("nbt");
                    addCamoCost(beNbt, "camo", ops, blockCosts);
                    addCamoCost(beNbt, "camo_two", ops, blockCosts);
                }
            }
        } catch (Exception e) {
            StructureStash.LOGGER.error("Failed to compute multi-block cost", e);
        }

        return new CostBreakdown(blockCosts);
    }

    /** Extract camo material from framed block NBT and add it to the cost map. */
    private static void addCamoCost(CompoundTag beNbt, String key,
                                     com.mojang.serialization.DynamicOps<Tag> ops,
                                     Map<Item, Integer> blockCosts) {
        if (!beNbt.contains(key)) return;
        CompoundTag camoTag = beNbt.getCompound(key);
        if (!camoTag.contains("state")) return;
        BlockState camoState = BlockState.CODEC.parse(ops, camoTag.getCompound("state"))
            .result().orElse(null);
        if (camoState == null || camoState.isAir()) return;
        Item camoItem = camoState.getBlock().asItem();
        if (camoItem != Items.AIR) {
            blockCosts.merge(camoItem, 1, Integer::sum);
        }
    }

    private List<ItemCost> computeMultiBlockCost(byte[] data) {
        CostBreakdown breakdown = computeDetailedMultiBlockCost(data);
        List<ItemCost> costs = new ArrayList<>();
        for (var entry : breakdown.blockCosts.entrySet()) {
            costs.add(ItemCost.of(entry.getKey(), entry.getValue()));
        }
        return costs;
    }

    private boolean consumeMultiBlockCost(ServerPlayer player, byte[] data, int quantity) {
        CostBreakdown breakdown = computeDetailedMultiBlockCost(data, player.registryAccess());

        // Phase 1: Check ALL blocks available in inventory
        for (var entry : breakdown.blockCosts.entrySet()) {
            int required = entry.getValue() * quantity;
            int have = countInventoryItem(player, entry.getKey());
            if (have < required) {
                String name = entry.getKey().getDescription().getString();
                player.displayClientMessage(
                    Component.literal("Not enough " + name + "! (need " + required + ")"), true);
                return false;
            }
        }

        // Phase 2: All checks passed — atomically deduct everything
        for (var entry : breakdown.blockCosts.entrySet()) {
            consumeInventoryItem(player, entry.getKey(), entry.getValue() * quantity);
        }

        return true;
    }

    private boolean canAffordMultiBlockClient(byte[] data, int quantity) {
        CostBreakdown breakdown = computeDetailedMultiBlockCost(data);

        // Check blocks in player inventory
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return false;
        for (var entry : breakdown.blockCosts.entrySet()) {
            int required = entry.getValue() * quantity;
            int have = 0;
            for (ItemStack slot : mc.player.getInventory().items) {
                if (slot.is(entry.getKey())) have += slot.getCount();
            }
            if (have < required) return false;
        }

        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Thumbnail rendering (GPU-accelerated)
    // ═══════════════════════════════════════════════════════════════

    private void renderMultiBlockThumbnail(byte[] data, NativeImage target, int size) {
        // Check cache first
        long cacheKey = ((long) Arrays.hashCode(data) << 32) | (size & 0xFFFFFFFFL);
        int[] cached = thumbCache.get(cacheKey);
        if (cached != null && cached.length == size * size) {
            for (int i = 0; i < cached.length; i++)
                target.setPixelRGBA(i % size, i / size, cached[i]);
            return;
        }

        long t0 = System.nanoTime();
        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            HolderLookup.Provider registries = getRegistries();
            if (registries == null) return;

            StructureThumbnailRenderer.renderToImage(root, target, size, registries);

            // Store in cache
            cachePixels(cacheKey, target, size);

            long ms = (System.nanoTime() - t0) / 1_000_000;
            StructureStash.LOGGER.debug("Rendered structure thumbnail {}x{} in {}ms (GPU, cache miss, key={})",
                size, size, ms, Long.toHexString(cacheKey));
        } catch (Exception e) {
            StructureStash.LOGGER.error("Failed to render structure thumbnail", e);
        }
    }

    // ── Deferred thumbnail SPI ────────────────────────────────────────

    @Override
    public void submitDeferredThumbnail(byte[] data, NativeImage target, int size, Runnable onReady) {
        long cacheKey = ((long) Arrays.hashCode(data) << 32) | (size & 0xFFFFFFFFL);

        // Check pixel cache — instant copy if cached
        int[] cached = thumbCache.get(cacheKey);
        if (cached != null && cached.length == size * size) {
            for (int i = 0; i < cached.length; i++)
                target.setPixelRGBA(i % size, i / size, cached[i]);
            onReady.run();
            return;
        }

        // Submit to background pipeline for deferred rendering
        HolderLookup.Provider registries = getRegistries();
        if (registries == null) return;
        pipeline().submit(cacheKey, data, registries);
        pendingCallbacks.add(new PendingThumb(cacheKey, target, size, onReady));
    }

    @Override
    public void tickDeferredThumbnails() {
        if (pendingCallbacks.isEmpty()) return;

        DeferredThumbnailPipeline.PreparedJob job = pipeline().poll();
        if (job == null) return;

        // Find the callback for this key
        PendingThumb pt = null;
        for (var it = pendingCallbacks.iterator(); it.hasNext();) {
            PendingThumb p = it.next();
            if (p.key == job.key()) { pt = p; it.remove(); break; }
        }
        if (pt == null) return; // stale — callback was cancelled

        long t0 = System.nanoTime();

        // Render prepared grid via FBO (fast — storages pre-decoded)
        StructureThumbnailRenderer.renderGridToImage(job.grid(), pt.target, pt.size);

        // Cache the rendered pixels
        cachePixels(pt.key, pt.target, pt.size);

        long ms = (System.nanoTime() - t0) / 1_000_000;
        StructureStash.LOGGER.debug("Rendered deferred thumbnail {}x{} in {}ms (FBO only, key={})",
            pt.size, pt.size, ms, Long.toHexString(pt.key));

        // Fire callback (uploads DynamicTexture to GPU)
        pt.onReady.run();
    }

    @Override
    public void cancelDeferredThumbnails() {
        if (pipeline != null) pipeline.cancelAll();
        pendingCallbacks.clear();
    }

    private void cachePixels(long cacheKey, NativeImage target, int size) {
        int[] pixels = new int[size * size];
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = target.getPixelRGBA(i % size, i / size);
        thumbCache.put(cacheKey, pixels);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Debug diagnostics
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String generateDebugInfo(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("═══ Structure Analysis ═══\n");

        HolderLookup.Provider registries = getRegistries();
        if (registries == null) {
            sb.append("error: registries unavailable\n");
            return sb.toString();
        }

        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            ListTag sizeTag = root.getList("size", Tag.TAG_INT);
            int sx = sizeTag.getInt(0), sy = sizeTag.getInt(1), sz = sizeTag.getInt(2);
            sb.append("size: ").append(sx).append("×").append(sy).append("×").append(sz)
              .append(" (").append(sx * sy * sz).append(" positions)\n");

            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            ListTag paletteList = root.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocksList = root.getList("blocks", Tag.TAG_COMPOUND);

            // Parse palette
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < paletteList.size(); i++) {
                palette.add(BlockState.CODEC.parse(ops, paletteList.getCompound(i))
                    .result().orElse(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
            }

            // Count non-air and blocks with entity data
            int nonAir = 0;
            int withEntity = 0;
            List<String> entityDetails = new ArrayList<>();
            List<String> anomalies = new ArrayList<>();

            // Compute cost items for cross-referencing
            Set<String> costedItems = new HashSet<>();
            List<ItemCost> costs = computeMultiBlockCost(data);
            for (ItemCost cost : costs) {
                costedItems.add(cost.stack().getHoverName().getString().toLowerCase());
                costedItems.add(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(cost.stack().getItem()).toString());
            }

            for (int i = 0; i < blocksList.size(); i++) {
                CompoundTag entry = blocksList.getCompound(i);
                int stateIdx = entry.getInt("state");
                if (stateIdx < 0 || stateIdx >= palette.size()) continue;
                BlockState state = palette.get(stateIdx);
                if (state.isAir()) continue;
                nonAir++;

                if (!entry.contains("nbt")) continue;
                withEntity++;

                ListTag posTag = entry.getList("pos", Tag.TAG_INT);
                String pos = "(" + posTag.getInt(0) + "," + posTag.getInt(1) + "," + posTag.getInt(2) + ")";
                CompoundTag beNbt = entry.getCompound("nbt");
                String blockId = state.getBlock().builtInRegistryHolder().key().location().toString();

                if (beNbt.contains("camo")) {
                    // Framed block — report camo state
                    CompoundTag camoTag = beNbt.getCompound("camo");
                    String camoDesc = "<empty>";
                    String camoBlockId = null;
                    if (camoTag.contains("state")) {
                        CompoundTag stateNbt = camoTag.getCompound("state");
                        camoDesc = stateNbt.getString("Name");
                        if (stateNbt.contains("Properties")) {
                            camoDesc += stateNbt.getCompound("Properties").toString();
                        }
                        camoBlockId = stateNbt.getString("Name");
                    }
                    entityDetails.add(pos + " " + blockId + " — camo: " + camoDesc);

                    // Check if camo material is in cost list
                    if (camoBlockId != null && !camoBlockId.isEmpty()) {
                        if (!costedItems.contains(camoBlockId)) {
                            anomalies.add(pos + " " + blockId
                                + ": has camo \"" + camoBlockId + "\" — material not in cost list");
                        }
                    }

                    // Check for second camo (double framed blocks)
                    if (beNbt.contains("camo_two")) {
                        CompoundTag camo2 = beNbt.getCompound("camo_two");
                        String camo2Desc = "<empty>";
                        String camo2Id = null;
                        if (camo2.contains("state")) {
                            CompoundTag s2 = camo2.getCompound("state");
                            camo2Desc = s2.getString("Name");
                            camo2Id = s2.getString("Name");
                        }
                        entityDetails.add(pos + " " + blockId + " — camo_two: " + camo2Desc);
                        if (camo2Id != null && !camo2Id.isEmpty() && !costedItems.contains(camo2Id)) {
                            anomalies.add(pos + " " + blockId
                                + ": has camo_two \"" + camo2Id + "\" — material not in cost list");
                        }
                    }
                } else {
                    // Other block entity — dump NBT keys
                    entityDetails.add(pos + " " + blockId + " — nbt keys: " + beNbt.getAllKeys());
                }
            }

            sb.append("non_air_blocks: ").append(nonAir).append("\n");
            sb.append("blocks_with_entity_data: ").append(withEntity).append("\n");

            // Palette
            sb.append("\npalette:\n");
            for (int i = 0; i < palette.size(); i++) {
                BlockState ps = palette.get(i);
                String id = ps.getBlock().builtInRegistryHolder().key().location().toString();
                String props = "";
                if (!ps.getValues().isEmpty()) {
                    StringBuilder propSb = new StringBuilder("{");
                    ps.getValues().forEach((prop, val) -> {
                        if (propSb.length() > 1) propSb.append(",");
                        propSb.append(prop.getName()).append("=").append(val.toString());
                    });
                    propSb.append("}");
                    props = propSb.toString();
                }
                sb.append("  [").append(i).append("] ").append(id).append(props).append("\n");
            }

            // Block entities
            if (!entityDetails.isEmpty()) {
                sb.append("\nblock_entities:\n");
                for (String detail : entityDetails) {
                    sb.append("  ").append(detail).append("\n");
                }
            }

            // Anomalies
            if (!anomalies.isEmpty()) {
                sb.append("\n═══ Anomalies ═══\n");
                for (String anomaly : anomalies) {
                    sb.append("  ⚠ ").append(anomaly).append("\n");
                }
            }

        } catch (Exception e) {
            sb.append("error: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private static HolderLookup.Provider getRegistries() {
        // Try server first (works on both dedicated and integrated)
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.registryAccess();
        // Fall back to client (only safe on physical client)
        if (FMLEnvironment.dist == Dist.CLIENT) return getClientRegistries();
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    private static HolderLookup.Provider getClientRegistries() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) return mc.level.registryAccess();
        var server = mc.getSingleplayerServer();
        if (server != null) return server.registryAccess();
        return null;
    }

    private static int countInventoryItem(ServerPlayer player, Item item) {
        int count = 0;
        for (ItemStack slot : player.getInventory().items) {
            if (slot.is(item)) count += slot.getCount();
        }
        return count;
    }

    private static void consumeInventoryItem(ServerPlayer player, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().items.size() && remaining > 0; i++) {
            ItemStack slot = player.getInventory().items.get(i);
            if (slot.is(item)) {
                int take = Math.min(slot.getCount(), remaining);
                slot.shrink(take);
                remaining -= take;
            }
        }
    }
}
