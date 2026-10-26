# Oracle Verification & Testing

Maradona is verified by unit tests in `:maradona`, offscreen screenshot tests in `:sample-desktop`, and a cross-platform visual and geometry oracle under [`oracle/`](../oracle/).

## Running Unit & Desktop Screenshot Tests

```sh
./gradlew :maradona:desktopTest :sample-desktop:desktopTest
```

`RenderScreenshotTest` renders representative diagrams offscreen to `sample-desktop/build/screenshots/*.png` and verifies pixel-level rendering invariants (nested sequence `rect` highlights, styled class/state/ER fills, Jewel dark/light theme tokens, inline `<img/>` painters, and disjoint sibling subgraphs).

## Cross-Platform Visual & Layout Oracle (`oracle/`)

The `oracle/` suite compares Maradona's output against `mermaid.js` (`mmdc`) reference goldens across **71 fixtures covering all 23 Mermaid diagram families** (**5** for Flowchart + **3** varied fixtures for each of the other 22 families).

### Coverage Summary (`710 / 710` Checks)

| Check Category | Target | Fixtures | Scales per Fixture | Total Checks | Report File |
| --- | --- | ---: | ---: | ---: | --- |
| **Layout / Geometry** | Flowchart 1:1 SVG-vs-IL (`5`) + 22-family invariants & IL-to-PNG ink (`66`) | 71 | 1 (`1x` IL + PNG) | **71 / 71** | `oracle/out/report-layout.json` |
| **Desktop Visual** | JVM / Skiko (`OracleGoldenTest`) | 71 | 3 (`100`, `min`, `max`) | **213 / 213** | `oracle/out/report-desktop.json` |
| **Android Visual** | Android Emulator (`Pixel_7a`) | 71 | 3 (`100`, `min`, `max`) | **213 / 213** | `oracle/out/report-android.json` |
| **iOS Visual** | iOS Simulator (`iPhone SE (3rd generation)`) | 71 | 3 (`100`, `min`, `max`) | **213 / 213** | `oracle/out/report-ios.json` |
| **Combined Viewer** | `oracle/out/web/index.html` (static HTML/JS viewer) | 71 | `1 layout + 9 visual` | **710 / 710** | `oracle/out/web/index.html` |

### How the Comparisons Work

1. **Visual captures (`oracle/tools/compare.py`):**
   - Each fixture is rendered at `100` (`1x`, calibrated to match the `mermaid.js` golden's binding-axis content dimension), `min` (`0.1x`), and `max` (`5x` covering pan-tile grid).
   - At `100` (`1x`), `compare.py` gates binding-axis size drift (`<= 3%`) and reports pixel mismatch for inspection, because font shaping and non-flowchart layout engines differ from Chromium/D3.
   - At `min` (`0.1x`), `compare.py` verifies bounding-box placement (`<= 2%` viewport offset) and scale consistency.
   - At `max` (`5x`), `compare.py` stitches the zoomed pan tiles and gates pixel self-consistency (`<= 10%` mismatch) and ink ratio (`[0.25, 1.6]`) against the upscaled `1x` render.
2. **Layout & geometry checks (`oracle/tools/compare_layout.py`):**
   - For the **5 Flowchart fixtures**, `compare_layout.py` compares Maradona's Dagre layout JSON (`<id>.layout.json`) 1:1 against `mermaid.js`'s SVG geometry (`size <= 5%`, `centre <= 2%` of diagonal, `label <= 3%`, `edges <= 2%`, `render-vs-IL <= 6px`).
   - For the **66 fixtures across the other 22 families**, `OracleGoldenTest` captures all 22 typed layout callbacks and `compare_layout.py` verifies `canvas_bounds`, `sibling_non_overlap`, binding-axis scale (`±3%`), family-specific structural invariants (ordering, containment, compartment height sums, 360° pie sweep sums, commit-center edge attachment, exact normalized `[0,1]×[0,1]` quadrant coordinates, forward-X Sankey flow, contiguous packet bit columns, radial spoke lengths, and treemap value-to-area monotonicity), and **rendered non-background ink presence** inside every positioned item's `[x, y, w, h]` rectangle in `<id>-100.png`.

### Running the Oracle Locally

```sh
# 1. Render all 71 fixtures and layout dumps on Desktop
./gradlew :sample-desktop:desktopTest --tests "dev.maradona.sample.OracleGoldenTest.renderOracleCharts" --rerun-tasks

# 2. Run Desktop visual and layout comparisons
oracle/tools/.venv/bin/python oracle/tools/compare.py desktop
oracle/tools/.venv/bin/python oracle/tools/compare_layout.py

# 3. Build & open the interactive side-by-side / onion-skin / diff viewer
oracle/tools/.venv/bin/python oracle/tools/web_report.py
oracle/tools/serve.sh
```

Note: `oracle/out/web/index.html` is a static HTML/JS comparison viewer used to inspect Desktop, Android, and iOS captures alongside `mermaid.js` goldens in a browser; Maradona does not have a Compose Web/Wasm target.
