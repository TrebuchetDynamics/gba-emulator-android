package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

import org.junit.Test;

public class ControlLayoutTest {

    // Real device resolution (Galaxy S24 Ultra class), used so the regression
    // this task fixes is concrete rather than hypothetical.
    private static final float LANDSCAPE_WIDTH = 2340f;
    private static final float LANDSCAPE_HEIGHT = 1080f;
    private static final float PORTRAIT_WIDTH = 1080f;
    private static final float PORTRAIT_HEIGHT = 2340f;

    @Test
    public void landscapeControlsDoNotOverlapTheGameScreen() {
        ControlLayout layout = ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT);
        for (ControlLayout.Control control : layout.controls) {
            assertTrue("control key=" + control.key + " label=" + control.label
                            + " overlaps the game screen",
                    !intersects(
                            control.cx - control.halfWidth, control.cy - control.halfHeight,
                            control.cx + control.halfWidth, control.cy + control.halfHeight,
                            layout.gameLeft, layout.gameTop, layout.gameRight, layout.gameBottom));
        }
    }

    @Test
    public void landscapeControlsDoNotOverlapEachOther() {
        ControlLayout layout = ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT);
        for (int i = 0; i < layout.controls.size(); i++) {
            for (int j = i + 1; j < layout.controls.size(); j++) {
                ControlLayout.Control a = layout.controls.get(i);
                ControlLayout.Control b = layout.controls.get(j);
                assertTrue("controls " + a.label + " and " + b.label + " overlap",
                        !intersects(
                                a.cx - a.halfWidth, a.cy - a.halfHeight,
                                a.cx + a.halfWidth, a.cy + a.halfHeight,
                                b.cx - b.halfWidth, b.cy - b.halfHeight,
                                b.cx + b.halfWidth, b.cy + b.halfHeight));
            }
        }
    }

    @Test
    public void landscapeShouldersAreNotOversized() {
        ControlLayout layout = ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT);
        float unit = Math.min(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT);
        boolean checkedL = false;
        boolean checkedR = false;
        for (ControlLayout.Control control : layout.controls) {
            if (control.key == MgbaSession.KEY_L || control.key == MgbaSession.KEY_R) {
                assertTrue("shoulder " + control.label + " halfWidth=" + control.halfWidth
                                + " must be < unit*0.15f=" + (unit * 0.15f),
                        control.halfWidth < unit * 0.15f);
                checkedL |= control.key == MgbaSession.KEY_L;
                checkedR |= control.key == MgbaSession.KEY_R;
            }
        }
        assertTrue("expected to find L and R controls", checkedL && checkedR);
    }

    @Test
    public void landscapeFaceButtonsAreThumbSized() {
        ControlLayout layout = ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT);
        float unit = Math.min(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT);
        boolean checkedA = false;
        boolean checkedB = false;
        for (ControlLayout.Control control : layout.controls) {
            if (control.key == MgbaSession.KEY_A || control.key == MgbaSession.KEY_B) {
                assertTrue("face button " + control.label + " radius=" + control.halfWidth
                                + " must be >= unit*0.08f=" + (unit * 0.08f),
                        control.halfWidth >= unit * 0.08f);
                checkedA |= control.key == MgbaSession.KEY_A;
                checkedB |= control.key == MgbaSession.KEY_B;
            }
        }
        assertTrue("expected to find A and B controls", checkedA && checkedB);
    }

    @Test
    public void hitTestAtEachControlCentreReturnsThatControlsKey() {
        assertHitTestMatchesEveryControlCentre(ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT));
        assertHitTestMatchesEveryControlCentre(ControlLayout.of(PORTRAIT_WIDTH, PORTRAIT_HEIGHT));
    }

    private void assertHitTestMatchesEveryControlCentre(ControlLayout layout) {
        for (ControlLayout.Control control : layout.controls) {
            if (control.shape == ControlLayout.Shape.DPAD) {
                continue;
            }
            assertEquals("control " + control.label + " at (" + control.cx + "," + control.cy + ")",
                    control.key, layout.keysAt(control.cx, control.cy));
        }
    }

    @Test
    public void hitTestInsideTheGameScreenReturnsNoKeys() {
        ControlLayout landscape = ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT);
        float landscapeCenterX = (landscape.gameLeft + landscape.gameRight) / 2f;
        float landscapeCenterY = (landscape.gameTop + landscape.gameBottom) / 2f;
        assertEquals(0, landscape.keysAt(landscapeCenterX, landscapeCenterY));

        ControlLayout portrait = ControlLayout.of(PORTRAIT_WIDTH, PORTRAIT_HEIGHT);
        float portraitCenterX = (portrait.gameLeft + portrait.gameRight) / 2f;
        float portraitCenterY = (portrait.gameTop + portrait.gameBottom) / 2f;
        assertEquals(0, portrait.keysAt(portraitCenterX, portraitCenterY));
    }

    @Test
    public void dpadDecomposesIntoDirectionsIncludingDiagonals() {
        ControlLayout layout = ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT);
        ControlLayout.Control dpad = findDpad(layout);

        // centre: inside the dead zone, no direction.
        assertEquals(0, layout.keysAt(dpad.cx, dpad.cy));

        float past = dpad.halfWidth * 0.5f;
        assertEquals(MgbaSession.KEY_LEFT, layout.keysAt(dpad.cx - past, dpad.cy));
        assertEquals(MgbaSession.KEY_RIGHT, layout.keysAt(dpad.cx + past, dpad.cy));
        assertEquals(MgbaSession.KEY_UP, layout.keysAt(dpad.cx, dpad.cy - past));
        assertEquals(MgbaSession.KEY_DOWN, layout.keysAt(dpad.cx, dpad.cy + past));

        // diagonal: both axes past the dead zone must produce both key bits.
        int diagonal = layout.keysAt(dpad.cx - past, dpad.cy - past);
        assertEquals(MgbaSession.KEY_LEFT | MgbaSession.KEY_UP, diagonal);
    }

    @Test
    public void loadAndNoticesChipsDoNotOverlapEachOtherOrTheGameScreen() {
        assertChipsDoNotOverlap(ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT));
        assertChipsDoNotOverlap(ControlLayout.of(PORTRAIT_WIDTH, PORTRAIT_HEIGHT));
    }

    private void assertChipsDoNotOverlap(ControlLayout layout) {
        assertTrue("LOAD and NOTICES chips overlap each other",
                !intersects(
                        layout.loadLeft, layout.loadTop, layout.loadRight, layout.loadBottom,
                        layout.noticesLeft, layout.noticesTop, layout.noticesRight, layout.noticesBottom));
        assertTrue("LOAD chip overlaps the game screen",
                !intersects(
                        layout.loadLeft, layout.loadTop, layout.loadRight, layout.loadBottom,
                        layout.gameLeft, layout.gameTop, layout.gameRight, layout.gameBottom));
        assertTrue("NOTICES chip overlaps the game screen",
                !intersects(
                        layout.noticesLeft, layout.noticesTop, layout.noticesRight, layout.noticesBottom,
                        layout.gameLeft, layout.gameTop, layout.gameRight, layout.gameBottom));
    }

    @Test
    public void menuChipDoesNotOverlapOtherChipsOrTheGameScreen() {
        for (ControlLayout l : new ControlLayout[] {
                ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT),
                ControlLayout.of(PORTRAIT_WIDTH, PORTRAIT_HEIGHT) }) {
            assertFalse(intersects(
                    l.menuLeft, l.menuTop, l.menuRight, l.menuBottom,
                    l.gameLeft, l.gameTop, l.gameRight, l.gameBottom));
            assertFalse(intersects(
                    l.menuLeft, l.menuTop, l.menuRight, l.menuBottom,
                    l.noticesLeft, l.noticesTop, l.noticesRight, l.noticesBottom));
            assertFalse(intersects(
                    l.menuLeft, l.menuTop, l.menuRight, l.menuBottom,
                    l.loadLeft, l.loadTop, l.loadRight, l.loadBottom));
        }
    }

    @Test
    public void portraitKeepsGameAboveControls() {
        ControlLayout layout = ControlLayout.of(PORTRAIT_WIDTH, PORTRAIT_HEIGHT);
        for (ControlLayout.Control control : layout.controls) {
            assertTrue("control " + control.label + " top=" + (control.cy - control.halfHeight)
                            + " must be below the game screen bottom=" + layout.gameBottom,
                    control.cy - control.halfHeight >= layout.gameBottom);
        }
    }

    private static ControlLayout.Control findDpad(ControlLayout layout) {
        for (ControlLayout.Control control : layout.controls) {
            if (control.shape == ControlLayout.Shape.DPAD) {
                return control;
            }
        }
        throw new AssertionError("no DPAD control found");
    }

    private static boolean intersects(float aLeft, float aTop, float aRight, float aBottom,
            float bLeft, float bTop, float bRight, float bBottom) {
        return aLeft < bRight && bLeft < aRight && aTop < bBottom && bTop < aBottom;
    }
}
