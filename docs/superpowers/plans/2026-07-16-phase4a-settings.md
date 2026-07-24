# Phase 4 Slice A: Settings Foundation + Toggles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a settings screen (reached from the library and the in-game menu) that lets the player control orientation lock, scaling mode, fast-forward speed, haptics, control opacity, and audio enable/volume — each wired live into the existing player.

**Architecture:** A thin `Settings` class wraps `SharedPreferences` with typed getters/setters and pure, JVM-tested clamp/enum helpers. A programmatic grouped `SettingsActivity` (no `androidx.preference` dependency — matching the codebase's build-Views-in-code pattern used by `LibraryActivity`/`NoticesActivity`) reads and writes it. The settings are applied by `MainActivity` on resume: it sets the requested orientation, pushes control settings (haptics, idle opacity, scale mode) into `EmulatorView`, and constructs `EmulationRunner` with a small playback config (fast-forward speed, audio enable, volume). Two pure helpers are added to already-tested classes: `FeelMath.fitScale` (aspect-fill, the non-integer scale option) and a fast-forward-speed parameter on `EmulationRunner.frameBudgetNanos`.

**Tech Stack:** Android (Java, minSdk 24, targetSdk 35), `SharedPreferences`, programmatic Views, `AudioTrack`, JUnit 4 (JVM), Gradle via `mgba-android/gradlew`.

## Global Constraints

- No new Gradle dependencies (no `androidx.preference`); the settings screen is built programmatically like `LibraryActivity`.
- Pure logic (`FeelMath.fitScale`, `frameBudgetNanos` with a speed argument, `Settings` clamp/enum helpers) must have NO `android.*` imports where it already doesn't, and must be JVM-unit-tested (the pattern of `FeelMath`, `ControlLayout`, `SaveStateStore`, `RomLibrary`).
- Control/chip geometry stays in `ControlLayout`; this slice changes how things are drawn/paced/heard from settings, never control positions.
- Settings apply live: returning from the settings screen to a running game reflects the new values (the player recreates its runner on resume and re-reads view settings then).
- Defaults must reproduce today's behavior exactly (integer scale, 4× fast-forward, haptics on, idle opacity ≈ current `MIN_ALPHA` 60/255, audio on at full volume, auto orientation).
- minSdk 24; zero Android lint errors (`lintDebug`).
- Out of scope (deliberate second slice): gamepad remapping, touch-layout drag-reposition, the Bluetooth-controller validation gate.
- Work on branch `mvp`. End commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Settings (keys, types, defaults, wiring)

| Setting | Group | Type / values | Default | Wires into |
|---|---|---|---|---|
| orientation | Video | AUTO / PORTRAIT / LANDSCAPE | AUTO | `MainActivity.setRequestedOrientation` |
| scaleMode | Video | INTEGER / FILL | INTEGER | `EmulatorView` draw (integerScale vs fitScale) |
| audioEnabled | Audio | boolean | true | `EmulationRunner` (skip AudioTrack) |
| audioVolumePercent | Audio | int 0..100 | 100 | `AudioTrack.setVolume` |
| haptics | Controls | boolean | true | `EmulatorView` (skip performHapticFeedback) |
| controlOpacityPercent | Controls | int 10..100 | 24 | `EmulatorView` idle alpha (FeelMath.controlAlpha minAlpha) |
| fastForwardSpeed | Emulation | int 2..4 | 4 | `EmulationRunner.frameBudgetNanos` divisor |

## File Structure

| File | Responsibility |
|---|---|
| `.../app/FeelMath.java` (modify) | add `fitScale` (aspect-fill scaling) |
| `.../app/FeelMathTest.java` (modify) | test `fitScale` |
| `.../app/Settings.java` (create) | SharedPreferences wrapper + pure clamp/enum helpers |
| `.../app/SettingsTest.java` (create) | JVM tests for the pure helpers |
| `.../app/SettingsActivity.java` (create) | programmatic grouped settings screen |
| `.../app/EmulationRunner.java` (modify) | `frameBudgetNanos(boolean, int)`; playback config (ff speed, audio enable, volume) |
| `.../app/EmulationRunnerTest.java` (modify) | test `frameBudgetNanos` with a speed |
| `.../app/EmulatorView.java` (modify) | settings setters: haptics, idle opacity, scale mode |
| `.../app/MainActivity.java` (modify) | apply settings on resume; open SettingsActivity from the menu |
| `.../app/InGameMenuView.java` (modify) | enable the Settings button, add `onSettings()` |
| `.../app/LibraryActivity.java` (modify) | a Settings button in the header |
| `.../app/src/main/AndroidManifest.xml` (modify) | register SettingsActivity |
| `.../app/src/main/res/values/strings.xml` (modify) | settings strings |

Package for all `.../app/` files: `com.trebuchetdynamics.emulator.app`.

---

### Task 1: Pure additions — aspect-fill scaling and speed-parameterized frame budget

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FeelMath.java`
- Modify: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FeelMathTest.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- Modify: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/EmulationRunnerTest.java`

**Interfaces:**
- Consumes: existing `FeelMath.Box`, `FeelMath.integerScale`, `EmulationRunner.frameBudgetNanos(boolean)`.
- Produces:
  - `static FeelMath.Box FeelMath.fitScale(float boxLeft, float boxTop, float boxRight, float boxBottom, int srcW, int srcH)` — largest aspect-preserving (non-integer) rect that fits the box, centred.
  - `static long EmulationRunner.frameBudgetNanos(boolean fastForward, int fastForwardSpeed)` — `FRAME_NANOS` normally, `FRAME_NANOS / fastForwardSpeed` when fast-forwarding. The existing 1-arg `frameBudgetNanos(boolean)` is removed (its only callers are updated in this task and the tests).
  Tasks 4 consumes both.

- [ ] **Step 1: Write the failing tests**

In `FeelMathTest.java`, add:
```java
    @Test
    public void fitScaleFillsAndPreservesAspect() {
        // box 1200x840 at origin, src 240x160 (3:2). Aspect-fit scale = min(1200/240, 840/160)
        // = min(5.0, 5.25) = 5.0 -> 1200x800, centred: top padded (840-800)/2 = 20.
        FeelMath.Box b = FeelMath.fitScale(0, 0, 1200, 840, 240, 160);
        assertEquals(0f, b.left, 0.001f);
        assertEquals(20f, b.top, 0.001f);
        assertEquals(1200f, b.right, 0.001f);
        assertEquals(820f, b.bottom, 0.001f);
    }

    @Test
    public void fitScaleIsNonIntegerWhenIntegerWouldWaste() {
        // box exactly 1260x840: fit scale 5.25 -> 1260x840 (fills), vs integerScale would give 5x=1200x800.
        FeelMath.Box b = FeelMath.fitScale(0, 0, 1260, 840, 240, 160);
        assertEquals(1260f, b.right - b.left, 0.001f);
        assertEquals(840f, b.bottom - b.top, 0.001f);
    }
```
In `EmulationRunnerTest.java`, replace the two existing `frameBudgetNanos` tests with the speed-parameterized ones:
```java
    @Test
    public void normalSpeedUsesTheFullFrameBudget() {
        assertEquals(FRAME_NANOS, EmulationRunner.frameBudgetNanos(false, 4));
    }

    @Test
    public void fastForwardDividesTheBudgetBySpeed() {
        assertEquals(FRAME_NANOS / 4, EmulationRunner.frameBudgetNanos(true, 4));
        assertEquals(FRAME_NANOS / 2, EmulationRunner.frameBudgetNanos(true, 2));
        assertEquals(FRAME_NANOS / 3, EmulationRunner.frameBudgetNanos(true, 3));
    }
```

- [ ] **Step 2: Run to verify failure**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*FeelMathTest' --tests '*EmulationRunnerTest'
```
Expected: FAIL — `fitScale` missing; `frameBudgetNanos(boolean,int)` missing.

- [ ] **Step 3: Implement `FeelMath.fitScale`**

In `FeelMath.java`, add after `integerScale`:
```java
    /**
     * The largest rectangle that preserves {@code srcW:srcH} aspect and fits
     * inside the box, centred. Unlike {@link #integerScale} the scale is not
     * floored to an integer, so it fills the box more fully at the cost of
     * uneven pixel sizes.
     */
    static Box fitScale(float boxLeft, float boxTop, float boxRight, float boxBottom,
                        int srcW, int srcH) {
        float boxW = boxRight - boxLeft;
        float boxH = boxBottom - boxTop;
        float scale = Math.min(boxW / srcW, boxH / srcH);
        float drawW = scale * srcW;
        float drawH = scale * srcH;
        float left = boxLeft + (boxW - drawW) / 2f;
        float top = boxTop + (boxH - drawH) / 2f;
        return new Box(left, top, left + drawW, top + drawH);
    }
```

- [ ] **Step 4: Parameterize `frameBudgetNanos` by speed**

In `EmulationRunner.java`:
- Delete the constant `private static final int FAST_FORWARD_SPEED = 4;` (line ~44).
- Replace `frameBudgetNanos(boolean)` (line ~107) with:
```java
    static long frameBudgetNanos(boolean fastForward, int fastForwardSpeed) {
        return fastForward ? FRAME_NANOS / fastForwardSpeed : FRAME_NANOS;
    }
```
- Update the loop's budget call (currently `long budget = frameBudgetNanos(ff);`) to `long budget = frameBudgetNanos(ff, 4);` — a literal 4 keeps current behavior; Task 4 makes this a configured field.

- [ ] **Step 5: Run to verify pass**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*FeelMathTest' --tests '*EmulationRunnerTest'
```
Expected: PASS.

- [ ] **Step 6: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FeelMath.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FeelMathTest.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/EmulationRunnerTest.java
git commit -m "feat(app): aspect-fill scaling and configurable fast-forward speed

FeelMath.fitScale is the non-integer scale option; frameBudgetNanos takes the
fast-forward multiplier as an argument so a setting can drive it. Defaults
(integer scale, 4x) are unchanged.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Settings store

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java`
- Test: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/SettingsTest.java`

**Interfaces:**
- Produces:
  - `enum Settings.Orientation { AUTO, PORTRAIT, LANDSCAPE }`, `enum Settings.ScaleMode { INTEGER, FILL }`
  - `Settings(Context context)`
  - typed getters/setters: `orientation()/setOrientation(Orientation)`, `scaleMode()/setScaleMode(ScaleMode)`, `audioEnabled()/setAudioEnabled(boolean)`, `audioVolumePercent()/setAudioVolumePercent(int)`, `haptics()/setHaptics(boolean)`, `controlOpacityPercent()/setControlOpacityPercent(int)`, `fastForwardSpeed()/setFastForwardSpeed(int)`
  - pure static helpers (JVM-tested): `static int clampPercent(int)`, `static int clampFastForwardSpeed(int)`, `static int opacityPercentToAlpha(int percent)`
  Tasks 3 and 4 consume these.

- [ ] **Step 1: Write the failing tests (pure helpers)**

Create `SettingsTest.java` (only the android-free static helpers are unit-tested; the SharedPreferences glue is device-verified in Task 5):
```java
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
}
```

- [ ] **Step 2: Run to verify failure**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*SettingsTest'
```
Expected: FAIL — `Settings` missing.

- [ ] **Step 3: Implement Settings**

Create `Settings.java`:
```java
package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed wrapper over the app's preferences, with pure clamp/enum helpers. */
final class Settings {
    enum Orientation { AUTO, PORTRAIT, LANDSCAPE }

    enum ScaleMode { INTEGER, FILL }

    private static final String FILE = "garnacha_settings";
    private static final String K_ORIENTATION = "orientation";
    private static final String K_SCALE = "scaleMode";
    private static final String K_AUDIO_ON = "audioEnabled";
    private static final String K_VOLUME = "audioVolumePercent";
    private static final String K_HAPTICS = "haptics";
    private static final String K_OPACITY = "controlOpacityPercent";
    private static final String K_FF_SPEED = "fastForwardSpeed";

    private final SharedPreferences prefs;

    Settings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    Orientation orientation() {
        return orientationFromOrdinal(prefs.getInt(K_ORIENTATION, Orientation.AUTO.ordinal()));
    }

    void setOrientation(Orientation value) {
        prefs.edit().putInt(K_ORIENTATION, value.ordinal()).apply();
    }

    ScaleMode scaleMode() {
        return scaleModeFromOrdinal(prefs.getInt(K_SCALE, ScaleMode.INTEGER.ordinal()));
    }

    void setScaleMode(ScaleMode value) {
        prefs.edit().putInt(K_SCALE, value.ordinal()).apply();
    }

    boolean audioEnabled() {
        return prefs.getBoolean(K_AUDIO_ON, true);
    }

    void setAudioEnabled(boolean value) {
        prefs.edit().putBoolean(K_AUDIO_ON, value).apply();
    }

    int audioVolumePercent() {
        return clampPercent(prefs.getInt(K_VOLUME, 100));
    }

    void setAudioVolumePercent(int value) {
        prefs.edit().putInt(K_VOLUME, clampPercent(value)).apply();
    }

    boolean haptics() {
        return prefs.getBoolean(K_HAPTICS, true);
    }

    void setHaptics(boolean value) {
        prefs.edit().putBoolean(K_HAPTICS, value).apply();
    }

    int controlOpacityPercent() {
        return Math.max(10, clampPercent(prefs.getInt(K_OPACITY, 24)));
    }

    void setControlOpacityPercent(int value) {
        prefs.edit().putInt(K_OPACITY, Math.max(10, clampPercent(value))).apply();
    }

    int fastForwardSpeed() {
        return clampFastForwardSpeed(prefs.getInt(K_FF_SPEED, 4));
    }

    void setFastForwardSpeed(int value) {
        prefs.edit().putInt(K_FF_SPEED, clampFastForwardSpeed(value)).apply();
    }

    static int clampPercent(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 100);
    }

    static int clampFastForwardSpeed(int value) {
        if (value < 2) {
            return 2;
        }
        return Math.min(value, 4);
    }

    static int opacityPercentToAlpha(int percent) {
        return Math.round(clampPercent(percent) / 100f * 255f);
    }

    private static Orientation orientationFromOrdinal(int ordinal) {
        Orientation[] values = Orientation.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Orientation.AUTO;
    }

    private static ScaleMode scaleModeFromOrdinal(int ordinal) {
        ScaleMode[] values = ScaleMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ScaleMode.INTEGER;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*SettingsTest'
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/SettingsTest.java
git commit -m "feat(app): typed Settings store over SharedPreferences

Video/audio/controls/emulation preferences with defaults reproducing current
behavior; pure clamp/enum helpers are JVM-tested.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: SettingsActivity — the settings screen, reachable and editable

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/SettingsActivity.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LibraryActivity.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/InGameMenuView.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`
- Modify: `mgba-android/app/src/main/AndroidManifest.xml`
- Modify: `mgba-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `Settings` (Task 2).
- Produces: a reachable, working settings screen that persists changes. Task 4 makes the running game react to those changes. `InGameMenuView.Listener` gains `void onSettings()`.

- [ ] **Step 1: Add settings strings**

In `res/values/strings.xml`, add inside `<resources>`:
```xml
    <string name="settings_title">Settings</string>
    <string name="settings_open">Settings</string>
    <string name="settings_group_video">Video</string>
    <string name="settings_group_audio">Audio</string>
    <string name="settings_group_controls">Controls</string>
    <string name="settings_group_emulation">Emulation</string>
    <string name="settings_orientation">Orientation</string>
    <string name="settings_orientation_auto">Automatic</string>
    <string name="settings_orientation_portrait">Portrait</string>
    <string name="settings_orientation_landscape">Landscape</string>
    <string name="settings_scale">Scaling</string>
    <string name="settings_scale_integer">Integer (crisp)</string>
    <string name="settings_scale_fill">Fill screen</string>
    <string name="settings_audio_enabled">Sound</string>
    <string name="settings_volume">Volume</string>
    <string name="settings_haptics">Vibrate on touch</string>
    <string name="settings_opacity">Control opacity when idle</string>
    <string name="settings_ff_speed">Fast-forward speed</string>
</resources>
```
(Replace the file's existing closing `</resources>` — do not add a second one.)

- [ ] **Step 2: Create SettingsActivity**

Create `SettingsActivity.java` — programmatic grouped rows: section headers, `Switch` for booleans, `SeekBar` for percents, and an `AlertDialog` single-choice for the enums. Every change writes through `Settings` immediately:
```java
package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    private Settings settings;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        setTitle(R.string.settings_title);
        getWindow().setStatusBarColor(0xFF0E1014);
        getWindow().setNavigationBarColor(0xFF0E1014);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xFF0E1014);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        content.addView(header(getString(R.string.settings_group_video)));
        content.addView(choiceRow(getString(R.string.settings_orientation),
                orientationLabel(), v -> pickOrientation()));
        content.addView(choiceRow(getString(R.string.settings_scale),
                scaleLabel(), v -> pickScale()));

        content.addView(header(getString(R.string.settings_group_audio)));
        content.addView(switchRow(getString(R.string.settings_audio_enabled),
                settings.audioEnabled(), (b, on) -> settings.setAudioEnabled(on)));
        content.addView(sliderRow(getString(R.string.settings_volume), 0, 100,
                settings.audioVolumePercent(), settings::setAudioVolumePercent));

        content.addView(header(getString(R.string.settings_group_controls)));
        content.addView(switchRow(getString(R.string.settings_haptics),
                settings.haptics(), (b, on) -> settings.setHaptics(on)));
        content.addView(sliderRow(getString(R.string.settings_opacity), 10, 100,
                settings.controlOpacityPercent(), settings::setControlOpacityPercent));

        content.addView(header(getString(R.string.settings_group_emulation)));
        content.addView(choiceRow(getString(R.string.settings_ff_speed),
                settings.fastForwardSpeed() + "×", v -> pickFastForward()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF7199DE);
        tv.setTextSize(14);
        tv.setPadding(0, dp(20), 0, dp(6));
        return tv;
    }

    private View switchRow(String label, boolean value,
                           android.widget.CompoundButton.OnCheckedChangeListener onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView tv = label(label);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(onChange);
        row.addView(sw);
        return row;
    }

    private interface IntConsumer { void accept(int value); }

    private View sliderRow(String label, int min, int max, int value, IntConsumer onChange) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(10), 0, dp(10));
        col.addView(label(label));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    onChange.accept(min + progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        col.addView(bar);
        return col;
    }

    private View choiceRow(String label, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setClickable(true);
        row.setOnClickListener(onClick);
        row.addView(label(label));
        TextView sub = new TextView(this);
        sub.setText(value);
        sub.setTextColor(0xFF9AA0AA);
        sub.setTextSize(13);
        sub.setTag("value");
        row.addView(sub);
        return row;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        return tv;
    }

    private String orientationLabel() {
        switch (settings.orientation()) {
            case PORTRAIT: return getString(R.string.settings_orientation_portrait);
            case LANDSCAPE: return getString(R.string.settings_orientation_landscape);
            default: return getString(R.string.settings_orientation_auto);
        }
    }

    private String scaleLabel() {
        return settings.scaleMode() == Settings.ScaleMode.FILL
                ? getString(R.string.settings_scale_fill)
                : getString(R.string.settings_scale_integer);
    }

    private void pickOrientation() {
        String[] labels = {
                getString(R.string.settings_orientation_auto),
                getString(R.string.settings_orientation_portrait),
                getString(R.string.settings_orientation_landscape) };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_orientation)
                .setSingleChoiceItems(labels, settings.orientation().ordinal(), (d, which) -> {
                    settings.setOrientation(Settings.Orientation.values()[which]);
                    d.dismiss();
                    recreate();
                })
                .show();
    }

    private void pickScale() {
        String[] labels = {
                getString(R.string.settings_scale_integer),
                getString(R.string.settings_scale_fill) };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_scale)
                .setSingleChoiceItems(labels, settings.scaleMode().ordinal(), (d, which) -> {
                    settings.setScaleMode(Settings.ScaleMode.values()[which]);
                    d.dismiss();
                    recreate();
                })
                .show();
    }

    private void pickFastForward() {
        String[] labels = {"2×", "3×", "4×"};
        int current = settings.fastForwardSpeed() - 2;
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_ff_speed)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    settings.setFastForwardSpeed(which + 2);
                    d.dismiss();
                    recreate();
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
```

- [ ] **Step 3: Register SettingsActivity in the manifest**

In `AndroidManifest.xml`, add alongside `NoticesActivity`:
```xml
        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:label="@string/settings_title" />
```

- [ ] **Step 4: Make it reachable from the library**

In `LibraryActivity.java`, add a Settings button to the header next to the Import button. In `onCreate` where the header is built (after the `importButton` is added to `header`), add:
```java
        android.widget.Button settingsButton = new android.widget.Button(this);
        settingsButton.setText(R.string.settings_open);
        settingsButton.setAllCaps(false);
        settingsButton.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, SettingsActivity.class)));
        header.addView(settingsButton);
```

- [ ] **Step 5: Make it reachable from the in-game menu**

In `InGameMenuView.java`:
- Add `void onSettings();` to the `Listener` interface.
- Replace the disabled Settings stub (the `settings.setEnabled(false)` block, lines ~46-48) with an enabled button:
```java
        addView(wideButton(context.getString(R.string.menu_settings), v -> listener.onSettings()));
```
(Remove the now-unused `Button settings = ...; settings.setEnabled(false);` lines. Also change the `menu_settings` string in `strings.xml` from "Settings (coming soon)" to "Settings".)

In `MainActivity.java`, the `InGameMenuView.Listener` anonymous implementation gains:
```java
            @Override public void onSettings() {
                startActivity(new android.content.Intent(MainActivity.this, SettingsActivity.class));
                closeMenu();
            }
```

- [ ] **Step 6: Build, lint, unit tests**

Run:
```sh
mgba-android/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all unit tests pass. The settings screen opens from the library and the in-game menu and persists changes (its effect on a running game is Task 4). `Switch` may raise a lint deprecation warning (use `SwitchCompat` only if the project already depends on appcompat — it does not — so `Switch` is correct here and any such warning is acceptable, not an error).

- [ ] **Step 7: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/SettingsActivity.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LibraryActivity.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/InGameMenuView.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java \
        mgba-android/app/src/main/AndroidManifest.xml \
        mgba-android/app/src/main/res/values/strings.xml
git commit -m "feat(app): settings screen reachable from library and in-game menu

Programmatic grouped SettingsActivity (video/audio/controls/emulation) that
reads and writes the Settings store; the in-game menu Settings stub is now live.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Apply the settings to the running player

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`

**Interfaces:**
- Consumes: `Settings` (Task 2), `FeelMath.fitScale` (Task 1), `EmulationRunner.frameBudgetNanos(boolean,int)` (Task 1).
- Produces: the running game reflects the settings on resume. No later task consumes this beyond device verification.

- [ ] **Step 1: EmulationRunner accepts a playback config**

In `EmulationRunner.java`:
- Add fields set from config:
```java
    private final boolean audioEnabled;
    private final float audioVolume; // 0f..1f
    private final int fastForwardSpeed;
```
- Add these constructor parameters to the existing constructor signature — add `boolean audioEnabled, float audioVolume, int fastForwardSpeed` as the last three parameters — and in the body:
```java
        this.audioEnabled = audioEnabled;
        this.audioVolume = audioVolume;
        this.fastForwardSpeed = fastForwardSpeed;
```
- Change the loop's budget call from `frameBudgetNanos(ff, 4)` (the literal from Task 1) to `frameBudgetNanos(ff, fastForwardSpeed)`.
- In `run()`, gate audio creation on the setting: wrap `audioTrack = createAudioTrack();` so it only runs when `audioEnabled`:
```java
            if (audioEnabled) {
                audioTrack = createAudioTrack();
            }
```
- In `createAudioTrack()`, before `return track;`, apply the volume:
```java
        track.setVolume(audioVolume);
```

- [ ] **Step 2: EmulatorView setters for haptics, opacity, scale mode**

In `EmulatorView.java`:
- Change `MIN_ALPHA` from a constant to a field with a setter, and add haptics + scale-mode fields:
```java
    private int minAlpha = 60;              // was MIN_ALPHA constant
    private boolean hapticsEnabled = true;
    private boolean integerScale = true;    // false = fill
```
Replace the `MIN_ALPHA` usage in the `controlAlpha` call with `minAlpha`.
- Add setters (called by MainActivity on resume):
```java
    void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
    }

    void setIdleOpacityAlpha(int alpha) {
        minAlpha = Math.max(0, Math.min(255, alpha));
        invalidate();
    }

    void setIntegerScale(boolean integer) {
        integerScale = integer;
        invalidate();
    }
```
- Gate the haptic on the flag: change the haptic block to
```java
        if (hapticsEnabled && FeelMath.introducesNewPress(previousTouchKeys, keys)) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
```
- Choose the scale in `onDraw`: replace the `FeelMath.integerScale(...)` call with
```java
            FeelMath.Box draw = integerScale
                    ? FeelMath.integerScale(gameRect.left, gameRect.top, gameRect.right,
                            gameRect.bottom, MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT)
                    : FeelMath.fitScale(gameRect.left, gameRect.top, gameRect.right,
                            gameRect.bottom, MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT);
```

- [ ] **Step 3: MainActivity applies settings on resume**

In `MainActivity.java`:
- Add a `Settings settings;` field; initialize it in `onCreate` (`settings = new Settings(this);`).
- In `onCreate`, before staging the ROM, apply orientation (so it takes effect immediately):
```java
        applyOrientation();
```
and add:
```java
    private void applyOrientation() {
        switch (settings.orientation()) {
            case PORTRAIT:
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case LANDSCAPE:
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            default:
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }
```
- In `onResume`, before `startRunner()`, push view settings and re-apply orientation (so returning from SettingsActivity updates a running game):
```java
        applyOrientation();
        emulatorView.setHapticsEnabled(settings.haptics());
        emulatorView.setIdleOpacityAlpha(Settings.opacityPercentToAlpha(settings.controlOpacityPercent()));
        emulatorView.setIntegerScale(settings.scaleMode() == Settings.ScaleMode.INTEGER);
```
- In `startRunner()`, pass the three new EmulationRunner args from settings (audio enable, volume 0..1, ff speed). At the `new EmulationRunner(...)` call, append:
```java
                settings.audioEnabled(),
                settings.audioVolumePercent() / 100f,
                settings.fastForwardSpeed()
```
as the last three constructor arguments (after the `StateListener`).

- [ ] **Step 4: Build, lint, unit tests, install**

Run:
```sh
mgba-android/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all unit tests pass. Defaults reproduce prior behavior; changing a setting and returning to the game applies it.

- [ ] **Step 5: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java
git commit -m "feat(app): apply settings to the running player

MainActivity re-applies orientation, control haptics/opacity/scale, and audio
enable/volume/fast-forward-speed on resume, so changes made in Settings take
effect when returning to a game.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Device verification

**Files:**
- Create: `docs/validation/phase4a-settings-<date>.md` (use the actual execution date)

**Interfaces:**
- Consumes: everything above.
- Produces: the Slice A exit evidence.

- [ ] **Step 1: Verify on a clean AVD**

The physical device's foreground contention has blocked prior verification; use a clean AVD as in Phase 3:
```sh
$ANDROID_HOME/emulator/emulator -avd game-emulator-mvp -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb -s emulator-5554 wait-for-device
# wait for sys.boot_completed == 1, then:
adb -s emulator-5554 install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
```
Import a ROM (push the MIT `mgba-android/core/src/androidTest/assets/hello.gba` to `/sdcard/Download` and pick it), then, capturing screenshots:
1. **Reachable:** open Settings from the library header; confirm the four groups (Video, Audio, Controls, Emulation) and all seven rows render.
2. **Orientation:** set Orientation → Landscape; confirm the player opens landscape; set back to Auto.
3. **Fast-forward speed:** set 2×; in game, toggle fast-forward from the menu; confirm the game runs ~2× (the `MgbaPerf` logcat `frames=` per 10 s window roughly doubles vs normal, not quadruples).
4. **Haptics off:** toggle off; confirm no code error (tactile effect is not observable on an AVD — verify only that pressing controls does not crash and the toggle persists).
5. **Opacity:** lower the idle opacity; in game, wait for the fade; confirm the idle controls are fainter than default.
6. **Scaling fill:** set Fill; confirm the game image fills the game area more fully (less/no letterbox); set back to Integer.
7. **Audio disabled:** toggle Sound off; confirm no crash and playback continues silently.
8. **Persistence:** leave Settings and return; confirm every choice persisted.

- [ ] **Step 2: Confirm no crashes and record**

```sh
adb -s emulator-5554 logcat -d | grep -icE "FATAL|ANR in com.trebuchetdynamics.garnacha"
adb -s emulator-5554 exec-out screencap -p > docs/validation/phase4a-settings.png
adb -s emulator-5554 emu kill
```
Expected: 0 fatal/ANR.

- [ ] **Step 3: Write the receipt and commit**

Create `docs/validation/phase4a-settings-<date>.md` recording device (AVD), each observation above marked pass/fail with what was seen (note the `MgbaPerf` frame-rate evidence for fast-forward speed, and that haptics is not observable on an AVD), the 0-fatal/ANR result, and the screenshot. Then:
```sh
git add docs/validation/phase4a-settings-<date>.md docs/validation/phase4a-settings.png
git commit -m "docs: record Phase 4a settings device verification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage (Phase 4, Slice A portion):** the spec's Phase 4 groups map as — Video: orientation lock (Task 4 `applyOrientation`) + scaling (Tasks 1 `fitScale` + 4 `setIntegerScale`); Audio: enable + volume (Task 4 EmulationRunner); Controls: haptics + opacity (Task 4 EmulatorView setters) — touch layout size/position and gamepad remapping are the deliberate second slice; Emulation: fast-forward speed (Tasks 1 + 4) — frameskip is deferred to the second slice with the other emulation controls. The "standard Android preference tree" is delivered as a programmatic grouped screen (design choice; no `androidx.preference` dependency).
- **Type consistency:** `Settings` enum/getter/setter names, `Settings.opacityPercentToAlpha`, `FeelMath.fitScale`, `frameBudgetNanos(boolean,int)`, `EmulatorView.setHapticsEnabled/setIdleOpacityAlpha/setIntegerScale`, `InGameMenuView.Listener.onSettings`, and the three appended `EmulationRunner` constructor args are used consistently across Tasks 1–4.
- **Defaults preserve behavior:** integer scale, ff speed 4, haptics on, opacity 24% (≈ alpha 61 vs the prior constant 60 — a 1-level difference, visually identical), audio on at volume 1.0, orientation unspecified (= today's auto-rotate) — so an untouched install behaves exactly as before Slice A.
- **Interlock:** Task 1 (pure) and Task 2 (Settings store) are standalone; Task 3 makes the screen reachable/editable but not yet effective on gameplay; Task 4 applies it. The app builds and runs after every task (Task 3's screen persists settings even before Task 4 wires them to playback).
- **Deliberately deferred (Slice B):** gamepad remapping (press-to-bind + persisted mapping), the Bluetooth-controller validation gate, touch-layout drag/size/position, frameskip, and any per-game profiles.
