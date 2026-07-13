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

## Demanding-title first light

Test content: *The Legend of Zelda: The Minish Cap* (USA), 16.78 MB,
dumped by the tester from a cartridge they own (tester attestation; the
ROM is not committed to this repository and lives only on the device).

The title imported through the document picker and rendered its title
screen (screenshot not committed — it contains copyrighted game art).
First `MgbaPerf` windows on the instrumented benchmark build:

```
frames=599 avg_us=3670 max_us=18306 late=1 underruns=8
frames=598 avg_us=3199 max_us=10865 late=0 underruns=0
frames=598 avg_us=3086 max_us=9977  late=0 underruns=0
frames=598 avg_us=3018 max_us=10513 late=0 underruns=0
```

Frame *work* time settles at 3.0–3.2 ms against the 16.743 ms budget
(~5× headroom); windows hold ~598 frames, i.e. full 59.7 fps speed. The
first window carries the startup transient (ROM load and AudioTrack
warm-up): one late frame, an 18.3 ms max, and 8 audio underruns. Steady
state that follows is clean — no late frames, no underruns, maxima around
10 ms, comfortably inside budget.

Those underruns are the first real exercise of the per-window underrun
delta fixed in `a57dc16b`; the earlier cumulative reading would have
reported `underruns=8` in every subsequent window and falsely implied
continuous audio glitching.

This is a strong preliminary signal but does NOT satisfy the
sustained-session gate below.

## Defects found during smoke testing

1. **Landscape touch layout is broken** (severity: blocks a good 1.0).
   In landscape the L and R shoulder buttons render as oversized pills
   dominating both sides, A and B are tiny circles placed near the middle,
   and the D-pad is small and tucked under the L button. The emulated
   screen stays small and centered. The layout is unusable for real play.
   Owner: M5 (settings and input) at the latest; the layout work may be
   worth pulling earlier since it affects every play session.
2. **Zip-packaged ROMs are not supported.** The picker's `*/*` filter lets
   the user select a `.zip`, which the core then rejects (it loads raw
   `.gba` bytes only). The user must extract manually first. Owner: M4
   (ROM library) — either filter the picker or transparently unzip.

## Sustained gameplay measurements

Pending (Task 7) — **blocked on device battery state.** The gate requires
30–60 min unplugged (battery/thermal figures are invalid while charging).
At the time of this session the device sat at 17% battery, and USB power
barely offset the emulator's own draw (charge current oscillated around
±200 mA under load). The session must be re-run from a high charge level
over wireless debugging.

## Interaction and integrity checks

Pending (Task 8).

## Verdict

Pending (Task 9).
