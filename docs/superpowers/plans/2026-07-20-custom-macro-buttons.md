# Custom Macro Buttons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add up to eight arbitrary touch-input combination buttons per orientation, with optional whole-combination 15 Hz Turbo, managed through the existing layout editor.

**Architecture:** A new pure `MacroControls` model owns validated macro definitions and serialization. `ControlLayout` appends those definitions to its existing geometry and returns separate normal/Turbo hit channels; `EmulatorView` applies a fixed frame cadence before the existing mGBA bitmask crosses the runner boundary. `LayoutEditorView` edits working macro and override copies, then `Settings` persists both layout records together.

**Tech Stack:** Java 8, Android SDK widgets/Canvas/SharedPreferences, JUnit 4, existing mGBA Android core; no new dependencies and no JNI changes.

## Global Constraints

- Macro sets are separate for portrait and landscape.
- Maximum eight macros per orientation.
- A macro contains any non-empty subset of Left, Up, Right, Down, A, B, L, R, Select, and Start.
- Turbo pulses the complete macro mask with two frames on and two frames off: 15 complete pulses per second at nominal 60 FPS.
- Empty and duplicate `(keyMask, turbo)` definitions are rejected.
- Reset removes every macro and override for only the active orientation.
- Back discards working edits; checkmark saves macros, overrides, and opacity together.
- Stock controls cannot be deleted.
- No adjustable Turbo rate, recorded sequences, hardware-controller macros, skins, or new native API.
- Preserve existing game geometry, stock touch hit areas, hardware mappings, save behavior, and unrelated dirty-worktree changes.
- Commit steps are only safe in a clean isolated worktree. In the current dirty worktree, skip commits rather than including pre-existing changes from the same files.

---

## File Map

**Create**

- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MacroControls.java` — pure macro definition, validation, labels, stable layout IDs, copy, and serialization.
- `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/MacroControlsTest.java` — model and persistence contract.

**Modify**

- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlLayout.java` — macro geometry, distinct layout IDs, and normal/Turbo hit channels.
- `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlLayoutTest.java` — geometry, overrides, and channel separation.
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FeelMath.java` — pure Turbo cadence.
- `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FeelMathTest.java` — cadence and held-input behavior.
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java` — portrait/landscape macro preferences and one-editor transaction.
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java` — active macro set, separate touch channels, macro rendering, and frame-resolved keys.
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java` — request frame-resolved input.
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java` — load saved macro sets into play and refresh them after editing.
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LayoutEditorView.java` — Add dialog, selection, deletion, reset, and transactional save.
- `mgba-android/app/src/main/res/values/strings.xml` — macro editor labels, validation, and accessibility copy.
- `docs/design/pizzaboy-live/play-store-listing-study.md` — record the implemented customization capability without claiming skins or scripting.

---

### Task 1: Pure Macro Definition and Serialization

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MacroControls.java`
- Create: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/MacroControlsTest.java`

**Interfaces:**
- Consumes: mGBA `KEY_*` compile-time constants from `MgbaSession`.
- Produces: `MacroControls`, `MacroControls.Macro`, `add(int, boolean)`, `removeLayoutId(int)`, `copy()`, `serialize()`, `parse(String)`, `isMacroLayoutId(int)`, and `macroForLayoutId(int)`.

- [ ] **Step 1: Write failing model tests**

Create `MacroControlsTest.java` with these concrete contracts:

```java
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
        MacroControls.Macro replacement = controls.add(MgbaSession.KEY_L | MgbaSession.KEY_R, false);
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
                "1:1:true,1:2:false,2:0:false,3:1048576:true,junk,4:2:false,5:2:false,6:4:maybe");
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
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest \
  --tests com.trebuchetdynamics.emulator.app.MacroControlsTest
```

Expected: compilation fails because `MacroControls` does not exist.

- [ ] **Step 3: Implement the minimal pure model**

Create `MacroControls.java` with this API and validation structure:

```java
package com.trebuchetdynamics.emulator.app;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class MacroControls {
    static final int MAX_CONTROLS = 8;
    private static final int LAYOUT_ID_BASE = 0x10000;
    static final int SUPPORTED_KEYS = MgbaSession.KEY_LEFT | MgbaSession.KEY_UP
            | MgbaSession.KEY_RIGHT | MgbaSession.KEY_DOWN | MgbaSession.KEY_A
            | MgbaSession.KEY_B | MgbaSession.KEY_L | MgbaSession.KEY_R
            | MgbaSession.KEY_SELECT | MgbaSession.KEY_START;
    static final MacroControls EMPTY = new MacroControls();

    static final class Macro {
        final int slot;
        final int keyMask;
        final boolean turbo;

        Macro(int slot, int keyMask, boolean turbo) {
            this.slot = slot;
            this.keyMask = keyMask;
            this.turbo = turbo;
        }

        int layoutId() { return layoutIdForSlot(slot); }

        String shortLabel() {
            String label = Integer.bitCount(keyMask) > 3
                    ? Integer.bitCount(keyMask) + " keys"
                    : joinLabels(keyMask, true);
            return turbo ? label + " ⚡" : label;
        }

        String contentLabel() {
            String label = joinLabels(keyMask, false);
            return turbo ? "Turbo " + label : label;
        }
    }

    private final Map<Integer, Macro> bySlot = new TreeMap<>();

    int size() { return bySlot.size(); }
    boolean isFull() { return size() >= MAX_CONTROLS; }
    List<Macro> values() { return Collections.unmodifiableList(new ArrayList<>(bySlot.values())); }

    Macro add(int keyMask, boolean turbo) {
        requireMask(keyMask);
        if (containsDefinition(keyMask, turbo)) {
            throw new IllegalArgumentException("That custom button already exists");
        }
        if (isFull()) {
            throw new IllegalStateException("Maximum 8 custom buttons per orientation");
        }
        for (int slot = 1; slot <= MAX_CONTROLS; slot++) {
            if (!bySlot.containsKey(slot)) {
                Macro macro = new Macro(slot, keyMask, turbo);
                bySlot.put(slot, macro);
                return macro;
            }
        }
        throw new AssertionError("No free macro slot");
    }

    boolean removeLayoutId(int layoutId) {
        return bySlot.remove(slotForLayoutId(layoutId)) != null;
    }

    Macro macroForLayoutId(int layoutId) {
        return isMacroLayoutId(layoutId) ? bySlot.get(slotForLayoutId(layoutId)) : null;
    }

    boolean containsDefinition(int keyMask, boolean turbo) {
        for (Macro macro : bySlot.values()) {
            if (macro.keyMask == keyMask && macro.turbo == turbo) return true;
        }
        return false;
    }

    void clear() { bySlot.clear(); }

    MacroControls copy() {
        MacroControls copy = new MacroControls();
        copy.bySlot.putAll(bySlot);
        return copy;
    }

    String serialize() {
        StringBuilder out = new StringBuilder();
        for (Macro macro : bySlot.values()) {
            if (out.length() > 0) out.append(',');
            out.append(macro.slot).append(':').append(macro.keyMask)
                    .append(':').append(macro.turbo);
        }
        return out.toString();
    }

    static MacroControls parse(String stored) {
        MacroControls out = new MacroControls();
        if (stored == null || stored.trim().isEmpty()) return out;
        for (String record : stored.split(",")) {
            String[] fields = record.split(":");
            if (fields.length != 3) continue;
            try {
                int slot = Integer.parseInt(fields[0]);
                int mask = Integer.parseInt(fields[1]);
                if (!"true".equals(fields[2]) && !"false".equals(fields[2])) continue;
                boolean turbo = Boolean.parseBoolean(fields[2]);
                if (slot < 1 || slot > MAX_CONTROLS || out.bySlot.containsKey(slot)
                        || !isValidMask(mask) || out.containsDefinition(mask, turbo)) continue;
                out.bySlot.put(slot, new Macro(slot, mask, turbo));
            } catch (NumberFormatException ignored) {
                // Keep valid records when one record is malformed.
            }
        }
        return out;
    }

    static int layoutIdForSlot(int slot) { return LAYOUT_ID_BASE + slot; }
    static boolean isMacroLayoutId(int id) {
        return id > LAYOUT_ID_BASE && id <= LAYOUT_ID_BASE + MAX_CONTROLS;
    }
    private static int slotForLayoutId(int id) { return id - LAYOUT_ID_BASE; }
    private static boolean isValidMask(int mask) {
        return mask != 0 && (mask & ~SUPPORTED_KEYS) == 0;
    }
    private static void requireMask(int mask) {
        if (!isValidMask(mask)) throw new IllegalArgumentException("Choose at least one input");
    }

    private static String joinLabels(int mask, boolean symbols) {
        int[] keys = { MgbaSession.KEY_LEFT, MgbaSession.KEY_UP, MgbaSession.KEY_RIGHT,
                MgbaSession.KEY_DOWN, MgbaSession.KEY_A, MgbaSession.KEY_B,
                MgbaSession.KEY_L, MgbaSession.KEY_R, MgbaSession.KEY_SELECT,
                MgbaSession.KEY_START };
        String[] shortNames = { "←", "↑", "→", "↓", "A", "B", "L", "R", "Select", "Start" };
        String[] fullNames = { "Left", "Up", "Right", "Down", "A", "B", "L", "R",
                "Select", "Start" };
        List<String> names = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            if ((mask & keys[i]) != 0) names.add(symbols ? shortNames[i] : fullNames[i]);
        }
        String separator = symbols ? " + " : " plus ";
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) out.append(separator);
            out.append(name);
        }
        return out.toString();
    }
}
```

Keep `EMPTY` read-only by convention, matching `ControlOverrides.EMPTY`; never hand it to editor mutation paths.

- [ ] **Step 4: Run model tests and verify GREEN**

Run the Task 1 command again.

Expected: all `MacroControlsTest` methods pass.

- [ ] **Step 5: Commit only in a clean isolated worktree**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MacroControls.java \
  mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/MacroControlsTest.java
git commit -m "feat(android): add macro control model"
```

Expected: one model-only commit. In the current dirty worktree, skip this command.

---

### Task 2: Macro Geometry and Separate Hit Channels

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlLayout.java`
- Modify: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlLayoutTest.java`

**Interfaces:**
- Consumes: `MacroControls.values()`, `Macro.layoutId()`, `Macro.keyMask`, and `Macro.turbo`.
- Produces: `Control.id`, `Control.key`, `Control.turbo`, `ControlLayout.Input`, and the overload `ControlLayout.of(..., MacroControls macros)`.

- [ ] **Step 1: Add failing geometry and input-channel tests**

Append these tests to `ControlLayoutTest`:

```java
@Test public void macroUsesStableLayoutIdAndOverride() {
    MacroControls macros = new MacroControls();
    MacroControls.Macro macro = macros.add(MgbaSession.KEY_LEFT | MgbaSession.KEY_A, false);
    ControlOverrides overrides = new ControlOverrides();
    overrides.put(macro.layoutId(), 0.5f, 0.4f, 1.5f);
    ControlLayout layout = ControlLayout.of(1080f, 2340f, overrides,
            MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, true, macros);
    ControlLayout.Control control = controlById(layout, macro.layoutId());
    assertEquals(MgbaSession.KEY_LEFT | MgbaSession.KEY_A, control.key);
    assertEquals(540f, control.cx, 0.5f);
    assertEquals(936f, control.cy, 0.5f);
    assertEquals(1.5f, overrides.scale(control.id), 0.001f);
}

@Test public void normalAndTurboMacrosUseSeparateChannels() {
    MacroControls macros = new MacroControls();
    MacroControls.Macro normal = macros.add(MgbaSession.KEY_LEFT | MgbaSession.KEY_A, false);
    MacroControls.Macro turbo = macros.add(MgbaSession.KEY_B, true);
    ControlOverrides overrides = new ControlOverrides();
    overrides.put(normal.layoutId(), 0.4f, 0.8f, 1f);
    overrides.put(turbo.layoutId(), 0.7f, 0.8f, 1f);
    ControlLayout layout = ControlLayout.of(1080f, 2340f, overrides,
            MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, true, macros);
    ControlLayout.Control normalControl = controlById(layout, normal.layoutId());
    ControlLayout.Control turboControl = controlById(layout, turbo.layoutId());
    ControlLayout.Input normalInput = layout.inputAt(normalControl.cx, normalControl.cy);
    ControlLayout.Input turboInput = layout.inputAt(turboControl.cx, turboControl.cy);
    assertEquals(MgbaSession.KEY_LEFT | MgbaSession.KEY_A, normalInput.normalKeys);
    assertEquals(0, normalInput.turboKeys);
    assertEquals(0, turboInput.normalKeys);
    assertEquals(MgbaSession.KEY_B, turboInput.turboKeys);
}
```

Add this helper near the existing `control(...)` helper:

```java
private static ControlLayout.Control controlById(ControlLayout layout, int id) {
    for (ControlLayout.Control control : layout.controls) {
        if (control.id == id) return control;
    }
    throw new AssertionError("Missing control id " + id);
}
```

- [ ] **Step 2: Run focused tests and verify RED**

```bash
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest \
  --tests com.trebuchetdynamics.emulator.app.ControlLayoutTest.macroUsesStableLayoutIdAndOverride \
  --tests com.trebuchetdynamics.emulator.app.ControlLayoutTest.normalAndTurboMacrosUseSeparateChannels
```

Expected: compilation fails for missing overload, `Control.id`, and `ControlLayout.Input`.

- [ ] **Step 3: Separate layout identity from emitted keys**

Change `ControlLayout.Control` to expose:

```java
final int id;
final int key;
final boolean turbo;

Control(int id, int key, boolean turbo, String label, Shape shape,
        float cx, float cy, float halfWidth, float halfHeight) {
    this.id = id;
    this.key = key;
    this.turbo = turbo;
    this.label = label;
    this.shape = shape;
    this.cx = cx;
    this.cy = cy;
    this.halfWidth = halfWidth;
    this.halfHeight = halfHeight;
}
```

Update every stock construction to pass `id == key`, `key == key`, and
`turbo == false`. The D-pad remains `id == 0`, `key == 0`.

Update override access from `c.key` to `c.id`:

```java
if (overrides.has(c.id)) {
    applied.add(applyOverride(c, overrides, width, height));
}
```

and preserve all fields in `applyOverride`:

```java
return new Control(c.id, c.key, c.turbo, c.label, c.shape,
        cx, cy, halfWidth, halfHeight);
```

- [ ] **Step 4: Append macro geometry through one overload**

Keep all existing overloads source-compatible and delegate them with
`MacroControls.EMPTY`. Add:

```java
static ControlLayout of(float width, float height, ControlOverrides overrides,
        float srcW, float srcH, boolean hasShoulders, MacroControls macros) {
    ControlLayout base = width > height
            ? landscape(width, height, srcW, srcH, hasShoulders)
            : portrait(width, height, srcW, srcH, hasShoulders);
    List<Control> controls = new ArrayList<>(base.controls);
    float unit = Math.min(width, height);
    for (MacroControls.Macro macro : macros.values()) {
        controls.add(new Control(macro.layoutId(), macro.keyMask, macro.turbo,
                macro.shortLabel(), Shape.PILL,
                width / 2f, height / 2f, unit * 0.13f, unit * 0.055f));
    }
    ControlLayout withMacros = new ControlLayout(base.gameLeft, base.gameTop,
            base.gameRight, base.gameBottom, base.menuLeft, base.menuTop,
            base.menuRight, base.menuBottom, base.muteLeft, base.muteTop,
            base.muteRight, base.muteBottom, controls);
    return applyOverrides(withMacros, overrides, width, height);
}
```

Extract the current override loop into `applyOverrides(...)`; return the input
layout unchanged when overrides are null/empty. Existing no-macro overloads must
continue returning identical stock geometry.

- [ ] **Step 5: Add one-pass input channels**

Add inside `ControlLayout`:

```java
static final class Input {
    final int normalKeys;
    final int turboKeys;
    Input(int normalKeys, int turboKeys) {
        this.normalKeys = normalKeys;
        this.turboKeys = turboKeys;
    }
}

Input inputAt(float x, float y) {
    int normal = 0;
    int turbo = 0;
    for (Control control : controls) {
        int hit = 0;
        if (control.shape == Shape.DPAD && control.contains(x, y)) {
            float dx = x - control.cx;
            float dy = y - control.cy;
            float dead = control.halfWidth * 0.18f;
            if (dx < -dead) hit |= MgbaSession.KEY_LEFT;
            if (dx > dead) hit |= MgbaSession.KEY_RIGHT;
            if (dy < -dead) hit |= MgbaSession.KEY_UP;
            if (dy > dead) hit |= MgbaSession.KEY_DOWN;
        } else if (control.shape != Shape.DPAD && control.contains(x, y)) {
            hit = control.key;
        }
        if (control.turbo) turbo |= hit;
        else normal |= hit;
    }
    return new Input(normal, turbo);
}

int keysAt(float x, float y) {
    Input input = inputAt(x, y);
    return input.normalKeys | input.turboKeys;
}
```

The compatibility `keysAt` keeps existing geometry tests meaningful; runtime
will use the separate channels in Task 4.

- [ ] **Step 6: Run all ControlLayout tests**

```bash
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest \
  --tests com.trebuchetdynamics.emulator.app.ControlLayoutTest
```

Expected: all old stock-layout tests and both macro tests pass.

- [ ] **Step 7: Commit only in a clean isolated worktree**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlLayout.java \
  mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlLayoutTest.java
git commit -m "feat(android): add macro control geometry"
```

In the current dirty worktree, skip this command.

---

### Task 3: Per-Orientation Persistence and Atomic Editor Save

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java`

**Interfaces:**
- Consumes: `MacroControls.parse(String)` and `serialize()`.
- Produces: `macroControls(boolean landscape)` and `setControlLayout(boolean, ControlOverrides, MacroControls, int)`.

- [ ] **Step 1: Add preference keys and read API**

Add constants beside the two existing layout keys:

```java
private static final String K_MACROS_PORTRAIT = "macroControlsPortrait";
private static final String K_MACROS_LANDSCAPE = "macroControlsLandscape";
```

Add:

```java
MacroControls macroControls(boolean landscape) {
    return MacroControls.parse(prefs.getString(
            landscape ? K_MACROS_LANDSCAPE : K_MACROS_PORTRAIT, ""));
}
```

- [ ] **Step 2: Add one transaction for editor state**

Add:

```java
void setControlLayout(boolean landscape, ControlOverrides overrides,
        MacroControls macros, int opacityPercent) {
    prefs.edit()
            .putString(landscape ? K_LAYOUT_LANDSCAPE : K_LAYOUT_PORTRAIT,
                    overrides.serialize())
            .putString(landscape ? K_MACROS_LANDSCAPE : K_MACROS_PORTRAIT,
                    macros.serialize())
            .putInt(K_OPACITY, Math.max(10, clampPercent(opacityPercent)))
            .apply();
}
```

Keep `setControlOverrides(...)` for compatibility until all current callers are
migrated. Do not change existing preference names or defaults.

- [ ] **Step 3: Compile the app**

```bash
tools/android_project/gradlew -p mgba-android :app:compileDebugJavaWithJavac
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit only in a clean isolated worktree**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java
git commit -m "feat(android): persist macro layouts per orientation"
```

In the current dirty worktree, skip this command.

---

### Task 4: Turbo Cadence and Play-Screen Input

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FeelMath.java`
- Modify: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FeelMathTest.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`

**Interfaces:**
- Consumes: `ControlLayout.Input`, saved portrait/landscape `MacroControls`.
- Produces: `FeelMath.applyTurbo(int normalKeys, int turboKeys, long frameIndex)`, `EmulatorView.keysForFrame(long)`, and `setControlLayouts(...)`.

- [ ] **Step 1: Write failing Turbo cadence tests**

Append to `FeelMathTest`:

```java
@Test public void turboPulsesWholeCombinationTwoFramesOnTwoFramesOff() {
    int combo = 1 | 32;
    assertEquals(combo, FeelMath.applyTurbo(0, combo, 0));
    assertEquals(combo, FeelMath.applyTurbo(0, combo, 1));
    assertEquals(0, FeelMath.applyTurbo(0, combo, 2));
    assertEquals(0, FeelMath.applyTurbo(0, combo, 3));
    assertEquals(combo, FeelMath.applyTurbo(0, combo, 4));
}

@Test public void normalKeysRemainHeldWhileTurboPulses() {
    int normal = 2;
    int turbo = 1 | 32;
    assertEquals(normal | turbo, FeelMath.applyTurbo(normal, turbo, 0));
    assertEquals(normal, FeelMath.applyTurbo(normal, turbo, 2));
}
```

- [ ] **Step 2: Run cadence tests and verify RED**

```bash
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest \
  --tests com.trebuchetdynamics.emulator.app.FeelMathTest.turboPulsesWholeCombinationTwoFramesOnTwoFramesOff \
  --tests com.trebuchetdynamics.emulator.app.FeelMathTest.normalKeysRemainHeldWhileTurboPulses
```

Expected: compilation fails because `applyTurbo` does not exist.

- [ ] **Step 3: Implement fixed cadence**

Add to `FeelMath`:

```java
static int applyTurbo(int normalKeys, int turboKeys, long frameIndex) {
    return frameIndex % 4 < 2 ? normalKeys | turboKeys : normalKeys;
}
```

Run the cadence tests again; expected: PASS.

- [ ] **Step 4: Load both orientation layouts into EmulatorView**

Add fields:

```java
private MacroControls portraitMacros = MacroControls.EMPTY;
private MacroControls landscapeMacros = MacroControls.EMPTY;
private volatile int touchTurboKeys;
```

Replace `setControlOverrides(...)` with a source-compatible delegating method
and one complete setter:

```java
void setControlOverrides(ControlOverrides portrait, ControlOverrides landscape) {
    setControlLayouts(portrait, portraitMacros, landscape, landscapeMacros);
}

void setControlLayouts(ControlOverrides portrait, MacroControls portraitMacros,
        ControlOverrides landscape, MacroControls landscapeMacros) {
    this.portraitOverrides = portrait == null ? ControlOverrides.EMPTY : portrait;
    this.landscapeOverrides = landscape == null ? ControlOverrides.EMPTY : landscape;
    this.portraitMacros = portraitMacros == null ? MacroControls.EMPTY : portraitMacros;
    this.landscapeMacros = landscapeMacros == null ? MacroControls.EMPTY : landscapeMacros;
    invalidate();
}
```

Add `activeMacros(w, h)` and pass it to the Task 2 `ControlLayout.of` overload in
`onSizeChanged` and `onDraw`.

- [ ] **Step 5: Collect normal and Turbo touch channels**

In `onTouchEvent`, replace the single local key accumulator with:

```java
int normalKeys = 0;
int turboKeys = 0;
if (!touchControlsHidden
        && event.getActionMasked() != MotionEvent.ACTION_UP
        && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
    for (int i = 0; i < event.getPointerCount(); i++) {
        ControlLayout.Input input = layout.inputAt(event.getX(i), event.getY(i));
        normalKeys |= input.normalKeys;
        turboKeys |= input.turboKeys;
    }
}
int allTouchKeys = normalKeys | turboKeys;
if (!touchControlsHidden && hapticsEnabled
        && FeelMath.introducesNewPress(previousTouchKeys, allTouchKeys)) {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
}
previousTouchKeys = allTouchKeys;
touchKeys = normalKeys;
touchTurboKeys = turboKeys;
```

Clear `touchTurboKeys` anywhere `touchKeys` is cleared, including Action Up,
Cancel/hidden-control transitions, and controller-only mode.

- [ ] **Step 6: Resolve frame input and pressed visuals**

Add:

```java
private volatile int resolvedFrameKeys;

int keysForFrame(long frameIndex) {
    int resolved = FeelMath.applyTurbo(
            touchKeys | hardwareKeys, touchTurboKeys, frameIndex);
    resolvedFrameKeys = resolved;
    return resolved;
}

private int visualKeys() {
    return resolvedFrameKeys;
}
```

Replace drawing-time `keys()` calls with `visualKeys()`. A macro pill is pressed
only when its complete mask is active:

```java
boolean pressed = (visualKeys() & control.key) == control.key;
```

This makes Turbo controls visibly follow the same two-on/two-off cadence while
preventing a combination such as `Left + A` from lighting when only A is held.
Stock single-key controls retain their existing pressed behavior.

- [ ] **Step 7: Pass frame index from the runner**

In `EmulationRunner.run`, replace:

```java
int audioFrames = session.runFrame(view.keys(), pixels, audio);
```

with:

```java
int audioFrames = session.runFrame(view.keysForFrame(frameIndex), pixels, audio);
```

No native signature changes are permitted.

- [ ] **Step 8: Refresh saved macros in MainActivity**

Replace both play-layout refresh sites (`onResume` and `closeLayoutEditor`) with:

```java
emulatorView.setControlLayouts(
        settings.controlOverrides(false), settings.macroControls(false),
        settings.controlOverrides(true), settings.macroControls(true));
```

- [ ] **Step 9: Run focused and full unit tests**

```bash
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest \
  --tests com.trebuchetdynamics.emulator.app.FeelMathTest \
  --tests com.trebuchetdynamics.emulator.app.ControlLayoutTest
```

Expected: all cadence, stock-control, and macro channel tests pass.

- [ ] **Step 10: Commit only in a clean isolated worktree**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FeelMath.java \
  mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FeelMathTest.java \
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java \
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java \
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java
git commit -m "feat(android): run touch macro buttons"
```

In the current dirty worktree, skip this command.

---

### Task 5: Smart Add/Delete Layout Editor

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LayoutEditorView.java`
- Modify: `mgba-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MacroControls` working copy, macro-aware `ControlLayout.of`, and `Settings.setControlLayout`.
- Produces: Add dialog, macro selection, macro deletion, reset, cancel, and saved editor flow.

- [ ] **Step 1: Add exact user-facing strings**

Add to `strings.xml`:

```xml
<string name="layout_add_macro">Add</string>
<string name="layout_add_macro_description">Add custom button</string>
<string name="layout_delete_macro">Delete</string>
<string name="layout_delete_macro_description">Delete selected custom button</string>
<string name="layout_macro_title">Add custom button</string>
<string name="layout_macro_turbo">Turbo · 15 presses/sec</string>
<string name="layout_macro_add">Add</string>
<string name="layout_macro_empty">Choose at least one input</string>
<string name="layout_macro_duplicate">That custom button already exists</string>
<string name="layout_macro_limit">Maximum 8 custom buttons in this orientation</string>
<string name="input_left">Left</string>
<string name="input_up">Up</string>
<string name="input_right">Right</string>
<string name="input_down">Down</string>
<string name="input_select">Select</string>
<string name="input_start">Start</string>
```

A, B, L, and R remain literal labels; every dialog row is exposed by the
multiple-choice list as readable text.

- [ ] **Step 2: Add working state and contextual actions**

Add fields:

```java
private MacroControls workingMacros = new MacroControls();
private final Button addButton;
private final Button deleteButton;
private int selectedControlId;
```

Remove the old `selectedKey` field and replace every editor reference with
`selectedControlId`; it now represents layout identity, while `Control.key`
continues to represent emitted mGBA bits.

Convert the contextual strip into a horizontal row: status uses weight `1f`,
then compact textual Add and Delete buttons:

```java
LinearLayout contextRow = new LinearLayout(context);
contextRow.setGravity(Gravity.CENTER_VERTICAL);
contextRow.setBackgroundColor(0xFA191C22);
status.setLayoutParams(new LinearLayout.LayoutParams(
        0, LayoutParams.WRAP_CONTENT, 1f));
contextRow.addView(status);
addButton = contextButton(R.string.layout_add_macro,
        R.string.layout_add_macro_description, v -> showAddMacroDialog());
deleteButton = contextButton(R.string.layout_delete_macro,
        R.string.layout_delete_macro_description, v -> deleteSelectedMacro());
deleteButton.setEnabled(false);
contextRow.addView(addButton);
contextRow.addView(deleteButton);
```

Add this compact button helper:

```java
private Button contextButton(int text, int description,
        View.OnClickListener listener) {
    Button button = new Button(getContext());
    button.setText(text);
    button.setAllCaps(false);
    button.setTextColor(Color.WHITE);
    button.setMinHeight(dp(48));
    button.setPadding(dp(12), 0, dp(12), 0);
    button.setBackgroundResource(android.R.drawable.list_selector_background);
    button.setOnClickListener(listener);
    button.setContentDescription(getContext().getString(description));
    return button;
}
```

Put `contextRow`, not the standalone status view, above the existing six-button
toolbar. Delete starts disabled.

- [ ] **Step 3: Seed and save one complete working transaction**

In `begin`:

```java
workingMacros = settings.macroControls(landscape).copy();
addButton.setEnabled(!workingMacros.isFull());
deleteButton.setEnabled(false);
```

In the checkmark action replace `setControlOverrides` with:

```java
settings.setControlLayout(landscape, working, workingMacros, workingOpacity);
callback.onLayoutEditorClosed();
```

The Back path already discards fields by closing without either setter.

- [ ] **Step 4: Build the focused Add dialog**

Implement `showAddMacroDialog()` with a real multiple-choice `ListView` so the
Turbo checkbox and inline error can share one compact dialog:

```java
private void showAddMacroDialog() {
    if (workingMacros.isFull()) {
        status.setText(R.string.layout_macro_limit);
        status.announceForAccessibility(status.getText());
        return;
    }
    int[] keys = { MgbaSession.KEY_LEFT, MgbaSession.KEY_UP, MgbaSession.KEY_RIGHT,
            MgbaSession.KEY_DOWN, MgbaSession.KEY_A, MgbaSession.KEY_B,
            MgbaSession.KEY_L, MgbaSession.KEY_R, MgbaSession.KEY_SELECT,
            MgbaSession.KEY_START };
    String[] labels = { getContext().getString(R.string.input_left),
            getContext().getString(R.string.input_up),
            getContext().getString(R.string.input_right),
            getContext().getString(R.string.input_down), "A", "B", "L", "R",
            getContext().getString(R.string.input_select),
            getContext().getString(R.string.input_start) };

    ListView choices = new ListView(getContext());
    choices.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
    choices.setAdapter(new ArrayAdapter<>(getContext(),
            android.R.layout.simple_list_item_multiple_choice, labels));
    CheckBox turbo = new CheckBox(getContext());
    turbo.setText(R.string.layout_macro_turbo);
    TextView error = new TextView(getContext());
    error.setTextColor(0xFFFF8A80);
    error.setVisibility(View.GONE);
    error.setPadding(dp(16), dp(8), dp(16), dp(8));

    LinearLayout content = new LinearLayout(getContext());
    content.setOrientation(LinearLayout.VERTICAL);
    content.addView(choices, new LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, dp(220)));
    content.addView(turbo);
    content.addView(error);

    AlertDialog dialog = new AlertDialog.Builder(getContext())
            .setTitle(R.string.layout_macro_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.layout_macro_add, null)
            .create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                int mask = 0;
                for (int i = 0; i < keys.length; i++) {
                    if (choices.isItemChecked(i)) mask |= keys[i];
                }
                if (mask == 0) {
                    showMacroError(error, R.string.layout_macro_empty);
                    return;
                }
                if (workingMacros.containsDefinition(mask, turbo.isChecked())) {
                    showMacroError(error, R.string.layout_macro_duplicate);
                    return;
                }
                MacroControls.Macro macro = workingMacros.add(mask, turbo.isChecked());
                selectedControlId = macro.layoutId();
                selectedLabel = macro.contentLabel();
                selNormCx = 0.5f;
                selNormCy = 0.5f;
                selScale = 1f;
                working.put(selectedControlId, selNormCx, selNormCy, selScale);
                hasSelection = true;
                setSizeActionsEnabled(true);
                deleteButton.setEnabled(true);
                addButton.setEnabled(!workingMacros.isFull());
                updateStatus();
                surface.invalidate();
                status.announceForAccessibility(status.getText());
                dialog.dismiss();
            }));
    dialog.show();
}

private void showMacroError(TextView error, int message) {
    error.setText(message);
    error.setVisibility(View.VISIBLE);
    error.announceForAccessibility(error.getText());
}
```

Import `AlertDialog`, `ArrayAdapter`, `CheckBox`, and `ListView`. The bounded
`dp(220)` list remains scrollable in landscape instead of expanding under system
bars.

- [ ] **Step 5: Make editor geometry macro-aware**

In `Surface.onDraw`, touch selection, and `pick`, replace each
`ControlLayout.of(w, h, working)` call with:

```java
ControlLayout.of(w, h, working, MgbaSession.VIDEO_WIDTH,
        MgbaSession.VIDEO_HEIGHT, true, workingMacros)
```

Selection must use `c.id`, not `c.key`:

```java
selectedControlId = hit.id;
MacroControls.Macro selectedMacro = workingMacros.macroForLayoutId(hit.id);
selectedLabel = selectedMacro == null
        ? (hit.shape == ControlLayout.Shape.DPAD ? "D-pad" : hit.label)
        : selectedMacro.contentLabel();
deleteButton.setEnabled(selectedMacro != null);
```

The editor preview is geometry-only; using GBA dimensions/shoulders matches its
existing stock preview. MainActivity still hides the live touch controls while
editing.

- [ ] **Step 6: Implement deletion and reset**

Delete action:

```java
private void deleteSelectedMacro() {
    if (!hasSelection || !MacroControls.isMacroLayoutId(selectedControlId)) return;
    workingMacros.removeLayoutId(selectedControlId);
    working.remove(selectedControlId);
    hasSelection = false;
    setSizeActionsEnabled(false);
    deleteButton.setEnabled(false);
    addButton.setEnabled(true);
    updateStatus();
    surface.invalidate();
}
```

Add the minimal removal API to `ControlOverrides`:

```java
void remove(int key) {
    map.remove(key);
}
```

Append this JVM test to `ControlOverridesTest`:

```java
@Test public void removeDeletesOnlyNamedOverride() {
    ControlOverrides overrides = new ControlOverrides();
    overrides.put(1, 0.2f, 0.3f, 1f);
    overrides.put(2, 0.4f, 0.5f, 1f);
    overrides.remove(1);
    assertFalse(overrides.has(1));
    assertTrue(overrides.has(2));
}
```

Reset action must execute:

```java
working.clear();
workingMacros.clear();
hasSelection = false;
setSizeActionsEnabled(false);
deleteButton.setEnabled(false);
addButton.setEnabled(true);
updateStatus();
surface.invalidate();
```

Stock selection always leaves Delete disabled.

- [ ] **Step 7: Extend status and accessibility feedback**

When a macro is selected, status uses its full content label and existing
size/opacity values. Add and Delete have 48dp touch targets and meaningful
content descriptions. Newly added macro selection announces, for example,
`Turbo Left plus A, size 100 percent, opacity 24 percent`.

- [ ] **Step 8: Run unit, lint, and assemble checks**

```bash
tools/android_project/gradlew -p mgba-android \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Expected: all tests pass, lint writes no errors, and debug APK assembles.

- [ ] **Step 9: Commit only in a clean isolated worktree**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LayoutEditorView.java \
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlOverrides.java \
  mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlOverridesTest.java \
  mgba-android/app/src/main/res/values/strings.xml
git commit -m "feat(android): edit custom macro buttons"
```

In the current dirty worktree, skip this command.

---

### Task 6: Documentation and Device Acceptance

**Files:**
- Modify: `docs/design/pizzaboy-live/play-store-listing-study.md`

**Interfaces:**
- Consumes: complete macro-button feature.
- Produces: accurate capability statement and verification receipts.

- [ ] **Step 1: Update the competitor-study implementation record**

Change the controls row to state that Garnacha supports arbitrary per-orientation
touch macro buttons and fixed whole-combination Turbo. Explicitly retain these
limits: eight macros, touch only, no recorded sequences, no adjustable rate.
Do not claim Pizza Boy assets, skins, or trade dress.

- [ ] **Step 2: Run repository checks after final documentation edits**

```bash
git diff --check
tools/android_project/gradlew -p mgba-android \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Expected: no whitespace errors; all JVM tests pass; lint and assemble succeed.

- [ ] **Step 3: Run native regression checks**

Use the repository’s existing host and device paths rather than adding a macro
JNI test, because macros resolve to the existing key bitmask before JNI:

```bash
cmake -S mgba-android/smoke -B mgba-android/smoke/build \
  -DCMAKE_BUILD_TYPE=Debug -DENABLE_SANITIZERS=ON
cmake --build mgba-android/smoke/build
ctest --test-dir mgba-android/smoke/build --output-on-failure

tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
```

Expected: host smoke suite and connected core instrumentation pass unchanged.

- [ ] **Step 4: Complete the two-orientation device walkthrough**

Install:

```bash
adb install -r mgba-android/app/build/outputs/apk/debug/app-debug.apk
```

Verify in portrait and landscape:

1. Open editor; Add is enabled and Delete is disabled.
2. Add `Left + A`; drag and resize it; save; confirm both bits activate together.
3. Add Turbo B; hold it; confirm the button visibly pulses and gameplay receives
   two frames on/two frames off.
4. Hold a normal direction while Turbo B is held; direction remains continuous.
5. Reopen editor; confirm labels, positions, sizes, and orientation independence.
6. Select a stock button; Delete remains disabled.
7. Delete a macro, then Back; confirm deletion was cancelled.
8. Delete and save; confirm it disappears from play.
9. Add eight macros; confirm Add reports the limit and no ninth macro is created.
10. Reset and Back; confirm saved macros return. Reset and save; confirm stock-only
    layout for that orientation.
11. Enable controller-only mode with a connected gamepad; confirm all touch macros
    hide with the stock touch controls.
12. Restart the app; confirm saved macros and Turbo definitions persist.

Capture one portrait and one landscape editor screenshot plus one active-play
screenshot showing a macro. Store them under a new dated folder in
`docs/design/` only if the user wants lasting evidence; otherwise keep temporary
captures outside the repository.

- [ ] **Step 5: Commit only in a clean isolated worktree**

```bash
git add docs/design/pizzaboy-live/play-store-listing-study.md
git commit -m "docs: record custom macro controls"
```

In the current dirty worktree, skip this command.
