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
    public void scaleForProgressSpansHalfToTwoWithMidpointOne() {
        assertEquals(0.5f, LayoutEditMath.scaleForProgress(0, 100), 1e-4f);
        assertEquals(2f, LayoutEditMath.scaleForProgress(100, 100), 1e-4f);
        assertEquals(1.25f, LayoutEditMath.scaleForProgress(50, 100), 1e-4f); // 0.5 + 0.5*1.5
    }

    @Test
    public void progressForScaleInvertsScaleForProgress() {
        assertEquals(0, LayoutEditMath.progressForScale(0.5f, 100));
        assertEquals(100, LayoutEditMath.progressForScale(2f, 100));
        assertEquals(50, LayoutEditMath.progressForScale(1.25f, 100));
    }
}
