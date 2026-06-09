package dev.structurestash.compat;

import com.mojang.blaze3d.platform.NativeImage;
import dev.assetshelf.api.AssetType;
import dev.assetshelf.api.ItemCost;
import dev.structurestash.compat.BlockNormalizer;
import dev.structurestash.StructureStash;
import dev.structurestash.client.BitsStashClientCache;
import dev.structurestash.client.DeferredThumbnailPipeline;
import dev.structurestash.client.StructureThumbnailRenderer;
import dev.structurestash.client.StructureThumbnailRenderer.StructureGrid;
import dev.structurestash.item.ModDataComponents;
import dev.structurestash.item.ModItems;
import dev.structurestash.network.StashNetwork;
import dev.structurestash.stash.BitsStash;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.api.item.bit.IBitItem;
import mod.chiselsandbits.api.item.bit.IBitItemManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.Optional;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Asset Shelf integration for Chisels & Bits chiseled blocks.
 * Supports both single-block (custom format) and multi-block (StructureTemplate format).
 */
public class ChiseledAssetType implements AssetType {

    public static final ResourceLocation TYPE_ID =
        ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "chiseled_block");

    // ── Caches ──

    /** Cached isMultiBlock results: avoids repeated NBT decompression for format detection. */
    private final Map<Integer, Boolean> multiBlockCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> e) { return size() > 256; }
    };

    /** Cached preview stacks for multi-block data: keyed on data content hash. */
    private final Map<Integer, Optional<ItemStack>> previewCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Optional<ItemStack>> e) { return size() > 64; }
    };

    /** Cached rendered thumbnails: key = (dataHash << 32 | size), value = ABGR pixel array. */
    private final Map<Long, int[]> thumbCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, int[]> e) { return size() > 128; }
    };

    /** Cached cost breakdowns: avoids per-frame NBT decompression + voxel counting. */
    private final Map<Integer, CostBreakdown> costCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, CostBreakdown> e) { return size() > 64; }
    };

    // ── Deferred thumbnail pipeline ──

    private final DeferredThumbnailPipeline pipeline = new DeferredThumbnailPipeline();
    private final List<PendingThumb> pendingCallbacks = new ArrayList<>();

    private record PendingThumb(long key, NativeImage target, int size, Runnable onReady) {}

    @Override
    public ResourceLocation id() { return TYPE_ID; }

    @Override
    public Component displayName() { return Component.literal("Chiseled Blocks"); }

    @Override
    public int accentColor() { return 0xFF6AAFCF; }

    @Override
    public ResourceLocation icon() {
        return ResourceLocation.fromNamespaceAndPath("structurestash", "textures/gui/icon.png");
    }

    @Override
    public Optional<ItemStack> getPreviewStack(byte[] data) {
        if (!isMultiBlock(data)) {
            StateEntryStorage storage = deserializeSingle(data);
            if (storage == null) return Optional.empty();
            ItemStack stack = CnBInterop.createChiseledItemStack(
                CnBInterop.getChiseledBlockItem(), storage
            );
            return stack.isEmpty() ? Optional.empty() : Optional.of(stack);
        }
        // Cache the multi-block preview result — avoids re-decompressing NBT
        // on every buildThumbs/rebuildDetailTex call for the same asset
        int key = Arrays.hashCode(data);
        return previewCache.computeIfAbsent(key, k -> extractSingleBlockPreview(data));
    }

    @Override
    public void renderThumbnail(byte[] data, NativeImage target, int size) {
        if (isMultiBlock(data)) {
            renderMultiBlockThumbnail(data, target, size);
        } else {
            renderSingleBlockThumbnail(data, target, size);
        }
    }

    @Override
    public List<ItemCost> computeCost(byte[] data) {
        if (isMultiBlock(data)) {
            return computeMultiBlockCost(data);
        } else {
            return computeSingleBlockCost(data);
        }
    }

    @Override
    public boolean consumeCost(ServerPlayer player, byte[] data, int quantity) {
        if (isMultiBlock(data)) {
            return consumeMultiBlockCost(player, data, quantity);
        } else {
            return consumeSingleBlockCost(player, data, quantity);
        }
    }

    @Override
    public boolean canAffordClient(byte[] data, int quantity) {
        if (isMultiBlock(data)) {
            return canAffordMultiBlockClient(data, quantity);
        } else {
            return canAffordSingleBlockClient(data, quantity);
        }
    }

    @Override
    public long countAvailableClient(ItemCost cost) {
        // Bit items: check the bits stash, not player inventory
        if (cost.stack().getItem() instanceof IBitItem bitItem) {
            BlockInformation info = bitItem.getBlockInformation(cost.stack());
            return BitsStashClientCache.getCount(info);
        }
        // Regular items: default inventory scan
        return AssetType.super.countAvailableClient(cost);
    }

    @Override
    public void onUse(ServerPlayer player, byte[] data) {
        if (isMultiBlock(data)) {
            // Give a Blueprint item with the structure data
            ItemStack blueprint = new ItemStack(ModItems.BLUEPRINT.get());
            blueprint.set(ModDataComponents.BLUEPRINT_DATA.get(), new dev.structurestash.item.BlueprintData(data));
            if (!player.getInventory().add(blueprint)) {
                player.drop(blueprint, false);
            }
        } else {
            // Single block: give chiseled block item
            StateEntryStorage storage = deserializeSingle(data, player.registryAccess());
            if (storage == null) {
                player.displayClientMessage(Component.literal("Invalid chiseled block data"), true);
                return;
            }
            ItemStack chiseledItem = CnBInterop.createChiseledItemStack(
                CnBInterop.getChiseledBlockItem(), storage
            );
            if (chiseledItem.isEmpty()) {
                player.displayClientMessage(Component.literal("Failed to create chiseled block"), true);
                return;
            }
            if (!player.getInventory().add(chiseledItem)) {
                player.drop(chiseledItem, false);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Format detection
    // ═══════════════════════════════════════════════════════════════

    private boolean isMultiBlock(byte[] data) {
        if (data == null || data.length == 0) return false;
        int key = Arrays.hashCode(data);
        Boolean cached = multiBlockCache.get(key);
        if (cached != null) return cached;
        try {
            CompoundTag root = NbtIo.readCompressed(
                new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            boolean result = root.contains("blocks") && root.contains("size");
            multiBlockCache.put(key, result);
            return result;
        } catch (Exception e) {
            multiBlockCache.put(key, false);
            return false;
        }
    }

    /**
     * For 1x1x1 multi-block structures, extract the single block and return a
     * preview ItemStack for native 3D rendering. Chiseled blocks use C&B's
     * item model pipeline; regular blocks return a plain ItemStack.
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

            if (CnBInterop.isChiseledBlock(state.getBlock()) && entry.contains("nbt")) {
                // Chiseled block: decode StateEntryStorage → C&B native item render
                StateEntryStorage storage = decodeStorageFromBlockEntityNbt(
                    entry.getCompound("nbt"), registries);
                if (storage == null) return Optional.empty();
                ItemStack stack = CnBInterop.createChiseledItemStack(
                    CnBInterop.getChiseledBlockItem(), storage
                );
                return stack.isEmpty() ? Optional.empty() : Optional.of(stack);
            }

            // Regular block: return a plain ItemStack
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) return Optional.empty();
            return Optional.of(new ItemStack(item));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Multi-block cost computation
    // ═══════════════════════════════════════════════════════════════

    private record CostBreakdown(
        Map<BlockInformation, Integer> bitCosts,
        Map<Item, Integer> blockCosts
    ) {}

    private CostBreakdown computeDetailedMultiBlockCost(byte[] data) {
        int key = Arrays.hashCode(data);
        CostBreakdown cached = costCache.get(key);
        if (cached != null) return cached;
        CostBreakdown result = computeDetailedMultiBlockCost(data, getRegistries());
        costCache.put(key, result);
        return result;
    }

    private CostBreakdown computeDetailedMultiBlockCost(byte[] data, HolderLookup.Provider registries) {
        Map<BlockInformation, Integer> bitCosts = new LinkedHashMap<>();
        Map<Item, Integer> blockCosts = new LinkedHashMap<>();
        if (registries == null) return new CostBreakdown(bitCosts, blockCosts);

        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());

            // Parse the StructureTemplate palette to identify blocks
            // The template format: "blocks" list entries reference "palette" by index
            // Each block entry has "state" (palette index) and optional "nbt"
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

                if (CnBInterop.isChiseledBlock(state.getBlock()) && blockEntry.contains("nbt")) {
                    // Chiseled block: decode bits from block entity NBT
                    CompoundTag beNbt = blockEntry.getCompound("nbt");
                    StateEntryStorage storage = decodeStorageFromBlockEntityNbt(beNbt, registries);
                    if (storage != null) {
                        Map<BlockInformation, Integer> bits = countBits(storage);
                        bits.forEach((info, count) -> {
                            if (!info.isAir()) bitCosts.merge(info, count, Integer::sum);
                        });
                    }
                } else {
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
            }
        } catch (Exception e) {
            StructureStash.LOGGER.error("Failed to compute multi-block cost", e);
        }

        return new CostBreakdown(bitCosts, blockCosts);
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

        // Bit costs (as C&B bit items)
        for (var entry : breakdown.bitCosts.entrySet()) {
            ItemStack bitStack = IBitItemManager.getInstance().create(entry.getKey(), entry.getValue());
            if (!bitStack.isEmpty()) {
                costs.add(new ItemCost(bitStack));
            }
        }

        // Block costs (as regular item stacks)
        for (var entry : breakdown.blockCosts.entrySet()) {
            costs.add(ItemCost.of(entry.getKey(), entry.getValue()));
        }

        return costs;
    }

    private boolean consumeMultiBlockCost(ServerPlayer player, byte[] data, int quantity) {
        CostBreakdown breakdown = computeDetailedMultiBlockCost(data, player.registryAccess());
        BitsStash stash = BitsStash.get(player);

        // Phase 1: Check ALL bits available
        for (var entry : breakdown.bitCosts.entrySet()) {
            long required = (long) entry.getValue() * quantity;
            if (stash.getCount(entry.getKey()) < required) {
                String name = entry.getKey().blockState().getBlock().getName().getString();
                player.displayClientMessage(
                    Component.literal("Not enough " + name + " bits! (need " + required + ")"), true);
                return false;
            }
        }

        // Phase 2: Check ALL blocks available in inventory
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

        // Phase 3: All checks passed — atomically deduct everything
        for (var entry : breakdown.bitCosts.entrySet()) {
            stash.consume(entry.getKey(), (long) entry.getValue() * quantity);
        }
        for (var entry : breakdown.blockCosts.entrySet()) {
            consumeInventoryItem(player, entry.getKey(), entry.getValue() * quantity);
        }

        StashNetwork.syncToClient(player);
        return true;
    }

    private boolean canAffordMultiBlockClient(byte[] data, int quantity) {
        CostBreakdown breakdown = computeDetailedMultiBlockCost(data);

        // Check bits in stash cache
        for (var entry : breakdown.bitCosts.entrySet()) {
            long required = (long) entry.getValue() * quantity;
            if (BitsStashClientCache.getCount(entry.getKey()) < required) return false;
        }

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
    //  Single-block cost (existing)
    // ═══════════════════════════════════════════════════════════════

    private List<ItemCost> computeSingleBlockCost(byte[] data) {
        StateEntryStorage storage = deserializeSingle(data);
        if (storage == null) return List.of();
        Map<BlockInformation, Integer> counts = countBits(storage);
        List<ItemCost> costs = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            if (entry.getKey().isAir()) continue;
            ItemStack bitStack = IBitItemManager.getInstance().create(entry.getKey(), entry.getValue());
            if (!bitStack.isEmpty()) costs.add(new ItemCost(bitStack));
        }
        return costs;
    }

    private boolean consumeSingleBlockCost(ServerPlayer player, byte[] data, int quantity) {
        StateEntryStorage storage = deserializeSingle(data, player.registryAccess());
        if (storage == null) {
            player.displayClientMessage(Component.literal("Invalid chiseled block data"), true);
            return false;
        }
        Map<BlockInformation, Integer> counts = countBits(storage);
        BitsStash stash = BitsStash.get(player);
        for (var entry : counts.entrySet()) {
            if (entry.getKey().isAir()) continue;
            long required = (long) entry.getValue() * quantity;
            if (stash.getCount(entry.getKey()) < required) {
                String name = entry.getKey().blockState().getBlock().getName().getString();
                player.displayClientMessage(
                    Component.literal("Not enough " + name + " bits! (need " + required + ")"), true);
                return false;
            }
        }
        for (var entry : counts.entrySet()) {
            if (entry.getKey().isAir()) continue;
            stash.consume(entry.getKey(), (long) entry.getValue() * quantity);
        }
        StashNetwork.syncToClient(player);
        return true;
    }

    private boolean canAffordSingleBlockClient(byte[] data, int quantity) {
        StateEntryStorage storage = deserializeSingle(data);
        if (storage == null) return false;
        Map<BlockInformation, Integer> counts = countBits(storage);
        for (var entry : counts.entrySet()) {
            if (entry.getKey().isAir()) continue;
            long required = (long) entry.getValue() * quantity;
            if (BitsStashClientCache.getCount(entry.getKey()) < required) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Thumbnail rendering
    // ═══════════════════════════════════════════════════════════════

    private void renderSingleBlockThumbnail(byte[] data, NativeImage target, int size) {
        StateEntryStorage storage = deserializeSingle(data);
        if (storage == null) return;
        float scale = (float) size / 16f;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockInformation topBit = null;
                for (int y = 15; y >= 0; y--) {
                    BlockInformation info = storage.getBlockInformation(x, y, z);
                    if (info != null && !info.isAir()) { topBit = info; break; }
                }
                if (topBit == null) continue;
                int color = getBlockColor(topBit);
                int abgr = argbToAbgr(color);
                int startX = (int) (x * scale);
                int startZ = (int) (z * scale);
                int endX = (int) ((x + 1) * scale);
                int endZ = (int) ((z + 1) * scale);
                for (int py = startZ; py < endZ && py < size; py++)
                    for (int px = startX; px < endX && px < size; px++)
                        target.setPixelRGBA(px, py, abgr);
            }
        }
    }

    // ── Multi-block thumbnail (GPU-accelerated) ────────────────────

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
            StructureStash.LOGGER.debug("Rendered multi-block thumbnail {}x{} in {}ms (GPU, cache miss, key={})",
                size, size, ms, Long.toHexString(cacheKey));
        } catch (Exception e) {
            StructureStash.LOGGER.error("Failed to render multi-block thumbnail", e);
        }
    }

    // ── Deferred thumbnail SPI ────────────────────────────────────────

    @Override
    public void submitDeferredThumbnail(byte[] data, NativeImage target, int size, Runnable onReady) {
        if (!isMultiBlock(data)) {
            // Single-block: instant pixel-map render
            renderSingleBlockThumbnail(data, target, size);
            onReady.run();
            return;
        }

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
        pipeline.submit(cacheKey, data, registries);
        pendingCallbacks.add(new PendingThumb(cacheKey, target, size, onReady));
    }

    @Override
    public void tickDeferredThumbnails() {
        if (pendingCallbacks.isEmpty()) return;

        DeferredThumbnailPipeline.PreparedJob job = pipeline.poll();
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
        pipeline.cancelAll();
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

        if (!isMultiBlock(data)) {
            return generateSingleBlockDebug(data, sb);
        }
        return generateMultiBlockDebug(data, sb);
    }

    private String generateSingleBlockDebug(byte[] data, StringBuilder sb) {
        sb.append("═══ Structure Analysis ═══\n");
        sb.append("format: single-block (custom C&B)\n");
        StateEntryStorage storage = deserializeSingle(data);
        if (storage == null) {
            sb.append("error: failed to decode storage\n");
            return sb.toString();
        }
        Map<BlockInformation, Integer> counts = countBits(storage);
        int totalNonAir = 0;
        for (var entry : counts.entrySet()) {
            if (!entry.getKey().isAir()) totalNonAir += entry.getValue();
        }
        sb.append("voxel_count: 4096 (16×16×16)\n");
        sb.append("non_air_voxels: ").append(totalNonAir).append("\n");
        sb.append("unique_materials: ").append(counts.size()).append("\n");
        for (var entry : counts.entrySet()) {
            String name = entry.getKey().isAir() ? "air"
                : entry.getKey().blockState().getBlock().builtInRegistryHolder()
                    .key().location().toString();
            sb.append("  ").append(name).append(" × ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    private String generateMultiBlockDebug(byte[] data, StringBuilder sb) {
        sb.append("═══ Structure Analysis ═══\n");
        sb.append("format: multi-block (StructureTemplate)\n");

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

                if (CnBInterop.isChiseledBlock(state.getBlock())) {
                    // C&B block — report decode status + material count
                    StateEntryStorage storage = CnBInterop.decodeStorage(beNbt, registries);
                    if (storage != null) {
                        Map<BlockInformation, Integer> bits = countBits(storage);
                        int uniqueNonAir = (int) bits.keySet().stream().filter(b -> !b.isAir()).count();
                        entityDetails.add(pos + " " + blockId + " — storage OK, "
                            + uniqueNonAir + " unique materials");
                    } else {
                        entityDetails.add(pos + " " + blockId + " — ⚠ DECODE FAILED");
                        anomalies.add(pos + " " + blockId + ": C&B storage decode failed");
                    }
                } else if (beNbt.contains("camo")) {
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

    public static byte[] serialize(StateEntryStorage storage, HolderLookup.Provider registries) {
        try {
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            var encoded = StateEntryStorage.CODEC.encodeStart(ops, storage).getOrThrow();
            CompoundTag root = new CompoundTag();
            root.putInt("version", 1);
            root.putString("format", "single");
            root.put("storage", encoded);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            StructureStash.LOGGER.error("Failed to serialize chiseled block asset", e);
            return new byte[0];
        }
    }

    private StateEntryStorage deserializeSingle(byte[] raw) {
        return deserializeSingle(raw, getRegistries());
    }

    private StateEntryStorage deserializeSingle(byte[] raw, HolderLookup.Provider registries) {
        if (raw == null || raw.length == 0) return null;
        if (registries == null) return null;
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(raw);
            CompoundTag root = NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            return StateEntryStorage.CODEC.parse(ops, root.get("storage")).result().orElse(null);
        } catch (Exception e) {
            StructureStash.LOGGER.error("Failed to deserialize single-block asset", e);
            return null;
        }
    }

    /**
     * Decode StateEntryStorage from a C&B block entity's NBT ("data" sub-tag).
     * Delegates to {@link CnBInterop#decodeStorage(CompoundTag, HolderLookup.Provider)}.
     */
    private StateEntryStorage decodeStorageFromBlockEntityNbt(CompoundTag beNbt, HolderLookup.Provider registries) {
        return CnBInterop.decodeStorage(beNbt, registries);
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

    private static Map<BlockInformation, Integer> countBits(StateEntryStorage storage) {
        Map<BlockInformation, Integer> counts = new LinkedHashMap<>();
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++) {
                    BlockInformation info = storage.getBlockInformation(x, y, z);
                    if (info != null && !info.isAir()) counts.merge(info, 1, Integer::sum);
                }
        return counts;
    }

    private static int getBlockColor(BlockInformation info) {
        try {
            int mapColor = info.blockState().getMapColor(null, null).col;
            if (mapColor == 0) return 0xFF808080;
            return 0xFF000000 | mapColor;
        } catch (Exception e) {
            return 0xFF808080;
        }
    }

    private static int argbToAbgr(int argb) {
        return ((argb & 0xFF000000))
             | ((argb & 0x00FF0000) >> 16)
             | ((argb & 0x0000FF00))
             | ((argb & 0x000000FF) << 16);
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
