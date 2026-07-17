package com.trebuchetdynamics.emulator.app;

import android.view.KeyEvent;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The default physical-key → GBA-key map. Reproduces the historical
 * {@code MainActivity.mapKey} switch verbatim, so an unbound install behaves
 * exactly as before gamepad remapping existed.
 */
final class GamepadDefaults {
    private GamepadDefaults() {
    }

    static Map<Integer, Integer> map() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        m.put(KeyEvent.KEYCODE_BUTTON_A, MgbaSession.KEY_A);
        m.put(KeyEvent.KEYCODE_X, MgbaSession.KEY_A);
        m.put(KeyEvent.KEYCODE_BUTTON_B, MgbaSession.KEY_B);
        m.put(KeyEvent.KEYCODE_Z, MgbaSession.KEY_B);
        m.put(KeyEvent.KEYCODE_BUTTON_START, MgbaSession.KEY_START);
        m.put(KeyEvent.KEYCODE_ENTER, MgbaSession.KEY_START);
        m.put(KeyEvent.KEYCODE_BUTTON_SELECT, MgbaSession.KEY_SELECT);
        m.put(KeyEvent.KEYCODE_DEL, MgbaSession.KEY_SELECT);
        m.put(KeyEvent.KEYCODE_DPAD_UP, MgbaSession.KEY_UP);
        m.put(KeyEvent.KEYCODE_DPAD_DOWN, MgbaSession.KEY_DOWN);
        m.put(KeyEvent.KEYCODE_DPAD_LEFT, MgbaSession.KEY_LEFT);
        m.put(KeyEvent.KEYCODE_DPAD_RIGHT, MgbaSession.KEY_RIGHT);
        m.put(KeyEvent.KEYCODE_BUTTON_L1, MgbaSession.KEY_L);
        m.put(KeyEvent.KEYCODE_BUTTON_R1, MgbaSession.KEY_R);
        return m;
    }
}
