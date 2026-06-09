package dev.assetshelf.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.assetshelf.api.AssetType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static dev.assetshelf.client.gui.ShelfPalette.*;

/**
 * Shared modal dialog for saving assets to local library or publishing to server.
 * Use {@link #saveLocal(Screen)} or {@link #publish(Screen)} to create a builder.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────┐
 * │  Save to Asset Shelf                         ✕  │
 * ├─────────────────────────────────────────────────┤
 * │  ┌──────────┐  ━━━━━ TYPENAME ─────────────     │
 * │  │ preview  │  hero title / description         │
 * │  │ [48×32]  │                                   │
 * │  └──────────┘                                   │
 * │  NAME                                           │
 * │  ┌─────────────────────────────────────────┐    │
 * │  │ value                                   │    │
 * │  └─────────────────────────────────────────┘    │
 * │  TAGS                                           │
 * │  ┌─────────────────────────────────────────┐    │
 * │  │ [tag1] [tag2]  type to add...           │    │
 * │  └─────────────────────────────────────────┘    │
 * │  ╔═══ + EXTENSION HEADER ══════════════════╗    │
 * │  ║  extension content                      ║    │
 * │  ╚═════════════════════════════════════════╝    │
 * │  footer note text                               │
 * │  ┌──────────┐  ┌═══════════════════════════┐    │
 * │  │  cancel   │  │       action              │    │
 * │  └──────────┘  └═══════════════════════════┘    │
 * └─────────────────────────────────────────────────┘
 * </pre>
 */
public class SaveAssetScreen extends Screen {

    public enum Mode { SAVE_LOCAL, PUBLISH, EDIT }

    // ── Config (set by builder) ──
    private final Screen parent;
    private final Mode mode;
    private final String headerTitle;
    private final String typeName;
    private final int accentColor;
    private final @Nullable String heroTitle;
    private final String heroSubtitle;
    private final String defaultName;
    private final String defaultDescription;
    private final boolean nameEditable;
    private final List<String> defaultTags;
    private final boolean tagsEditable;
    private final String footerNote;
    private final String actionLabel;
    private final SaveAction onAction;
    private final @Nullable ModalExtension extension;

    // Thumbnail data
    private final int thumbWidthPx, thumbHeightPx;
    private final int[] thumbPixels;
    private final byte[] thumbAssetData;
    private final @Nullable AssetType thumbAssetType;

    // ── Runtime state ──
    private EditBox nameInput;
    private MultiLineEditBox descInput;
    private TagInputWidget tagInput;
    private boolean extensionExpanded;

    // Thumbnail texture
    private DynamicTexture thumbTex;
    private ResourceLocation thumbLoc;
    private static final int THUMB_SIZE = 56;

    // Layout
    private int panelL, panelR, panelT, panelB;
    private static final int PANEL_W = 300;
    private static final int PAD = 8;
    private static final int INNER_PAD = 6;
    private static final int HEADER_H = 18;
    private static final int HERO_H = 60;
    private static final int FIELD_H = 18;
    private static final int BTN_H = 20;
    private static final int ACCENT_BAR_H = 3;

    // Computed button regions for click handling
    private int cancelBtnL, cancelBtnR, cancelBtnT, cancelBtnB;
    private int actionBtnL, actionBtnR, actionBtnT, actionBtnB;
    private int closeBtnL, closeBtnT, closeBtnR, closeBtnB;
    private int extHeaderT, extHeaderB, extContentT;
    private int extContentH;

    private SaveAssetScreen(Builder b) {
        super(Component.literal(b.headerTitle));
        this.parent = b.parent;
        this.mode = b.mode;
        this.headerTitle = b.headerTitle;
        this.typeName = b.typeName;
        this.accentColor = b.accentColor;
        this.heroTitle = b.heroTitle;
        this.heroSubtitle = b.heroSubtitle;
        this.defaultName = b.defaultName;
        this.defaultDescription = b.defaultDescription;
        this.nameEditable = b.nameEditable;
        this.defaultTags = b.defaultTags;
        this.tagsEditable = b.tagsEditable;
        this.footerNote = b.footerNote;
        this.actionLabel = b.actionLabel;
        this.onAction = b.onAction;
        this.extension = b.extension;
        this.thumbWidthPx = b.thumbWidthPx;
        this.thumbHeightPx = b.thumbHeightPx;
        this.thumbPixels = b.thumbPixels;
        this.thumbAssetData = b.thumbAssetData;
        this.thumbAssetType = b.thumbAssetType;
        this.extensionExpanded = extension != null && extension.startExpanded();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Builder
    // ═══════════════════════════════════════════════════════════════

    public static Builder saveLocal(Screen parent) {
        Builder b = new Builder(parent, Mode.SAVE_LOCAL);
        b.headerTitle = "Save to Asset Shelf";
        b.footerNote = "saves to local disk. publish to server\nlater from the browser.";
        b.actionLabel = "save local";
        return b;
    }

    public static Builder edit(Screen parent) {
        Builder b = new Builder(parent, Mode.EDIT);
        b.headerTitle = "Edit Asset";
        b.footerNote = "editing **local** asset metadata.";
        b.actionLabel = "save changes";
        return b;
    }

    public static Builder publish(Screen parent) {
        Builder b = new Builder(parent, Mode.PUBLISH);
        b.headerTitle = "Publish to server";
        b.footerNote = "a copy stays on your local disk. the\npublished copy becomes immutable except\nby you or ops.";
        b.actionLabel = "publish";
        return b;
    }

    public static class Builder {
        private final Screen parent;
        private final Mode mode;
        private String headerTitle;
        private String typeName = "ASSET";
        private int accentColor = 0xFFC47840;
        private @Nullable String heroTitle;
        private String heroSubtitle = "";
        private String defaultName = "";
        private String defaultDescription = "";
        private boolean nameEditable = true;
        private List<String> defaultTags = List.of();
        private boolean tagsEditable = true;
        private String footerNote = "";
        private String actionLabel = "save";
        private SaveAction onAction = (n, d, t) -> {};
        private @Nullable ModalExtension extension;
        private int thumbWidthPx, thumbHeightPx;
        private int[] thumbPixels;
        private byte[] thumbAssetData;
        private @Nullable AssetType thumbAssetType;

        private Builder(Screen parent, Mode mode) {
            this.parent = parent;
            this.mode = mode;
        }

        /** Set the asset type display name (shown in hero bar) and accent color. */
        public Builder assetTypeInfo(String displayName, int accentColor) {
            this.typeName = displayName.toUpperCase();
            this.accentColor = accentColor;
            return this;
        }

        /** Provide raw ARGB pixel data for the thumbnail preview. */
        public Builder thumbnail(int widthPx, int heightPx, int[] pixelsArgb) {
            this.thumbWidthPx = widthPx;
            this.thumbHeightPx = heightPx;
            this.thumbPixels = pixelsArgb;
            return this;
        }

        /** Provide asset bytes + type for thumbnail rendering via AssetType.renderThumbnail. */
        public Builder thumbnailFromBytes(byte[] assetData, AssetType type, int widthPx, int heightPx) {
            this.thumbAssetData = assetData;
            this.thumbAssetType = type;
            this.thumbWidthPx = widthPx;
            this.thumbHeightPx = heightPx;
            return this;
        }

        /** Bold title in the hero section (typically used in publish mode). */
        public Builder heroTitle(String title) { this.heroTitle = title; return this; }

        /** Description or metadata line in the hero section. */
        public Builder heroSubtitle(String sub) { this.heroSubtitle = sub; return this; }

        /** Pre-filled name. */
        public Builder defaultName(String name) { this.defaultName = name; return this; }

        /** Pre-filled description. */
        public Builder defaultDescription(String desc) { this.defaultDescription = desc; return this; }

        /** Whether the name field is editable. */
        public Builder nameEditable(boolean e) { this.nameEditable = e; return this; }

        /** Pre-filled tags. */
        public Builder defaultTags(List<String> tags) { this.defaultTags = tags; return this; }

        /** Whether tags can be added/removed. */
        public Builder tagsEditable(boolean e) { this.tagsEditable = e; return this; }

        /** Footer note text (supports \n for line breaks). */
        public Builder footerNote(String note) { this.footerNote = note; return this; }

        /** Label for the primary action button. */
        public Builder actionLabel(String label) { this.actionLabel = label; return this; }

        /** Callback invoked when the action button is clicked. Receives (name, description, tags). */
        public Builder onAction(SaveAction cb) { this.onAction = cb; return this; }

        /** Optional mod-specific extension section. */
        public Builder extension(@Nullable ModalExtension ext) { this.extension = ext; return this; }

        public SaveAssetScreen build() {
            return new SaveAssetScreen(this);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  init
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        buildThumbnail();

        int contentW = PANEL_W - PAD * 2;

        // Calculate total panel height
        int h = HEADER_H          // header bar
            + INNER_PAD
            + HERO_H              // hero section
            + INNER_PAD
            + 10 + FIELD_H + 2   // NAME label + input
            + INNER_PAD
            + 10 + 36 + 2        // DESCRIPTION label + multi-line input
            + INNER_PAD
            + 10 + FIELD_H + 2;  // TAGS label + input (minimum 1 row)

        if (extension != null) {
            h += INNER_PAD + 16;  // collapsed header
            if (extensionExpanded) {
                extContentH = 40; // estimate; re-measured during render
                h += extContentH + 4;
            }
        }

        h += INNER_PAD;
        // Footer note lines
        String[] noteLines = footerNote.split("\n");
        h += noteLines.length * 10 + 4;
        h += BTN_H + INNER_PAD;  // buttons + bottom pad

        panelL = (this.width - PANEL_W) / 2;
        panelR = panelL + PANEL_W;
        panelT = (this.height - h) / 2;
        panelB = panelT + h;

        // Name input
        int cx = panelL + PAD;
        int fieldW = contentW;
        int nameY = panelT + HEADER_H + INNER_PAD + HERO_H + INNER_PAD + 10;

        nameInput = new EditBox(this.font, cx, nameY, fieldW, FIELD_H, Component.literal("Name"));
        nameInput.setMaxLength(128);
        nameInput.setValue(defaultName);
        nameInput.setEditable(nameEditable);
        if (!nameEditable) {
            nameInput.setTextColor(INK_DIM);
        }
        addRenderableWidget(nameInput);
        if (nameEditable) setInitialFocus(nameInput);

        // Description input (multi-line, 3 rows)
        int descLabelY = nameY + FIELD_H + 2 + INNER_PAD;
        int descY = descLabelY + 10;
        int descH = 36; // ~3 lines
        descInput = new MultiLineEditBox(this.font, cx, descY, fieldW, descH,
            Component.literal("description..."), Component.literal("Description"));
        descInput.setCharacterLimit(512);
        descInput.setValue(defaultDescription);
        if (!nameEditable) descInput.active = false;
        addRenderableWidget(descInput);

        // Tag input
        int tagLabelY = descY + descH + 2 + INNER_PAD;
        int tagY = tagLabelY + 10;
        tagInput = new TagInputWidget(cx, tagY, fieldW, new ArrayList<>(defaultTags), tagsEditable);
        addRenderableWidget(tagInput);
    }

    private void buildThumbnail() {
        releaseThumbnail();
        NativeImage image = new NativeImage(THUMB_SIZE, THUMB_SIZE, true);

        if (thumbAssetData != null && thumbAssetType != null) {
            // Fill with card bg
            fillImage(image, CARD_BG);
            thumbAssetType.renderThumbnail(thumbAssetData, image, THUMB_SIZE);
        } else if (thumbPixels != null) {
            // Render from raw pixels
            float scale = Math.min((float) THUMB_SIZE / thumbWidthPx, (float) THUMB_SIZE / thumbHeightPx);
            int dstW = Math.max(1, Math.round(thumbWidthPx * scale));
            int dstH = Math.max(1, Math.round(thumbHeightPx * scale));
            int offX = (THUMB_SIZE - dstW) / 2;
            int offY = (THUMB_SIZE - dstH) / 2;

            // Checkerboard background
            for (int y = 0; y < THUMB_SIZE; y++)
                for (int x = 0; x < THUMB_SIZE; x++) {
                    boolean check = ((x / 4) + (y / 4)) % 2 == 0;
                    image.setPixelRGBA(x, y, argbToAbgr(check ? 0xFF3A3734 : 0xFF44413D));
                }

            for (int y = 0; y < dstH; y++) {
                for (int x = 0; x < dstW; x++) {
                    int srcX = Math.min((int) (x / scale), thumbWidthPx - 1);
                    int srcY = Math.min((int) (y / scale), thumbHeightPx - 1);
                    int color = thumbPixels[srcY * thumbWidthPx + srcX];
                    if (((color >> 24) & 0xFF) > 0) {
                        image.setPixelRGBA(offX + x, offY + y, argbToAbgr(color));
                    }
                }
            }
        } else {
            // Dotted placeholder
            fillImage(image, CARD_BG);
            for (int y = 0; y < THUMB_SIZE; y += 4)
                for (int x = 0; x < THUMB_SIZE; x += 4)
                    image.setPixelRGBA(x, y, argbToAbgr(INK_FAINT));
        }

        thumbTex = new DynamicTexture(image);
        thumbLoc = Minecraft.getInstance().getTextureManager()
            .register("assetshelf_save_thumb", thumbTex);
    }

    private void releaseThumbnail() {
        if (thumbLoc != null) {
            Minecraft.getInstance().getTextureManager().release(thumbLoc);
            thumbLoc = null;
        }
        if (thumbTex != null) {
            thumbTex.close();
            thumbTex = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  render
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Dimmed background
        this.renderTransparentBackground(g);

        Font f = this.font;
        int cx = panelL + PAD;
        int contentW = PANEL_W - PAD * 2;

        // ── Drop shadow ──
        g.fill(panelL + 3, panelT + 3, panelR + 3, panelB + 3, 0x60000000);

        // ── Panel background ──
        g.fill(panelL, panelT, panelR, panelB, PAPER);
        g.renderOutline(panelL, panelT, PANEL_W, panelB - panelT, RULE_DARK);

        // ── Header bar ──
        int hdrB = panelT + HEADER_H;
        g.fill(panelL + 1, panelT + 1, panelR - 1, hdrB, TITLE_BAR);
        g.drawString(f, headerTitle, panelL + PAD, panelT + 5, TITLE_TEXT, false);

        // Close button (X)
        String closeX = "\u2715";
        closeBtnR = panelR - PAD;
        closeBtnL = closeBtnR - f.width(closeX) - 2;
        closeBtnT = panelT + 3;
        closeBtnB = hdrB - 2;
        boolean closeHover = mouseX >= closeBtnL && mouseX <= closeBtnR
            && mouseY >= closeBtnT && mouseY <= closeBtnB;
        g.drawString(f, closeX, closeBtnL, closeBtnT + 1,
            closeHover ? 0xFFFF8888 : TITLE_TEXT, false);

        int dy = hdrB + INNER_PAD;

        // ── Hero section (two columns) ──
        renderHero(g, f, cx, dy, contentW, mouseX, mouseY);
        dy += HERO_H + INNER_PAD;

        // ── NAME field ──
        g.drawString(f, "NAME", cx, dy, INK_DIM, false);
        dy += 10;
        // nameInput renders via super.render
        dy += FIELD_H + 2 + INNER_PAD;

        // ── DESCRIPTION field ──
        g.drawString(f, "DESCRIPTION", cx, dy, INK_DIM, false);
        dy += 10;
        // descInput renders via super.render
        dy += FIELD_H + 2 + INNER_PAD;

        // ── TAGS field ──
        String tagsLabel = tagsEditable ? "TAGS" : "TAGS (KEPT FROM LOCAL)";
        g.drawString(f, tagsLabel, cx, dy, INK_DIM, false);
        dy += 10;
        // tagInput renders via super.render
        dy += tagInput.getComputedHeight() + INNER_PAD;

        // ── Extension section ──
        if (extension != null) {
            renderExtension(g, f, cx, dy, contentW, mouseX, mouseY);
            dy += 16; // header
            if (extensionExpanded) {
                dy += extContentH + 4;
            }
            dy += INNER_PAD;
        }

        // ── Footer note ──
        String[] noteLines = footerNote.split("\n");
        for (String line : noteLines) {
            // Render with bold markers: **text** → rendered in white/bright
            renderNoteLineWithBold(g, f, line, cx, dy);
            dy += 10;
        }
        dy += 4;

        // ── Buttons ──
        int btnGap = 8;
        int cancelW = 80;
        int actionW = contentW - cancelW - btnGap;

        // Cancel (ghost)
        cancelBtnL = cx;
        cancelBtnR = cx + cancelW;
        cancelBtnT = dy;
        cancelBtnB = dy + BTN_H;
        boolean cancelHover = mouseX >= cancelBtnL && mouseX < cancelBtnR
            && mouseY >= cancelBtnT && mouseY < cancelBtnB;
        g.fill(cancelBtnL, cancelBtnT, cancelBtnR, cancelBtnB,
            cancelHover ? PAPER_WARM : CARD_BG);
        g.renderOutline(cancelBtnL, cancelBtnT, cancelW, BTN_H, RULE);
        drawCentered(g, f, "cancel", cancelBtnL + cancelW / 2, cancelBtnT + 6, INK_DIM);

        // Action (filled gold)
        actionBtnL = cx + cancelW + btnGap;
        actionBtnR = cx + contentW;
        actionBtnT = dy;
        actionBtnB = dy + BTN_H;
        boolean actionHover = mouseX >= actionBtnL && mouseX < actionBtnR
            && mouseY >= actionBtnT && mouseY < actionBtnB;
        boolean canAct = !nameInput.getValue().trim().isEmpty();
        int actionCol = canAct ? (actionHover ? BTN_GOLD_HVR : BTN_GOLD) : RULE;

        // Drop shadow
        g.fill(actionBtnL + 2, actionBtnT + 2, actionBtnR + 2, actionBtnB + 2, 0x30000000);
        g.fill(actionBtnL, actionBtnT, actionBtnR, actionBtnB, actionCol);
        g.renderOutline(actionBtnL, actionBtnT, actionBtnR - actionBtnL, BTN_H, RULE_DARK);
        drawCentered(g, f, actionLabel, (actionBtnL + actionBtnR) / 2, actionBtnT + 6,
            canAct ? INK : INK_FAINT);

        // Update panel bottom based on actual content
        panelB = dy + BTN_H + INNER_PAD;

        // Widgets (EditBox, TagInputWidget)
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderHero(GuiGraphics g, Font f, int cx, int dy, int contentW, int mx, int my) {
        int thumbDrawSize = THUMB_SIZE;
        int thumbX = cx;
        int thumbY = dy + (HERO_H - thumbDrawSize) / 2;

        // Thumbnail border
        g.fill(thumbX - 1, thumbY - 1, thumbX + thumbDrawSize + 1, thumbY + thumbDrawSize + 1, RULE_DARK);
        if (thumbLoc != null) {
            g.blit(thumbLoc, thumbX, thumbY, thumbDrawSize, thumbDrawSize,
                0f, 0f, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE);
        }

        // Dimensions label on thumbnail
        String dims = "[ " + thumbWidthPx + "\u00D7" + thumbHeightPx + " ]";
        int dimsW = f.width(dims);
        int dimsX = thumbX + (thumbDrawSize - dimsW) / 2;
        int dimsY = thumbY + thumbDrawSize - 10;
        g.fill(dimsX - 2, dimsY - 1, dimsX + dimsW + 2, dimsY + 9, 0xAA000000);
        g.drawString(f, dims, dimsX, dimsY, 0xFFCCCCCC, false);

        // Right column: accent bar + text
        int rightX = thumbX + thumbDrawSize + PAD;
        int rightW = contentW - thumbDrawSize - PAD;
        int barY = dy + 4;

        // Accent bar
        g.fill(rightX, barY, rightX + rightW, barY + ACCENT_BAR_H, accentColor);
        int textY = barY + ACCENT_BAR_H + 4;

        // Type name label
        g.drawString(f, typeName, rightX, textY, accentColor, false);
        textY += 12;

        // Hero title (bold, publish mode)
        if (heroTitle != null && !heroTitle.isEmpty()) {
            g.drawString(f, heroTitle, rightX, textY, INK, false);
            textY += 12;
        }

        // Hero subtitle (wraps)
        if (!heroSubtitle.isEmpty()) {
            for (String line : heroSubtitle.split("\n")) {
                g.drawString(f, line, rightX, textY, INK_DIM, false);
                textY += 10;
            }
        }
    }

    private void renderExtension(GuiGraphics g, Font f, int cx, int dy, int contentW, int mx, int my) {
        int cardBg = ShelfPalette.tintOver(extension.tintColor(), 25);
        int cardBorder = ShelfPalette.tintOver(extension.tintColor(), 60);

        extHeaderT = dy;
        extHeaderB = dy + 16;

        // Header background
        g.fill(cx, extHeaderT, cx + contentW, extHeaderB, cardBorder);
        g.fill(cx + 1, extHeaderT + 1, cx + contentW - 1, extHeaderB - 1, cardBg);

        // Expand/collapse toggle
        String toggle = extensionExpanded ? "\u25BC " : "+ ";
        g.drawString(f, toggle + extension.headerLabel(), cx + 4, extHeaderT + 4,
            extension.tintColor(), false);

        if (extensionExpanded) {
            extContentT = extHeaderB;
            // Content background
            g.fill(cx, extContentT, cx + contentW, extContentT + extContentH + 4, cardBg);
            g.fill(cx + 1, extContentT, cx + contentW - 1, extContentT + extContentH + 4, cardBg);

            // Let extension render
            int actualH = extension.renderContent(g, cx + 4, extContentT + 2,
                contentW - 8, mx, my);
            extContentH = Math.max(actualH, 10);

            // Bottom border
            g.fill(cx, extContentT + extContentH + 3, cx + contentW,
                extContentT + extContentH + 4, cardBorder);
        }
    }

    private void renderNoteLineWithBold(GuiGraphics g, Font f, String line, int x, int y) {
        // Simple **bold** parsing
        int cx = x;
        int idx = 0;
        while (idx < line.length()) {
            int boldStart = line.indexOf("**", idx);
            if (boldStart < 0) {
                g.drawString(f, line.substring(idx), cx, y, INK_DIM, false);
                break;
            }
            // Text before bold
            String before = line.substring(idx, boldStart);
            g.drawString(f, before, cx, y, INK_DIM, false);
            cx += f.width(before);

            int boldEnd = line.indexOf("**", boldStart + 2);
            if (boldEnd < 0) {
                g.drawString(f, line.substring(boldStart), cx, y, INK_DIM, false);
                break;
            }
            String bold = line.substring(boldStart + 2, boldEnd);
            g.drawString(f, bold, cx, y, INK, false);
            cx += f.width(bold);
            idx = boldEnd + 2;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Input
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return super.mouseClicked(mx, my, btn);

        // Close button
        if (mx >= closeBtnL && mx <= closeBtnR && my >= closeBtnT && my <= closeBtnB) {
            onClose();
            return true;
        }

        // Cancel button
        if (mx >= cancelBtnL && mx < cancelBtnR && my >= cancelBtnT && my < cancelBtnB) {
            onClose();
            return true;
        }

        // Action button
        if (mx >= actionBtnL && mx < actionBtnR && my >= actionBtnT && my < actionBtnB) {
            doAction();
            return true;
        }

        // Extension header toggle
        if (extension != null && my >= extHeaderT && my < extHeaderB
            && mx >= panelL + PAD && mx < panelR - PAD) {
            extensionExpanded = !extensionExpanded;
            rebuildLayout();
            return true;
        }

        // Extension content clicks
        if (extension != null && extensionExpanded && my >= extContentT
            && my < extContentT + extContentH) {
            if (extension.mouseClicked(mx, my, btn)) return true;
        }

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Enter on name field = action
        if (key == 257 && nameInput.isFocused() && !nameInput.getValue().trim().isEmpty()) {
            doAction();
            return true;
        }
        // Escape
        if (key == 256) {
            onClose();
            return true;
        }
        // Extension keys
        if (extension != null && extensionExpanded && extension.keyPressed(key, scan, mods)) {
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void doAction() {
        String name = nameInput.getValue().trim();
        if (name.isEmpty()) return;
        String description = descInput.getValue().trim();
        List<String> tags = tagInput.getTags();
        onAction.accept(name, description, tags);
        onClose();
    }

    private void rebuildLayout() {
        clearWidgets();
        init();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        super.removed();
        releaseThumbnail();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════

    private static void drawCentered(GuiGraphics g, Font f, String text, int cx, int y, int color) {
        g.drawString(f, text, cx - f.width(text) / 2, y, color, false);
    }

    private static void fillImage(NativeImage img, int argb) {
        int abgr = argbToAbgr(argb);
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                img.setPixelRGBA(x, y, abgr);
    }

    static int argbToAbgr(int argb) {
        return (argb & 0xFF00FF00)
            | ((argb & 0x00FF0000) >> 16)
            | ((argb & 0x000000FF) << 16);
    }
}
