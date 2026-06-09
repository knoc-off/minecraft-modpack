package dev.assetshelf.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.assetshelf.client.gui.ShelfPalette.*;

/**
 * A chip/tag input widget. Shows existing tags as removable chips
 * followed by an inline text input for adding new ones.
 * <p>
 * When {@code editable} is false, chips render without remove buttons
 * and no text input is shown.
 */
public class TagInputWidget extends AbstractWidget {

    private static final int MAX_TAGS = 16;
    private static final int MAX_TAG_LEN = 32;
    private static final int CHIP_H = 14;
    private static final int CHIP_PAD_X = 4;
    private static final int CHIP_GAP = 3;
    private static final int ROW_GAP = 2;
    private static final int CHIP_CLOSE_W = 8;

    private static final int CHIP_BG      = HEADER_BG;
    private static final int CHIP_BG_HVR  = 0xFFE0D8C8;
    private static final int CHIP_BORDER  = RULE;
    private static final int CHIP_TEXT    = INK;
    private static final int INPUT_HINT   = INK_FAINT;
    private static final int FIELD_BG     = 0xFFFFFFFF;
    private static final int FIELD_BORDER = RULE;
    private static final int CURSOR_COLOR = INK;

    private final List<String> tags;
    private final boolean editable;
    private String inputBuffer = "";
    private boolean focused = false;
    private int cursorTick = 0;

    // Cached layout: chip positions for hit testing
    private final List<ChipLayout> chipLayouts = new ArrayList<>();
    private int computedHeight;

    private record ChipLayout(String tag, int x, int y, int w, int closeX) {}

    public TagInputWidget(int x, int y, int width, List<String> initialTags, boolean editable) {
        super(x, y, width, CHIP_H + 6, Component.empty());
        this.tags = new ArrayList<>(initialTags);
        this.editable = editable;
        recomputeLayout();
    }

    public List<String> getTags() {
        return List.copyOf(tags);
    }

    public int getComputedHeight() {
        return computedHeight;
    }

    /** Recompute chip layout positions and widget height. */
    private void recomputeLayout() {
        chipLayouts.clear();
        Font font = Minecraft.getInstance().font;
        int cx = getX() + 3;
        int cy = getY() + 3;
        int maxX = getX() + getWidth() - 3;

        for (String tag : tags) {
            int chipW = CHIP_PAD_X + font.width(tag)
                + (editable ? CHIP_PAD_X + CHIP_CLOSE_W : CHIP_PAD_X);
            if (cx + chipW > maxX && cx > getX() + 3) {
                cx = getX() + 3;
                cy += CHIP_H + ROW_GAP;
            }
            int closeX = cx + chipW - CHIP_PAD_X - CHIP_CLOSE_W + 1;
            chipLayouts.add(new ChipLayout(tag, cx, cy, chipW, closeX));
            cx += chipW + CHIP_GAP;
        }

        // Account for input area on same line
        if (editable) {
            int inputHintW = font.width("type to add...") + 8;
            if (cx + inputHintW > maxX && cx > getX() + 3) {
                cy += CHIP_H + ROW_GAP;
            }
        }

        computedHeight = (cy - getY()) + CHIP_H + 6;
        this.height = computedHeight;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;

        // Outer field border + background
        g.fill(getX(), getY(), getX() + getWidth(), getY() + computedHeight, FIELD_BORDER);
        g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + computedHeight - 1, FIELD_BG);

        // Render chips
        for (int i = 0; i < chipLayouts.size(); i++) {
            ChipLayout cl = chipLayouts.get(i);
            boolean hovered = editable && mouseX >= cl.closeX && mouseX < cl.closeX + CHIP_CLOSE_W
                && mouseY >= cl.y && mouseY < cl.y + CHIP_H;
            int bg = hovered ? CHIP_BG_HVR : CHIP_BG;

            g.fill(cl.x, cl.y, cl.x + cl.w, cl.y + CHIP_H, CHIP_BORDER);
            g.fill(cl.x + 1, cl.y + 1, cl.x + cl.w - 1, cl.y + CHIP_H - 1, bg);
            g.drawString(font, cl.tag, cl.x + CHIP_PAD_X, cl.y + 3, CHIP_TEXT, false);

            if (editable) {
                int closeColor = hovered ? 0xFFCC4444 : INK_DIM;
                g.drawString(font, "\u00D7", cl.closeX, cl.y + 3, closeColor, false);
            }
        }

        // Render inline input area (after last chip)
        if (editable) {
            int inputX, inputY;
            if (chipLayouts.isEmpty()) {
                inputX = getX() + 4;
                inputY = getY() + 3;
            } else {
                ChipLayout last = chipLayouts.get(chipLayouts.size() - 1);
                inputX = last.x + last.w + CHIP_GAP;
                inputY = last.y;
                if (inputX + 30 > getX() + getWidth() - 3) {
                    inputX = getX() + 4;
                    inputY += CHIP_H + ROW_GAP;
                }
            }

            if (inputBuffer.isEmpty() && !focused) {
                g.drawString(font, "type to add...", inputX, inputY + 3, INPUT_HINT, false);
            } else {
                String display = inputBuffer;
                g.drawString(font, display, inputX, inputY + 3, CHIP_TEXT, false);
                // Blinking cursor
                if (focused && (cursorTick / 6) % 2 == 0) {
                    int curX = inputX + font.width(display);
                    g.fill(curX, inputY + 2, curX + 1, inputY + CHIP_H - 2, CURSOR_COLOR);
                }
            }
        }

        cursorTick++;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (!isMouseOver(mx, my)) {
            focused = false;
            return false;
        }
        if (!editable) return false;

        // Check chip close buttons
        for (int i = 0; i < chipLayouts.size(); i++) {
            ChipLayout cl = chipLayouts.get(i);
            if (mx >= cl.closeX && mx < cl.closeX + CHIP_CLOSE_W
                && my >= cl.y && my < cl.y + CHIP_H) {
                tags.remove(i);
                recomputeLayout();
                return true;
            }
        }

        focused = true;
        return true;
    }

    @Override
    public boolean isMouseOver(double mx, double my) {
        return mx >= getX() && mx < getX() + getWidth()
            && my >= getY() && my < getY() + computedHeight;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!focused || !editable) return false;

        // Enter = commit tag
        if (key == 257) {
            commitTag();
            return true;
        }
        // Backspace
        if (key == 259) {
            if (!inputBuffer.isEmpty()) {
                inputBuffer = inputBuffer.substring(0, inputBuffer.length() - 1);
            } else if (!tags.isEmpty()) {
                tags.remove(tags.size() - 1);
                recomputeLayout();
            }
            return true;
        }
        // Escape = unfocus
        if (key == 256) {
            focused = false;
            return true;
        }
        // Tab = commit if there's text
        if (key == 258 && !inputBuffer.isEmpty()) {
            commitTag();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!focused || !editable) return false;

        // Comma or semicolon = commit
        if (c == ',' || c == ';') {
            commitTag();
            return true;
        }

        // Only allow alphanumeric, hyphens, underscores
        if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
            if (inputBuffer.length() < MAX_TAG_LEN) {
                inputBuffer += Character.toLowerCase(c);
            }
            return true;
        }

        // Space = commit if there's text, else ignore
        if (c == ' ') {
            if (!inputBuffer.isEmpty()) commitTag();
            return true;
        }

        return false;
    }

    private void commitTag() {
        String tag = inputBuffer.trim().toLowerCase();
        inputBuffer = "";
        if (tag.isEmpty()) return;
        if (tags.size() >= MAX_TAGS) return;
        if (tags.contains(tag)) return;
        tags.add(tag);
        recomputeLayout();
    }

    @Override
    public void setFocused(boolean f) {
        this.focused = f;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    /** Static helper: render a list of tags as read-only chips (no widget instance needed). */
    public static void renderReadOnlyChips(GuiGraphics g, Font font, List<String> tags,
                                            int x, int y, int maxWidth) {
        renderClickableChips(g, font, tags, x, y, maxWidth, -1, -1);
    }

    /**
     * Render tags as clickable chips with hover effect. Returns null if no tag was clicked.
     * Call from both render (for visuals) and mouseClicked (for hit testing).
     */
    public static void renderClickableChips(GuiGraphics g, Font font, List<String> tags,
                                             int x, int y, int maxWidth,
                                             int mouseX, int mouseY) {
        int cx = x;
        int cy = y;
        for (String tag : tags) {
            int chipW = CHIP_PAD_X * 2 + font.width(tag);
            if (cx + chipW > x + maxWidth && cx > x) {
                cx = x;
                cy += CHIP_H + ROW_GAP;
            }
            boolean hovered = mouseX >= cx && mouseX < cx + chipW
                && mouseY >= cy && mouseY < cy + CHIP_H;
            g.fill(cx, cy, cx + chipW, cy + CHIP_H, CHIP_BORDER);
            g.fill(cx + 1, cy + 1, cx + chipW - 1, cy + CHIP_H - 1,
                hovered ? CHIP_BG_HVR : CHIP_BG);
            g.drawString(font, tag, cx + CHIP_PAD_X, cy + 3, CHIP_TEXT, false);
            cx += chipW + CHIP_GAP;
        }
    }

    /**
     * Hit-test clickable chips. Returns the tag string that was clicked, or null.
     */
    public static String hitTestChips(Font font, List<String> tags,
                                       int x, int y, int maxWidth,
                                       double mouseX, double mouseY) {
        int cx = x;
        int cy = y;
        for (String tag : tags) {
            int chipW = CHIP_PAD_X * 2 + font.width(tag);
            if (cx + chipW > x + maxWidth && cx > x) {
                cx = x;
                cy += CHIP_H + ROW_GAP;
            }
            if (mouseX >= cx && mouseX < cx + chipW
                && mouseY >= cy && mouseY < cy + CHIP_H) {
                return tag;
            }
            cx += chipW + CHIP_GAP;
        }
        return null;
    }

    /** Calculate height needed for read-only chip rendering. */
    public static int readOnlyChipsHeight(Font font, List<String> tags, int maxWidth) {
        if (tags.isEmpty()) return 0;
        int cx = 0;
        int rows = 1;
        for (String tag : tags) {
            int chipW = CHIP_PAD_X * 2 + font.width(tag);
            if (cx + chipW > maxWidth && cx > 0) {
                cx = 0;
                rows++;
            }
            cx += chipW + CHIP_GAP;
        }
        return rows * (CHIP_H + ROW_GAP) - ROW_GAP;
    }
}
