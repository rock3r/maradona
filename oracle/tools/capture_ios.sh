#!/usr/bin/env bash
# Captures oracle renders from a booted iOS simulator.
# Expects the sample app (dev.maradona.sample) to be installed on it.
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
for chart in "${CHARTS[@]}"; do
  for zoom in fit 100 min max; do
    xcrun simctl terminate "$SIM" dev.maradona.sample 2>/dev/null || true
    xcrun simctl launch "$SIM" dev.maradona.sample -oracleChart "$chart" -oracleZoom "$zoom" >/dev/null
    sleep 3
    xcrun simctl io "$SIM" screenshot "$OUT/$chart-$zoom.png" >/dev/null
    echo "captured: ios/$chart-$zoom"
  done
done
