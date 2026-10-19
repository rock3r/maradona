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

### Current state

| chart | node size | centre offset | edge label | edge endpoints |
| --- | --- | --- | --- | --- |
| basic-td | 0.4% | 0.3% | 0.3% | 0.8% |
| edges-heads | 0.0% | 0.2% | 0.1% | 2.9% |
| shapes-lr | 0.2% | 0.0% | 0.0% | 1.4% |
| styling | 0.3% | 0.1% | 0.0% | 0.9% |
| subgraphs | 0.4% | 0.9% | 0.0% | 3.8% |

All five charts are within tolerance, and Maradona runs a port of dagre
(`maradona/layout/dagre`): ranking (longest path, feasible tight tree, network
simplex), crossing minimisation (DFS order, barycentre sweeps, weighted crossing
count) and coordinate assignment (Brandes-Köpf with dagre's conflicts and four
averaged alignments), in `dagre/layout.js` order.

**The goldens are rendered with `flowchart.layout: "dagre"`.** mermaid 12
defaults flowchart layout to tidy-tree (mermaid-cli registers
`@mermaid-js/layout-tidy-tree`), which Maradona does not implement; the oracle
therefore asks mermaid for the algorithm we actually implement. Comparing
against the default would mean comparing against a different layout engine.
That was the single biggest discovery here: every "cluster difference" chased
before it was tidy-tree's output, not dagre's.

Other things that had to line up, all found by measurement:

* dagre halves `ranksep` and doubles every `minlen` to make room for edge
  labels, so the gap drawn between ranks is `2 * ranksep/2` = 50 and the sibling
  gap is `nodesep` = 50 (not 40, which tidy-tree's spacing had led us to).
* `removeEmptyRanks` must not compact, except on ranks that are multiples of
  `nodeRankFactor`.
* with dagre, mermaid passes edge labels as their *text* box, so a one-line
  label occupies a ~21-unit rank (tidy-tree's was 60).
* some shapes draw outside their label box: a cylinder's path has a cap above and
  a bulge below (`labelH + 24 + 3*ry`), and a flag's notch pokes past the left
  edge (adding `h/4` of width).
* a cluster's box comes from dagre's border dummies: ~40 units outside its
  children horizontally, one rank (25) above and below.
* the diagram's bounds come from nodes, clusters and edge labels - not from the
  routed polylines, which would shift the whole diagram when an edge swings wide.

### The one remaining difference: clusters

`subgraphs` is off by 11.5% of the diagonal, and the reason is pinned down.
Reserving rank space for cluster boundaries the way dagre's nesting graph does
(an edge that crosses a cluster gets extra `minlen`) took it from 26% to 11.5%
and stopped the cluster boxes overlapping their neighbours. The rest is the
extraction described below.
mermaid pre-processes clusters before dagre sees the graph, in its dagre wrapper
(`chunk-WA6RL3DZ.mjs`):

1. `clusterDb` records, for every cluster, a *safe anchor* child
   (`findNonClusterChild`, preferring a child with no edges in common with the
   cluster) and whether the cluster has an external connection.
2. `adjustClustersAndEdges` rewrites any edge that is incident on a cluster id so
   that it is incident on that anchor child instead, and then marks the cluster
   as having external connections.
3. `extractor` replaces every cluster that has children and *no* external
   connections with a `clusterNode` holding its own dagre graph: the cluster's
   subtree is copied in and laid out with the **opposite rank direction**
   (`rankdir === 'TB' ? 'LR' : 'TB'`), `nodesep`/`ranksep` 50 and margins 8.
   Nested clusters repeat this recursively (depth-limited).
4. When rendering, a cluster node is drawn from its top-left rather than its
   centre (`translate(x - w/2, y - h/2 - 8)`), so its box is offset by mermaid's
   padding rather than centred.

Evidence that this is what produces the golden and not dagre's own compound
layout: running real dagre with compound nodes on the same node sizes and
spacings gives a *mirrored* result (UI at x=174.5, VM at x=119) where the golden
has the opposite (UI 107.5, VM 152), and a different rank spacing (85 vs 105
units). Neither a plain TB nor an LR cluster graph reproduces the golden's
diagonal child offsets (44.5, 105) on its own, so the anchored external edges are
part of the internal layout too.

Porting that machinery (the three steps above plus the recursive composition and
the top-left cluster anchoring) is what remains for this chart. dagre's own
nesting-graph/compound implementation is already ported in
`layout/dagre/DagreClusters.kt` and `DagreOrderCompound.kt`; it models a
different algorithm, so it is not wired in.

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
