# Changelog

All notable changes to Garnacha Boy are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

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
