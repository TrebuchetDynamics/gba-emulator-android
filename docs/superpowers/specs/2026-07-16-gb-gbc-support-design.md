# Game Boy / Game Boy Color support — Design

**Date:** 2026-07-16
**Status:** Approved design, pending spec review
**Phase:** Multi-console step (GB/GBC targeted for 1.1 in the roadmap).
**Predecessors:** Phase 1–4 (GBA emulator complete through the touch-layout editor).

## Goal

Import and play **Game Boy (DMG)** and **Game Boy Color (GBC)** ROMs alongside
GBA, with correct video dimensions and aspect, a Game-Boy-appropriate on-screen
control set (no L/R), content-based ROM detection, and a per-game console badge —
with **zero regression to GBA**.

## Scope

**In scope**

1. **Enable the GB core** in the native build (mGBA's GB core covers both GB and
   GBC).
2. **Core selection by ROM content:** a pure `RomSystem.detect(byte[])` picks
   `GBA` vs `GB`; native creates `mPLATFORM_GBA` or `mPLATFORM_GB` accordingly.
3. **Variable video dimensions:** query the core's real dimensions (GB/GBC =
   160×144) instead of hard-coding 240×160, through the whole render path.
4. **Control set + aspect:** `ControlLayout` uses the loaded ROM's aspect (10:9
   for GB/GBC, 3:2 for GBA) and omits L/R for Game Boy.
5. **Import & library:** accept `.gb`/`.gbc` (raw and inside zips), store the
   detected system, and show a GB / GBC / GBA badge per game.

**Out of scope (deferred / not built)**

- DMG monochrome **palette / tint chooser** — use mGBA's default rendering (GBC
  games are already full colour).
- Super Game Boy borders, colourization of DMG games, per-game palettes.
- Game Boy **link cable** / multiplayer.
- A second, rebalanced GB touch layout — GB simply hides L/R and keeps the other
  controls where they are.
- Game Boy BIOS/boot-ROM handling — mGBA boots GB/GBC without a boot ROM.

## Global constraints

- Java + C (JNI), Android `minSdk 24` / `targetSdk 35`, package
  `com.trebuchetdynamics.emulator.app` (app) and `...emulator.mgba` (core).
  Programmatic Views only.
- **No GBA regression:** an unchanged GBA ROM imports, plays, saves, and renders
  exactly as it does today (240×160, 3:2, L/R present).
- mGBA is used **unmodified** (vendored at `vendor/mgba`); this slice only flips
  the `M_CORE_GB` build option and calls existing mGBA APIs — no patches to the
  emulator core.
- Pure logic (`RomSystem.detect`, `ControlLayout` geometry) has no `android.*`
  imports and is JVM-tested. `ControlLayout` already forbids `android.*`.
- Key bitmasks are shared: mGBA's GB and GBA key enums both use bit positions
  0–7 for A/B/SELECT/START/RIGHT/LEFT/UP/DOWN; L (bit 9) and R (bit 8) exist only
  on GBA and are ignored by the GB core. The existing `MgbaSession.KEY_*`
  constants are therefore correct for both; passing an L/R bit to a GB core is a
  safe no-op.
- Commit trailer on every commit: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Current state (what exists)

- **Native** (`mgba-android/core/src/main/cpp/mgba_android.c`): `nativeCreate`
  calls `mCoreCreate(mPLATFORM_GBA)` (fixed), sets a video buffer with a fixed
  `VIDEO_STRIDE`, and hard-codes 240×160. `nativeLoadRom(byte[])` /
  `nativeLoadRomFile(path)` call `core->loadROM`. `runFrame` copies
  `session->video` to an ARGB `int[]`. `MgbaCore.canCreateGbaCore` is a JNI probe.
- **CMake** (`mgba-android/core/CMakeLists.txt`): `M_CORE_GBA ON`, **`M_CORE_GB
  OFF`**, `LIBMGBA_ONLY ON`; adds `vendor/mgba` as a subdirectory and links
  `mgba`.
- **`MgbaSession`** (Java): constants `VIDEO_WIDTH=240`, `VIDEO_HEIGHT=160`,
  `FRAME_PIXELS`; `KEY_*` bit constants (A=1<<0 … DOWN=1<<7, R=1<<8, L=1<<9);
  `loadRom(byte[])`, `loadRom(File)`, `runFrame(keys, argb, audio)`, save states.
- **`EmulationRunner`**: allocates `int[] pixels = new int[MgbaSession.FRAME_PIXELS]`
  and calls `session.runFrame(view.keys(), pixels, audio)`, then
  `view.publishFrame(pixels)`.
- **`EmulatorView`**: draws a game bitmap and the on-screen controls, both from a
  per-frame `ControlLayout.of(w,h, overrides)`; the game rect aspect comes from
  `ControlLayout`, which reads `MgbaSession.VIDEO_WIDTH/HEIGHT`.
- **`ControlLayout`** (pure): `of(width, height[, overrides])` builds the game
  rect (sized to the GBA 3:2 aspect via `MgbaSession.VIDEO_WIDTH/HEIGHT`), the
  chips, and a `controls` list including L (key 512) and R (key 256).
- **`RomImporter` / `RomArchive` / `RomLibrary`**: import `.gba` (and single-ROM
  zips) into private storage; `romId` = SHA-256 hex; meta stores display name and
  last-played time.

## Architecture

### Component 1 — `RomSystem` (pure, JVM-tested)

`enum RomSystem { GBA, GB, GBC, UNKNOWN }` with a static detector:

- `static RomSystem detect(byte[] rom)`:
  - **GBA:** the fixed byte `0x96` at offset `0xB2` (the GBA logo/fixed value)
    and a plausible header length → `GBA`.
  - **GB/GBC:** the 48-byte Nintendo logo at `0x104`–`0x133` matches, and the
    header is at `0x134`–`0x14F`; the CGB flag at `0x143` (`0x80` or `0xC0`)
    distinguishes `GBC` from `GB`.
  - Otherwise `UNKNOWN`.
- For **core selection**, GB and GBC both map to the GB core, so a helper
  `boolean isGameBoy()` (GB or GBC) is what native needs; the GBC-vs-GB
  distinction is used only for the library badge (the GB core itself switches to
  GBC mode from the ROM header).

No `android.*` imports. `detect` is total (never throws; short ROMs → `UNKNOWN`).

Ownership: consumed by import validation, the library badge, and (as a platform
selector) `MgbaSession`/native.

### Component 2 — Native: core-by-platform + variable video

- **CMake:** `set(M_CORE_GB ON ...)`. mGBA compiles its GB core into `libmgba`.
- **Core creation moves to load time by platform.** `nativeCreate` allocates the
  session but no longer fixes the platform; a new native entry point creates the
  core for a given platform — e.g. `nativeLoadRom(handle, bytes, platform)` /
  `nativeLoadRomFile(handle, path, platform)` where `platform` is `0` = GBA, `1`
  = GB (passed from Java via `RomSystem`). Native maps it to `mPLATFORM_GBA` /
  `mPLATFORM_GB`, creates + inits the core, applies the existing `mCoreOptions`,
  then `loadROM`.
- **Variable dimensions:** after core creation, call
  `core->desiredVideoDimensions(core, &w, &h)`, store `w`/`h` on the session,
  allocate the video buffer to `w*h` (stride = `w`), and `setVideoBuffer`
  accordingly. `runFrame`'s ARGB conversion iterates the session's real `w*h`.
  The GBA path yields 240×160 exactly as before.
- **`canCreateGbCore`** JNI probe mirrors `canCreateGbaCore` (used by the
  instrumented test to confirm the GB core linked).
- Save/state paths are dimension-independent and unchanged.

### Component 3 — `MgbaSession`: dimensions per ROM + platform

- Replace the fixed `VIDEO_WIDTH/HEIGHT/FRAME_PIXELS` constants with instance
  accessors valid after `loadRom`: `int videoWidth()`, `int videoHeight()`,
  `int framePixels()`. Keep a `MAX_FRAME_PIXELS` constant (GBA 240×160) only
  where a fixed upper bound is genuinely needed (e.g. a reusable buffer).
- `loadRom(...)` takes the target `RomSystem` (or an `isGameBoy` boolean /
  platform int) and forwards it to the native load call; after load, dimensions
  are queried from native.
- Add `RomSystem system()` / `boolean isGb()` for the UI. `KEY_*` constants
  unchanged.

### Component 4 — `EmulationRunner`: dynamic frame buffer

Allocate `pixels` from `session.framePixels()` **after** `loadRom` (not from a
compile-time constant), and pass the ROM's `RomSystem`/platform into `loadRom`.
The audio path is unchanged (GB/GBC audio uses the same sample pipeline).

### Component 5 — `ControlLayout`: aspect + shoulder toggle

Extend the builder to take the game's source dimensions and a shoulder flag:
`of(width, height, overrides, srcW, srcH, hasShoulders)`. The 2-arg / 3-arg
overloads delegate with the GBA defaults (`srcW=240, srcH=160,
hasShoulders=true`) so **existing call sites and tests are unaffected**.

- The game rect is sized to `srcW:srcH` (10:9 for GB/GBC, 3:2 for GBA).
- When `hasShoulders == false`, L and R are omitted from the `controls` list (and
  therefore not drawn and not hit-tested); the D-pad, A, B, SELECT, START keep
  their computed positions.
- Overrides remain keyed by control id and normalized, so the **same
  per-orientation overrides apply to both consoles**; L/R overrides are simply
  irrelevant when shoulders are hidden. No per-console override storage.

### Component 6 — `EmulatorView` glue

After a ROM loads, the view learns the ROM's dimensions and system (from
`MgbaSession` via the runner / `MainActivity`), sizes the game bitmap to
`videoWidth()×videoHeight()`, and builds `ControlLayout.of(w, h, overrides,
srcW, srcH, hasShoulders=!isGb)`. Both drawing and hit-testing continue to derive
from that one layout (the single-source invariant is preserved). Scaling
(integer / fill) already keys off the source dimensions.

### Component 7 — Import & library

- **Picker:** the SAF `ACTION_OPEN_DOCUMENT` filter accepts `.gb` and `.gbc` in
  addition to `.gba` (and zips).
- **Validation & extraction:** `RomImporter` / `RomArchive` accept a raw or
  zipped `.gb`/`.gbc`/`.gba`, validating "is this a real ROM" via
  `RomSystem.detect(...) != UNKNOWN` (content, not extension).
- **Library meta:** store the detected `RomSystem` per game;
  `LibraryActivity` shows a small **GB / GBC / GBA** badge on each row. `romId`
  (SHA-256) continues to namespace saves and states, so no save collisions across
  systems.

## Data flow

**Import:** pick file → `RomSystem.detect(bytes)` (reject `UNKNOWN`) → import to
private storage, record display name + system in `RomLibrary`.

**Play:** tap a game → `MainActivity` reads the ROM + its system →
`EmulationRunner` calls `session.loadRom(file, system)` → native creates the
matching core, loads, reports dimensions → `EmulatorView` sizes the bitmap and
builds `ControlLayout` with the ROM's aspect and `hasShoulders=!isGb` → play.

**Render:** each frame, `runFrame` fills `session.framePixels()` ARGB pixels at
the ROM's dimensions → `publishFrame` blits into the correctly-sized bitmap.

## Error handling & edge cases

- **Unknown / corrupt ROM** → `detect` returns `UNKNOWN` → import is rejected
  with a message; play never reaches a bad core.
- **GB core failed to link** (build misconfig) → `canCreateGbCore` is false; the
  instrumented test fails loudly; a GB load surfaces the existing load-failure
  path (no crash).
- **Dimension mismatch** → dimensions are always read from the core after load,
  so the buffer and bitmap can never disagree with the core.
- **L/R pressed on a GB game** → the bits are not produced (controls hidden) and
  would be ignored by the GB core anyway.
- **A GBA layout override for L/R, then a GB game** → ignored (shoulders hidden);
  the override persists for when a GBA game is played again.
- **GBC-only ROM on the GB core** → mGBA's GB core enters GBC mode from the
  header; no special handling needed.

## Testing

**Pure JVM unit tests**

- `RomSystem.detect`: a GBA header (0x96 at 0xB2) → `GBA`; a GB header (logo +
  CGB flag 0x00) → `GB`; a GBC header (CGB flag 0x80/0xC0) → `GBC`; random/short
  bytes → `UNKNOWN`; `isGameBoy()` true for GB and GBC, false for GBA.
- `ControlLayout` with `srcW=160, srcH=144, hasShoulders=false`: the `controls`
  list contains **no** L or R; the game rect matches the 10:9 aspect; `keysAt`
  still resolves A/B/D-pad/SELECT/START; and the GBA default overloads
  (`hasShoulders=true`, 240×160) are byte-identical to today (no regression).

**Instrumented / on-device**

- `canCreateGbCore` returns true (GB core linked).
- A GB and a GBC homebrew ROM (MIT/homebrew, sourced like the existing
  `hello.gba`) import, are detected/badged correctly, and **run** at 160×144 with
  L/R hidden and the 10:9 aspect; a GBA ROM still runs at 240×160 with L/R.
- 0 FATAL/ANR.

## Files

- **Modify:** `mgba-android/core/CMakeLists.txt` (`M_CORE_GB ON`);
  `mgba-android/core/src/main/cpp/mgba_android.c` (platform-selected core,
  variable video, `canCreateGbCore`);
  `mgba-android/core/src/main/java/.../mgba/MgbaSession.java` (variable dims +
  platform); `.../mgba/MgbaCore.java` (add `canCreateGbCore`).
- **Create:** `.../app/RomSystem.java` + `RomSystemTest.java`.
- **Modify:** `ControlLayout.java` (+ `ControlLayoutTest.java`);
  `EmulatorView.java`; `EmulationRunner.java`; `MainActivity.java`;
  `RomImporter.java`; `RomArchive.java`; `RomLibrary.java`;
  `LibraryActivity.java`; the SAF picker filter.
- **Test assets:** a GB and a GBC homebrew test ROM under the core's
  `androidTest/assets`.

## Exit criteria (multi-console step toward 1.1)

- Game Boy and Game Boy Color ROMs import (content-detected, badged) and play
  correctly — 160×144, 10:9 aspect, L/R hidden — through the same library/menu/
  save-state flow as GBA.
- GBA is fully unaffected (dimensions, aspect, controls, saves).
- The GB core is confirmed linked on device; pure detection and layout logic are
  JVM-tested; 0 FATAL/ANR on device.
- Deferred: DMG palette chooser, SGB borders, link cable — future polish.
