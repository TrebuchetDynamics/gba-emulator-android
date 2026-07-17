package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmulationRunnerTest {
    private static final long FRAME_NANOS = 16_743_000L;

    @Test
    public void normalSpeedUsesTheFullFrameBudget() {
        assertEquals(FRAME_NANOS, EmulationRunner.frameBudgetNanos(false, 4));
    }

    @Test
    public void fastForwardDividesTheBudgetBySpeed() {
        assertEquals(FRAME_NANOS / 4, EmulationRunner.frameBudgetNanos(true, 4));
        assertEquals(FRAME_NANOS / 2, EmulationRunner.frameBudgetNanos(true, 2));
        assertEquals(FRAME_NANOS / 3, EmulationRunner.frameBudgetNanos(true, 3));
    }

    @Test
    public void frameskipZeroRendersEveryFrame() {
        for (long i = 0; i < 6; i++) {
            assertTrue(EmulationRunner.shouldRenderFrame(i, 0));
        }
    }

    @Test
    public void frameskipOneRendersEveryOtherFrame() {
        assertTrue(EmulationRunner.shouldRenderFrame(0, 1));
        assertFalse(EmulationRunner.shouldRenderFrame(1, 1));
        assertTrue(EmulationRunner.shouldRenderFrame(2, 1));
        assertFalse(EmulationRunner.shouldRenderFrame(3, 1));
    }

    @Test
    public void frameskipThreeRendersOneInFour() {
        assertTrue(EmulationRunner.shouldRenderFrame(0, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(1, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(2, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(3, 3));
        assertTrue(EmulationRunner.shouldRenderFrame(4, 3));
    }
}
