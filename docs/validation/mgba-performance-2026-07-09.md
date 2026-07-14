# Custom mGBA performance pass — 2026-07-09

## Objective and completion criteria

The objective is to find concrete ways to improve the custom Android app's performance. This pass is complete when it:

1. inspects the actual runtime/build path instead of relying on generic emulator advice;
2. removes one evidenced performance penalty from the installable test product;
3. adds a reproducible measurement tool; and
4. records the next highest-value candidates and the evidence still required before changing them.

## Finding implemented: stop distributing an `-O0` emulator core

The only installable custom APK previously built by CI was the Android `debug` variant. Its generated arm64 compile command contains `-O0` for both the adapter and canonical mGBA. That is appropriate for source debugging but not for gameplay or performance testing.

`benchmark` build types now exist in both `mgba-android/app/build.gradle` and `mgba-android/core/build.gradle`. The app variant:

- is non-debuggable;
- maps the native build to CMake `RelWithDebInfo` and `-O2`;
- remains installable with Android's debug key for test devices;
- is explicitly not a production-signed release.

Observed clean-build artifact sizes:

| Variant | Native optimization | APK size |
|---|---:|---:|
| `debug` | `-O0` | 2,901,824 bytes |
| `benchmark` | `-O2` | 2,301,660 bytes |

The optimized APK is 600,164 bytes (20.7%) smaller.

## Reproducible core measurement

`mgba-android/smoke/mgba_core_benchmark.c` runs a selected ROM for a requested frame count without real-time pacing. It is a diagnostic executable, not a timing gate: shared CI hosts and thermally constrained devices are too noisy for a hard microsecond threshold.

```sh
cmake -S mgba-android/smoke -B /tmp/mgba-smoke-debug -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/mgba-smoke-debug
cmake -S mgba-android/smoke -B /tmp/mgba-smoke-release -G Ninja \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build /tmp/mgba-smoke-release

/tmp/mgba-smoke-debug/mgba-core-benchmark \
  mgba-android/core/src/androidTest/assets/hello.gba 30000
/tmp/mgba-smoke-release/mgba-core-benchmark \
  mgba-android/core/src/androidTest/assets/hello.gba 30000
```

Two alternating host samples produced:

| Build | Sample 1 | Sample 2 |
|---|---:|---:|
| Debug | 760.39 µs/frame | 384.31 µs/frame |
| RelWithDebInfo | 241.51 µs/frame | 143.87 µs/frame |

Host load caused substantial absolute variance, but the optimized core was 2.7–3.1× faster in paired runs. An API 34 x86_64 AVD process-CPU sample also moved in the expected direction, but repeat variance was too high to claim a reliable Android percentage. Emulator graphics frame statistics were unstable and are intentionally not used as proof of smoother presentation.

## Follow-up implemented: remove duplicate Java ROM residency

The original host retained each ROM twice during gameplay: up to 32 MiB in `MainActivity.romData`, then another native allocation made by `nativeLoadRom`. It also re-hashed the entire Java array whenever an emulation session restarted.

The app now streams the selected document through a 64 KiB buffer into an atomic, SHA-256-named private file. `MgbaSession.loadRom(File)` reads that file directly into the one native allocation owned by the session. The Java layer retains only `File` and hash objects, removing up to one full ROM size (32 MiB) from the live Java heap and eliminating JNI array import plus repeated resume-time hashing.

Evidence:

- the new public file-loading test runs the MIT ROM through mGBA on Android;
- instrumentation now reports 5 tests with 0 failures/errors/skips;
- manual API 34 validation created one exact 1,300-byte hash-named private file and rendered the ROM;
- three background/resume cycles kept one private file, resumed rendering, and left no stale file mappings or fatal/ANR logs.

## Next measured candidates

> **Superseded 2026-07-14 by `mgba-arm64-device-2026-07-13.md`.** Candidate 1 is
> done. Candidates 2–4 are **measured to be unnecessary**: on a Snapdragon 8
> Gen 3, 25 minutes of continuous commercial-game emulation used a worst-case
> 2,469 µs of the 16,743 µs frame budget, with 0 late frames in 90,896, 0 janky
> frames, and no underruns after startup. Each remaining candidate was
> predicated on a bottleneck that does not exist on this hardware, so they are
> struck rather than carried as debt. Revisit only if low-end device testing
> (M6) shows a tighter budget.

Ranked by likely leverage, not yet implemented:

1. **Profile on physical arm64 hardware.** DONE — see
   `docs/validation/mgba-arm64-device-2026-07-13.md`.
2. **Remove the frame-copy pipeline.** `nativeRunFrame` converts 38,400 pixels into a Java `int[]`; `EmulatorView.publishFrame` then copies that array into a `Bitmap`. Two 153,600-byte transfers at 59.737 Hz are at least 18.3 MB/s before any JNI pin/copy overhead. Measure a direct buffer plus `SurfaceView`/texture path before replacing the simple Canvas host.
3. **Use audio as the pacing clock.** The current loop performs blocking `AudioTrack.write` and then separately sleeps against a fixed frame deadline. Instrument underruns and drift before moving to a low-latency callback/ring-buffer design.
4. **Measure bitmap lock contention.** The emulation and UI threads serialize around `Bitmap.setPixels`/`drawBitmap`. Confirm contention with Perfetto before adding double/triple buffering.

## Prompt-to-artifact audit

| Requirement | Evidence | Status |
|---|---|---|
| Find performance improvements from current code | Build flags, JNI frame path, ROM import path, audio/frame loop, and Canvas lock inspected | Covered |
| Make one concrete improvement | Installable `benchmark` app/core variants use `RelWithDebInfo`/`-O2` | Covered |
| Continue with the next evidenced bottleneck | Private-file import and `loadRom(File)` remove the retained Java ROM copy and repeated hashing; RED compile failure then 5-test GREEN instrumentation receipt | Covered |
| Provide reproducible evidence | Host benchmark source, commands, paired output, compile commands, APK sizes | Covered |
| Avoid proxy-only completion | Actual MIT ROM execution measured; noisy AVD graphics results rejected rather than treated as proof | Covered |
| Preserve correctness | Debug and RelWithDebInfo host CTest pass 1/1; Android instrumentation reports 5 tests with 0 failures/errors/skips; final app loaded and resumed MIT `hello.gba` without fatal/ANR logs | Covered |
| Identify uncertainty | Physical arm64 performance/audio/frame pacing/thermal behavior explicitly remains unverified | Covered |
