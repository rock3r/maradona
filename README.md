# Maradona

Maradona renders Mermaid diagrams as native Compose Multiplatform composables.
It does not use a WebView, a JavaScript engine, or SVG.

It is a native-answer to [Pele](https://pele.run): the same idea (fast Mermaid
rendering that follows your theme), built for Compose instead of the browser.

## Status

Version 0.23 supports flowcharts (`graph` / `flowchart`), sequence diagrams
(`sequenceDiagram`), class diagrams (`classDiagram` / `classDiagram-v2`),
state diagrams (`stateDiagram` / `stateDiagram-v2`), entity-relationship
diagrams (`erDiagram`), mindmaps (`mindmap`), Gantt charts (`gantt`),
pie charts (`pie`), Git graphs (`gitGraph`), timelines (`timeline`),
user journeys (`journey`), quadrant charts (`quadrantChart`), XY charts
(`xychart` / `xychart-beta`), Kanban boards (`kanban`), Sankey diagrams
(`sankey` / `sankey-beta`), requirement diagrams (`requirementDiagram`),
packet diagrams (`packet` / `packet-beta`), radar charts
(`radar` / `radar-beta`), block diagrams (`block` / `block-beta`),
architecture diagrams (`architecture` / `architecture-beta`), treemap
diagrams (`treemap` / `treemap-beta`), C4 diagrams (`C4Context`,
`C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`), and ZenUML
sequence diagrams (`zenuml`).

Supported in flowcharts:

- Directions: `TB`/`TD`, `BT`, `LR`, `RL`
- Node shapes: rect, rounded, stadium, subroutine, cylinder, circle,
  double circle, diamond, hexagon, parallelogram, trapezoid, asymmetric
  (classic bracket syntax and Mermaid v2 `@{ shape: ..., label: "..." }` metadata)
- Edges: solid, dotted, thick, invisible; arrow, cross, and circle start/end heads;
  bidirectional edges (`<-->`, `o--o`, `x--x`); chained edges (`A --> B --> C`);
  grouped edges (`A & B --> C`); edge IDs (`e1@-->`)
- Edge labels: pipe (`-->|text|`) and inline (`-- text -->`)
- Subgraphs, including nesting and edges incident on subgraph IDs
- Styling: `style` (`fill`, `stroke`, `color`, `stroke-width`), `classDef`,
  `class`, inline `:::className`, and `linkStyle` (`stroke`, `stroke-width`)
- Frontmatter (`--- ... ---`) and `%%{init}%%` directives
- Label markup: `<br>`, `<b>`, `<i>` become `AnnotatedString` spans
- Cycles and self-loops

Accepted and ignored: `click` statements.

Supported in sequence diagrams:

- Participants: `participant` and `actor` (stick figure) with optional `as`
  aliases, plus implicit participants from messages and notes, mirrored at top
  and bottom of lifelines
- Messages: `->>`, `-->>`, `->`, `-->`, `-x`, `--x`, `-)`, `--)`, `<<->>`,
  `<<-->>`, and self-messages (`A->>A: text`)
- Activations: `activate` / `deactivate` statements and `+` / `-` arrow
  shorthands, including nested stacked activations
- Notes: `Note left of`, `Note right of`, and `Note over` (single or spanning
  participants)
- Control blocks: `loop`, `alt`/`else`, `opt`, `par`/`and`, `critical`/`option`,
  `break`, `rect`, and `box` participant groupings
- `autonumber` badges and `title`

Supported in class diagrams:

- Classes: `class Name`, `class Name["Label"]`, ``class `Name!` ``, generic types
  (`Square~Shape~` and nested `List~List~int~~`), annotations (`<<interface>>`,
  `<<enumeration>>`, etc.), `{ ... }` member blocks, and colon members (`A : +int x`)
- Members: visibility (`+`, `-`, `#`, `~`), attributes and methods with return types,
  and abstract (`*`, italic) / static (`$`, underlined) classifiers
- Relationships: inheritance/realization (`<|--`, `--|>`, `<|..`, `..|>`), composition
  (`*--`, `--*`), aggregation (`o--`, `--o`), association/dependency (`<--`, `-->`,
  `<..`, `..>`), links (`--`, `..`), lollipop interfaces (`()--`, `--()`), cardinalities
  (`"1" --> "*"`), and labels (`: label`)
- Notes (`note "..."` and `note for Class "..."`), `namespace` blocks (hierarchical and
  flat), directions (`TB`/`TD`, `BT`, `LR`, `RL`), and `style` / `classDef` / `cssClass`

Supported in state diagrams:

- Headers: `stateDiagram` and `stateDiagram-v2`, YAML frontmatter (`title`,
  `hideEmptyDescription`), `title`, `accTitle` / `accDescr`, `hide empty description`,
  and `direction TB|TD|BT|LR|RL` (top-level and per composite state)
- States: simple states (`StateId`), descriptions (`StateId : description`), aliased
  declarations (`state "Label" as s1`), and pseudostates (`[*]` start/end per scope,
  `<<choice>>` / `[[choice]]`, `<<fork>>` / `[[fork]]`, `<<join>>` / `[[join]]`)
- Composite states (`state Composite { ... }`) with nested states, transitions, notes,
  and `--` / `---` concurrent regions
- Transitions (`A --> B : label`, chained `A --> B --> C : label`), notes
  (`note right of`, `note left of`, multi-line `end note`, standalone `note "..." as N`),
  and styling (`classDef`, `class`, `cssClass`, `style`, inline `:::className`)

Supported in entity-relationship (`erDiagram`) diagrams:

- Headers: `erDiagram`, YAML frontmatter (`title`), `title`, `accTitle` / `accDescr`,
  and `direction TB|TD|BT|LR|RL` (top-level and per `subgraph`)
- Entities: simple identifiers (`CUSTOMER`, `LINE-ITEM`), quoted names, bracket aliases
  (`p[Person]`, `a["Customer Account"]`), and `{ ... }` attribute tables (`type name [PK|FK|UK] ["comment"]`)
- Relationships: crow's-foot symbols (`||`, `|o`, `o|`, `}|`, `|{`, `}o`, `o{` with `--`
  identifying or `..` non-identifying) and natural-language aliases (`only one`,
  `zero or more`, `many(0)`, `1`, `0+`, `1+` with `to` / `optionally to`)
- Nested `subgraph id [title] ... end` clusters (including relationships incident on subgraph IDs)
  and styling (`classDef`, `classDef default`, `class`, `cssClass`, `style`, inline `:::className`)

Supported in mindmaps (`mindmap`):

- Headers: `mindmap`, YAML frontmatter (`title`), `title`, and `accTitle` / `accDescr`
- Indentation-driven hierarchy (spaces and tabs, tolerant of uneven sibling indentation)
- Shapes: default, square (`[Label]`), rounded (`(Label)`), circle (`((Label))`),
  bang (`))Label((`), cloud (`)Label(`), and hexagon (`{{Label}}`)
- Multi-line markdown labels (`` ["`**Bold** and *italic*`"] ``), `::icon(...)` and
  `:::class1 class2` decorators, and `classDef` / `class` / `style`

Supported in Gantt charts (`gantt`):

- Headers and directives: `gantt`, YAML frontmatter (`title`, `displayMode: compact`,
  `topAxis`), `title`, `accTitle` / `accDescr`, `dateFormat` (`YYYY-MM-DD`, `HH:mm`,
  `YYYY`, `X`, `x`), `axisFormat`, `tickInterval`, `weekday`, `weekend` (`saturday` /
  `friday`), `excludes` (`weekends`, weekday names, `YYYY-MM-DD` dates),
  `inclusiveEndDates`, and `topAxis`
- Sections (`section Name`), task tags (`done`, `active`, `crit`, `milestone`, `vert`),
  explicit and auto-generated task IDs, dependencies (`after id1 id2`, `until id1 id2`
  including forward references), and `displayMode: compact` row packing

Supported in pie charts (`pie`):

- Headers and directives: `pie`, `pie showData`, `pie title <text>`, YAML frontmatter
  and `%%{init}%%` configuration (`textPosition`, `donutHole`, `highlightSlice`,
  `pieOuterStrokeWidth`), `title`, `showData`, and `accTitle` / `accDescr`
- Slice entries (`"Label" : value`) with percentage labels, optional donut hole,
  highlighted exploded slices, and legend with optional `[value]` display

Supported in Git graphs (`gitGraph`):

- Headers and directives: `gitGraph` (`LR`, `TB`, `BT`), YAML frontmatter and
  `%%{init}%%` configuration (`title`, `showBranches`, `showCommitLabel`,
  `rotateCommitLabel`, `parallelCommits`, `mainBranchName`, `mainBranchOrder`),
  `title`, and `accTitle` / `accDescr`
- Statements: `commit` (`id:`, `tag:`, `type: NORMAL | REVERSE | HIGHLIGHT`),
  `branch <name> [order: <int>]`, `checkout` / `switch`, `merge` (`id:`, `tag:`, `type:`),
  and `cherry-pick` (`id:`, `parent:`, `tag:`, `type:`)

Supported in timelines (`timeline`):

- Headers and directives: `timeline` (`LR`, `TD`/`TB`), YAML frontmatter and
  `%%{init}%%` configuration (`title`, `disableMulticolor`), `title`, and
  `accTitle` / `accDescr`
- Sections (`section <title>`), periods and events (`period : event1 : event2`),
  continuation lines (`: event3`), and `<br>` / `<b>` / `<i>` label markup

Supported in user journeys (`journey`):

- Headers and directives: `journey`, YAML frontmatter and `%%{init}%%` configuration
  (`title`), `title`, and `accTitle` / `accDescr`
- Sections (`section <title>`) and tasks (`Task name : score [: actor1, actor2]`)
  with actor legend badges, actor indicator dots, score tracks, and satisfaction mood faces

Supported in quadrant charts (`quadrantChart`):

- Headers and directives: `quadrantChart`, YAML frontmatter and `%%{init}%%`
  configuration (`title`, `chartWidth`, `chartHeight`), `title`, and `accTitle` / `accDescr`
- Axes and quadrants: `x-axis <left> [--> <right>]`, `y-axis <bottom> [--> <top>]`,
  `quadrant-1`, `quadrant-2`, `quadrant-3`, and `quadrant-4`
- Points and styling: `Point[:::<class>] : [x, y] [color: ..., radius: ..., stroke-color: ..., stroke-width: ...]`
  and `classDef <name> ...`

Supported in XY charts (`xychart` / `xychart-beta`):

- Headers and directives: `xychart` and `xychart-beta`, `horizontal` and `vertical`
  orientations, YAML frontmatter and `%%{init}%%` configuration (`title`, `width`,
  `height`, `chartOrientation`, `showTitle`, `showLegend`, `showDataLabel`,
  `showDataLabelOutsideBar`, `plotColorPalette`), `title`, and `accTitle` / `accDescr`
- Axes: categorical `x-axis [<title>] [cat1, cat2, ...]`, numeric `x-axis [<title>] <min> --> <max>`,
  and numeric `y-axis [<title>] [<min> --> <max>]` with automatic range and nice ticks
- Series: `bar [<title>] [v1, v2, ...]` (grouped when multiple bar series are present)
  and `line [<title>] [v1 ["pointLabel"], v2, ...]` with series legend

Supported in Kanban boards (`kanban`):

- Headers and directives: `kanban`, YAML frontmatter and `%%{init}%%`
  configuration (`title`, `sectionWidth`, `ticketBaseUrl`), `title`, and
  `accTitle` / `accDescr`
- Indentation-based columns (`col[Column Title]`, `[Column Title]`, or bare `Todo`)
  and task cards (`task1[Description]`, `[Description]`)
- Task metadata `@{ ticket: ..., assigned: ..., priority: 'Very High' | 'High' | 'Medium' | 'Low' | 'Very Low', label: ..., icon: ... }`
  and decorators (`::icon(...)`, `:::className`)

Supported in Sankey diagrams (`sankey` / `sankey-beta`):

- Headers and directives: `sankey` and `sankey-beta`, YAML frontmatter and `%%{init}%%`
  configuration (`title`, `width`, `height`, `nodeWidth`, `nodePadding`,
  `nodeAlignment`, `linkColor`, `showValues`, `prefix`, `suffix`, `labelStyle`,
  `nodeColors`), `title`, and `accTitle` / `accDescr`
- RFC-4180 CSV flow records (`source,target,value`) with quoted fields and escaped
  `""` quotes, proportional node bars, and horizontal cubic-Bezier flow ribbons

Supported in requirement diagrams (`requirementDiagram`):

- Headers, directions, and directives: `requirementDiagram`, `direction TB|TD|BT|LR|RL`,
  YAML frontmatter and `%%{init}%%` configuration (`title`), `title`, and `accTitle` / `accDescr`
- Requirement blocks (`requirement`, `functionalRequirement`, `interfaceRequirement`,
  `performanceRequirement`, `physicalRequirement`, `designConstraint`) with `id`,
  `text`, `risk` (`low`, `medium`, `high`), and `verifymethod` (`analysis`,
  `demonstration`, `inspection`, `test`)
- Element blocks (`element`) with `type` and `docRef`
- Relationships (`<src> - <rel> -> <dst>` and `<dst> <- <rel> - <src>`) for
  `contains` (`⊕` marker), `copies`, `derives`, `satisfies`, `verifies`,
  `refines`, and `traces`, plus `classDef`, `class`, `:::className`, and `style`

Supported in packet diagrams (`packet` / `packet-beta`):

- Headers and directives: `packet` and `packet-beta`, YAML frontmatter and
  `%%{init}%%` configuration (`title`, `bitsPerRow`, `showBits`, `bitOrder`,
  `rowHeight`, `bitWidth`, `paddingX`, `paddingY`), `title`, and `accTitle` / `accDescr`
- Bit-range fields (`<start>-<end>: "<label>"`), single-bit fields (`<bit>: "<label>"`),
  and relative bit-count fields (`+<bits>: "<label>"`)
- Automatic multi-row splitting across `bitsPerRow` boundaries and `ascending` /
  `descending` bit numbering

Supported in radar charts (`radar` / `radar-beta`):

- Headers and directives: `radar` and `radar-beta`, YAML frontmatter and
  `%%{init}%%` configuration (`title`, `width`, `height`, `marginTop`,
  `marginRight`, `marginBottom`, `marginLeft`, `showLegend`, `ticks`, `max`,
  `min`, `graticule`, `axisScaleFactor`, `axisLabelFactor`, `curveTension`,
  `curveOpacity`, `curveStrokeWidth`), `title`, and `accTitle` / `accDescr`
- Radial axes (`axis id["Label"], ...`) and curves with positional
  (`curve id["Label"]{v1, v2, ...}`) or axis-keyed
  (`curve id["Label"]{axis1: v1, axis2: v2, ...}`) entries
- Circular and polygonal graticules (`graticule circle | polygon`), smooth
  closed curves, and series legend

Supported in block diagrams (`block` / `block-beta`):

- Headers and directives: `block` and `block-beta`, YAML frontmatter and
  `%%{init}%%` configuration (`title`, `padding`), `title`, and `accTitle` / `accDescr`
- Grid layout: `columns <N | auto>`, `space` / `space:<N>`, column spans (`id:N`,
  `block:group:N`), and nested `block ... end` / `block:id ... end` composite
  containers
- All 15 block shapes (`[]`, `()`, `([])`, `[[]]`, `[()]`, `(())`, `((()))`, `>]`,
  `{}`, `{{}}`, `[/ /]`, `[\ \]`, `[/ \]`, `[\ /]`, and directional block arrows
  `<["Label"]>(right | left | up | down | x | y)`)
- Connecting edges (`-->`, `---`, `<-->`, `o--o`, `x--x`, `==>`, `-.->`, `~~~`,
  `-- "label" -->`, `-->|label|`) and `classDef`, `class`, `:::className`, and `style`

Supported in architecture diagrams (`architecture` / `architecture-beta`):

- Headers and directives: `architecture` and `architecture-beta`, YAML
  frontmatter and `%%{init}%%` configuration (`title`, `padding`, `iconSize`,
  `fontSize`, `nodeSpacing`), `title`, and `accTitle` / `accDescr`
- Groups (`group id(icon)[Title] [in parentGroup]`), services
  (`service id(icon | "iconText")[Title] [in parentGroup]`), and junctions
  (`junction id [in parentGroup]`) with built-in vector icons (`cloud`,
  `database`, `disk`, `internet`, `server`)
- Port-to-port connections (`lhsId({group})?:<L|R|T|B> (<)? ( -- | -[Label]- ) (>)? <L|R|T|B>:rhsId({group})?`)
  and row/column alignment hints (`align row|column id1 id2 ...`)

Supported in treemap diagrams (`treemap` / `treemap-beta`):

- Headers and directives: `treemap` and `treemap-beta`, YAML frontmatter and
  `%%{init}%%` configuration (`title`, `width`, `height`, `padding`,
  `diagramPadding`, `showValues`, `valueFontSize`, `labelFontSize`,
  `borderWidth`, `valueFormat`), `title`, and `accTitle` / `accDescr`
- Indentation-based hierarchical sections (`"Section"`) and leaf items
  (`"Leaf": <value>`) with squarified proportional tile layout
- Inline `:::className` decorators and `classDef` (`fill`, `stroke`, `color`,
  `stroke-width`, `font-weight`)

Supported in C4 diagrams (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`):

- Headers and directives: `C4Context`, `C4Container`, `C4Component`, `C4Dynamic`,
  and `C4Deployment`, `direction TB|TD|BT|LR|RL`, YAML frontmatter and
  `%%{init}%%` configuration (`title`, `c4ShapeInRow`, `c4BoundaryInRow`,
  `diagramMarginX`, `diagramMarginY`), `title`, and `accTitle` / `accDescr`
- Elements: `Person`, `Person_Ext`, `System`, `System_Ext`, `SystemDb`,
  `SystemDb_Ext`, `SystemQueue`, `SystemQueue_Ext`, `Container`, `Container_Ext`,
  `ContainerDb`, `ContainerDb_Ext`, `ContainerQueue`, `ContainerQueue_Ext`,
  `Component`, `Component_Ext`, `ComponentDb`, `ComponentDb_Ext`,
  `ComponentQueue`, and `ComponentQueue_Ext` (positional and `$key="value"`
  named arguments)
- Boundaries and deployment nodes (`{ ... }`): `Boundary`, `Enterprise_Boundary`,
  `System_Boundary`, `Container_Boundary`, `Deployment_Node`, `Node`, `Node_L`,
  and `Node_R`
- Relationships and style/layout overrides: `Rel`, `BiRel`, `Rel_Back`,
  `Rel_U`/`Rel_Up`, `Rel_D`/`Rel_Down`, `Rel_L`/`Rel_Left`, `Rel_R`/`Rel_Right`,
  `RelIndex`, `UpdateElementStyle`, `UpdateBoundaryStyle`, `UpdateRelStyle`, and
  `UpdateLayoutConfig`

Supported in ZenUML diagrams (`zenuml`):

- Headers and directives: `zenuml`, YAML frontmatter and `%%{init}%%`
  configuration (`title`, `mirrorParticipants`), `title`, and `accTitle` /
  `accDescr`
- Participants and groups: `@Actor`, `@Boundary`, `@Control`, `@Entity`,
  `@Database`, `@Collections`, `@Queue`, `@Starter(...)`, `A as "Alias"`, and
  `group Name { ... }`
- Interactions: synchronous method calls (`A->B.method(args) { ... }`,
  `B.method(args) { ... }`, `ret = B.method(args)`), self-calls
  (`internalMethod(args)`), constructor calls (`p = new Service(args)`),
  asynchronous messages (`A->B: message`), and return statements (`return value`,
  `@return A->B: value`)
- Control flow blocks (`if` / `else if` / `else`, `while` / `forEach` / `for` /
  `loop`, `opt`, `par`, `try` / `catch` / `finally`) and `// comment` notes

## How it works

Three layers, each usable on its own:

1. **Parser** (`dev.maradona.parse.FlowchartParser`) — pure Kotlin.
   Turns Mermaid text into a `Flowchart` model. No Compose types.
2. **Layout** (`dev.maradona.layout.FlowchartLayout`) — pure Kotlin.
   A multiplatform port of dagre (`dev.maradona.layout.dagre`): DFS feedback
   arc cycle breaking, network-simplex ranking, label dummy ranks, barycentre
   crossing reduction, Brandes–Köpf coordinate assignment, and shape-outline
   edge attachment (`NodeOutline`). It takes node sizes as input and returns
   positioned nodes, edge waypoints, and subgraph boxes.
3. **Composables** (`dev.maradona.render`) — `commonMain` Compose.
   `MaradonaLayout` is a custom `Layout`. It measures one `MaradonaNode`
   composable per node, feeds the measured sizes to the layout engine,
   and places the nodes. Edge splines (`d3.curveBasis`) and subgraph boxes are
   drawn on one canvas layer behind the nodes. Edge labels and subgraph labels
   are composables too.

Because nodes are real composables, each node has semantics, can take
focus, and uses the platform text renderer. A screen reader can walk
node by node. Each node announces its label and its connections.

## Use

```kotlin
MaradonaDiagram(
    source = """
        flowchart TD
          A[Mermaid text] --> B{Flowchart?}
          B -- Yes --> C[Maradona]
          B -- No --> D[Pele]
          C --> E([Composables])
          D --> E
    """.trimIndent(),
    theme = MaradonaTheme.light(), // or .dark(), or your own
    modifier = Modifier.fillMaxSize(),
)
```

`MaradonaDiagram` shrinks the diagram to fit, then supports pan and zoom
(double-tap resets). If the source stops parsing, the last good diagram
stays on screen, dimmed, with the error shown at the bottom.

## Theme

`MaradonaTheme` is structured around host-customizable sub-models
(`MaradonaPalette`, `MaradonaTypography`, `MaradonaShapes`, `MaradonaSpacing`,
and `MaradonaStyle`) and can be supplied via `ProvideMaradonaTheme` /
`LocalMaradonaTheme` or the `theme` parameter on `MaradonaDiagram`. Built-in
presets include `MaradonaTheme.light()`, `MaradonaTheme.dark()`,
`MaradonaTheme.jewelLight()`, and `MaradonaTheme.jewelDark()`.

## SVG Export

`MaradonaSvgExporter.exportToSvg(source, theme, options)` exports standalone SVG
documents across all 23 Mermaid diagram families without requiring a Compose UI
scene.

## Targets

The library targets Desktop (JVM), Android, and iOS (arm64). All library
code is in `commonMain` and uses only Compose Multiplatform common APIs,
so every target shares the same sources. (Note: `oracle/out/web/index.html`
is a static HTML/JS comparison viewer inspected in headless Chrome to compare
Desktop, Android, and iOS captures against `mermaid.js` goldens — Maradona does
not have a separate web renderer target.)

Samples:

- `sample-desktop` — Desktop editor + live diagram
- `sample-android` — Android app
- `iosApp` + `sample-shared` — iOS app (the shared module builds the
  `MaradonaSample` framework; `iosApp` is the Xcode shell)

## Run

```sh
./gradlew :sample-desktop:run          # desktop editor + live diagram
./gradlew :sample-android:installDebug # Android (device or emulator)
open iosApp/iosApp.xcodeproj           # iOS: build and run from Xcode
./gradlew :maradona:desktopTest :sample-desktop:desktopTest
```

Android requires the SDK path in `local.properties` (`sdk.dir=...`) or
`ANDROID_HOME`. The iOS app builds for Apple-silicon simulators and
devices; Compose Multiplatform no longer ships an iosX64 target.

The sample tests render diagrams offscreen to
`sample-desktop/build/screenshots/*.png`. Use them as visual checks.

## Scalability & Limits

- Supports all 23 Mermaid diagram families: Flowcharts, Sequence, Class, State,
  ER, Mindmap, Gantt, Pie, GitGraph, Timeline, User Journey, Quadrant, XY Chart,
  Kanban, Sankey, Requirement, Packet, Radar, Block, Architecture, Treemap, C4,
  and ZenUML.
- Subgraphs are laid out with horizontal cluster coalescing, non-overlapping
  sibling separation, and cross-subgraph edge obstacle deflection.
- Rich labels support HTML/Markdown inline formatting (`<b>`, `<i>`, `<u>`,
  `<ins>`, `<s>`, `<code>`, `<sup>`, `<sub>`, `<small>`, `<mark>`, `<h1>`–`<h6>`,
  blockquotes), icon glyph badges (`fa:`, `mdi:`, `::icon()`), and inline images
  via `LocalMaradonaImageResolver`.
- Interactive `click` / `link` / `callback` directives, `onNodeClick`, and
  screen-reader accessibility traversal (`diagramNodeInteraction`) are supported
  across all 23 diagram families.
- **Viewport-lazy composition within the single Compose pipeline:** Maradona
  intentionally avoids a parallel Canvas-only renderer so native text
  measurement, hit-testing (`onNodeClick`), and accessibility semantics stay
  unified. When zoomed in or panned (`viewportCullingEnabled = true`),
  `MaradonaLayout` lazily composes only nodes, edge labels, and subgraph headers
  whose bounds intersect the visible viewport (plus overscan) and culls
  off-screen underlay edges while still drawing edges that cross the viewport
  between off-screen endpoints. When zoomed out so that thousands of nodes are
  simultaneously visible inside the viewport, layout and frame times vary with
  graph density and may still be slow.

- **Oracle visual & layout verification (`oracle/`):**
  - **71 fixtures across all 23 Mermaid families** (`5` for Flowchart + `3`
    varied fixtures for each of the other 22 families: Sequence, Class, State,
    ER, Mindmap, Gantt, Pie, GitGraph, Timeline, User Journey, Quadrant, XY
    Chart, Kanban, Sankey, Requirement, Packet, Radar, Block, Architecture,
    Treemap, C4, and ZenUML).
  - **Visual comparisons (`639` total):** every fixture is captured and compared
    at `100` (`1x`), `min` (`0.1x`), and `max` (`5x` pan-tile grid) on **Desktop
    (JVM/Skiko)** (`213/213`), **Android (`Pixel_7a`)** (`213/213`), and **iOS
    (`iPhone SE (3rd generation)`)** (`213/213`). At `1x`, binding-axis scale is
    gated against the `mermaid.js` (`mmdc`) golden (`±3%`) while pixel mismatch
    is reported as a visual aid because font shaping and non-flowchart layout
    engines differ from Chromium/D3; at `5x`, pixel self-consistency (`<= 10%`
    mismatch) and ink ratios (`[0.25, 1.6]`) are gated against the `1x` render.
  - **Layout/geometry comparisons (`71/71`):** the `5` Flowchart fixtures verify
    1:1 `mermaid.js` SVG-vs-Dagre IL node size (`<= 5%`), node centre (`<= 2%`
    of diagonal), edge label (`<= 3%`), edge endpoint (`<= 2%`), and rendered
    fill-vs-IL (`<= 6px`) tolerances; the `66` fixtures across the other 22
    families verify family-specific structural invariants (canvas bounds,
    sibling non-overlap, ordering, containment, compartment sums, angle sums,
    normalized coordinates, and proportional areas) plus IL-to-PNG rendered ink
    presence for every positioned item.
  - **Scope & remaining blind spots:** 3–5 representative fixtures per family
    validate core constructs, directions, and nesting per family, not exhaustive
    combinatorial syntax/theme/interaction permutations.

## License

Apache License 2.0 - see [LICENSE](LICENSE).
