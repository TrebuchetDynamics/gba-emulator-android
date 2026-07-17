package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RomSystemTest {

    private static final int[] NINTENDO_LOGO = {
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83,
            0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
            0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63,
            0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E };

    private static byte[] gbaRom() {
        byte[] r = new byte[0x100];
        r[0xB2] = (byte) 0x96;
        return r;
    }

    private static byte[] gbRom(int cgbFlag) {
        byte[] r = new byte[0x150];
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            r[0x104 + i] = (byte) NINTENDO_LOGO[i];
        }
        r[0x143] = (byte) cgbFlag;
        return r;
    }

    @Test
    public void detectsGba() {
        assertEquals(RomSystem.GBA, RomSystem.detect(gbaRom()));
    }

    @Test
    public void detectsMonochromeGb() {
        assertEquals(RomSystem.GB, RomSystem.detect(gbRom(0x00)));
    }

    @Test
    public void detectsGbcByCgbFlag() {
        assertEquals(RomSystem.GBC, RomSystem.detect(gbRom(0x80)));
        assertEquals(RomSystem.GBC, RomSystem.detect(gbRom(0xC0)));
    }

    @Test
    public void gbLogoWinsOverIncidentalGbaByteCollision() {
        // A real GB ROM whose ordinary program code happens to place 0x96 at
        // offset 0xB2 (the GBA fixed-byte offset) must still detect as GB: the
        // 48-byte Nintendo logo is checked first because it is far more specific.
        byte[] r = gbRom(0x00);
        r[0xB2] = (byte) 0x96;
        assertEquals(RomSystem.GB, RomSystem.detect(r));
    }

    @Test
    public void unknownForGarbageOrShort() {
        assertEquals(RomSystem.UNKNOWN, RomSystem.detect(new byte[16]));
        assertEquals(RomSystem.UNKNOWN, RomSystem.detect(null));
        assertEquals(RomSystem.UNKNOWN, RomSystem.detect(new byte[0x150])); // no logo, no GBA byte
    }

    @Test
    public void isGameBoyGrouping() {
        assertTrue(RomSystem.GB.isGameBoy());
        assertTrue(RomSystem.GBC.isGameBoy());
        assertFalse(RomSystem.GBA.isGameBoy());
        assertFalse(RomSystem.UNKNOWN.isGameBoy());
    }
}
