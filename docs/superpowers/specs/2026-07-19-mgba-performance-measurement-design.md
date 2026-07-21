# mGBA end-to-end performance measurement design

- Date: 2026-07-19
- Status: Approved design; written review pending
- Scope: Performance track, slice 1 of 2

## Context

Garnacha Boy's mGBA adapter is now separated into a pure-C session module and a thin JNI wrapper, with host sanitizer coverage and existing Android instrumentation passing. The existing host benchmark reports roughly 344 microseconds per raw mGBA frame on the development machine, far below the 16.743 millisecond real-time frame budget. That result does not include Android JNI array handling, bitmap publication, UI drawing, blocking audio writes, or periodic rewind-state serialization, so it cannot identify the application's actual device bottleneck.

The Android application already records total per-frame work, late frames, and `AudioTrack` underruns in ten-second `MgbaPerf` windows. `measure-session.sh` collects those logs and Android `gfxinfo`, but it cannot attribute work to phases, its package ID predates the Garnacha Boy rebrand, and its current flow targets long unplugged battery sessions rather than a repeatable connected profiling run.

This specification covers measurement and baseline collection only. The measured hotspot receives a second, smaller design before production optimization code is changed.

## Goals

- Measure end-to-end frame work on the attached SM-S928B during representative GBA gameplay.
- Attribute average frame cost to native/JNI work, blocking audio output, bitmap publication, and rewind snapshots.
- Preserve existing total-time, maximum-time, late-frame, underrun, UI-jank, and memory evidence.
- Produce three comparable ten-minute baseline runs and rank the dominant contributor.
- Confirm the dominant contributor with Android platform profiling before recommending an optimization.
- Define an objective acceptance gate for the later hotspot slice.

## Non-goals

- No speculative optimization in this slice.
- No emulator-core, JNI, renderer, audio, rewind, or pacing behavior change.
- No performance claim from the host raw-core benchmark alone.
- No battery or thermal-efficiency claim from a connected ten-minute run.
- No collection, copying, inspection, or publication of ROM data.
- No changes to vendored mGBA or the legacy root `src/main.c`.
- No broad telemetry framework, analytics dependency, benchmark library, or persistent in-app diagnostics UI.

## Chosen approach

Use lightweight phase instrumentation in the existing `EmulationRunner` and aggregate it through the existing `FrameStats` ten-second windows. Keep Android `gfxinfo` for UI rendering and use a short Perfetto/simpleperf capture only to confirm the winning phase.

This is preferred over profiler-only measurement because profiler stacks do not reliably separate Java/JNI array handling, blocking `AudioTrack.write`, bitmap upload, and once-per-second rewind serialization into stable application-level totals. It is preferred over immediately optimizing likely hotspots because the raw core is already well within budget and the end-to-end bottleneck is unknown.

## Two-slice boundary

### Slice 1: measurement and baseline

Implement phase counters, repair and extend the measurement script, enable shell profiling only in the benchmark build, validate the harness, and collect three ten-minute baseline runs.

The output is a baseline report containing:

- the median total frame-work time across the three runs;
- per-phase average contribution and percentage of total work;
- maximum total frame time, late frames, and audio underruns;
- Android `gfxinfo` jank evidence;
- memory observations;
- a platform-profile trace naming the dominant call path;
- one recommended hotspot for the second slice, or a recommendation to make no production change.

### Slice 2: measured hotspot

Write a separate targeted design for the winning hotspot. Implement one bounded candidate at a time, rerun all correctness gates, and compare three candidate runs against the three-run baseline. Revert candidates that miss the acceptance gate.

A conditional optimization is not included in the slice-1 implementation plan because its files, risks, and correct validation depend on evidence that does not exist yet.

## Instrumentation design

### `EmulationRunner`

Retain the current frame loop and timing source. Add monotonic timing boundaries around existing operations only:

1. `MgbaSession.runFrame`, including native emulation, pixel conversion, audio extraction, JNI transitions, and Java-array handling;
2. blocking `AudioTrack.write` when audio is active and fast-forward is off;
3. `EmulatorView.publishFrame`, including bitmap pixel upload and frame-lock acquisition;
4. periodic `MgbaSession.saveState` used for rewind;
5. the existing total frame-work interval.

Command handling remains outside the measured frame interval, matching current behavior. Parking and deliberate frame pacing also remain outside frame-work time. Fast-forward samples are excluded from the representative baseline by fixing normal-speed settings.

Timing uses primitive locals and aggregate counters only. It must allocate no per-frame objects and add no per-frame logging. Additional `elapsedRealtimeNanos` calls are accepted because baseline and candidate use identical instrumentation and the required improvement is an order of magnitude larger than clock-call overhead.

### `FrameStats`

Extend the existing accumulator rather than adding a new metrics abstraction. `record` receives total, native/JNI, audio, publication, and rewind durations. All durations are non-negative nanoseconds.

Each ten-second line retains the existing stable fields and adds average contribution per emulated frame:

```text
frames=... avg_us=... max_us=... late=... underruns=... native_us=... audio_us=... publish_us=... rewind_us=... rewind_max_us=...
```

A phase that does not run during a frame contributes zero to that frame. This makes phase averages directly comparable and preserves the cost of periodic rewind snapshots in total per-frame work. `rewind_max_us` exposes its spike separately.

The phase sum may be lower than total time because key reads, loop bookkeeping, and clock calls remain unattributed. The report labels the difference as `other_us`; negative differences are treated as invalid measurement data.

### Android UI evidence

`publish_us` ends after `Bitmap.setPixels` and invalidation scheduling. Actual UI-thread drawing remains asynchronous and is not folded into emulation-thread totals. Android `dumpsys gfxinfo` remains the source for total rendered frames, janky frames, and frame-time percentiles.

### Benchmark-build profiling

Add a benchmark-only manifest overlay with `<profileable android:shell="true"/>`. Debug builds are unsuitable for final profiling and production release builds must not become shell-profileable. Perfetto/simpleperf is used for a short confirmation capture after phase totals identify the likely hotspot; it is not run continuously through all ten-minute sessions.

## Measurement script

Update `mgba-android/tools/measure-session.sh` without replacing its sustained battery workflow:

- change the package ID to `com.trebuchetdynamics.garnacha`;
- add connected `profile-start`, `profile-collect`, and `profile-summary` modes;
- keep existing `start` and `collect` behavior for later battery/thermal work;
- reject missing packages, non-running gameplay, absent `MgbaPerf` windows, malformed lines, or fewer than the required run logs;
- collect logs, `gfxinfo`, `meminfo`, device model/build, brightness, refresh mode, battery temperature, and run timestamps;
- calculate run medians and three-run medians with host Python's standard library from stable key-value log lines;
- never install an APK, launch a ROM, read app-private files, or copy device content.

`profile-start` resets logcat and `gfxinfo` after the user completes the warm-up. `profile-collect` writes one self-contained run directory. `profile-summary` accepts exactly three baseline run directories and emits a deterministic text report suitable for later baseline/candidate comparison.

The script records device settings rather than changing brightness, refresh rate, power mode, or thermal configuration. The user controls those settings and gameplay.

## Baseline protocol

Use the attached SM-S928B and the optimized benchmark APK.

For each of three runs:

1. Start from the same user-owned GBA save state and representative gameplay segment.
2. Use normal speed, audio enabled, frameskip `0`, rewind enabled, and fixed brightness.
3. Allow one minute of gameplay warm-up before `profile-start` resets counters.
4. Play for ten minutes while remaining connected for profiling.
5. Run `profile-collect` immediately afterward.
6. Allow the device to cool before the next run; starting battery temperature must remain within two degrees Celsius across runs.

The benchmark harness does not automate gameplay or access the ROM. If the gameplay path cannot be repeated exactly, the report records that limitation and the 10% gate remains intentionally large.

The baseline value is computed in two stages: take the median ten-second `avg_us` window within each run, then take the median of those three run medians. Phase contributions use the same two-stage median method. `gfxinfo` jank, late frames, and underruns are reported independently rather than folded into one score.

## Hotspot decision rules

Rank phases by median contribution to total frame-work time and confirm the leading call path with Perfetto/simpleperf.

- If native/JNI dominates, the next design may target `mgba_session_run_frame` conversion or transfer costs while preserving emulation behavior.
- If bitmap publication or UI rendering dominates, the next design may target frame-lock contention, bitmap upload, or repeated layout work without replacing the rendering stack wholesale.
- If audio dominates, first distinguish intentional pacing from harmful blocking. Do not trade latency or stability for a lower measured duration.
- If rewind dominates late-frame spikes, the next design must preserve single-threaded mGBA access and explicitly evaluate rewind granularity.
- If no phase is dominant or the application already has no meaningful performance problem, recommend no production optimization.

## Slice-2 acceptance gate

A candidate is accepted only when all of the following hold:

- median total frame-work time across three candidate runs is at least 10% lower than the three-run baseline;
- late-frame count does not increase;
- audio underruns do not increase;
- Android `gfxinfo` janky-frame rate and reported percentiles do not regress;
- functional, unit, JNI instrumentation, host sanitizer, lint, and build gates continue to pass;
- no user-facing visual, audio, rewind, input, or pacing behavior regresses.

Candidates below 10% are reverted. Multiple speculative changes are never bundled into one comparison.

## Error handling and data integrity

Instrumentation must never stop emulation. Durations come from ordered monotonic timestamps; if a clock anomaly produces a negative value, that metrics sample is skipped and gameplay continues. Existing runtime failures continue through existing error handling.

Profile commands fail before resetting data when the package is absent or gameplay is not running. Collection fails when no complete metric window is present. Summary fails on malformed fields, mismatched device identity, fewer than three runs, or negative unexplained time.

Generated reports contain performance counters and device metadata only. They exclude logcat categories unrelated to `MgbaPerf`, app-private storage, ROM names unless the user supplies a neutral run label, and all ROM bytes.

## Testing and validation

### Automated checks

- Extend `FrameStatsTest` for phase averages, reset behavior, zero-occurrence phases, rewind maximum, late frames, and cumulative underrun deltas.
- Add deterministic script fixture checks for valid three-run medians and rejection of missing/malformed windows.
- Run `bash -n mgba-android/tools/measure-session.sh`.
- Run Android Java unit tests, lint, benchmark APK/AAR builds, and existing core instrumentation.
- Re-run host ASan/UBSan session tests to ensure the performance measurement slice does not disturb native hardening.
- Verify the benchmark manifest alone is shell-profileable and release manifests remain unchanged.

### Manual evidence

- Three ten-minute baseline run directories from the same SM-S928B.
- One generated baseline summary.
- One short Perfetto/simpleperf confirmation capture.
- A written hotspot recommendation naming the measured phase, contribution, call path, and proposed boundary for slice 2.

## Expected files for slice 1

- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FrameStats.java`
- `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FrameStatsTest.java`
- `mgba-android/app/src/benchmark/AndroidManifest.xml`
- `mgba-android/tools/measure-session.sh`
- `mgba-android/README.md`

No core C/JNI file is expected to change in the measurement slice.

## Completion condition

Slice 1 is complete when the measurement harness passes all automated checks, three valid ten-minute baseline runs produce a deterministic summary, platform profiling confirms the dominant contributor, and the resulting evidence supports either a bounded slice-2 design or an explicit no-change recommendation.
