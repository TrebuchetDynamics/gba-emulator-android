# Phase 4b — Gamepad remapping + frameskip — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make physical controllers remappable (data-driven `keycode → GBA-key` map + press-to-bind screen) and add a frameskip emulation control, completing the input/emulation half of Phase 4.

**Architecture:** A pure `KeyBindings` map replaces the hard-coded `MainActivity.mapKey` switch; a new `GamepadSettingsActivity` edits it press-to-bind and persists it through `Settings`; `MainActivity` loads it and looks up keycodes at runtime. Frameskip is a pure `shouldRenderFrame` gate in the `EmulationRunner` loop that skips `view.publishFrame` for N frames while the core steps and audio writes every frame. Follows the Phase 4a pattern: pure logic JVM-tested, settings persisted via SharedPreferences, applied on `onResume`, appended `EmulationRunner` constructor args.

**Tech Stack:** Java, Android `minSdk 24` / `targetSdk 35`, programmatic Views (no XML, no `androidx.preference`), SharedPreferences, mGBA JNI, `AudioTrack`, Canvas rendering.

**Spec:** `docs/superpowers/specs/2026-07-16-phase4b-controllers-frameskip-design.md`

## Global Constraints

- Java, `minSdk 24`, package `com.trebuchetdynamics.emulator.app`. Programmatic Views only — no XML layouts, no `androidx.preference`.
- **No regression for the default install:** the default `KeyBindings` map reproduces the current `mapKey` switch verbatim, and `frameskip` defaults to `0` (render every frame). An untouched install behaves exactly as after Phase 4a.
- Pure logic (`KeyBindings`, `shouldRenderFrame`, `Settings` clamp/serde helpers) must be JVM-testable with **no `android.*` imports** in the pure paths. Default keycode values are injected from the Android side as ints (`GamepadDefaults`).
- No change to `ControlLayout` geometry, the in-game menu / save-state / notices wiring, the ROM library/import/play-by-id contract, or Phase 4a Settings behavior.
- Emulation-thread discipline: settings read on the UI thread, handed to the emulation thread at runner construction (as Phase 4a did). `KeyBindings` runtime lookup runs on the UI thread (`dispatchKeyEvent`).
- GBA key bitmasks (from `MgbaSession`): `KEY_A=1, KEY_B=2, KEY_SELECT=4, KEY_START=8, KEY_RIGHT=16, KEY_LEFT=32, KEY_UP=64, KEY_DOWN=128, KEY_R=256, KEY_L=512`.
- Commit trailer on every commit: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Build/test commands (run from repo root):
  - Unit tests: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest`
  - Lint: `tools/android_project/gradlew -p mgba-android lintDebug`
  - Benchmark APK: `tools/android_project/gradlew -p mgba-android :app:assembleBenchmark`

## File Structure

- `KeyBindings.java` (new, pure) — keycode→GBA-key map: lookup, bind, reset, serialize/parse.
- `GamepadDefaults.java` (new, Android) — the default map built from `KeyEvent`/`MgbaSession` constants (verbatim `mapKey`).
- `GamepadSettingsActivity.java` (new) — press-to-bind editor screen.
- `Settings.java` (modify) — `frameskip`/`clampFrameskip`, `gamepadBindings`/`setGamepadBindings`.
- `EmulationRunner.java` (modify) — `shouldRenderFrame` static + appended `frameskip` ctor arg + loop gating.
- `MainActivity.java` (modify) — load `KeyBindings`, replace `mapKey` lookup, pass `frameskip` to the runner.
- `SettingsActivity.java` (modify) — "Gamepad buttons" row + "Frameskip" picker.
- `AndroidManifest.xml`, `res/values/strings.xml` (modify).
- Tests: `KeyBindingsTest.java` (new), `SettingsTest.java` / `EmulationRunnerTest.java` (extend).

Test dir: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/`
Main dir: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/`

---

### Task 1: Pure logic — `KeyBindings` + `Settings` additions

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/KeyBindings.java`
- Create: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/KeyBindingsTest.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java`
- Modify: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/SettingsTest.java`

**Interfaces:**
- Produces: `KeyBindings` — `static KeyBindings of(Map<Integer,Integer>)`, `static KeyBindings parse(String, Map<Integer,Integer> defaults)`, `int gbaKeyFor(int keyCode)`, `void bind(int gbaKey, int keyCode)`, `int keyCodeFor(int gbaKey)`, `void reset(Map<Integer,Integer> defaults)`, `String serialize()`.
- Produces: `Settings.frameskip()`, `Settings.setFrameskip(int)`, `static Settings.clampFrameskip(int)`, `Settings.gamepadBindings(Map<Integer,Integer> defaults)`, `Settings.setGamepadBindings(KeyBindings)`.
- Consumes: nothing new (both are additive; `Settings` already wraps SharedPreferences).

- [ ] **Step 1: Write the failing test for `KeyBindings`**

Create `KeyBindingsTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*KeyBindingsTest'`
Expected: FAIL — `KeyBindings` does not exist (compile error).

- [ ] **Step 3: Implement `KeyBindings`**

Create `KeyBindings.java`:

```java
package com.trebuchetdynamics.emulator.app;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure, JVM-testable map from Android key codes to GBA key bitmasks.
 *
 * <p>No {@code android.*} imports: key codes and GBA key bitmasks are plain
 * ints supplied by callers (see {@link GamepadDefaults} for the real values).
 */
final class KeyBindings {
    private final Map<Integer, Integer> map;

    private KeyBindings(Map<Integer, Integer> initial) {
        this.map = new LinkedHashMap<>(initial);
    }

    /** A live, mutable binding set seeded from {@code initial}. */
    static KeyBindings of(Map<Integer, Integer> initial) {
        return new KeyBindings(initial);
    }

    /** The GBA key bitmask bound to {@code keyCode}, or 0 if unbound. */
    int gbaKeyFor(int keyCode) {
        Integer v = map.get(keyCode);
        return v == null ? 0 : v;
    }

    /**
     * Bind {@code keyCode} to {@code gbaKey}. A GBA button has exactly one key
     * in the editable map, and a physical key drives exactly one GBA button:
     * any key(s) already bound to {@code gbaKey} are dropped, and {@code put}
     * overwrites any prior binding of {@code keyCode}.
     */
    void bind(int gbaKey, int keyCode) {
        Iterator<Map.Entry<Integer, Integer>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() == gbaKey) {
                it.remove();
            }
        }
        map.put(keyCode, gbaKey);
    }

    /** Some key bound to {@code gbaKey}, or -1 if none. */
    int keyCodeFor(int gbaKey) {
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (e.getValue() == gbaKey) {
                return e.getKey();
            }
        }
        return -1;
    }

    /** Restore the injected defaults. */
    void reset(Map<Integer, Integer> defaults) {
        map.clear();
        map.putAll(defaults);
    }

    /** Deterministic {@code "keycode:gbakey,..."} ordered by keycode. */
    String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(map).entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Parse a stored string; on null/empty/garbage (or a parse that yields no
     * usable pairs) fall back to {@code defaults}.
     */
    static KeyBindings parse(String s, Map<Integer, Integer> defaults) {
        if (s == null || s.trim().isEmpty()) {
            return new KeyBindings(defaults);
        }
        Map<Integer, Integer> parsed = new LinkedHashMap<>();
        try {
            for (String pair : s.split(",")) {
                if (pair.trim().isEmpty()) {
                    continue;
                }
                String[] kv = pair.split(":");
                if (kv.length != 2) {
                    continue;
                }
                parsed.put(Integer.parseInt(kv[0].trim()), Integer.parseInt(kv[1].trim()));
            }
        } catch (NumberFormatException e) {
            return new KeyBindings(defaults);
        }
        return new KeyBindings(parsed.isEmpty() ? defaults : parsed);
    }
}
```

- [ ] **Step 4: Run the `KeyBindings` test to verify it passes**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*KeyBindingsTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Write the failing test for the `Settings` additions**

Append to `SettingsTest.java` (inside the existing class; it constructs a `Settings` over a Robolectric/instrumented context as the existing tests do — mirror the existing setup exactly). If `SettingsTest` currently exercises only pure static helpers (no `Settings` instance), add these two static-helper tests plus round-trip tests using the same instance pattern the file already uses. Add:

```java
    @Test
    public void clampFrameskipBoundsToZeroThroughThree() {
        assertEquals(0, Settings.clampFrameskip(-1));
        assertEquals(0, Settings.clampFrameskip(0));
        assertEquals(2, Settings.clampFrameskip(2));
        assertEquals(3, Settings.clampFrameskip(3));
        assertEquals(3, Settings.clampFrameskip(9));
    }
```

If (and only if) the existing `SettingsTest` already builds a real `Settings` instance, also add round-trip coverage for `frameskip` (default 0, set/get through clamp) and `gamepadBindings` (set a `KeyBindings`, read it back, confirm a bound keycode resolves). If the existing file is static-helper-only, leave the instance round-trips to the device pass and note it in the report — do **not** introduce a new Android test harness here.

- [ ] **Step 6: Run to verify the new `Settings` test fails**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*SettingsTest'`
Expected: FAIL — `clampFrameskip` not defined.

- [ ] **Step 7: Implement the `Settings` additions**

In `Settings.java`, add the import and constants near the existing ones:

```java
import java.util.Map;
```
```java
    private static final String K_FRAMESKIP = "frameskip";
    private static final String K_GAMEPAD = "gamepadBindings";
```

Add these methods (place `frameskip`/`setFrameskip` after `setFastForwardSpeed`, `clampFrameskip` after `clampFastForwardSpeed`, and the gamepad pair after them):

```java
    int frameskip() {
        return clampFrameskip(prefs.getInt(K_FRAMESKIP, 0));
    }

    void setFrameskip(int value) {
        prefs.edit().putInt(K_FRAMESKIP, clampFrameskip(value)).apply();
    }

    KeyBindings gamepadBindings(Map<Integer, Integer> defaults) {
        return KeyBindings.parse(prefs.getString(K_GAMEPAD, ""), defaults);
    }

    void setGamepadBindings(KeyBindings bindings) {
        prefs.edit().putString(K_GAMEPAD, bindings.serialize()).apply();
    }
```
```java
    static int clampFrameskip(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 3);
    }
```

- [ ] **Step 8: Run the full unit-test suite to verify green**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest`
Expected: PASS — all prior tests plus the new `KeyBindings` and `Settings` tests.

- [ ] **Step 9: Lint**

Run: `tools/android_project/gradlew -p mgba-android lintDebug`
Expected: 0 errors.

- [ ] **Step 10: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/KeyBindings.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/KeyBindingsTest.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/SettingsTest.java
git commit -m "feat(app): key bindings map and frameskip/gamepad settings

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Frameskip end-to-end

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- Modify: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/EmulationRunnerTest.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/SettingsActivity.java`
- Modify: `mgba-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `Settings.frameskip()` (Task 1).
- Produces: `static boolean EmulationRunner.shouldRenderFrame(long frameIndex, int frameskip)`; `EmulationRunner` constructor gains a trailing `int frameskip` arg (appended after `fastForwardSpeed`).

- [ ] **Step 1: Write the failing test for `shouldRenderFrame`**

Add to `EmulationRunnerTest.java` (mirror the existing test style; it already tests `frameBudgetNanos`):

```java
    @Test
    public void frameskipZeroRendersEveryFrame() {
        for (long i = 0; i < 6; i++) {
            assertTrue(EmulationRunner.shouldRenderFrame(i, 0));
        }
    }

    @Test
    public void frameskipOneRendersEveryOtherFrame() {
        assertTrue(EmulationRunner.shouldRenderFrame(0, 1));
        assertFalse(EmulationRunner.shouldRenderFrame(1, 1));
        assertTrue(EmulationRunner.shouldRenderFrame(2, 1));
        assertFalse(EmulationRunner.shouldRenderFrame(3, 1));
    }

    @Test
    public void frameskipThreeRendersOneInFour() {
        assertTrue(EmulationRunner.shouldRenderFrame(0, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(1, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(2, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(3, 3));
        assertTrue(EmulationRunner.shouldRenderFrame(4, 3));
    }
```

Ensure the JUnit imports for `assertTrue`/`assertFalse` are present (add `import static org.junit.Assert.assertFalse;` / `assertTrue;` if missing).

- [ ] **Step 2: Run to verify it fails**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*EmulationRunnerTest'`
Expected: FAIL — `shouldRenderFrame` not defined.

- [ ] **Step 3: Add `shouldRenderFrame` + the `frameskip` field/arg + loop gating**

In `EmulationRunner.java`:

Add the field near `fastForwardSpeed`:
```java
    private final int frameskip;
```

Change the constructor signature (append `int frameskip` after `int fastForwardSpeed`) and assign it (clamp defensively so the divisor is never zero):
```java
    EmulationRunner(Context context, EmulatorView view, File rom, String romId,
                    StateListener stateListener,
                    boolean audioEnabled, float audioVolume, int fastForwardSpeed,
                    int frameskip) {
        // ... existing assignments ...
        this.fastForwardSpeed = fastForwardSpeed;
        this.frameskip = Math.max(0, frameskip);
        thread = new Thread(this, "mgba-emulation");
        // ... rest unchanged ...
    }
```

Add the pure static near `frameBudgetNanos`:
```java
    /** True when frame {@code frameIndex} should be blitted under {@code frameskip}. */
    static boolean shouldRenderFrame(long frameIndex, int frameskip) {
        return frameIndex % (frameskip + 1) == 0;
    }
```

In `run()`, declare a frame counter before the `while (running)` loop (next to `nextFrame`):
```java
            long frameIndex = 0;
```
and replace the unconditional `view.publishFrame(pixels);` with:
```java
                if (shouldRenderFrame(frameIndex, frameskip)) {
                    view.publishFrame(pixels);
                }
                frameIndex++;
```
Leave `session.runFrame(...)` and the audio `write(...)` exactly as they are — the core steps and audio writes every frame; only the blit is gated.

- [ ] **Step 4: Update the `MainActivity` call site**

In `MainActivity.java`, find the single `new EmulationRunner(...)` construction and append `settings.frameskip()` as the final argument:
```java
        runner = new EmulationRunner(this, emulatorView, rom, romId,
                stateListener,
                settings.audioEnabled(), settings.audioVolumePercent() / 100f,
                settings.fastForwardSpeed(), settings.frameskip());
```
(Match the existing argument list exactly; only the trailing `settings.frameskip()` is new.)

- [ ] **Step 5: Add the Frameskip picker to `SettingsActivity`**

In `SettingsActivity.java`, add the Frameskip row to the Emulation group (right after the fast-forward `choiceRow`):
```java
        content.addView(choiceRow(getString(R.string.settings_frameskip),
                frameskipLabel(), v -> pickFrameskip()));
```

Add these helpers (next to `pickFastForward`):
```java
    private String frameskipLabel() {
        int f = settings.frameskip();
        return f == 0 ? getString(R.string.settings_frameskip_off) : String.valueOf(f);
    }

    private void pickFrameskip() {
        String[] labels = {getString(R.string.settings_frameskip_off), "1", "2", "3"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_frameskip)
                .setSingleChoiceItems(labels, settings.frameskip(), (d, which) -> {
                    settings.setFrameskip(which);
                    d.dismiss();
                    recreate();
                })
                .show();
    }
```

Add to `res/values/strings.xml` (after `settings_ff_speed`):
```xml
    <string name="settings_frameskip">Frameskip</string>
    <string name="settings_frameskip_off">Off</string>
```

- [ ] **Step 6: Run unit tests + lint + build the APK**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest lintDebug :app:assembleBenchmark`
Expected: tests PASS (incl. the 3 new frameskip tests), lint 0 errors, APK assembles. The whole app compiles (the `EmulationRunner` ctor change and its call site are both updated here).

- [ ] **Step 7: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/EmulationRunnerTest.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/SettingsActivity.java \
        mgba-android/app/src/main/res/values/strings.xml
git commit -m "feat(app): frameskip control in the emulation loop

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Gamepad bind screen (reachable, edits persist)

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/GamepadDefaults.java`
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/GamepadSettingsActivity.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/SettingsActivity.java`
- Modify: `mgba-android/app/src/main/res/values/strings.xml`
- Modify: `mgba-android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `KeyBindings`, `Settings.gamepadBindings/setGamepadBindings` (Task 1).
- Produces: `static Map<Integer,Integer> GamepadDefaults.map()` (the verbatim default `mapKey` map, reused by Task 4); a launchable `GamepadSettingsActivity`.

> This task makes the editor reachable and persisting. Runtime gameplay still uses the hard-coded `mapKey` until Task 4 — mirroring Phase 4a, where Task 3 persisted settings before Task 4 applied them.

- [ ] **Step 1: Create `GamepadDefaults` — verbatim reproduction of `mapKey`**

First **read** the current `MainActivity.mapKey` switch and reproduce it exactly. Create `GamepadDefaults.java`:

```java
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
```

Verify each pair against the live `mapKey` switch. If the live switch differs from the spec's listing, the **live switch wins** — reproduce what the code actually does and note the difference in the report.

- [ ] **Step 2: Create `GamepadSettingsActivity`**

Create `GamepadSettingsActivity.java`:

```java
package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

/** Press-to-bind editor for the physical-controller → GBA-button map. */
public final class GamepadSettingsActivity extends Activity {

    private static final int[] GBA_KEYS = {
            MgbaSession.KEY_A, MgbaSession.KEY_B, MgbaSession.KEY_L, MgbaSession.KEY_R,
            MgbaSession.KEY_START, MgbaSession.KEY_SELECT,
            MgbaSession.KEY_UP, MgbaSession.KEY_DOWN, MgbaSession.KEY_LEFT, MgbaSession.KEY_RIGHT };
    private static final String[] GBA_LABELS = {
            "A", "B", "L", "R", "START", "SELECT",
            "D-pad Up", "D-pad Down", "D-pad Left", "D-pad Right" };

    private Settings settings;
    private KeyBindings bindings;
    private LinearLayout list;
    private int listeningGbaKey; // 0 = not listening

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        bindings = settings.gamepadBindings(GamepadDefaults.map());
        setTitle(R.string.gamepad_title);
        getWindow().setStatusBarColor(0xFF0E1014);
        getWindow().setNavigationBarColor(0xFF0E1014);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xFF0E1014);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        Button reset = new Button(this);
        reset.setText(R.string.gamepad_reset);
        reset.setOnClickListener(v -> {
            bindings.reset(GamepadDefaults.map());
            settings.setGamepadBindings(bindings);
            listeningGbaKey = 0;
            buildRows();
        });
        content.addView(reset);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        buildRows();
    }

    private void buildRows() {
        list.removeAllViews();
        for (int i = 0; i < GBA_KEYS.length; i++) {
            final int gbaKey = GBA_KEYS[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                listeningGbaKey = gbaKey;
                buildRows();
            });

            TextView name = new TextView(this);
            name.setText(GBA_LABELS[i]);
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            row.addView(name);

            TextView sub = new TextView(this);
            sub.setTextColor(0xFF9AA0AA);
            sub.setTextSize(13);
            if (gbaKey == listeningGbaKey) {
                sub.setText(R.string.gamepad_press);
            } else {
                sub.setText(keyLabel(bindings.keyCodeFor(gbaKey)));
            }
            row.addView(sub);
            list.addView(row);
        }
    }

    private String keyLabel(int keyCode) {
        if (keyCode < 0) {
            return getString(R.string.gamepad_unbound);
        }
        String s = KeyEvent.keyCodeToString(keyCode); // e.g. "KEYCODE_BUTTON_A"
        return s.startsWith("KEYCODE_") ? s.substring("KEYCODE_".length()) : s;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (listeningGbaKey == 0 || event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK) {
            listeningGbaKey = 0; // cancel, do not leave the screen
            buildRows();
            return true;
        }
        if (isIgnoredKey(kc)) {
            return super.dispatchKeyEvent(event); // never bind system nav/volume/power
        }
        bindings.bind(listeningGbaKey, kc);
        settings.setGamepadBindings(bindings);
        listeningGbaKey = 0;
        buildRows();
        return true;
    }

    private static boolean isIgnoredKey(int kc) {
        return kc == KeyEvent.KEYCODE_HOME
                || kc == KeyEvent.KEYCODE_APP_SWITCH
                || kc == KeyEvent.KEYCODE_VOLUME_UP
                || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE
                || kc == KeyEvent.KEYCODE_POWER;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
```

Note: binding accepts **any** non-ignored key while listening (not only gamepad-source events). This is intentional — it lets keyboard keys bind too and is what makes AVD validation via `adb shell input keyevent` work.

- [ ] **Step 3: Add the "Gamepad buttons" row to `SettingsActivity`**

In `SettingsActivity.java`, under the Controls group (after the opacity `sliderRow`):
```java
        content.addView(choiceRow(getString(R.string.settings_gamepad),
                getString(R.string.settings_gamepad_sub),
                v -> startActivity(new android.content.Intent(this, GamepadSettingsActivity.class))));
```

- [ ] **Step 4: Add strings**

In `res/values/strings.xml`:
```xml
    <string name="settings_gamepad">Gamepad buttons</string>
    <string name="settings_gamepad_sub">Remap a connected controller</string>
    <string name="gamepad_title">Gamepad buttons</string>
    <string name="gamepad_press">Press a button…</string>
    <string name="gamepad_unbound">Not set</string>
    <string name="gamepad_reset">Reset to defaults</string>
```

- [ ] **Step 5: Register the activity**

In `AndroidManifest.xml`, alongside the other non-exported activities:
```xml
        <activity
            android:name=".GamepadSettingsActivity"
            android:exported="false" />
```

- [ ] **Step 6: Unit tests + lint + build**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest lintDebug :app:assembleBenchmark`
Expected: tests PASS (unchanged set still green), lint 0 errors, APK assembles.

- [ ] **Step 7: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/GamepadDefaults.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/GamepadSettingsActivity.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/SettingsActivity.java \
        mgba-android/app/src/main/res/values/strings.xml \
        mgba-android/app/src/main/AndroidManifest.xml
git commit -m "feat(app): press-to-bind gamepad settings screen

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Apply remapping at runtime

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`

**Interfaces:**
- Consumes: `KeyBindings`, `GamepadDefaults.map()`, `Settings.gamepadBindings` (Tasks 1, 3).
- Produces: runtime gameplay driven by the persisted bindings.

- [ ] **Step 1: Add a `KeyBindings` field and load it**

In `MainActivity.java`, add a field near the `settings` field:
```java
    private KeyBindings bindings;
```

Initialize it in `onCreate` right after `settings` is created:
```java
        bindings = settings.gamepadBindings(GamepadDefaults.map());
```

Reload it in `onResume` (so returning from `GamepadSettingsActivity` picks up edits) alongside the other settings pushes:
```java
        bindings = settings.gamepadBindings(GamepadDefaults.map());
```

- [ ] **Step 2: Route `dispatchKeyEvent` through the bindings**

In `dispatchKeyEvent`, replace the `mapKey` lookup with the instance bindings (guard against a null field if a key arrives before `onCreate` finishes):
```java
        int key = bindings == null ? 0 : bindings.gbaKeyFor(event.getKeyCode());
        if (key != 0) {
            emulatorView.setHardwareKey(key, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(event);
```

Delete the now-unused `private static int mapKey(int keyCode)` method and, if it becomes unused, the `import ...MgbaSession` line only if nothing else in `MainActivity` references `MgbaSession` (check first — leave the import if still used).

- [ ] **Step 3: Unit tests + lint + build**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest lintDebug :app:assembleBenchmark`
Expected: tests PASS, lint 0 errors (no "unused method" warning for `mapKey` since it's deleted), APK assembles.

- [ ] **Step 4: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java
git commit -m "feat(app): drive gameplay from the remappable key bindings

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Device verification (clean AVD)

**Files:**
- Create: `docs/validation/phase4b-controllers-frameskip-<date>.md` (use the actual execution date)

**Interfaces:**
- Consumes: everything above.
- Produces: the Slice B exit evidence.

- [ ] **Step 1: Boot the clean AVD and install**

```sh
/usr/lib/android-sdk/emulator/emulator -avd game-emulator-mvp -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb -s emulator-5554 wait-for-device
# wait until: adb -s emulator-5554 shell getprop sys.boot_completed == 1
adb -s emulator-5554 install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
adb -s emulator-5554 push mgba-android/core/src/androidTest/assets/hello.gba /sdcard/Download/hello.gba
```
Import the ROM through the library's Import ROM → SAF picker (as in Phase 3/4a) and launch it.

- [ ] **Step 2: Verify remapping (validated via injected key events)**

The AVD has no controller; inject gamepad keycodes with `adb shell input keyevent`:
1. **Default map drives the game:** with default bindings, inject a bound gamepad keycode (e.g. `adb -s emulator-5554 shell input keyevent 96` = `KEYCODE_BUTTON_A`) and confirm via `MgbaPerf`/no-crash that the app processes it (the `hello.gba` test ROM has no visible reaction, so confirm the input path does not crash and, where possible, use a ROM/known-input that reacts, or inspect that `setHardwareKey` is reached). Record what was observed.
2. **Rebind:** open Settings → Controls → Gamepad buttons → tap **A** → inject a *different* keycode (e.g. `adb -s emulator-5554 shell input keyevent 29` = `KEYCODE_A`) → confirm the A row now shows the new key and persists.
3. **New binding drives the game; old does not:** return to the player; injecting the new keycode now drives GBA-A, and injecting the old `96` no longer does (confirm via no-crash + the bind screen state; note the observability limit of the all-black test ROM).
4. **Reset to defaults** restores the original bindings (bind screen shows the defaults again).
Note explicitly in the receipt: this validates the `dispatchKeyEvent → KeyBindings.gbaKeyFor → setHardwareKey` pipeline with real `KeyEvent`s, but **not** real Bluetooth/USB controller pairing (no hardware) — that gate stays open.

- [ ] **Step 3: Verify frameskip**

Set Settings → Emulation → Frameskip = 1. In game, capture `MgbaPerf` for ~20 s and confirm the **rendered** frame count drops noticeably versus frameskip 0 while the core keeps stepping (audio underruns not newly climbing). Repeat a quick check at Frameskip = 3. Set back to Off. Record the `frames=` evidence.

Note: `MgbaPerf`'s `frames=` counts core-stepped frames (every loop iteration), which stays ~full under frameskip. To evidence the blit reduction, either (a) add a temporary log or observe rendering smoothness, or (b) rely on the unit-tested `shouldRenderFrame` gate plus confirming no regression/crash and that the setting persists. Prefer (b) if (a) is not readily available, and state which was used.

- [ ] **Step 4: Confirm no crashes and record**

```sh
adb -s emulator-5554 logcat -d | grep -icE "FATAL|ANR in com.trebuchetdynamics.garnacha"
adb -s emulator-5554 exec-out screencap -p > docs/validation/phase4b-controllers-frameskip.png
adb -s emulator-5554 emu kill
```
Expected: 0 fatal/ANR.

- [ ] **Step 5: Write the receipt and commit**

Create `docs/validation/phase4b-controllers-frameskip-<date>.md` recording device (AVD), each check above marked pass/fail with what was seen, the injected-key-event caveat and the open Bluetooth-hardware gate, the frameskip evidence and which method was used, the 0-fatal/ANR result, and the screenshot. Then:
```bash
git add docs/validation/phase4b-controllers-frameskip-<date>.md docs/validation/phase4b-controllers-frameskip.png
git commit -m "docs: record Phase 4b device verification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** gamepad remapping = Tasks 1 (`KeyBindings`) + 3 (bind screen + `GamepadDefaults`) + 4 (runtime lookup); frameskip = Tasks 1 (`Settings`) + 2 (`shouldRenderFrame` + loop + picker). BT validation gate = Task 5 (injected key events; real hardware explicitly left open). Touch-layout editor is out of scope (separate future spec) per the design.
- **Type consistency:** `KeyBindings.of/parse/gbaKeyFor/bind/keyCodeFor/reset/serialize`, `Settings.frameskip/setFrameskip/clampFrameskip/gamepadBindings/setGamepadBindings`, `GamepadDefaults.map()`, `EmulationRunner.shouldRenderFrame` + the appended `int frameskip` ctor arg, and the `mapKey → bindings.gbaKeyFor` swap are used consistently across tasks.
- **Defaults preserve behavior:** `GamepadDefaults.map()` reproduces `mapKey` verbatim (Task 3 step 1 verifies against live code); `frameskip` default 0 → `shouldRenderFrame` always true → every frame blitted. So an untouched install is unchanged.
- **Interlock (app builds after each task):** Task 1 is additive (nothing calls it). Task 2 changes the `EmulationRunner` ctor and its sole call site together. Task 3 adds a reachable screen that persists but is not yet consulted at runtime. Task 4 flips runtime lookup to the bindings. Each task leaves a compiling, runnable app.
- **Sentinel consistency:** `gbaKeyFor` returns `0` for unbound; `dispatchKeyEvent` already treats `0` as "no key" (`if (key != 0)`), matching the old `mapKey` default `return 0`.
- **Deliberately deferred:** analog-stick axis handling, per-controller mappings, touch-layout editor, per-game profiles.
