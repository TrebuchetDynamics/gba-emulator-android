# Garnacha Boy for Android

This is the Garnacha Boy product: an owned Android client around canonical mGBA. It is the repository's only supported emulator client and never packages a second emulator core.

## Current scope

- pins mGBA `0.10.5` at commit `26b7884bc25a5933960f3cdcd98bac1ae14d42e2`;
- builds the mGBA GB/GBC/GBA core for `arm64-v8a` and `x86_64`;
- exposes owned JNI session APIs for memory/file ROM loading, 240×160 ARGB frames, 48 kHz stereo PCM, key input, save states, and cartridge savedata;
- provides a custom offline APK with atomic private-file import, touch/gamepad and controller-only modes, rotating autosave/resume, manual and rewind states, clean-frame screenshots, `AudioTrack` output, and atomic private savedata persistence;
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

mgba-android/gradlew -p mgba-android clean lintDebug \
  :app:assembleBenchmark :core:assembleBenchmark :core:assembleDebugAndroidTest
# With an emulator/device connected, run correctness tests against the debug core:
ANDROID_SERIAL=<serial> mgba-android/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
```

For product-owned native lifecycle checks under AddressSanitizer and
UndefinedBehaviorSanitizer:

```sh
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DGARNACHA_SANITIZERS=ON
cmake --build build/mgba-smoke
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
ctest --test-dir build/mgba-smoke --output-on-failure
```

Sanitizer flags apply only to the product session test, not vendored mGBA or
Android release artifacts.

### Connected performance baseline

Build and install the optimized, shell-profileable benchmark variant, then
start a user-owned GBA game with normal speed, audio on, frameskip 0, rewind
on, and fixed brightness. After one minute of warm-up:

```sh
mgba-android/gradlew -p mgba-android :app:assembleBenchmark
adb -s RFCX81EJPNN install -r \
  mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
export ANDROID_SERIAL=RFCX81EJPNN
export OUT_DIR=build/perf
mgba-android/tools/measure-session.sh profile-start
# Play the same representative segment for at least 10 minutes.
mgba-android/tools/measure-session.sh profile-collect baseline-1
```

Repeat as `baseline-2` and `baseline-3`, cooling the device until starting
battery temperatures are within 2°C, then summarize:

```sh
mgba-android/tools/measure-session.sh profile-summary \
  build/perf/profile-baseline-1 \
  build/perf/profile-baseline-2 \
  build/perf/profile-baseline-3 | tee build/perf/baseline-summary.txt
```

The connected profile is a frame-time diagnostic, not a battery-life result.
The script collects counters and device metadata only; it never accesses ROM
or app-private data.

Outputs:

- optimized test app: `mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk`
- optimized reusable core: `mgba-android/core/build/outputs/aar/core-benchmark.aar`

The benchmark variant is non-debuggable, compiles native code as `RelWithDebInfo` (`-O2`), and uses Android's debug signing key only so it can be installed for measurements. It is not a production-signed release.

The host test validates core creation and GBA dimensions. Android instrumentation validates version pinning, native lifecycle, memory/file MIT ROM execution, frame/audio output, save-state restoration, cartridge savedata round trips, and closed-session behavior.

## Remaining release gates

- sustained gameplay, audio latency, controllers, pause/resume, saves, battery, and thermal behavior on physical arm64 devices;
- production branding, accessibility review, release signing, dependency/security audit, and store/legal review;
- optional product features such as library metadata, rewind, cheats, shaders, cloud sync, and link cable.
