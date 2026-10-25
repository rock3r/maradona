#!/usr/bin/env bash
# Captures oracle renders from a booted iOS simulator.
#
# Simulators only: the oracle compares captures 1:1 against the golden, so the
# screen has to be deterministic. Use a device without a notch, island or home
# indicator (iPhone SE) - those are painted into screenshots and would count as
# diagram ink. The app hides the status bar itself in oracle mode.
#
# Usage: capture_ios.sh <simulator-udid> [chart ...]
set -euo pipefail
SIM=${1:?usage: capture_ios.sh <simulator-udid> [chart ...]}
shift || true
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/oracle/out/ios
PY=$ROOT/oracle/tools/.venv/bin/python
GEOM=$ROOT/oracle/tools/oracle_geom.py
APP=dev.maradona.sample
mkdir -p "$OUT"
CAPTURES=$OUT/captures.txt

case "$SIM" in
  *[!0-9A-F-]*|"") echo "expected a simulator UDID, got '$SIM'" >&2; exit 1 ;;
esac
if ! xcrun simctl </dev/null list devices | grep -q "$SIM"; then
  echo "no such simulator: $SIM" >&2
  exit 1
fi

CHARTS=("$@")
if [ ${#CHARTS[@]} -eq 0 ]; then
  rm -f "$OUT"/*.png "$OUT"/captures.txt "$OUT"/manifest.json
  : > "$CAPTURES"
  for f in "$ROOT"/oracle/charts/*.mmd; do CHARTS+=("$(basename "$f" .mmd)"); done
else
  touch "$CAPTURES"
  for c in "${CHARTS[@]}"; do
    rm -f "$OUT/$c"-*.png
    grep -v "^$c " "$CAPTURES" > "$CAPTURES.tmp" || true
    mv "$CAPTURES.tmp" "$CAPTURES"
  done
fi

log() { printf '\033[2m%s\033[0m\n' "$*"; }

log "preparing $SIM"
xcrun simctl </dev/null bootstatus "$SIM" -b >/dev/null 2>&1 || xcrun simctl boot "$SIM" >/dev/null
# Deterministic text metrics; no animations mid-capture.
xcrun simctl </dev/null ui "$SIM" content_size medium >/dev/null 2>&1 || true

SEQ=0

launch() { # chart zoom fx fy density
  xcrun simctl </dev/null terminate "$SIM" "$APP" >/dev/null 2>&1 || true
  local extra=()
  [ -n "${5:-}" ] && extra=(-oracleDensity "$5")
  SEQ=$((SEQ + 1))
  xcrun simctl </dev/null launch "$SIM" "$APP" \
    -oracleChart "$1" -oracleZoom "$2" -oraclePanX "$3" -oraclePanY "$4" -oracleSeq "$SEQ" \
    ${extra[@]+"${extra[@]}"} >/dev/null
}

render_log() { # the app's oracle log lines
  xcrun simctl </dev/null spawn "$SIM" log show --style compact --last 30s \
    --predicate 'eventMessage CONTAINS "MARADONA_ORACLE"' 2>/dev/null | grep MARADONA_ORACLE || true
}

get_field() { # field zoom panx pany [density] - retries while the app settles
  local i v filter
  # seq is unique per launch, so it alone identifies this render (pan values
  # are formatted differently by the app than by the shell).
  filter="zoom=$2.*seq=$SEQ"
  [ -n "${5:-}" ] && filter="$filter.*density=$5"
  for i in $(seq 1 90); do
    v=$(render_log | grep -E "$filter" | grep -o "$1=[0-9.x]*" | tail -1 | cut -d= -f2 || true)
    if [ -n "$v" ]; then echo "$v"; return 0; fi
    sleep 1
  done
  return 1
}

shot() { # chart level fx fy index density
  launch "$1" "$2" "$3" "$4" "$6"
  get_field canvas "$2" "$3" "$4" >/dev/null || true
  sleep 0.5
  if [ -n "${5:-}" ]; then
    xcrun simctl </dev/null io "$SIM" screenshot "$OUT/$1-$2-$5.png" >/dev/null 2>&1
  else
    xcrun simctl </dev/null io "$SIM" screenshot "$OUT/$1-$2.png" >/dev/null 2>&1
  fi
}

# Viewport size, from one throwaway capture.
launch "${CHARTS[0]}" 100 0.5 0.5
sleep 3
xcrun simctl </dev/null io "$SIM" screenshot "$OUT/.probe.png" >/dev/null 2>&1
VIEW=$("$PY" -c "
from PIL import Image; print('%dx%d' % Image.open('$OUT/.probe.png').size)")
VW=${VIEW%x*}; VH=${VIEW#*x}
log "viewport ${VIEW}"

for chart in "${CHARTS[@]}"; do
  log "chart $chart"
  # Calibration: the app's density matches the golden against its layout box,
  # which carries padding the golden does not. Render the whole diagram once
  # at 0.1x, measure the real ink, and correct the density.
  launch "$chart" min 0.5 0.5
  if ! RAW_DENSITY=$(get_field density min 0.5 0.5); then
    echo "ERROR: no density logged for $chart" >&2
    echo "  (check: xcrun simctl </dev/null spawn $SIM log show --predicate 'eventMessage CONTAINS \"MARADONA_ORACLE\"')" >&2
    exit 1
  fi
  DENSITY=""
  for attempt in 1 2 3 4 5; do
    sleep 1.5
    xcrun simctl </dev/null io "$SIM" screenshot "$OUT/.probe.png" >/dev/null 2>&1
    if DENSITY=$("$PY" "$ROOT/oracle/tools/calibrate.py" --capture "$OUT/.probe.png" \
      --scale 0.1 --density "$RAW_DENSITY" --gold "$ROOT/oracle/goldens/$chart.png" 2>/dev/null); then
      break
    fi
    log "  calibration attempt $attempt was not a clean render; retrying"
    DENSITY=""
  done
  if [ -z "$DENSITY" ]; then
    echo "ERROR: calibration failed for $chart" >&2
    exit 1
  fi

  # Canvas = the diagram's own size at the calibrated density.
  # Pan 0.25 is used only here, so the log line is unambiguously this render.
  launch "$chart" 100 0.25 0.25 "$DENSITY"
  sleep 2
  if ! CANVAS=$(get_field canvas 100 0.25 0.25); then
    echo "ERROR: no canvas size logged for $chart" >&2
    exit 1
  fi

  # The 0.1x extrapolation above is inflated by minimum stroke widths and
  # layout rounding (a few percent). Capture the real 100% tiles, measure the
  # ink exactly, and refine the density before the final pass.
  GRID=$("$PY" "$GEOM" tiles --canvas "$CANVAS" --viewport "$VIEW" --level 100)
  COUNT=$(echo "$GRID" | wc -l | tr -d ' ')
  TILE_SPEC=""
  while read -r row col fx fy; do
    idx_file=""
    [ "$COUNT" -gt 1 ] && idx_file="$row-$col"
    shot "$chart" 100 "$fx" "$fy" "$idx_file" "$DENSITY"
    TILE_SPEC="$TILE_SPEC$fx,$fy;"
  done <<< "$GRID"
  if ! REFINED=$("$PY" "$ROOT/oracle/tools/refine.py" --dir "$OUT" --chart "$chart" \
    --canvas "$CANVAS" --viewport "$VIEW" --tiles "${TILE_SPEC%;}" \
    --density "$DENSITY" --gold "$ROOT/oracle/goldens/$chart.png"); then
    echo "ERROR: density refinement failed for $chart (see above)" >&2
    exit 1
  fi
  DENSITY=${REFINED%% *}
  log "  density $DENSITY (ink ${REFINED##* })"

  # The canvas scales with the density, so re-read it for the final framing.
  # Pan 0.25 is used only here, so the log line is unambiguously this render.
  launch "$chart" 100 0.75 0.75 "$DENSITY"
  sleep 2
  if ! CANVAS=$(get_field canvas 100 0.75 0.75); then
    echo "ERROR: no canvas size logged for $chart" >&2
    exit 1
  fi
  log "  canvas $CANVAS"
  for level in 100 min max; do
    COUNT=$("$PY" "$GEOM" tiles --canvas "$CANVAS" --viewport "$VIEW" --level "$level" | wc -l | tr -d ' ')
    while read -r row col fx fy; do
      idx_file=""
      [ "$COUNT" -gt 1 ] && idx_file="$row-$col"
      shot "$chart" "$level" "$fx" "$fy" "$idx_file" "$DENSITY"
      echo "$chart ${CANVAS%x*} ${CANVAS#*x} $VW $VH $level $fx $fy" >> "$CAPTURES"
    done < <("$PY" "$GEOM" tiles --canvas "$CANVAS" --viewport "$VIEW" --level "$level")
    log "  $level: $COUNT tile(s)"
  done
done
rm -f "$OUT/.probe.png"
"$PY" "$GEOM" manifest --captures "$CAPTURES" --out "$OUT/manifest.json"
log "captured $(grep -c . "$CAPTURES") tiles"
