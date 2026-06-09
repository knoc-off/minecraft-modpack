package dev.assetshelf.client.gui;

/**
 * Shared color palette for Asset Shelf UI components.
 * Used by ShelfBrowserScreen, SaveAssetScreen, TagInputWidget, etc.
 */
public final class ShelfPalette {

    private ShelfPalette() {}

    // ── Paper / backgrounds ──
    public static final int PAPER       = 0xFFF4EFE4;
    public static final int PAPER_WARM  = 0xFFF8F2E6;
    public static final int CARD_BG     = 0xFFFAFAF5;
    public static final int HEADER_BG   = 0xFFEDE6D8;

    // ── Ink / text ──
    public static final int INK         = 0xFF2D2B28;
    public static final int INK_DIM     = 0xFF7A7468;
    public static final int INK_FAINT   = 0xFF9E9890;

    // ── Rules / borders ──
    public static final int RULE        = 0xFFD5CEBF;
    public static final int RULE_DARK   = 0xFF65615C;

    // ── Buttons ──
    public static final int BTN_GOLD    = 0xFFD4A858;
    public static final int BTN_GOLD_HVR= 0xFFDEB564;

    // ── Leather cover (browser) ──
    public static final int LEATHER     = 0xFF3A2818;
    public static final int LEATHER_LITE= 0xFF4D3A28;
    public static final int LEATHER_DARK= 0xFF261A10;
    public static final int COVER_EDGE  = 0xFF1A1410;

    // ── Title bar ──
    public static final int TITLE_BAR   = 0xFF1A1A2E;
    public static final int TITLE_TEXT  = 0xFFCCCCCC;

    // ── Binder (browser) ──
    public static final int BINDER_BG   = 0xFF2D2B28;
    public static final int BINDER_INACT= 0xFF3A3734;

    /** Mix an accent color at a given alpha over paper for tinted backgrounds. */
    public static int tintOver(int accentArgb, int alpha) {
        float a = alpha / 255f;
        int br = (PAPER >> 16) & 0xFF, bg = (PAPER >> 8) & 0xFF, bb = PAPER & 0xFF;
        int ar = (accentArgb >> 16) & 0xFF, ag = (accentArgb >> 8) & 0xFF, ab = accentArgb & 0xFF;
        int r = (int)(br + (ar - br) * a);
        int g = (int)(bg + (ag - bg) * a);
        int b = (int)(bb + (ab - bb) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
