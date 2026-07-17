package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class KeyBindingsTest {

    // A tiny stand-in default map using arbitrary ints (no android constants).
    private static Map<Integer, Integer> defaults() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        m.put(96, 1);   // BUTTON_A -> KEY_A
        m.put(52, 1);   // X        -> KEY_A (keyboard fallback)
        m.put(97, 2);   // BUTTON_B -> KEY_B
        m.put(19, 64);  // DPAD_UP  -> KEY_UP
        return m;
    }

    @Test
    public void defaultLookupResolvesEachBoundKey() {
        KeyBindings b = KeyBindings.of(defaults());
        assertEquals(1, b.gbaKeyFor(96));
        assertEquals(1, b.gbaKeyFor(52));
        assertEquals(2, b.gbaKeyFor(97));
        assertEquals(64, b.gbaKeyFor(19));
    }

    @Test
    public void unknownKeycodeReturnsZero() {
        assertEquals(0, KeyBindings.of(defaults()).gbaKeyFor(12345));
    }

    @Test
    public void bindOverwritesAndKeepsOneKeyPerButton() {
        KeyBindings b = KeyBindings.of(defaults());
        // Rebind KEY_A (gba 1) to a new keycode 200. Both prior keys for 1 drop.
        b.bind(1, 200);
        assertEquals(1, b.gbaKeyFor(200));
        assertEquals(0, b.gbaKeyFor(96));
        assertEquals(0, b.gbaKeyFor(52));
    }

    @Test
    public void bindReassignsKeyPreviouslyUsedElsewhere() {
        KeyBindings b = KeyBindings.of(defaults());
        // Keycode 97 currently -> KEY_B(2). Rebind it to KEY_A(1): no double-drive.
        b.bind(1, 97);
        assertEquals(1, b.gbaKeyFor(97));
        // Nothing else now drives KEY_B via 97.
        assertNotEquals(2, b.gbaKeyFor(97));
    }

    @Test
    public void keyCodeForReturnsABoundKeyOrMinusOne() {
        KeyBindings b = KeyBindings.of(defaults());
        assertEquals(2, b.gbaKeyFor(b.keyCodeFor(2)));
        assertEquals(-1, b.keyCodeFor(999)); // no key bound to gba 999
    }

    @Test
    public void resetRestoresDefaults() {
        KeyBindings b = KeyBindings.of(defaults());
        b.bind(1, 200);
        b.reset(defaults());
        assertEquals(1, b.gbaKeyFor(96));
        assertEquals(0, b.gbaKeyFor(200));
    }

    @Test
    public void serializeParseRoundTrips() {
        KeyBindings b = KeyBindings.of(defaults());
        b.bind(1, 200);
        String s = b.serialize();
        KeyBindings back = KeyBindings.parse(s, new LinkedHashMap<>());
        assertEquals(1, back.gbaKeyFor(200));
        assertEquals(64, back.gbaKeyFor(19));
    }

    @Test
    public void parseEmptyOrGarbageFallsBackToDefaults() {
        assertEquals(1, KeyBindings.parse("", defaults()).gbaKeyFor(96));
        assertEquals(1, KeyBindings.parse(null, defaults()).gbaKeyFor(96));
        assertEquals(1, KeyBindings.parse("not:a:number,,x", defaults()).gbaKeyFor(96));
    }
}
