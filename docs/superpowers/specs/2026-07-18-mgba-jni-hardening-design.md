# mGBA JNI adapter hardening design

- Date: 2026-07-18
- Status: Approved
- Scope: First of three native-code improvement tracks

## Context

Garnacha Boy has three distinct native-code improvement tracks:

1. harden the primary Android JNI adapter;
2. reduce warnings and split bounded responsibilities from the legacy SkyEmu client;
3. benchmark and optimize only measured runtime bottlenecks.

This specification covers only track 1. The primary Android adapter is currently a single 500-line file, `mgba-android/core/src/main/cpp/mgba_android.c`, containing both JNI conversion and mGBA session ownership. Android instrumentation covers successful runtime behavior, but the adapter's ownership and failure paths cannot be exercised directly by host sanitizers.

## Goals

- Preserve the existing Java/JNI API, successful-path behavior, and Java exception categories.
- Make mGBA ownership, cleanup order, and failure behavior explicit.
- Exercise product-owned native lifecycle and data paths under AddressSanitizer and UndefinedBehaviorSanitizer.
- Remove duplicated ROM loading and cleanup logic.
- Keep canonical mGBA `0.10.5` unmodified.

## Non-goals

- No new emulator features or Java API changes.
- No UI or application-layer refactor.
- No C++ conversion, general native framework, logging framework, allocator abstraction, or custom error hierarchy.
- No speculative optimization or performance claim.
- No work on legacy `src/main.c`; that is a separate design.
- No physical-device gate for this behavior-preserving hardening slice.

## Chosen approach

Extract one pure-C session module and leave a thin JNI wrapper:

- `mgba-android/core/src/main/cpp/mgba_session.h`
- `mgba-android/core/src/main/cpp/mgba_session.c`
- `mgba-android/core/src/main/cpp/mgba_android.c`

The session module owns core lifecycle and emulator operations. The JNI file owns only Java handles, strings, primitive arrays, JNI exception checks, and conversion to the existing Java return contract. `vendor/mgba` remains untouched.

This seam is preferred over inline cleanup because it permits meaningful host sanitizer tests. A C++ RAII rewrite was rejected as a larger language and ownership change with no additional user benefit.

## Module boundaries

### `mgba_session.h`

Expose an opaque `MgbaSession` and the minimum product-owned C operations:

- create and destroy a session for GB or GBA;
- load a heap-owned ROM buffer or a ROM file;
- query post-load video dimensions;
- run one frame into caller-provided ARGB and interleaved stereo buffers;
- query frame count;
- apply a DMG palette;
- save and restore emulator state;
- reset the loaded core;
- copy and restore cartridge savedata.

The header documents every pointer's ownership and every buffer's units. It does not expose `struct mCore`, JNI types, Android headers, or future-backend interfaces.

The ROM-buffer entry point consumes a `malloc`-compatible allocation on every call. On success the session retains it until destruction; on failure the session frees it. This preserves the current one-copy JNI byte-array path and makes transfer unambiguous.

State save uses a caller-sized output buffer after querying the exact state size. Savedata copy returns a `malloc`-compatible allocation and byte count; the caller frees it. All other output buffers remain caller-owned.

### `mgba_session.c`

Own:

- `mCore` creation, initialization, config initialization, and deinitialization;
- the ROM backing allocation and its `VFile` lifetime;
- fixed-stride video storage and validated live video dimensions;
- audio channel setup and frame extraction;
- pixel conversion to Android ARGB;
- state, reset, palette, and savedata operations.

Store the selected platform so a failed load can restore a fresh unloaded core. A ROM load is transactional: success produces a loaded session with validated dimensions; failure leaves a valid unloaded session that can either retry or be destroyed.

Use one setup helper, one teardown helper, and one owned-memory load helper. Both byte-array and file loading converge on the owned-memory helper.

### `mgba_android.c`

Retain the existing JNI symbol names exactly. Each JNI function:

1. converts and validates Java inputs;
2. acquires required JNI resources;
3. invokes one session operation;
4. releases every acquired resource through one cleanup path;
5. maps the result to the existing Java contract.

No mGBA internals or session fields remain in the JNI file.

### Build files

`mgba-android/core/CMakeLists.txt` compiles `mgba_session.c` and `mgba_android.c` into `libmgba-android.so`.

`mgba-android/smoke/CMakeLists.txt` builds the session module into a host test target with both GB and GBA cores enabled. Product-owned C targets compile with `-Wall -Wextra -Werror` on Clang and GCC; warning policy does not apply to vendored mGBA.

## Lifecycle and data flow

### Creation and destruction

Session creation allocates zeroed state, records the platform, creates and initializes the corresponding mGBA core, initializes config, loads defaults, disables SGB borders, installs video/audio buffers, and records which stages completed.

Destruction is null-safe and ordered:

1. deinitialize config only if initialized;
2. deinitialize the core only if initialized;
3. release the core allocation according to mGBA's lifecycle contract;
4. free ROM backing memory only after core deinitialization closes its `VFile`;
5. free the session.

The same teardown logic handles partial setup failures and ordinary destruction.

### ROM loading

JNI byte-array loading allocates one native buffer, copies with `GetByteArrayRegion`, checks for a pending JNI exception, and transfers ownership to the session layer.

File loading validates the reported size, allocates once, loops until exactly that many bytes are read, and closes the source file on every path. Missing, empty, oversized, truncated, or rejected ROMs fail cleanly.

The shared loader creates a memory `VFile`, calls `loadROM`, resets the core, and queries final dimensions. Dimensions must be nonzero and fit the fixed video stride and height. If any post-ownership step fails, the module tears down and recreates a fresh unloaded core before returning failure.

### Frame execution

Before advancing the core, validate:

- session is loaded;
- pixel capacity covers `videoWidth * videoHeight` without overflow;
- audio capacity contains at least one stereo frame;
- output pointers are non-null.

The JNI wrapper obtains both Java output arrays before invoking the session operation. If either acquisition fails, it releases anything already acquired and does not advance emulation.

The session masks input keys, runs one frame, converts only the live video rectangle from native color to ARGB, and reads equal left/right audio frame counts into interleaved output. The return value remains the number of stereo frames produced, or `-1` on failure.

### State and savedata

State sizes and savedata sizes must fit both `size_t` and JNI's signed `jsize` before Java arrays are allocated. JNI exceptions after array copy operations produce failure and do not leak temporary native memory.

State restore requires the exact current mGBA state size. Savedata restore rejects null or empty data. Reset requires a loaded core and retains cartridge savedata, matching current behavior.

## Error contract

The pure-C module uses only booleans, counts, `NULL`, and `-1` where already meaningful. It does not allocate error strings or introduce error enums.

The Java-visible behavior remains:

- failed session creation -> `IllegalStateException`;
- rejected ROM/state/savedata or invalid caller data -> existing `IllegalArgumentException` paths;
- failed state serialization/reset -> existing `IllegalStateException` paths;
- closed or unloaded use -> existing Java lifecycle exceptions.

Invalid handles and invalid native state fail safely even when Java preconditions should normally prevent them.

## Host test design

Add one framework-free executable, `mgba_session_test.c`, registered with CTest. It uses the existing repository-owned test assets and simple assertions with diagnostic output.

Cover:

- GBA and GB create/destroy, including repeated cycles;
- successful memory and private-file ROM loading;
- correct GBA and GB dimensions after load;
- rejection of empty, oversized, truncated, and second ROM loads;
- retry with a valid ROM after a failed load;
- undersized pixel and audio buffers without frame-counter advancement;
- rendered pixels and generated audio after a frame;
- state save, frame advance, state restore, and frame-counter restoration;
- reset behavior;
- savedata copy and restore;
- null-safe destruction and cleanup after failed load paths.

Do not add an allocator shim solely to force `malloc` failures. Allocation branches remain defensive and are inspected; ASan/UBSan cover reachable ownership paths.

## Validation gates

Run all of the following:

```sh
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug \
  -DGARNACHA_SANITIZERS=ON
cmake --build build/mgba-smoke
ctest --test-dir build/mgba-smoke --output-on-failure

# Existing benchmark remains executable; this slice makes no speed claim.
build/mgba-smoke/mgba-core-benchmark \
  mgba-android/core/src/androidTest/assets/hello.gba 30000

tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:testDebugUnitTest :app:assembleBenchmark \
  :core:assembleBenchmark :core:assembleDebugAndroidTest

: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the target emulator or device serial}"
tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
```

`GARNACHA_SANITIZERS=ON` adds AddressSanitizer, UndefinedBehaviorSanitizer, and frame pointers only to the product host test/session targets. It is disabled by default and is not added to Android release artifacts or vendored mGBA.

The slice is complete when host CTest passes under both sanitizers, product-owned C is warning-free under the configured strict flags, the benchmark still executes, Android lint/build/unit tests pass, and the existing instrumentation suite passes on an available emulator or device.

## Follow-on tracks

After this slice is implemented and validated:

1. write a separate design for bounded legacy SkyEmu warning cleanup and `src/main.c` decomposition;
2. write a separate measurement-first performance design using the existing benchmark and device frame statistics.

Neither follow-on track is silently included in this implementation.
