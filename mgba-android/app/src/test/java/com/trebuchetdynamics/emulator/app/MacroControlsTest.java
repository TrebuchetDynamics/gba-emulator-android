package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;
import org.junit.Test;

public class MacroControlsTest {
    @Test public void arbitraryCombinationAndTurboRoundTrip() {
        MacroControls controls = new MacroControls();
        MacroControls.Macro macro = controls.add(
                MgbaSession.KEY_LEFT | MgbaSession.KEY_A, true);
        MacroControls parsed = MacroControls.parse(controls.serialize());
        MacroControls.Macro back = parsed.macroForLayoutId(macro.layoutId());
        assertNotNull(back);
        assertEquals(MgbaSession.KEY_LEFT | MgbaSession.KEY_A, back.keyMask);
        assertTrue(back.turbo);
        assertEquals("← + A ⚡", back.shortLabel());
        assertEquals("Turbo Left plus A", back.contentLabel());
        assertEquals("10 keys", controls.add(MacroControls.SUPPORTED_KEYS, false).shortLabel());
    }

    @Test public void duplicateAndEmptyDefinitionsAreRejected() {
        MacroControls controls = new MacroControls();
        controls.add(MgbaSession.KEY_A, true);
        assertThrows(IllegalArgumentException.class,
                () -> controls.add(MgbaSession.KEY_A, true));
        assertThrows(IllegalArgumentException.class, () -> controls.add(0, false));
    }

    @Test public void unsupportedBitsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MacroControls().add(1 << 20, false));
    }

    @Test public void firstFreeSlotIsStableAndRemovalIsByLayoutId() {
        MacroControls controls = new MacroControls();
        MacroControls.Macro first = controls.add(MgbaSession.KEY_A, true);
        MacroControls.Macro second = controls.add(MgbaSession.KEY_B, true);
        assertTrue(controls.removeLayoutId(first.layoutId()));
        MacroControls.Macro replacement = controls.add(
                MgbaSession.KEY_L | MgbaSession.KEY_R, false);
        assertEquals(first.layoutId(), replacement.layoutId());
        assertNotNull(controls.macroForLayoutId(second.layoutId()));
    }

    @Test public void ninthMacroIsRejected() {
        MacroControls controls = new MacroControls();
        for (int i = 1; i <= MacroControls.MAX_CONTROLS; i++) {
            controls.add(i, true);
        }
        assertThrows(IllegalStateException.class,
                () -> controls.add(MgbaSession.KEY_A | MgbaSession.KEY_B, false));
    }

    @Test public void parseDropsMalformedDuplicateAndInvalidRecords() {
        MacroControls parsed = MacroControls.parse(
                "1:1:true,1:2:false,2:0:false,3:1048576:true,junk,"
                        + "4:2:false,5:2:false,6:4:maybe");
        assertEquals(2, parsed.size());
        assertEquals(MgbaSession.KEY_A,
                parsed.macroForLayoutId(MacroControls.layoutIdForSlot(1)).keyMask);
        assertEquals(MgbaSession.KEY_B,
                parsed.macroForLayoutId(MacroControls.layoutIdForSlot(4)).keyMask);
        assertNull(parsed.macroForLayoutId(MacroControls.layoutIdForSlot(5)));
        assertNull(parsed.macroForLayoutId(MacroControls.layoutIdForSlot(6)));
    }

    @Test public void copyIsIndependent() {
        MacroControls original = new MacroControls();
        MacroControls.Macro macro = original.add(MgbaSession.KEY_A, true);
        MacroControls copy = original.copy();
        copy.removeLayoutId(macro.layoutId());
        assertEquals(1, original.size());
        assertEquals(0, copy.size());
        assertFalse(original.serialize().isEmpty());
    }
}
