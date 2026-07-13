package dev.paintcraft.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;

/**
 * Editor settings + keybind remapping. Opened from the paint editor; returns to it on close.
 */
public final class EditorSettingsScreen extends Screen {

    private final Screen parent;
    private final EditorSettings settings;
    private final EnumMap<EditorAction, Button> keyButtons = new EnumMap<>(EditorAction.class);

    /** Action currently listening for a new key, or null. */
    private EditorAction listening = null;

    private int rowsTop;
    private static final int ROW_H = 22;
    private static final int LABEL_X_OFFSET = -150;
    private static final int BTN_W = 120;
    // Keybind rows are laid out in two columns to keep the screen compact.
    private static final int KB_BTN_W = 88;
    private static final int COL0_LABEL_DX = -250, COL0_BTN_DX = -148;
    private static final int COL1_LABEL_DX = 8,    COL1_BTN_DX = 110;

    public EditorSettingsScreen(Screen parent, EditorSettings settings) {
        super(Component.literal("Editor Settings"));
        this.parent = parent;
        this.settings = settings;
    }

    private int kbHalf() {
        return (EditorAction.values().length + 1) / 2;
    }

    @Override
    protected void init() {
        keyButtons.clear();
        int cx = this.width / 2;
        int y = 40;

        // Toggles / cycles (single column, right of their labels).
        addRenderableWidget(Button.builder(scrollLabel(), b -> {
            settings.invertScroll = !settings.invertScroll;
            b.setMessage(scrollLabel());
        }).bounds(cx + 10, y, BTN_W, 20).build());
        y += ROW_H;

        addRenderableWidget(Button.builder(eyedropLabel(), b -> {
            settings.eyedropperInheritOpacity = !settings.eyedropperInheritOpacity;
            b.setMessage(eyedropLabel());
        }).bounds(cx + 10, y, BTN_W, 20).build());
        y += ROW_H;

        addRenderableWidget(Button.builder(scrollModLabel(), b -> {
            EditorSettings.ScrollModifier[] vals = EditorSettings.ScrollModifier.values();
            settings.scrollOpacityModifier = vals[(settings.scrollOpacityModifier.ordinal() + 1) % vals.length];
            b.setMessage(scrollModLabel());
        }).bounds(cx + 10, y, BTN_W, 20).build());
        y += ROW_H;

        addRenderableWidget(Button.builder(unifiedSizeLabel(), b -> {
            settings.unifiedSize = !settings.unifiedSize;
            b.setMessage(unifiedSizeLabel());
        }).bounds(cx + 10, y, BTN_W, 20).build());
        y += ROW_H;

        addRenderableWidget(Button.builder(unifiedOpacityLabel(), b -> {
            settings.unifiedOpacity = !settings.unifiedOpacity;
            b.setMessage(unifiedOpacityLabel());
        }).bounds(cx + 10, y, BTN_W, 20).build());
        y += ROW_H + 6;

        // Keybinds — two columns.
        rowsTop = y;
        int half = kbHalf();
        EditorAction[] acts = EditorAction.values();
        for (int i = 0; i < acts.length; i++) {
            boolean col0 = i < half;
            int row = col0 ? i : i - half;
            int bx = cx + (col0 ? COL0_BTN_DX : COL1_BTN_DX);
            int by = rowsTop + row * ROW_H;
            EditorAction a = acts[i];
            Button b = Button.builder(keyLabel(a), btn -> beginListening(a))
                .bounds(bx, by, KB_BTN_W, 20).build();
            keyButtons.put(a, b);
            addRenderableWidget(b);
        }
        y = rowsTop + half * ROW_H;

        addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> resetDefaults())
            .bounds(cx - 155, y + 6, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(cx + 5, y + 6, 150, 20).build());
    }

    private Component scrollLabel() {
        return Component.literal("Invert Scroll: " + (settings.invertScroll ? "ON" : "OFF"));
    }

    private Component eyedropLabel() {
        return Component.literal("Eyedropper: " + (settings.eyedropperInheritOpacity ? "Inherit alpha" : "Preserve alpha"));
    }

    private Component scrollModLabel() {
        String m = switch (settings.scrollOpacityModifier) {
            case ALT -> "Alt";
            case SHIFT -> "Shift";
            case CTRL -> "Ctrl";
        };
        return Component.literal("Opacity modifier: " + m);
    }

    private Component unifiedSizeLabel() {
        return Component.literal("Unified size: " + (settings.unifiedSize ? "ON" : "OFF"));
    }

    private Component unifiedOpacityLabel() {
        return Component.literal("Unified opacity: " + (settings.unifiedOpacity ? "ON" : "OFF"));
    }

    private Component keyLabel(EditorAction a) {
        if (listening == a) return Component.literal("> press a key <");
        return Component.literal(keyName(settings.keyFor(a)));
    }

    private static String keyName(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) return "\u2014"; // em dash = unbound
        return InputConstants.Type.KEYSYM.getOrCreate(keyCode).getDisplayName().getString();
    }

    private void beginListening(EditorAction a) {
        listening = a;
        keyButtons.get(a).setMessage(keyLabel(a));
    }

    private void resetDefaults() {
        for (EditorAction a : EditorAction.values()) settings.keybinds.put(a, a.defaultKey);
        listening = null;
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                listening = null;
            } else if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                settings.bind(listening, GLFW.GLFW_KEY_UNKNOWN);
                listening = null;
            } else {
                settings.bind(listening, keyCode);
                listening = null;
            }
            rebuildWidgets();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);

        int cx = this.width / 2;
        gfx.drawString(this.font, "Scroll direction", cx + LABEL_X_OFFSET, 46, 0xFFAAAAAA);
        gfx.drawString(this.font, "Eyedropper opacity", cx + LABEL_X_OFFSET, 46 + ROW_H, 0xFFAAAAAA);
        gfx.drawString(this.font, "Scroll opacity key", cx + LABEL_X_OFFSET, 46 + ROW_H * 2, 0xFFAAAAAA);
        gfx.drawString(this.font, "Unified size", cx + LABEL_X_OFFSET, 46 + ROW_H * 3, 0xFFAAAAAA);
        gfx.drawString(this.font, "Unified opacity", cx + LABEL_X_OFFSET, 46 + ROW_H * 4, 0xFFAAAAAA);

        // Keybind action labels, two columns matching init().
        int half = kbHalf();
        EditorAction[] acts = EditorAction.values();
        for (int i = 0; i < acts.length; i++) {
            boolean col0 = i < half;
            int row = col0 ? i : i - half;
            int lx = cx + (col0 ? COL0_LABEL_DX : COL1_LABEL_DX);
            int ly = rowsTop + row * ROW_H + 6;
            gfx.drawString(this.font, acts[i].label, lx, ly, 0xFFFFFFFF);
        }
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
        // Match the paint editor: skip the vanilla menu blur.
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
