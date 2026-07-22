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

    // controlOverrides(boolean)/setControlOverrides persist per orientation via
    // ControlOverrides serialize/parse (covered by ControlOverridesTest); the
    // two-key orientation split is exercised on-device in the touch-layout pass.
    @Test
    public void controlOverridesSerdeContractHolds() {
        ControlOverrides o = new ControlOverrides();
        o.put(1, 0.2f, 0.3f, 1.5f);
        ControlOverrides back = ControlOverrides.parse(o.serialize());
        assertEquals(0.2f, back.normCx(1), 1e-3f);
    }

    // Settings persists these enums by ordinal, so their declaration order is a
    // storage format: reordering or removing a value would silently remap every
    // stored user choice. This pins the ordinals so such a change fails loudly
    // here instead of corrupting preferences at runtime. Append new values only.
    @Test
    public void persistedEnumOrdinalsAreStable() {
        assertEquals(0, Settings.Orientation.AUTO.ordinal());
        assertEquals(1, Settings.Orientation.PORTRAIT.ordinal());
        assertEquals(2, Settings.Orientation.LANDSCAPE.ordinal());

        assertEquals(0, Settings.ScaleMode.INTEGER.ordinal());
        assertEquals(1, Settings.ScaleMode.FILL.ordinal());

        assertEquals(0, Settings.TouchVisibility.ALWAYS.ordinal());
        assertEquals(1, Settings.TouchVisibility.AFTER_10_SECONDS.ordinal());
        assertEquals(2, Settings.TouchVisibility.WITH_GAMEPAD.ordinal());

        assertEquals(0, DmgPalette.GREEN.ordinal());
        assertEquals(1, DmgPalette.GREY.ordinal());
    }
}
