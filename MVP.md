# Android emulator MVP

This repository contains a local SkyEmu fork and an isolated custom Android product built on mGBA.

## Build the installable SkyEmu MVP

Requirements: JDK 17, Android SDK 35, NDK `22.1.7171670`, and CMake `3.18.1`.

```sh
git submodule update --init --depth 1
cd tools/android_project
./gradlew clean lintDebug assembleDebug
```

APK: `tools/android_project/app/build/outputs/apk/debug/com.trebuchetdynamics.skyemu-v1-debug.apk`

Fork-specific changes:

- application ID `com.trebuchetdynamics.skyemu`;
- target API 35;
- no repository-stored release keystore or hard-coded signing password;
- no legacy broad storage permissions;
- selected files are copied through Android's document picker into private app storage;
- CI builds and uploads an unsigned debug artifact.

No games or proprietary BIOS files are included. Supply only content you are authorized to use.

## Build the custom mGBA product

```sh
tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:assembleBenchmark :core:assembleBenchmark :core:assembleDebugAndroidTest
```

Outputs:

- optimized test APK: `mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk`
- optimized AAR: `mgba-android/core/build/outputs/aar/core-benchmark.aar`

The installable benchmark APK uses `-O2` native code and an Android debug key; it is for performance testing, not production distribution.

The custom app loads user-selected GBA ROMs, renders mGBA frames, streams audio, maps touch/gamepad input, and persists cartridge saves. See `mgba-android/README.md` and `docs/adr/0001-dual-core-strategy.md`.

## Upstreams and licenses

- SkyEmu: <https://github.com/skylersaleh/SkyEmu>, MIT (`LICENSE`), tracked as Git remote `upstream`.
- mGBA: <https://github.com/mgba-emu/mgba>, MPL-2.0, pinned as `vendor/mgba` submodule; its license is packaged in the core AAR.
