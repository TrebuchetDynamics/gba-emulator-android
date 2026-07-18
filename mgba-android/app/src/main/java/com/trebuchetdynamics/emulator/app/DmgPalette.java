package com.trebuchetdynamics.emulator.app;

/**
 * A monochrome palette for original Game Boy (DMG) games: four opaque ARGB
 * shades from lightest (pixel value 0) to darkest (pixel value 3).
 *
 * <p>No {@code android.*} imports, so it is JVM-testable. Only DMG (Game Boy)
 * rendering consults this — Game Boy Color and GBA are unaffected.
 *
 * <p>Persisted by ordinal in SharedPreferences — append new palettes only;
 * never reorder or remove existing ones, or stored user choices remap silently.
 */
enum DmgPalette {
    /** The classic green Game Boy LCD look. */
    GREEN(0xFF9BBC0F, 0xFF8BAC0F, 0xFF306230, 0xFF0F380F),
    /** Neutral grayscale (Game Boy Pocket style). */
    GREY(0xFFFFFFFF, 0xFFA9A9A9, 0xFF545454, 0xFF000000);

    /** The palette used when the player has not chosen one. */
    static final DmgPalette DEFAULT = GREY;

    private final int[] shades;

    DmgPalette(int lightest, int light, int dark, int darkest) {
        this.shades = new int[] { lightest, light, dark, darkest };
    }

    /** The four ARGB shades, lightest (index 0) to darkest (index 3); a copy. */
    int[] shades() {
        return shades.clone();
    }

    /** Short label for a settings picker. */
    String label() {
        return this == GREY ? "Grey" : "Green";
    }
}
