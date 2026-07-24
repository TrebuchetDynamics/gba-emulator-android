# Custom mGBA Android validation — 2026-07-09

## Product artifacts

- APK: `mgba-android/app/build/outputs/apk/debug/app-debug.apk` — 2,884,656 bytes
- AAR: `mgba-android/core/build/outputs/aar/core-debug.aar` — 639,607 bytes
- package: `com.trebuchetdynamics.mgba`, target API 35
- native ABIs: `arm64-v8a`, `x86_64`
- core: canonical mGBA `0.10.5` / `26b7884bc25a5933960f3cdcd98bac1ae14d42e2`

## Automated receipts

```text
mgba-android/gradlew -p mgba-android clean lintDebug \
  :app:assembleDebug :core:assembleDebug :core:assembleDebugAndroidTest
BUILD SUCCESSFUL
app lint: 0 errors, 1 target-API warning because SDK 36 is locally installed
core lint: No issues found
```

```text
ANDROID_SERIAL=emulator-5556 mgba-android/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
Starting 4 tests on game-emulator-mvp(AVD) - 14
Finished 4 tests on game-emulator-mvp(AVD) - 14
BUILD SUCCESSFUL
```

Persisted XML evidence reports `tests="4" failures="0" errors="0" skipped="0"`. Coverage:

1. mGBA version and native create/init/destroy lifecycle;
2. MIT `hello.gba` execution, non-black frame output, generated audio samples, key input, save-state restore, and frame-counter restore;
3. MIT `sram.gba` cartridge savedata round trip between sessions;
4. closed-session rejection.

The savedata test initially exposed that mGBA direct writeback does nothing before save-type detection. The adapter now restores through mGBA's masked savedata path; the regression test passes.

Host CTest continues to validate creation and 240×160 dimensions without ROM content.

## Interactive emulator receipt

Environment: Android 14 / API 34 Google APIs x86_64 AVD `game-emulator-mvp`.

The 2.9 MB custom app installed and launched without fatal Java/native logs. Android DocumentsUI selected MIT-licensed `jsmolka/gba-tests/ppu/hello.gba` (SHA-256 `38aed48b67bc0f701e8aa222b0c3334bd306bd29888707bb7224d81f5576c264`). The product-owned mGBA host rendered “Hello world!” with its own touch D-pad, A/B, L/R, Start, Select, and Load controls; see `mgba-custom-hello-rom.png`. Home/background then hot-resume restarted the emulation session and rendered the ROM again without fatal logs.

No proprietary game or BIOS was used.

## Remaining release gate

The custom path is now playable, but physical arm64 sustained gameplay, audible latency, Bluetooth/USB controllers, real-game savedata, battery, and thermal behavior remain unverified. Production signing, branding, accessibility review, security/dependency audit, and legal/store review also remain outside this debug MVP.
