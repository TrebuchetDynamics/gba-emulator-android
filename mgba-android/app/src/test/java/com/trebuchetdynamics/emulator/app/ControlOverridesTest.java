package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ControlOverridesTest {

    @Test
    public void putThenReadRoundTrips() {
        ControlOverrides o = new ControlOverrides();
        o.put(1, 0.25f, 0.75f, 1.5f);
        assertTrue(o.has(1));
        assertEquals(0.25f, o.normCx(1), 1e-4f);
        assertEquals(0.75f, o.normCy(1), 1e-4f);
        assertEquals(1.5f, o.scale(1), 1e-4f);
        assertFalse(o.has(2));
    }

    @Test
    public void putClampsOutOfRangeValues() {
        ControlOverrides o = new ControlOverrides();
        o.put(0, -0.2f, 1.4f, 5f);
        assertEquals(0f, o.normCx(0), 1e-4f);
        assertEquals(1f, o.normCy(0), 1e-4f);
        assertEquals(2f, o.scale(0), 1e-4f);
        o.put(0, 0.5f, 0.5f, 0.1f);
        assertEquals(0.5f, o.clampScale(0.1f), 1e-4f); // 0.1 -> 0.5 floor via helper
        assertEquals(0.5f, o.scale(0), 1e-4f);
    }

    @Test
    public void clampHelpers() {
        assertEquals(0f, ControlOverrides.clampNorm(-1f), 1e-4f);
        assertEquals(1f, ControlOverrides.clampNorm(2f), 1e-4f);
        assertEquals(0.3f, ControlOverrides.clampNorm(0.3f), 1e-4f);
        assertEquals(0.5f, ControlOverrides.clampScale(0.2f), 1e-4f);
        assertEquals(2f, ControlOverrides.clampScale(9f), 1e-4f);
        assertEquals(1f, ControlOverrides.clampScale(1f), 1e-4f);
    }

    @Test
    public void serializeParseRoundTripsMultipleControlsIncludingDpad() {
        ControlOverrides o = new ControlOverrides();
        o.put(0, 0.1f, 0.6f, 1.25f);   // D-pad (key 0)
        o.put(1, 0.9f, 0.5f, 2f);      // A
        ControlOverrides back = ControlOverrides.parse(o.serialize());
        assertTrue(back.has(0));
        assertTrue(back.has(1));
        assertEquals(0.1f, back.normCx(0), 1e-3f);
        assertEquals(0.6f, back.normCy(0), 1e-3f);
        assertEquals(1.25f, back.scale(0), 1e-3f);
        assertEquals(0.9f, back.normCx(1), 1e-3f);
        assertEquals(2f, back.scale(1), 1e-3f);
    }

    @Test
    public void parseEmptyOrGarbageYieldsNoOverrides() {
        assertFalse(ControlOverrides.parse("").has(1));
        assertFalse(ControlOverrides.parse(null).has(1));
        assertFalse(ControlOverrides.parse("junk,1:2,x:y:z:w").has(1));
    }

    @Test
    public void clearEmpties() {
        ControlOverrides o = new ControlOverrides();
        o.put(2, 0.4f, 0.4f, 1f);
        o.clear();
        assertFalse(o.has(2));
    }
}
