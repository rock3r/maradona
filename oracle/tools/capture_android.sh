#!/usr/bin/env bash
# Captures oracle renders from a booted Android emulator.
#
# Emulators only: the oracle compares captures 1:1 against the golden, so the
# screen has to be deterministic (fixed size, no cutout, no dynamic theming,
# no system bars, standard font scale). Physical devices are refused unless
# you pass --allow-physical, and even then the capture will most likely fail
# comparison for all the reasons above.
#
# The app renders every chart at a density chosen so its content matches the
# golden's pixel size; the visible window is panned over the diagram canvas
# and the tiles are stitched back together by compare.py.
#
# Usage: capture_android.sh <emulator-serial> [--allow-physical] [chart ...]
set -euo pipefail
SERIAL=${1:?usage: capture_android.sh <emulator-serial> [--allow-physical] [chart ...]}
shift || true
ALLOW_PHYSICAL=0
if [ "${1:-}" = "--allow-physical" ]; then ALLOW_PHYSICAL=1; shift; fi
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/oracle/out/android
PY=$ROOT/oracle/tools/.venv/bin/python
GEOM=$ROOT/oracle/tools/oracle_geom.py
PKG=dev.maradona.sample.android
mkdir -p "$OUT"
# Start clean: a render left by an earlier run (or by an older naming scheme) must
# never be measured as if it came from this one.
rm -f "$OUT"/*.png "$OUT"/captures.txt "$OUT"/manifest.json
CAPTURES=$OUT/captures.txt

case "$SERIAL" in
  emulator-*) : ;;
  *)
    if [ "$ALLOW_PHYSICAL" != 1 ]; then
      echo "refusing '$SERIAL': oracle captures must come from an emulator" >&2
      echo "(fixed screen, no cutout, no dynamic theming). Pass --allow-physical to override." >&2
      exit 1
    fi
    ;;
esac

CHARTS=("$@")
if [ ${#CHARTS[@]} -eq 0 ]; then
  for f in "$ROOT"/oracle/charts/*.mmd; do CHARTS+=("$(basename "$f" .mmd)"); done
fi

log() { printf '\033[2m%s\033[0m\n' "$*"; }

log "preparing $SERIAL"
adb -s "$SERIAL" </dev/null shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$SERIAL" </dev/null shell wm dismiss-keyguard >/dev/null 2>&1 || true
# Deterministic text metrics and no animations mid-capture.
adb -s "$SERIAL" </dev/null shell settings put system font_scale 1.0 >/dev/null
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$SERIAL" </dev/null shell settings put global "$s" 0 >/dev/null
done
# A dimmed or sleeping screen would be captured instead of the diagram.
adb -s "$SERIAL" </dev/null shell settings put system screen_off_timeout 1800000 >/dev/null
adb -s "$SERIAL" </dev/null shell settings put global stay_on_while_plugged_in 7 >/dev/null
# A camera cutout would be painted into the screenshot and counted as ink.
adb -s "$SERIAL" </dev/null shell cmd overlay enable com.android.internal.display.cutout.emulation.noCutout >/dev/null 2>&1 || true
# Hiding the system bars (oracle mode does) triggers a one-time "swipe up to
# exit full screen" confirmation that takes window focus and blocks captures.
adb -s "$SERIAL" </dev/null shell settings put secure immersive_mode_confirmations confirmed >/dev/null

# Monotonic capture id: every launch renders fresh (and logs) even when a tile
# is captured twice with identical parameters.
SEQ=0

cold_launch() { # chart zoom fx fy density - force-stop, then wait for focus
  adb -s "$SERIAL" shell am force-stop "$PKG" </dev/null >/dev/null 2>&1 || true
  launch "$@"
}

launch() { # chart zoom fx fy density
  local extra=()
  [ -n "${5:-}" ] && extra=(--ef oracleDensity "$5")
  SEQ=$((SEQ + 1))
  # stdin must be detached: adb would otherwise swallow the tile list the
  # caller is iterating over with `while read`.
  adb -s "$SERIAL" </dev/null shell am start -n "$PKG/.MainActivity" \
    --es oracleChart "$1" --es oracleZoom "$2" \
    --ef oraclePanX "$3" --ef oraclePanY "$4" --ei oracleSeq "$SEQ" \
    ${extra[@]+"${extra[@]}"} </dev/null >/dev/null 2>&1
  local focus="" i
  for i in $(seq 1 120); do
    focus=$(adb -s "$SERIAL" </dev/null shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true)
    case "$focus" in
      *"$PKG"*) return 0 ;;
    esac
    sleep 0.5
  done
  echo "ERROR: $PKG did not reach the foreground" >&2
  echo "  focused window: $focus" >&2
  echo "  (ANR dialog, lockscreen, or a wedged emulator - fix it and re-run)" >&2
  exit 1
}

render_log() { # the app's oracle log lines
  adb -s "$SERIAL" </dev/null logcat -d -s System.out:I 2>/dev/null | grep MARADONA_ORACLE || true
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

shot() { # chart level fx level fx fy index density
  # Clearing the log first makes the wait below unambiguous: the matching line
  # can only come from the render we just asked for.
  adb -s "$SERIAL" logcat -c </dev/null >/dev/null 2>&1 || true
  launch "$1" "$2" "$3" "$4" "$6"
  # Wait for this launch's own log line: proves the new render has been laid
  # out, so the screenshot cannot show the previous one.
  get_field canvas "$2" "$3" "$4" >/dev/null || true
  sleep 1.5
  if [ -n "${5:-}" ]; then
    adb -s "$SERIAL" exec-out screencap -p </dev/null > "$OUT/$1-$2-$5.png"
  else
    adb -s "$SERIAL" exec-out screencap -p </dev/null > "$OUT/$1-$2.png"
  fi
}

# Screen (viewport) size, from one throwaway capture.
cold_launch "${CHARTS[0]}" 100 0.5 0.5
sleep 2
adb -s "$SERIAL" </dev/null exec-out screencap -p > "$OUT/.probe.png"
VIEW=$("$PY" -c "
from PIL import Image; print('%dx%d' % Image.open('$OUT/.probe.png').size)")
VW=${VIEW%x*}; VH=${VIEW#*x}
log "viewport ${VIEW}"

: > "$CAPTURES"
for chart in "${CHARTS[@]}"; do
  log "chart $chart"
  # Calibration: the app's density matches the golden against its layout box,
  # which carries padding the golden does not. Render the whole diagram once
  # at 0.1x, measure the real ink, and correct the density.
  adb -s "$SERIAL" logcat -c </dev/null >/dev/null 2>&1 || true
  cold_launch "$chart" min 0.5 0.5
  if ! RAW_DENSITY=$(get_field density min 0.5 0.5); then
    echo "ERROR: no density logged for $chart (check 'adb logcat -s System.out:I')" >&2
    exit 1
  fi
  # The emulator can lag behind the layout, so screenshot and validate in a
  # loop: calibrate.py rejects anything that is not a clean diagram render.
  DENSITY=""
  for attempt in 1 2 3 4 5; do
    sleep 1.5
    adb -s "$SERIAL" exec-out screencap -p </dev/null > "$OUT/.probe.png"
    if DENSITY=$("$PY" "$ROOT/oracle/tools/calibrate.py" --capture "$OUT/.probe.png" \
      --scale 0.1 --density "$RAW_DENSITY" --gold "$ROOT/oracle/goldens/$chart.png" 2>/dev/null); then
      break
    fi
    log "  calibration attempt $attempt was not a clean render; retrying"
    DENSITY=""
  done
  if [ -z "$DENSITY" ]; then
    echo "ERROR: could not calibrate $chart - the screen is not showing a clean diagram" >&2
    exit 1
  fi

  # Canvas = the diagram's own size at the calibrated density (logged by the app).
  # Pan 0.25 is used only here, so the log line is unambiguously this render.
  adb -s "$SERIAL" logcat -c </dev/null >/dev/null 2>&1 || true
  launch "$chart" 100 0.25 0.25 "$DENSITY"
  sleep 1
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
  REFINED=""
  for attempt in 1 2 3; do
    if REFINED=$("$PY" "$ROOT/oracle/tools/refine.py" --dir "$OUT" --chart "$chart" \
      --canvas "$CANVAS" --viewport "$VIEW" --tiles "${TILE_SPEC%;}" \
      --density "$DENSITY" --gold "$ROOT/oracle/goldens/$chart.png" 2>/dev/null); then
      break
    fi
    log "  refinement attempt $attempt did not see a complete render; re-capturing"
    REFINED=""
    while read -r row col fx fy; do
      idx_file=""
      [ "$COUNT" -gt 1 ] && idx_file="$row-$col"
      shot "$chart" 100 "$fx" "$fy" "$idx_file" "$DENSITY"
    done <<< "$GRID"
  done
  if [ -z "$REFINED" ]; then
    echo "ERROR: could not measure $chart - the 100% tiles are not a complete render" >&2
    exit 1
  fi
  DENSITY=${REFINED%% *}
  log "  density $DENSITY (ink ${REFINED##* })"

  # The canvas scales with the density, so re-read it for the final framing
  # (pan 0.75 is used only here).
  adb -s "$SERIAL" logcat -c </dev/null >/dev/null 2>&1 || true
  launch "$chart" 100 0.75 0.75 "$DENSITY"
  sleep 1
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
