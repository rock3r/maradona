# Oracle: golden-reference comparison against mermaid.js

Renders the same flowcharts with mermaid.js (the reference implementation) and
with Maradona on desktop, Android and iOS, then compares them.

Every capture is framed the same way on every platform, so the comparison is
1:1 — no letterboxing, no rescaling of our render:

* the render density is chosen so Maradona's content comes out at the golden's
  pixel size (the sample apps do this themselves; the capture scripts refine the
  density once they can measure the real ink),
* the diagram canvas is centred in the platform's viewport (the desktop render
  scene, or a device screen),
* each level renders at an absolute scale (`initialScale`): 1×, 0.1×, 5×,
* levels too big for the viewport are captured as pan tiles, and `compare.py`
  replays the same pan maths to crop the matching view out of the golden.

## Two levels, because pixels cannot express fidelity

A flowchart is sparse line art: the pixel diff between two *completely
different* renders is still only ~2% of the canvas, and a deliberate 4px shift
scores the same as a perfect render (measured: 0.084 vs 0.085). So there is no
mismatch budget for the golden levels any more, and fidelity is measured where
it is actually defined - in geometry:

**1. Layout: Maradona's IL vs mermaid's SVG** (`tools/compare_layout.py`)

mermaid's SVG is a machine-readable geometry oracle: node ids and shape
elements, edge topology and paths, label boxes, clusters. `golden_geometry.py`
extracts it into `goldens/<id>.geometry.json` (validated against the PNG: SVG
boxes and rasterised fill boxes agree within 1-2px). Maradona's side is its own
intermediate representation - the `FlowchartLayout` result, dumped by the
desktop test as `out/desktop/<id>.layout.json` (`onLayout` on
`MaradonaDiagram`/`MaradonaLayout`).

The comparison is per node id: box size, centre offset, edge-label placement,
label padding, at matched scale (the absolute scale difference is reported
separately so a calibration artifact cannot hide as geometry).

For this to mean anything the two sides have to agree on their inputs, which
took most of the work:

* **font**: both render Roboto 14px - the TTF is vendored at `oracle/fonts`,
  loaded by mermaid through `--cssFile` and by the oracle renderer via Skia
  (`MaradonaTheme.fontFamily`). The goldens used to be "trebuchet ms".
* **shadow**: mermaid's `neo` look drop-shadows node shapes, which inflated the
  rasterised fills the extractor measures. Disabled with
  `themeVariables.dropShadow: none` (we draw none).
* **node metrics**: `NodeSizing` ports mermaid's own rules - labels wrap at
  `wrappingWidth` 120 and clamp to `minNodeWidth` 120 (circles measure the raw
  text), line height 1.5, and each shape's padding formula, with the shape
  point sets derived from the box height so the drawn shape and the box agree.
* **spacing**: mermaid configures dagre with 50/50, but dagre halves `ranksep`
  and doubles every `minlen`, so the gap drawn is 40 units; a labelled edge gets
  a 61.5-unit label rank (label height + 2 * `edgesep`).
* **coordinate assignment**: ranks are centred by their own width. dagre
  additionally aligns nodes with their neighbours, but its result for a
  flowchart is what rank-centred packing produces anyway (a parent over its
  children's span, a merge node between its parents); the alignment sweeps we
  had pulled those nodes to one side.

Current worst case per chart (node size / centre offset, both relative):

| chart | size | centre | absolute scale |
| --- | --- | --- | --- |
| basic-td | 2.4% | 0.2% | 0.999 |
| edges-heads | 0.2% | 2.9% | 0.998 |
| shapes-lr | 6.1% | 0.8% | 1.003 |
| styling | 0.6% | 0.4% | 1.003 |
| subgraphs | 26.0% | 8.9% | 0.882 |

Known remaining differences, with their mechanism:

* **subgraphs** (11-16%): mermaid's dagre wrapper builds a *separate nested
  dagre graph per cluster* (`chunk-WA6RL3DZ.mjs`: `clusterGraph = new
  Graph({compound: true}).setGraph({nodesep: 50, ranksep: 50, ...})`), lays it
  out recursively and composes the results. So a cluster's children get their
  own rank spacing and margins, which is why the drawn gaps vary (measured 69,
  105 and 177 units in one chart) rather than following one set of rules.
  Matching it means porting dagre's rank/order/BK pipeline plus that
  composition step.
* **flag, subroutine** (a few %): mermaid reuses `node.width`/`node.height`
  between its two render passes, so those shapes come out larger than their own
  formula.
* **long-edge endpoints** (edges-heads' root and sink, 65px): dagre aligns an
  endpoint with the dummy chain that routes its long edge, which places it
  beside the main column; we keep it on the column.
* **edge routing**: mermaid draws `curve: basis` splines; we draw orthogonal
  polylines.

**2. Rendering: our pixels vs our IL** (same script, `renderer:` line)

The renderer must draw the IL it was given: node boxes and positions are
compared against the IL rects (relative geometry, so the capture's frame offset
does not count). This separates "our layout is wrong" from "our renderer is
wrong". It is a first cut - it matches rendered fill boxes to IL rects
heuristically, so a couple of nodes per chart come out ambiguous.

## Capture checks

`compare.py` keeps the per-platform capture checks and the visual diff:

| level | what it proves |
| --- | --- |
| `100` | capture sanity (viewport-sized, bright background, plausible ink) and the size relationship: our content must match the golden's in the axis the density was matched against (<=3%). |
| `min` | 0.1x: the (sub-pixel) drawing sits where the golden's does. |
| `max` | 5x scale self-consistency against our own 100% render, with an ink-ratio check. |

The per-level `mismatch` in the report is the share of the canvas that differs
- reported for the viewer, deliberately not a gate. The share of the *drawing*
that differs (`inkMismatch`, ~200-300% here) is the honest number: the two
drawings share almost no pixels.

## Old levels section

| level | what it proves |
| --- | --- |
| `100` | fidelity: our full-size render vs the golden, aligned by content bbox. Also enforces the size relationship the oracle exists for: our content must match the golden's in the binding axis (≤3%), because that is the axis the density was matched against. |
| `min` | 0.1×: the drawing sits where the golden's does (position and size of the faint content). At 0.1× strokes are sub-pixel, so ink counting says nothing. |
| `max` | 5×: scale correctness. Mermaid cannot produce a meaningful 5× reference (its raster would be a blurry upscale, ours crisp vector), so the reference is our own 100% render upscaled 5×, and the ink we drew must be comparable to it. |

`fit` is deliberately absent: on desktop it equals 100% (the canvas is the
100% size), and on a device it is just a viewport-dependent view of the same
render, not a content-fidelity level.

## What is and is not checked

Capture sanity comes first, and is what makes the failures loud: a capture must
be viewport-sized, have a bright diagram background (a dimmed screenshot means a
system dialog or an ANR stole the foreground) and plausible ink. The device
capture scripts additionally verify that our app holds window focus before every
screenshot, and abort otherwise.

The pixel diff against the golden is a *gross-error* check, not a fidelity
verdict. Maradona's layout is not mermaid's (our diagrams are 15–75% narrower
than mermaid's for the same content, and an `LR` chart of ours is a single row
where mermaid's wraps), so thin lines land a few pixels apart and a large share
of the drawing differs even when the render is faithful — `inkMismatch` in the
report is that share, reported but not gated. What the fidelity levels do
enforce is the size relationship and the absence of missing/wrong content; the
per-chart `drift` (our content size vs the golden's, per axis) is reported in
the viewer so the layout difference stays visible instead of being normalised
away.

## Layout

- `charts/*.mmd` — canonical chart sources, one per file; the file name is the chart id
- `goldens/<id>.png` — mermaid.js render (committed; regenerate deliberately)
- `out/<platform>/manifest.json` — canvas, viewport and the pan fraction of every tile
- `out/<platform>/<id>-<level>.png`, `<id>-<level>-<i>-<j>.png` — our renders (gitignored)
- `out/report-<platform>.json` — last comparison report
- `out/web/` — viewer (see below)
- `tools/` — all scripts below

## Running

```sh
# 1. Goldens (rarely; only when charts or the theme change on purpose).
#    Uses system Chrome via puppeteer-config.json.
oracle/tools/render_goldens.sh

# 2. Embed charts and golden sizes into the sample apps (needs Pillow):
oracle/tools/.venv/bin/python oracle/tools/sync_charts.py

# 3a. Desktop renders (headless, JVM):
./gradlew :sample-desktop:desktopTest --tests "dev.maradona.sample.OracleGoldenTest"

# 3b. Android renders (app installed on a booted emulator):
./gradlew :sample-android:installDebug    # or: adb -s <serial> install -r ...
oracle/tools/capture_android.sh <emulator-serial>

# 3c. iOS renders (app installed on a booted simulator):
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'id=<udid>' \
  -derivedDataPath iosApp/build build
xcrun simctl install <udid> iosApp/build/Build/Products/Debug-iphonesimulator/Maradona.app
oracle/tools/capture_ios.sh <udid>

# 4. Compare (uses oracle/tools/.venv; create once: python3 -m venv ... && pip install pillow numpy)
oracle/tools/.venv/bin/python oracle/tools/compare.py <desktop|android|ios>

# 5. Viewer
oracle/tools/serve.sh          # http://localhost:8931/out/web/
```

Exit code is non-zero if any level fails; the console table and the JSON report
show per-chart mismatch, ink ratio and size drift.

### Device capture requirements

Device captures are compared pixel for pixel, so the screen has to be
deterministic:

* **emulator / simulator only** (`capture_android.sh` refuses physical devices
  unless you pass `--allow-physical`, and even then the capture will most likely
  fail comparison). Use an emulator with hardware GPU acceleration and a
  simulator without a notch or island (iPhone SE): cutouts are painted into
  screenshots and counted as ink.
* the app hides the system bars itself in oracle mode; the Android script also
  suppresses the one-time immersive-mode confirmation toast and the camera
  cutout overlay, and pins the font scale,
* no dynamic theming: the sample's diagram colours come from
  `MaradonaTheme.light()` and its activity theme is a static light theme, so the
  system wallpaper/dark mode cannot leak in,
* the capture scripts render each chart at a calibrated density, verify the
  result is a real render (not a dialog, dimmed screen or stale frame) and retry
  otherwise; every capture carries a sequence number so the app always
  re-renders and re-logs, even when a tile is captured twice with identical
  parameters.

## Adding a chart

Drop `my-chart.mmd` into `charts/`, run `render_goldens.sh` and `sync_charts.py`
(regenerates `OracleCharts.kt` and `OracleGoldSizes.kt`), rebuild the samples,
re-capture, compare. Keep charts valid for *both* mermaid.js and Maradona's
parser (see the parser KDoc for the supported subset).

## Thresholds

All at the top of `tools/compare.py`: pixel tolerance, fidelity/scale mismatch
budgets, the binding-axis size tolerance, ink bounds and the 0.1× placement
tolerance. They were calibrated against real captures on all three platforms.
