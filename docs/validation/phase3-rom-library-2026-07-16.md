# Phase 3 ROM library — device verification (2026-07-16)

Device: **clean x86_64 AVD** (`game-emulator-mvp`, 1080×2400) — used because the
physical test device's foreground contention and swipe lockscreen blocked
interactive automation across the prior phases. The APK ships `arm64-v8a` +
`x86_64`, so the x86_64 build runs natively on the AVD.
Build: `app-benchmark.apk` from commit `516f4bf0`.

## Confirmed on device — the full library flow, end to end

1. **Empty state** (`phase3-library-empty.png`): a fresh install opens to the
   `LibraryActivity` launcher — "Garnacha Boy" title, an "Import ROM" button,
   and the centered "No games yet. Tap Import to add a GBA ROM." empty state.
2. **Import** (`phase3-library-imported.png`): tapping **Import ROM** opens the
   SAF document picker; selecting the MIT `hello.gba` test ROM adds a row that
   reads **"hello"** (the document display name, with the `.gba` extension
   correctly stripped) and **"0 minutes ago"** (relative last-played time). This
   exercises RomImporter → `RomLibrary.record` → `list()` → row rendering.
3. **Play** (`phase3-library-play.png`): tapping the "hello" row launches the
   player via `EXTRA_ROM_ID` and the ROM runs — "Hello world!" renders. The
   player is reached only through the library now.
4. **Back returns to the library**: the system Back button from the player
   returns to `LibraryActivity` (natural back stack).
5. **Delete with confirm** (`phase3-library-delete.png`): long-pressing the row
   shows a native confirm dialog — "Delete game? This removes **hello** and its
   saves and save states. This cannot be undone." (the display name is correctly
   interpolated). Tapping **DELETE** removes the row and the library returns to
   the empty state; the deleted game does not reappear.
6. **No crashes**: 0 FATAL / ANR entries for `com.trebuchetdynamics.garnacha`
   across the whole flow.

### Bonus — Phase 2 confirmed as a side effect

The clean AVD also confirmed two Phase 2 behaviors that the hostile physical
device had blocked: in the `phase3-library-play.png` shot the on-screen controls
are **faded** (the game had run untouched for several seconds → idle fade), and
the "Hello world!" glyphs show **hard pixel edges** (nearest-neighbor / integer
scaling), with no blur.

## Covered by unit tests (not separately re-driven on device)

- **Last-played re-ordering** — a single ROM was imported, so multi-ROM
  ordering wasn't driven in the UI, but it is unit-tested
  (`RomLibraryTest.listSortsMostRecentlyPlayedFirst`, `touchBumpsOrder`).
- **Cascade-delete of the ROM's `.sav` and save-state files** — the benchmark
  build is non-debuggable so `run-as` couldn't inspect the private dirs, but the
  UI confirmed the row is gone and stays gone, and the cascade is unit-tested
  (`RomLibraryTest.deleteRemovesRomSavesStatesAndIndex`) and the delete flow was
  code-reviewed.

## Result

Phase 3 is verified: the app is library-first, importing/playing/deleting all
work on-device, and no code defect was observed. 0 FATAL/ANR.
