package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmulationRunnerTest {
    private static final long FRAME_NANOS = 16_743_000L;

    @Test
    public void normalSpeedUsesTheFullFrameBudget() {
        assertEquals(FRAME_NANOS, EmulationRunner.frameBudgetNanos(false));
    }

    @Test
    public void fastForwardShrinksTheBudget() {
        long ff = EmulationRunner.frameBudgetNanos(true);
        assertTrue("fast-forward budget must be smaller", ff < FRAME_NANOS);
        assertEquals(FRAME_NANOS / 4, ff);
    }
}
