# Maradona

Maradona renders Mermaid diagrams as native Compose Multiplatform composables.
It does not use a WebView, a JavaScript engine, or SVG.

It is a native-answer to [Pele](https://pele.run): the same idea (fast Mermaid
rendering that follows your theme), built for Compose instead of the browser.

## Status

Version 0.5 supports flowcharts (`graph` / `flowchart`), sequence diagrams
(`sequenceDiagram`), class diagrams (`classDiagram` / `classDiagram-v2`),
state diagrams (`stateDiagram` / `stateDiagram-v2`), and entity-relationship
diagrams (`erDiagram`).
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

- Flowcharts, sequence diagrams, class diagrams, state diagrams, and ER diagrams only. Other types come later.
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
