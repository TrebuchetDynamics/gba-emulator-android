# Changelog

All notable changes to Garnacha Boy are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
- Completed the repository-wide Garnacha Boy rebrand across Android, desktop,
  web, libretro, package metadata, CI artifacts, documentation, and icon assets.
- Removed the unused legacy emulator clients and made the mGBA Android product
  the repository's sole supported build path.

## [0.5.0] — 2026-07-16

The daily-driver release: a game library, in-game save states, a full settings
screen, controller remapping, and a customizable on-screen layout. This entry
covers everything since 0.1.0 (the 0.2–0.4 milestones were internal and were not
cut as separate public builds).

### Added
- **In-game menu overlay:** pause without leaving the game to save or load any of
  four state slots, toggle fast-forward, or reset the console.
- **ROM library:** import and keep multiple ROMs, most-recently-played ordering,
  resume by tapping, and delete a game together with its saves and save states.
- **Settings screen** (grouped Video / Audio / Controls / Emulation): orientation
  lock (auto / portrait / landscape), integer-crisp vs fill scaling, sound on/off
  and volume, vibrate-on-touch, idle control opacity, and fast-forward speed.
- **Gamepad remapping:** press-to-bind any controller or keyboard button to a GBA
  input, persisted, with reset-to-defaults.
- **Frameskip:** optional 1–3 frame skip for weaker hardware (the emulation core
  still runs every frame; only the video blit is skipped).
- **Touch-layout editor:** drag to reposition and a slider to resize the on-screen
  controls, saved independently per orientation.

### Changed
- **Immersive play feel:** landscape controls fade when idle and re-assert on
  touch for an edge-to-edge view; crisp integer/aspect scaling with correct
  letterboxing; haptic feedback on button presses.

### Known limitations
- Bluetooth / USB controllers remain untested on physical hardware — remapping is
  validated via injected key events, but real-pad pairing is unverified.
- Battery life is unmeasured.
- Tested primarily on a Snapdragon 8 Gen 3 device and a clean emulator; low-end
  hardware is unverified.

## [0.1.0] — 2026-07-14

The first public build. A working Game Boy Advance emulator: import a ROM, play
it, and your cartridge saves persist.

### Added
- GBA emulation via mGBA 0.10.5, used unmodified, with 48 kHz audio.
- ROM import through Android's document picker into private app storage,
  including transparent import of zip-packaged ROMs.
- On-screen touch controls with distinct portrait and landscape layouts.
- Cartridge save persistence.
- Open-source notices screen carrying mGBA's MPL-2.0 attribution.

### Known limitations
- No ROM library — one ROM at a time. (Coming in 0.2.)
- No settings, control remapping, or save-state UI. (Coming in 0.3.)
- Bluetooth controllers are untested.
- Battery life is unmeasured.
- Tested on one device (Snapdragon 8 Gen 3). Low-end hardware is unverified.

No game ROMs or BIOS files are included. Supply only content you are legally
entitled to use.
