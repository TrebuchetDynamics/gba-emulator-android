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

PKG=com.trebuchetdynamics.mgba
MODE="${1:?usage: measure-session.sh start | collect [title]}"
STATE="${OUT_DIR:-.}/.session-state"

battery_level() { adb shell dumpsys battery | sed -n 's/^  level: //p'; }
battery_temp()  { adb shell dumpsys battery | sed -n 's/^  temperature: //p'; }
thermal()       { adb shell dumpsys thermalservice | sed -n '/Current temperatures/,/^$/p'; }

case "$MODE" in
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
    echo "usage: measure-session.sh start | collect [title]" >&2
    exit 1
    ;;
esac
