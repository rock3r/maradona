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

Zoom levels: `fit`, `100` (natural size), `min` (0.1×), `max` (5×).

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

1. Crop each image to its content bbox (background = dominant corner colour).
2. Flatten near-background pixels to white (our bg is cream, mermaid's white).
3. Letterbox both onto a shared canvas, never upscaling the smaller side.
4. Gaussian-blur to absorb AA/font differences.
5. Count pixels whose per-channel delta exceeds `PIXEL_TOLERANCE` (48);
   pass when the mismatching fraction is ≤ `MAX_MISMATCH` (15%).

On phones the screen is the viewport, so `100` and `max` can show clipped
fragments; those levels get a smoke check (rendered, non-blank, sane ink
coverage) instead of a pixel comparison. Desktop compares all four levels:
its renderer sizes the viewport so the whole diagram fits at every zoom.

Thresholds live at the top of `tools/compare.py` — tune them there.

## Adding a chart

Drop `my-chart.mmd` into `charts/`, run `render_goldens.sh` and
`sync_charts.py`, rebuild the samples, re-capture, compare. Keep charts valid
for *both* mermaid.js and Maradona's parser (see the parser KDoc for the
supported subset).
