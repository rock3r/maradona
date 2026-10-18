#!/usr/bin/env bash
# Captures oracle renders from a running Android device/emulator.
# Expects the sample app (dev.maradona.sample.android) to be installed.
# Usage: capture_android.sh <adb-serial> [chart ...]
set -euo pipefail
SERIAL=$1; shift || true
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/oracle/out/android
mkdir -p "$OUT"
CHARTS=("$@")
if [ ${#CHARTS[@]} -eq 0 ]; then
  CHARTS=(); for f in "$ROOT"/oracle/charts/*.mmd; do CHARTS+=("$(basename "$f" .mmd)"); done
fi
for chart in "${CHARTS[@]}"; do
  for zoom in fit 100 min max; do
    adb -s "$SERIAL" shell am force-stop dev.maradona.sample.android
    adb -s "$SERIAL" shell am start -W -n dev.maradona.sample.android/.MainActivity \
      --es oracleChart "$chart" --es oracleZoom "$zoom" >/dev/null
    sleep 2.5
    adb -s "$SERIAL" exec-out screencap -p > "$OUT/$chart-$zoom.png"
    echo "captured: android/$chart-$zoom"
  done
done
