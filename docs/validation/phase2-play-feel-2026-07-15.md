# Phase 2 play-feel — device verification (2026-07-15)

Device: Samsung Galaxy S24 Ultra (SM-S928B), Android 16 / API 36, arm64-v8a.
Build: `app-benchmark.apk`, from commit `f0f5e136`.

## Confirmed on device

- **Build/install/launch:** the Phase 2 build installs and launches; no FATAL or
  ANR entries for `com.trebuchetdynamics.garnacha`.
- **Fade is correctly gated off on the home screen** (screenshot:
  `phase2-home-full-opacity.png`): with no ROM loaded, the on-screen controls
  (D-pad, A/B, L/R, SELECT/START) and the MENU/NOTICES/LOAD chips render at
  **full opacity** — the idle fade does not apply when no game is running, which
  is the key correctness gate of the fade feature (`hasFrame == false` →
  `controlAlpha = 255`).
- **Render integrity after the Task 2 draw changes:** the empty-state game area
  draws correctly (black with the "Tap to load a GBA ROM" prompt) — the
  integer-scale / letterbox / `filterBitmap(false)` change did not disturb the
  non-playing path.

## Covered by unit tests + review, not re-confirmed end-to-end on device

The play-time visual/tactile behaviors are covered by the 44 passing unit tests
(`FeelMathTest` pins the integer-scale centering, the fade-alpha curve including
the 50%-fade rounding, and new-press detection) and four clean task reviews (the
setColor→setAlpha pairing was walked exhaustively; the haptic fires only on a
newly-pressed bit). Their end-to-end device confirmation was **not completed**:

- **Crisp integer-scaled pixels + letterbox** — needs a screenshot of a running
  game to inspect pixel edges and the letterbox bars.
- **Fade during play** — controls fading after ~2 s idle and snapping back on
  touch while a game runs.
- **Haptic tick** — tactile, only a human at the device can feel it.

## Why the play-time test was not completed

Same hostile device environment as Phase 1's device pass: other foreground apps
on this device (a fleet-management app, `com.salmalist.rn`, and others)
repeatedly stole focus and backgrounded the emulator within seconds
(`WindowStopped … focusedPkgName: com.salmalist.rn` in logcat), the document
picker did not respond consistently to automation, and the swipe lockscreen
re-engaged on timeout. Sustaining a running game in the foreground long enough
to observe fade timing and capture a crisp-pixel screenshot was not achievable.

## Recommendation

Verify crisp scaling, fade-during-play, and the haptic tick on a device without
competing foreground apps (or a clean x86_64 AVD). No code change is indicated:
the render pipeline is intact, the fade is correctly gated (confirmed on the home
screen), and the feel logic is unit-tested and reviewed. Haptic confirmation is
inherently a human-at-the-device check.
