# Oracle: golden-reference comparison against mermaid.js

Renders the same flowcharts with mermaid.js (the reference implementation)
and with Maradona on desktop, Android and iOS, then compares the images with
tolerances generous enough to absorb web-vs-native text shaping, font and AA
differences — while still catching missing nodes/edges, wrong shapes, broken
styling or geometry regressions.

## Layout

- `charts/*.mmd` — canonical chart sources, one per file; the file name is the chart id
- `goldens/<id>.png` — mermaid.js render (committed; regenerate deliberately)
- `out/<platform>/<id>-<zoom>.png` — our renders (gitignored)
- `out/report-<platform>.json` — last comparison report
- `tools/` — all scripts below

Zoom levels: `fit`, `100` (natural size), `min` (0.1×), `max` (5×). Max is
captured as a 3×3 pan tile grid (`<id>-max-<i><j>.png`) so the whole zoomed
diagram is covered, not just the centred fragment.

## Running

```sh
# 1. Goldens (rarely; only when charts or the theme change on purpose).
#    Uses system Chrome via puppeteer-config.json.
oracle/tools/render_goldens.sh

# 2. Embed charts into the sample apps (run after editing charts/)
python3 oracle/tools/sync_charts.py

# 3a. Desktop renders (headless, JVM):
./gradlew :sample-desktop:desktopTest --tests "dev.maradona.sample.OracleGoldenTest"

# 3b. Android renders (app must be installed; drives oracle mode via intent extras):
./gradlew :sample-android:installDebug    # or: adb -s <serial> install -r ...
oracle/tools/capture_android.sh <adb-serial>

# 3c. iOS renders (app must be installed on a booted simulator):
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'id=<udid>' \
  -derivedDataPath iosApp/build build
xcrun simctl install <udid> iosApp/build/Build/Products/Debug-iphonesimulator/Maradona.app
oracle/tools/capture_ios.sh <udid>

# 4. Compare (uses oracle/tools/.venv; create once: python3 -m venv ... && pip install pillow)
oracle/tools/.venv/bin/python oracle/tools/compare.py <desktop|android|ios>
```

Exit code is non-zero if any pair fails; the console table and the JSON
report show per-chart mismatch fractions.

## How comparison works

Desktop is the strict, scale-matched oracle. `OracleGoldenTest` renders each
chart on a fixed canvas sized to the chart's 100% layout (all zoom levels
share the canvas) and writes `out/desktop/manifest.json` with the canvas,
layout and content sizes; `compare.py` uses it to align everything exactly:

1. Crop the golden to its content bbox, resize to our measured 100% content
   size, paste centred on the manifest canvas → the gold views for `100` and
   `min` (the latter scaled by `MIN_ZOOM`).
2. Flatten near-background pixels to white, gaussian-blur both sides, count
   pixels whose per-channel delta exceeds `PIXEL_TOLERANCE` (48); pass when
   the mismatching fraction is ≤ `MAX_MISMATCH` (15%).
3. `fit` is not rendered on desktop (with a 100%-sized canvas it would equal
   `100`); devices capture it as their only full-diagram view.

Max-zoom tiles can't be compared against mermaid: structural layout
differences (node spacing, label placement) amplify 5× and swamp any pixel
diff. Instead they verify *zoom self-consistency*: our own 100% canvas is
upscaled 5× and cropped with the same pan-window math the renderer uses
(window origin `2.5·(canvas − layout) + f·(5·layout − canvas)` per axis).
That checks that vectors, strokes, arrowheads and text scale correctly at
zoom — the layout fidelity side is covered by `100` and `min`. Zoom tiles
get more blur (`ZOOM_BLUR_RADIUS`) and a looser budget (`ZOOM_MAX_MISMATCH`,
25%) because text re-laid out at 5× differs subtly from 5× the hinted 100%
text; they still catch exploded, clipped or misplaced content.

On phones the screen is the viewport, so `100` and the max tiles can show
clipped fragments; those get a smoke check (bright diagram background — a
dimmed screen means a system dialog/ANR stole the foreground — plus sane
ink coverage: not blank, not a wall of non-diagram pixels) while `fit` and
`min` get the full comparison with letterbox normalisation. The capture
script also verifies our app holds window focus before every screenshot and
aborts loudly otherwise (wedged emulator, lockscreen, ANR dialog).

Thresholds live at the top of `tools/compare.py` — tune them there.

## Adding a chart

Drop `my-chart.mmd` into `charts/`, run `render_goldens.sh` and
`sync_charts.py`, rebuild the samples, re-capture, compare. Keep charts valid
for *both* mermaid.js and Maradona's parser (see the parser KDoc for the
supported subset).
