package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsTest {
    @Test
    public void clampPercentBounds() {
        assertEquals(0, Settings.clampPercent(-10));
        assertEquals(100, Settings.clampPercent(150));
        assertEquals(37, Settings.clampPercent(37));
    }

    @Test
    public void clampFastForwardSpeedBounds() {
        assertEquals(2, Settings.clampFastForwardSpeed(1));
        assertEquals(4, Settings.clampFastForwardSpeed(9));
        assertEquals(3, Settings.clampFastForwardSpeed(3));
    }

    @Test
    public void opacityPercentMapsToAlpha() {
        assertEquals(0, Settings.opacityPercentToAlpha(0));
        assertEquals(255, Settings.opacityPercentToAlpha(100));
        assertEquals(61, Settings.opacityPercentToAlpha(24)); // 24% -> round(0.24*255)=61
    }

    @Test
    public void clampFrameskipBoundsToZeroThroughThree() {
        assertEquals(0, Settings.clampFrameskip(-1));
        assertEquals(0, Settings.clampFrameskip(0));
        assertEquals(2, Settings.clampFrameskip(2));
        assertEquals(3, Settings.clampFrameskip(3));
        assertEquals(3, Settings.clampFrameskip(9));
    }
}
