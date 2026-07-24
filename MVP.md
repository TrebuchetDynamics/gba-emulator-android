# Garnacha Boy build guide

This repository contains one emulator product: the Garnacha Boy Android app and its reusable mGBA core library. The app uses canonical mGBA from `vendor/mgba`; no second emulator core is built.

## Build and test

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

- optimized APK: `mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk`
- reusable mGBA AAR: `mgba-android/core/build/outputs/aar/core-benchmark.aar`

The benchmark APK uses Android's debug signing key and is not a production release. Tagged releases are built by [`.github/workflows/release.yml`](.github/workflows/release.yml) and fail closed unless release-signing secrets are configured.

## Architecture and licensing

- `mgba-android/app/` is the Garnacha Boy Android application.
- `mgba-android/core/` is the owned JNI boundary around pinned, unmodified mGBA.
- `vendor/mgba/` is the mGBA `0.10.5` submodule under MPL-2.0.
- `docs/` contains architecture decisions and validation receipts.

No game or proprietary BIOS content is bundled. Supply only content you are authorized to use.
