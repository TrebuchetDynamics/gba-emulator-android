# Custom mGBA Android product

This project is the product-owned Android path around canonical mGBA. It remains isolated from the SkyEmu MVP so the app never packages two emulator cores.

## Current scope

- pins mGBA `0.10.5` at commit `26b7884bc25a5933960f3cdcd98bac1ae14d42e2`;
- builds only the GBA core for `arm64-v8a` and `x86_64`;
- exposes owned JNI session APIs for memory/file ROM loading, 240×160 ARGB frames, 48 kHz stereo PCM, key input, save states, and cartridge savedata;
- provides a custom APK with atomic private-file document import, no retained Java ROM copy, touch/gamepad controls, `AudioTrack` output, lifecycle restart, and atomic private savedata persistence;
- packages the unmodified mGBA MPL-2.0 license in the AAR;
- uses only MIT-licensed `gba-tests` ROMs in instrumentation tests.

No game or proprietary BIOS content is bundled in the application.

## Build and test

```sh
git submodule update --init --depth 1
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/mgba-smoke
ctest --test-dir build/mgba-smoke --output-on-failure
# Optional repeatable core throughput measurement:
build/mgba-smoke/mgba-core-benchmark \
  mgba-android/core/src/androidTest/assets/hello.gba 30000

tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:assembleBenchmark :core:assembleBenchmark :core:assembleDebugAndroidTest
# With an emulator/device connected, run correctness tests against the debug core:
ANDROID_SERIAL=<serial> tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
```

Outputs:

- optimized test app: `mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk`
- optimized reusable core: `mgba-android/core/build/outputs/aar/core-benchmark.aar`

The benchmark variant is non-debuggable, compiles native code as `RelWithDebInfo` (`-O2`), and uses Android's debug signing key only so it can be installed for measurements. It is not a production-signed release.

The host test validates core creation and GBA dimensions. Android instrumentation validates version pinning, native lifecycle, memory/file MIT ROM execution, frame/audio output, save-state restoration, cartridge savedata round trips, and closed-session behavior.

## Remaining release gates

- sustained gameplay, audio latency, controllers, pause/resume, saves, battery, and thermal behavior on physical arm64 devices;
- production branding, accessibility review, release signing, dependency/security audit, and store/legal review;
- optional product features such as library metadata, rewind, cheats, shaders, cloud sync, and link cable.
