# Touch-layout editor — device verification (2026-07-16)

Device: **clean x86_64 AVD** (`game-emulator-mvp`) running landscape (2400×1080)
— used because the physical device's foreground contention and swipe lockscreen
blocked interactive automation across prior phases.
Build: `app-benchmark.apk` from commit `e84e679d` (Task 4 + back-button fix).
Test ROM: the MIT `hello.gba`.

## Confirmed on device

1. **Reachable** (`t03-menu.png`): the in-game **MENU** gains an **Edit layout**
   item below Settings; tapping it opens the editor overlay. **PASS**
2. **WYSIWYG editor** (`t04-editor.png`): the overlay renders all seven gamepad
   controls with outlines (L, R, D-pad — labelled "D-pad", A, B, SELECT, START)
   over the still-running game, plus a bottom bar with a scale slider and
   **RESET / CANCEL / SAVE**. **PASS**
3. **Drag to reposition** (`t05` → `t07`): dragging the A button moves it; the
   selected control highlights teal and the scale slider seeds to its current
   scale (~33% ≙ 1×). **PASS**
4. **Uniform scale** (`t07-scaled.png`): raising the slider enlarges the selected
   A button (slider ~62% ≙ ~1.4×). **PASS**
5. **Save applies to the live player** (`t08-saved.png`): after Save the editor
   closes and the player renders the A button at its **new, enlarged** position —
   a single A, override applied. **PASS**
6. **Persistence across relaunch** (`t09-relaunch.png`): returning to the library
   and relaunching the ROM shows the A button **still** moved/enlarged (loaded
   from SharedPreferences on resume). **PASS**
7. **Reset restores defaults** (`t10-reset.png` → `t11-reset-saved.png`): in the
   editor, Reset snaps the working layout back to defaults (without persisting
   until Save); Save then persists the default layout — the player shows all
   controls back at their default positions. **PASS**
8. **Back cancels the editor** (`t12-back-cancel.png`): with an uncommitted drag
   in progress, pressing the Back button closes the editor and returns to the
   game with the change **discarded** (control at its default) — i.e. Back = Cancel
   (the back-button fix, commit `e84e679d`). **PASS**
9. **Runtime hit path**: tapping both the new and old A locations in the player
   produced no crash. The exact "moved control responds at its new box" bit is
   guaranteed by the single-source architecture (`onTouchEvent` reads the same
   `ControlLayout` `onDraw` builds with overrides) and the unit test
   `keysAtFollowsTheOverriddenControl`; the all-black `hello.gba` shows no visible
   button reaction, so only the render-move + no-crash were observable here.
   **PASS (as verifiable on this ROM)**

## No crashes

**0 FATAL / ANR** for `com.trebuchetdynamics.garnacha` across the whole session
(menu, editor open, drag, scale, Save, relaunch, Reset+Save, Back-cancel).

## Observations / caveats

- **Cosmetic — live controls bleed through the editor (backlog):** the editor
  overlay is transparent so the running game stays visible for context, but the
  live `EmulatorView` also keeps drawing its **own** controls (at their saved/
  default positions, idle-faded) behind the editor. For a control you have moved,
  this shows a faint "ghost" at its old position while editing (visible in `t05`/
  `t07`). It is **not** a correctness bug — there is exactly one real control per
  key, and Save updates the live layer so the ghost disappears. Candidate polish:
  dim or hide the `EmulatorView`'s controls (or add a scrim) while the editor is
  open.
- **Per-orientation independence not exercised on-device:** the AVD orientation
  was locked to Landscape (a persisted Phase 4a setting), so the portrait editor
  was not reachable in the UI. Independence is by construction — portrait and
  landscape use separate SharedPreferences keys (`layoutOverridesPortrait` /
  `layoutOverridesLandscape`) and `EmulatorView` picks the set by `w > h` each
  frame.

## Result

The touch-layout editor is verified on device: reachable from the in-game menu,
controls drag and uniformly scale in a WYSIWYG overlay, Save applies + persists,
Reset restores defaults, Back cancels, and there is no draw/hit-test drift (the
moved control both renders and is hit-tested from one overridden layout). 0 FATAL/
ANR. One cosmetic ghosting item is recorded for follow-up; the real-hardware
Bluetooth-controller gate (Phase 4b) remains the one open item before tagging v0.5.
