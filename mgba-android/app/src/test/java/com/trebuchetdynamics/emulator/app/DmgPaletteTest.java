package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DmgPaletteTest {

    @Test
    public void everyPaletteHasFourOpaqueShades() {
        for (DmgPalette p : DmgPalette.values()) {
            int[] s = p.shades();
            assertEquals("palette " + p + " shade count", 4, s.length);
            for (int shade : s) {
                assertEquals("palette " + p + " alpha must be opaque", 0xFF, (shade >>> 24) & 0xFF);
            }
        }
    }

    @Test
    public void shadesRunLightestToDarkest() {
        for (DmgPalette p : DmgPalette.values()) {
            int[] s = p.shades();
            for (int i = 1; i < s.length; i++) {
                assertTrue("palette " + p + " shade " + i + " must be no lighter than " + (i - 1),
                        luminance(s[i]) <= luminance(s[i - 1]));
            }
        }
    }

    @Test
    public void shadesAccessorReturnsADefensiveCopy() {
        DmgPalette p = DmgPalette.GREEN;
        int[] s = p.shades();
        s[0] = 0;
        assertTrue("mutating the returned array must not alter the palette",
                p.shades()[0] != 0);
    }

    @Test
    public void greenAndGreyHaveExpectedEndpoints() {
        assertEquals(0xFF9BBC0F, DmgPalette.GREEN.shades()[0]);
        assertEquals(0xFF0F380F, DmgPalette.GREEN.shades()[3]);
        assertEquals(0xFFFFFFFF, DmgPalette.GREY.shades()[0]);
        assertEquals(0xFF000000, DmgPalette.GREY.shades()[3]);
    }

    @Test
    public void labelsAreDistinctAndNonEmpty() {
        assertEquals("Green", DmgPalette.GREEN.label());
        assertEquals("Grey", DmgPalette.GREY.label());
    }

    @Test
    public void defaultIsGrey() {
        assertEquals(DmgPalette.GREY, DmgPalette.DEFAULT);
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r * 299 + g * 587 + b * 114; // Rec. 601 weighted (×1000)
    }
}
