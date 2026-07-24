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

`ANDROID_SERIAL=RFCX81EJPNN mgba-android/gradlew -p mgba-android
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

1. **Landscape touch layout is broken** — FIXED (`9ce30ab0`), pulled
   forward from M5 at the tester's request. Three real causes, all fixed:
   control sizes derived from `width` (the long edge in landscape, so
   shoulders ballooned while face buttons shrank); a portrait-only
   arrangement; and geometry duplicated between `onDraw` and `keysAt` with
   *different* constants, so touch targets never matched the drawn buttons.
   `ControlLayout` now computes both arrangements once as pure,
   android-free, unit-tested geometry that drawing and hit-testing share.
   Verified on device: the landscape layout renders correctly (game centred,
   D-pad left, A/B diagonal right, shoulders in the corners, SELECT/START
   bottom-centre), and holding the drawn START button both highlights it and
   advances the game — hit-testing and rendering now agree. Emulation
   performance is unaffected (`avg_us` 2576/2903, 0 late, 0 underruns).
2. **Zip-packaged ROMs are not supported** — FIXED (`005b53a8`), pulled
   forward from M4 at the tester's request. `RomArchive` (pure, android-free,
   10 JVM tests) detects an archive by its `PK\x03\x04` magic bytes rather
   than by name or MIME, streams out the single `.gba` entry, rejects
   archives holding zero or several ROMs, and caps the *decompressed* size
   so a zip bomb cannot exceed the cartridge limit (`ZipEntry.getSize()` is
   attacker-controlled and is not trusted). Entry names are never used as
   paths, so the extractor cannot be made to write outside its temp file.

   The SHA-256 is taken over the extracted ROM, never the archive. This is
   load-bearing: the private ROM file and the `.sav` file are both named from
   that hash, so hashing the archive would have silently split one game's
   saves between its zipped and raw imports.

   Verified on device with a real 7.25 MB archive of a 16.78 MB ROM: the zip
   imported and the game booted, and the private ROM directory afterwards held
   exactly ONE file —
   `bedc74df62755f705398273de8ed3bc59be610cf55760d0b9aa277f1f5035e73.gba` —
   whose name equals `sha256sum` of the separately-extracted `.gba`. The zip
   import reproduced the ROM byte-for-byte and deduped onto the file the
   earlier raw import had created, so the two import routes share one save.

## Sustained gameplay measurements (2026-07-14)

Wireless debugging was unusable: the host sits on `10.0.2.0/24` and
`10.0.4.0/24`, the device on `192.168.1.0/24`, with no route between them
(100% packet loss). The session therefore ran with **no adb attached** —
counters reset over USB, cable pulled, gameplay, then reconnect and collect.
`MgbaPerf` windows survive in logcat, `gfxinfo` accumulates in-process, and
thermals read on reconnect while the device is still hot.

Title: *The Legend of Zelda: The Minish Cap* (tester's own cartridge dump).
Emulation ran continuously from 15:02:03 to 15:27:15 — **25 minutes, 152
windows, 90,896 frames** — then the activity paused when play stopped, ~10
minutes before reconnect. The `MgbaPerf` record is complete for that span (the
5 MiB ring buffer held 3 MiB; it did not wrap).

### Frame pacing and audio — decisive pass

| Metric | Value | Budget |
|---|---:|---:|
| Mean window frame work | 1,873 µs | 16,743 µs |
| **Worst** window frame work | **2,469 µs** | 16,743 µs |
| Worst single-frame max | 9,137 µs | 16,743 µs |
| Late frames (of 90,896) | **0** | — |
| Slowest window | 598 frames | 598 = full speed |
| Audio underruns | 7 (startup only) | — |

Every one of the 152 windows sat inside budget with **6.8× headroom at the
worst point**, and no window dropped below full 59.7 fps speed. `gfxinfo`
agrees independently: 89,590 frames rendered, **1 janky frame (0.00%)**, with
the 50th through 99th percentiles all at 5 ms. No FATAL or ANR entries.
Memory was stable (TOTAL PSS 47.9 MB; native heap 432 KB).

Thermals never became a factor: AP 33.2 °C and SKIN 32.3 °C on reconnect,
against a first SKIN throttling threshold of 38 °C. The workload is simply too
light on this SoC to provoke throttling — which is itself the finding.

### Battery — measurement failed, not passed

The run recorded **0% drain and no temperature change**, which is not credible
for a screen-on session and must be treated as a failed measurement rather than
as evidence of low power draw. Nothing on the device confirms it was ever
actually on battery: the unplug broadcast fell outside the retained log, and
`batterystats` was not reset at session start, so its counters cover two days
rather than this session.

The tooling has been corrected (`dumpsys batterystats --reset` at session
start, a 32 MiB log buffer, an explicit on-battery check, and a refusal to
present a flat 0% drain as a result). **Battery life remains an open release
gate** and must be re-measured; it does not block the rendering/audio verdict
below, which rests on frame, audio, and jank data that are internally
consistent and independently corroborated.

## Interaction and integrity checks

- **Touch input:** verified against the rebuilt layout — holding the drawn
  START button highlights it and advances the game, so hit-testing and
  rendering agree (they did not before `9ce30ab0`).
- **Lifecycle:** the activity paused and resumed across the session and three
  earlier background/resume cycles with no FATAL or ANR entries. Emulation
  restarts on resume by design.
- **Cartridge saves:** `cartridgeSavedataRoundTripsBetweenSessions` passes on
  this hardware. Import dedup was proven end-to-end: a zipped and a raw import
  of the same ROM resolve to one private file and therefore one `.sav`.
- **Save states:** covered on this device by
  `sessionRunsMitLicensedRomAndRestoresState`; there is no app UI for slots yet
  (M5).
- **Bluetooth controller: NOT TESTED.** No gamepad was paired. This remains an
  open release gate.

## Verdict

**The rendering and audio path is release-viable on this device. M2 collapses.**

The existing Canvas + `AudioTrack` implementation sustained 25 minutes of
continuous commercial-game emulation with zero late frames out of 90,896, zero
janky frames per `gfxinfo`, no audio underruns after startup warm-up, stable
memory, and no throttling. The worst 10-second window used 2,469 µs of a
16,743 µs budget. The three rendering/audio rewrites M2 was created to hold —
`SurfaceView`/GL rendering, Choreographer pacing, and a low-latency audio path
— are **not needed for 1.0** on hardware of this class, and the roadmap should
proceed directly to M3 (release plumbing).

This verdict is scoped honestly:

- It covers **one** device, a 2024 flagship (Snapdragon 8 Gen 3). Low-end and
  mid-range hardware is unmeasured, and the frame budget there could be far
  tighter. Beta testing across a device spread (M6) is where that risk lands.
- It rests on a **25-minute** sample, not the 30–60 minutes the gate specified.
  Given zero late frames across the entire span and no thermal rise, a longer
  run is very unlikely to change the conclusion — but it is a shortfall, and it
  is recorded as one rather than rounded up.
- **Two release gates stay open** and are explicitly *not* cleared by this
  verdict: battery life (the measurement failed; see above) and Bluetooth
  controller support (never tested). Both must be closed before 1.0, but
  neither is a rendering or audio defect, so neither revives M2.

Consequence for the roadmap: candidate 1 of
`mgba-performance-2026-07-09.md` ("profile on physical arm64 hardware") is now
done, and candidates 2–4 (removing the frame-copy pipeline, audio-clocked
pacing, bitmap lock contention) are **measured to be unnecessary** — each was
predicated on a bottleneck that does not exist on this hardware. They should be
struck from the plan rather than carried as debt.
