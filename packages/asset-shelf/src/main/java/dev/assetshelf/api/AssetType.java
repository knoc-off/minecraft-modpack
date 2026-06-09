package dev.assetshelf.api;

import com.mojang.blaze3d.platform.NativeImage;
import dev.assetshelf.client.gui.ModalExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Interface that content mods implement to register their asset type with Asset Shelf.
 * Each content mod provides serialization, thumbnail rendering, and item creation.
 */
public interface AssetType {

    /** Unique identifier for this asset type (e.g., "paintcraft:painting"). */
    ResourceLocation id();

    /** Display name shown in the browser UI. */
    Component displayName();

    /**
     * Icon texture for binder tabs (16x16 PNG).
     * Return a ResourceLocation pointing to the mod's GUI icon texture,
     * e.g. ResourceLocation.fromNamespaceAndPath("paintcraft", "textures/gui/icon.png").
     * Returns null by default (falls back to vertical text).
     */
    @Nullable
    default ResourceLocation icon() {
        return null;
    }

    /**
     * Accent color (ARGB) for binder tabs, color bands, and highlights.
     * Default implementation derives a warm hue from the namespace hash.
     */
    default int accentColor() {
        int hash = Math.abs(id().getNamespace().hashCode());
        int[][] presets = {
            {0xC4, 0x78, 0x40}, // warm orange
            {0x6A, 0xAF, 0xCF}, // blue
            {0x72, 0xBA, 0x5A}, // green
            {0xC0, 0x80, 0xCF}, // purple
            {0xCF, 0x6A, 0x6A}, // red
            {0x6A, 0xCF, 0xA0}, // teal
        };
        int[] c = presets[hash % presets.length];
        return 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    /**
     * Client-side: render a thumbnail of the asset into the target image.
     * The image is pre-allocated at size×size pixels in ABGR format.
     */
    void renderThumbnail(byte[] data, NativeImage target, int size);

    /**
     * Server-side: called when a player "uses" an asset.
     * Implementation should create the appropriate item and add it to the player's inventory.
     */
    void onUse(ServerPlayer player, byte[] data);

    /**
     * Compute the material cost for producing one instance of this asset.
     * Returns empty list = free. Called on both client (for UI) and server (for validation).
     */
    default List<ItemCost> computeCost(byte[] data) {
        return List.of();
    }

    /**
     * Client-side: optionally provide a modal extension section for save/publish dialogs.
     * The extension is rendered as an expandable inset card in the SaveAssetScreen.
     *
     * @param data      raw asset bytes
     * @param isPublish true if this is the publish dialog, false for save-local
     * @return extension instance, or null for no mod-specific section
     */
    @Nullable
    default ModalExtension createModalExtension(byte[] data, boolean isPublish) {
        return null;
    }

    /**
     * Client-side: optionally provide an ItemStack for 3D GUI rendering.
     * When non-empty, the browser uses GuiGraphics.renderItem() instead of NativeImage blitting.
     * Override for asset types that represent 3D blocks/items (e.g., chiseled blocks).
     */
    default Optional<ItemStack> getPreviewStack(byte[] data) {
        return Optional.empty();
    }

    /**
     * Server-side: validate the player can afford and consume the cost for the given quantity.
     * Default implementation checks player inventory using computeCost() with component-aware matching.
     * Override for custom cost systems (e.g., bits stash).
     *
     * @return true if cost was successfully consumed, false if player can't afford
     */
    default boolean consumeCost(ServerPlayer player, byte[] data, int quantity) {
        List<ItemCost> costs = computeCost(data);
        if (costs.isEmpty()) return true;

        // Check inventory has enough (component-aware)
        for (ItemCost cost : costs) {
            long required = (long) cost.count() * quantity;
            long have = countMatchingItems(player, cost);
            if (have < required) {
                player.displayClientMessage(
                    Component.literal("Not enough " +
                        cost.stack().getHoverName().getString() + "!"), true);
                return false;
            }
        }

        // Consume items
        for (ItemCost cost : costs) {
            int remaining = (int) Math.min((long) cost.count() * quantity, Integer.MAX_VALUE);
            for (int i = 0; i < player.getInventory().items.size() && remaining > 0; i++) {
                ItemStack slot = player.getInventory().items.get(i);
                if (matchesSlot(slot, cost)) {
                    int take = Math.min(slot.getCount(), remaining);
                    slot.shrink(take);
                    remaining -= take;
                }
            }
        }

        return true;
    }

    /**
     * Client-side: count how many of a specific cost item the player currently has.
     * Default scans player inventory. Override for custom stash systems.
     */
    default long countAvailableClient(ItemCost cost) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        long have = 0;
        for (ItemStack slot : mc.player.getInventory().items) {
            if (matchesSlot(slot, cost)) have += slot.getCount();
        }
        return have;
    }

    /**
     * Client-side: check if the local player can afford this asset at the given quantity.
     * Default checks inventory against computeCost() with component-aware matching.
     * Override for custom stash-based cost systems.
     */
    default boolean canAffordClient(byte[] data, int quantity) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        List<ItemCost> costs = computeCost(data);
        if (costs.isEmpty()) return true;

        for (ItemCost cost : costs) {
            long required = (long) cost.count() * quantity;
            long have = 0;
            for (ItemStack slot : mc.player.getInventory().items) {
                if (matchesSlot(slot, cost)) {
                    have += slot.getCount();
                }
            }
            if (have < required) return false;
        }
        return true;
    }

    // ── Deferred thumbnail rendering ─────────────────────────────────

    /**
     * Submit a thumbnail for potentially deferred rendering.
     * <p>
     * The target NativeImage is pre-filled with a placeholder color by the browser.
     * Implementations may either fill it immediately (synchronous) or schedule
     * background preparation and fill it later. The {@code onReady} callback must
     * be called <b>on the render thread</b> once the target has been written — the
     * browser uses it to upload the texture to the GPU.
     * <p>
     * Default: renders synchronously via {@link #renderThumbnail} and calls
     * {@code onReady} immediately.
     *
     * @param data    raw asset bytes
     * @param target  pre-allocated NativeImage (may already be displayed as placeholder)
     * @param size    thumbnail size in pixels
     * @param onReady callback to run on the render thread when pixels are ready
     */
    default void submitDeferredThumbnail(byte[] data, NativeImage target, int size, Runnable onReady) {
        renderThumbnail(data, target, size);
        onReady.run();
    }

    /**
     * Drive any deferred thumbnail render queue. Called once per frame while the
     * browser screen is open. Implementations should render at most one queued
     * thumbnail per call to avoid frame stutter.
     * <p>
     * Default: no-op.
     */
    default void tickDeferredThumbnails() {}

    /**
     * Cancel all pending deferred thumbnails. Called when the browser changes page
     * or closes — stale callbacks must not fire on released textures.
     * <p>
     * Default: no-op.
     */
    default void cancelDeferredThumbnails() {}

    // ── Debug ────────────────────────────────────────────────────────

    /**
     * Generate type-specific debug diagnostics for the given asset data.
     * Called when the user presses Ctrl+D in the browser detail view.
     * The returned string is appended to the generic metadata section.
     * <p>
     * Default: empty string (no type-specific info).
     */
    default String generateDebugInfo(byte[] data) { return ""; }

    private static long countMatchingItems(ServerPlayer player, ItemCost cost) {
        long count = 0;
        for (ItemStack slot : player.getInventory().items) {
            if (matchesSlot(slot, cost)) {
                count += slot.getCount();
            }
        }
        return count;
    }

    private static boolean matchesSlot(ItemStack slot, ItemCost cost) {
        if (slot.isEmpty()) return false;
        if (cost.hasComponents()) {
            return ItemStack.isSameItemSameComponents(slot, cost.stack());
        } else {
            return slot.is(cost.item());
        }
    }
}
