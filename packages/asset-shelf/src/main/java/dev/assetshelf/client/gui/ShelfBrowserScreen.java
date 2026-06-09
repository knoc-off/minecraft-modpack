package dev.assetshelf.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.assetshelf.api.AssetShelfApi;
import dev.assetshelf.api.AssetType;
import dev.assetshelf.api.ItemCost;
import dev.assetshelf.core.AssetMeta;
import dev.assetshelf.network.*;
import dev.assetshelf.storage.LocalLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Asset Shelf browser — book/binder metaphor.
 *
 * Layout: dark title bar → binder tabs (left) → cream book (header + two-page spread).
 * Left page: contact-sheet grid.  Right page: detail view of selected asset.
 */
public class ShelfBrowserScreen extends Screen {

    // ── Palette: cream book on leather cover ──────────────────────────
    static final int TITLE_BAR   = ShelfPalette.TITLE_BAR;
    static final int TITLE_TEXT  = ShelfPalette.TITLE_TEXT;
    static final int BINDER_BG   = ShelfPalette.BINDER_BG;
    static final int BINDER_INACT= ShelfPalette.BINDER_INACT;
    static final int PAPER       = ShelfPalette.PAPER;
    static final int PAPER_WARM  = ShelfPalette.PAPER_WARM;
    static final int HEADER_BG   = ShelfPalette.HEADER_BG;
    static final int CARD_BG     = ShelfPalette.CARD_BG;
    static final int INK         = ShelfPalette.INK;
    static final int INK_DIM     = ShelfPalette.INK_DIM;
    static final int INK_FAINT   = ShelfPalette.INK_FAINT;
    static final int RULE        = ShelfPalette.RULE;
    static final int RULE_DARK   = ShelfPalette.RULE_DARK;
    static final int BTN_GOLD    = ShelfPalette.BTN_GOLD;
    static final int BTN_GOLD_HVR= ShelfPalette.BTN_GOLD_HVR;
    static final int LEATHER     = ShelfPalette.LEATHER;
    static final int LEATHER_LITE= ShelfPalette.LEATHER_LITE;
    static final int LEATHER_DARK= ShelfPalette.LEATHER_DARK;
    static final int COVER_EDGE  = ShelfPalette.COVER_EDGE;

    // ── Layout constants ──
    static final int TITLE_H     = 12;
    static final int BINDER_W    = 28;
    static final int HEADER_H    = 20;
    static final int BAND_H      = 3;   // accent color band
    static final int PAD         = 6;
    static final int PAGE_SIZE   = 16;  // 4×4 grid per page
    static final int MARGIN      = 24;  // inset from screen edge
    static final int COVER_PAD   = 4;   // leather border thickness around content

    // ── Session-persistent state (survives screen close/reopen) ──
    private static ResourceLocation savedTypeId;
    private static Tab savedTab = Tab.LOCAL;
    private static int savedPage = 0;
    private static int savedSelectedIndex = -1;
    private static int savedQuantity = 1;
    private static String savedSearch = "";
    private static List<String> savedTagFilters = new ArrayList<>();

    // ── State ──
    private enum Tab { SERVER, LOCAL }
    private Tab activeTab = savedTab;
    private int currentPage = savedPage;
    private int totalCount = 0;
    private boolean awaitingServer = false;
    private int selectedQuantity = savedQuantity;
    private boolean needsDataLoad = true;
    private final List<String> activeTagFilters = new ArrayList<>(savedTagFilters);

    private final List<DisplayAsset> displayAssets = new ArrayList<>();
    private Set<UUID> localAssetIds = Set.of();
    private int selectedIndex = savedSelectedIndex;

    // Active type (binder tab selection)
    private ResourceLocation activeTypeId = savedTypeId;

    // Computed layout regions
    private int outerL, outerR, outerT, outerB;
    private int contentL, contentR, contentT, contentB;
    private int bookL, bookR, bookT, bookB;
    private int headerT, headerB;
    private int leftL, leftR, rightL, rightR;
    private int spineX;
    private int gridTop;
    private int cols, cardW, cardH, thumbH;

    // Thumbnails
    private final List<ThumbEntry> thumbnails = new ArrayList<>();
    private final List<ItemStack> previewStacks = new ArrayList<>();
    private DynamicTexture detailTex;
    private ResourceLocation detailLoc;
    private int detailSize;
    private ItemStack detailPreviewStack = ItemStack.EMPTY;
    private byte[] detailDataRef; // tracks which data the detail was built from

    // Tag click regions: set during renderRightPage, used in mouseClicked
    private int detailTagsX, detailTagsY, detailTagsW;
    private List<String> detailTagsList = List.of();

    // Active filter chip region: set during renderLeftPage
    private int filterChipsX, filterChipsY, filterChipsW;
    private List<String> filterChipsList = List.of();

    // Cached cost/affordability — recomputed only on selection or quantity change
    private byte[] costDataRef;
    private int costQuantityRef = -1;
    private List<ItemCost> cachedCost = List.of();
    private long[] cachedHave = new long[0]; // per-entry available count
    private boolean cachedCanAfford = true;

    // Widgets
    private EditBox searchBox;

    private record DisplayAsset(AssetMeta meta, byte[] data, boolean isLocal, boolean published) {}
    private record ThumbEntry(DynamicTexture tex, ResourceLocation loc, int size) {}

    public ShelfBrowserScreen() {
        super(Component.literal("Asset Shelf"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  init
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        // Pick the first registered type if none selected
        if (activeTypeId == null) {
            AssetType first = getActiveType();
            if (first != null) activeTypeId = first.id();
        }

        // Outer frame (leather cover outer edge)
        outerL = MARGIN;
        outerR = this.width - MARGIN;
        outerT = MARGIN;
        outerB = this.height - MARGIN;

        // Content area inside the leather cover
        contentL = outerL + COVER_PAD;
        contentR = outerR - COVER_PAD;
        contentT = outerT + COVER_PAD;
        contentB = outerB - COVER_PAD;

        // Book region (pages area, right of binder, below title bar)
        bookL = contentL + BINDER_W;
        bookR = contentR;
        bookT = contentT + TITLE_H;
        bookB = contentB;
        headerT = bookT;
        headerB = bookT + HEADER_H;

        // Two-page split — always allocate both pages
        int bookW = bookR - bookL;
        leftL = bookL;
        leftR = bookL + bookW * 55 / 100;
        spineX = leftR;
        rightL = spineX;
        rightR = bookR;

        // Grid layout
        int pageContentTop = headerB + BAND_H + 22; // band + type label row
        gridTop = pageContentTop;
        int gridW = leftR - leftL - PAD * 3;
        cols = Math.max(1, Math.min(4, gridW / 60));
        cardW = (gridW - (cols - 1) * PAD) / cols;
        thumbH = (int)(cardW * 0.8f);
        cardH = thumbH + 20; // name + author

        // Search box (in header, right side)
        int searchW = Math.min(100, bookW / 4);
        String prevSearch = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(this.font, bookR - searchW - PAD, headerT + 4, searchW, HEADER_H - 8,
            Component.literal("Search"));
        searchBox.setHint(Component.literal("search..."));
        searchBox.setMaxLength(64);
        searchBox.setBordered(true);
        searchBox.setValue(prevSearch.isEmpty() ? savedSearch : prevSearch);
        searchBox.setResponder(s -> { currentPage = 0; saveSession(); loadCurrentPage(); });
        addRenderableWidget(searchBox);

        if (needsDataLoad) {
            loadCurrentPage();
            needsDataLoad = false;
        }
        rebuildDetailTex();
    }

    // ═══════════════════════════════════════════════════════════════
    //  render
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // Drive deferred thumbnail pipeline (renders at most one per frame)
        AssetType type = getActiveType();
        if (type != null) type.tickDeferredThumbnails();

        // 1. Dim game world (same as vanilla book)
        this.renderTransparentBackground(g);

        // 2. Book drop shadow
        g.fill(outerL + 3, outerT + 3, outerR + 3, outerB + 3, 0x80000000);

        // 3. Leather cover
        g.fill(outerL, outerT, outerR, outerB, LEATHER);
        g.renderOutline(outerL, outerT, outerR - outerL, outerB - outerT, COVER_EDGE);
        // Bevel: lighter top/left, darker bottom/right
        g.fill(outerL + 1, outerT + 1, outerR - 1, outerT + 2, LEATHER_LITE);
        g.fill(outerL + 1, outerT + 1, outerL + 2, outerB - 1, LEATHER_LITE);
        g.fill(outerL + 1, outerB - 2, outerR - 1, outerB - 1, LEATHER_DARK);
        g.fill(outerR - 2, outerT + 1, outerR - 1, outerB - 1, LEATHER_DARK);

        // 4. Title bar
        g.fill(contentL, contentT, contentR, contentT + TITLE_H, TITLE_BAR);
        g.drawString(font, "ASSET SHELF", contentL + PAD, contentT + 2, TITLE_TEXT, false);

        // 5. Binder strip
        renderBinderStrip(g, mx, my);

        // 6. Book pages (flat cream)
        g.fill(bookL, bookT, bookR, bookB, PAPER);

        // 7. Header
        renderHeader(g, mx, my);

        // 8. Spine shadow
        renderSpine(g);

        // 9. Left page
        renderLeftPage(g, mx, my);

        // 10. Right page
        renderRightPage(g, mx, my);

        // 11. Widgets on top
        super.render(g, mx, my, pt);

        // 12. Tooltips
        renderCardTooltips(g, mx, my);
    }

    // ── Binder strip ──

    private void renderBinderStrip(GuiGraphics g, int mx, int my) {
        int bL = contentL;
        int bT = contentT + TITLE_H;
        int bR = contentL + BINDER_W;
        g.fill(bL, bT, bR, contentB, BINDER_BG);

        List<AssetType> types = new ArrayList<>();
        for (AssetType t : AssetShelfApi.allTypes()) types.add(t);
        if (types.isEmpty()) return;

        int tabH = 24; // compact: 16px icon + 4px padding top/bottom

        for (int i = 0; i < types.size(); i++) {
            AssetType t = types.get(i);
            boolean active = t.id().equals(activeTypeId);
            int ty = bT + i * tabH;

            // Tab background
            int tabRight = active ? bR : bR - 2;
            int bg = active ? PAPER : BINDER_INACT;
            g.fill(bL, ty, tabRight, ty + tabH, bg);

            // Accent stripe (left edge, 3px)
            g.fill(bL, ty, bL + 3, ty + tabH, t.accentColor());

            // Icon (centered in tab) or vertical text fallback
            ResourceLocation icon = t.icon();
            if (icon != null) {
                int iconSize = 16;
                int ix = bL + 3 + (BINDER_W - 3 - iconSize) / 2;
                int iy = ty + (tabH - iconSize) / 2;
                g.blit(icon, ix, iy, 0, 0, iconSize, iconSize, iconSize, iconSize);
            } else {
                String name = t.displayName().getString();
                int textColor = active ? INK : INK_FAINT;
                int charH = 8;
                int textTotalH = name.length() * charH;
                int textStartY = ty + Math.max(2, (tabH - textTotalH) / 2);
                for (int c = 0; c < name.length(); c++) {
                    int cy = textStartY + c * charH;
                    if (cy + charH > ty + tabH - 2) break;
                    g.drawString(font, String.valueOf(name.charAt(c)), bL + 6, cy, textColor, false);
                }
            }

            // Bottom separator
            g.fill(bL, ty + tabH - 1, tabRight, ty + tabH, BINDER_BG);
        }
    }

    // ── Header (SERVER / LOCAL tabs) ──

    private void renderHeader(GuiGraphics g, int mx, int my) {
        g.fill(bookL, headerT, bookR, headerB, HEADER_BG);
        g.fill(bookL, headerB - 1, bookR, headerB, RULE); // bottom rule

        // SERVER tab
        int tx = bookL + PAD;
        boolean srvActive = activeTab == Tab.SERVER;
        boolean locActive = activeTab == Tab.LOCAL;
        int srvW = font.width("SERVER") + 12;
        int locW = font.width("LOCAL") + 12;
        int tabH = HEADER_H - 6;
        int tabY = headerT + 3;

        // SERVER
        g.fill(tx, tabY, tx + srvW, tabY + tabH, srvActive ? PAPER : HEADER_BG);
        if (srvActive) g.renderOutline(tx, tabY, srvW, tabH, RULE);
        g.drawString(font, "SERVER", tx + 6, tabY + 3, srvActive ? INK : INK_DIM, false);
        tx += srvW + 2;

        // LOCAL
        g.fill(tx, tabY, tx + locW, tabY + tabH, locActive ? PAPER : HEADER_BG);
        if (locActive) g.renderOutline(tx, tabY, locW, tabH, RULE);
        g.drawString(font, "LOCAL", tx + 6, tabY + 3, locActive ? INK : INK_DIM, false);
    }

    // ── Spine shadow ──

    private void renderSpine(GuiGraphics g) {
        for (int i = 0; i < 4; i++) {
            int alpha = 0x18 - i * 0x04;
            int color = (alpha << 24);
            g.fill(spineX - 4 + i, headerB, spineX - 3 + i, bookB, color);
            g.fill(spineX + i, headerB, spineX + 1 + i, bookB, color);
        }
        g.fill(spineX - 1, headerB, spineX + 1, bookB, 0x10000000);
    }

    // ── Left page (contact sheet) ──

    private void renderLeftPage(GuiGraphics g, int mx, int my) {
        AssetType type = getActiveType();
        int accent = type != null ? type.accentColor() : 0xFFC47840;
        String typeName = type != null ? type.displayName().getString().toUpperCase() : "ASSETS";

        // Accent band
        int bandY = headerB;
        g.fill(leftL + PAD, bandY, leftR - PAD, bandY + BAND_H, accent);

        // Type label + count
        int labelY = bandY + BAND_H + 4;
        g.drawString(font, typeName, leftL + PAD, labelY, INK_DIM, false);
        String count = totalCount + " " + (type != null ? type.displayName().getString().toLowerCase() : "asset") + (totalCount != 1 ? "s" : "");
        int countX = leftL + PAD + font.width(typeName) + 10;
        g.drawString(font, count, countX, labelY, INK_FAINT, false);

        // Active tag filter chips
        filterChipsList = List.of(); // reset
        if (!activeTagFilters.isEmpty()) {
            int chipX = leftL + PAD;
            int chipBaseY = labelY + 12;
            int chipY = chipBaseY;
            int maxW = leftR - leftL - PAD * 2;

            // Store for hit testing
            filterChipsX = chipX;
            filterChipsY = chipBaseY;
            filterChipsW = maxW;
            filterChipsList = List.copyOf(activeTagFilters);

            for (int ti = 0; ti < activeTagFilters.size(); ti++) {
                String tag = activeTagFilters.get(ti);
                String chipText = tag + " \u2715";
                int tw = font.width(chipText) + 8;
                if (chipX + tw > leftL + PAD + maxW && chipX > leftL + PAD) {
                    chipX = leftL + PAD;
                    chipY += 14;
                }
                boolean chipHover = mx >= chipX && mx < chipX + tw && my >= chipY && my < chipY + 12;
                g.fill(chipX, chipY, chipX + tw, chipY + 12, chipHover ? 0xFF885555 : 0xFF4A6A8A);
                g.drawString(font, chipText, chipX + 4, chipY + 2, 0xFFFFFFFF, false);
                chipX += tw + 3;
            }
            // Adjust gridTop to account for filter chips
            gridTop = chipY + 16;
        }

        // Loading / empty state
        int centerX = (leftL + leftR) / 2;
        int centerY = (gridTop + bookB) / 2;
        if (awaitingServer && displayAssets.isEmpty()) {
            drawCentered(g, "Loading...", centerX, centerY, INK_FAINT);
            return;
        }
        if (displayAssets.isEmpty()) {
            String msg = activeTab == Tab.LOCAL
                ? "No local assets yet." : "No published assets.";
            drawCentered(g, msg, centerX, centerY, INK_FAINT);
            return;
        }

        // Grid of cards
        for (int i = 0; i < displayAssets.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = leftL + PAD + col * (cardW + PAD);
            int cy = gridTop + row * (cardH + PAD);
            if (cy + cardH > bookB - 16) break;

            boolean hovered = mx >= cx && mx < cx + cardW && my >= cy && my < cy + cardH;
            boolean selected = i == selectedIndex;
            renderCard(g, i, cx, cy, hovered, selected);
        }

        // Pagination
        renderPagination(g);
    }

    private void renderCard(GuiGraphics g, int idx, int cx, int cy, boolean hovered, boolean selected) {
        DisplayAsset asset = displayAssets.get(idx);

        // Card fill
        int bg = selected ? PAPER_WARM : (hovered ? PAPER_WARM : CARD_BG);
        g.fill(cx, cy, cx + cardW, cy + cardH, bg);

        // Border
        if (selected) {
            // Double border: outer dark, inner gap, inner rule
            g.renderOutline(cx - 1, cy - 1, cardW + 2, cardH + 2, RULE_DARK);
            g.renderOutline(cx, cy, cardW, cardH, RULE_DARK);
        } else {
            g.renderOutline(cx, cy, cardW, cardH, hovered ? RULE_DARK : RULE);
        }

        // Index number
        String num = String.format("%02d", currentPage * PAGE_SIZE + idx + 1);
        g.drawString(font, num, cx + 3, cy + 2, INK_FAINT, false);

        // Thumbnail (aspect-ratio preserving via UV crop, or 3D item render)
        if (idx < previewStacks.size() && !previewStacks.get(idx).isEmpty()) {
            // 3D item render path
            ItemStack previewStack = previewStacks.get(idx);
            int areaW = cardW - 4, areaH = thumbH - 4;
            float scale = Math.min(areaW, areaH) / 16.0f;
            int rx = cx + 2 + (areaW - (int)(16 * scale)) / 2;
            int ry = cy + 2 + (areaH - (int)(16 * scale)) / 2;
            g.pose().pushPose();
            g.pose().translate(rx, ry, 100);
            g.pose().scale(scale, scale, scale);
            g.renderItem(previewStack, 0, 0);
            g.pose().popPose();
        } else if (idx < thumbnails.size() && thumbnails.get(idx) != null) {
            ThumbEntry th = thumbnails.get(idx);
            int areaW = cardW - 4, areaH = thumbH - 4;
            int aw = asset.meta().widthPx(), ah = asset.meta().heightPx();
            if (aw <= 0) aw = 1; if (ah <= 0) ah = 1;

            // Where renderThumbnail placed content in the square texture
            float texScale = Math.min((float) th.size / aw, (float) th.size / ah);
            int texW = Math.max(1, Math.round(aw * texScale));
            int texH = Math.max(1, Math.round(ah * texScale));
            int texOffX = (th.size - texW) / 2;
            int texOffY = (th.size - texH) / 2;

            // Fit into card area preserving aspect ratio
            float dispScale = Math.min((float) areaW / aw, (float) areaH / ah);
            int rw = Math.max(1, (int)(aw * dispScale));
            int rh = Math.max(1, (int)(ah * dispScale));
            int rx = cx + 2 + (areaW - rw) / 2;
            int ry = cy + 2 + (areaH - rh) / 2;

            // Blit only the painted region (skip letterbox bands)
            g.blit(th.loc, rx, ry, rw, rh,
                (float) texOffX, (float) texOffY, texW, texH, th.size, th.size);
        }

        // Name + author
        int textY = cy + thumbH;
        int maxW = cardW - 4;
        String name = truncate(asset.meta().name(), maxW);
        g.drawString(font, name, cx + 2, textY + 1, INK, false);

        String author = "by " + asset.meta().authorName() + " · "
            + asset.meta().widthPx() + "×" + asset.meta().heightPx();
        author = truncate(author, maxW);
        g.drawString(font, author, cx + 2, textY + 10, INK_DIM, false);
    }

    private void renderPagination(GuiGraphics g) {
        int totalPages = Math.max(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
        int py = bookB - 12;
        g.drawString(font, "page " + (currentPage + 1) + " of " + totalPages,
            leftL + PAD, py, INK_FAINT, false);
        if (totalPages > 1) {
            StringBuilder nav = new StringBuilder();
            if (currentPage > 0) nav.append("\u2039 prev  ");
            if (currentPage < totalPages - 1) nav.append("next \u203A");
            String s = nav.toString();
            g.drawString(font, s, leftR - PAD - font.width(s), py, INK_DIM, false);
        }
    }

    // ── Right page (detail) ──

    private void renderRightPage(GuiGraphics g, int mx, int my) {
        AssetType type = getActiveType();
        int accent = type != null ? type.accentColor() : 0xFFC47840;
        String typeName = type != null ? type.displayName().getString().toUpperCase() : "ASSETS";

        int px = rightL + PAD;
        int pw = rightR - rightL - PAD * 2;

        // Accent band
        g.fill(px, headerB, rightR - PAD, headerB + BAND_H, accent);

        // Placeholder when nothing selected
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size()) {
            int cx = (rightL + rightR) / 2;
            int cy = (headerB + bookB) / 2;
            drawCentered(g, "Select an asset to preview", cx, cy, INK_FAINT);
            return;
        }

        DisplayAsset asset = displayAssets.get(selectedIndex);
        AssetMeta meta = asset.meta();

        // Type label
        int dy = headerB + BAND_H + 4;
        g.drawString(font, typeName, px, dy, INK_DIM, false);
        dy += 14;

        // Asset name
        g.drawString(font, meta.name(), px, dy, INK, false);
        dy += 12;

        // Author + dimensions
        String sub = "by " + meta.authorName() + " \u00B7 "
            + meta.widthPx() + "\u00D7" + meta.heightPx() + "px";
        if (asset.isLocal() && asset.published()) sub += " \u00B7 published";
        g.drawString(font, sub, px, dy, INK_DIM, false);
        dy += 12;

        // Description (word-wrapped)
        if (!meta.description().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> descLines =
                font.split(Component.literal(meta.description()), pw);
            int maxDescLines = 3;
            for (int dl = 0; dl < Math.min(descLines.size(), maxDescLines); dl++) {
                g.drawString(font, descLines.get(dl), px, dy, INK_DIM, false);
                dy += 10;
            }
        }

        // Tags (clickable — store position for hit testing)
        detailTagsList = meta.tags();
        detailTagsX = px;
        detailTagsY = dy;
        detailTagsW = pw;
        if (!meta.tags().isEmpty()) {
            TagInputWidget.renderClickableChips(g, font, meta.tags(), px, dy, pw, mx, my);
            dy += TagInputWidget.readOnlyChipsHeight(font, meta.tags(), pw) + 4;
        }
        dy += 4;

        // ── Bottom-up fixed layout ──
        int btnH = 20;
        int btnY = bookB - btnH - PAD;
        int actionY = btnY - 4 - 14;
        int costIconsY = actionY - 4 - 36;  // 2 rows of 18px icons
        int costLabelY = costIconsY - 4 - 10;
        int qtyBtnY = costLabelY - 4 - 16;
        int qtyLabelY = qtyBtnY - 4 - 10;

        // Preview — 3D item render or aspect-ratio preserving UV crop
        if (!detailPreviewStack.isEmpty()) {
            // 3D item rendering for chiseled blocks etc.
            int previewMaxH = qtyLabelY - 4 - dy;
            int previewSize = Math.min(pw, Math.max(24, previewMaxH));
            float scale = previewSize / 16.0f;
            int rx = px + (pw - previewSize) / 2;
            int ry = dy + (Math.max(24, previewMaxH) - previewSize) / 2;

            g.fill(rx - 2, ry - 2, rx + previewSize + 2, ry + previewSize + 2, RULE);
            g.fill(rx - 1, ry - 1, rx + previewSize + 1, ry + previewSize + 1, CARD_BG);

            g.pose().pushPose();
            g.pose().translate(rx, ry, 200);
            g.pose().scale(scale, scale, scale);
            g.renderItem(detailPreviewStack, 0, 0);
            g.pose().popPose();
        } else if (detailLoc != null) {
            int aw = meta.widthPx(), ah = meta.heightPx();
            if (aw <= 0) aw = 1; if (ah <= 0) ah = 1;

            // Where content sits in the 128×128 detail texture
            float texScale = Math.min((float) detailSize / aw, (float) detailSize / ah);
            int texW = Math.max(1, Math.round(aw * texScale));
            int texH = Math.max(1, Math.round(ah * texScale));
            int texOffX = (detailSize - texW) / 2;
            int texOffY = (detailSize - texH) / 2;

            // Fit into available space
            int previewMaxH = qtyLabelY - 4 - dy;
            float dispScale = Math.min((float) pw / aw, (float) Math.max(24, previewMaxH) / ah);
            int rw = Math.max(1, (int)(aw * dispScale));
            int rh = Math.max(1, (int)(ah * dispScale));

            g.fill(px, dy, px + rw, dy + rh, RULE);
            g.fill(px + 1, dy + 1, px + rw - 1, dy + rh - 1, CARD_BG);
            g.blit(detailLoc, px + 2, dy + 2, rw - 4, rh - 4,
                (float) texOffX, (float) texOffY, texW, texH, detailSize, detailSize);
        }

        // QUANTITY selector
        g.drawString(font, "QUANTITY", px, qtyLabelY, INK_FAINT, false);
        int[] qtys = {1, 4, 8, 16};
        int qbw = Math.min(36, (pw - 6) / 4);
        for (int i = 0; i < qtys.length; i++) {
            int qx = px + i * (qbw + 2);
            boolean sel = selectedQuantity == qtys[i];
            g.fill(qx, qtyBtnY, qx + qbw, qtyBtnY + 16, sel ? accent : CARD_BG);
            g.renderOutline(qx, qtyBtnY, qbw, 16, sel ? RULE_DARK : RULE);
            String label = "\u00D7 " + qtys[i];
            int tw = font.width(label);
            g.drawString(font, label, qx + (qbw - tw) / 2, qtyBtnY + 4, sel ? 0xFFFFFFFF : INK, false);
        }

        // COST section (uses cached values — recomputed on selection/quantity change)
        g.drawString(font, "COST", px, costLabelY, INK_FAINT, false);
        refreshCostIfNeeded(type, asset.data());
        List<ItemCost> baseCost = cachedCost;
        boolean canAfford = cachedCanAfford;
        if (baseCost.isEmpty()) {
            g.drawString(font, "Free", px, costIconsY + 4, INK_FAINT, false);
        } else {
            int ix = px;
            int maxX = px + pw;
            int row = 0;
            for (int ci = 0; ci < baseCost.size(); ci++) {
                ItemCost cost = baseCost.get(ci);
                int totalCount = cost.count() * selectedQuantity;
                boolean hasEnough = ci < cachedHave.length && cachedHave[ci] >= totalCount;
                String cLabel = "\u00D7" + totalCount;
                int entryW = 17 + font.width(cLabel) + 4;
                if (ix + entryW > maxX) {
                    ix = px;
                    row++;
                    if (row >= 2) break;
                }
                int iy = costIconsY - row * 18;
                g.renderItem(cost.stack(), ix, iy);
                g.drawString(font, cLabel, ix + 17, iy + 4, hasEnough ? INK_DIM : 0xFFCC4444, false);
                ix += entryW;
            }
            // canAfford already set from cached value above
        }

        // Action buttons
        if (asset.isLocal()) {
            int smallW = pw / 3 - 1;
            String pubLabel = asset.published() ? "Un-publish" : "Publish";
            renderSmallBtn(g, mx, my, px, actionY, smallW, pubLabel);
            renderSmallBtn(g, mx, my, px + smallW + 2, actionY, smallW, "Edit");
            renderSmallBtn(g, mx, my, px + (smallW + 2) * 2, actionY, smallW, "Delete");
        } else {
            var mc = Minecraft.getInstance();
            boolean isAuthor = mc.player != null && meta.authorUUID().equals(mc.player.getUUID());
            boolean alreadyLocal = localAssetIds.contains(meta.id());
            if (isAuthor) {
                int thirdW = pw / 3 - 1;
                if (alreadyLocal) {
                    renderSmallBtnDisabled(g, px, actionY, thirdW, "Saved");
                } else {
                    renderSmallBtn(g, mx, my, px, actionY, thirdW, "Save Local");
                }
                renderSmallBtn(g, mx, my, px + thirdW + 2, actionY, thirdW, "Edit");
                renderSmallBtn(g, mx, my, px + (thirdW + 2) * 2, actionY, thirdW, "Delete");
            } else {
                int halfW = pw / 2;
                if (alreadyLocal) {
                    renderSmallBtnDisabled(g, px, actionY, halfW, "Saved");
                } else {
                    renderSmallBtn(g, mx, my, px, actionY, halfW, "Save Local");
                }
                if (mc.player != null && meta.authorUUID().equals(mc.player.getUUID())) {
                    renderSmallBtn(g, mx, my, px + halfW + 2, actionY, halfW - 2, "Delete");
                }
            }
        }

        // Golden "take N stamps" button
        boolean btnHover = mx >= px && mx < px + pw && my >= btnY && my < btnY + btnH;
        int btnCol;
        String takeLabel;
        if (canAfford) {
            btnCol = btnHover ? BTN_GOLD_HVR : BTN_GOLD;
            takeLabel = "take " + selectedQuantity + " stamp" + (selectedQuantity > 1 ? "s" : "");
        } else {
            btnCol = RULE;
            takeLabel = "can't afford";
        }
        g.fill(px + 2, btnY + 2, px + pw + 2, btnY + btnH + 2, 0x30000000);
        g.fill(px, btnY, px + pw, btnY + btnH, btnCol);
        g.renderOutline(px, btnY, pw, btnH, RULE_DARK);
        drawCentered(g, takeLabel, px + pw / 2, btnY + 6, INK);
    }

    private void renderSmallBtn(GuiGraphics g, int mx, int my, int x, int y, int w, String label) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? PAPER_WARM : CARD_BG);
        g.renderOutline(x, y, w, 14, RULE);
        drawCentered(g, label, x + w / 2, y + 3, INK_DIM);
    }

    private void renderSmallBtnDisabled(GuiGraphics g, int x, int y, int w, String label) {
        g.fill(x, y, x + w, y + 14, CARD_BG);
        g.renderOutline(x, y, w, 14, RULE);
        drawCentered(g, label, x + w / 2, y + 3, INK_FAINT);
    }

    // ── Rename overlay ──

    private void renderCardTooltips(GuiGraphics g, int mx, int my) {
        for (int i = 0; i < displayAssets.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = leftL + PAD + col * (cardW + PAD);
            int cy = gridTop + row * (cardH + PAD);
            if (cy + cardH > bookB - 16) break;
            if (mx >= cx && mx < cx + cardW && my >= cy && my < cy + cardH) {
                DisplayAsset a = displayAssets.get(i);
                List<Component> tip = List.of(
                    Component.literal(a.meta().name()),
                    Component.literal(a.meta().widthPx() + "\u00D7" + a.meta().heightPx()
                        + "px  by " + a.meta().authorName())
                );
                g.renderTooltip(font, tip, java.util.Optional.empty(), mx, my);
                break;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Input
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return super.mouseClicked(mx, my, btn);

        // Binder tab clicks
        if (mx >= contentL && mx < contentL + BINDER_W && my > contentT + TITLE_H && my < contentB) {
            List<AssetType> types = new ArrayList<>();
            for (AssetType t : AssetShelfApi.allTypes()) types.add(t);
            if (!types.isEmpty()) {
                int bT = contentT + TITLE_H;
                int tabH = 24;
                int idx = (int)((my - bT) / tabH);
                if (idx >= 0 && idx < types.size()) {
                    activeTypeId = types.get(idx).id();
                    currentPage = 0; selectedIndex = -1;
                    saveSession();
                    needsDataLoad = true;
                    rebuildAll();
                    return true;
                }
            }
        }

        // Header tab clicks (SERVER / LOCAL)
        if (my >= headerT && my < headerB && mx >= bookL) {
            int tx = bookL + PAD;
            int srvW = font.width("SERVER") + 12;
            int locW = font.width("LOCAL") + 12;
            if (mx >= tx && mx < tx + srvW) { switchTab(Tab.SERVER); return true; }
            tx += srvW + 2;
            if (mx >= tx && mx < tx + locW) { switchTab(Tab.LOCAL); return true; }
        }

        // Quantity selector clicks (on right page)
        if (selectedIndex >= 0 && rightL > 0) {
            // Find quantity button positions (need to match render coords)
            // We re-derive dy from the right page layout
            int qClickY = findQuantityY();
            if (qClickY > 0 && my >= qClickY && my < qClickY + 16) {
                int px = rightL + PAD;
                int pw = rightR - rightL - PAD * 2;
                int[] qtys = {1, 4, 8, 16};
                int qbw = Math.min(36, (pw - 6) / 4);
                for (int i = 0; i < qtys.length; i++) {
                    int qx = px + i * (qbw + 2);
                    if (mx >= qx && mx < qx + qbw) {
                        selectedQuantity = qtys[i];
                        saveSession();
                        invalidateCost();
                        return true;
                    }
                }
            }

            // Golden take button
            int btnH = 20;
            int btnY = bookB - btnH - 6;
            int px = rightL + PAD;
            int pw = rightR - rightL - PAD * 2;
            if (mx >= px && mx < px + pw && my >= btnY && my < btnY + btnH) {
                takeStamps();
                return true;
            }

            // Small action buttons
            int actionY = findActionBtnY();
            if (actionY > 0 && my >= actionY && my < actionY + 14 && selectedIndex < displayAssets.size()) {
                DisplayAsset sel = displayAssets.get(selectedIndex);
                int smallW = pw / 3 - 1;
                if (sel.isLocal()) {
                    if (mx >= px && mx < px + smallW) {
                        if (sel.published()) unpublishSelected(); else publishSelected();
                        return true;
                    }
                    if (mx >= px + smallW + 2 && mx < px + (smallW + 2) * 2 - 2) { editSelected(); return true; }
                    if (mx >= px + (smallW + 2) * 2) { deleteSelected(); return true; }
                } else {
                    boolean isAuthor = minecraft != null && minecraft.player != null
                        && sel.meta().authorUUID().equals(minecraft.player.getUUID());
                    boolean alreadyLocal = localAssetIds.contains(sel.meta().id());
                    if (isAuthor) {
                        int thirdW = pw / 3 - 1;
                        if (mx >= px && mx < px + thirdW) {
                            if (!alreadyLocal) saveSelectedLocal();
                            return true;
                        }
                        if (mx >= px + thirdW + 2 && mx < px + (thirdW + 2) * 2 - 2) { editSelected(); return true; }
                        if (mx >= px + (thirdW + 2) * 2) { deleteSelected(); return true; }
                    } else {
                        int halfW = pw / 2;
                        if (mx >= px && mx < px + halfW) {
                            if (!alreadyLocal) saveSelectedLocal();
                            return true;
                        }
                        if (mx >= px + halfW + 2) { deleteSelected(); return true; }
                    }
                }
            }
        }

        // Active filter chip clicks (remove filter)
        if (!filterChipsList.isEmpty()) {
            int chipX = filterChipsX;
            int chipY = filterChipsY;
            for (String tag : filterChipsList) {
                String chipText = tag + " \u2715";
                int tw = font.width(chipText) + 8;
                if (chipX + tw > filterChipsX + filterChipsW && chipX > filterChipsX) {
                    chipX = filterChipsX;
                    chipY += 14;
                }
                if (mx >= chipX && mx < chipX + tw && my >= chipY && my < chipY + 12) {
                    removeTagFilter(tag);
                    return true;
                }
                chipX += tw + 3;
            }
        }

        // Tag chip clicks in detail panel (add filter) — uses positions from last render
        if (selectedIndex >= 0 && !detailTagsList.isEmpty() && detailTagsW > 0) {
            String hitTag = TagInputWidget.hitTestChips(font, detailTagsList,
                detailTagsX, detailTagsY, detailTagsW, mx, my);
            if (hitTag != null) {
                addTagFilter(hitTag);
                return true;
            }
        }

        // Card clicks
        for (int i = 0; i < displayAssets.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = leftL + PAD + col * (cardW + PAD);
            int cy = gridTop + row * (cardH + PAD);
            if (cy + cardH > bookB - 16) break;
            if (mx >= cx && mx < cx + cardW && my >= cy && my < cy + cardH) {
                if (selectedIndex == i) {
                    // Double-click = take stamp
                    takeStamps();
                } else {
                    selectIdx(i);
                }
                return true;
            }
        }

        // Pagination
        int pgY = bookB - 14;
        if (my >= pgY && my < bookB) {
            int mid = (leftL + leftR) / 2;
            int totalPages = Math.max(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
            if (mx < mid && currentPage > 0) { prevPage(); return true; }
            if (mx >= mid && currentPage < totalPages - 1) { nextPage(); return true; }
        }

        // Click empty grid area → deselect
        if (mx >= leftL && mx < leftR && my >= gridTop && my < bookB - 16 && selectedIndex >= 0) {
            selectedIndex = -1;
            releaseDetailTex();
        }

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (selectedIndex >= 0) {
            if (hasControlDown() && key == 68) { copyDebugInfo(); return true; } // Ctrl+D
            if (key == 263) { selectIdx(selectedIndex - 1); return true; }
            if (key == 262) { selectIdx(selectedIndex + 1); return true; }
            if (key == 265) { selectIdx(selectedIndex - cols); return true; }
            if (key == 264) { selectIdx(selectedIndex + cols); return true; }
            if (key == 257) { takeStamps(); return true; }
            if (key == 261) { deleteSelected(); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return super.charTyped(c, mods);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Data loading
    // ═══════════════════════════════════════════════════════════════

    private void loadCurrentPage() {
        String filter = searchBox != null ? searchBox.getValue().trim().toLowerCase() : "";
        if (activeTab == Tab.LOCAL) loadLocal(filter); else requestServer();
    }

    private void loadLocal(String filter) {
        releaseThumbs();
        displayAssets.clear();
        ResourceLocation typeId = activeTypeId;
        if (typeId == null) { totalCount = 0; return; }
        List<AssetMeta> all = LocalLibrary.list(typeId);
        // Cache all local IDs for server-tab "already saved" check
        Set<UUID> allLocalIds = new java.util.HashSet<>(all.size());
        for (AssetMeta m : all) allLocalIds.add(m.id());
        localAssetIds = allLocalIds;
        if (!filter.isEmpty()) all.removeIf(m -> !m.name().toLowerCase().contains(filter));
        if (!activeTagFilters.isEmpty()) {
            all.removeIf(m -> {
                for (String req : activeTagFilters) {
                    if (!m.tags().contains(req)) return true;
                }
                return false;
            });
        }
        all.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        totalCount = all.size();
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, all.size());
        Set<UUID> publishedIds = LocalLibrary.getPublishedIds(typeId);
        for (int i = start; i < end; i++) {
            AssetMeta m = all.get(i);
            displayAssets.add(new DisplayAsset(m, LocalLibrary.loadData(typeId, m.id()),
                true, publishedIds.contains(m.id())));
        }
        buildThumbs();
    }

    private void requestServer() {
        releaseThumbs();
        displayAssets.clear();
        awaitingServer = true;
        // Cache local IDs so we can detect "already saved" in server results
        if (activeTypeId != null) {
            List<AssetMeta> localAll = LocalLibrary.list(activeTypeId);
            Set<UUID> ids = new java.util.HashSet<>(localAll.size());
            for (AssetMeta m : localAll) ids.add(m.id());
            localAssetIds = ids;
        }
        String filter = searchBox != null ? searchBox.getValue().trim() : "";
        PacketDistributor.sendToServer(new BrowseRequestPayload(
            activeTypeId, currentPage, PAGE_SIZE, filter, activeTagFilters));
    }

    public void receiveServerAssets(List<BrowseResponsePayload.Entry> entries, int total, int page) {
        releaseThumbs();
        displayAssets.clear();
        awaitingServer = false;
        totalCount = total;
        currentPage = page;
        for (var e : entries) displayAssets.add(new DisplayAsset(e.meta(), e.data(), false, false));
        buildThumbs();
        if (selectedIndex >= 0) rebuildDetailTex();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Thumbnails
    // ═══════════════════════════════════════════════════════════════

    private void buildThumbs() {
        releaseThumbs();
        var tm = Minecraft.getInstance().getTextureManager();
        AssetType type = getActiveType();
        for (DisplayAsset asset : displayAssets) {
            // Try 3D item preview first
            ItemStack previewStack = ItemStack.EMPTY;
            if (type != null && asset.data().length > 0) {
                previewStack = type.getPreviewStack(asset.data()).orElse(ItemStack.EMPTY);
            }
            previewStacks.add(previewStack);

            if (previewStack.isEmpty()) {
                // Fallback: pixel-based NativeImage thumbnail
                int sz = Math.max(16, thumbH);
                NativeImage img = new NativeImage(sz, sz, true);
                fillImage(img, sz, CARD_BG);
                DynamicTexture tex = new DynamicTexture(img);
                ResourceLocation loc = tm.register("assetshelf_t", tex);
                thumbnails.add(new ThumbEntry(tex, loc, sz));
                // Submit for deferred rendering (shows placeholder until ready)
                if (type != null && asset.data().length > 0) {
                    type.submitDeferredThumbnail(asset.data(), img, sz, tex::upload);
                }
            } else {
                // Placeholder entry — won't be used for rendering (item render path instead)
                thumbnails.add(null);
            }
        }
    }

    private void releaseThumbs() {
        // Cancel any in-flight deferred renders before releasing textures
        AssetType type = getActiveType();
        if (type != null) type.cancelDeferredThumbnails();
        var tm = Minecraft.getInstance().getTextureManager();
        for (var t : thumbnails) {
            if (t != null) { tm.release(t.loc); t.tex.close(); }
        }
        thumbnails.clear();
        previewStacks.clear();
    }

    private void rebuildDetailTex() {
        // Determine what data the detail should show
        byte[] wantData = null;
        if (selectedIndex >= 0 && selectedIndex < displayAssets.size()) {
            wantData = displayAssets.get(selectedIndex).data();
            if (wantData != null && wantData.length == 0) wantData = null;
        }
        // Skip if already showing the right data
        if (wantData == detailDataRef) return;
        detailDataRef = wantData;

        releaseDetailTex();
        detailPreviewStack = ItemStack.EMPTY;
        if (wantData == null) return;
        AssetType type = getActiveType();
        if (type == null) return;

        // Try 3D item preview for detail
        detailPreviewStack = type.getPreviewStack(wantData).orElse(ItemStack.EMPTY);

        if (detailPreviewStack.isEmpty()) {
            // Fallback: pixel-based detail texture (deferred for heavy asset types)
            detailSize = 128;
            NativeImage img = new NativeImage(detailSize, detailSize, true);
            fillImage(img, detailSize, CARD_BG);
            detailTex = new DynamicTexture(img);
            detailLoc = Minecraft.getInstance().getTextureManager().register("assetshelf_d", detailTex);
            type.submitDeferredThumbnail(wantData, img, detailSize, detailTex::upload);
        }
    }

    private void releaseDetailTex() {
        if (detailLoc != null) { Minecraft.getInstance().getTextureManager().release(detailLoc); detailLoc = null; }
        if (detailTex != null) { detailTex.close(); detailTex = null; }
        detailPreviewStack = ItemStack.EMPTY;
        detailDataRef = null;
    }

    /** Recompute cost + affordability only when the selected asset or quantity changes. */
    private void refreshCostIfNeeded(@javax.annotation.Nullable AssetType type, byte[] data) {
        if (data == costDataRef && selectedQuantity == costQuantityRef) return;
        costDataRef = data;
        costQuantityRef = selectedQuantity;

        // Creative mode: everything is free
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getAbilities().instabuild) {
            cachedCost = List.of();
            cachedHave = new long[0];
            cachedCanAfford = true;
            return;
        }

        cachedCost = type != null ? type.computeCost(data) : List.of();
        cachedHave = new long[cachedCost.size()];
        cachedCanAfford = true;
        for (int i = 0; i < cachedCost.size(); i++) {
            cachedHave[i] = type != null ? type.countAvailableClient(cachedCost.get(i)) : 0;
            if (cachedHave[i] < (long) cachedCost.get(i).count() * selectedQuantity) {
                cachedCanAfford = false;
            }
        }
    }

    /** Force cost recalculation (e.g. after taking stamps changes inventory). */
    private void invalidateCost() {
        costDataRef = null;
        costQuantityRef = -1;
    }

    private static void fillImage(NativeImage img, int sz, int argb) {
        int abgr = toAbgr(argb);
        for (int y = 0; y < sz; y++)
            for (int x = 0; x < sz; x++)
                img.setPixelRGBA(x, y, abgr);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════════

    private void takeStamps() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size() || activeTypeId == null) return;
        DisplayAsset a = displayAssets.get(selectedIndex);
        int qty = Math.max(1, selectedQuantity);
        if (a.isLocal()) {
            PacketDistributor.sendToServer(new UseLocalAssetPayload(activeTypeId, a.data(), qty));
        } else {
            PacketDistributor.sendToServer(new UseServerAssetPayload(a.meta().id(), qty));
        }
        // Do NOT close — user stays in the browser
        invalidateCost(); // inventory changed — recalculate affordability next frame
    }

    private void copyDebugInfo() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size()) return;
        DisplayAsset a = displayAssets.get(selectedIndex);
        AssetType type = getActiveType();

        StringBuilder sb = new StringBuilder();
        sb.append("═══ Asset Debug Info ═══\n");
        sb.append("name: \"").append(a.meta().name()).append("\"\n");
        sb.append("id: ").append(a.meta().id()).append("\n");
        sb.append("type: ").append(a.meta().typeId()).append("\n");
        sb.append("author: ").append(a.meta().authorName())
          .append(" (").append(a.meta().authorUUID()).append(")\n");
        sb.append("dimensions: ").append(a.meta().widthPx()).append("×")
          .append(a.meta().heightPx()).append(" px\n");
        sb.append("created: ").append(java.time.Instant.ofEpochMilli(a.meta().createdAt())).append("\n");
        sb.append("tags: ").append(a.meta().tags()).append("\n");
        sb.append("data_size: ").append(a.data().length).append(" bytes\n");
        sb.append("source: ").append(a.isLocal() ? "local" : "server").append("\n");

        // Type-specific diagnostics
        if (type != null && a.data().length > 0) {
            String typeDebug = type.generateDebugInfo(a.data());
            if (!typeDebug.isEmpty()) {
                sb.append("\n").append(typeDebug);
            }
        }

        // Cost breakdown
        if (type != null && a.data().length > 0) {
            sb.append("\n═══ Cost Breakdown ═══\n");
            List<ItemCost> costs = type.computeCost(a.data());
            if (costs.isEmpty()) {
                sb.append("  (free)\n");
            } else {
                for (ItemCost cost : costs) {
                    sb.append("  ").append(cost.stack().getHoverName().getString())
                      .append(" × ").append(cost.count()).append("\n");
                }
                sb.append("total_entries: ").append(costs.size()).append("\n");
            }
        }

        // Copy to clipboard
        long window = Minecraft.getInstance().getWindow().getWindow();
        org.lwjgl.glfw.GLFW.glfwSetClipboardString(window, sb.toString());
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                Component.literal("Debug info copied to clipboard"), true);
        }
    }

    private void publishSelected() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size() || activeTypeId == null) return;
        DisplayAsset a = displayAssets.get(selectedIndex);
        if (!a.isLocal()) return;
        AssetType type = getActiveType();
        if (type == null) return;

        String timeAgo = formatTimeAgo(a.meta().createdAt());
        String subtitle = "by you \u00B7 " + a.meta().widthPx() + "\u00D7" + a.meta().heightPx()
            + " \u00B7 saved locally " + timeAgo;

        SaveAssetScreen.Builder builder = SaveAssetScreen.publish(this)
            .thumbnailFromBytes(a.data(), type, a.meta().widthPx(), a.meta().heightPx())
            .assetTypeInfo(type.displayName().getString().toUpperCase(), type.accentColor())
            .heroTitle(a.meta().name())
            .heroSubtitle(subtitle)
            .defaultName(a.meta().name())
            .defaultDescription(a.meta().description())
            .defaultTags(a.meta().tags())
            .onAction((name, description, tags) -> {
                PacketDistributor.sendToServer(new PublishPayload(
                    activeTypeId, a.meta().id(), name, description,
                    a.meta().widthPx(), a.meta().heightPx(), a.data(), tags));
                LocalLibrary.updateMetaAndPublish(activeTypeId, a.meta().id(), name, description, tags);
                if (minecraft != null && minecraft.player != null)
                    minecraft.player.displayClientMessage(
                        Component.literal("Published '" + name + "'"), true);
                needsDataLoad = true;
                rebuildAll();
            });

        // Let the asset type provide an extension section
        var ext = type.createModalExtension(a.data(), true);
        if (ext != null) builder.extension(ext);

        minecraft.setScreen(builder.build());
    }

    private void unpublishSelected() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size() || activeTypeId == null) return;
        DisplayAsset a = displayAssets.get(selectedIndex);
        if (!a.isLocal() || !a.published()) return;

        PacketDistributor.sendToServer(new DeletePublishedPayload(a.meta().id()));
        LocalLibrary.setPublished(activeTypeId, a.meta().id(), false);
        needsDataLoad = true;
        rebuildAll();
        if (minecraft != null && minecraft.player != null)
            minecraft.player.displayClientMessage(
                Component.literal("Un-published '" + a.meta().name() + "'"), true);
    }

    private static String formatTimeAgo(long epochMillis) {
        long diff = System.currentTimeMillis() - epochMillis;
        long seconds = diff / 1000;
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " h ago";
        long days = hours / 24;
        if (days < 30) return days + " d ago";
        long months = days / 30;
        return months + " mo ago";
    }

    private void deleteSelected() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size()) return;
        DisplayAsset a = displayAssets.get(selectedIndex);
        if (a.isLocal()) {
            if (a.published()) {
                // Also remove from server
                PacketDistributor.sendToServer(new DeletePublishedPayload(a.meta().id()));
            }
            if (activeTypeId != null) LocalLibrary.delete(activeTypeId, a.meta().id());
        } else {
            PacketDistributor.sendToServer(new DeletePublishedPayload(a.meta().id()));
        }
        selectedIndex = -1;
        needsDataLoad = true;
        rebuildAll();
    }

    private void saveSelectedLocal() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size()) return;
        DisplayAsset a = displayAssets.get(selectedIndex);
        if (a.isLocal() || activeTypeId == null) return;
        LocalLibrary.save(activeTypeId, a.data(), a.meta().name(), a.meta().widthPx(), a.meta().heightPx(), a.meta().tags());
        if (minecraft != null && minecraft.player != null)
            minecraft.player.displayClientMessage(Component.literal("Saved '" + a.meta().name() + "' locally"), true);
    }

    private void editSelected() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size() || activeTypeId == null) return;
        DisplayAsset a = displayAssets.get(selectedIndex);
        AssetType type = getActiveType();
        if (type == null) return;

        String timeAgo = formatTimeAgo(a.meta().createdAt());
        String subtitle = "by " + a.meta().authorName() + " \u00B7 " + a.meta().widthPx() + "\u00D7" + a.meta().heightPx()
            + " \u00B7 " + (a.isLocal() ? "saved locally " : "published ") + timeAgo;

        SaveAssetScreen.Builder builder = SaveAssetScreen.edit(this)
            .thumbnailFromBytes(a.data(), type, a.meta().widthPx(), a.meta().heightPx())
            .assetTypeInfo(type.displayName().getString().toUpperCase(), type.accentColor())
            .heroTitle(a.meta().name())
            .heroSubtitle(subtitle)
            .defaultName(a.meta().name())
            .defaultDescription(a.meta().description())
            .defaultTags(a.meta().tags());

        if (a.isLocal()) {
            builder.onAction((name, description, tags) -> {
                LocalLibrary.updateMeta(activeTypeId, a.meta().id(), name, description, tags);
                // Auto-sync to server if published
                if (a.published()) {
                    PacketDistributor.sendToServer(new UpdatePublishedPayload(
                        a.meta().id(), name, description, tags));
                }
                needsDataLoad = true;
                rebuildAll();
            });
        } else {
            builder.onAction((name, description, tags) -> {
                PacketDistributor.sendToServer(new UpdatePublishedPayload(
                    a.meta().id(), name, description, tags));
                needsDataLoad = true;
                rebuildAll();
            });
        }

        var ext = type.createModalExtension(a.data(), false);
        if (ext != null) builder.extension(ext);

        minecraft.setScreen(builder.build());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Navigation helpers
    // ═══════════════════════════════════════════════════════════════

    private void switchTab(Tab tab) {
        if (activeTab == tab) return;
        activeTab = tab; currentPage = 0; selectedIndex = -1;
        needsDataLoad = true;
        saveSession();
        rebuildAll();
    }

    private void selectIdx(int i) {
        if (i >= 0 && i < displayAssets.size()) {
            selectedIndex = i;
            saveSession();
            rebuildDetailTex();
        }
    }

    private void prevPage() { currentPage--; selectedIndex = -1; saveSession(); needsDataLoad = true; rebuildAll(); }
    private void nextPage() { currentPage++; selectedIndex = -1; saveSession(); needsDataLoad = true; rebuildAll(); }
    private void rebuildAll() { clearWidgets(); init(); }

    private void saveSession() {
        savedTypeId = activeTypeId;
        savedTab = activeTab;
        savedPage = currentPage;
        savedSelectedIndex = selectedIndex;
        savedQuantity = selectedQuantity;
        savedSearch = searchBox != null ? searchBox.getValue() : "";
        savedTagFilters = new ArrayList<>(activeTagFilters);
    }

    /** Add a tag filter and reload. Called when a tag chip in the detail panel is clicked. */
    private void addTagFilter(String tag) {
        if (activeTagFilters.contains(tag)) return;
        activeTagFilters.add(tag);
        currentPage = 0;
        selectedIndex = -1;
        saveSession();
        needsDataLoad = true;
        rebuildAll();
    }

    private void removeTagFilter(String tag) {
        if (!activeTagFilters.remove(tag)) return;
        currentPage = 0;
        selectedIndex = -1;
        saveSession();
        needsDataLoad = true;
        rebuildAll();
    }

    /**
     * Approximate Y position of quantity buttons on the right page.
     * Must match the layout in renderRightPage().
     */
    private int findQuantityY() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size()) return -1;
        int btnY = bookB - 20 - PAD;
        int actionY = btnY - 4 - 14;
        int costIconsY = actionY - 4 - 36;
        int costLabelY = costIconsY - 4 - 10;
        return costLabelY - 4 - 16;
    }

    private int findActionBtnY() {
        if (selectedIndex < 0 || selectedIndex >= displayAssets.size()) return -1;
        return bookB - 20 - PAD - 4 - 14;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════

    private AssetType getActiveType() {
        if (activeTypeId != null) {
            AssetType t = AssetShelfApi.getType(activeTypeId);
            if (t != null) return t;
        }
        for (AssetType t : AssetShelfApi.allTypes()) return t;
        return null;
    }

    private String truncate(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        while (s.length() > 1 && font.width(s + "\u2026") > maxW) s = s.substring(0, s.length() - 1);
        return s + "\u2026";
    }

    private void drawCentered(GuiGraphics g, String text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    static int toAbgr(int argb) {
        return ((argb & 0xFF000000))
             | ((argb & 0x00FF0000) >> 16)
             | ((argb & 0x0000FF00))
             | ((argb & 0x000000FF) << 16);
    }

    @Override public void removed() { super.removed(); releaseThumbs(); releaseDetailTex(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // Skip default dark overlay — we draw our own leather + paper background
    }
}
