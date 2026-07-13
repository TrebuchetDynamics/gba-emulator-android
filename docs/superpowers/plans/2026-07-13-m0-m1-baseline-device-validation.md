# M0 Baseline + M1 Physical-Device Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the in-flight private-file ROM work with green CI (M0), then produce a physical arm64 device validation receipt with a release-viability verdict (M1), per the approved spec `docs/superpowers/specs/2026-07-13-mgba-product-roadmap-design.md`.

**Architecture:** The custom mGBA Android product lives in `mgba-android/` (app module `com.trebuchetdynamics.mgba` + core AAR wrapping the pinned `vendor/mgba` submodule via JNI in `mgba_android.c`). M0 is verification + split commits of already-written changes. M1 adds a small, unit-tested `FrameStats` accumulator logged from the emulation loop, then runs scripted measurement sessions on a physical arm64 device and records everything as a `docs/validation/` receipt ending in an explicit verdict.

**Tech Stack:** Android Gradle (JDK 17, SDK 35, NDK `22.1.7171670`, CMake `3.18.1`), JNI/C (mGBA 0.10.5), JUnit 4 (JVM unit tests), AndroidX instrumentation tests, `adb`/`dumpsys` for device measurement, GitHub Actions CI.

## Global Constraints

- mGBA stays an unmodified pinned submodule at `26b7884bc25a5933960f3cdcd98bac1ae14d42e2` (`0.10.5`); never edit files under `vendor/mgba`.
- No game or BIOS content is ever committed; tests use only the MIT `gba-tests` ROMs already in `mgba-android/core/src/androidTest/assets/`.
- Manual validation content beyond those: only freely-licensed homebrew whose license you verify first, or ROMs dumped from cartridges the tester owns. Record source and license in the receipt.
- Zero Android lint errors on every commit (`lintDebug`).
- No signing keys or machine-local properties are committed.
- Every measurement claim in the receipt must state the exact command that produced it.
- Toolchain: JDK 17, Android SDK 35, NDK `22.1.7171670`, CMake `3.18.1` (host smoke also builds with system CMake ≥ 3.18 + Ninja).
- All work happens on branch `mvp`. The worktree currently holds ONLY the 9 modified files this plan lands in Tasks 2–4 — do not `git add -A` anything else that may appear.
- End commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Verify the in-flight worktree is green (host + physical device)

The 9 dirty files are finished work (private-file ROM import, no retained Java ROM copy) that was validated on an x86_64 AVD on 2026-07-09. Before committing, re-verify on the host and — for the first time — on the physical arm64 device.

**Files:**
- No file changes. Verification only.

**Interfaces:**
- Produces: a connected arm64 device usable by all later tasks (`$ANDROID_SERIAL` known); local proof that host smoke, lint, benchmark builds, and the 5-test instrumentation suite pass, quoted verbatim in Task 5's receipt.

- [ ] **Step 1: Initialize submodules**

Run:
```sh
cd /home/xel/git/trebuchet-dynamics/game-emulator-mobile
git submodule update --init --depth 1
```
Expected: `vendor/mgba` (and the SkyEmu submodules) check out without error.

- [ ] **Step 2: Build and run the host smoke test**

Run:
```sh
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/mgba-smoke
ctest --test-dir build/mgba-smoke --output-on-failure
```
Expected: build succeeds; CTest reports `100% tests passed, 0 tests failed out of 1`.

- [ ] **Step 3: Lint and build the optimized product APK/AAR**

Run:
```sh
tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:assembleBenchmark :core:assembleBenchmark :core:assembleDebugAndroidTest
```
Expected: `BUILD SUCCESSFUL`; lint reports 0 errors; outputs exist at
`mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk` and
`mgba-android/core/build/outputs/aar/core-benchmark.aar`.

- [ ] **Step 4: Connect the physical arm64 device**

On the device: enable Developer options and USB debugging, plug in via USB, accept the RSA prompt. Then:
```sh
adb devices -l
```
Expected: exactly one `device` line (not `unauthorized`). Note its serial and export it:
```sh
export ANDROID_SERIAL=<serial from adb devices>
adb shell getprop ro.product.cpu.abi
```
Expected: `arm64-v8a`. If it prints anything else, STOP — this device cannot run the M1 gates.

- [ ] **Step 5: Run the 5-test instrumentation suite on the device**

Run:
```sh
ANDROID_SERIAL=$ANDROID_SERIAL tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
```
Expected: `BUILD SUCCESSFUL`; the XML/HTML report under
`mgba-android/core/build/reports/androidTests/connected/` shows 5 tests
(`pinnedCoreLoadsAndInitializesOnAndroid`, `sessionRunsMitLicensedRomAndRestoresState`,
`sessionRunsMitLicensedRomFromPrivateFile`, `cartridgeSavedataRoundTripsBetweenSessions`,
`closedSessionRejectsFurtherUse`), 0 failures/errors/skips. Save the report XML somewhere outside the repo (e.g. the session scratchpad) — Task 5 quotes it. If any test fails on arm64, STOP and report: that is a finding, not something to patch around silently.

---

### Task 2: Commit 1 — core private-file ROM loading

**Files:**
- Modify (already edited, commit as-is): `mgba-android/core/src/main/cpp/mgba_android.c` (adds `nativeLoadRomFile` JNI: opens the path with `VFileOpen(O_RDONLY)`, size-checks against `MAX_GBA_ROM_SIZE`, reads into one native allocation, loads via `VFileFromMemory`)
- Modify: `mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java` (adds `public synchronized void loadRom(File rom)` and `private static native boolean nativeLoadRomFile(long handle, String path)`)
- Test (already edited): `mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java` (adds `sessionRunsMitLicensedRomFromPrivateFile`)

**Interfaces:**
- Consumes: green verification from Task 1.
- Produces: committed `MgbaSession.loadRom(java.io.File)` — throws `IllegalStateException` if closed/already loaded, `IllegalArgumentException` if the file is missing/empty/> 32 MiB or rejected by mGBA. Task 3's app commit depends on this API.

- [ ] **Step 1: Review the staged scope**

Run:
```sh
git diff --stat -- mgba-android/core
```
Expected: exactly the 3 files above (`mgba_android.c` +60, `MgbaSession.java` +19, `MgbaCoreInstrumentedTest.java` +31). Anything else listed → STOP and reconcile before committing.

- [ ] **Step 2: Commit**

```sh
git add mgba-android/core/src/main/cpp/mgba_android.c \
        mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java \
        mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java
git commit -m "feat(core): load ROMs from private files without a Java heap copy

nativeLoadRomFile reads a seekable private file into the single native
allocation owned by the session; MgbaSession.loadRom(File) validates
size/state on the Java side. Verified by the new
sessionRunsMitLicensedRomFromPrivateFile instrumentation test (5 tests,
0 failures on arm64 hardware and API 34 x86_64 AVD).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Expected: commit created; `git status` no longer lists the 3 core files.

---

### Task 3: Commit 2 — app atomic private-file import

**Files:**
- Modify (already edited, commit as-is): `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java` (streams the SAF document through a 64 KiB buffer into an atomic SHA-256-named file under `filesDir/roms/`, fsync + rename, tracks `romFile`/`romId` instead of `byte[] romData`)
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java` (takes `File rom, String romId`; calls `session.loadRom(rom)`; save file named by `romId` instead of re-hashing)

**Interfaces:**
- Consumes: `MgbaSession.loadRom(File)` from Task 2.
- Produces: committed app host whose `EmulationRunner` constructor is `EmulationRunner(Context, EmulatorView, File rom, String romId, ErrorListener)` — Task 6 modifies this class.

- [ ] **Step 1: Review the staged scope**

Run:
```sh
git diff --stat -- mgba-android/app
```
Expected: exactly `MainActivity.java` and `EmulationRunner.java`.

- [ ] **Step 2: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java
git commit -m "feat(app): import ROMs atomically to private files

The selected document streams through a 64 KiB buffer into an
fsync-then-rename SHA-256-named file under filesDir/roms/. The emulation
runner now receives the File and precomputed id, eliminating the retained
32 MiB Java ROM copy and resume-time re-hashing.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Expected: commit created; only the 5 doc files remain dirty.

---

### Task 4: Commit 3 — docs, SkyEmu freeze note, push, CI green

**Files:**
- Modify (already edited): `MVP.md`, `mgba-android/README.md`, `docs/validation/goal-audit-2026-07-09.md`, `docs/validation/mgba-performance-2026-07-09.md`
- Modify (new edit): `MVP.md` — add the SkyEmu freeze note below

**Interfaces:**
- Produces: clean worktree, remote `mvp` branch green in CI — the M0 exit criteria.

- [ ] **Step 1: Add the SkyEmu freeze note**

In `MVP.md`, directly under the heading `## Build the installable SkyEmu MVP`, insert this paragraph (before the "Requirements:" line):

```markdown
The SkyEmu fork is feature-frozen: it is kept buildable exactly as documented
below and receives no further product work. All product development continues
in the custom mGBA app (see
`docs/superpowers/specs/2026-07-13-mgba-product-roadmap-design.md`).
```

- [ ] **Step 2: Commit the docs**

```sh
git add MVP.md mgba-android/README.md docs/validation/goal-audit-2026-07-09.md \
        docs/validation/mgba-performance-2026-07-09.md
git commit -m "docs: record private-file import evidence and freeze the SkyEmu track

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git status --short
```
Expected: empty `git status --short` output (clean worktree).

- [ ] **Step 3: Push and watch CI**

```sh
git push origin mvp
gh run watch --exit-status || gh run list --branch mvp --limit 1
```
Expected: the `Build Android MVP` workflow completes with conclusion `success`. If it fails, read the failing step's log, fix, commit, and re-push before proceeding — M0 exits only on green CI.

---

### Task 5: M1 receipt skeleton + benchmark app smoke on the device

**Files:**
- Create: `docs/validation/mgba-arm64-device-2026-07-13.md` (adjust the date in the filename and title to the actual execution date)

**Interfaces:**
- Consumes: instrumentation report and device serial from Task 1.
- Produces: the receipt document that Tasks 7–9 append to; the benchmark APK installed and proven to render on the device.

- [ ] **Step 1: Record device identity**

Run each and note the output:
```sh
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.soc.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
```

- [ ] **Step 2: Install the benchmark APK and render the MIT test ROM**

```sh
adb install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
adb shell am start -n com.trebuchetdynamics.mgba/com.trebuchetdynamics.emulator.app.MainActivity
```
On the device: the app opens with its "tap to choose a ROM" status. Push and import the MIT test ROM:
```sh
adb push mgba-android/core/src/androidTest/assets/hello.gba /sdcard/Download/hello.gba
```
Tap the screen, pick `hello.gba` in the system document picker. Expected: the app shows "Importing ROM…" then renders the "Hello world!" test screen with the touch control overlay. Capture proof:
```sh
adb exec-out screencap -p > docs/validation/mgba-arm64-device-hello.png
adb logcat -d | grep -iE "fatal|ANR in com.trebuchetdynamics" | tail -20
```
Expected: screenshot saved; no FATAL/ANR lines for the app.

- [ ] **Step 3: Write the receipt skeleton**

Create `docs/validation/mgba-arm64-device-2026-07-13.md`:

```markdown
# Physical arm64 device validation — 2026-07-13

M1 gate receipt for the roadmap spec
(`docs/superpowers/specs/2026-07-13-mgba-product-roadmap-design.md`).
APK under test: `app-benchmark.apk` (RelWithDebInfo native code, -O2) built
from commit <git rev-parse --short HEAD>.

## Device

| Property | Value |
|---|---|
| Manufacturer / model | <getprop output> |
| SoC | <getprop output> |
| Android version / API | <release> / <sdk> |
| ABI | arm64-v8a |

## Instrumentation suite on arm64

`:core:connectedDebugAndroidTest` on this device: 5 tests, <N> failures
(<paste one-line summary from the Task 1 report XML>).

## Benchmark app smoke

MIT `gba-tests/ppu/hello.gba` imported via DocumentsUI and rendered
(screenshot: `mgba-arm64-device-hello.png`); no FATAL/ANR logcat entries.

## Sustained gameplay measurements

Pending (Task 7).

## Interaction and integrity checks

Pending (Task 8).

## Verdict

Pending (Task 9).
```
Replace every `<...>` with real values before committing. No placeholder may survive the commit.

- [ ] **Step 4: Commit**

```sh
git add docs/validation/mgba-arm64-device-2026-07-13.md docs/validation/mgba-arm64-device-hello.png
git commit -m "docs: start arm64 device validation receipt with instrumentation and smoke evidence

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: FrameStats instrumentation in the emulation loop

The perf doc (`docs/validation/mgba-performance-2026-07-09.md`, "Next measured candidates") requires instrumenting frame timing and audio underruns before any pacing redesign. Add a pure, JVM-unit-tested accumulator and log one summary line every ~10 s from `EmulationRunner`.

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FrameStats.java`
- Create: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FrameStatsTest.java`
- Modify: `mgba-android/app/build.gradle` (add JUnit test dependency)
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`

**Interfaces:**
- Consumes: `EmulationRunner` as committed in Task 3; `FRAME_NANOS = 16_743_000L` (its existing frame budget constant).
- Produces: logcat lines under tag `MgbaPerf` with the exact format `frames=<n> avg_us=<n> max_us=<n> late=<n> underruns=<n>`, one per ~10 s window. Task 7's session script greps this tag and format.

- [ ] **Step 1: Add the JUnit dependency**

In `mgba-android/app/build.gradle`, change the dependencies block to:
```groovy
dependencies {
    implementation project(':core')
    testImplementation 'junit:junit:4.13.2'
}
```

- [ ] **Step 2: Write the failing test**

Create `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FrameStatsTest.java`:
```java
package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameStatsTest {
    private static final long BUDGET_NANOS = 16_743_000L;

    @Test
    public void summarizesWindowAndCountsLateFrames() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertFalse(stats.hasFrames());
        stats.record(10_000_000L);
        stats.record(20_000_000L);
        assertTrue(stats.hasFrames());
        assertEquals("frames=2 avg_us=15000 max_us=20000 late=1 underruns=3",
                stats.summarizeAndReset(3));
    }

    @Test
    public void resetsAfterSummary() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        stats.record(20_000_000L);
        stats.summarizeAndReset(0);
        assertFalse(stats.hasFrames());
        stats.record(1_000_000L);
        assertEquals("frames=1 avg_us=1000 max_us=1000 late=0 underruns=7",
                stats.summarizeAndReset(7));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:
```sh
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*FrameStatsTest'
```
Expected: FAIL — compilation error, `FrameStats` does not exist.

- [ ] **Step 4: Implement FrameStats**

Create `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FrameStats.java`:
```java
package com.trebuchetdynamics.emulator.app;

import java.util.Locale;

/** Accumulates per-frame work durations for one logging window. Not thread-safe. */
final class FrameStats {
    private final long budgetNanos;
    private long frames;
    private long lateFrames;
    private long totalNanos;
    private long maxNanos;

    FrameStats(long budgetNanos) {
        this.budgetNanos = budgetNanos;
    }

    void record(long frameNanos) {
        frames++;
        totalNanos += frameNanos;
        if (frameNanos > maxNanos) {
            maxNanos = frameNanos;
        }
        if (frameNanos > budgetNanos) {
            lateFrames++;
        }
    }

    boolean hasFrames() {
        return frames > 0;
    }

    /** Formats one summary line for the window and resets it. Requires hasFrames(). */
    String summarizeAndReset(int underruns) {
        String line = String.format(Locale.US,
                "frames=%d avg_us=%d max_us=%d late=%d underruns=%d",
                frames, totalNanos / frames / 1_000, maxNanos / 1_000,
                lateFrames, underruns);
        frames = 0;
        lateFrames = 0;
        totalNanos = 0;
        maxNanos = 0;
        return line;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```sh
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest --tests '*FrameStatsTest'
```
Expected: PASS (`BUILD SUCCESSFUL`, 2 tests).

- [ ] **Step 6: Wire FrameStats into EmulationRunner**

In `EmulationRunner.java`, add imports:
```java
import android.util.Log;
```
Add constants/fields next to `FRAME_NANOS`:
```java
    private static final String PERF_TAG = "MgbaPerf";
    private static final long PERF_LOG_INTERVAL_NANOS = 10_000_000_000L;
```
Replace the `while (running)` loop body inside `run()` with (only the marked lines are new):
```java
            int[] pixels = new int[MgbaSession.FRAME_PIXELS];
            short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
            FrameStats stats = new FrameStats(FRAME_NANOS);                  // new
            long nextPerfLog = SystemClock.elapsedRealtimeNanos()            // new
                    + PERF_LOG_INTERVAL_NANOS;                               // new
            long nextFrame = SystemClock.elapsedRealtimeNanos();
            while (running) {
                long frameStart = SystemClock.elapsedRealtimeNanos();        // new
                int audioFrames = session.runFrame(view.keys(), pixels, audio);
                if (audioFrames < 0) {
                    throw new IllegalStateException("mGBA failed to run a frame");
                }
                if (audioTrack != null && audioFrames > 0) {
                    audioTrack.write(audio, 0, audioFrames * 2, AudioTrack.WRITE_BLOCKING);
                }
                view.publishFrame(pixels);
                long now = SystemClock.elapsedRealtimeNanos();               // new
                stats.record(now - frameStart);                              // new
                if (now >= nextPerfLog && stats.hasFrames()) {               // new
                    int underruns = audioTrack == null                       // new
                            ? 0 : audioTrack.getUnderrunCount();             // new
                    Log.i(PERF_TAG, stats.summarizeAndReset(underruns));     // new
                    nextPerfLog = now + PERF_LOG_INTERVAL_NANOS;             // new
                }                                                            // new

                nextFrame += FRAME_NANOS;
                long wait = nextFrame - SystemClock.elapsedRealtimeNanos();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                } else if (wait < -FRAME_NANOS * 4) {
                    nextFrame = SystemClock.elapsedRealtimeNanos();
                }
            }
```
Notes: `AudioTrack.getUnderrunCount()` needs API 24 — equal to the module's `minSdk 24`, so no guard is required. The recorded duration is frame *work* time (emulation + blocking audio write + frame publish), excluding the pacing park; a window whose `avg_us` exceeds 16743 cannot hold full speed.

- [ ] **Step 7: Build, lint, and verify on the device**

```sh
tools/android_project/gradlew -p mgba-android lintDebug :app:assembleBenchmark \
  :app:testDebugUnitTest
adb install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
adb shell am start -n com.trebuchetdynamics.mgba/com.trebuchetdynamics.emulator.app.MainActivity
```
Re-import/resume `hello.gba` on the device, wait ~35 s, then:
```sh
adb logcat -d -s MgbaPerf
```
Expected: ≥ 3 lines matching `frames=... avg_us=... max_us=... late=... underruns=...`, with `frames` near 597 per window (59.727 fps × 10 s) and 0 lint errors from the build step.

- [ ] **Step 8: Commit**

```sh
git add mgba-android/app/build.gradle \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FrameStats.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FrameStatsTest.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java
git commit -m "feat(app): log frame-pacing and audio-underrun stats every 10s

Unit-tested FrameStats accumulator; MgbaPerf logcat lines report per-window
frame count, avg/max work time, late frames, and AudioTrack underruns as
required before any pacing redesign.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Sustained gameplay measurement sessions

**Files:**
- Modify: `docs/validation/mgba-arm64-device-2026-07-13.md` (fill "Sustained gameplay measurements")

**Interfaces:**
- Consumes: `MgbaPerf` log format from Task 6; the receipt from Task 5.
- Produces: measured numbers (frame stats, underruns, gfxinfo jank, memory, battery, thermal) that Task 9's verdict cites.

- [ ] **Step 1: Choose and document test content**

Two sessions minimum:
1. `hello.gba` (MIT, already available) — trivial baseline.
2. A demanding title: either a graphically heavy open homebrew (e.g. a Butano-engine game such as Varooom 3D — download from its official page and verify its license permits use before running) or a commercial ROM dumped from a cartridge the tester owns. Record name, source, and license/ownership basis in the receipt. Do NOT commit any ROM.

- [ ] **Step 2: Prepare an unplugged measurement channel**

Battery/thermal numbers are invalid while USB-powered. Use wireless debugging (Android 11+): on the device enable Developer options → Wireless debugging, then:
```sh
adb pair <ip>:<pair-port>   # code shown on device
adb connect <ip>:<port>
export ANDROID_SERIAL=<ip>:<port>
adb devices
```
then unplug USB. If the device predates wireless debugging, run the session unplugged with no adb, and collect logs after reconnecting (`adb logcat -d` ring buffer holds well over 30 min of MgbaPerf lines at 6 lines/min); note in the receipt that gfxinfo was collected post-session.

- [ ] **Step 3: Run one session per title (30–60 min each)**

Per session:
```sh
adb shell dumpsys battery | grep level          # start battery %
adb shell dumpsys thermalservice | grep -A8 "Current temperatures"   # start temps
adb shell dumpsys gfxinfo com.trebuchetdynamics.mgba reset
adb logcat -c
adb shell am start -n com.trebuchetdynamics.mgba/com.trebuchetdynamics.emulator.app.MainActivity
```
Play actively for the first ~10 min (touch input), then let it run; interact briefly every ~10 min so the workload stays a real game loop. Keep the screen on. After 30–60 min:
```sh
adb logcat -d -s MgbaPerf > /tmp/mgbaperf-session.txt   # or scratchpad path
adb shell dumpsys media.audio_flinger | grep -iE "latency|underrun" | head -20   # output-path latency estimate
adb shell dumpsys gfxinfo com.trebuchetdynamics.mgba | sed -n '/Total frames/,/95th/p'
adb shell dumpsys meminfo com.trebuchetdynamics.mgba | grep -E "TOTAL|Native Heap|Java Heap"
adb shell dumpsys battery | grep level          # end battery %
adb shell dumpsys thermalservice | grep -A8 "Current temperatures"   # end temps + throttling status
```

- [ ] **Step 4: Record the results in the receipt**

Replace the "Pending (Task 7)" section with, per session: title/source/license, duration, session-wide MgbaPerf aggregate (total frames, worst window `avg_us`, worst `max_us`, total late frames, final cumulative `underruns`), gfxinfo janky-frame percentage, meminfo TOTAL/Native/Java, battery % drained per hour, start/end temperatures and any `thermalservice` throttling status change. Every number cites its command. Also state the qualitative check: audio audibly continuous or not, visible stutter or not.

- [ ] **Step 5: Commit**

```sh
git add docs/validation/mgba-arm64-device-2026-07-13.md
git commit -m "docs: record sustained arm64 gameplay measurements

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Controller, lifecycle, and save-integrity checks

**Files:**
- Modify: `docs/validation/mgba-arm64-device-2026-07-13.md` (fill "Interaction and integrity checks")

**Interfaces:**
- Consumes: the installed benchmark app; a Bluetooth gamepad.
- Produces: pass/fail evidence per check for Task 9's verdict.

- [ ] **Step 1: Bluetooth controller**

Pair a Bluetooth gamepad in Android settings, open the app with a ROM running, and verify: D-pad/A/B/L/R/Start/Select all register in-game. Record the controller model and result. If buttons are wrong or dead, record exactly which — that becomes M2/M5 input (remapping is an M5 feature; total non-function is an M2 defect).

- [ ] **Step 2: Pause/resume cycles**

With a ROM running, 3 times: press Home, wait ~10 s, reopen the app.
```sh
adb logcat -d | grep -iE "FATAL|ANR in com.trebuchetdynamics" | tail
```
Expected: emulation resumes each time (the runner restarts on resume by design — note that in-flight unsaved state resets is current behavior, not a defect); no FATAL/ANR lines. Record.

- [ ] **Step 3: Process-death recovery and cartridge-save integrity**

Use a title with battery-backed saves (the demanding homebrew or owned title from Task 7; if neither saves, state that and test with what exists — savedata round-trip already has instrumentation coverage from Task 1). Procedure: create an in-game save, back out to the app's home state (press Home so `persistSavedata` runs on runner stop), then:
```sh
adb shell am force-stop com.trebuchetdynamics.mgba
adb shell run-as com.trebuchetdynamics.mgba ls -l files/saves 2>/dev/null || \
  echo "run-as unavailable on benchmark build - verify via in-game load instead"
adb shell am start -n com.trebuchetdynamics.mgba/com.trebuchetdynamics.emulator.app.MainActivity
```
Re-select the same ROM and load the in-game save. Expected: the save is present and loads. Record.

Save-*state* integrity (as distinct from cartridge saves) has no app UI yet (multiple slots arrive in M5); its on-device coverage is the `sessionRunsMitLicensedRomAndRestoresState` instrumentation test already run on this hardware in Task 1 — state that explicitly in the receipt so the spec's save-state gate is accounted for.

- [ ] **Step 4: Commit**

```sh
git add docs/validation/mgba-arm64-device-2026-07-13.md
git commit -m "docs: record arm64 controller, lifecycle, and save-integrity checks

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: The M1 verdict

**Files:**
- Modify: `docs/validation/mgba-arm64-device-2026-07-13.md` (fill "Verdict")

**Interfaces:**
- Consumes: every measurement from Tasks 5–8.
- Produces: the M1 exit artifact — a verdict that either clears M2 to collapse or defines its scope. The roadmap's next planning session starts from this section.

- [ ] **Step 1: Write the verdict**

Replace "Pending (Task 9)" with exactly one of:

**(a) Release-viable.** Justified only if ALL hold on the demanding title: every 10 s window's `avg_us` < 16743 with headroom (state the worst window); cumulative underruns ≈ 0 and audio audibly continuous; no FATAL/ANR; saves intact across force-stop; battery/thermal drift stated and acceptable (no throttling-induced slowdown observed); controller functional. Then write: "The current Canvas + AudioTrack path is release-viable on this device; M2 collapses to nothing" and list any minor observations as M5-or-later notes.

**(b) Defect list for M2.** Otherwise, list each failed gate as a concrete defect with its measurement, e.g. "worst 10 s window avg_us=19200 on <title> — frame work exceeds the 16743 µs budget; M2 must remove the frame-copy pipeline (candidate 2 in mgba-performance-2026-07-09.md)". Map each defect to the perf doc's ranked candidates where one applies. This list IS the M2 scope — write it so the M2 planning session can consume it directly.

Also update `docs/validation/mgba-performance-2026-07-09.md`: candidate 1 ("Profile on physical arm64 hardware") is now done — annotate it with a pointer to this receipt.

- [ ] **Step 2: Self-check the receipt**

Verify: no `<...>` placeholders or "Pending" markers remain anywhere in the receipt; every number names its command; the verdict cites only measurements recorded above it.

- [ ] **Step 3: Commit and push**

```sh
git add docs/validation/mgba-arm64-device-2026-07-13.md docs/validation/mgba-performance-2026-07-09.md
git commit -m "docs: conclude M1 arm64 device validation with release-viability verdict

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push origin mvp
gh run watch --exit-status || gh run list --branch mvp --limit 1
```
Expected: CI green. M1 complete — the next roadmap step (M2 or M3) is chosen by the verdict.
