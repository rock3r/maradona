# Maradona

Maradona renders Mermaid diagrams as native Compose Multiplatform composables.
It does not use a WebView, a JavaScript engine, or SVG.

It is a native-answer to [Pele](https://pele.run): the same idea (fast Mermaid
rendering that follows your theme), built for Compose instead of the browser.

## Status

Version 0.20 supports flowcharts (`graph` / `flowchart`), sequence diagrams
(`sequenceDiagram`), class diagrams (`classDiagram` / `classDiagram-v2`),
state diagrams (`stateDiagram` / `stateDiagram-v2`), entity-relationship
diagrams (`erDiagram`), mindmaps (`mindmap`), Gantt charts (`gantt`),
pie charts (`pie`), Git graphs (`gitGraph`), timelines (`timeline`),
user journeys (`journey`), quadrant charts (`quadrantChart`), XY charts
(`xychart` / `xychart-beta`), Kanban boards (`kanban`), Sankey diagrams
(`sankey` / `sankey-beta`), requirement diagrams (`requirementDiagram`),
packet diagrams (`packet` / `packet-beta`), radar charts
(`radar` / `radar-beta`), block diagrams (`block` / `block-beta`), and
architecture diagrams (`architecture` / `architecture-beta`).
Other Mermaid diagram types are not supported yet.

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

`MaradonaTheme` is a data class of colours and text sizes. Build one from
your Material colour scheme, or use `MaradonaTheme.light()` / `.dark()`.
A theme change re-renders the diagram with the new colours.

## Targets

The library targets Desktop (JVM), Android, and iOS (arm64). All library
code is in `commonMain` and uses only Compose Multiplatform common APIs,
so every target shares the same sources.

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

## Limits

- Flowcharts, sequence diagrams, class diagrams, state diagrams, ER diagrams, mindmaps, Gantt charts, pie charts, Git graphs, timelines, user journeys, quadrant charts, XY charts, Kanban boards, Sankey diagrams, requirement diagrams, packet diagrams, radar charts, block diagrams, and architecture diagrams only. Other types come later.
- Subgraphs are bounding boxes. The layout engine does not lay out a
  subgraph as its own cluster yet, so members may not stay together in
  large graphs.
- HTML in labels is limited to line breaks, bold, and italic.
- No icons and no images inside nodes.
- No SVG or PNG export from the library yet. On Desktop you can render
  offscreen with `ImageComposeScene`; that is how the sample tests work.
- One composable per node suits documentation-size diagrams. Graphs with
  thousands of nodes need a canvas fast path, which is future work.

## License

Apache License 2.0 - see [LICENSE](LICENSE).
