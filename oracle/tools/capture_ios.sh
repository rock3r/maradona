#!/usr/bin/env bash
# Captures oracle renders from a booted iOS simulator.
# Expects the sample app (dev.maradona.sample) to be installed on it.
# fit/100/min are single shots; max is a 3x3 pan-tile grid.
# Usage: capture_ios.sh <simulator-udid-or-booted> [chart ...]
set -euo pipefail
SIM=$1; shift || true
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/oracle/out/ios
mkdir -p "$OUT"
CHARTS=("$@")
if [ ${#CHARTS[@]} -eq 0 ]; then
  CHARTS=(); for f in "$ROOT"/oracle/charts/*.mmd; do CHARTS+=("$(basename "$f" .mmd)"); done
fi

shot() { # chart zoom panX panY outfile
  xcrun simctl terminate "$SIM" dev.maradona.sample 2>/dev/null || true
  xcrun simctl launch "$SIM" dev.maradona.sample \
    -oracleChart "$1" -oracleZoom "$2" -oraclePanX "$3" -oraclePanY "$4" >/dev/null
  sleep 3
  xcrun simctl io "$SIM" screenshot "$5" >/dev/null
  echo "captured: ios/$(basename "$5" .png)"
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
