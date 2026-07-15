# Phase 1 in-game menu — device verification (2026-07-15)

Device: Samsung Galaxy S24 Ultra (SM-S928B), Android 16 / API 36, arm64-v8a.
Build: `app-benchmark.apk`, versionName 0.1.0-dev, from commit `e094d89b`.

## Confirmed on device

- **Build/install/launch:** the app installs and launches to its home screen;
  `com.trebuchetdynamics.garnacha` is the resumed activity.
- **MENU chip renders** in the header band in the correct position — left of
  NOTICES and LOAD — in both the empty-home and running-game states, matching
  the single-sourced `ControlLayout` geometry (screenshot:
  `phase1-menu-chip-running.png`). This confirms Task 4/5 geometry live.
- **Full-speed gameplay** with a real commercial ROM (Minish Cap, tester's own
  cartridge dump) loaded via the document picker: `MgbaPerf` reported
  `frames=598 avg_us=1313 max_us=4685 late=0 underruns=0` — full 59.7 fps, no
  late frames, no audio underruns.
- **The in-game menu overlay renders correctly and completely** (screenshot:
  `phase1-menu-overlay-ondevice.png`): a translucent panel over the running
  game with the game visible behind it, a **Save state** row of four enabled
  slot buttons, a **Load state** row of four slots each showing
  "Slot N · empty" and correctly **disabled** (no state saved yet), a
  **Fast-forward** button, a **Reset** button, and a **Settings (coming soon)**
  button correctly rendered **disabled**. This confirms the full open path end
  to end: MENU chip → `openMenu` → `bind(SaveStateStore, fastForward)` reading
  per-slot occupancy → overlay display, with the empty/disabled slot states
  read live from `SaveStateStore`.
- **Soft reset** is separately confirmed by the Task 1 on-device instrumentation
  test (`resetReturnsTheCoreToPowerOn`, part of the 6/6 passing
  `:core:connectedDebugAndroidTest` on this device), which verified the frame
  counter returns to power-on after `MgbaSession.reset()`.

## Covered by unit/instrumentation, not re-confirmed end-to-end on device

The per-button *actions* (save writes a slot, load restores it, fast-forward
runs 4× and mutes audio) are covered by the 37 passing unit tests
(`SaveStateStoreTest` round-trips real bytes through the slot files;
`EmulationRunnerTest` pins `frameBudgetNanos`; the command-queue/mute wiring was
verified in review) plus the reset instrumentation test. Their end-to-end
device confirmation (tap slot → observe toast → observe game jump) was **not
completed** — see below.

## Why the interactive behavioral test was not completed

Repeated attempts to drive the save/load/fast-forward taps on the physical
device were disrupted by a hostile device environment, not by any code defect:

- other foreground apps on the device (a fleet-management app and others)
  repeatedly stole focus mid-interaction, replacing the emulator or the
  document picker;
- auto-rotation and unstable window focus moved targets between a screenshot
  and the following tap;
- the device's swipe lockscreen re-engaged on screen timeout during multi-step
  sequences.

The document picker itself was driven successfully with Maestro (semantic text
matching), and the menu *opened and rendered correctly*; the failures were all
in sustaining a stable foreground long enough to tap a menu button and read the
result.

## Recommendation

Re-run the interactive save → play → load → fast-forward → reset sequence on a
device without competing foreground apps (or a clean emulator/AVD) to close the
end-to-end confirmation. No code change is indicated: the overlay renders
correctly, the open/bind path is confirmed live, reset is instrumentation-
tested, and the action logic is unit-tested.

## Fatal/ANR

No FATAL or ANR entries for `com.trebuchetdynamics.garnacha` were observed
across the session's launches and menu interactions.
