#!/usr/bin/env bash
# Captures oracle renders from a running Android device/emulator.
# Expects the sample app (dev.maradona.sample.android) to be installed.
# fit/100/min are single shots; max is a 3x3 pan-tile grid.
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

shot() { # chart zoom panX panY outfile
  adb -s "$SERIAL" shell am force-stop dev.maradona.sample.android
  adb -s "$SERIAL" shell am start -W -n dev.maradona.sample.android/.MainActivity \
    --es oracleChart "$1" --es oracleZoom "$2" \
    --ef oraclePanX "$3" --ef oraclePanY "$4" >/dev/null
  sleep 2.5
  adb -s "$SERIAL" exec-out screencap -p > "$5"
  echo "captured: android/$(basename "$5" .png)"
}

for chart in "${CHARTS[@]}"; do
  for zoom in fit 100 min; do
    shot "$chart" "$zoom" -1 -1 "$OUT/$chart-$zoom.png"
  done
  for fx in 0 0.5 1; do
    for fy in 0 0.5 1; do
      shot "$chart" max "$fx" "$fy" "$OUT/$chart-max-$(python3 -c "print(int($fx*2))")$(python3 -c "print(int($fy*2))").png"
    done
  done
done
