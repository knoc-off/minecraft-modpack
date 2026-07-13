package dev.paintcraft.client.gui;

import java.util.EnumMap;
import java.util.Map;

/**
 * Mutable editor UX settings shared between {@link PaintScreen} and {@link EditorSettingsScreen}.
 * Persisted via {@link dev.paintcraft.client.EditorPrefs}.
 */
public final class EditorSettings {

    /** Which held modifier makes the scroll wheel adjust opacity instead of brush size. */
    public enum ScrollModifier { ALT, SHIFT, CTRL }

    /** Invert the scroll-wheel direction for every scroll-bound control (brush size, opacity). */
    public boolean invertScroll = false;

    /** Eyedropper: true = adopt the sampled pixel's alpha, false = keep the tool's current opacity. */
    public boolean eyedropperInheritOpacity = true;

    /** Modifier that switches scroll-wheel from size to opacity adjustment. */
    public ScrollModifier scrollOpacityModifier = ScrollModifier.ALT;

    /** When true, changing any tool's size applies to all tools. */
    public boolean unifiedSize = true;

    /** When true, changing any tool's opacity applies to all tools. */
    public boolean unifiedOpacity = false;

    /** Action → bound GLFW key code (GLFW_KEY_UNKNOWN = unbound). */
    public final EnumMap<EditorAction, Integer> keybinds = new EnumMap<>(EditorAction.class);

    public EditorSettings() {
        for (EditorAction a : EditorAction.values()) {
            keybinds.put(a, a.defaultKey);
        }
    }

    public int keyFor(EditorAction a) {
        Integer k = keybinds.get(a);
        return k == null ? org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN : k;
    }

    /** Find the action bound to {@code keyCode}, or null if none. */
    public EditorAction actionForKey(int keyCode) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN) return null;
        for (Map.Entry<EditorAction, Integer> e : keybinds.entrySet()) {
            if (e.getValue() != null && e.getValue() == keyCode) return e.getKey();
        }
        return null;
    }

    /** Bind {@code keyCode} to {@code action}, clearing any other action that held that key. */
    public void bind(EditorAction action, int keyCode) {
        if (keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN) {
            for (EditorAction a : EditorAction.values()) {
                if (a != action && keybinds.get(a) != null && keybinds.get(a) == keyCode) {
                    keybinds.put(a, org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN);
                }
            }
        }
        keybinds.put(action, keyCode);
    }
}
