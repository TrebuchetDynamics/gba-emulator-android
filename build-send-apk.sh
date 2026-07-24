#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-release}"

case "$MODE" in
    release|benchmark) VARIANT=benchmark ;;
    debug) VARIANT=debug ;;
    *) echo "Usage: $0 [release|benchmark|debug]" >&2; exit 2 ;;
esac

APK="$ROOT/mgba-android/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
OUTPUT="$ROOT/garnachaboy-$(LC_ALL=C date +%d-%b-%y).apk"

# The benchmark variant is release-optimized and locally installable; release itself is unsigned.
"$ROOT/mgba-android/gradlew" -p "$ROOT/mgba-android" --rerun-tasks ":app:assemble${VARIANT^}"
cp "$APK" "$OUTPUT"
adb install -r "$OUTPUT"
