# Custom Macro Buttons Design

**Date:** 2026-07-20  
**Status:** Approved design; implementation not started

## Goal

Let players add up to eight custom touch buttons per orientation. Each button
can emit any non-empty combination of GBA inputs and can optionally pulse the
whole combination at 15 presses per second while held.

Examples include `Left + A`, `Up + B`, `Right + R`, Turbo A, Turbo B, and larger
combinations. Normal play remains unchanged until the player adds a macro.

## Decisions

- Macro buttons are configured independently for portrait and landscape.
- Each orientation supports at most eight macro buttons.
- Turbo pulses the complete selected combination at 15 presses per second.
- Reset restores the stock layout and removes all macro buttons for the active
  orientation.
- Back cancels all editor changes; the checkmark saves them together.
- Empty and duplicate macro definitions are rejected with clear feedback.
- No predefined macro catalog, gesture system, adjustable turbo rate, or
  hardware-controller macros are included in this slice.

## Existing seams

The implementation extends existing code rather than creating a second control
system:

- `ControlLayout` is already the single source of truth for drawn and touchable
  controls (`mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlLayout.java:20-307`).
- `ControlOverrides` already persists normalized position and scale by integer
  control identity (`mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlOverrides.java:17-118`).
- `LayoutEditorView` already owns direct selection, dragging, resizing, opacity,
  reset, save, and cancellation through Back
  (`mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LayoutEditorView.java:19-271`).
- `EmulatorView` already combines touch and hardware input before each emulated
  frame (`mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java:16-385`).
- `Settings` already stores separate portrait and landscape layout records
  (`mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/Settings.java:165-174`).

## Data model

Add a pure, JVM-testable `MacroControls` value type.

Each macro stores:

- a stable slot ID from 1 through 8;
- a non-empty mGBA key bitmask;
- a Turbo boolean.

The layout identity is derived from a reserved high integer range plus the slot
ID. It never overlaps stock mGBA key masks. Position and scale therefore keep
using `ControlOverrides` without changing its serialization.

Macro definitions use their own deterministic versionless record:

```text
slot:keyMask:turbo,slot:keyMask:turbo
```

Parsing drops malformed, out-of-range, empty-mask, and duplicate-slot records.
Adding rejects a duplicate `(keyMask, turbo)` definition. Stored order is slot
order, keeping labels and output deterministic.

`Settings` stores one macro string for portrait and one for landscape. Saving
from the editor writes the macro string and matching override string through a
single `SharedPreferences.Editor.apply()` call so the visible controls and
positions cannot diverge.

## Runtime input flow

`ControlLayout` appends macro controls to the existing stock-control list. A
macro is rendered as a compact pill with a generated short label such as
`← + A`, `A + B`, or `A ⚡`. Its accessibility description uses full words,
for example `Turbo A` or `Left plus A`.

Touch input is split into two channels:

1. normal held keys;
2. Turbo macro keys.

Stock touch controls and hardware controls remain in the normal channel. Every
emulated frame, `EmulatorView` combines normal keys with Turbo keys only during
the active half of a fixed two-frames-on/two-frames-off cadence. At nominal
60 FPS this produces 15 complete pulses per second. If a normal button and a
Turbo macro are held together, the normal button remains continuously held and
only the macro mask pulses.

The runner continues passing one ordinary mGBA key bitmask into the existing
native API. No JNI or core changes are required.

## Editor interaction

The contextual strip above the current editor toolbar gains **Add** and
**Delete** actions.

### Add

**Add** opens one focused dialog containing:

- multi-select rows for Left, Up, Right, Down, A, B, L, R, Select, and Start;
- one independent Turbo checkbox;
- Cancel and Add actions.

Add remains disabled until at least one input is selected. At eight macros it
is disabled and explains the orientation limit. A duplicate definition remains
in the dialog with an inline error instead of silently selecting an existing
button.

On success, the macro appears at screen center, becomes selected, and receives
an initial 100% scale override. The player can immediately drag or resize it.

### Selection and deletion

The status strip shows the selected definition and layout values, for example:

```text
Left + A · Turbo · size 100% · opacity 24%
```

Delete is enabled only for a selected macro. Stock buttons cannot be deleted.
Deletion removes both the working macro definition and its working override.

### Save, reset, and cancel

- Checkmark: save macros, overrides, and opacity; return to play.
- Reset: clear working macros and overrides for the active orientation.
- Back: discard the complete working copy and return to the saved layout.

## Validation and errors

Validation occurs before persistence and again while parsing stored data:

- key mask must contain only supported GBA input bits and cannot be empty;
- slot must be 1 through 8;
- each slot and each `(mask, turbo)` definition must be unique;
- malformed stored records are ignored without preventing play;
- the editor reports empty, duplicate, and limit errors in place.

No combination is otherwise forbidden, including opposing directions, because
the requested contract is arbitrary combinations and mGBA already accepts a
key bitmask.

## Tests

Minimum automated coverage:

1. `MacroControlsTest`
   - add/remove and eight-item limit;
   - deterministic serialization round trip;
   - malformed and duplicate records;
   - stable layout identities and generated labels.
2. `ControlLayoutTest`
   - macro geometry appears in portrait and landscape;
   - macro position/scale follows `ControlOverrides`;
   - normal and Turbo hit channels remain separate;
   - stock controls and hit areas remain unchanged.
3. `FeelMathTest` or an equivalent pure input test
   - two frames on, two frames off;
   - full combination pulses together;
   - normal held keys remain continuous.
4. Existing unit, lint, assemble, host-native, and connected-device checks.
5. Device walkthrough in both orientations: add, drag, resize, Turbo hold,
   delete, reset, cancel, save, restart, and controller-only mode.

## Non-goals

- Adjustable or per-button Turbo frequency.
- Recorded input sequences, delays, loops, or scripting.
- Hardware-controller macros.
- More than eight macros per orientation.
- Sharing one macro layout between orientations.
- Skin, icon-pack, or artwork support.
