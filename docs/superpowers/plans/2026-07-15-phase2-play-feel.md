# Phase 2: Disappear-the-Chrome Play Feel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make play feel premium — crisp integer-scaled pixels, on-screen controls that fade when idle and re-assert on touch (the game reads edge-to-edge), and haptic feedback on presses — shipping as v0.3.

**Architecture:** All new decision logic (fade alpha over time, integer-scaled draw rect, new-press detection) lives in a pure, android-free `FeelMath` class that unit-tests on the JVM (the codebase pattern used by `ControlLayout`, `SaveStateStore`, `FrameStats`). `EmulatorView` consumes those helpers in its existing Canvas `onDraw`/`onTouchEvent` loop: it draws the game bitmap with nearest-neighbor filtering into an integer-scaled, letterboxed rect; it tracks a last-input timestamp and applies a time-based alpha to the controls and chips while a game is running; and it fires a light haptic when a touch introduces a new key press. No new threads, no new geometry — control positions stay single-sourced in `ControlLayout`.

**Tech Stack:** Android (Java, minSdk 24, targetSdk 35), Canvas 2D drawing, `View.performHapticFeedback`, JUnit 4 (JVM unit tests), Gradle via `tools/android_project/gradlew`.

## Global Constraints

- All on-screen control/chip geometry stays ONLY in `ControlLayout`; this phase changes how things are *drawn* (alpha, scale), never *where* — no positions move.
- `FeelMath` must have NO `android.*` imports so it unit-tests on the JVM.
- Fade applies only while a game is running (`hasFrame == true`); on the home/empty screen the controls and chips stay fully opaque (nothing to watch fade against).
- Haptics use `View.performHapticFeedback` (no `VIBRATE` permission, no new manifest entry).
- minSdk 24; zero Android lint errors (`lintDebug`).
- Work on branch `mvp`. End commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Tunable defaults (chosen here; adjustable later without structural change)

- Idle hold before fade begins: `HOLD_MS = 1500`.
- Fade duration: `FADE_MS = 500`.
- Idle (faded) alpha: `MIN_ALPHA = 60` (~24% — controls stay faintly visible so they remain findable).
- Active alpha: `255`.

## File Structure

| File | Responsibility |
|---|---|
| `.../app/FeelMath.java` (create) | pure helpers: `integerScale`, `controlAlpha`, `introducesNewPress` |
| `.../app/FeelMathTest.java` (create) | JVM tests for the above |
| `.../app/EmulatorView.java` (modify) | nearest-neighbor + integer-scaled bitmap draw; idle-fade alpha on controls/chips; haptic on new press |

Package for all files: `com.trebuchetdynamics.emulator.app`.

---

### Task 1: FeelMath — the pure decision helpers

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FeelMath.java`
- Test: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FeelMathTest.java`

**Interfaces:**
- Produces:
  - `FeelMath.Box` — immutable `{float left, top, right, bottom}` value holder (android-free).
  - `static Box integerScale(float boxLeft, float boxTop, float boxRight, float boxBottom, int srcW, int srcH)` — largest integer multiple of `srcW×srcH` centered within the box (min scale 1).
  - `static int controlAlpha(long nowMs, long lastInputMs, int holdMs, int fadeMs, int minAlpha, int maxAlpha)` — `maxAlpha` for `holdMs` after input, then linear fade to `minAlpha` over `fadeMs`.
  - `static boolean introducesNewPress(int previousKeys, int currentKeys)` — true iff `currentKeys` sets a bit not in `previousKeys`.
  Task 2 consumes `integerScale`/`Box`; Task 3 consumes `controlAlpha`; Task 4 consumes `introducesNewPress`.

- [ ] **Step 1: Write the failing tests**

Create `FeelMathTest.java`:
```java
package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeelMathTest {
    private static final int W = 240;
    private static final int H = 160;

    @Test
    public void exactMultipleFillsTheBox() {
        FeelMath.Box b = FeelMath.integerScale(0, 0, 1200, 800, W, H); // exactly 5x
        assertEquals(0f, b.left, 0.001f);
        assertEquals(0f, b.top, 0.001f);
        assertEquals(1200f, b.right, 0.001f);
        assertEquals(800f, b.bottom, 0.001f);
    }

    @Test
    public void nonIntegerBoxSnapsDownAndCentres() {
        // box 1200x840 at origin (10,20): min(1200/240,840/160)=5.0 -> 5x = 1200x800,
        // centred: x unchanged (1200==1200), y padded (840-800)/2 = 20.
        FeelMath.Box b = FeelMath.integerScale(10, 20, 1210, 860, W, H);
        assertEquals(10f, b.left, 0.001f);
        assertEquals(40f, b.top, 0.001f);
        assertEquals(1210f, b.right, 0.001f);
        assertEquals(840f, b.bottom, 0.001f);
    }

    @Test
    public void boxSmallerThanNativeStaysAtScaleOne() {
        FeelMath.Box b = FeelMath.integerScale(0, 0, 100, 100, W, H);
        assertEquals(240f, b.right - b.left, 0.001f);
        assertEquals(160f, b.bottom - b.top, 0.001f);
    }

    @Test
    public void alphaIsFullDuringHold() {
        assertEquals(255, FeelMath.controlAlpha(1000, 1000, 1500, 500, 60, 255));
        assertEquals(255, FeelMath.controlAlpha(2500, 1000, 1500, 500, 60, 255)); // exactly at hold end
    }

    @Test
    public void alphaInterpolatesDuringFade() {
        // elapsed 1750 -> 250 into the 500ms fade -> 50% -> 255 - 0.5*195 = 157.5 -> round 158
        assertEquals(158, FeelMath.controlAlpha(2750, 1000, 1500, 500, 60, 255));
    }

    @Test
    public void alphaBottomsOutAtMin() {
        assertEquals(60, FeelMath.controlAlpha(3000, 1000, 1500, 500, 60, 255)); // hold+fade
        assertEquals(60, FeelMath.controlAlpha(9000, 1000, 1500, 500, 60, 255)); // long idle
    }

    @Test
    public void newPressDetection() {
        assertTrue(FeelMath.introducesNewPress(0, 1));      // press A
        assertFalse(FeelMath.introducesNewPress(1, 1));     // still A
        assertTrue(FeelMath.introducesNewPress(1, 3));      // add B
        assertFalse(FeelMath.introducesNewPress(3, 1));     // release B, no new press
        assertFalse(FeelMath.introducesNewPress(1, 0));     // release A
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run:
```sh
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*FeelMathTest'
```
Expected: FAIL — `FeelMath` does not exist.

- [ ] **Step 3: Implement FeelMath**

Create `FeelMath.java`:
```java
package com.trebuchetdynamics.emulator.app;

/**
 * Pure "feel" math for the play view: integer game scaling, idle-fade alpha, and
 * new-press detection. No android.* imports so it unit-tests on the JVM.
 */
final class FeelMath {
    private FeelMath() {
    }

    /** Immutable rectangle in view pixels. */
    static final class Box {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Box(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /**
     * The largest integer multiple of {@code srcW x srcH} that fits inside the
     * given box, centred. Never smaller than 1x (so a box smaller than native
     * still draws at native size, which our layout never produces in practice).
     */
    static Box integerScale(float boxLeft, float boxTop, float boxRight, float boxBottom,
                            int srcW, int srcH) {
        float boxW = boxRight - boxLeft;
        float boxH = boxBottom - boxTop;
        int scale = (int) Math.floor(Math.min(boxW / srcW, boxH / srcH));
        if (scale < 1) {
            scale = 1;
        }
        float drawW = (float) scale * srcW;
        float drawH = (float) scale * srcH;
        float left = boxLeft + (boxW - drawW) / 2f;
        float top = boxTop + (boxH - drawH) / 2f;
        return new Box(left, top, left + drawW, top + drawH);
    }

    /**
     * Alpha (0..255) for the on-screen controls: {@code maxAlpha} for {@code
     * holdMs} after the last input, then a linear fade to {@code minAlpha} over
     * {@code fadeMs}.
     */
    static int controlAlpha(long nowMs, long lastInputMs, int holdMs, int fadeMs,
                            int minAlpha, int maxAlpha) {
        long elapsed = nowMs - lastInputMs;
        if (elapsed <= holdMs) {
            return maxAlpha;
        }
        if (elapsed >= (long) holdMs + fadeMs) {
            return minAlpha;
        }
        float frac = (float) (elapsed - holdMs) / (float) fadeMs;
        return Math.round(maxAlpha - frac * (maxAlpha - minAlpha));
    }

    /** True iff {@code currentKeys} presses a key bit not already down in {@code previousKeys}. */
    static boolean introducesNewPress(int previousKeys, int currentKeys) {
        return (currentKeys & ~previousKeys) != 0;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run:
```sh
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*FeelMathTest'
```
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FeelMath.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FeelMathTest.java
git commit -m "feat(app): pure feel math — integer scale, idle-fade alpha, new-press

Android-free, JVM-tested helpers the play view will consume for crisp scaling,
control fade, and haptics.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Crisp integer-scaled, nearest-neighbor game rendering

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`

**Interfaces:**
- Consumes: `FeelMath.integerScale` / `FeelMath.Box` (Task 1).
- Produces: the game bitmap drawn crisp (no bilinear blur) at an exact integer scale, centred inside the allocated game rect with black letterbox bars filling the remainder.

- [ ] **Step 1: Force nearest-neighbor filtering**

In `EmulatorView`'s main constructor (after `setBackgroundColor(...)`, around line 40), add:
```java
        paint.setFilterBitmap(false); // crisp GBA pixels, no bilinear smoothing
```

- [ ] **Step 2: Draw the bitmap into an integer-scaled, letterboxed rect**

In `onDraw`, replace the existing game-rect fill + bitmap block (currently):
```java
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(gameRect, 14, 14, paint);
        if (hasFrame) {
            synchronized (frameLock) {
                canvas.drawBitmap(frame, null, gameRect, paint);
            }
        } else {
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(32, getWidth() * 0.045f));
            canvas.drawText(status, gameRect.centerX(), gameRect.centerY(), paint);
        }
```
with:
```java
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(gameRect, 14, 14, paint); // letterbox backdrop
        if (hasFrame) {
            FeelMath.Box draw = FeelMath.integerScale(
                    gameRect.left, gameRect.top, gameRect.right, gameRect.bottom,
                    MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT);
            frameDst.set(draw.left, draw.top, draw.right, draw.bottom);
            synchronized (frameLock) {
                canvas.drawBitmap(frame, null, frameDst, paint);
            }
        } else {
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(32, getWidth() * 0.045f));
            canvas.drawText(status, gameRect.centerX(), gameRect.centerY(), paint);
        }
```
Add a reusable `RectF` field next to `gameRect` (around line 19):
```java
    private final RectF frameDst = new RectF();
```

- [ ] **Step 3: Build, lint, unit tests**

Run:
```sh
tools/android_project/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all unit tests pass (37 from before + 7 new FeelMath = 44).

- [ ] **Step 4: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java
git commit -m "feat(app): crisp integer-scaled, nearest-neighbor game rendering

The frame is now drawn with filterBitmap disabled at an exact integer multiple
of 240x160, centred with black letterbox bars — sharp pixels, no bilinear blur.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Fade-when-idle controls and chips

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`

**Interfaces:**
- Consumes: `FeelMath.controlAlpha` (Task 1).
- Produces: on-screen controls and the LOAD/NOTICES/MENU chips fade to `MIN_ALPHA` after `HOLD_MS` idle while a game runs, and snap back to full opacity on any touch or hardware-key input. Since the running game invalidates the view every frame (`publishFrame` → `postInvalidateOnAnimation`), the fade animates for free with no extra invalidation.

- [ ] **Step 1: Add timing constants and last-input tracking**

In `EmulatorView`, add imports:
```java
import android.os.SystemClock;
```
Add constants and a field (near the other fields, ~line 24):
```java
    private static final int HOLD_MS = 1500;
    private static final int FADE_MS = 500;
    private static final int MIN_ALPHA = 60;

    private long lastInputMs = SystemClock.uptimeMillis();
```

- [ ] **Step 2: Reset the idle timer on input**

In `setHardwareKey` (so a gamepad re-asserts the overlay), add as the first line:
```java
        lastInputMs = SystemClock.uptimeMillis();
```
In `onTouchEvent`, add as the first line (any touch — press, move, or chip tap — counts as input):
```java
        lastInputMs = SystemClock.uptimeMillis();
```

- [ ] **Step 3: Compute alpha once per draw and thread it into the control/chip draws**

At the top of `onDraw` (after `gameRect.set(...)`), compute the alpha (full opacity unless a game is running):
```java
        int controlAlpha = hasFrame
                ? FeelMath.controlAlpha(SystemClock.uptimeMillis(), lastInputMs,
                        HOLD_MS, FADE_MS, MIN_ALPHA, 255)
                : 255;
```
Apply `controlAlpha` to each chip and control draw. For every chip block (LOAD, NOTICES, MENU), after `paint.setColor(0xCC262A31);` add `paint.setAlpha(chipAlpha(controlAlpha));`, and after the white-label `paint.setColor(Color.WHITE);` add `paint.setAlpha(controlAlpha);`. Define this helper method on the view:
```java
    // Chips are already ~80% opaque (0xCC); scale that base by the fade factor.
    private static int chipAlpha(int controlAlpha) {
        return 0xCC * controlAlpha / 255;
    }
```
Change the three draw helpers to take and apply the alpha:
- `drawDpad(Canvas, float cx, float cy, float radius, int alpha)` — after each `paint.setColor(...)`, call `paint.setAlpha(alpha)`.
- `drawButton(Canvas, float cx, float cy, float radius, String label, int key, int alpha)` — same.
- `drawPill(Canvas, ControlLayout.Control control, int alpha)` — same.
Update the `for` loop that dispatches to them to pass `controlAlpha`.

Because `setColor` resets alpha to the color's own alpha, `setAlpha` must be called AFTER each `setColor` in these methods for the fade to take effect.

- [ ] **Step 4: Build, lint, unit tests, install, and eyeball the fade**

Run:
```sh
tools/android_project/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all tests pass. The controller installs and confirms on-device (Task 5) that controls fade after ~2 s of no touch and snap back on touch while a game runs, and stay solid on the home screen.

- [ ] **Step 5: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java
git commit -m "feat(app): fade on-screen controls when idle during play

Controls and chips fade to ~24% after 1.5s of no input while a game runs and
snap back to full opacity on any touch or gamepad input, so the game reads
edge-to-edge. Full opacity on the home screen. Fade animates on the existing
per-frame invalidation — no extra work.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Haptic feedback on presses

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`

**Interfaces:**
- Consumes: `FeelMath.introducesNewPress` (Task 1).
- Produces: a light haptic tick when a touch introduces a new key press (not on every move, not on release).

- [ ] **Step 1: Add imports and previous-keys tracking**

In `EmulatorView`, add import:
```java
import android.view.HapticFeedbackConstants;
```
Add a field near `touchKeys` (~line 26):
```java
    private int previousTouchKeys;
```

- [ ] **Step 2: Fire a haptic on a newly pressed key**

In `onTouchEvent`, in the block that computes `keys` from the active pointers (just before `touchKeys = keys;`), add:
```java
        if (FeelMath.introducesNewPress(previousTouchKeys, keys)) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
        previousTouchKeys = keys;
```
And in the `ACTION_UP` branch, reset the tracker so the next touch's first press ticks: add `previousTouchKeys = 0;` next to the existing `touchKeys = 0;`.

- [ ] **Step 3: Build, lint, unit tests**

Run:
```sh
tools/android_project/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all tests pass.

- [ ] **Step 4: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java
git commit -m "feat(app): haptic tick on a newly pressed on-screen control

Fires performHapticFeedback only when a touch introduces a key bit not already
down — not on move or release. No VIBRATE permission needed.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Device verification

**Files:**
- Create: `docs/validation/phase2-play-feel-<date>.md` (use the actual execution date)

**Interfaces:**
- Consumes: everything above.
- Produces: the Phase 2 exit evidence.

- [ ] **Step 1: Install and observe on the device**

With the physical device connected and a ROM running:
```sh
adb install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
```
Confirm, capturing screenshots where visual:
1. **Crisp pixels:** the game image has hard pixel edges (no blur); compare a zoomed screenshot of text/sprites against the pre-Phase-2 look.
2. **Letterbox:** thin black bars appear where the integer scale doesn't exactly fill the game rect; the image is centred.
3. **Fade:** stop touching for ~2 s while a game runs — the controls and chips fade to faint; touch anywhere — they snap back to full.
4. **Home screen:** with no ROM loaded, controls/chips stay fully opaque (no fade).
5. **Haptic:** pressing an on-screen button gives a tick; sliding a finger across buttons does not re-tick per pixel; releasing gives no tick.

- [ ] **Step 2: Confirm no crashes and record**

```sh
adb logcat -d | grep -icE "FATAL|ANR in com.trebuchetdynamics.garnacha"
adb exec-out screencap -p > docs/validation/phase2-crisp-and-fade.png
```
Expected: 0 fatal/ANR.

- [ ] **Step 3: Write the receipt and commit**

Create `docs/validation/phase2-play-feel-<date>.md` recording device, each of the five observations above marked pass/fail with what was seen, the 0-fatal/ANR result, and the screenshot reference. Then:
```sh
git add docs/validation/phase2-play-feel-<date>.md docs/validation/phase2-crisp-and-fade.png
git commit -m "docs: record Phase 2 play-feel device verification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** the spec's Phase 2 lists "landscape controls become semi-transparent gutter overlays that fade when idle and re-assert on touch" (Task 3 — the fade applies in both orientations; the gutters are already where `ControlLayout` puts landscape controls), "portrait control opacity polish" (Task 3 — same fade path applies in portrait), "haptic feedback on presses" (Task 4), and "rendering crispness: correct letterboxing and integer/near-integer scaling" (Task 2).
- **Type consistency:** `FeelMath.Box{left,top,right,bottom}`, `integerScale(float×4,int,int)→Box`, `controlAlpha(long,long,int,int,int,int)→int`, `introducesNewPress(int,int)→boolean` are used identically in Tasks 2–4. The three `drawDpad/drawButton/drawPill` helpers gain a trailing `int alpha` parameter consistently.
- **Deliberately deferred:** a user-facing settings toggle for scaling mode / fade timing / haptics on-off (that's Phase 4 settings), shaders/filters (post-1.0), and any change to control positions (out of scope — geometry is frozen).
- **Risk:** the fade relies on the running game invalidating every frame; if emulation is paused (e.g. the in-game menu open) the view still redraws on menu interaction, and on the home screen fade is disabled — so there is no "stuck mid-fade" state.
