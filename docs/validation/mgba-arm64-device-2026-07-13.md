# Physical arm64 device validation — 2026-07-13

M1 gate receipt for the roadmap spec
(`docs/superpowers/specs/2026-07-13-mgba-product-roadmap-design.md`).
APK under test: `app-benchmark.apk` (RelWithDebInfo native code, -O2) built
from the source state of commit `58c27c99` (code identical to `a8721604`;
the later commit is docs-only).

## Device

| Property | Value |
|---|---|
| Manufacturer / model | samsung SM-S928B (Galaxy S24 Ultra) |
| SoC | SM8650 (Snapdragon 8 Gen 3) |
| Android version / API | 16 / 36 |
| ABI | arm64-v8a |
| Serial (adb) | RFCX81EJPNN, USB |

## Instrumentation suite on arm64

`ANDROID_SERIAL=RFCX81EJPNN tools/android_project/gradlew -p mgba-android
:core:connectedDebugAndroidTest`: **5 tests, 0 failures, 0 errors, 0
skipped** (`pinnedCoreLoadsAndInitializesOnAndroid`,
`sessionRunsMitLicensedRomAndRestoresState`,
`sessionRunsMitLicensedRomFromPrivateFile`,
`cartridgeSavedataRoundTripsBetweenSessions`,
`closedSessionRejectsFurtherUse`). This is the suite's first run on
physical arm64 hardware; all prior runs were API 34 x86_64 AVD.

## Benchmark app smoke

MIT `gba-tests/ppu/hello.gba` (1,300 bytes) pushed to `/sdcard/Download`,
imported through the system document picker, and rendered with the touch
control overlay (screenshot: `mgba-arm64-device-hello.png`, captured with
`adb exec-out screencap -p`). `adb logcat -d` filtered for
`FATAL|ANR in com.trebuchetdynamics`: 0 entries for this app today (one
unrelated third-party app crash from 07-10 present in the ring buffer).

Observation for the roadmap: a zip-packaged ROM is selectable in the
picker (`*/*` filter) but rejected by the core, which loads raw `.gba`
bytes only. Zip import is recorded as an M4 (ROM library) candidate
feature.

## Sustained gameplay measurements

Pending (Task 7).

## Interaction and integrity checks

Pending (Task 8).

## Verdict

Pending (Task 9).
