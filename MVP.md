# Garnacha Boy build guide

This repository contains two deliberately isolated emulator clients. The primary Android product uses mGBA; a legacy cross-platform client retains the original SkyEmu architecture and MIT lineage. No APK packages both cores.

## Build the primary Android product

Requirements: JDK 17, Android SDK 35, NDK `22.1.7171670`, CMake `3.18.1`, and Ninja.

```sh
git submodule update --init --recursive
tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:testDebugUnitTest :app:assembleBenchmark \
  :core:assembleBenchmark :core:assembleDebugAndroidTest
```

Outputs:

- optimized Garnacha Boy APK: `mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk`
- reusable mGBA AAR: `mgba-android/core/build/outputs/aar/core-benchmark.aar`

The app ID is permanently `com.trebuchetdynamics.garnacha`. The benchmark APK uses optimized native code and Android's debug signing key; it is not a production release. The app imports user-selected `.gb`, `.gbc`, `.gba`, and ZIP files into private storage, renders and plays audio through mGBA, supports touch and physical controls, and persists cartridge saves and save states.

Host smoke tests:

```sh
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/mgba-smoke
ctest --test-dir build/mgba-smoke --output-on-failure
```

See [`mgba-android/README.md`](mgba-android/README.md) for implementation details.

## Build the legacy SkyEmu-derived client

This path is retained to keep the fork's original cross-platform implementation buildable and independently attributable. Product-facing names and artifacts use Garnacha Boy, while compatibility-sensitive Java/JNI namespaces, preference paths, cache signatures, and upstream references remain unchanged.

```sh
cd tools/android_project
./gradlew clean lintDebug assembleDebug
```

APK: `tools/android_project/app/build/outputs/apk/debug/garnacha-boy-legacy-v1-debug.apk`

Legacy-client properties:

- application ID `com.trebuchetdynamics.skyemu`, retained for install/data compatibility;
- target API 35;
- no repository-stored release keystore or hard-coded signing password;
- no legacy broad storage permissions;
- selected files are copied through Android's document picker into private app storage;
- CI builds and uploads a debug artifact.

The cross-platform CMake client can also be built with:

```sh
cmake -S . -B build/garnacha-boy
cmake --build build/garnacha-boy
```

The native executable is `GarnachaBoy`; the libretro target is `garnachaboy_libretro`.

## Upstreams and licenses

- [SkyEmu](https://github.com/skylersaleh/SkyEmu): MIT, preserved in [`LICENSE`](LICENSE), tracked as Git remote `upstream`.
- [mGBA](https://github.com/mgba-emu/mgba): MPL-2.0, pinned as `vendor/mgba`; its license is packaged in the Android core AAR and app notices.

No games or proprietary BIOS files are included. Supply only content you are authorized to use.
