package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeelMathTest {
    private static final int W = 240;
    private static final int H = 160;

    @Test
    public void exactMultipleFillsTheBox() {
        FeelMath.Box b = FeelMath.integerScale(0, 0, 1200, 800, W, H); // exactly 5x
        assertEquals(0f, b.left, 0.001f);
        assertEquals(0f, b.top, 0.001f);
        assertEquals(1200f, b.right, 0.001f);
        assertEquals(800f, b.bottom, 0.001f);
    }

    @Test
    public void nonIntegerBoxSnapsDownAndCentres() {
        // box 1200x840 at origin (10,20): min(1200/240,840/160)=5.0 -> 5x = 1200x800,
        // centred: x unchanged (1200==1200), y padded (840-800)/2 = 20.
        FeelMath.Box b = FeelMath.integerScale(10, 20, 1210, 860, W, H);
        assertEquals(10f, b.left, 0.001f);
        assertEquals(40f, b.top, 0.001f);
        assertEquals(1210f, b.right, 0.001f);
        assertEquals(840f, b.bottom, 0.001f);
    }

    @Test
    public void boxSmallerThanNativeStaysAtScaleOne() {
        FeelMath.Box b = FeelMath.integerScale(0, 0, 100, 100, W, H);
        assertEquals(240f, b.right - b.left, 0.001f);
        assertEquals(160f, b.bottom - b.top, 0.001f);
    }

    @Test
    public void controlsUseConfiguredTransparencyWithoutInputOverride() {
        assertEquals(60, FeelMath.controlAlpha(60, 255));
    }

    @Test
    public void activeOpacityStillCapsConfiguredTransparency() {
        assertEquals(128, FeelMath.controlAlpha(200, 128));
    }

    @Test
    public void autoHideRequiresEnabledIdleTouchControlsAtTheDeadline() {
        assertFalse(FeelMath.shouldAutoHideControls(false, false, 10_000, 0, 10_000));
        assertFalse(FeelMath.shouldAutoHideControls(true, true, 10_000, 0, 10_000));
        assertFalse(FeelMath.shouldAutoHideControls(true, false, 9_999, 0, 10_000));
        assertTrue(FeelMath.shouldAutoHideControls(true, false, 10_000, 0, 10_000));
    }

    @Test
    public void newPressDetection() {
        assertTrue(FeelMath.introducesNewPress(0, 1));      // press A
        assertFalse(FeelMath.introducesNewPress(1, 1));     // still A
        assertTrue(FeelMath.introducesNewPress(1, 3));      // add B
        assertFalse(FeelMath.introducesNewPress(3, 1));     // release B, no new press
        assertFalse(FeelMath.introducesNewPress(1, 0));     // release A
    }

    @Test
    public void fitScaleFillsAndPreservesAspect() {
        // box 1200x840 at origin, src 240x160 (3:2). Aspect-fit scale = min(1200/240, 840/160)
        // = min(5.0, 5.25) = 5.0 -> 1200x800, centred: top padded (840-800)/2 = 20.
        FeelMath.Box b = FeelMath.fitScale(0, 0, 1200, 840, 240, 160);
        assertEquals(0f, b.left, 0.001f);
        assertEquals(20f, b.top, 0.001f);
        assertEquals(1200f, b.right, 0.001f);
        assertEquals(820f, b.bottom, 0.001f);
    }

    @Test
    public void fitScaleIsNonIntegerWhenIntegerWouldWaste() {
        // box exactly 1260x840: fit scale 5.25 -> 1260x840 (fills), vs integerScale would give 5x=1200x800.
        FeelMath.Box b = FeelMath.fitScale(0, 0, 1260, 840, 240, 160);
        assertEquals(1260f, b.right - b.left, 0.001f);
        assertEquals(840f, b.bottom - b.top, 0.001f);
    }

    @Test public void turboPulsesWholeCombinationTwoFramesOnTwoFramesOff() {
        int combo = 1 | 32;
        assertEquals(combo, FeelMath.applyTurbo(0, combo, 0));
        assertEquals(combo, FeelMath.applyTurbo(0, combo, 1));
        assertEquals(0, FeelMath.applyTurbo(0, combo, 2));
        assertEquals(0, FeelMath.applyTurbo(0, combo, 3));
        assertEquals(combo, FeelMath.applyTurbo(0, combo, 4));
    }

    @Test public void normalKeysRemainHeldWhileTurboPulses() {
        int normal = 2;
        int turbo = 1 | 32;
        assertEquals(normal | turbo, FeelMath.applyTurbo(normal, turbo, 0));
        assertEquals(normal, FeelMath.applyTurbo(normal, turbo, 2));
    }
}
