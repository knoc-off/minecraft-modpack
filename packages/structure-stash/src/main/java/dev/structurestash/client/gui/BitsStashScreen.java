package dev.structurestash.client.gui;

import dev.structurestash.client.BitsStashClientCache;
import dev.structurestash.network.StashDepositPayload;
import dev.structurestash.network.StashWithdrawPayload;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.api.chiseling.eligibility.IEligibilityManager;
import mod.chiselsandbits.api.item.bit.IBitItem;
import mod.chiselsandbits.api.item.chiseled.IChiseledBlockItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Bits Stash screen — vanilla chest-style UI.
 * Top: stash grid (9×7 scrollable). Bottom: player inventory.
 * Click inventory slot to deposit, click stash slot to withdraw.
 */
public class BitsStashScreen extends Screen {

    // ── Vanilla palette ────────────────────────────────────────────────
    private static final int PANEL_BG        = 0xFFC6C6C6;
    private static final int PANEL_BORDER    = 0xFF000000;
    private static final int PANEL_HIGHLIGHT = 0xFFFFFFFF;
    private static final int PANEL_SHADOW    = 0xFF555555;
    private static final int SLOT_BG         = 0xFF8B8B8B;
    private static final int SLOT_DARK       = 0xFF373737;
    private static final int SLOT_LIGHT      = 0xFFFFFFFF;
    private static final int LABEL_COLOR     = 0x404040;
    private static final int SLOT_HOVER      = 0x80FFFFFF;
    private static final int SORT_ACTIVE     = 0xFFD4A858;
    private static final int SORT_INACTIVE   = 0xFF8B8B8B;
    private static final int HOTBAR_TINT     = 0x18FFFFFF;
    private static final int SEARCH_BG       = 0xFF373737;
    private static final int GRAYED_OVERLAY  = 0xA0404040;

    // ── Layout constants ───────────────────────────────────────────────
    private static final int COLS = 9;
    private static final int STASH_ROWS = 7;
    private static final int SLOT = 18;
    private static final int PAD = 8;
    private static final int HEADER_H = 30;
    private static final int PANEL_W = PAD + COLS * SLOT + PAD + 4; // 8+162+8+4 = 182
    private static final int INV_LABEL_H = 14;
    private static final int INV_GAP = 6;
    private static final int HOTBAR_GAP = 4;
    private static final int BOTTOM_PAD = 8;

    // Total height: header + stash + gap + inv_label + 3 rows + hotbar_gap + 1 row + bottom
    private static final int PANEL_H = HEADER_H + STASH_ROWS * SLOT + INV_GAP + INV_LABEL_H
        + 3 * SLOT + HOTBAR_GAP + SLOT + BOTTOM_PAD;

    // ── Sort modes ─────────────────────────────────────────────────────
    private enum SortMode { RECENT, MOST, AZ }
    private SortMode sortMode = SortMode.RECENT;

    // ── State ──────────────────────────────────────────────────────────
    private int scrollOffset = 0;
    private List<StashEntry> entries = new ArrayList<>();
    private List<StashEntry> filteredEntries = new ArrayList<>();
    private EditBox searchBox;
    private int panelX, panelY;

    // Tooltip
    private List<Component> hoverTooltip = null;
    private int hoverTooltipX, hoverTooltipY;

    private record StashEntry(BlockInformation info, long count, long modified, String name) {}

    public BitsStashScreen() {
        super(Component.literal("Bits Stash"));
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        String prev = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(font, panelX + PANEL_W - PAD - 80, panelY + 5, 78, 12,
            Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(32);
        searchBox.setValue(prev);
        searchBox.setHint(Component.literal("\u2315 search..."));
        searchBox.setTextColor(0xFFFFFFFF);
        searchBox.setResponder(s -> { scrollOffset = 0; rebuildFiltered(); });
        addRenderableWidget(searchBox);

        rebuildEntries();
    }

    private void rebuildEntries() {
        entries.clear();
        Map<BlockInformation, Long> all = BitsStashClientCache.getAll();
        Map<BlockInformation, Long> mods = BitsStashClientCache.getAllLastModified();
        for (var e : all.entrySet()) {
            String name = e.getKey().blockState().getBlock().getName().getString();
            entries.add(new StashEntry(e.getKey(), e.getValue(),
                mods.getOrDefault(e.getKey(), 0L), name));
        }
        rebuildFiltered();
    }

    private void rebuildFiltered() {
        String filter = searchBox != null ? searchBox.getValue().trim().toLowerCase() : "";
        filteredEntries.clear();
        for (StashEntry e : entries) {
            if (filter.isEmpty() || e.name.toLowerCase().contains(filter)) {
                filteredEntries.add(e);
            }
        }
        // Sort
        switch (sortMode) {
            case RECENT -> filteredEntries.sort((a, b) -> Long.compare(b.modified, a.modified));
            case MOST -> filteredEntries.sort((a, b) -> Long.compare(b.count, a.count));
            case AZ -> filteredEntries.sort(Comparator.comparing(e -> e.name.toLowerCase()));
        }
        // Clamp scroll
        int maxScroll = Math.max(0, filteredEntries.size() - STASH_ROWS * COLS);
        int maxScrollRows = (maxScroll + COLS - 1) / COLS;
        if (scrollOffset > maxScrollRows) scrollOffset = maxScrollRows;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (BitsStashClientCache.isDirtyAndClear()) rebuildEntries();
        hoverTooltip = null;

        renderTransparentBackground(g);
        renderPanel(g);
        renderHeader(g, mx, my);
        renderStashGrid(g, mx, my);
        renderInventory(g, mx, my);

        // Render search box on top
        super.render(g, mx, my, pt);

        // Tooltip last (on top of everything)
        if (hoverTooltip != null) {
            renderVanillaTooltip(g, hoverTooltip, hoverTooltipX, hoverTooltipY);
        }
    }

    // ── Panel frame (vanilla 3D bevel) ─────────────────────────────────

    private void renderPanel(GuiGraphics g) {
        int x = panelX, y = panelY, w = PANEL_W, h = PANEL_H;
        // Outer black border
        g.fill(x, y, x + w, y + h, PANEL_BORDER);
        // White highlight (top + left inner)
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_HIGHLIGHT);
        // Shadow overwrite (bottom + right inner)
        g.fill(x + 2, y + 2, x + w - 1, y + h - 1, PANEL_SHADOW);
        // Panel fill
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, PANEL_BG);
    }

    // ── Header ─────────────────────────────────────────────────────────

    private void renderHeader(GuiGraphics g, int mx, int my) {
        int x = panelX, y = panelY;

        // Title
        g.drawString(font, "Bits Stash", x + PAD + 1, y + 6, LABEL_COLOR, false);

        // Stats
        long total = 0;
        for (StashEntry e : entries) total += e.count;
        String stats = formatCount(total) + " bits \u00b7 " + entries.size() + " types";
        int statsX = x + PAD + 1 + font.width("Bits Stash") + 8;
        g.drawString(font, stats, statsX, y + 6, SORT_INACTIVE, false);

        // Search box background
        int sbx = searchBox.getX() - 2, sby = searchBox.getY() - 2;
        int sbw = searchBox.getWidth() + 4, sbh = searchBox.getHeight() + 4;
        g.fill(sbx, sby, sbx + sbw, sby + sbh, SEARCH_BG);
        g.renderOutline(sbx, sby, sbw, sbh, PANEL_BORDER);

        // Sort tabs
        String[] labels = {"Recent", "Most", "A-Z"};
        SortMode[] modes = {SortMode.RECENT, SortMode.MOST, SortMode.AZ};
        int tabY = y + 18;
        int tabX = x + PAD + 1;
        for (int i = 0; i < labels.length; i++) {
            int tw = font.width(labels[i]);
            boolean active = sortMode == modes[i];
            boolean hov = mx >= tabX && mx < tabX + tw + 4 && my >= tabY && my < tabY + 10;
            int col = active ? SORT_ACTIVE : (hov ? 0xFFCCCCCC : SORT_INACTIVE);
            g.drawString(font, labels[i], tabX + 2, tabY + 1, col, false);
            if (active) {
                g.fill(tabX, tabY + 10, tabX + tw + 4, tabY + 11, SORT_ACTIVE);
            }
            tabX += tw + 8;
        }
    }

    // ── Stash grid ─────────────────────────────────────────────────────

    private void renderStashGrid(GuiGraphics g, int mx, int my) {
        int gridX = panelX + PAD;
        int gridY = panelY + HEADER_H;

        int startIdx = scrollOffset * COLS;

        // Pass 1: render slots and items
        for (int row = 0; row < STASH_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = startIdx + row * COLS + col;
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;

                renderSlot(g, sx, sy);

                if (idx < filteredEntries.size()) {
                    StashEntry entry = filteredEntries.get(idx);
                    Block block = entry.info.blockState().getBlock();
                    ItemStack icon = new ItemStack(block.asItem());
                    if (!icon.isEmpty()) {
                        g.renderItem(icon, sx + 1, sy + 1);
                    }
                }
            }
        }

        // Pass 2: render counts on top (z=200 to be above items)
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        for (int row = 0; row < STASH_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = startIdx + row * COLS + col;
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;

                if (idx < filteredEntries.size()) {
                    StashEntry entry = filteredEntries.get(idx);
                    String countStr = formatCountShort(entry.count);
                    int tw = font.width(countStr);
                    g.drawString(font, countStr, sx + 17 - tw, sy + 9, 0xFFFFFFFF, true);
                }
            }
        }
        g.pose().popPose();

        // Pass 3: hover highlights
        for (int row = 0; row < STASH_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = startIdx + row * COLS + col;
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;

                if (idx < filteredEntries.size()) {
                    if (mx >= sx + 1 && mx < sx + 17 && my >= sy + 1 && my < sy + 17) {
                        g.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_HOVER);
                        setStashTooltip(filteredEntries.get(idx), mx, my);
                    }
                }
            }
        }

        // Scrollbar (right edge, if needed)
        int totalRows = (filteredEntries.size() + COLS - 1) / COLS;
        if (totalRows > STASH_ROWS) {
            int sbX = gridX + COLS * SLOT + 1;
            int sbH = STASH_ROWS * SLOT;
            int thumbH = Math.max(8, sbH * STASH_ROWS / totalRows);
            int maxScroll = totalRows - STASH_ROWS;
            int thumbY = gridY + (sbH - thumbH) * scrollOffset / Math.max(1, maxScroll);
            g.fill(sbX, gridY, sbX + 2, gridY + sbH, PANEL_SHADOW);
            g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, SLOT_DARK);
        }
    }

    // ── Player inventory ───────────────────────────────────────────────

    private void renderInventory(GuiGraphics g, int mx, int my) {
        int invX = panelX + PAD;
        int invY = panelY + HEADER_H + STASH_ROWS * SLOT + INV_GAP;

        // "Inventory" label
        g.drawString(font, "Inventory", invX + 1, invY + 1, LABEL_COLOR, false);
        invY += INV_LABEL_H;

        if (minecraft == null || minecraft.player == null) return;
        var inv = minecraft.player.getInventory();

        // Main inventory (slots 9-35 → 3 rows of 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int sx = invX + col * SLOT;
                int sy = invY + row * SLOT;
                renderInventorySlot(g, sx, sy, inv.getItem(slot), slot, mx, my);
            }
        }

        // Hotbar (slots 0-8)
        int hbY = invY + 3 * SLOT + HOTBAR_GAP;
        g.fill(invX - 1, hbY - 1, invX + 9 * SLOT + 1, hbY + SLOT + 1, HOTBAR_TINT);
        for (int col = 0; col < 9; col++) {
            int sx = invX + col * SLOT;
            renderInventorySlot(g, sx, hbY, inv.getItem(col), col, mx, my);
        }
    }

    private void renderInventorySlot(GuiGraphics g, int sx, int sy, ItemStack stack,
                                     int slot, int mx, int my) {
        renderSlot(g, sx, sy);

        if (!stack.isEmpty()) {
            boolean depositable = isDepositable(stack);

            g.renderItem(stack, sx + 1, sy + 1);
            if (!depositable) {
                g.fill(sx + 1, sy + 1, sx + 17, sy + 17, GRAYED_OVERLAY);
            }

            // Stack count (z=200 to render above item)
            if (stack.getCount() > 1) {
                String cs = String.valueOf(stack.getCount());
                int tw = font.width(cs);
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                g.drawString(font, cs, sx + 17 - tw, sy + 9, 0xFFFFFFFF, true);
                g.pose().popPose();
            }

            // Hover
            if (mx >= sx + 1 && mx < sx + 17 && my >= sy + 1 && my < sy + 17) {
                g.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_HOVER);
                if (depositable) {
                    setDepositTooltip(stack, mx, my);
                }
            }
        }
    }

    // ── Slot rendering (vanilla 3D inset) ──────────────────────────────

    private void renderSlot(GuiGraphics g, int sx, int sy) {
        // Top edge (dark)
        g.fill(sx, sy, sx + 17, sy + 1, SLOT_DARK);
        // Left edge (dark)
        g.fill(sx, sy + 1, sx + 1, sy + 17, SLOT_DARK);
        // Bottom edge (white)
        g.fill(sx + 1, sy + 17, sx + 18, sy + 18, SLOT_LIGHT);
        // Right edge (white)
        g.fill(sx + 17, sy + 1, sx + 18, sy + 17, SLOT_LIGHT);
        // Interior (gray)
        g.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_BG);
        // Corner pixels
        g.fill(sx, sy + 17, sx + 1, sy + 18, SLOT_BG);
        g.fill(sx + 17, sy, sx + 18, sy + 1, SLOT_BG);
    }

    // ── Tooltips (vanilla style) ───────────────────────────────────────

    private void setStashTooltip(StashEntry entry, int mx, int my) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(entry.name));
        long blocks = entry.count / 4096;
        long remainder = entry.count % 4096;
        String countLine = String.format("%,d bits", entry.count);
        if (blocks > 0) {
            countLine += " (" + blocks + " block" + (blocks != 1 ? "s" : "");
            if (remainder > 0) countLine += " + " + remainder;
            countLine += ")";
        }
        lines.add(Component.literal(countLine).withStyle(s -> s.withColor(0xAAAAAA)));
        lines.add(Component.empty());
        lines.add(Component.literal("Click: withdraw 64").withStyle(s -> s.withColor(0x707070)));
        lines.add(Component.literal("Right-click: withdraw 1").withStyle(s -> s.withColor(0x707070)));
        hoverTooltip = lines;
        hoverTooltipX = mx;
        hoverTooltipY = my;
    }

    private void setDepositTooltip(ItemStack stack, int mx, int my) {
        List<Component> lines = new ArrayList<>();
        lines.add(stack.getHoverName());

        // Show conversion
        long bitsPerItem = 0;
        if (stack.getItem() instanceof IBitItem) {
            bitsPerItem = 1;
        } else if (stack.getItem() instanceof IChiseledBlockItem) {
            bitsPerItem = 4096; // approximate
        } else if (stack.getItem() instanceof BlockItem) {
            bitsPerItem = 4096;
        }
        if (bitsPerItem > 0) {
            long totalBits = bitsPerItem * stack.getCount();
            lines.add(Component.literal("\u2192 " + formatCount(totalBits) + " bits")
                .withStyle(s -> s.withColor(0xD4A858)));
        }

        lines.add(Component.empty());
        lines.add(Component.literal("Click: deposit 1").withStyle(s -> s.withColor(0x707070)));
        lines.add(Component.literal("Shift-click: deposit all").withStyle(s -> s.withColor(0x707070)));
        hoverTooltip = lines;
        hoverTooltipX = mx;
        hoverTooltipY = my;
    }

    private void renderVanillaTooltip(GuiGraphics g, List<Component> lines, int mx, int my) {
        if (lines.isEmpty()) return;
        // Use MC's built-in tooltip rendering for proper positioning and style
        g.renderComponentTooltip(font, lines, mx, my);
    }

    // ── Input ──────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0 && btn != 1) return super.mouseClicked(mx, my, btn);

        boolean shift = hasShiftDown();

        // Sort tabs
        if (clickSortTabs(mx, my)) return true;

        // Stash grid clicks (withdraw)
        if (clickStashGrid(mx, my, btn, shift)) return true;

        // Inventory clicks (deposit)
        if (clickInventory(mx, my, btn, shift)) return true;

        return super.mouseClicked(mx, my, btn);
    }

    private boolean clickSortTabs(double mx, double my) {
        int tabY = panelY + 18;
        int tabX = panelX + PAD + 1;
        String[] labels = {"Recent", "Most", "A-Z"};
        SortMode[] modes = {SortMode.RECENT, SortMode.MOST, SortMode.AZ};
        for (int i = 0; i < labels.length; i++) {
            int tw = font.width(labels[i]) + 4;
            if (mx >= tabX && mx < tabX + tw && my >= tabY && my < tabY + 11) {
                sortMode = modes[i];
                scrollOffset = 0;
                rebuildFiltered();
                return true;
            }
            tabX += tw + 4;
        }
        return false;
    }

    private boolean clickStashGrid(double mx, double my, int btn, boolean shift) {
        int gridX = panelX + PAD;
        int gridY = panelY + HEADER_H;

        if (mx < gridX || mx >= gridX + COLS * SLOT || my < gridY || my >= gridY + STASH_ROWS * SLOT)
            return false;

        int col = (int)(mx - gridX) / SLOT;
        int row = (int)(my - gridY) / SLOT;
        int idx = scrollOffset * COLS + row * COLS + col;

        if (idx < 0 || idx >= filteredEntries.size()) return false;

        StashEntry entry = filteredEntries.get(idx);
        int qty;
        if (btn == 1) {
            qty = 1;
        } else {
            qty = (int) Math.min(64, entry.count);
        }

        if (qty > 0) {
            PacketDistributor.sendToServer(new StashWithdrawPayload(entry.info, qty));
        }
        return true;
    }

    private boolean clickInventory(double mx, double my, int btn, boolean shift) {
        if (minecraft == null || minecraft.player == null) return false;

        int invX = panelX + PAD;
        int invY = panelY + HEADER_H + STASH_ROWS * SLOT + INV_GAP + INV_LABEL_H;
        int hbY = invY + 3 * SLOT + HOTBAR_GAP;

        // Main inventory (slots 9-35)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int sx = invX + col * SLOT;
                int sy = invY + row * SLOT;
                if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) {
                    return tryDepositClick(slot, shift);
                }
            }
        }

        // Hotbar (slots 0-8)
        for (int col = 0; col < 9; col++) {
            int sx = invX + col * SLOT;
            if (mx >= sx && mx < sx + SLOT && my >= hbY && my < hbY + SLOT) {
                return tryDepositClick(col, shift);
            }
        }

        return false;
    }

    private boolean tryDepositClick(int slot, boolean fullStack) {
        if (minecraft == null || minecraft.player == null) return false;
        ItemStack stack = minecraft.player.getInventory().getItem(slot);
        if (stack.isEmpty() || !isDepositable(stack)) return false;
        PacketDistributor.sendToServer(new StashDepositPayload(slot, fullStack));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Let search box consume input first
        if (searchBox != null && searchBox.isFocused()) {
            if (key == 256) { // Escape
                searchBox.setFocused(false);
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }

        // Number keys 1-9 deposit from hotbar (shift = full stack)
        if (key >= 49 && key <= 57) {
            int slot = key - 49;
            boolean shift = hasShiftDown();
            tryDepositClick(slot, shift);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int gridX = panelX + PAD;
        int gridY = panelY + HEADER_H;

        // Scroll stash grid
        if (mx >= gridX && mx < gridX + COLS * SLOT && my >= gridY && my < gridY + STASH_ROWS * SLOT) {
            int totalRows = (filteredEntries.size() + COLS - 1) / COLS;
            int maxScroll = Math.max(0, totalRows - STASH_ROWS);
            if (dy < 0 && scrollOffset < maxScroll) {
                scrollOffset++;
                return true;
            }
            if (dy > 0 && scrollOffset > 0) {
                scrollOffset--;
                return true;
            }
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // Skip default — we draw our own
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static boolean isDepositable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof IBitItem) return true;
        if (stack.getItem() instanceof IChiseledBlockItem) return true;
        if (stack.getItem() instanceof BlockItem) {
            return IEligibilityManager.getInstance().canBeChiseled(stack);
        }
        return false;
    }

    private static String formatCount(long count) {
        if (count >= 1_000_000) return String.format("%.1fM", count / 1_000_000.0);
        if (count >= 1_000) return String.format("%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }

    private static String formatCountShort(long count) {
        if (count >= 10_000_000) return String.format("%.0fM", count / 1_000_000.0);
        if (count >= 1_000_000) return String.format("%.1fM", count / 1_000_000.0);
        if (count >= 100_000) return String.format("%.0fK", count / 1_000.0);
        if (count >= 10_000) return String.format("%.1fK", count / 1_000.0);
        if (count >= 1_000) return String.format("%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }
}
