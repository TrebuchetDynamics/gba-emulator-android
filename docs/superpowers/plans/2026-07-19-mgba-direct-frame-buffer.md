# mGBA Direct Frame-Buffer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Android app's normal converted `int[]` video path with a session-owned JNI direct frame buffer while preserving the existing API and accepting the candidate only if it reduces median device frame work by at least 10%.

**Architecture:** Keep mGBA rendering into the pure-C session's existing `color_t` array, switch the loaded core to a width-sized contiguous stride, and expose that stable memory through one cached `DirectByteBuffer`. The Android app copies the raw RGB bytes into its existing opaque bitmap under the existing frame lock; the legacy ARGB conversion remains as a startup fallback and compatibility API.

**Tech Stack:** C11, mGBA 0.10.5, JNI, Java, Android `Bitmap`/`ByteBuffer`, Gradle, CMake/CTest, ASan/UBSan, Android instrumentation, ADB, simpleperf.

## Global Constraints

- Do not modify `vendor/mgba` or legacy root `src/main.c`.
- Preserve all existing JNI symbols and `MgbaSession.runFrame(int, int[], short[])` behavior.
- Add no dependency, renderer rewrite, native bitmap locking, audio change, pacing change, rewind change, or per-frame allocation/logging.
- Support both configured ABIs: `arm64-v8a` and `x86_64`.
- Keep direct-buffer memory session-owned and never use it after `MgbaSession.close()`.
- Preserve unrelated dirty-worktree changes; snapshot every candidate file before editing.
- Measurement tooling must not inspect, copy, automate, or publish ROM/app-private data.
- Accept only a three-run median at or below 1,644 microseconds with zero late frames, no jank/percentile regression, no more than five aggregate underruns, and no behavior regression.
- Revert the complete candidate if any acceptance gate fails.
- Commit task changes only after explicit shipping approval; otherwise leave them unstaged.

---

## File map

- `mgba-android/core/src/main/cpp/mgba_session.h`: pure-C direct-frame API.
- `mgba-android/core/src/main/cpp/mgba_session.c`: contiguous stride, direct frame execution, buffer access, and retained legacy conversion.
- `mgba-android/smoke/mgba_session_test.c`: host equivalence, dimensions, validation, and sanitizer coverage.
- `mgba-android/core/src/main/cpp/mgba_android.c`: direct-buffer and direct-frame JNI entry points.
- `mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java`: one cached read-only direct buffer and additive Java API.
- `mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java`: GBA/GB/GBC raw-buffer-to-bitmap pixel equivalence.
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`: select direct or legacy path once per loaded session.
- `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`: opaque bitmap creation and direct-buffer publication.
- `build/perf/`: ignored backups, candidate measurements, reports, and acceptance evidence.

---

### Task 1: Add the pure-C direct frame path

**Files:**
- Modify: `mgba-android/core/src/main/cpp/mgba_session.h`
- Modify: `mgba-android/core/src/main/cpp/mgba_session.c`
- Test: `mgba-android/smoke/mgba_session_test.c`

**Interfaces:**
- Consumes: existing `MgbaSession`, ROM loading, audio extraction, and `mgba_session_run_frame` behavior.
- Produces: `mgba_session_run_frame_direct(MgbaSession*, uint32_t, int16_t*, size_t) -> int` and `mgba_session_video_buffer(const MgbaSession*, size_t*) -> const void*`.

- [ ] **Step 1: Snapshot candidate files and excluded-scope hashes**

```sh
set -euo pipefail
mkdir -p build/perf/direct-buffer-prechange
FILES=(
  mgba-android/core/src/main/cpp/mgba_session.h
  mgba-android/core/src/main/cpp/mgba_session.c
  mgba-android/smoke/mgba_session_test.c
  mgba-android/core/src/main/cpp/mgba_android.c
  mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java
  mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java
)
tar -czf build/perf/direct-buffer-prechange/files.tar.gz "${FILES[@]}"
find vendor/mgba src/main.c -type f -print0 \
  | sort -z | xargs -0 sha256sum | sha256sum \
  > build/perf/direct-buffer-prechange/excluded.sha256
git status --short > build/perf/direct-buffer-prechange/status.txt
test -s build/perf/direct-buffer-prechange/files.tar.gz
test -s build/perf/direct-buffer-prechange/excluded.sha256
```

Expected: the archive and hash exist under ignored `build/perf/`; no worktree file changes.

- [ ] **Step 2: Write the failing host test**

Add this helper and test to `mgba-android/smoke/mgba_session_test.c`:

```c
static uint32_t native_to_argb(uint32_t native) {
    return 0xFF000000U
            | ((native & 0x000000FFU) << 16)
            | (native & 0x0000FF00U)
            | ((native & 0x00FF0000U) >> 16);
}

static void test_direct_frame_path(const char* rom_path,
                                   MgbaPlatform platform,
                                   int expected_width,
                                   int expected_height) {
    MgbaSession* direct = mgba_session_create(platform);
    MgbaSession* legacy = mgba_session_create(platform);
    size_t frame_bytes = 1;
    CHECK(direct != NULL);
    CHECK(legacy != NULL);
    CHECK(mgba_session_video_buffer(direct, &frame_bytes) == NULL);
    CHECK(frame_bytes == 0);
    CHECK(mgba_session_load_rom_file(direct, rom_path));
    CHECK(mgba_session_load_rom_file(legacy, rom_path));
    CHECK(mgba_session_video_width(direct) == expected_width);
    CHECK(mgba_session_video_height(direct) == expected_height);

    size_t frame_pixels = (size_t) expected_width * (size_t) expected_height;
    const uint32_t* native_pixels = mgba_session_video_buffer(direct, &frame_bytes);
    int32_t* argb_pixels = calloc(frame_pixels, sizeof(*argb_pixels));
    int16_t direct_audio[4096] = {0};
    int16_t legacy_audio[4096] = {0};
    CHECK(native_pixels != NULL);
    CHECK(frame_bytes == frame_pixels * sizeof(*native_pixels));
    CHECK(argb_pixels != NULL);

    uint64_t before = 0;
    uint64_t after = 0;
    CHECK(mgba_session_frame_counter(direct, &before));
    CHECK(mgba_session_run_frame_direct(direct, 0, direct_audio, 1) == -1);
    CHECK(mgba_session_frame_counter(direct, &after));
    CHECK(after == before);

    int direct_frames = mgba_session_run_frame_direct(
            direct, 0, direct_audio, sizeof(direct_audio) / sizeof(direct_audio[0]));
    int legacy_frames = mgba_session_run_frame(
            legacy, 0, argb_pixels, frame_pixels,
            legacy_audio, sizeof(legacy_audio) / sizeof(legacy_audio[0]));
    CHECK(direct_frames >= 0);
    CHECK(legacy_frames >= 0);
    if (native_pixels && argb_pixels) {
        for (size_t i = 0; i < frame_pixels; ++i) {
            CHECK((uint32_t) argb_pixels[i] == native_to_argb(native_pixels[i]));
        }
    }

    free(argb_pixels);
    mgba_session_destroy(legacy);
    mgba_session_destroy(direct);
}
```

Call it from `main` after `test_frame_validation_and_output`:

```c
    test_direct_frame_path(argv[1], MGBA_PLATFORM_GBA, 240, 160);
    test_direct_frame_path(argv[2], MGBA_PLATFORM_GB, 160, 144);
```

- [ ] **Step 3: Run the host build to verify the new test fails**

```sh
rm -rf build/mgba-direct-red
cmake -S mgba-android/smoke -B build/mgba-direct-red -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DGARNACHA_SANITIZERS=ON
cmake --build build/mgba-direct-red
```

Expected: compilation or linkage fails because `mgba_session_video_buffer` and `mgba_session_run_frame_direct` do not exist.

- [ ] **Step 4: Declare the minimal pure-C API**

Add before `mgba_session_run_frame` in `mgba_session.h`:

```c
/* Runs one frame without converting the session-owned native video buffer. */
int mgba_session_run_frame_direct(MgbaSession* session,
                                  uint32_t keys,
                                  int16_t* stereo_audio,
                                  size_t audio_sample_capacity);

/* Returned storage remains owned by session and is valid until destruction. */
const void* mgba_session_video_buffer(const MgbaSession* session,
                                      size_t* byte_count);
```

- [ ] **Step 5: Make loaded frames contiguous**

Replace `refresh_dimensions` in `mgba_session.c` with:

```c
static bool refresh_dimensions(MgbaSession* session) {
    unsigned width = VIDEO_WIDTH;
    unsigned height = VIDEO_HEIGHT;
    session->core->desiredVideoDimensions(session->core, &width, &height);
    if (!width || !height || width > VIDEO_STRIDE || height > VIDEO_HEIGHT) {
        return false;
    }
    session->video_width = (int) width;
    session->video_height = (int) height;
    session->core->setVideoBuffer(session->core, session->video, width);
    return true;
}
```

The setup-time maximum stride remains unchanged; the width-sized stride is selected only after successful ROM loading determines dimensions.

- [ ] **Step 6: Implement direct execution and retain legacy conversion**

Replace the current `mgba_session_run_frame` block with:

```c
const void* mgba_session_video_buffer(const MgbaSession* session,
                                      size_t* byte_count) {
    if (byte_count) {
        *byte_count = 0;
    }
    if (!session || !session->loaded || !byte_count) {
        return NULL;
    }
    *byte_count = (size_t) session->video_width
            * (size_t) session->video_height * sizeof(session->video[0]);
    return session->video;
}

int mgba_session_run_frame_direct(MgbaSession* session,
                                  uint32_t keys,
                                  int16_t* stereo_audio,
                                  size_t audio_sample_capacity) {
    if (!session || !session->loaded || !stereo_audio
            || audio_sample_capacity < 2) {
        return -1;
    }
    session->core->setKeys(session->core, keys & 0x3FFU);
    session->core->runFrame(session->core);

    blip_t* left = session->core->getAudioChannel(session->core, 0);
    blip_t* right = session->core->getAudioChannel(session->core, 1);
    int available = blip_samples_avail(left);
    if (available <= 0) {
        return 0;
    }
    size_t frame_capacity = audio_sample_capacity / 2;
    int frames_to_read = available;
    if (frame_capacity < (size_t) frames_to_read) {
        frames_to_read = (int) frame_capacity;
    }
    int left_frames = blip_read_samples(left, stereo_audio, frames_to_read, true);
    int right_frames = blip_read_samples(right, stereo_audio + 1, frames_to_read, true);
    return left_frames < right_frames ? left_frames : right_frames;
}

int mgba_session_run_frame(MgbaSession* session,
                           uint32_t keys,
                           int32_t* argb_pixels,
                           size_t pixel_capacity,
                           int16_t* stereo_audio,
                           size_t audio_sample_capacity) {
    if (!session || !session->loaded || !argb_pixels || !stereo_audio) {
        return -1;
    }
    size_t required_pixels = (size_t) session->video_width
            * (size_t) session->video_height;
    if (pixel_capacity < required_pixels || audio_sample_capacity < 2) {
        return -1;
    }
    int produced = mgba_session_run_frame_direct(
            session, keys, stereo_audio, audio_sample_capacity);
    if (produced < 0) {
        return produced;
    }
    for (size_t i = 0; i < required_pixels; ++i) {
        uint32_t native = session->video[i];
        argb_pixels[i] = (int32_t) (0xFF000000U
                | ((native & 0x000000FFU) << 16)
                | (native & 0x0000FF00U)
                | ((native & 0x00FF0000U) >> 16));
    }
    return produced;
}
```

- [ ] **Step 7: Run host sanitizer tests**

```sh
cmake --build build/mgba-direct-red
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
ctest --test-dir build/mgba-direct-red --output-on-failure
```

Expected: both CTests pass with no sanitizer or strict-warning failure.

- [ ] **Step 8: Delivery checkpoint**

With explicit shipping approval only:

```sh
git add mgba-android/core/src/main/cpp/mgba_session.h \
  mgba-android/core/src/main/cpp/mgba_session.c \
  mgba-android/smoke/mgba_session_test.c
git commit -m "feat(core): expose contiguous direct frame buffer"
```

Otherwise leave these files unstaged and continue with the validated worktree.

---

### Task 2: Expose the direct buffer through JNI and Java

**Files:**
- Modify: `mgba-android/core/src/main/cpp/mgba_android.c`
- Modify: `mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java`
- Test: `mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java`

**Interfaces:**
- Consumes: Task 1's `mgba_session_video_buffer` and `mgba_session_run_frame_direct`.
- Produces: `MgbaSession.directFrameBuffer() -> ByteBuffer` (nullable fallback signal) and `MgbaSession.runFrameDirect(int, short[]) -> int`.

- [ ] **Step 1: Write the failing Android pixel-equivalence test**

Add imports to `MgbaCoreInstrumentedTest.java`:

```java
import android.graphics.Bitmap;

import java.nio.ByteBuffer;
```

Add this test and helper before `readAsset`:

```java
    @Test
    public void directFrameBufferMatchesLegacyArgbForSupportedSystems() throws Exception {
        assertDirectMatchesLegacy("hello.gba", MgbaSession.PLATFORM_GBA);
        assertDirectMatchesLegacy("hello.gb", MgbaSession.PLATFORM_GB);
        assertDirectMatchesLegacy("hello.gbc", MgbaSession.PLATFORM_GB);
    }

    private static void assertDirectMatchesLegacy(String asset, int platform) throws Exception {
        byte[] rom = readAsset(asset);
        short[] directAudio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
        short[] legacyAudio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];

        try (MgbaSession direct = new MgbaSession(platform);
             MgbaSession legacy = new MgbaSession(platform)) {
            direct.loadRom(rom);
            legacy.loadRom(rom);
            ByteBuffer buffer = direct.directFrameBuffer();
            assertNotNull(buffer);
            assertTrue(buffer.isDirect());
            assertTrue(buffer.isReadOnly());
            assertEquals(direct.framePixels() * Integer.BYTES, buffer.capacity());

            int directFrames = direct.runFrameDirect(0, directAudio);
            int[] legacyPixels = new int[legacy.framePixels()];
            int legacyFrames = legacy.runFrame(0, legacyPixels, legacyAudio);
            assertTrue(directFrames >= 0);
            assertTrue(legacyFrames >= 0);
            assertEquals(1, direct.frameCounter());
            assertEquals(1, legacy.frameCounter());

            Bitmap bitmap = Bitmap.createBitmap(
                    direct.videoWidth(), direct.videoHeight(), Bitmap.Config.ARGB_8888);
            bitmap.setHasAlpha(false);
            buffer.position(0);
            bitmap.copyPixelsFromBuffer(buffer);
            int[] directPixels = new int[direct.framePixels()];
            bitmap.getPixels(directPixels, 0, direct.videoWidth(), 0, 0,
                    direct.videoWidth(), direct.videoHeight());
            assertArrayEquals(asset, legacyPixels, directPixels);
            bitmap.recycle();
        }
    }
```

- [ ] **Step 2: Run instrumentation compilation to verify it fails**

```sh
tools/android_project/gradlew -p mgba-android :core:compileDebugAndroidTestJavaWithJavac
```

Expected: compilation fails because `directFrameBuffer` and `runFrameDirect` are undefined.

- [ ] **Step 3: Add JNI direct-buffer and direct-frame entry points**

Add to `mgba_android.c` immediately after the existing `nativeRunFrame` function:

```c
JNIEXPORT jobject JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoBuffer(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    size_t byte_count = 0;
    const void* buffer = mgba_session_video_buffer(
            session_from_handle(handle), &byte_count);
    if (!buffer || !byte_count) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, (void*) buffer, (jlong) byte_count);
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeRunFrameDirect(
        JNIEnv* env, jclass clazz, jlong handle, jint keys, jshortArray audio) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !audio) {
        return -1;
    }
    jsize audio_capacity = (*env)->GetArrayLength(env, audio);
    jshort* audio_data = (*env)->GetShortArrayElements(env, audio, NULL);
    if (!audio_data) {
        return -1;
    }
    int produced = mgba_session_run_frame_direct(
            session, (uint32_t) keys, (int16_t*) audio_data,
            (size_t) audio_capacity);
    (*env)->ReleaseShortArrayElements(
            env, audio, audio_data, produced >= 0 ? 0 : JNI_ABORT);
    return produced;
}
```

- [ ] **Step 4: Cache and expose one read-only Java buffer**

Add the import and field in `MgbaSession.java`:

```java
import java.nio.ByteBuffer;
```

```java
    private ByteBuffer frameBuffer;
```

After `loaded = true;` in both `loadRom` overloads, call:

```java
        initializeDirectFrameBuffer();
```

Add these methods after the existing `runFrame` method:

```java
    /**
     * Returns the session-owned native frame buffer, or null when the direct path
     * is unavailable. The buffer becomes invalid when this session is closed.
     */
    public synchronized ByteBuffer directFrameBuffer() {
        requireLoaded();
        return frameBuffer;
    }

    /** Runs one frame without producing the compatibility ARGB int array. */
    public synchronized int runFrameDirect(int keys, short[] stereoAudio) {
        requireLoaded();
        if (frameBuffer == null) {
            throw new IllegalStateException("Direct frame buffer is unavailable");
        }
        if (stereoAudio == null || stereoAudio.length < MIN_AUDIO_BUFFER_SAMPLES) {
            throw new IllegalArgumentException("Audio buffer is too small");
        }
        return nativeRunFrameDirect(handle, keys & 0x3FF, stereoAudio);
    }

    private void initializeDirectFrameBuffer() {
        ByteBuffer buffer = nativeVideoBuffer(handle);
        int expectedBytes = framePixels() * Integer.BYTES;
        frameBuffer = buffer != null && buffer.isDirect()
                && buffer.capacity() == expectedBytes
                ? buffer.asReadOnlyBuffer() : null;
    }
```

Set `frameBuffer = null;` in `close` immediately before `nativeDestroy(handle)`:

```java
        if (handle != 0) {
            frameBuffer = null;
            nativeDestroy(handle);
            handle = 0;
            loaded = false;
        }
```

Add native declarations beside `nativeRunFrame`:

```java
    private static native ByteBuffer nativeVideoBuffer(long handle);
    private static native int nativeRunFrameDirect(long handle, int keys, short[] audio);
```

- [ ] **Step 5: Run core compilation and connected instrumentation**

```sh
tools/android_project/gradlew -p mgba-android \
  :core:assembleDebugAndroidTest :core:assembleDebug
ANDROID_SERIAL=YOUR_DEVICE_SERIAL tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
```

Expected: the direct-buffer equivalence test passes for GBA, GB, and GBC; all existing instrumentation remains green.

- [ ] **Step 6: Re-run host tests after JNI integration**

```sh
cmake --build build/mgba-direct-red
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
ctest --test-dir build/mgba-direct-red --output-on-failure
```

Expected: both CTests still pass under ASan/UBSan.

- [ ] **Step 7: Delivery checkpoint**

With explicit shipping approval only:

```sh
git add mgba-android/core/src/main/cpp/mgba_android.c \
  mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java \
  mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java
git commit -m "feat(android): expose direct mGBA frame buffer"
```

Otherwise leave these files unstaged.

---

### Task 3: Use the direct path in the Android application

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`

**Interfaces:**
- Consumes: Task 2's cached `ByteBuffer`, direct frame method, and nullable startup fallback signal.
- Produces: allocation-free normal direct publication with legacy `int[]` fallback.

- [ ] **Step 1: Add opaque bitmap creation and direct publication**

Add to `EmulatorView.java`:

```java
import java.nio.ByteBuffer;
```

Replace the initial frame field with:

```java
    private volatile Bitmap frame = createFrameBitmap(
            MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT);
```

Add this helper before the constructors:

```java
    private static Bitmap createFrameBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setHasAlpha(false);
        return bitmap;
    }
```

In `setVideoSize`, replace `Bitmap.createBitmap` with:

```java
            frame = createFrameBitmap(w, h);
```

Replace `publishFrame(int[])` and add its overload:

```java
    void publishFrame(int[] pixels) {
        synchronized (frameLock) {
            frame.setPixels(pixels, 0, videoWidth, 0, 0, videoWidth, videoHeight);
        }
        framePublished();
    }

    void publishFrame(ByteBuffer pixels) {
        synchronized (frameLock) {
            pixels.position(0);
            frame.copyPixelsFromBuffer(pixels);
        }
        framePublished();
    }

    private void framePublished() {
        hasFrame = true;
        status = "Running";
        postInvalidateOnAnimation();
    }
```

- [ ] **Step 2: Select frame transport once per session**

Add to `EmulationRunner.java`:

```java
import java.nio.ByteBuffer;
```

Replace the current pixel/audio allocation with:

```java
            ByteBuffer directPixels = session.directFrameBuffer();
            int[] pixels = directPixels == null ? new int[session.framePixels()] : null;
            short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
```

Replace the frame call with:

```java
                int audioFrames = directPixels == null
                        ? session.runFrame(view.keys(), pixels, audio)
                        : session.runFrameDirect(view.keys(), audio);
```

Replace the publication call with:

```java
                    if (directPixels == null) {
                        view.publishFrame(pixels);
                    } else {
                        view.publishFrame(directPixels);
                    }
```

Keep the existing timing boundaries around these calls so baseline and candidate phase totals remain comparable.

- [ ] **Step 3: Verify the hot loop has no new allocation or logging**

```sh
python3 - <<'PY'
from pathlib import Path
s = Path('mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java').read_text()
block = s[s.index('while (running)'):s.index('if (autoResume)')]
normal = block.replace('throw new IllegalStateException("mGBA failed to run a frame");', '')
assert 'new ' not in normal
assert 'Log.' in block  # Existing ten-second aggregate log only.
assert 'session.runFrameDirect' in block
assert 'session.runFrame(view.keys(), pixels, audio)' in block
assert 'view.publishFrame(directPixels)' in block
print('direct and fallback frame paths are allocation-free in the hot loop')
PY
```

Expected: the confirmation line prints.

- [ ] **Step 4: Run app unit tests and build the benchmark APK**

```sh
tools/android_project/gradlew -p mgba-android \
  :app:testDebugUnitTest :app:assembleBenchmark
```

Expected: all app unit tests pass and `app-benchmark.apk` builds.

- [ ] **Step 5: Run connected core pixel-equivalence tests again**

```sh
ANDROID_SERIAL=YOUR_DEVICE_SERIAL tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
```

Expected: all instrumentation tests pass after application integration.

- [ ] **Step 6: Delivery checkpoint**

With explicit shipping approval only:

```sh
git add \
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java \
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java
git commit -m "perf(android): publish mGBA frames from direct buffer"
```

Otherwise leave these files unstaged.

---

### Task 4: Validate and measure the candidate

**Files:**
- Verify: all candidate files listed in the file map
- Create ignored artifacts: `build/perf/profile-candidate-{1,2,3}/`
- Create ignored artifact: `build/perf/candidate-summary.txt`
- Create ignored artifact: `build/perf/candidate-acceptance.txt`
- Create ignored artifacts: `build/perf/candidate-perf.data`, `build/perf/candidate-simpleperf-report.txt`, `build/perf/candidate-mgba-symbols.txt`

**Interfaces:**
- Consumes: Tasks 1–3 and baseline `build/perf/baseline-summary.txt` plus `build/perf/profile-baseline-{1,2,3}`.
- Produces: an accepted measured candidate or an exact restoration of all pre-candidate files.

- [ ] **Step 1: Run the complete automated gate**

```sh
set -euo pipefail
bash -n mgba-android/tools/measure-session.sh
tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:testDebugUnitTest :app:assembleBenchmark \
  :core:assembleBenchmark :core:assembleDebugAndroidTest
ANDROID_SERIAL=YOUR_DEVICE_SERIAL tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
rm -rf build/mgba-direct-final
cmake -S mgba-android/smoke -B build/mgba-direct-final -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DGARNACHA_SANITIZERS=ON
cmake --build build/mgba-direct-final
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
ctest --test-dir build/mgba-direct-final --output-on-failure
git diff --check
```

Expected: Gradle reports `BUILD SUCCESSFUL`, all connected instrumentation passes, both CTests pass under sanitizers, and diff checks are clean.

- [ ] **Step 2: Prove excluded scope is unchanged**

```sh
find vendor/mgba src/main.c -type f -print0 \
  | sort -z | xargs -0 sha256sum | sha256sum \
  > build/perf/direct-buffer-excluded-final.sha256
cmp build/perf/direct-buffer-prechange/excluded.sha256 \
  build/perf/direct-buffer-excluded-final.sha256
```

Expected: `cmp` exits zero.

- [ ] **Step 3: Install the optimized benchmark build without clearing imported data**

```sh
adb -s YOUR_DEVICE_SERIAL install -r \
  mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
adb -s YOUR_DEVICE_SERIAL shell pm path com.trebuchetdynamics.garnacha
```

Expected: install reports `Success` and `pm path` returns the benchmark package. Open the already imported `minish` entry through the app UI; do not read or copy its ROM or app-private files.

- [ ] **Step 4: Collect three comparable candidate runs**

For each run, start representative `minish` gameplay, warm up one minute, then execute the matching pair. Cool the device between runs so all starting battery temperatures remain within 2°C.

```sh
export ANDROID_SERIAL=YOUR_DEVICE_SERIAL
export OUT_DIR=build/perf
mgba-android/tools/measure-session.sh profile-start
# Keep representative gameplay running for at least 600 seconds.
mgba-android/tools/measure-session.sh profile-collect candidate-1
```

```sh
export ANDROID_SERIAL=YOUR_DEVICE_SERIAL
export OUT_DIR=build/perf
mgba-android/tools/measure-session.sh profile-start
# Keep representative gameplay running for at least 600 seconds.
mgba-android/tools/measure-session.sh profile-collect candidate-2
```

```sh
export ANDROID_SERIAL=YOUR_DEVICE_SERIAL
export OUT_DIR=build/perf
mgba-android/tools/measure-session.sh profile-start
# Keep representative gameplay running for at least 600 seconds.
mgba-android/tools/measure-session.sh profile-collect candidate-3
```

Expected: each run directory contains complete `MgbaPerf`, `gfxinfo`, `meminfo`, device, and temperature evidence; no command accesses ROM content.

- [ ] **Step 5: Generate the candidate summary and verify run comparability**

```sh
mgba-android/tools/measure-session.sh profile-summary \
  build/perf/profile-candidate-1 \
  build/perf/profile-candidate-2 \
  build/perf/profile-candidate-3 \
  | tee build/perf/candidate-summary.txt
python3 - <<'PY'
from pathlib import Path
temps = []
for n in (1, 2, 3):
    env = dict(line.split('=', 1) for line in
        Path(f'build/perf/profile-candidate-{n}/device.env').read_text().splitlines())
    temps.append(int(env['start_battery_temp']))
    assert int(env['duration_seconds']) >= 600
assert max(temps) - min(temps) <= 20
print(f'candidate start-temperature spread={(max(temps)-min(temps))/10:.1f}C')
PY
```

Expected: summary reports exactly three runs and temperature spread is at most 2.0°C.

- [ ] **Step 6: Apply the numerical acceptance gate**

```sh
python3 - <<'PY' | tee build/perf/candidate-acceptance.txt
from pathlib import Path

def values(path):
    return dict(line.split('=', 1) for line in Path(path).read_text().splitlines()
                if '=' in line)

baseline = values('build/perf/baseline-summary.txt')
candidate = values('build/perf/candidate-summary.txt')
base_total = int(baseline['avg_us'])
candidate_total = int(candidate['avg_us'])
threshold = base_total * 90 // 100
checks = {
    'three_runs': int(candidate['runs']) == 3,
    'total_at_most_90_percent': candidate_total <= threshold,
    'late_not_increased': int(candidate['late']) <= int(baseline['late']),
    'underruns_not_increased': int(candidate['underruns']) <= int(baseline['underruns']),
    'jank_not_increased': float(candidate['janky_pct']) <= float(baseline['janky_pct']),
}
for name, passed in checks.items():
    print(f'{name}={str(passed).lower()}')
print(f'baseline_avg_us={base_total}')
print(f'candidate_avg_us={candidate_total}')
print(f'threshold_us={threshold}')
print(f'improvement_percent={(base_total-candidate_total)*100/base_total:.2f}')
assert all(checks.values())
PY
```

Expected: every check is `true`, candidate total is at most 1,644 microseconds, and the command exits zero.

- [ ] **Step 7: Compare Android frame-time percentiles**

```sh
python3 - <<'PY'
import re
from pathlib import Path

percentiles = ('50th', '90th', '95th', '99th')
def run_values(prefix):
    result = {key: [] for key in percentiles}
    for n in (1, 2, 3):
        text = Path(f'build/perf/profile-{prefix}-{n}/gfxinfo.txt').read_text()
        for key in percentiles:
            match = re.search(rf'^{key} percentile: (\d+)ms$', text, re.MULTILINE)
            assert match, (prefix, n, key)
            result[key].append(int(match.group(1)))
    return {key: max(values) for key, values in result.items()}

baseline = run_values('baseline')
candidate = run_values('candidate')
for key in percentiles:
    print(f'{key}: baseline_max={baseline[key]}ms candidate_max={candidate[key]}ms')
    assert candidate[key] <= baseline[key]
PY
```

Expected: candidate 50th, 90th, 95th, and 99th percentile maxima do not exceed baseline.

- [ ] **Step 8: Capture and symbolicate candidate simpleperf evidence**

```sh
set -euo pipefail
mapfile -t LIB_DIRS < <(find \
  mgba-android/core/build/intermediates/cxx/RelWithDebInfo \
  -type d -path '*/obj/arm64-v8a' | sort)
[ "${#LIB_DIRS[@]}" -eq 1 ]
rm -rf binary_cache build_cache
ANDROID_SERIAL=YOUR_DEVICE_SERIAL python3 \
  /usr/lib/android-sdk/ndk/22.1.7171670/simpleperf/app_profiler.py \
  -p com.trebuchetdynamics.garnacha \
  -r '-e task-clock:u -f 1000 -g --duration 30' \
  -lib "${LIB_DIRS[0]}" --disable_adb_root \
  -o build/perf/candidate-perf.data
python3 /usr/lib/android-sdk/ndk/22.1.7171670/simpleperf/report.py \
  -i build/perf/candidate-perf.data --symfs "$PWD/binary_cache" --sort symbol \
  > build/perf/candidate-simpleperf-report.txt
export MGBA_SO="${LIB_DIRS[0]}/libmgba-android.so"
export ADDR2LINE=/usr/lib/android-sdk/ndk/22.1.7171670/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-addr2line
python3 - <<'PY'
import os, re, subprocess
from pathlib import Path
out = []
for line in Path('build/perf/candidate-simpleperf-report.txt').read_text().splitlines():
    match = re.match(r'([0-9.]+%)\s+libmgba-android\.so\[\+([0-9a-f]+)\]$', line.strip())
    if not match:
        continue
    symbol, source = subprocess.check_output([
        os.environ['ADDR2LINE'], '-f', '-C', '-e', os.environ['MGBA_SO'],
        '0x' + match.group(2)], text=True).splitlines()[:2]
    out.append(f'{match.group(1)} symbol={symbol} source={source}')
    if len(out) == 30:
        break
Path('build/perf/candidate-mgba-symbols.txt').write_text('\n'.join(out) + '\n')
PY
test -s build/perf/candidate-perf.data
test -s build/perf/candidate-simpleperf-report.txt
test -s build/perf/candidate-mgba-symbols.txt
! grep -q 'symbol=mgba_session_run_frame source=' \
  build/perf/candidate-mgba-symbols.txt
```

Expected: all artifacts are non-empty and the former conversion-loop lines are absent from sampled candidate execution.

- [ ] **Step 9: Perform the behavior gate**

On the benchmark build, verify normal-speed `minish` gameplay has correct colors and opacity, responsive input, stable audio, working rewind, save/load state, reset, and clean exit/relaunch with savedata. Also launch the bundled GBA, GB, and GBC instrumentation assets through their automated tests rather than exposing user ROM content.

Expected: no visual, audio, input, rewind, state, savedata, pacing, or lifecycle regression.

- [ ] **Step 10: Keep or restore the candidate**

If every automated, numerical, percentile, profiler, and behavior gate passed, retain the candidate unstaged and record:

```sh
printf '%s\n' \
  'decision=accept' \
  'candidate=direct-frame-buffer' \
  'baseline_summary=build/perf/baseline-summary.txt' \
  'candidate_summary=build/perf/candidate-summary.txt' \
  'acceptance=build/perf/candidate-acceptance.txt' \
  'profile=build/perf/candidate-mgba-symbols.txt' \
  > build/perf/direct-buffer-decision.txt
```

If any gate failed, restore every candidate file exactly:

```sh
tar -xzf build/perf/direct-buffer-prechange/files.tar.gz
printf '%s\n' \
  'decision=revert' \
  'candidate=direct-frame-buffer' \
  'reason=acceptance gate failed; inspect candidate-acceptance and profile artifacts' \
  > build/perf/direct-buffer-decision.txt
git diff --check
```

Expected: accepted production files exist only after all gates pass; otherwise all candidate files match their prechange snapshot.

- [ ] **Step 11: Final delivery checkpoint**

Only after an accepted result and explicit shipping approval:

```sh
git add \
  mgba-android/core/src/main/cpp/mgba_session.h \
  mgba-android/core/src/main/cpp/mgba_session.c \
  mgba-android/smoke/mgba_session_test.c \
  mgba-android/core/src/main/cpp/mgba_android.c \
  mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java \
  mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java \
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java \
  mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java
git commit -m "perf(android): use direct mGBA frame buffer"
```

Before committing, confirm `git diff --cached --name-only` contains exactly those eight paths. Do not commit rejected candidate code or generated `build/perf` artifacts.
