package dev.paintcraft.client.gui;

import org.lwjgl.glfw.GLFW;

/**
 * Editor actions that can be triggered by a (remappable) single key press inside the paint editor.
 * Ctrl-combos (undo/redo/paste/debug) stay hardcoded; these are the plain-key bindings.
 */
public enum EditorAction {
    SELECT_PENCIL("Select Pencil", GLFW.GLFW_KEY_P),
    SELECT_BRUSH ("Select Brush",  GLFW.GLFW_KEY_B),
    SELECT_ERASER("Select Eraser", GLFW.GLFW_KEY_E),
    SELECT_FILL  ("Select Fill",   GLFW.GLFW_KEY_G),
    SELECT_LINE  ("Select Line",   GLFW.GLFW_KEY_L),
    INCREASE_SIZE("Increase Size", GLFW.GLFW_KEY_RIGHT_BRACKET),
    DECREASE_SIZE("Decrease Size", GLFW.GLFW_KEY_LEFT_BRACKET),
    INCREASE_OPACITY("Increase Opacity", GLFW.GLFW_KEY_PERIOD),
    DECREASE_OPACITY("Decrease Opacity", GLFW.GLFW_KEY_COMMA),
    SWAP_MOUSE_TOOLS("Swap Mouse 1/2 Tools", GLFW.GLFW_KEY_X),
    PAN_UP   ("Pan Up",    GLFW.GLFW_KEY_UP),
    PAN_DOWN ("Pan Down",  GLFW.GLFW_KEY_DOWN),
    PAN_LEFT ("Pan Left",  GLFW.GLFW_KEY_LEFT),
    PAN_RIGHT("Pan Right", GLFW.GLFW_KEY_RIGHT),
    M2_INCREASE_SIZE("M2 Increase Size", GLFW.GLFW_KEY_UNKNOWN),
    M2_DECREASE_SIZE("M2 Decrease Size", GLFW.GLFW_KEY_UNKNOWN);

    /** Human-readable name shown in the settings screen. */
    public final String label;
    /** Default GLFW key code ({@link GLFW#GLFW_KEY_UNKNOWN} = unbound by default). */
    public final int defaultKey;

    EditorAction(String label, int defaultKey) {
        this.label = label;
        this.defaultKey = defaultKey;
    }
}
