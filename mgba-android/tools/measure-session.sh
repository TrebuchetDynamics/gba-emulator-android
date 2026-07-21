#!/usr/bin/env bash
# Sustained-gameplay measurement for the M1 physical-device gate.
#
# Battery and thermal figures are only meaningful on an UNPLUGGED device, so the
# session runs with no adb connection and the data is collected afterwards:
# MgbaPerf windows survive in logcat's ring buffer, gfxinfo accumulates inside
# the app process, and battery/thermal read accurately the moment USB is back.
#
#   mgba-android/tools/measure-session.sh start
#     ... unplug USB, play for 30-60 min, keep the screen on, replug ...
#   mgba-android/tools/measure-session.sh collect "Minish Cap"
#
# If the host and device share a LAN, wireless debugging (adb pair/connect)
# works too and `collect` can then be run without replugging.
set -uo pipefail

PKG=com.trebuchetdynamics.garnacha
MODE="${1:?usage: measure-session.sh start | collect [title] | profile-start | profile-collect LABEL | profile-summary RUN1 RUN2 RUN3}"
STATE="${OUT_DIR:-.}/.session-state"

battery_level() { adb shell dumpsys battery | sed -n 's/^  level: //p'; }
battery_temp()  { adb shell dumpsys battery | sed -n 's/^  temperature: //p'; }
thermal()       { adb shell dumpsys thermalservice | sed -n '/Current temperatures/,/^$/p'; }

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

case "$MODE" in
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
start)
    if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
        echo "REFUSING: $PKG is not running. Load a ROM and start the game first." >&2
        exit 1
    fi

    # Keep the screen alive for the whole session; a sleeping screen pauses the
    # activity, which stops the emulation loop and truncates the measurement.
    adb shell settings put system screen_off_timeout 3600000 >/dev/null

    # A 5 MiB ring buffer holds ~35 min of MgbaPerf windows amid other logspam;
    # raise it so a long session cannot silently lose its earliest windows.
    adb logcat -G 32M >/dev/null 2>&1

    adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
    # Device-recorded, session-scoped power accounting. The battery percentage
    # is too coarse to trust over a short session (it can read a flat 0% drain),
    # and it cannot prove the device was ever actually on battery. batterystats
    # records both, on the device, and survives a logcat wrap.
    adb shell dumpsys batterystats --reset >/dev/null 2>&1
    adb logcat -c

    {
        echo "start_epoch=$(date +%s)"
        echo "start_level=$(battery_level)"
        echo "start_temp=$(battery_temp)"
        echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
    } > "$STATE"

    echo "Counters reset (gfxinfo, batterystats, logcat 32M). Screen timeout 1h."
    echo
    echo "NOW: unplug USB, play for 30-60 min (keep the screen on), then replug"
    echo "and run: mgba-android/tools/measure-session.sh collect \"<title>\""
    ;;

collect)
    TITLE="${2:-unnamed}"
    [ -f "$STATE" ] || { echo "No session state — run 'start' first." >&2; exit 1; }
    # shellcheck disable=SC1090
    . "$STATE"

    END_LEVEL="$(battery_level)"
    END_TEMP="$(battery_temp)"
    MINUTES=$(( ($(date +%s) - start_epoch) / 60 ))
    OUT="${OUT_DIR:-.}/session-$(printf '%s' "$TITLE" | tr -c 'A-Za-z0-9' '-')"

    adb logcat -d -s MgbaPerf > "${OUT}-frames.log"

    if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
        echo "WARNING: $PKG is no longer running — it may have died mid-session." >&2
        echo "gfxinfo/meminfo are unavailable; frame windows below are still valid." >&2
    fi

    # Emulation stops when the activity pauses, so the windows may cover less
    # than the wall-clock duration. Report the emulated span separately rather
    # than dividing power draw by a duration the emulator was not running for.
    FIRST_W="$(sed -n '2p' "${OUT}-frames.log" | cut -c1-18)"
    LAST_W="$(tail -1 "${OUT}-frames.log" | cut -c1-18)"

    {
        echo "=== session: $TITLE ==="
        echo "device: $model"
        echo "wall clock: ${MINUTES} min"
        echo "emulating:  ${FIRST_W} -> ${LAST_W}"
        echo
        echo "--- on battery? (device-recorded; the % below is worthless if this says 0) ---"
        adb shell dumpsys batterystats | grep -E "^  Time on battery:|^  Screen on discharge:|^  Discharge:" | head -3
        echo
        echo "--- battery ---"
        echo "level: ${start_level}% -> ${END_LEVEL}%  (drained $((start_level - END_LEVEL))% over ${MINUTES} min wall clock)"
        echo "temp:  $((start_temp / 10))C -> $((END_TEMP / 10))C"
        echo "NOTE: a flat 0% drain is not credible for a screen-on session — treat"
        echo "      it as a failed measurement, not as evidence of low power use."
        echo
        echo "--- thermal (on reconnect, still hot) ---"
        thermal
        echo "--- gfxinfo ---"
        adb shell dumpsys gfxinfo "$PKG" | sed -n '/Total frames/,/99th/p'
        echo "--- meminfo ---"
        adb shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|Native Heap|Java Heap"
        echo "--- fatal/ANR for $PKG ---"
        adb logcat -d | grep -cE "FATAL|ANR in $PKG"
    } > "${OUT}.log"

    # Frame-window summary. Each window is ~10s; the budget is 16743 us/frame.
    awk '/MgbaPerf/ {
        for (i = 1; i <= NF; i++) {
            split($i, kv, "=")
            if (kv[1] == "frames")    { f += kv[2]; n++; if (kv[2] < min_f || n == 1) min_f = kv[2] }
            if (kv[1] == "avg_us")    { sum_avg += kv[2]; if (kv[2] > worst_avg) worst_avg = kv[2] }
            if (kv[1] == "max_us")    { if (kv[2] > worst_max) worst_max = kv[2] }
            if (kv[1] == "late")      { late += kv[2] }
            if (kv[1] == "underruns") { under += kv[2] }
        }
    }
    END {
        if (n == 0) { print "NO MgbaPerf WINDOWS CAPTURED — the ring buffer may have wrapped"; exit 1 }
        printf "windows: %d (~%d min)   frames: %d   slowest window: %d frames\n", n, n / 6, f, min_f
        printf "mean window avg:  %d us\n", sum_avg / n
        printf "WORST window avg: %d us   (budget 16743)\n", worst_avg
        printf "WORST window max: %d us\n", worst_max
        printf "late frames: %d   underruns: %d\n", late, under
        printf "\nSIGNAL: %s\n", (worst_avg < 16743 ? "every window within budget" : "OVER BUDGET")
    }' "${OUT}-frames.log" | tee -a "${OUT}.log"

    echo
    cat "${OUT}.log"
    echo
    echo "Wrote ${OUT}.log and ${OUT}-frames.log"
    ;;

*)
    echo "usage: $0 start | collect [title] | profile-start | profile-collect LABEL | profile-summary RUN1 RUN2 RUN3" >&2
    exit 1
    ;;
esac
