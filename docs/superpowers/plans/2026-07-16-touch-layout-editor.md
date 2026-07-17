# Touch-Layout Editor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the player drag-reposition and uniformly resize the seven on-screen gamepad controls, persisted per orientation, overriding the computed `ControlLayout` geometry — with no drift between what is drawn and what is touchable.

**Architecture:** A pure `ControlOverrides` model (normalized center + scale per control `key`) is composed onto the defaults by a new pure `ControlLayout.of(w, h, overrides)` overload, clamped on-screen. `EmulatorView` builds that one layout per frame for both drawing and hit-testing (single source of truth preserved). A `LayoutEditorView` overlay launched from the in-game menu edits the current orientation's overrides (tap-select, drag-move, scale slider, Save/Reset/Cancel) and persists them via `Settings`.

**Tech Stack:** Java, Android `minSdk 24`, programmatic Views (no XML, no `androidx.preference`), Canvas, SharedPreferences.

**Spec:** `docs/superpowers/specs/2026-07-16-touch-layout-editor-design.md`

## Global Constraints

- Java, `minSdk 24`, package `com.trebuchetdynamics.emulator.app`. Programmatic Views only.
- **No regression for the default install:** with no overrides stored, every control renders and hit-tests exactly where it does today. `ControlLayout.of(w, h)` must behave identically to the current implementation.
- Pure logic (`ControlOverrides`, `LayoutEditMath`, `ControlLayout.of(..., overrides)`, `Settings` clamp/serde helpers) must have **no `android.*` imports** and be JVM-tested. `ControlLayout` already forbids `android.*` — keep it.
- **Single source of truth:** drawing and hit-testing must keep deriving from one `ControlLayout` built per frame; an override must move a control's picture and its touch box together.
- Control `key` ids (from `MgbaSession`): D-pad `0`, A `1`, B `2`, SELECT `4`, START `8`, R `256`, L `512` — all unique, valid override map keys.
- No change to the in-game menu save-state/notices/settings wiring beyond adding "Edit layout", to the ROM library/import/play-by-id contract, or to Phase 4a/4b behavior.
- Commit trailer on every commit: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Build/test (from repo root):
  - Unit tests: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest`
  - Lint: `tools/android_project/gradlew -p mgba-android lintDebug`
  - Benchmark APK: `tools/android_project/gradlew -p mgba-android :app:assembleBenchmark`

## File Structure

- `ControlOverrides.java` (new, pure) — per-control override map + serde + clamps.
- `LayoutEditMath.java` (new, pure) — finger→norm and slider↔scale conversions.
- `ControlLayout.java` (modify) — `of(w, h, overrides)` overload + clamp.
- `Settings.java` (modify) — per-orientation override get/set.
- `EmulatorView.java` (modify) — hold both override sets, apply in `of(...)`.
- `MainActivity.java` (modify) — load/push overrides, host the editor, handle `onEditLayout`.
- `InGameMenuView.java` (modify) — `onEditLayout` + "Edit layout" button.
- `LayoutEditorView.java` (new) — the WYSIWYG editor overlay.
- `res/values/strings.xml` (modify) — editor strings.
- Tests: `ControlOverridesTest.java`, `LayoutEditMathTest.java` (new); `ControlLayoutTest.java`, `SettingsTest.java` (extend).

Test dir: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/`
Main dir: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/`

---

### Task 1: Pure model — `ControlOverrides`, `LayoutEditMath`, `Settings` additions

**Files:**
- Create: `main/.../ControlOverrides.java`, `test/.../ControlOverridesTest.java`
- Create: `main/.../LayoutEditMath.java`, `test/.../LayoutEditMathTest.java`
- Modify: `main/.../Settings.java`, `test/.../SettingsTest.java`

**Interfaces:**
- Produces `ControlOverrides`: `static final ControlOverrides EMPTY`; `boolean has(int key)`; `float normCx(int)`, `normCy(int)`, `scale(int)`; `void put(int key, float normCx, float normCy, float scale)`; `void clear()`; `String serialize()`; `static ControlOverrides parse(String)`; `static float clampNorm(float)`; `static float clampScale(float)`.
- Produces `LayoutEditMath`: `static float toNorm(float pixel, float extent)`; `static float scaleForProgress(int progress, int max)`; `static int progressForScale(float scale, int max)`.
- Produces `Settings`: `ControlOverrides controlOverrides(boolean landscape)`, `void setControlOverrides(boolean landscape, ControlOverrides)`.

- [ ] **Step 1: Write the failing `ControlOverrides` test**

Create `ControlOverridesTest.java`:

```java
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*ControlOverridesTest'`
Expected: FAIL — `ControlOverrides` does not exist.

- [ ] **Step 3: Implement `ControlOverrides`**

Create `ControlOverrides.java`:

```java
package com.trebuchetdynamics.emulator.app;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure per-control layout overrides: for each control {@code key}, a normalized
 * center (fraction of width/height) and a uniform scale. No {@code android.*}
 * imports, so it is JVM-testable.
 *
 * <p>{@link #EMPTY} is a shared read-only default handed to
 * {@link ControlLayout#of(float, float, ControlOverrides)}; never mutate it.
 * {@link #parse} always returns a fresh mutable instance.
 */
final class ControlOverrides {

    /** Shared empty default. Do not mutate. */
    static final ControlOverrides EMPTY = new ControlOverrides();

    private static final class Entry {
        final float normCx;
        final float normCy;
        final float scale;

        Entry(float normCx, float normCy, float scale) {
            this.normCx = normCx;
            this.normCy = normCy;
            this.scale = scale;
        }
    }

    private final Map<Integer, Entry> map = new LinkedHashMap<>();

    boolean has(int key) {
        return map.containsKey(key);
    }

    float normCx(int key) {
        return map.get(key).normCx;
    }

    float normCy(int key) {
        return map.get(key).normCy;
    }

    float scale(int key) {
        return map.get(key).scale;
    }

    void put(int key, float normCx, float normCy, float scale) {
        map.put(key, new Entry(clampNorm(normCx), clampNorm(normCy), clampScale(scale)));
    }

    void clear() {
        map.clear();
    }

    String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Entry> e : new TreeMap<>(map).entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            Entry v = e.getValue();
            sb.append(e.getKey()).append(':')
                    .append(fmt(v.normCx)).append(':')
                    .append(fmt(v.normCy)).append(':')
                    .append(fmt(v.scale));
        }
        return sb.toString();
    }

    static ControlOverrides parse(String s) {
        ControlOverrides out = new ControlOverrides();
        if (s == null || s.trim().isEmpty()) {
            return out;
        }
        for (String field : s.split(",")) {
            if (field.trim().isEmpty()) {
                continue;
            }
            String[] parts = field.split(":");
            if (parts.length != 4) {
                continue;
            }
            try {
                int key = Integer.parseInt(parts[0].trim());
                float ncx = Float.parseFloat(parts[1].trim());
                float ncy = Float.parseFloat(parts[2].trim());
                float sc = Float.parseFloat(parts[3].trim());
                out.put(key, ncx, ncy, sc);
            } catch (NumberFormatException ignored) {
                // skip the malformed field, keep the good ones
            }
        }
        return out;
    }

    static float clampNorm(float v) {
        if (v < 0f) {
            return 0f;
        }
        return Math.min(v, 1f);
    }

    static float clampScale(float v) {
        if (v < 0.5f) {
            return 0.5f;
        }
        return Math.min(v, 2f);
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.4f", v);
    }
}
```

- [ ] **Step 4: Run `ControlOverrides` test to green**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*ControlOverridesTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing `LayoutEditMath` test**

Create `LayoutEditMathTest.java`:

```java
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
```

- [ ] **Step 6: Run to verify it fails**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*LayoutEditMathTest'`
Expected: FAIL — `LayoutEditMath` does not exist.

- [ ] **Step 7: Implement `LayoutEditMath`**

Create `LayoutEditMath.java`:

```java
package com.trebuchetdynamics.emulator.app;

/** Pure conversions for the layout editor's gestures and scale slider. */
final class LayoutEditMath {
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2f;

    private LayoutEditMath() {
    }

    /** A finger x/y as a normalized fraction of the extent, clamped to [0,1]. */
    static float toNorm(float pixel, float extent) {
        if (extent <= 0f) {
            return 0f;
        }
        return ControlOverrides.clampNorm(pixel / extent);
    }

    /** Slider position 0..max mapped linearly to [0.5, 2.0]. */
    static float scaleForProgress(int progress, int max) {
        if (max <= 0) {
            return 1f;
        }
        float t = (float) progress / max;
        return MIN_SCALE + t * (MAX_SCALE - MIN_SCALE);
    }

    /** Inverse of {@link #scaleForProgress}, rounded to the nearest step. */
    static int progressForScale(float scale, int max) {
        float clamped = ControlOverrides.clampScale(scale);
        float t = (clamped - MIN_SCALE) / (MAX_SCALE - MIN_SCALE);
        return Math.round(t * max);
    }
}
```

- [ ] **Step 8: Run `LayoutEditMath` test to green**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*LayoutEditMathTest'`
Expected: PASS.

- [ ] **Step 9: Write the failing `Settings` round-trip test**

`SettingsTest` is static-helper-only (confirmed in Phase 4b — no `Settings` instance / Robolectric harness). The `controlOverrides` getters need a real `SharedPreferences`, so do NOT add a Robolectric harness here. Instead, unit-test only what is pure, by asserting the (de)serialization contract the getters rely on, through `ControlOverrides` directly — which is already covered in `ControlOverridesTest`. Add one guard to `SettingsTest` documenting that the pref key selection is orientation-split, exercised on-device in Task 5:

```java
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
```

Add `import static org.junit.Assert.assertEquals;` if not already present.

- [ ] **Step 10: Run to verify it fails, then implement the `Settings` additions**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*SettingsTest'`
Expected: FAIL (compile) until the test's referenced types build — this test only needs `ControlOverrides` (from Step 3), so it should already pass once compiled; if it passes immediately that is acceptable (it documents the contract). Then add the `Settings` methods regardless.

In `Settings.java`, add constants near the others:
```java
    private static final String K_LAYOUT_PORTRAIT = "layoutOverridesPortrait";
    private static final String K_LAYOUT_LANDSCAPE = "layoutOverridesLandscape";
```
Add the methods (after the gamepad bindings methods):
```java
    ControlOverrides controlOverrides(boolean landscape) {
        String stored = prefs.getString(landscape ? K_LAYOUT_LANDSCAPE : K_LAYOUT_PORTRAIT, "");
        return ControlOverrides.parse(stored);
    }

    void setControlOverrides(boolean landscape, ControlOverrides overrides) {
        prefs.edit()
                .putString(landscape ? K_LAYOUT_LANDSCAPE : K_LAYOUT_PORTRAIT, overrides.serialize())
                .apply();
    }
```

- [ ] **Step 11: Full suite + lint**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest lintDebug`
Expected: all tests pass; lint 0 errors.

- [ ] **Step 12: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlOverrides.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlOverridesTest.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LayoutEditMath.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/LayoutEditMathTest.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/SettingsTest.java
git commit -m "feat(app): control-override model and per-orientation persistence

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `ControlLayout.of(w, h, overrides)` — the single-source apply seam

**Files:**
- Modify: `main/.../ControlLayout.java`
- Modify: `test/.../ControlLayoutTest.java`

**Interfaces:**
- Consumes: `ControlOverrides` (Task 1).
- Produces: `static ControlLayout of(float width, float height, ControlOverrides overrides)`; `of(width, height)` now delegates to it with `ControlOverrides.EMPTY`.

- [ ] **Step 1: Write the failing tests (draw == hit-test after override)**

Add to `ControlLayoutTest.java`:

```java
    @Test
    public void overrideMovesAndScalesTheNamedControlOnly() {
        float w = 1080f, h = 2340f;
        ControlLayout base = ControlLayout.of(w, h);
        ControlOverrides o = new ControlOverrides();
        o.put(MgbaSession.KEY_A, 0.5f, 0.5f, 2f); // move A to center, double size
        ControlLayout moved = ControlLayout.of(w, h, o);

        ControlLayout.Control baseA = control(base, MgbaSession.KEY_A);
        ControlLayout.Control movedA = control(moved, MgbaSession.KEY_A);
        assertEquals(0.5f * w, movedA.cx, 0.5f);
        assertEquals(0.5f * h, movedA.cy, 0.5f);
        assertEquals(baseA.halfWidth * 2f, movedA.halfWidth, 0.5f);
        assertEquals(baseA.halfHeight * 2f, movedA.halfHeight, 0.5f);

        // Un-overridden controls are unchanged.
        ControlLayout.Control baseB = control(base, MgbaSession.KEY_B);
        ControlLayout.Control movedB = control(moved, MgbaSession.KEY_B);
        assertEquals(baseB.cx, movedB.cx, 1e-3f);
        assertEquals(baseB.cy, movedB.cy, 1e-3f);
        assertEquals(baseB.halfWidth, movedB.halfWidth, 1e-3f);
    }

    @Test
    public void keysAtFollowsTheOverriddenControl() {
        float w = 1080f, h = 2340f;
        ControlOverrides o = new ControlOverrides();
        o.put(MgbaSession.KEY_A, 0.5f, 0.5f, 1f);
        ControlLayout moved = ControlLayout.of(w, h, o);
        // Hit-test at the new drawn center -> KEY_A bit set.
        assertTrue((moved.keysAt(0.5f * w, 0.5f * h) & MgbaSession.KEY_A) != 0);
        // The old default A location no longer reports KEY_A.
        ControlLayout base = ControlLayout.of(w, h);
        ControlLayout.Control baseA = control(base, MgbaSession.KEY_A);
        assertEquals(0, moved.keysAt(baseA.cx, baseA.cy) & MgbaSession.KEY_A);
    }

    @Test
    public void overrideClampsControlFullyOnScreen() {
        float w = 1080f, h = 2340f;
        ControlOverrides o = new ControlOverrides();
        o.put(MgbaSession.KEY_START, 1f, 1f, 2f); // push to bottom-right corner, big
        ControlLayout.Control c = control(ControlLayout.of(w, h, o), MgbaSession.KEY_START);
        assertTrue(c.cx + c.halfWidth <= w + 1e-3f);
        assertTrue(c.cy + c.halfHeight <= h + 1e-3f);
        assertTrue(c.cx - c.halfWidth >= -1e-3f);
        assertTrue(c.cy - c.halfHeight >= -1e-3f);
    }

    @Test
    public void movedDpadStillDecomposesIntoDirections() {
        float w = 2340f, h = 1080f;
        ControlOverrides o = new ControlOverrides();
        o.put(0, 0.5f, 0.5f, 1f); // move D-pad (key 0) to center
        ControlLayout moved = ControlLayout.of(w, h, o);
        ControlLayout.Control d = control(moved, 0);
        // A point clearly left of center within the D-pad box -> LEFT bit.
        assertTrue((moved.keysAt(d.cx - d.halfWidth * 0.8f, d.cy) & MgbaSession.KEY_LEFT) != 0);
    }

    @Test
    public void defaultOverloadEqualsEmptyOverrides() {
        float w = 1080f, h = 2340f;
        ControlLayout a = ControlLayout.of(w, h);
        ControlLayout b = ControlLayout.of(w, h, ControlOverrides.EMPTY);
        assertEquals(a.controls.size(), b.controls.size());
        for (int i = 0; i < a.controls.size(); i++) {
            assertEquals(a.controls.get(i).cx, b.controls.get(i).cx, 1e-4f);
            assertEquals(a.controls.get(i).cy, b.controls.get(i).cy, 1e-4f);
            assertEquals(a.controls.get(i).halfWidth, b.controls.get(i).halfWidth, 1e-4f);
        }
    }

    private static ControlLayout.Control control(ControlLayout layout, int key) {
        for (ControlLayout.Control c : layout.controls) {
            if (c.key == key) {
                return c;
            }
        }
        throw new IllegalArgumentException("no control key=" + key);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*ControlLayoutTest'`
Expected: FAIL — `of(w, h, overrides)` does not exist.

- [ ] **Step 3: Implement the overload + clamp**

In `ControlLayout.java`:

Change the existing entry point so both builders route through the override applier. Replace:
```java
    static ControlLayout of(float width, float height) {
        return width > height ? landscape(width, height) : portrait(width, height);
    }
```
with:
```java
    static ControlLayout of(float width, float height) {
        return of(width, height, ControlOverrides.EMPTY);
    }

    static ControlLayout of(float width, float height, ControlOverrides overrides) {
        ControlLayout base = width > height ? landscape(width, height) : portrait(width, height);
        if (overrides == null || overrides == ControlOverrides.EMPTY) {
            return base;
        }
        List<Control> applied = new ArrayList<>(base.controls.size());
        boolean changed = false;
        for (Control c : base.controls) {
            if (overrides.has(c.key)) {
                applied.add(applyOverride(c, overrides, width, height));
                changed = true;
            } else {
                applied.add(c);
            }
        }
        if (!changed) {
            return base;
        }
        return new ControlLayout(base.gameLeft, base.gameTop, base.gameRight, base.gameBottom,
                base.loadLeft, base.loadTop, base.loadRight, base.loadBottom,
                base.noticesLeft, base.noticesTop, base.noticesRight, base.noticesBottom,
                base.menuLeft, base.menuTop, base.menuRight, base.menuBottom, applied);
    }

    private static Control applyOverride(Control c, ControlOverrides o, float w, float h) {
        float halfWidth = c.halfWidth * o.scale(c.key);
        float halfHeight = c.halfHeight * o.scale(c.key);
        float cx = clampCenter(o.normCx(c.key) * w, halfWidth, w);
        float cy = clampCenter(o.normCy(c.key) * h, halfHeight, h);
        return new Control(c.key, c.label, c.shape, cx, cy, halfWidth, halfHeight);
    }

    /** Keep a control's box fully within [0, extent]; center it if it cannot fit. */
    private static float clampCenter(float center, float half, float extent) {
        if (half * 2f >= extent) {
            return extent / 2f;
        }
        if (center < half) {
            return half;
        }
        return Math.min(center, extent - half);
    }
```

(`java.util.ArrayList` and `java.util.List` are already imported.)

- [ ] **Step 4: Run the tests to green**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*ControlLayoutTest'`
Expected: PASS (new tests + all pre-existing `ControlLayoutTest` cases).

- [ ] **Step 5: Full suite + lint**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest lintDebug`
Expected: all pass; lint 0 errors.

- [ ] **Step 6: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlLayout.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlLayoutTest.java
git commit -m "feat(app): apply per-control overrides in ControlLayout

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Apply overrides in the running player (no editor yet)

**Files:**
- Modify: `main/.../EmulatorView.java`
- Modify: `main/.../MainActivity.java`

**Interfaces:**
- Consumes: `ControlOverrides`, `Settings.controlOverrides` (Task 1), `ControlLayout.of(w,h,overrides)` (Task 2).
- Produces: `EmulatorView.setControlOverrides(ControlOverrides portrait, ControlOverrides landscape)`.

> After this task the app builds and runs identically to before, because no overrides are ever stored yet (the editor arrives in Task 4). Its value is the verified apply path and no-regression.

- [ ] **Step 1: Add the override fields + setter to `EmulatorView`**

In `EmulatorView.java`, near the `layout` field:
```java
    private ControlOverrides portraitOverrides = ControlOverrides.EMPTY;
    private ControlOverrides landscapeOverrides = ControlOverrides.EMPTY;
```
Add a setter next to the other Phase-4 setters (`setIntegerScale`, etc.):
```java
    void setControlOverrides(ControlOverrides portrait, ControlOverrides landscape) {
        this.portraitOverrides = portrait == null ? ControlOverrides.EMPTY : portrait;
        this.landscapeOverrides = landscape == null ? ControlOverrides.EMPTY : landscape;
        invalidate();
    }

    private ControlOverrides activeOverrides(int w, int h) {
        return w > h ? landscapeOverrides : portraitOverrides;
    }
```

- [ ] **Step 2: Build the layout with the active overrides**

In `EmulatorView.java`, change the two layout-build sites:

`onSizeChanged` — replace `layout = ControlLayout.of(w, h);` with:
```java
        layout = ControlLayout.of(w, h, activeOverrides(w, h));
```
`onDraw` — replace `layout = ControlLayout.of(getWidth(), getHeight());` with:
```java
        layout = ControlLayout.of(getWidth(), getHeight(), activeOverrides(getWidth(), getHeight()));
```
`onTouchEvent` is unchanged: it reads the `layout` field that `onDraw` built, so it now hit-tests the overridden layout automatically — the draw/hit-test single source is preserved.

- [ ] **Step 3: Load + push overrides in `MainActivity.onResume`**

In `MainActivity.java`, in `onResume` next to the other `emulatorView.set…` pushes (after `setIntegerScale`):
```java
        emulatorView.setControlOverrides(
                settings.controlOverrides(false), settings.controlOverrides(true));
```

- [ ] **Step 4: Build, test, lint, APK**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest lintDebug :app:assembleBenchmark`
Expected: tests pass, lint 0 errors, APK assembles. Behavior is unchanged (no overrides stored → `EMPTY` → default layout).

- [ ] **Step 5: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java
git commit -m "feat(app): apply saved control overrides to the player view

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: The WYSIWYG editor overlay

**Files:**
- Create: `main/.../LayoutEditorView.java`
- Modify: `main/.../InGameMenuView.java`
- Modify: `main/.../MainActivity.java`
- Modify: `main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ControlOverrides`, `LayoutEditMath`, `ControlLayout.of(w,h,overrides)`, `Settings.controlOverrides/setControlOverrides`, `EmulatorView.setControlOverrides`.
- Produces: `InGameMenuView.Listener.onEditLayout()`; a hostable `LayoutEditorView`.

- [ ] **Step 1: Add `onEditLayout` to the in-game menu**

In `InGameMenuView.java`, add to the `Listener` interface (after `onSettings`):
```java
        void onEditLayout();
```
Add a button in the constructor, right after the Settings button (line ~47):
```java
        addView(wideButton(context.getString(R.string.menu_edit_layout), v -> listener.onEditLayout()));
```
Add to `res/values/strings.xml`:
```xml
    <string name="menu_edit_layout">Edit layout</string>
    <string name="layout_save">Save</string>
    <string name="layout_reset">Reset</string>
    <string name="layout_cancel">Cancel</string>
```

- [ ] **Step 2: Create `LayoutEditorView`**

Create `LayoutEditorView.java`:

```java
package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

/**
 * WYSIWYG editor overlay for the on-screen control layout. Shown on top of the
 * running player; captures all touch so no input reaches the game. Edits the
 * current orientation's {@link ControlOverrides} and persists on Save.
 */
public final class LayoutEditorView extends FrameLayout {

    interface Callback {
        /** The editor finished (saved or cancelled); host should reload + hide. */
        void onLayoutEditorClosed();
    }

    private final Settings settings;
    private final Callback callback;
    private final Surface surface;
    private final SeekBar scaleBar;

    private ControlOverrides working = new ControlOverrides();
    private boolean landscape;
    private boolean hasSelection;
    private int selectedKey;
    private float selNormCx;
    private float selNormCy;
    private float selScale = 1f;

    LayoutEditorView(Context context, Settings settings, Callback callback) {
        super(context);
        this.settings = settings;
        this.callback = callback;

        surface = new Surface(context);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xCC0E1014);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(8);
        bar.setPadding(pad, pad, pad, pad);

        scaleBar = new SeekBar(context);
        scaleBar.setMax(100);
        scaleBar.setEnabled(false);
        scaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && hasSelection) {
                    selScale = LayoutEditMath.scaleForProgress(progress, sb.getMax());
                    working.put(selectedKey, selNormCx, selNormCy, selScale);
                    surface.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        bar.addView(scaleBar, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 3f));
        bar.addView(button(context, R.string.layout_reset, v -> {
            working.clear();
            hasSelection = false;
            scaleBar.setEnabled(false);
            surface.invalidate();
        }));
        bar.addView(button(context, R.string.layout_cancel, v -> callback.onLayoutEditorClosed()));
        bar.addView(button(context, R.string.layout_save, v -> {
            settings.setControlOverrides(landscape, working);
            callback.onLayoutEditorClosed();
        }));

        LayoutParams barParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.BOTTOM;
        addView(bar, barParams);
    }

    /** Seed the editor from the saved overrides for the current orientation. */
    void begin(boolean landscape) {
        this.landscape = landscape;
        this.working = settings.controlOverrides(landscape);
        this.hasSelection = false;
        scaleBar.setEnabled(false);
        surface.invalidate();
    }

    private Button button(Context context, int textRes, View.OnClickListener onClick) {
        Button b = new Button(context);
        b.setText(textRes);
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** The drawing + drag surface. */
    private final class Surface extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Surface(Context context) {
            super(context);
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(4f);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            ControlLayout layout = ControlLayout.of(w, h, working);
            text.setTextSize(Math.max(28, w * 0.03f));
            for (ControlLayout.Control c : layout.controls) {
                boolean sel = hasSelection && c.key == selectedKey;
                fill.setColor(sel ? 0x5533C1B3 : 0x33FFFFFF);
                stroke.setColor(sel ? 0xFF33C1B3 : 0x88FFFFFF);
                if (c.shape == ControlLayout.Shape.CIRCLE) {
                    canvas.drawCircle(c.cx, c.cy, c.halfWidth, fill);
                    canvas.drawCircle(c.cx, c.cy, c.halfWidth, stroke);
                } else {
                    canvas.drawRect(c.cx - c.halfWidth, c.cy - c.halfHeight,
                            c.cx + c.halfWidth, c.cy + c.halfHeight, fill);
                    canvas.drawRect(c.cx - c.halfWidth, c.cy - c.halfHeight,
                            c.cx + c.halfWidth, c.cy + c.halfHeight, stroke);
                }
                String label = c.shape == ControlLayout.Shape.DPAD ? "D-pad" : c.label;
                if (label != null && !label.isEmpty()) {
                    canvas.drawText(label, c.cx, c.cy + text.getTextSize() * 0.35f, text);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int w = getWidth();
            int h = getHeight();
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    ControlLayout layout = ControlLayout.of(w, h, working);
                    ControlLayout.Control hit = pick(layout, x, y);
                    if (hit == null) {
                        hasSelection = false;
                        scaleBar.setEnabled(false);
                    } else {
                        hasSelection = true;
                        selectedKey = hit.key;
                        selNormCx = hit.cx / w;
                        selNormCy = hit.cy / h;
                        selScale = working.has(hit.key) ? working.scale(hit.key) : 1f;
                        scaleBar.setEnabled(true);
                        scaleBar.setProgress(LayoutEditMath.progressForScale(selScale, scaleBar.getMax()));
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (hasSelection) {
                        selNormCx = LayoutEditMath.toNorm(x, w);
                        selNormCy = LayoutEditMath.toNorm(y, h);
                        working.put(selectedKey, selNormCx, selNormCy, selScale);
                        invalidate();
                    }
                    return true;
                }
                default:
                    return true;
            }
        }

        private ControlLayout.Control pick(ControlLayout layout, float x, float y) {
            ControlLayout.Control best = null;
            float bestArea = Float.MAX_VALUE;
            for (ControlLayout.Control c : layout.controls) {
                if (c.contains(x, y)) {
                    float area = c.halfWidth * c.halfHeight;
                    if (area < bestArea) {
                        bestArea = area;
                        best = c;
                    }
                }
            }
            return best;
        }
    }
}
```

- [ ] **Step 3: Host the editor in `MainActivity`**

In `MainActivity.java`:

Add a field near `menu`:
```java
    private LayoutEditorView layoutEditor;
```
In `onCreate`, after the `menu` is added to `root` (after line ~89), create and add the editor overlay (initially hidden):
```java
        layoutEditor = new LayoutEditorView(this, settings, () -> {
            layoutEditor.setVisibility(View.GONE);
            emulatorView.setControlOverrides(
                    settings.controlOverrides(false), settings.controlOverrides(true));
        });
        layoutEditor.setVisibility(View.GONE);
        root.addView(layoutEditor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
```
In the `InGameMenuView.Listener` anonymous implementation (the `new InGameMenuView.Listener() { … }` block), add:
```java
            @Override public void onEditLayout() {
                menu.setVisibility(View.GONE);
                boolean landscape = emulatorView.getWidth() > emulatorView.getHeight();
                layoutEditor.begin(landscape);
                layoutEditor.setVisibility(View.VISIBLE);
                layoutEditor.bringToFront();
            }
```
(The `onLayoutEditorClosed` callback above already reloads both override sets into `emulatorView` and hides the editor. `bringToFront()` guarantees the editor sits above the menu/emulator in the `FrameLayout`.)

- [ ] **Step 4: Build, test, lint, APK**

Run: `tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest lintDebug :app:assembleBenchmark`
Expected: tests pass, lint 0 errors, APK assembles. The whole app compiles (the new `Listener.onEditLayout` is implemented in `MainActivity`).

- [ ] **Step 5: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LayoutEditorView.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/InGameMenuView.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java \
        mgba-android/app/src/main/res/values/strings.xml
git commit -m "feat(app): WYSIWYG touch-layout editor from the in-game menu

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Device verification (clean AVD)

**Files:**
- Create: `docs/validation/touch-layout-editor-<date>.md` (actual execution date)

**Interfaces:**
- Consumes: everything above.
- Produces: the slice's exit evidence.

- [ ] **Step 1: Boot AVD + install**

```sh
/usr/lib/android-sdk/emulator/emulator -avd game-emulator-mvp -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb -s emulator-5554 wait-for-device
# wait until sys.boot_completed == 1
adb -s emulator-5554 install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
adb -s emulator-5554 push mgba-android/core/src/androidTest/assets/hello.gba /sdcard/Download/hello.gba
```
Import (SAF picker; media-scan the pushed file first if it does not appear:
`adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/hello.gba`) and launch the ROM.

- [ ] **Step 2: Edit the layout**

1. Open the in-game **MENU** → **Edit layout**; confirm the editor overlay shows the controls with outlines and a bottom bar (scale slider + Reset/Cancel/Save).
2. **Drag the D-pad** to a clearly different position; **tap the A button**, raise the **scale slider** to enlarge it. Screenshot.
3. **Save.** Confirm the editor closes and the controls now **render** at the new positions/sizes in the player.
4. **Hit-test check (draw == touch):** tap/`input tap` at the D-pad's *new* on-screen location and confirm it registers as a D-pad press (e.g. no crash and, where a reacting ROM is available, movement); confirm the *old* D-pad location no longer does. Note the all-black `hello.gba` observability limit and rely on the unit-tested `keysAt`-follows-override guarantee for the exact bit, plus the visible render move.
5. **Relaunch** the ROM (back to library, tap the game) → confirm the custom layout **persists**.
6. Re-open **Edit layout** → **Reset** → **Save** → confirm controls return to defaults and persist.
7. If orientation is not locked, rotate and confirm the other orientation keeps its **own** (default) layout — portrait/landscape independence.

- [ ] **Step 3: No crashes + record**

```sh
adb -s emulator-5554 logcat -d | grep -icE "FATAL|ANR in com.trebuchetdynamics.garnacha"
adb -s emulator-5554 exec-out screencap -p > docs/validation/touch-layout-editor.png
adb -s emulator-5554 emu kill
```
Expected: 0 fatal/ANR.

- [ ] **Step 4: Write the receipt + commit**

Create `docs/validation/touch-layout-editor-<date>.md` recording device (AVD), each check pass/fail with what was seen, the draw==hit-test observation and any observability caveat, persistence + reset + per-orientation results, the 0-fatal/ANR result, and the screenshot. Then:
```bash
git add docs/validation/touch-layout-editor-<date>.md docs/validation/touch-layout-editor.png
git commit -m "docs: record touch-layout editor device verification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** override model = Task 1 (`ControlOverrides` + `LayoutEditMath` + `Settings`); apply seam = Task 2 (`ControlLayout.of(w,h,overrides)`); runtime application = Task 3 (`EmulatorView` + `MainActivity`); editor = Task 4 (`LayoutEditorView` + in-game menu + host). Device = Task 5. Chips/game-rect editing, independent W/H resize, per-game layouts are out of scope per the design.
- **Type consistency:** `ControlOverrides` (has/normCx/normCy/scale/put/clear/serialize/parse/clamp*, EMPTY), `LayoutEditMath` (toNorm/scaleForProgress/progressForScale), `ControlLayout.of(w,h,overrides)`, `Settings.controlOverrides/setControlOverrides`, `EmulatorView.setControlOverrides`, `InGameMenuView.Listener.onEditLayout`, `LayoutEditorView.begin/Callback.onLayoutEditorClosed` are used consistently across tasks.
- **Single source of truth:** `EmulatorView` builds `ControlLayout.of(w,h,activeOverrides)` in `onDraw`/`onSizeChanged`; `onTouchEvent` reads the same `layout` field. Task 2's `keysAtFollowsTheOverriddenControl` test locks the draw==hit-test guarantee; the device pass re-confirms it by pressing the moved control.
- **Defaults preserve behavior:** `of(w,h)` delegates to `of(w,h,EMPTY)` and returns the un-wrapped `base` when overrides are empty/absent, so an install with no stored overrides is byte-identical to today (pinned by `defaultOverloadEqualsEmptyOverrides`).
- **EMPTY aliasing safety:** `ControlOverrides.EMPTY` is never mutated — `parse` and `new ControlOverrides()` return fresh instances; the editor edits a fresh `settings.controlOverrides(...)` copy; only `of(...)` reads `EMPTY`.
- **Interlock (app builds after each task):** T1 additive; T2 adds the overload with `of(w,h)` delegating; T3 wires the view/activity to the T2 overload (no overrides stored yet → no visible change); T4 adds the editor and the `Listener.onEditLayout` method implemented in the same task. Each task leaves a compiling, runnable app.
- **Deliberately deferred:** editing chips/game rect, independent W/H, pinch-scale, snap-to-grid, per-game layouts.
