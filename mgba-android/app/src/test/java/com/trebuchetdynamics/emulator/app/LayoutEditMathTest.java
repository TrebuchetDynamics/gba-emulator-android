package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LayoutEditMathTest {

    @Test
    public void toNormDividesAndClamps() {
        assertEquals(0.5f, LayoutEditMath.toNorm(500f, 1000f), 1e-4f);
        assertEquals(0f, LayoutEditMath.toNorm(-50f, 1000f), 1e-4f);
        assertEquals(1f, LayoutEditMath.toNorm(1500f, 1000f), 1e-4f);
    }

    @Test
    public void stepScaleMovesByA10thAndClamps() {
        assertEquals(0.9f, LayoutEditMath.stepScale(1f, -1), 1e-4f);
        assertEquals(1.1f, LayoutEditMath.stepScale(1f, 1), 1e-4f);
        assertEquals(0.5f, LayoutEditMath.stepScale(0.5f, -1), 1e-4f);
        assertEquals(2f, LayoutEditMath.stepScale(2f, 1), 1e-4f);
    }
}
