#!/usr/bin/env bash
# Sustained-gameplay measurement session for the M1 physical-device gate.
#
# Collects frame pacing, audio underruns, jank, memory, battery, and thermal
# data for one gameplay session, then prints a summary.
#
# The device must be UNPLUGGED for the battery and thermal figures to mean
# anything, so drive it over wireless debugging:
#
#   adb pair <ip>:<pair-port>          # code shown on the device
#   adb connect <ip>:<port>
#   export ANDROID_SERIAL=<ip>:<port>
#   # unplug USB, start the game, then:
#   mgba-android/tools/measure-session.sh 30 "Minish Cap"
#
# Usage: measure-session.sh <minutes> [title]
set -uo pipefail

MINUTES="${1:?usage: measure-session.sh <minutes> [title]}"
TITLE="${2:-unnamed}"
PKG=com.trebuchetdynamics.mgba
OUT="${OUT_DIR:-.}/session-$(printf '%s' "$TITLE" | tr -c 'A-Za-z0-9' '-')"

battery_level() { adb shell dumpsys battery | sed -n 's/^  level: //p'; }
battery_temp()  { adb shell dumpsys battery | sed -n 's/^  temperature: //p'; }
charging()      { adb shell dumpsys battery | sed -n 's/^  status: //p'; }
thermal()       { adb shell dumpsys thermalservice | sed -n '/Current temperatures/,/^$/p'; }

if [ "$(charging)" = "2" ]; then
    echo "REFUSING: the device is charging." >&2
    echo "Battery drain and thermal figures are invalid while plugged in." >&2
    echo "Unplug it (use wireless debugging) and re-run." >&2
    exit 1
fi

if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
    echo "REFUSING: $PKG is not running. Start the game first." >&2
    exit 1
fi

echo "Session: $TITLE, ${MINUTES} min, device $(adb shell getprop ro.product.model)"

START_LEVEL="$(battery_level)"
START_TEMP="$(battery_temp)"
{
    echo "=== session start: $(date -Is) ==="
    echo "title: $TITLE"
    echo "battery level: ${START_LEVEL}%   battery temp: $((START_TEMP / 10))C"
    echo "--- thermal (start) ---"
    thermal
} > "${OUT}.log"

adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
adb logcat -c

echo "Play actively. Sampling every 5 min; keep the screen on."
ELAPSED=0
while [ "$ELAPSED" -lt "$MINUTES" ]; do
    sleep 300
    ELAPSED=$((ELAPSED + 5))
    if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
        echo "WARNING: app died at ${ELAPSED} min - recording and stopping" | tee -a "${OUT}.log"
        break
    fi
    printf '  %s min: battery %s%%  temp %sC  last: %s\n' \
        "$ELAPSED" "$(battery_level)" "$(($(battery_temp) / 10))" \
        "$(adb logcat -d -s MgbaPerf | tail -1 | sed 's/.*MgbaPerf: //')" | tee -a "${OUT}.log"
done

END_LEVEL="$(battery_level)"
END_TEMP="$(battery_temp)"
adb logcat -d -s MgbaPerf > "${OUT}-frames.log"

{
    echo "=== session end: $(date -Is) ==="
    echo "--- gfxinfo ---"
    adb shell dumpsys gfxinfo "$PKG" | sed -n '/Total frames/,/99th/p'
    echo "--- meminfo ---"
    adb shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|Native Heap|Java Heap"
    echo "--- audio ---"
    adb shell dumpsys media.audio_flinger | grep -iE "underrun|latency" | head -10
    echo "--- thermal (end) ---"
    thermal
    echo "--- battery ---"
    echo "level: ${START_LEVEL}% -> ${END_LEVEL}% over ${ELAPSED} min"
    echo "temp:  $((START_TEMP / 10))C -> $((END_TEMP / 10))C"
    echo "--- fatal/ANR ---"
    adb logcat -d | grep -cE "FATAL|ANR in $PKG" || echo 0
} >> "${OUT}.log"

# Frame-window summary: worst average, worst max, totals.
awk '/MgbaPerf/ {
    for (i = 1; i <= NF; i++) {
        split($i, kv, "=")
        if (kv[1] == "frames")    { f += kv[2]; n++ }
        if (kv[1] == "avg_us")    { if (kv[2] > worst_avg) worst_avg = kv[2] }
        if (kv[1] == "max_us")    { if (kv[2] > worst_max) worst_max = kv[2] }
        if (kv[1] == "late")      { late += kv[2] }
        if (kv[1] == "underruns") { under += kv[2] }
    }
}
END {
    if (n == 0) { print "NO MgbaPerf WINDOWS CAPTURED"; exit }
    printf "windows: %d   frames: %d\n", n, f
    printf "worst window avg: %d us   (budget 16743)\n", worst_avg
    printf "worst window max: %d us\n", worst_max
    printf "late frames: %d   underruns: %d\n", late, under
    printf "VERDICT SIGNAL: %s\n", (worst_avg < 16743 ? "within budget" : "OVER BUDGET")
}' "${OUT}-frames.log" | tee -a "${OUT}.log"

echo
echo "Wrote ${OUT}.log and ${OUT}-frames.log"
