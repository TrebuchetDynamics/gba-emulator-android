# mGBA Performance Measurement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Attribute Garnacha Boy's end-to-end Android frame cost and produce a repeatable three-run performance baseline without changing emulator behavior.

**Architecture:** Extend the existing allocation-free `FrameStats` windows with four phase totals, instrument existing frame-loop boundaries, and add connected profiling modes to the existing measurement script. Keep UI rendering in Android `gfxinfo`, enable shell profiling only for the benchmark variant, and stop after evidence identifies one hotspot.

**Tech Stack:** Java, JUnit 4, Android `SystemClock`, `AudioTrack`, `gfxinfo`, simpleperf, Bash, Python 3 standard library, Gradle/AGP.

## Global Constraints

- This plan implements measurement slice 1 only; it makes no production optimization.
- Use the attached SM-S928B for three ten-minute representative GBA runs.
- Normal speed, audio enabled, frameskip `0`, rewind enabled, fixed brightness, one-minute warm-up.
- No per-frame allocation or per-frame logging from instrumentation.
- Never install, launch, inspect, copy, or publish ROM data from the script.
- Keep vendored mGBA, core C/JNI, Java core API, and legacy root `src/main.c` unchanged.
- Benchmark builds alone may set `android:profileable`; release builds must not.
- Candidate acceptance belongs to slice 2: at least 10% lower three-run median with no late-frame, jank, underrun, or behavior regression.
- Preserve unrelated dirty-worktree changes.
- Commit steps are delivery checkpoints; execute them only after explicit user shipping intent.

## File Map

- Modify `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FrameStats.java`: aggregate phase durations and format stable windows.
- Modify `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FrameStatsTest.java`: deterministic phase and rejection coverage.
- Modify `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`: capture existing frame phases with primitive timestamps.
- Create `mgba-android/app/src/benchmark/AndroidManifest.xml`: benchmark-only shell profiling permission.
- Modify `mgba-android/tools/measure-session.sh`: current package ID and connected profile collection/summary modes.
- Modify `mgba-android/README.md`: profiling protocol and command reference.
- Generate under ignored `build/perf/`: three run directories, baseline summary, `perf.data`, and simpleperf report.

---

### Task 1: Extend frame-window aggregation

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FrameStats.java`
- Test: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FrameStatsTest.java`

**Interfaces:**
- Produces: `boolean FrameStats.record(long totalNanos, long nativeNanos, long audioNanos, long publishNanos, long rewindNanos)`.
- Produces stable fields: `native_us`, `audio_us`, `publish_us`, `rewind_us`, `other_us`, `rewind_max_us`.

- [ ] **Step 1: Record excluded-scope content before editing**

```sh
mkdir -p build/perf
find vendor/mgba mgba-android/core/src/main/cpp \
  mgba-android/core/src/main/java src/main.c -type f -print0 \
  | sort -z | xargs -0 sha256sum | sha256sum \
  > build/perf/measurement-excluded.sha256
```

Expected: one aggregate SHA-256 line is written. This protects pre-existing dirty core and legacy files without treating their earlier changes as part of this slice.

- [ ] **Step 2: Replace the unit test with phase-aware expectations**

Use this complete `FrameStatsTest.java`:

```java
package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameStatsTest {
    private static final long BUDGET_NANOS = 16_743_000L;

    private static void record(FrameStats stats, long totalNanos) {
        assertTrue(stats.record(totalNanos, 0, 0, 0, 0));
    }

    @Test
    public void summarizesWindowAndCountsLateFrames() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertFalse(stats.hasFrames());
        record(stats, 10_000_000L);
        record(stats, 20_000_000L);
        assertTrue(stats.hasFrames());
        assertEquals("frames=2 avg_us=15000 max_us=20000 late=1 underruns=3 "
                        + "native_us=0 audio_us=0 publish_us=0 rewind_us=0 "
                        + "other_us=15000 rewind_max_us=0",
                stats.summarizeAndReset(3));
    }

    @Test
    public void reportsAveragePhaseContributionsAndRewindMaximum() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertTrue(stats.record(10_000_000L, 4_000_000L, 2_000_000L,
                1_000_000L, 0));
        assertTrue(stats.record(20_000_000L, 6_000_000L, 4_000_000L,
                2_000_000L, 5_000_000L));
        assertEquals("frames=2 avg_us=15000 max_us=20000 late=1 underruns=0 "
                        + "native_us=5000 audio_us=3000 publish_us=1500 rewind_us=2500 "
                        + "other_us=3000 rewind_max_us=5000",
                stats.summarizeAndReset(0));
    }

    @Test
    public void rejectsInvalidSamplesWithoutAffectingTheWindow() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertFalse(stats.record(-1, 0, 0, 0, 0));
        assertFalse(stats.record(10, 11, 0, 0, 0));
        assertFalse(stats.record(10, 4, 4, 4, 0));
        assertFalse(stats.hasFrames());
    }

    @Test
    public void resetsAllPhasesAfterSummary() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertTrue(stats.record(20_000_000L, 10_000_000L, 0, 0, 5_000_000L));
        stats.summarizeAndReset(0);
        assertFalse(stats.hasFrames());
        record(stats, 1_000_000L);
        assertEquals("frames=1 avg_us=1000 max_us=1000 late=0 underruns=7 "
                        + "native_us=0 audio_us=0 publish_us=0 rewind_us=0 "
                        + "other_us=1000 rewind_max_us=0",
                stats.summarizeAndReset(7));
    }

    @Test
    public void reportsUnderrunsAsPerWindowDeltaOfCumulativeCount() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        record(stats, 10_000_000L);
        assertTrue(stats.summarizeAndReset(5).contains("underruns=5"));
        record(stats, 10_000_000L);
        assertTrue(stats.summarizeAndReset(7).contains("underruns=2"));
        record(stats, 10_000_000L);
        assertTrue(stats.summarizeAndReset(7).contains("underruns=0"));
    }

    @Test(expected = IllegalStateException.class)
    public void summarizeAndResetRequiresFrames() {
        new FrameStats(BUDGET_NANOS).summarizeAndReset(0);
    }
}
```

- [ ] **Step 3: Run the focused test and verify compilation fails**

```sh
tools/android_project/gradlew -p mgba-android \
  :app:testDebugUnitTest --tests '*FrameStatsTest'
```

Expected: Java compilation fails because the five-duration `record` method does not exist.

- [ ] **Step 4: Implement the phase accumulator**

Replace `FrameStats.java` with:

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
    private long nativeNanos;
    private long audioNanos;
    private long publishNanos;
    private long rewindNanos;
    private long rewindMaxNanos;
    private int lastCumulativeUnderruns;

    FrameStats(long budgetNanos) {
        this.budgetNanos = budgetNanos;
    }

    boolean record(long frameNanos, long nativeFrameNanos, long audioFrameNanos,
            long publishFrameNanos, long rewindFrameNanos) {
        if (frameNanos < 0 || nativeFrameNanos < 0 || audioFrameNanos < 0
                || publishFrameNanos < 0 || rewindFrameNanos < 0) {
            return false;
        }
        long attributed = nativeFrameNanos;
        if (audioFrameNanos > frameNanos - attributed) return false;
        attributed += audioFrameNanos;
        if (publishFrameNanos > frameNanos - attributed) return false;
        attributed += publishFrameNanos;
        if (rewindFrameNanos > frameNanos - attributed) return false;

        frames++;
        totalNanos += frameNanos;
        nativeNanos += nativeFrameNanos;
        audioNanos += audioFrameNanos;
        publishNanos += publishFrameNanos;
        rewindNanos += rewindFrameNanos;
        maxNanos = Math.max(maxNanos, frameNanos);
        rewindMaxNanos = Math.max(rewindMaxNanos, rewindFrameNanos);
        if (frameNanos > budgetNanos) lateFrames++;
        return true;
    }

    boolean hasFrames() {
        return frames > 0;
    }

    String summarizeAndReset(int cumulativeUnderruns) {
        if (frames == 0) {
            throw new IllegalStateException("summarizeAndReset() requires hasFrames()");
        }
        int windowUnderruns = cumulativeUnderruns - lastCumulativeUnderruns;
        lastCumulativeUnderruns = cumulativeUnderruns;
        long otherNanos = totalNanos - nativeNanos - audioNanos - publishNanos - rewindNanos;
        String line = String.format(Locale.US,
                "frames=%d avg_us=%d max_us=%d late=%d underruns=%d "
                        + "native_us=%d audio_us=%d publish_us=%d rewind_us=%d "
                        + "other_us=%d rewind_max_us=%d",
                frames, totalNanos / frames / 1_000, maxNanos / 1_000,
                lateFrames, windowUnderruns, nativeNanos / frames / 1_000,
                audioNanos / frames / 1_000, publishNanos / frames / 1_000,
                rewindNanos / frames / 1_000, otherNanos / frames / 1_000,
                rewindMaxNanos / 1_000);
        frames = 0;
        lateFrames = 0;
        totalNanos = 0;
        maxNanos = 0;
        nativeNanos = 0;
        audioNanos = 0;
        publishNanos = 0;
        rewindNanos = 0;
        rewindMaxNanos = 0;
        return line;
    }
}
```

- [ ] **Step 5: Run the focused test**

Run the Step 3 command again.

Expected: all six `FrameStatsTest` tests pass.

- [ ] **Step 6: Delivery checkpoint**

With explicit shipping approval only:

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/FrameStats.java \
  mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FrameStatsTest.java
git commit -m "test(app): attribute emulator frame phases"
```

---

### Task 2: Instrument existing frame-loop phases

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- Test: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/FrameStatsTest.java`

**Interfaces:**
- Consumes: Task 1's five-duration `FrameStats.record` method.
- Produces: populated `native_us`, `audio_us`, `publish_us`, `rewind_us`, and `other_us` log fields.

- [ ] **Step 1: Replace the measured portion of the frame loop**

Replace the block from `long frameStart` through `stats.record(...)` with:

```java
                long frameStart = SystemClock.elapsedRealtimeNanos();
                int audioFrames = session.runFrame(view.keys(), pixels, audio);
                long nativeEnd = SystemClock.elapsedRealtimeNanos();
                long nativeNanos = nativeEnd - frameStart;
                if (audioFrames < 0) {
                    throw new IllegalStateException("mGBA failed to run a frame");
                }

                // Audio is intentionally skipped during fast-forward, which starves the
                // AudioTrack; underruns during FF are a measurement artifact, not a glitch.
                long audioNanos = 0;
                if (!ff && audioTrack != null && audioFrames > 0) {
                    long audioStart = SystemClock.elapsedRealtimeNanos();
                    audioTrack.write(audio, 0, audioFrames * 2, AudioTrack.WRITE_BLOCKING);
                    audioNanos = SystemClock.elapsedRealtimeNanos() - audioStart;
                }

                long publishNanos = 0;
                if (shouldRenderFrame(frameIndex, frameskip)) {
                    long publishStart = SystemClock.elapsedRealtimeNanos();
                    view.publishFrame(pixels);
                    publishNanos = SystemClock.elapsedRealtimeNanos() - publishStart;
                }
                frameIndex++;

                long rewindNanos = 0;
                if (frameIndex % REWIND_INTERVAL_FRAMES == 0) {
                    long rewindStart = SystemClock.elapsedRealtimeNanos();
                    try {
                        rewind.push(session.saveState());
                        rewindSnapshots = rewind.size();
                    } catch (RuntimeException ignored) {
                        rewind.clear();
                        rewindSnapshots = 0;
                    }
                    rewindNanos = SystemClock.elapsedRealtimeNanos() - rewindStart;
                }

                long now = SystemClock.elapsedRealtimeNanos();
                stats.record(now - frameStart, nativeNanos, audioNanos,
                        publishNanos, rewindNanos);
```

Keep the existing `MgbaPerf` logging and pacing code immediately after this block unchanged. Preserve the existing fast-forward audio comment above the audio branch.

- [ ] **Step 2: Compile and run all app unit tests**

```sh
tools/android_project/gradlew -p mgba-android :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`; all app tests pass.

- [ ] **Step 3: Verify allocation-free instrumentation structure**

```sh
python3 - <<'PY'
from pathlib import Path
s = Path('mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java').read_text()
block = s[s.index('long frameStart ='):s.index('if (now >= nextPerfLog')]
normal_path = block.replace(
    'throw new IllegalStateException("mGBA failed to run a frame");', '')
assert 'new ' not in normal_path
assert block.count('Log.') == 0
assert 'stats.record(now - frameStart, nativeNanos, audioNanos,' in block
print('frame instrumentation has no explicit allocation or logging')
PY
```

Expected: the confirmation line prints and the command exits zero.

- [ ] **Step 4: Delivery checkpoint**

With explicit shipping approval only:

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java
git commit -m "feat(app): measure emulator frame phases"
```

---

### Task 3: Add benchmark-only profiling and connected collection

**Files:**
- Create: `mgba-android/app/src/benchmark/AndroidManifest.xml`
- Modify: `mgba-android/tools/measure-session.sh`

**Interfaces:**
- Produces commands: `profile-start`, `profile-collect LABEL`, `profile-summary RUN1 RUN2 RUN3`.
- Produces each run directory with `frames.log`, `gfxinfo.txt`, `meminfo.txt`, and `device.env`.

- [ ] **Step 1: Verify profile summary mode is absent**

```sh
if mgba-android/tools/measure-session.sh profile-summary a b c 2>/dev/null; then
  echo 'unexpected existing profile-summary mode' >&2
  exit 1
fi
```

Expected: command exits nonzero with the current usage message.

- [ ] **Step 2: Add the benchmark manifest overlay**

Create `mgba-android/app/src/benchmark/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <profileable android:shell="true" />
    </application>
</manifest>
```

- [ ] **Step 3: Correct the package and add profile helpers**

Change `PKG` to:

```sh
PKG=com.trebuchetdynamics.garnacha
```

Insert before the existing `case "$MODE" in`:

```sh
PROFILE_STATE="${OUT_DIR:-build/perf}/.profile-state"

require_package() {
    adb shell pm path "$PKG" 2>/dev/null | grep -q '^package:' || {
        echo "REFUSING: $PKG is not installed." >&2
        return 1
    }
}

require_running() {
    adb shell pidof "$PKG" >/dev/null 2>&1 || {
        echo "REFUSING: $PKG is not running. Start representative gameplay first." >&2
        return 1
    }
}

profile_start() {
    require_package && require_running || return 1
    local base="${OUT_DIR:-build/perf}"
    mkdir -p "$base"
    adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
    adb logcat -c
    {
        echo "start_epoch=$(date +%s)"
        echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
        echo "build=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
        echo "brightness=$(adb shell settings get system screen_brightness | tr -d '\r')"
        echo "peak_refresh_rate=$(adb shell settings get system peak_refresh_rate | tr -d '\r')"
        echo "battery_temp=$(battery_temp)"
    } > "$PROFILE_STATE"
    echo "Profile counters reset after warm-up. Play for at least 10 minutes."
}

profile_collect() {
    local label="$1"
    [ -n "$label" ] || { echo "profile-collect requires a label" >&2; return 1; }
    [ -f "$PROFILE_STATE" ] || { echo "Run profile-start first." >&2; return 1; }
    require_package && require_running || return 1
    # shellcheck disable=SC1090
    . "$PROFILE_STATE"
    local elapsed=$(( $(date +%s) - start_epoch ))
    [ "$elapsed" -ge 600 ] || {
        echo "REFUSING: profile duration is ${elapsed}s; 600s required." >&2
        return 1
    }
    local base="${OUT_DIR:-build/perf}"
    local safe
    safe="$(printf '%s' "$label" | tr -c 'A-Za-z0-9._-' '-')"
    local out="$base/profile-$safe"
    [ ! -e "$out" ] || { echo "REFUSING: $out already exists." >&2; return 1; }
    mkdir -p "$out"
    adb logcat -d -s MgbaPerf:I '*:S' > "$out/frames.log"
    local windows
    windows="$(grep -c 'MgbaPerf' "$out/frames.log")"
    [ "$windows" -ge 50 ] || {
        echo "REFUSING: only $windows complete metric windows captured." >&2
        rm -rf "$out"
        return 1
    }
    adb shell dumpsys gfxinfo "$PKG" > "$out/gfxinfo.txt"
    adb shell dumpsys meminfo "$PKG" > "$out/meminfo.txt"
    {
        echo "model=$model"
        echo "build=$build"
        echo "brightness=$brightness"
        echo "peak_refresh_rate=$peak_refresh_rate"
        echo "start_battery_temp=$battery_temp"
        echo "end_battery_temp=$(battery_temp)"
        echo "duration_seconds=$elapsed"
    } > "$out/device.env"
    echo "Wrote $out"
}

profile_summary() {
    python3 - "$@" <<'PY'
import re
import statistics
import sys
from pathlib import Path

KEYS = ('avg_us', 'native_us', 'audio_us', 'publish_us',
        'rewind_us', 'other_us', 'rewind_max_us')
PHASES = ('native_us', 'audio_us', 'publish_us', 'rewind_us', 'other_us')
runs = []
identity = None
for name in sys.argv[1:]:
    root = Path(name)
    env = dict(line.split('=', 1) for line in (root / 'device.env').read_text().splitlines())
    current = (env['model'], env['build'])
    if identity is None:
        identity = current
    elif current != identity:
        raise SystemExit('run device/build mismatch')
    rows = []
    for line in (root / 'frames.log').read_text().splitlines():
        if 'MgbaPerf' not in line:
            continue
        fields = {}
        for token in line.split():
            if '=' in token:
                key, value = token.split('=', 1)
                value = value.rstrip(',')
                if value.lstrip('-').isdigit():
                    fields[key] = int(value)
        required = {'avg_us', 'max_us', 'late', 'underruns', *KEYS[1:]}
        missing = required - fields.keys()
        if missing:
            raise SystemExit(f'{root}: malformed metric line, missing {sorted(missing)}')
        if any(fields[key] < 0 for key in required):
            raise SystemExit(f'{root}: negative metric value')
        rows.append(fields)
    if not rows:
        raise SystemExit(f'{root}: no MgbaPerf windows')
    jank = re.search(r'Janky frames:\s+\d+\s+\(([0-9.]+)%\)',
                     (root / 'gfxinfo.txt').read_text())
    if not jank:
        raise SystemExit(f'{root}: missing gfxinfo jank summary')
    runs.append({
        **{key: int(statistics.median(row[key] for row in rows)) for key in KEYS},
        'max_us': max(row['max_us'] for row in rows),
        'late': sum(row['late'] for row in rows),
        'underruns': sum(row['underruns'] for row in rows),
        'janky_pct': float(jank.group(1)),
    })

median = {key: int(statistics.median(run[key] for run in runs)) for key in KEYS}
dominant = max(PHASES, key=lambda key: median[key])
print(f'runs={len(runs)}')
print(f'device={identity[0]}')
print(f'build={identity[1]}')
for key in KEYS:
    print(f'{key}={median[key]}')
print(f'max_us={max(run["max_us"] for run in runs)}')
print(f'late={sum(run["late"] for run in runs)}')
print(f'underruns={sum(run["underruns"] for run in runs)}')
print(f'janky_pct={statistics.median(run["janky_pct"] for run in runs):.2f}')
print(f'dominant={dominant.removesuffix("_us")}')
PY
}
```

- [ ] **Step 4: Add profile cases without changing sustained-session cases**

Replace the existing `MODE=...` line with:

```sh
MODE="${1:?usage: measure-session.sh start | collect [title] | profile-start | profile-collect LABEL | profile-summary RUN1 RUN2 RUN3}"
```

Insert these cases before existing `start)`:

```sh
profile-start)
    profile_start
    ;;
profile-collect)
    profile_collect "${2:-}"
    ;;
profile-summary)
    [ "$#" -eq 4 ] || {
        echo "usage: $0 profile-summary RUN1 RUN2 RUN3" >&2
        exit 1
    }
    profile_summary "$2" "$3" "$4"
    ;;
```

Replace the fallback usage with:

```sh
    echo "usage: $0 start | collect [title] | profile-start | profile-collect LABEL | profile-summary RUN1 RUN2 RUN3" >&2
```

- [ ] **Step 5: Run syntax and deterministic fixture checks**

```sh
bash -n mgba-android/tools/measure-session.sh
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
for n in 1 2 3; do
  mkdir -p "$TMP/run$n"
  cat > "$TMP/run$n/device.env" <<'EOF'
model=SM-S928B
build=test-build
EOF
  avg=$((800 + n * 100))
  native=$((320 + n * 40))
  cat > "$TMP/run$n/frames.log" <<EOF
I MgbaPerf: frames=600 avg_us=$avg max_us=2200 late=0 underruns=0 native_us=$native audio_us=200 publish_us=100 rewind_us=50 other_us=250 rewind_max_us=1000
EOF
  cat > "$TMP/run$n/gfxinfo.txt" <<'EOF'
Janky frames: 2 (2.00%)
EOF
done
mgba-android/tools/measure-session.sh profile-summary \
  "$TMP/run1" "$TMP/run2" "$TMP/run3" > "$TMP/actual"
grep -qx 'runs=3' "$TMP/actual"
grep -qx 'avg_us=1000' "$TMP/actual"
grep -qx 'native_us=400' "$TMP/actual"
grep -qx 'dominant=native' "$TMP/actual"
grep -qx 'janky_pct=2.00' "$TMP/actual"
```

Expected: all commands exit zero.

- [ ] **Step 6: Verify benchmark-only profileability**

```sh
tools/android_project/gradlew -p mgba-android \
  :app:processBenchmarkMainManifest :app:processReleaseMainManifest
python3 - <<'PY'
from pathlib import Path
benchmark = list(Path('mgba-android/app/build/intermediates').glob(
    '**/benchmark/**/AndroidManifest.xml'))
release = list(Path('mgba-android/app/build/intermediates').glob(
    '**/release/**/AndroidManifest.xml'))
assert any('android:profileable="true"' in p.read_text() or
           '<profileable android:shell="true"' in p.read_text() for p in benchmark)
assert not any('<profileable' in p.read_text() for p in release)
print('profileable is benchmark-only')
PY
```

Expected: confirmation prints. If AGP serializes the namespace differently, inspect the matched benchmark manifest and assert the profileable node has shell `true` by XML namespace rather than weakening the check.

- [ ] **Step 7: Delivery checkpoint**

With explicit shipping approval only:

```sh
git add mgba-android/app/src/benchmark/AndroidManifest.xml \
  mgba-android/tools/measure-session.sh
git commit -m "feat(tools): collect device frame phase profiles"
```

---

### Task 4: Document and validate the measurement harness

**Files:**
- Modify: `mgba-android/README.md`

- [ ] **Step 1: Add the connected profiling workflow**

Append under `## Build and test` after sanitizer instructions:

````markdown
### Connected performance baseline

Build and install the optimized, shell-profileable benchmark variant, then
start a user-owned GBA game with normal speed, audio on, frameskip 0, rewind
on, and fixed brightness. After one minute of warm-up:

```sh
export ANDROID_SERIAL=YOUR_DEVICE_SERIAL
export OUT_DIR=build/perf
mgba-android/tools/measure-session.sh profile-start
# Play the same representative segment for at least 10 minutes.
mgba-android/tools/measure-session.sh profile-collect baseline-1
```

Repeat as `baseline-2` and `baseline-3`, cooling the device until starting
battery temperatures are within 2°C, then summarize:

```sh
mgba-android/tools/measure-session.sh profile-summary \
  build/perf/profile-baseline-1 \
  build/perf/profile-baseline-2 \
  build/perf/profile-baseline-3 | tee build/perf/baseline-summary.txt
```

The connected profile is a frame-time diagnostic, not a battery-life result.
The script collects counters and device metadata only; it never accesses ROM
or app-private data.
````

- [ ] **Step 2: Run all automated gates**

```sh
bash -n mgba-android/tools/measure-session.sh
tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:testDebugUnitTest :app:assembleBenchmark \
  :core:assembleBenchmark :core:assembleDebugAndroidTest
ANDROID_SERIAL=YOUR_DEVICE_SERIAL tools/android_project/gradlew -p mgba-android \
  :core:connectedDebugAndroidTest
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DGARNACHA_SANITIZERS=ON
cmake --build build/mgba-smoke
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
ctest --test-dir build/mgba-smoke --output-on-failure
git diff --check
```

Expected: Java tests, lint, builds, 8 Android instrumentation tests, and 2 host CTests pass; script syntax and diff checks exit zero.

- [ ] **Step 3: Verify excluded scopes**

```sh
find vendor/mgba mgba-android/core/src/main/cpp \
  mgba-android/core/src/main/java src/main.c -type f -print0 \
  | sort -z | xargs -0 sha256sum | sha256sum \
  > build/perf/measurement-excluded-current.sha256
cmp build/perf/measurement-excluded.sha256 \
  build/perf/measurement-excluded-current.sha256
```

Expected: `cmp` exits zero, proving this slice did not alter excluded files despite their pre-existing dirty state.

- [ ] **Step 4: Delivery checkpoint**

With explicit shipping approval only:

```sh
git add mgba-android/README.md
git commit -m "docs: describe connected performance baselines"
```

---

### Task 5: Collect the three-run baseline and confirm the hotspot

**Files:**
- Generate ignored artifacts under: `build/perf/`

- [ ] **Step 1: Build and install the benchmark APK**

```sh
tools/android_project/gradlew -p mgba-android :app:assembleBenchmark
adb -s YOUR_DEVICE_SERIAL install -r \
  mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
```

Expected: Gradle succeeds and adb prints `Success`.

- [ ] **Step 2: User checkpoint for representative gameplay**

The user imports or selects their own representative GBA ROM, loads the same save state, sets normal speed/audio on/frameskip 0/rewind on/fixed brightness, and plays for one warm-up minute. The agent does not access or automate ROM data.

- [ ] **Step 3: Collect three ten-minute runs**

Repeat this sequence with `N=1`, then `N=2`, then `N=3`, warming up before each run:

```sh
N=1
export ANDROID_SERIAL=YOUR_DEVICE_SERIAL
export OUT_DIR=build/perf
mgba-android/tools/measure-session.sh profile-start
# User plays the same segment for at least 600 seconds.
mgba-android/tools/measure-session.sh profile-collect "baseline-$N"
```

Set `N` to the current run number. Before the next run, compare `start_battery_temp` in each `device.env`; all starts must be within 20 tenths of a degree Celsius.

Expected: `build/perf/profile-baseline-1`, `-2`, and `-3` each contain at least 50 complete windows plus gfxinfo, meminfo, and device metadata.

- [ ] **Step 4: Produce the baseline summary**

```sh
mgba-android/tools/measure-session.sh profile-summary \
  build/perf/profile-baseline-1 \
  build/perf/profile-baseline-2 \
  build/perf/profile-baseline-3 | tee build/perf/baseline-summary.txt
```

Expected: `runs=3`, one `dominant=` phase, non-negative metrics, identical device/build identity, and no malformed-window error.

- [ ] **Step 5: Capture a 30-second simpleperf confirmation**

While the same benchmark gameplay is running:

```sh
mkdir -p build/perf
mapfile -t LIB_DIRS < <(find \
  mgba-android/core/build/intermediates/cxx/RelWithDebInfo \
  -type d -path '*/obj/arm64-v8a' | sort)
[ "${#LIB_DIRS[@]}" -eq 1 ] || {
  echo "Expected one optimized arm64 native directory, found ${#LIB_DIRS[@]}" >&2
  exit 1
}
python3 /usr/lib/android-sdk/ndk/22.1.7171670/simpleperf/app_profiler.py \
  -p com.trebuchetdynamics.garnacha \
  -r '-e task-clock:u -f 1000 -g --duration 30' \
  -lib "${LIB_DIRS[0]}" \
  --disable_adb_root \
  -o build/perf/perf.data
python3 /usr/lib/android-sdk/ndk/22.1.7171670/simpleperf/report.py \
  -i build/perf/perf.data --symfs "$PWD/binary_cache" --sort symbol \
  > build/perf/simpleperf-report.txt
export MGBA_SO="${LIB_DIRS[0]}/libmgba-android.so"
export ADDR2LINE=/usr/lib/android-sdk/ndk/22.1.7171670/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-addr2line
python3 - <<'PY'
import os, re, subprocess
from pathlib import Path
out = []
for line in Path('build/perf/simpleperf-report.txt').read_text().splitlines():
    match = re.match(r'([0-9.]+%)\s+libmgba-android\.so\[\+([0-9a-f]+)\]$', line.strip())
    if not match:
        continue
    symbol, source = subprocess.check_output([
        os.environ['ADDR2LINE'], '-f', '-C', '-e', os.environ['MGBA_SO'],
        '0x' + match.group(2)], text=True).splitlines()[:2]
    out.append(f'{match.group(1)} symbol={symbol} source={source}')
    if len(out) == 30:
        break
Path('build/perf/simpleperf-mgba-symbols.txt').write_text('\n'.join(out) + '\n')
PY
```

Expected: exactly one optimized arm64 directory is selected; `perf.data`, the report, and a non-empty symbolication file exist.

- [ ] **Step 6: Write the slice-1 evidence note**

Generate `build/perf/hotspot-recommendation.txt` directly from measured output:

```sh
SUMMARY=build/perf/baseline-summary.txt
REPORT=build/perf/simpleperf-report.txt
SYMBOLS=build/perf/simpleperf-mgba-symbols.txt
dominant="$(awk -F= '$1 == "dominant" {print $2}' "$SUMMARY")"
total="$(awk -F= '$1 == "avg_us" {print $2}' "$SUMMARY")"
phase="$(awk -F= -v key="${dominant}_us" '$1 == key {print $2}' "$SUMMARY")"
call_path="$(grep -m1 -E 'mgba_session_run_frame|mCore' "$SYMBOLS" | tr -s ' ')"
[ -n "$dominant" ] && [ -n "$total" ] && [ -n "$phase" ] && [ -n "$call_path" ]
case "$dominant" in
  native) next_action='design native/JNI frame-transfer optimization' ;;
  audio) next_action='design audio pacing optimization without latency regression' ;;
  publish) next_action='design bitmap publication and lock optimization' ;;
  rewind) next_action='design rewind snapshot spike optimization' ;;
  other) next_action='no production change; refine unattributed measurement' ;;
  *) echo "Unknown dominant phase: $dominant" >&2; exit 1 ;;
esac
{
  echo 'baseline_summary=build/perf/baseline-summary.txt'
  echo 'profile_report=build/perf/simpleperf-report.txt'
  echo 'symbolication=build/perf/simpleperf-mgba-symbols.txt'
  echo "dominant_phase=$dominant"
  echo "median_total_us=$total"
  echo "dominant_phase_us=$phase"
  echo "dominant_call_path=$call_path"
  echo "next_action=$next_action"
} > build/perf/hotspot-recommendation.txt
```

Expected: command exits zero and every evidence line contains measured data.

- [ ] **Step 7: Stop at the evidence gate**

Do not edit production performance code. Present `baseline-summary.txt` and `hotspot-recommendation.txt` for review, then start a separate brainstorming/spec cycle for slice 2 only if evidence supports a bounded optimization.

---

## Final Review Checklist

- [ ] Existing `MgbaPerf` fields remain compatible and phase fields are deterministic.
- [ ] Instrumentation creates no per-frame objects and logs only once per window.
- [ ] Invalid metric samples cannot stop gameplay.
- [ ] Profile commands fail safely for absent package, stopped gameplay, short runs, malformed logs, and device mismatch.
- [ ] Benchmark is shell-profileable; release is not.
- [ ] Java tests, lint, builds, Android instrumentation, and host sanitizers pass.
- [ ] Three valid ten-minute SM-S928B runs produce a median baseline.
- [ ] `gfxinfo` and simpleperf evidence confirm the dominant phase.
- [ ] No ROM data or app-private data is collected.
- [ ] No optimization or excluded core/vendor/legacy file is changed.
