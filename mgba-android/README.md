# Custom mGBA Android product

This project is the product-owned Android path around canonical mGBA. It remains isolated from the SkyEmu MVP so the app never packages two emulator cores.

## Current scope

- pins mGBA `0.10.5` at commit `26b7884bc25a5933960f3cdcd98bac1ae14d42e2`;
- builds only the GBA core for `arm64-v8a` and `x86_64`;
- exposes owned JNI session APIs for ROM loading, 240×160 ARGB frames, 48 kHz stereo PCM, key input, save states, and cartridge savedata;
- provides a 2.9 MB custom APK with Android document-picker import, touch/gamepad controls, `AudioTrack` output, lifecycle restart, and atomic private savedata persistence;
- packages the unmodified mGBA MPL-2.0 license in the AAR;
- uses only MIT-licensed `gba-tests` ROMs in instrumentation tests.

No game or proprietary BIOS content is bundled in the application.

## Build and test

```sh
git submodule update --init --depth 1
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/mgba-smoke
ctest --test-dir build/mgba-smoke --output-on-failure
tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:assembleDebug :core:assembleDebug :core:assembleDebugAndroidTest
# With an emulator/device connected:
ANDROID_SERIAL=<serial> tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
```

Outputs:

- app: `mgba-android/app/build/outputs/apk/debug/app-debug.apk`
- reusable core: `mgba-android/core/build/outputs/aar/core-debug.aar`

The host test validates core creation and GBA dimensions. Android instrumentation validates version pinning, native lifecycle, MIT ROM execution, frame/audio output, save-state restoration, cartridge savedata round trips, and closed-session behavior.

## Remaining release gates

- sustained gameplay, audio latency, controllers, pause/resume, saves, battery, and thermal behavior on physical arm64 devices;
- production branding, accessibility review, release signing, dependency/security audit, and store/legal review;
- optional product features such as library metadata, rewind, cheats, shaders, cloud sync, and link cable.
