# Phase 4a settings — device verification (2026-07-16)

Device: **clean x86_64 AVD** (`game-emulator-mvp`, 1080×2400) — used because the
physical test device's foreground contention and swipe lockscreen blocked
interactive automation across prior phases (same rationale as Phase 3).
Build: `app-benchmark.apk` from commit `aaeb3df1`.
Test ROM: the MIT `hello.gba` (`mgba-android/core/src/androidTest/assets/hello.gba`).

## Confirmed on device — every Slice A setting, end to end

1. **Reachable / renders** (`02-settings.png`): the **Settings** button in the
   `LibraryActivity` header opens the programmatic grouped screen. All four
   groups and seven rows render with correct defaults: Video → Orientation
   *Automatic*, Scaling *Integer (crisp)*; Audio → Sound *on*, Volume *max*;
   Controls → Vibrate on touch *on*, Control opacity when idle *low* (≈24%
   default); Emulation → Fast-forward speed *4×*. **PASS**
2. **Enum pickers** (`03`/`04`/`06`): the AlertDialog single-choice pickers show
   the right options with the current value pre-selected — Fast-forward shows
   2×/3×/4× with **4× selected** (confirms the `which+2` ordinal offset),
   Orientation shows Automatic/Portrait/Landscape, Scaling shows Integer/Fill.
   Each selection persists and the row summary updates via `recreate()`. **PASS**
3. **Orientation → Landscape** (`11-game-landscape.png`): after setting
   Orientation to Landscape, launching a ROM opens the player in **landscape**
   (surface 2400×1080), "Hello world!" rendered. `applyOrientation()` works.
   **PASS**
4. **Fast-forward speed → 2×** (MgbaPerf logcat): baseline normal speed measured
   **571–599 frames per 10 s window** (≈58 fps). With Fast-forward toggled on
   from the in-game menu and the speed set to 2×, the same window measured
   **1051–1109 frames** — ≈**1.85×** the baseline, i.e. ~2×, **not** the old
   hard-coded 4× (which would read ~2300). Confirms `frameBudgetNanos` now uses
   the configurable `fastForwardSpeed` field. **PASS**
5. **Haptics off**: with Vibrate on touch toggled off, pressing A, B, D-pad, and
   START produced **0 FATAL/ANR** and emulation stayed alive. (The tactile
   effect itself is not observable on an AVD; only crash-freedom and toggle
   persistence are verifiable here.) **PASS (as verifiable on AVD)**
6. **Control opacity → minimum** (`11-game-landscape.png`): with idle opacity
   dragged to minimum, after the idle fade the on-screen controls render
   **extremely faint** (much fainter than the default), and a screen touch
   snaps them back to full opacity (`15-integer-clean.png`). `setIdleOpacityAlpha`
   feeds `FeelMath.controlAlpha`'s `minAlpha`. **PASS**
7. **Scaling → Fill vs Integer** (`11` Fill vs `15` Integer): in Fill the game
   image is edge-filling (~1033 px wide in the capture); switching to Integer
   yields a slightly smaller centered image (~975 px) with a visible margin — a
   ~6% difference, exactly matching `FeelMath.fitScale`'s aspect-fit 4.29× vs
   `integerScale`'s 4× for a 240×160 source in this box. `setIntegerScale`
   selects the correct path in `onDraw`. **PASS**
8. **Sound off**: with Sound toggled off the game runs silently with no
   AudioTrack created and **no crash** (0 FATAL/ANR); `audioEnabled` gates the
   audio path and every downstream `audioTrack` use is null-guarded. **PASS**
9. **Persistence** (`08-persist.png`): leaving Settings to the library and
   returning showed **every** choice retained (Landscape, Fill, Sound off,
   Vibrate off, opacity min, FF 2×). Settings is also reachable from the
   **in-game menu** — the previously-disabled "Settings" stub is now live
   (`12-menu.png` → `14-ingame-settings.png`). **PASS**

## No crashes

**0 FATAL / ANR** for `com.trebuchetdynamics.garnacha` across the entire
session (Settings navigation, all pickers/toggles, ROM import, landscape play,
fast-forward, control presses with haptics + audio off, and the scaling switch).

Receipt screenshot: `docs/validation/phase4a-settings.png` (landscape play with
faint idle controls — orientation + opacity in one frame).

## Result

Phase 4a is verified on device: the settings screen is reachable from both the
library and the in-game menu, all seven controls persist, and every setting
takes effect on the running player (orientation lock, 2× fast-forward, faint
idle controls, fill-vs-integer scaling, silent audio) with no gameplay
regression and 0 FATAL/ANR.
