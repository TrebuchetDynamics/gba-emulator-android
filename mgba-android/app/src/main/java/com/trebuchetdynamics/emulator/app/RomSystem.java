package com.trebuchetdynamics.emulator.app;

/**
 * Which console a ROM targets, detected from the ROM's header bytes (content,
 * not file extension). No android.* imports — JVM-testable. Must NOT reference
 * MgbaSession (its static loadLibrary would break unit tests).
 */
enum RomSystem {
    GBA, GB, GBC, UNKNOWN;

    /** GB and GBC both run on mGBA's Game Boy core. */
    boolean isGameBoy() {
        return this == GB || this == GBC;
    }

    /**
     * True only for original monochrome Game Boy (DMG). The chosen DMG palette
     * applies here; Game Boy Color and GBA render their own colours and must be
     * left untouched.
     */
    boolean usesDmgPalette() {
        return this == GB;
    }

    /** Short label for the library badge. */
    String badge() {
        switch (this) {
            case GBA: return "GBA";
            case GB: return "GB";
            case GBC: return "GBC";
            default: return "?";
        }
    }

    private static final int[] NINTENDO_LOGO = {
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83,
            0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
            0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63,
            0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E };

    /** Tolerant parser for the stored library index: unknown/missing values default to GBA. */
    static RomSystem valueOfOrGba(String s) {
        if (s == null) {
            return GBA;
        }
        try {
            return RomSystem.valueOf(s);
        } catch (IllegalArgumentException e) {
            return GBA;
        }
    }

    static RomSystem detect(byte[] rom) {
        if (rom == null) {
            return UNKNOWN;
        }
        // Game Boy / Game Boy Color: the 48-byte Nintendo logo is far more specific
        // than the GBA fixed byte, so check it first to avoid a 0xB2==0x96 collision
        // in Game Boy program code.
        if (rom.length >= 0x150 && matchesNintendoLogo(rom)) {
            int cgb = rom[0x143] & 0xFF;
            return (cgb == 0x80 || cgb == 0xC0) ? GBC : GB;
        }
        // GBA: fixed value 0x96 at 0xB2 within the 192-byte cartridge header.
        if (rom.length >= 0xC0 && (rom[0xB2] & 0xFF) == 0x96) {
            return GBA;
        }
        return UNKNOWN;
    }

    private static boolean matchesNintendoLogo(byte[] rom) {
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            if ((rom[0x104 + i] & 0xFF) != NINTENDO_LOGO[i]) {
                return false;
            }
        }
        return true;
    }
}
