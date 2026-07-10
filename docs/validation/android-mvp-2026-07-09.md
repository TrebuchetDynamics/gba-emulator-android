# Android MVP validation — 2026-07-09

## Environment

- AVD: `game-emulator-mvp`
- Android: 14 / API 34, Google APIs, x86_64
- App: `com.trebuchetdynamics.skyemu` version `0.1.0`, target API 35
- Test ROM: [`jsmolka/gba-tests`](https://github.com/jsmolka/gba-tests) MIT repository commit `a7113b67e63f83a9b321696ddd7042ccfad6c881`, `ppu/hello.gba`, SHA-256 `38aed48b67bc0f701e8aa222b0c3334bd306bd29888707bb7224d81f5576c264`

No proprietary game or BIOS was used.

## Receipts

```text
./gradlew clean lintDebug assembleDebug
BUILD SUCCESSFUL
Android lint: 0 errors, 11 warnings
APK: 106,530,500 bytes
```

`aapt dump badging` confirmed package `com.trebuchetdynamics.skyemu`, min API 24, target API 35. `aapt dump permissions` showed `INTERNET` and the AndroidX non-exported-receiver permission; legacy read/write storage permissions were absent. `apksigner verify` confirmed debug signing and no repository-owned release key.

The APK installed successfully, launched `com.sky.SkyEmu.EnhancedNativeActivity`, and remained alive without fatal Java/native logs. The Load Game action opened Android DocumentsUI. Selecting `hello.gba` copied exactly 1,300 bytes to private app storage at `files/roms/hello.gba`; logcat recorded `Imported file: hello.gba`. The emulator rendered “Hello world!” with touch controls; see `android-mvp-hello-rom.png`.

```text
cmake --build /tmp/game-emulator-mobile-mgba-smoke
ctest --test-dir /tmp/game-emulator-mobile-mgba-smoke --output-on-failure
100% tests passed, 0 tests failed out of 1
```

```text
tools/android_project/gradlew -p mgba-android clean lintDebug :core:assembleDebug
BUILD SUCCESSFUL
mGBA Android lint: No issues found
AAR: 633,908 bytes
```

The AAR contained `libmgba-android.so` for `arm64-v8a` and `x86_64`, the `MgbaCore` Java class, and `META-INF/LICENSE-mGBA`. Exported-symbol inspection found both JNI methods.

```text
ANDROID_SERIAL=emulator-5556 tools/android_project/gradlew -p mgba-android :core:connectedDebugAndroidTest
Starting 1 tests on game-emulator-mvp(AVD) - 14
Finished 1 tests on game-emulator-mvp(AVD) - 14
BUILD SUCCESSFUL
```

The Android instrumentation test verified mGBA version `0.10.5` and create/init/destroy lifecycle on Android.

## Remaining device gate

The SkyEmu path still needs sustained gameplay, audio, controller, pause/resume, save, battery, and thermal validation on physical arm64 devices. The custom mGBA path is a core integration spike, not yet a playable product host.
