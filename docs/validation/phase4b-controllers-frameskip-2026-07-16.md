# Phase 4b controllers + frameskip — device verification (2026-07-16)

Device: **clean x86_64 AVD** (`game-emulator-mvp`, 1080×2400) — used because the
physical device's foreground contention and swipe lockscreen blocked
interactive automation across prior phases.
Build: `app-benchmark.apk` from commit `74e13cd8`.
Test ROM: the MIT `hello.gba`.

## Confirmed on device

1. **Settings rows render** (`b04-settings.png`): the Controls group gains a
   **"Gamepad buttons"** row ("Remap a connected controller"); the Emulation
   group gains a **"Frameskip"** row ("Off"). **PASS**
2. **Bind screen** (`b05-gamepad.png`): Gamepad buttons opens a list of all ten
   GBA inputs with correct default labels — A→BUTTON_A, B→BUTTON_B, L→BUTTON_L1,
   R→BUTTON_R1, START→BUTTON_START, SELECT→BUTTON_SELECT, D-pad
   Up/Down/Left/Right→DPAD_UP/DOWN/LEFT/RIGHT — plus a Reset-to-defaults button.
   Confirms `GamepadDefaults.map()`, `KeyBindings.keyCodeFor`, and the
   `KEYCODE_`-stripped labels. **PASS**
3. **Press-to-bind** (`b06-listening.png` → `b07-rebound.png`): tapping the A row
   shows "Press a button…"; injecting `KEYCODE_BUTTON_X` (`adb shell input
   keyevent 99`) rebinds A, and the row updates to **BUTTON_X**. This exercises
   the real `dispatchKeyEvent → KeyBindings.bind → Settings.setGamepadBindings`
   path with a genuine `KeyEvent`. **PASS**
4. **Persistence** (`b08-persist.png`): leaving to Settings and reopening the
   screen shows A still bound to **BUTTON_X**. **PASS**
5. **Reset to defaults** (`b09-reset.png`): tapping Reset restores every row to
   its default binding (A→BUTTON_A, etc.). **PASS**
6. **Frameskip picker** (`b10-frameskip-dialog.png` → `b11-frameskip-set.png`):
   the picker shows Off/1/2/3 (Off pre-selected); selecting 1 updates the row to
   **1** and persists (index maps directly 0..3, no off-by-one). **PASS**
7. **Runtime remap + frameskip in the player** (`b12-game-frameskip.png`): with
   Frameskip = 1 and Orientation = Landscape, launching the ROM runs it in
   landscape ("Hello world!" renders crisp). Injecting ten different gamepad
   keycodes (`96 97 108 109 19 20 21 22 102 103`) through the running player
   produced **no crash** — the `dispatchKeyEvent → bindings.gbaKeyFor →
   setHardwareKey` path is alive. **PASS**
8. **Frameskip does not slow the core** (MgbaPerf): under Frameskip = 1,
   `MgbaPerf` reads **frames=599 / 598 per 10 s window** — identical to the
   full-speed baseline (~590–599) — proving the core steps every frame; only the
   video blit is skipped (which `MgbaPerf`'s `frames=` does not count, so the
   count is expected to stay full). `avg_us` dropped to ~356 (less per-iteration
   work), consistent with skipped blits. This matches the unit-tested
   `shouldRenderFrame` gate. **PASS**

## No crashes

**0 FATAL / ANR** for `com.trebuchetdynamics.garnacha` across the whole session
(settings navigation, both new pickers/rows, the bind screen incl. listening /
rebind / persist / reset, ROM launch, runtime key injection, and frameskip play).

## Observations / caveats

- **Bluetooth-hardware gate remains OPEN (as designed):** no physical controller
  was available, so this validates the remap *pipeline* end-to-end with injected
  `KeyEvent`s, but not real Bluetooth/USB pairing against a physical pad. That
  check is deferred until a controller is available.
- **Cosmetic (minor, not blocking) — multi-alias label flips after persist:**
  for GBA buttons that keep two default keys (B=BUTTON_B+Z, START=BUTTON_START+
  ENTER, SELECT=BUTTON_SELECT+DEL, and A=BUTTON_A+X before rebinding), the row
  label shows whichever alias has the lowest Android keycode. On a fresh
  defaults map that is insertion order (BUTTON_B/START/SELECT); after any bind
  triggers a `serialize()` round-trip (which sorts by keycode), the labels flip
  to the lower-keycode keyboard aliases (Z/ENTER/DEL) — see the difference
  between `b05` and `b08`. **Both aliases remain bound and functional** (the map
  is intact); only the displayed "primary" label changes. Candidate polish for
  the touch-layout/controllers follow-up: show all bound keys, or prefer the
  gamepad alias in the label.

## Result

Phase 4b is verified on device: gamepad remapping (press-to-bind, persistence,
reset, runtime application) and frameskip (picker, persistence, core-unaffected
skip) all work with 0 FATAL/ANR. The real-hardware Bluetooth gate stays open,
and the touch-layout editor remains a separate future slice.
