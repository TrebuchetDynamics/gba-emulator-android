# mGBA direct frame-buffer optimization design

- Date: 2026-07-19
- Status: Approved design; written review pending
- Scope: Performance track, slice 2

## Context

Three ten-minute benchmark runs on the attached SM-S928B measured median total frame work at 1,827 microseconds. Native/JNI work dominated at 1,462 microseconds, or 80% of measured frame work; bitmap publication contributed 319 microseconds. The runs recorded no late frames and 0% Android UI jank. simpleperf identified the product-owned pixel conversion in `mgba_session_run_frame` as the leading symbol.

The current path renders mGBA's 32-bit native pixels into a session buffer, converts every pixel to Android ARGB integers, acquires and releases a Java `int[]` through JNI, then copies it into an Android bitmap with `Bitmap.setPixels`. This slice tests one bounded candidate that removes the conversion and JNI pixel-array transfer while preserving the existing Java API.

## Goals

- Remove per-frame channel conversion and JNI `int[]` transfer from the Android application's normal frame path.
- Keep the existing `MgbaSession.runFrame(int, int[], short[])` behavior available for compatibility.
- Preserve video output, audio, input, pacing, rewind, save states, savedata, and lifecycle behavior.
- Keep the pure-C session boundary host-testable and the Java/JNI additions small.
- Accept the candidate only if three comparable device runs improve median total frame work by at least 10% without regressions.

## Non-goals

- No changes to `vendor/mgba` or legacy root `src/main.c`.
- No renderer rewrite, OpenGL/Vulkan path, native bitmap locking, double buffering, or new dependency.
- No audio, rewind, pacing, frameskip, or emulation-core optimization.
- No removal or behavior change of the existing pixel-array API.
- No battery or thermal-efficiency claim from the connected benchmark.
- No ROM inspection, copying, automation, or publication by measurement tooling.

## Considered approaches

### 1. Direct native frame buffer — chosen

Expose a JNI direct-buffer view over the session-owned video buffer and publish it with `Bitmap.copyPixelsFromBuffer`. This removes the measured conversion loop and Java pixel-array transfer while retaining the existing bitmap and frame-lock architecture. It has the best chance of clearing the 10% gate without broad renderer changes.

### 2. Faster scalar conversion

Replace the current masks and shifts with a byte-swap expression and rely on compiler vectorization. This is smaller, but it retains the full conversion pass and JNI array transfer. Profiling indicates it is unlikely to reduce total frame work by 10%.

### 3. Render directly into an Android bitmap

Lock bitmap pixels from JNI and let mGBA render into them. This could remove another copy, but it couples the session to Android bitmap APIs and requires additional synchronization or double buffering to prevent concurrent UI drawing. The complexity and behavioral risk are not justified for the first candidate.

## Architecture

### Pure-C session

Keep the session-owned `color_t` video array. After ROM loading determines the runtime dimensions, configure mGBA's video stride to the exact video width so the active frame is contiguous.

Split the current frame operation into two internal responsibilities:

1. run emulation and extract interleaved audio while leaving pixels in the native video buffer;
2. convert the current native frame into Android ARGB integers for the compatibility path.

The existing `mgba_session_run_frame` calls both responsibilities and retains its current contract. Add narrowly scoped accessors for the contiguous video-buffer address and byte length, plus a frame-running entry point that does not request ARGB conversion. Invalid sessions, unloaded ROMs, undersized audio buffers, or invalid output parameters continue to fail without advancing through an unsafe path.

The session structure owns the frame memory for its entire lifetime. No per-frame allocation is introduced.

### JNI and Java session API

Add an additive direct path while retaining every existing JNI symbol and Java method:

- obtain one `DirectByteBuffer` view over the loaded session's active frame;
- run one frame and fill the existing `short[]` audio buffer without producing an `int[]` frame.

The Java `MgbaSession` caches the direct buffer after a successful ROM load. The buffer is valid only until that session closes. The emulator runner stops before closing the session, matching the existing lifecycle and preventing access after native memory is released.

If JNI cannot create the buffer or its capacity differs from `videoWidth * videoHeight * 4`, the application keeps the existing reusable `int[]` and uses the compatibility path. This is a startup decision, not a per-frame retry or allocation.

### Android frame publication

Add a direct-buffer overload to `EmulatorView.publishFrame`. Under the existing `frameLock`, it resets the reusable buffer's position and calls `Bitmap.copyPixelsFromBuffer`. Drawing and screenshot copying continue to use the same locked bitmap.

The supported ABIs, `arm64-v8a` and `x86_64`, are little-endian. mGBA's 32-bit native color value stores red, green, and blue as raw bytes in the order expected by Android's RGBA bitmap memory, with the high byte unused. The bitmap is marked opaque so the unused alpha byte is ignored. This layout assumption is enforced by Android pixel-equivalence tests; failure rejects the candidate rather than adding a runtime conversion fallback inside the direct path.

## Frame data flow

For the selected direct path:

1. `EmulationRunner` reads input and starts the existing native phase timer.
2. `MgbaSession.runFrameDirect` runs mGBA into its session-owned contiguous video buffer and fills the reusable Java audio array.
3. The existing audio write runs unchanged.
4. `EmulatorView.publishFrame` copies raw bytes from the reusable direct buffer into the existing bitmap under `frameLock`.
5. Rewind capture, statistics, and frame pacing run unchanged.

The legacy path remains:

1. run mGBA into the native buffer;
2. convert into the caller's ARGB `int[]`;
3. publish through `Bitmap.setPixels`.

Both paths advance exactly one emulated frame and return the number of stereo audio frames produced.

## Error handling and lifecycle

- Direct-buffer access before a ROM is loaded fails cleanly.
- Invalid native pointers or capacities do not expose a Java direct buffer.
- Failure to establish the direct path selects the existing compatibility path for that session.
- Runtime emulation failures continue through existing error handling; they do not silently switch paths after a frame may have advanced.
- The direct buffer is never retained beyond runner shutdown and session close.
- Buffer position is reset before every bitmap copy; no new buffer is allocated per frame.
- Session loading, retry recovery, reset, save states, and savedata ownership remain unchanged.

## Testing

### Host native tests

Extend the framework-free session test to verify:

- the active frame buffer exists only for a loaded session;
- its byte length equals `width * height * sizeof(color_t)`;
- the configured active frame is contiguous for GBA and GB dimensions;
- direct frame execution advances one frame and produces valid audio counts;
- legacy ARGB conversion still produces the expected channel order;
- direct and legacy paths remain safe under ASan, UBSan, `-Wall`, `-Wextra`, and `-Werror`.

### Android tests

Extend instrumentation using the bundled MIT-compatible `hello.gba`, `hello.gb`, and `hello.gbc` assets:

- save state, run a direct frame, restore state, and run a legacy frame;
- copy the direct bytes into an opaque `ARGB_8888` bitmap;
- assert all bitmap pixels equal the legacy ARGB output;
- verify dimensions, frame counters, audio counts, legacy-path availability, and close lifecycle;
- retain all existing JNI hardening instrumentation coverage.

Run existing Java unit tests, lint, benchmark and release builds, host sanitizer tests, and connected instrumentation. No visual spot check replaces pixel-equivalence testing.

## Performance validation

Use the existing benchmark harness and the imported `minish` ROM on the same SM-S928B with normal speed, audio enabled, frameskip `0`, rewind enabled, fixed brightness, and a one-minute warm-up.

Collect three ten-minute candidate runs with starting battery temperatures within two degrees Celsius. Compare the two-stage three-run medians against the recorded baseline:

- baseline total frame work: 1,827 microseconds;
- acceptance threshold: at most 1,644 microseconds, representing at least a 10% reduction;
- baseline late frames: 0;
- baseline Android jank: 0.00%;
- baseline aggregate audio underruns: 5.

Accept only if total frame work clears the threshold, late frames remain zero, jank and frame-time percentiles do not regress, underruns do not exceed baseline, and no visual, audio, input, rewind, save, or pacing behavior regresses. Use simpleperf to confirm that the conversion-loop samples disappeared and that no new product-owned hotspot replaced them.

If any gate fails, revert the production candidate. Do not weaken the threshold, bundle a second optimization, or claim an improvement from a single run.

## Expected implementation files

- `mgba-android/core/src/main/cpp/mgba_session.c`
- `mgba-android/core/src/main/cpp/mgba_session.h`
- `mgba-android/core/src/main/cpp/mgba_android.c`
- `mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java`
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`
- `mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java`
- `mgba-android/smoke/mgba_session_test.c`
- `mgba-android/README.md` only if the benchmark procedure needs a correction

No vendored or legacy emulator file is in scope.

## Completion condition

The slice is complete only when correctness gates pass, three valid candidate runs clear every acceptance criterion, simpleperf confirms removal of the targeted conversion cost, and the direct path remains additive and allocation-free per frame. Otherwise the production candidate is reverted and the baseline implementation remains the result.
