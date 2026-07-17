# GB/GBC support — device verification (2026-07-16)

Device: **clean x86_64 AVD** (`game-emulator-mvp`) — used because the physical
device's foreground contention blocked prior interactive verification.
Build: `app-benchmark.apk` from commit `406c0f72` (Task 6 + the GB-dimensions fix).
Test ROMs (added under `mgba-android/core/src/androidTest/assets/`, provenance in
that dir's `NOTICE.md`):
- `hello.gb` — blargg `dmg_sound/01-registers` (DMG, CGB flag 0x00), public domain.
- `hello.gbc` — blargg `interrupt_time` (CGB flag 0xC0), public domain.
- `hello.gba` — the existing MIT jsmolka test ROM.

## Confirmed on device

1. **Content-detected badges** (`g08-gbc-library.png`): all three imports show the
   correct system badge — **GBC**, **GB**, **GBA** — from `RomSystem.detect` on the
   header, not the filename. A legacy GBA entry (imported before this feature)
   correctly defaults to the **GBA** badge. **PASS**
2. **Game Boy plays** (`g07-gb-play2.png`): the DMG ROM runs — blargg
   `01-registers` renders "01-registers / Passed" — at a near-square **10:9** game
   area (160×144) with **no L/R** shoulder buttons (only D-pad, A, B, SELECT,
   START). DMG monochrome (mGBA default). **PASS**
3. **Game Boy Color plays** (`g09-gbc-play.png`): the CGB ROM runs — blargg
   `interrupt_time` renders "interrupt time … Passed" — at 160×144, 10:9, no L/R.
   The CGB ROM is loaded into the GB core in Game Boy Color mode. *(This
   particular test ROM emits only monochrome text — it is a timing test, not a
   colour demo — so GBC colour output was not visually demonstrated; correct CGB
   execution and dimensions were.)* **PASS**
4. **No GBA regression** (`g10-gba-regression.png`): the GBA ROM still plays
   "Hello world!" at the wide **3:2** aspect (240×160) with **L and R present** —
   identical to before this slice. **PASS**
5. **No crashes**: **0 FATAL / ANR** for `com.trebuchetdynamics.garnacha` across
   the whole session (import ×3, play GB/GBC/GBA).

## Bug found and fixed during verification

The first GB play attempt failed with "Could not initialize the mGBA core"
(`g04-gb-play.png`). Root cause (`vendor/mgba/src/gb/core.c:360-365`): mGBA's GB
core reports the **256×224** Super-Game-Boy border canvas from
`desiredVideoDimensions` **until a ROM is loaded and SGB borders are off** — it
only reports the real 160×144 afterwards. Task 1 queried dimensions at core
*creation* (pre-load), got 224 rows, and tripped the buffer-overflow guard
(`h > 160`). (`canCreateGbCore` passed because it never queries dimensions; the
instrumented tests therefore did not catch it.)

Fix (commit `406c0f72`): (a) disable `sgb.borders` in the core config — SGB
borders are out of scope and a 224-row render would overflow the fixed 256×160
buffer; (b) move the dimension query + guard from `setupCore` (pre-load) into the
load path (after `loadROM`+`reset`); (c) read `videoWidth()`/`videoHeight()` live
after load in Java rather than caching them in the constructor. GBA is unaffected
(it reports 240×160 post-load and `sgb.borders` is GB-only). After the fix, GB
reports 160×144 post-load and plays correctly (verified above).

## Result

GB and GBC ROMs import (content-detected, badged) and play correctly at 160×144 /
10:9 with L/R hidden, through the same library/menu/save-state flow as GBA, with
zero GBA regression and 0 FATAL/ANR. The multi-console step is verified on device.
Deferred: DMG palette chooser, Super Game Boy borders, link cable.
