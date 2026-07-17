# Touch-Layout Editor — Design

**Date:** 2026-07-16
**Status:** Approved design, pending spec review
**Phase:** Phase 4 (Settings + controllers), final slice — the deferred touch-layout editor.
**Predecessors:** Phase 4a (settings foundation), Phase 4b (gamepad remapping + frameskip).

## Goal

Let the player drag-reposition and resize the on-screen gamepad controls (D-pad,
A, B, L, R, SELECT, START), persisted **per orientation**, overriding the
computed `ControlLayout` geometry — without ever letting what is drawn drift from
what is touchable.

## Scope

**In scope**

1. A pure per-control **override model** (`ControlOverrides`): normalized center
   (fraction of width/height) + uniform scale (0.5×–2×) per control, stored
   separately for portrait and landscape.
2. A pure **apply seam**: `ControlLayout.of(w, h, overrides)` that composes the
   overrides onto the computed defaults, clamped to stay on-screen, preserving
   the single-source-of-truth (draw == hit-test).
3. A **WYSIWYG editor overlay** (`LayoutEditorView`) launched from the in-game
   menu: tap-select, drag-move, a scale slider, and Save / Reset / Cancel.
4. **Persistence** via `Settings` (two pref slots) and runtime application in
   `EmulatorView` / `MainActivity`.

**Out of scope (deferred / not built)**

- Editing the MENU / NOTICES / LOAD chips (they remain fixed header UI; they are
  not in the `controls` list and are functional, rarely-moved UI).
- Independent width/height resize (uniform scale only).
- Per-game layouts, snap-to-grid, opacity-per-control, multi-touch pinch scaling.
- Editing the game rect.

## Global constraints

- Java, `minSdk 24`, `targetSdk 35`, package `com.trebuchetdynamics.emulator.app`.
  Programmatic Views only — no XML layouts, no `androidx.preference`.
- **No regression for the default install:** with no overrides stored, every
  control renders and hit-tests exactly where it does today. `of(w, h)` must
  behave identically to the current implementation.
- Pure logic (`ControlOverrides`, `LayoutEditMath`, `ControlLayout.of(...,
  overrides)`, `Settings` clamp/serde helpers) must be JVM-testable with **no
  `android.*` imports** in the pure paths. `ControlLayout` already carries this
  constraint (an `android.graphics.RectF` throws "Stub!" off-device) — keep it.
- **Single source of truth preserved:** `EmulatorView` must continue to derive
  both drawing and hit-testing from one `ControlLayout` built per frame, so an
  override can never move a control's picture without moving its touch box. This
  is the property that fixed the earlier landscape hit-test bug; it is the
  primary correctness constraint of this slice.
- No change to the in-game menu save-state / notices / settings wiring beyond
  adding the new "Edit layout" entry, to the ROM library/import/play-by-id
  contract, or to Phase 4a/4b behavior.
- Commit trailer on every commit: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Current state (what exists)

- `ControlLayout` (pure) is the single source of truth. `ControlLayout.of(float
  width, float height)` returns `width > height ? landscape(...) : portrait(...)`.
  It exposes the game rect, the LOAD/NOTICES/MENU chip rects (as separate
  `float` fields), and a `List<Control> controls`. Each `Control` has
  `int key, String label, Shape shape {CIRCLE,PILL,DPAD}, float cx, cy,
  halfWidth, halfHeight`, a `contains(x,y)` test, and the layout exposes
  `keysAt(x,y)` (OR of every control hit; the D-pad box decomposes into
  direction bits around a dead zone) and `isLoadHit/isNoticesHit/isMenuHit`.
- The `controls` list holds exactly the seven gamepad controls with these `key`
  ids (from `MgbaSession`): D-pad `key = 0`; A `= 1`; B `= 2`; SELECT `= 4`;
  START `= 8`; R `= 256`; L `= 512`. Every id is unique within the list (the
  D-pad's `0` included), so `key` is a valid override map key.
- `EmulatorView` rebuilds the layout every frame: `onDraw` and `onSizeChanged`
  both call `ControlLayout.of(getWidth(), getHeight())`, storing it in `layout`;
  `onDraw` draws the game rect, the three chips, and each control from `layout`;
  `onTouchEvent` hit-tests via `layout.isMenuHit/isNoticesHit/isLoadHit` and
  `layout.keysAt(...)`. Draw and hit-test therefore always agree.
- The in-game menu (`InGameMenuView`) has a `Listener` with existing callbacks
  (save/load state, fast-forward, reset, settings, close). Phase 4a added
  `onSettings`. This slice adds `onEditLayout` the same way.
- `Settings` is a typed SharedPreferences wrapper (`garnacha_settings`,
  `MODE_PRIVATE`, `apply()`) with pure clamp/serde helpers and tolerant parsers
  (the `KeyBindings.parse` and ordinal-guard patterns).
- `MainActivity` hosts `EmulatorView` and the `InGameMenuView` overlay, loads
  settings on `onResume` and pushes them to the view, and constructs the
  `EmulationRunner`.

## Architecture

### Component 1 — `ControlOverrides` (pure)

Holds a map from control `key` → an override triple. Represented as a small
value object per entry.

- `static final ControlOverrides EMPTY` — an immutable empty set (used as the
  default so `of(w,h)` composes trivially).
- `boolean has(int key)`.
- `float normCx(int key)`, `float normCy(int key)`, `float scale(int key)` —
  defined only when `has(key)`; callers guard with `has`.
- `void put(int key, float normCx, float normCy, float scale)` — stores a
  clamped override (`clampNorm` on the centers, `clampScale` on the scale).
- `void clear()` — remove all overrides (per-orientation reset).
- `String serialize()` — deterministic `"key:normCx:normCy:scale,..."` ordered
  by key; floats formatted with a fixed, locale-independent precision.
- `static ControlOverrides parse(String s)` — tolerant: null/empty/garbage or an
  unparseable field yields `EMPTY` (or skips the bad entry), never throws.
- `static float clampNorm(float v)` → `[0f, 1f]`.
- `static float clampScale(float v)` → `[0.5f, 2f]`.

No `android.*` imports. Keys, norms, and scales are plain numbers.

Ownership: produced/consumed by `Settings` (persistence), `ControlLayout.of`
(apply), and `LayoutEditorView` (editing). One instance represents one
orientation's overrides.

### Component 2 — `ControlLayout.of(w, h, overrides)` (pure)

A new overload. `of(w, h)` delegates to `of(w, h, ControlOverrides.EMPTY)`.

`of(w, h, overrides)` builds the default `controls` list exactly as today, then,
for each control whose `key` is overridden, produces a replacement `Control`:

- `cx = clampCenterX(overrides.normCx(key) * w, halfWidth', w)`
- `cy = clampCenterY(overrides.normCy(key) * h, halfHeight', h)`
- `halfWidth' = defaultHalfWidth * overrides.scale(key)`
- `halfHeight' = defaultHalfHeight * overrides.scale(key)`
- `shape`, `key`, `label` unchanged.

Clamp keeps the control's box fully on-screen: `cx ∈ [halfWidth', w -
halfWidth']`, `cy ∈ [halfHeight', h - halfHeight']` (if a scaled control is
wider/taller than the screen, clamp to center — an unreachable case given the
0.5×–2× range and control sizes, but defined). The default `of(w,h)` path and the
chip/game-rect fields are unchanged. Because `EmulatorView` will call this one
method for both drawing and hit-testing, the override moves the picture and the
touch box together.

`keysAt`, `contains`, and the chip hit-tests are unchanged — they operate on
whatever `controls`/fields the layout holds, overridden or not.

### Component 3 — `LayoutEditMath` (pure)

The gesture/'geometry math the editor needs, isolated for JVM testing:

- `static float toNorm(float pixel, float extent)` → `clampNorm(pixel / extent)`
  — convert a finger coordinate to a normalized center.
- `static float scaleForProgress(int seekProgress, int seekMax)` → maps a slider
  position `0..seekMax` linearly to `[0.5f, 2f]` (so the midpoint ≈ 1×).
- `static int progressForScale(float scale, int seekMax)` — the inverse, to seed
  the slider from an existing override.
- `static int pickControl(List<ControlLayout.Control> controls, float x, float
  y)` — returns the index of the control whose box contains `(x, y)` (topmost /
  smallest-area wins on overlap), or `-1`. *(If passing `Control` here would pull
  a nested type into a pure test awkwardly, this selector may instead live in
  `LayoutEditorView` and be covered by the device pass; the `toNorm`/`scale`
  conversions are the parts that must be unit-tested.)*

No `android.*` imports.

### Component 4 — `Settings` additions

- `ControlOverrides controlOverrides(boolean landscape)` — reads the stored
  string for that orientation (`K_LAYOUT_LANDSCAPE` or `K_LAYOUT_PORTRAIT`,
  default `""`) and returns `ControlOverrides.parse(stored)`.
- `void setControlOverrides(boolean landscape, ControlOverrides overrides)` —
  persists `overrides.serialize()` under the matching key.

Two new pref keys. No change to existing keys or getters.

### Component 5 — `LayoutEditorView` (the editor overlay)

A custom `View` shown on top of `EmulatorView` while editing (added to
`MainActivity`'s root container, initially `GONE`). The running game stays
behind it; the editor captures all touch so no input reaches the game.

State: a working `ControlOverrides` (seeded from the current orientation's
saved overrides), the current orientation (`w > h`), and a `selectedKey`
(`-1` = none).

Rendering: builds `ControlLayout.of(w, h, working)` each `onDraw` and draws each
control at **full opacity** with an outline; the selected control gets a
distinct highlight. A bottom action bar (a sibling view, or drawn) hosts a scale
`SeekBar` (enabled only when a control is selected) and **Save / Reset / Cancel**
buttons.

Touch:
- **Down** on a control → select it (`selectedKey`), record the touch offset,
  seed the scale slider from `working.scale(key)` (or 1× if unset).
- **Move** → update `working` for `selectedKey`: `normCx =
  LayoutEditMath.toNorm(x, w)`, `normCy = toNorm(y, h)`, keeping the current
  scale. Redraw.
- **Down** on empty space → deselect.
- **Scale slider change** → `working.put(selectedKey, currentNormCx,
  currentNormCy, LayoutEditMath.scaleForProgress(progress, max))`. Redraw.
- **Save** → `settings.setControlOverrides(landscape, working)`, hand `working`
  to `EmulatorView`, hide the editor.
- **Reset** → `working.clear()`, redraw (controls snap to defaults); stays in the
  editor so the user sees the reset (Save still required to persist the cleared
  state; Cancel to abort).
- **Cancel** → discard `working`, hide the editor (no persistence change).

The editor edits only the current orientation; to edit the other the user
rotates the device (when not orientation-locked). If orientation is locked
(Phase 4a), only the locked orientation is editable — acceptable and noted in
the UI copy is not required, but the behavior is intentional.

### Component 6 — `InGameMenuView` + `MainActivity` wiring

- `InGameMenuView.Listener` gains `void onEditLayout()`; the menu adds an
  **"Edit layout"** button (same construction as the Phase 4a "Settings" item).
- `MainActivity`:
  - On `onResume`, loads `settings.controlOverrides(false)` and
    `settings.controlOverrides(true)` and pushes **both** to `EmulatorView`.
  - Implements `onEditLayout()`: hide the menu, seed and show the
    `LayoutEditorView` for the current orientation.
  - On editor Save, receives the new overrides and pushes them to `EmulatorView`
    (and they are already persisted by the editor).

### Component 7 — `EmulatorView` runtime application

- Holds two override sets: `portraitOverrides` and `landscapeOverrides`
  (default `EMPTY`), plus `void setControlOverrides(ControlOverrides portrait,
  ControlOverrides landscape)`.
- In `onDraw` and `onTouchEvent`, replaces `ControlLayout.of(w, h)` with
  `ControlLayout.of(w, h, (w > h) ? landscapeOverrides : portraitOverrides)`, so
  a rotation applies the correct set with no reload.

## Data flow

**Edit:** in-game menu → `onEditLayout` → `MainActivity` shows `LayoutEditorView`
seeded with `settings.controlOverrides(currentOrientation)` → user drags/scales →
Save → `settings.setControlOverrides(landscape, working)` + push to
`EmulatorView` → editor hides.

**Play:** `EmulatorView.onDraw`/`onTouchEvent` → `ControlLayout.of(w, h,
orientationOverrides)` → controls drawn and hit-tested from the same overridden
layout.

**Persistence across launch:** `MainActivity.onResume` →
`settings.controlOverrides(false/true)` → `EmulatorView.setControlOverrides(...)`.

## Error handling & edge cases

- **No overrides** → `parse("")` = `EMPTY` → `of(w,h,EMPTY)` = today's layout.
- **Corrupt/partial stored string** → tolerant `parse` yields `EMPTY` or skips
  the bad entry; never throws.
- **Off-screen / overlarge control** → clamp in `of(...)` keeps the box fully on
  screen (and centered if it cannot fit).
- **Overlapping controls after edit** → allowed (free placement); `keysAt` ORs
  all hits, so an intentional overlap registers both — same semantics as today.
- **D-pad override** → `key = 0` is a valid map entry; the moved/scaled D-pad box
  still decomposes into direction bits in `keysAt` because that logic reads the
  (overridden) `Control`.
- **Rotation mid-edit** → the editor is per-orientation; rotating during an edit
  is an accepted rough edge (the working set is for the entered orientation). If
  simple, `LayoutEditorView` may cancel on a config change; otherwise the device
  test just avoids rotating mid-edit.
- **Scale bounds** → `clampScale` keeps 0.5×–2×; `clampNorm` keeps centers in
  `[0,1]` before the pixel clamp.

## Testing

**Pure JVM unit tests**

- `ControlOverrides`: `put`/`has`/`normCx`/`normCy`/`scale` round-trip;
  `clampNorm` (−0.2→0, 1.5→1), `clampScale` (0.1→0.5, 3→2); `serialize`/`parse`
  round-trip incl. multiple controls and the D-pad `key=0`; `parse` of
  empty/garbage → `EMPTY`; `clear` empties.
- `ControlLayout.of(w,h,overrides)`: an override moves the named control's `cx/cy`
  to `norm*extent` and scales its half-extents by `scale`; **un-overridden
  controls are byte-identical to `of(w,h)`**; the chips and game rect are
  identical; clamp keeps a near-edge control on-screen; **`keysAt` returns the
  overridden control's key at its new center and 0 at its old center** (the
  draw==hit-test guarantee); a scaled/moved D-pad still yields direction bits.
- `LayoutEditMath`: `toNorm` (clamped), `scaleForProgress`/`progressForScale`
  round-trip and midpoint ≈ 1×.
- `Settings`: `controlOverrides`/`setControlOverrides` round-trip per orientation
  (portrait and landscape independent); default is `EMPTY`.

**Device (clean AVD, as in prior phases)**

- Import + launch a ROM. Open the in-game menu → **Edit layout**.
- Drag the D-pad to a new position; scale the A button up; Save.
- Confirm the controls **render** at the new spot and **respond** there (inject
  a touch / observe a press at the new location; the old location no longer
  triggers). This is the draw==hit-test device check.
- Relaunch the ROM → the custom layout persists.
- Reset → controls snap back to defaults; Save; confirm persisted default.
- Verify portrait and landscape hold independent layouts (edit one, rotate,
  confirm the other is unaffected) — subject to orientation-lock.
- 0 FATAL/ANR across the flow.

## Files

- **Create:** `ControlOverrides.java`, `ControlOverridesTest.java`,
  `LayoutEditMath.java`, `LayoutEditMathTest.java`, `LayoutEditorView.java`.
- **Modify:** `ControlLayout.java` (+ `ControlLayoutTest.java`),
  `Settings.java` (+ `SettingsTest.java`), `EmulatorView.java`,
  `MainActivity.java`, `InGameMenuView.java`, `res/values/strings.xml`.

## Exit criteria (completes Phase 4 toward v0.5)

- The seven on-screen gamepad controls can be dragged and uniformly scaled in a
  WYSIWYG editor launched from the in-game menu, per orientation.
- Layouts persist across relaunch; Reset restores defaults; portrait and
  landscape are independent.
- No draw/hit-test drift: a moved/scaled control both renders and responds at its
  new box (unit-tested and device-verified).
- Defaults reproduce prior behavior exactly (an install with no overrides is
  unchanged).
- With this slice, remapping (4b), settings (4a), and the touch-layout editor
  together complete the Phase 4 controls surface; the real-hardware Bluetooth
  controller gate (4b) remains the one open item before tagging v0.5.
