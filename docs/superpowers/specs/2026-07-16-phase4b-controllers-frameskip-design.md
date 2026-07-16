# Phase 4 Slice B — Gamepad remapping + frameskip — Design

**Date:** 2026-07-16
**Status:** Approved design, pending spec review
**Phase:** Phase 4 (Settings + controllers), Slice B — the deferred second half.
**Predecessor:** Phase 4a (settings foundation + cheap toggles), commits `c315d93d..66d34438`.

## Goal

Make physical game controllers **remappable** and add a **frameskip** emulation
control, completing the input/emulation half of Phase 4. Deliberately excludes
the touch-layout editor (drag/resize/reposition of on-screen controls), which
is a separate future spec.

## Scope

**In scope**

1. **Gamepad remapping** — a data-driven `keycode → GBA-key` map with a
   press-to-bind screen, persisted, replacing the hard-coded `MainActivity.mapKey`
   switch. Buttons + D-pad/hat `KeyEvent`s only (no analog-axis handling). A
   single global mapping shared by all controllers.
2. **Frameskip** — an emulation setting (0=off default, up to 3) that skips the
   video blit for N frames while the core steps and audio writes every frame.

**Out of scope (deferred)**

- Touch-layout editor (own spec).
- Analog left-stick → D-pad axis handling.
- Per-controller mappings keyed by `InputDevice` descriptor.
- Per-game settings/profiles.

**Partially closed**

- The roadmap's **Bluetooth-controller validation gate**. No physical
  controller is available, so real BT-pairing against a real pad is **not**
  closed here. The remap pipeline is validated on the AVD with injected gamepad
  `KeyEvent`s (`adb shell input keyevent <gamepad-keycode>`), which exercises the
  exact `dispatchKeyEvent → mapKey → setHardwareKey` path a real pad uses. The
  real-hardware check remains an explicitly-open gate, recorded in the exit
  criteria.

## Global constraints

- Language/platform: Java, `minSdk 24`, `targetSdk 35`, package
  `com.trebuchetdynamics.emulator.app`. Programmatic Views only — no XML
  layouts, no `androidx.preference` dependency.
- **No regression for the default (unbound) install:** the default `KeyBindings`
  map must reproduce the current `mapKey` switch exactly, and `frameskip`
  defaults to 0 (render every frame) — so an untouched install behaves exactly
  as it does after Phase 4a.
- Pure logic (`KeyBindings` map/lookup/serde, `shouldRenderFrame`, `Settings`
  clamp helpers) must be JVM-testable with **no `android.*` imports** in the
  pure paths. Default keycode values are injected from the Android side as ints.
- No change to the single-sourced `ControlLayout` geometry, the in-game
  menu / save-state / notices wiring, the ROM library/import/play-by-id
  contract, or the Phase 4a Settings behavior.
- Emulation-thread discipline: settings are read on the UI thread and handed to
  the emulation thread at runner construction (as Phase 4a did with audio/ff);
  `KeyBindings` used at runtime is read on the UI thread (`dispatchKeyEvent`).

## Current state (what exists)

- `MainActivity.dispatchKeyEvent(KeyEvent)` calls `mapKey(int keyCode)` — a
  hard-coded `switch` returning a GBA-key bitmask (or a sentinel for "no
  binding"), then `emulatorView.setHardwareKey(key, down)`. When the in-game
  menu is open, gamepad keys are routed to menu navigation instead (unchanged
  by this slice).
- GBA key bitmasks are defined on `MgbaSession`: `KEY_A`, `KEY_B`, `KEY_L`,
  `KEY_R`, `KEY_START`, `KEY_SELECT`, `KEY_UP`, `KEY_DOWN`, `KEY_LEFT`,
  `KEY_RIGHT`.
- The current default bindings (from the `mapKey` switch) are:
  - A ← `BUTTON_A`, `X`
  - B ← `BUTTON_B`, `Z`
  - START ← `BUTTON_START`, `ENTER`
  - SELECT ← `BUTTON_SELECT`, `DEL`
  - Up/Down/Left/Right ← `DPAD_UP`/`DPAD_DOWN`/`DPAD_LEFT`/`DPAD_RIGHT`
  - L ← `BUTTON_L1`; R ← `BUTTON_R1`
  (Confirm the exact set against `MainActivity.mapKey` at implementation time and
  reproduce it verbatim as the `KeyBindings` default.)
- `Settings` (Phase 4a) is a typed SharedPreferences wrapper (file
  `garnacha_settings`, `MODE_PRIVATE`, `apply()`), with `clampPercent`,
  `clampFastForwardSpeed`, `opacityPercentToAlpha` pure helpers and ordinal-safe
  enum getters.
- `EmulationRunner` (Phase 4a) constructor already takes appended
  `audioEnabled, audioVolume, fastForwardSpeed` args; the loop steps the core,
  writes audio, and invokes a frame/render callback that copies the native
  framebuffer to a bitmap and invalidates `EmulatorView` each frame.
- `SettingsActivity` (Phase 4a) is a programmatic grouped screen
  (Video/Audio/Controls/Emulation) with Switch rows, SeekBar rows, and
  single-choice `AlertDialog` pickers; each control persists immediately, enum
  pickers call `recreate()`. `exported=false`.

## Architecture

### Component 1 — `KeyBindings` (pure)

A pure value/logic class holding a `Map<Integer,Integer>` from Android keycode to
GBA-key bitmask.

- `int gbaKeyFor(int keyCode)` — returns the GBA-key bitmask, or `0` if the
  keycode is unbound. (`0` = no GBA key; callers already treat "no binding" as a
  no-op.)
- `void bind(int gbaKey, int keyCode)` — binds `keyCode` to `gbaKey`. A physical
  keycode maps to exactly one GBA button: binding a keycode already used
  elsewhere first removes the old entry (conflict reassignment). Binding also
  replaces any existing keycode(s) for that `gbaKey` per the press-to-bind model
  (one keycode per GBA button in the editable map; the multi-key keyboard
  fallbacks exist only in the default map).
- `void reset(Map<Integer,Integer> defaults)` — restores the injected defaults.
- `String serialize()` — compact, stable string, e.g.
  `"96:1,97:2,..."` (`keycode:gbakey` pairs, comma-separated; deterministic
  order for testable round-trips).
- `static KeyBindings parse(String s, Map<Integer,Integer> defaults)` — parses a
  stored string; on empty/null/garbage falls back to `defaults` (tolerant, like
  the Phase 4a ordinal guards).

No `android.*` imports. Keycode and GBA-key values are plain ints supplied by the
caller, so all behavior is JVM-testable.

Ownership/interface: consumed by `MainActivity` (runtime lookup) and
`GamepadSettingsActivity` (editing). Produced/persisted via `Settings`.

### Component 2 — `Settings` additions

- `KeyBindings gamepadBindings(Map<Integer,Integer> defaults)` — reads the stored
  serialized string and returns `KeyBindings.parse(stored, defaults)`.
- `void setGamepadBindings(KeyBindings bindings)` — persists
  `bindings.serialize()`.
- `int frameskip()` — default `0`; `void setFrameskip(int)` — clamps on write.
- `static int clampFrameskip(int)` — clamps to `0..3` (pure).

The default keycode→GBA-key map itself is built on the Android side (from
`KeyEvent.KEYCODE_*` and `MgbaSession.KEY_*` constants) and passed in, keeping
`Settings`/`KeyBindings` pure paths android-free.

### Component 3 — `GamepadSettingsActivity` (new)

A programmatic screen (same style as `SettingsActivity`), `exported=false`,
reached from `SettingsActivity` → Controls → a **"Gamepad buttons"** row.

- Renders a list of the 10 GBA inputs in a fixed order (A, B, L, R, START,
  SELECT, D-pad Up, Down, Left, Right). Each row: the GBA button name + the
  currently-bound physical key label (`KeyEvent.keyCodeToString` trimmed of the
  `KEYCODE_` prefix; "Unbound" if none).
- Tapping a row enters a **listening** state ("Press a button…", the row
  highlighted / a small dialog). The next qualifying gamepad `KeyEvent` (from a
  gamepad/D-pad source) is captured via `dispatchKeyEvent`/`onKeyDown`, bound to
  the selected GBA button through `KeyBindings.bind`, persisted via
  `Settings.setGamepadBindings`, and the list refreshes. `KEYCODE_BACK` cancels
  the listening state (does not bind, does not leave the screen).
- A **Reset to defaults** action restores the injected default map and persists.
- Editing mutates a working `KeyBindings` loaded on entry; every bind/reset
  persists immediately (no separate "save").

### Component 4 — Frameskip in the emulation loop

- `shouldRenderFrame(long frameIndex, int frameskip)` — pure static:
  `frameIndex % (frameskip + 1) == 0`. `frameskip=0` ⇒ always true (current
  behavior). Lives as a testable static (in `EmulationRunner` or a pure helper
  class) with unit tests.
- `EmulationRunner` takes a `frameskip` value (a new appended constructor arg,
  following the Phase 4a pattern), maintains a per-run frame index, and invokes
  the video render callback only when `shouldRenderFrame` is true. The core step
  and audio write happen every frame regardless. During fast-forward, frameskip
  and the FF frame-budget change compose (both apply); no special-casing.
- `MainActivity.startRunner` passes `settings.frameskip()`.

### Component 5 — `SettingsActivity` additions

- Under **Controls**: a **"Gamepad buttons"** row (subtitle e.g. "Remap a
  connected controller") that starts `GamepadSettingsActivity`.
- Under **Emulation**: a **"Frameskip"** single-choice picker (Off / 1 / 2 / 3),
  mapping selection index → `0..3`, persisted immediately via `setFrameskip`.

### Component 6 — `MainActivity` runtime wiring

- Build the default keycode→GBA-key map once (from `KeyEvent`/`MgbaSession`
  constants), matching the current `mapKey` switch verbatim.
- Load `KeyBindings` (via `settings.gamepadBindings(defaults)`) in `onResume`,
  alongside the other pushed settings, so a return from
  `GamepadSettingsActivity` picks up new bindings.
- Replace the `mapKey` switch body with `bindings.gbaKeyFor(keyCode)`.
- In-game-menu gamepad navigation is unchanged.

## Data flow

**Bind:** user opens Settings → Controls → Gamepad buttons →
`GamepadSettingsActivity` → taps a GBA row → presses a controller button →
`dispatchKeyEvent` captures the keycode → `KeyBindings.bind` → `Settings.
setGamepadBindings` (serialized to SharedPreferences) → list refreshes.

**Play:** user returns to the player → `MainActivity.onResume` reloads
`KeyBindings` → controller button press → `dispatchKeyEvent` →
`bindings.gbaKeyFor(keyCode)` → `emulatorView.setHardwareKey`.

**Frameskip:** user sets Frameskip in Settings → persisted → `MainActivity.
onResume`/`startRunner` constructs `EmulationRunner` with the value → the loop
renders every `(frameskip+1)`th frame; core + audio unaffected.

## Error handling & edge cases

- **Unbound keycode** → `gbaKeyFor` returns `0` → no-op (matches today's "no
  binding" path).
- **Corrupt/empty stored bindings** → `parse` falls back to defaults.
- **Conflict** (binding a keycode already used) → old entry removed so no
  physical key drives two GBA buttons.
- **BACK during listening** → cancels, no bind.
- **Frameskip out of range** (stored or passed) → `clampFrameskip` bounds it to
  `0..3`.
- **Bind screen with no controller** → the screen still works; rows just stay at
  their current bindings until a gamepad event arrives (keyboard keycodes also
  qualify on the AVD, which is how we validate).

## Testing

**Pure JVM unit tests**

- `KeyBindings`: default lookup for each GBA button; unknown keycode → 0;
  `bind` overrides and reassigns on conflict; `reset` restores defaults;
  `serialize`/`parse` round-trip; `parse` of empty/garbage → defaults.
- `shouldRenderFrame`: sequences for frameskip 0 (all render), 1 (every 2nd),
  2 (every 3rd), 3 (every 4th).
- `Settings`: `clampFrameskip` bounds `-1→0`, `5→3`, `2→2`; `frameskip` default
  0; `gamepadBindings` round-trips through set/get with defaults injected.

**Device (clean AVD, as in Phase 3/4a)**

- Inject gamepad keycodes (`adb shell input keyevent 96`/`97`/D-pad/etc.) →
  confirm the **default** map drives the game (button registers in-game).
- Open Gamepad buttons → rebind one GBA button (e.g. A) to a different keycode →
  inject the new keycode → confirm A now fires and the old keycode no longer
  does. Confirm persistence across leaving/returning.
- Reset to defaults → confirm original bindings restored.
- Set Frameskip = 1 → confirm the game still runs and `MgbaPerf` shows the
  rendered-frame count roughly halving while the core's stepped-frame rate stays
  full (audio underruns unaffected).
- 0 FATAL/ANR across the flow.

## Files

- **Create:** `KeyBindings.java`, `KeyBindingsTest.java`,
  `GamepadSettingsActivity.java`.
- **Modify:** `Settings.java` (+ `SettingsTest.java`), `EmulationRunner.java`
  (+ frameskip helper test), `MainActivity.java`, `SettingsActivity.java`,
  `AndroidManifest.xml` (register `GamepadSettingsActivity`, `exported=false`).

## Exit criteria (v0.5 progress)

- Controllers are remappable via a press-to-bind screen; bindings persist and
  drive gameplay; validated on the AVD with injected gamepad key events.
- Frameskip 0–3 works; default 0 is a no-op; verified it reduces rendered frames
  without slowing the core.
- Defaults reproduce prior behavior exactly (no regression for an unbound,
  frameskip-off install).
- **Open gate (documented, not closed):** real Bluetooth/USB controller pairing
  and remapping against physical hardware — pending a controller becoming
  available.
- The touch-layout editor remains for a subsequent Phase 4 slice before v0.5 is
  fully complete.
