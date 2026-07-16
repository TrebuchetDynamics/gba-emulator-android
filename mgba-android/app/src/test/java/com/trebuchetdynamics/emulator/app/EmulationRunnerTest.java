package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
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
}
