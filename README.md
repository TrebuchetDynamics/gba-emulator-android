# GARNACHA BOY

**A free, ad-free, offline Game Boy, Game Boy Color, and Game Boy Advance emulator for Android.**

[![Android CI](https://github.com/TrebuchetDynamics/gba-emulator-android/actions/workflows/deploy_android.yml/badge.svg)](https://github.com/TrebuchetDynamics/gba-emulator-android/actions/workflows/deploy_android.yml)
[![Release](https://github.com/TrebuchetDynamics/gba-emulator-android/actions/workflows/release.yml/badge.svg)](https://github.com/TrebuchetDynamics/gba-emulator-android/actions/workflows/release.yml)

Garnacha Boy keeps the game in focus: import your own ROMs, play with touch or physical controls, and keep cartridge saves and save states entirely on-device. No games, proprietary BIOS files, ads, telemetry, or online account are included.

## Highlights

- Game Boy, Game Boy Color, and Game Boy Advance playback through canonical mGBA
- Private, offline ROM library with `.gb`, `.gbc`, `.gba`, and ZIP import
- Touch controls, controller remapping, haptics, and editable portrait/landscape layouts
- Cartridge saves, four manual save-state slots, rotating autosave/resume, and rewind
- Fast-forward, screenshots, scaling and palette controls
- Android 7.0+ (`minSdk 24`), with arm64 and x86_64 builds

## Build and test the Android product

Requirements: JDK 17, Android SDK 35, NDK `22.1.7171670`, CMake `3.18.1`, and Ninja.

```sh
git submodule update --init --recursive
mgba-android/gradlew -p mgba-android clean lintDebug \
  :app:testDebugUnitTest :app:assembleBenchmark \
  :core:assembleBenchmark :core:assembleDebugAndroidTest

cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/mgba-smoke
ctest --test-dir build/mgba-smoke --output-on-failure
```

Outputs:

- Android APK: `mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk`
- reusable mGBA AAR: `mgba-android/core/build/outputs/aar/core-benchmark.aar`

The benchmark APK is optimized but debug-signed. Tagged production releases are built by [`.github/workflows/release.yml`](.github/workflows/release.yml) and fail closed unless the release-signing secrets are configured.

See [`mgba-android/README.md`](mgba-android/README.md) for implementation and validation details, and [`MVP.md`](MVP.md) for the product build contract.

## Project layout

| Path | Purpose |
|---|---|
| `mgba-android/app/` | Primary Garnacha Boy Android application |
| `mgba-android/core/` | Owned JNI boundary around pinned, unmodified mGBA |
| `vendor/mgba/` | mGBA `0.10.5` submodule (MPL-2.0) |
| `docs/` | Architecture decisions, validation receipts, and design research |

## Core and licensing

Garnacha Boy uses canonical [mGBA](https://github.com/mgba-emu/mgba) `0.10.5` under MPL-2.0. The pinned submodule is kept unmodified, and its license ships in the app and AAR notices. See [`LICENSE`](LICENSE), [`ACKNOWLEDGMENTS.md`](ACKNOWLEDGMENTS.md), and [`docs/adr/0001-mgba-only-core.md`](docs/adr/0001-mgba-only-core.md).

Garnacha Boy is not affiliated with or endorsed by Nintendo or mGBA.
