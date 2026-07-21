# Garnacha Boy UI/UX study — 2026-07-14

Purpose: study what makes a great mobile GBA-emulator feel right, and turn it
into a concrete portrait + landscape design for Garnacha Boy. Feeds milestone
M5 (settings and input). References are studied for *ideas and conventions* —
no proprietary assets, art, or trade dress are copied.

References:
- **Pizza Boy GBA 2.2.13** — live device teardown on SM-S928B; screenshots and
  UI Automator hierarchies are in [`pizzaboy-live/`](pizzaboy-live/). The gold
  standard for "feels great in the hand."
- **Lemuroid 1.17.0** — installed on the test device from its Apache-2.0 GitHub
  release; live Maestro study captured separately.

---

## Part 1 — Pizza Boy live teardown: what makes it feel great

### The core principle
The game is the product; the chrome disappears. In active play there is nothing
on screen but the game and the controls — no title bars, no menus, no
distractions. Every management surface is one deliberate gesture away and then
gone again.

### Portrait
- **Game pinned to the top third**, letterboxed on a flat dark field, at integer
  or near-integer scale so pixels stay crisp. The screen never fights the
  controls for space.
- **Controls own the bottom two-thirds**, sized generously because there is room:
  a large D-pad bottom-left, A/B (and often X/Y positions unused for GBA) bottom-
  right on a diagonal, L/R as wide shoulder pills across the top of the control
  band, START/SELECT small and central. Thumbs rest naturally without reaching.
- **A single unobtrusive affordance** (a menu dot / swipe from the top edge)
  opens the in-game menu. No persistent buttons cluttering the play area.

### Landscape
- **Game centred and as large as the height allows**, 3:2, with the controls
  overlaid *transparently on the left and right gutters* rather than stealing
  vertical space. This is the key landscape move: the screen is maximised and the
  controls float semi-transparent over the black bars beside it.
- **D-pad in the left thumb arc, A/B in the right thumb arc**, both positioned
  where a thumb naturally curls when gripping a phone in two hands — not
  centred, not high. Shoulders at the very top corners under the index fingers.
- **Controls fade** when untouched so they don't distract from the game, and
  re-assert on touch.

### The in-game menu (the part most emulators get wrong)
- Invoked by an edge gesture or a small persistent handle; slides in *over* the
  game, game visible behind it. Not a separate opaque screen.
- Fast, flat, one-thumb reachable: **Save state / Load state** (with visible
  slot thumbnails), **Fast-forward** toggle, **Rewind**, **Reset**, **Settings**,
  **Quit**. The two most-used (save/load state, fast-forward) are the largest and
  closest to the thumb.

### Settings organisation
- Grouped and shallow: **Video** (scaling, aspect, filters/shaders, integer
  scale, orientation lock), **Audio** (enable, volume, low-latency),
  **Controls** (touch layout size/opacity/position, gamepad mapping, haptics),
  **Emulation** (frameskip, speed, BIOS), **General** (autosave, library).
- Live preview where it matters (scaling/filters show the game behind the
  setting), sensible defaults so a user never *has* to open settings to play.

### Why it feels premium
1. Zero chrome during play.
2. Controls placed for real thumb ergonomics, not grid symmetry.
3. Landscape maximises the screen by overlaying, not stacking.
4. The highest-frequency actions (save state, fast-forward) are the biggest and
   nearest.
5. Nothing blocks starting a game: pick ROM → play, no setup wall.

---

## Part 2 — Lemuroid live study (device reference)

Lemuroid 1.17.0, installed on SM-S928B. Findings from the on-device Maestro tour
are appended here (see the Maestro flows under `mgba-android/tools/ui-study/`).
Lemuroid's relevant conventions to note:
- Library-first entry (grid of games) rather than a single-ROM screen — this is
  the M4 direction, worth seeing live.
- In-game controls with an on-screen menu affordance; touch layouts per system.
- Settings as a standard Android preference tree (a cleaner-to-build baseline
  than a fully custom settings canvas).

*(Live capture pending — device is on the lockscreen; run the Maestro flows once
it is unlocked.)*

---

## Part 3 — Garnacha Boy UI spec (portrait + landscape)

Garnacha's own design, taking the ergonomics and disappear-the-chrome principles
above. This is what M5 (and the in-game menu, an M5/M6 feature) should build. It
extends, not replaces, the single-sourced `ControlLayout` already in the app.

### Portrait
- Game in the **top ~40%**, letterboxed on the app's dark field, centred, integer
  scale where it fits. Keep the current header band for LOAD/NOTICES until the
  library (M4) subsumes it.
- **D-pad bottom-left**, **A/B diagonal bottom-right** (already implemented and
  device-verified), sized off `unit = min(w,h)`. Keep.
- **L/R shoulder pills** across the top of the control band; **START/SELECT**
  small and centred at the bottom. Keep.
- Add (M5): a **small menu handle** (top-left, mirroring LOAD/NOTICES) that opens
  the in-game menu overlay.

### Landscape
- Game **centred, maximised to height** at 3:2 (already implemented). Keep.
- **Controls overlaid semi-transparently on the gutters** — evolve the current
  opaque gutter controls toward Pizza Boy's transparent, fade-when-idle overlay
  so the game reads as edge-to-edge. This is the single highest-value landscape
  upgrade.
- D-pad left thumb-arc, A/B right thumb-arc, shoulders top corners (already
  positioned this way). Tune opacity + idle-fade.

### In-game menu overlay (M5/M6)
- Edge-swipe or menu-handle opens a **translucent panel over the running game**
  (game keeps rendering behind it).
- Contents, largest/nearest first: **Save state**, **Load state** (with slot
  thumbnails once save-state UI lands in M5), **Fast-forward** toggle, **Reset**,
  **Settings**, **Close**.

### Settings (M5)
- Build as a **standard Android preference screen** (Lemuroid-style) — far less
  work than a custom canvas and instantly familiar. Groups: **Video** (scale,
  aspect, orientation lock; shaders deferred post-1.0), **Audio**, **Controls**
  (touch size/opacity/position, gamepad remap, haptics), **Emulation**
  (frameskip, fast-forward speed), **General** (autosave, library).

### Anti-goals (protect the differentiation)
- Do **not** clone Pizza Boy's exact look, skins, or iconography — borrow the
  ergonomics and the disappear-the-chrome philosophy, ship Garnacha's own flat,
  clean visual identity.
- No ads, no upsell, no setup wall. Free and immediate is the identity.

---

## Roadmap impact
- **M4 (library):** Lemuroid's library-first entry confirms the direction.
- **M5 (settings + input):** the settings groups, the transparent fade-when-idle
  landscape overlay, and the touch-layout adjustability above are the concrete
  scope.
- **New M5/M6 feature:** the in-game menu overlay (save/load state,
  fast-forward, reset, settings) — the single biggest "feels like a real
  emulator" upgrade. Add to the roadmap backlog.
