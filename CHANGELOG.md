# Changelog

All notable changes to Maradona are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-10-26

First public release of Maradona (`dev.sebastiano.maradona`).

### Added

- **Core library (`dev.sebastiano.maradona:maradona`)** for **Desktop (`jvm("desktop")`, JDK 25+)**, **Android (`minSdk 24`, JDK 25+)**, and **iOS (`iosArm64`, `iosSimulatorArm64`)**:
  - Pure-Kotlin parsers (`MaradonaParser`), layout engines, interactive Compose Multiplatform renderers (`MaradonaDiagram`), and standalone SVG export (`MaradonaSvgExporter`) across all **23 Mermaid diagram families**:
    1. Flowcharts (`flowchart` / `graph`) with 12 node shapes, Mermaid v2 `@{ shape, label }` metadata, nested subgraphs with cluster coalescing and cross-subgraph edge deflection, `style` / `classDef` / `linkStyle`, and `click` directives.
    2. Sequence diagrams (`sequenceDiagram`) with `participant` / `actor`, activations, `loop` / `alt` / `opt` / `par` / `critical` / `break` / `rect` / `box` frames, notes, `autonumber`, and participant links.
    3. Class diagrams (`classDiagram` / `classDiagram-v2`) with generics, annotations, visibility & static/abstract classifiers, UML relationships, lollipop interfaces, namespaces, and notes.
    4. State diagrams (`stateDiagram` / `stateDiagram-v2`) with composite states, concurrent `--` regions, `[*]` start/end, `<<choice>>`, `<<fork>>`, `<<join>>`, and notes.
    5. Entity-Relationship diagrams (`erDiagram`) with crow's-foot and natural-language cardinalities, `{ type name PK/FK/UK "comment" }` attribute tables, and nested subgraphs.
    6. Mindmaps (`mindmap`) with bidirectional tidy-tree layout, 7 node shapes, `::icon(...)`, and `:::className` decorators.
    7. Gantt charts (`gantt`) with sections, `done` / `active` / `crit` / `milestone` / `vert` tasks, `after` / `until` dependencies, `excludes`, and `compact` mode.
    8. Pie charts (`pie` / `pie showData`) with configurable `donutHole`, `highlightSlice`, and legends.
    9. Git graphs (`gitGraph` `LR` / `TB` / `BT`) with `commit`, `branch`, `checkout`, `merge`, `cherry-pick`, and tags.
    10. Timelines (`timeline`) with sections, multi-era periods, and stacked events.
    11. User Journey diagrams (`journey`) with actor legends, task score tracks, and satisfaction mood faces.
    12. Quadrant charts (`quadrantChart`) with 4 quadrants, rotated Y-axis titles, and normalized `[x, y]` points.
    13. XY charts (`xychart` / `xychart-beta`) with vertical and horizontal orientations, grouped bar series, and line series.
    14. Kanban boards (`kanban`) with columns, task cards, `@{ ticket, assigned, priority, icon }` metadata, and `ticketBaseUrl` links.
    15. Sankey diagrams (`sankey` / `sankey-beta`) with RFC-4180 CSV flow parsing and proportional Bezier ribbons.
    16. Requirement diagrams (`requirementDiagram`) with 6 requirement stereotypes, `element` blocks, risk/verify badges, and `contains` (`⊕`) / trace relationships.
    17. Packet diagrams (`packet` / `packet-beta`) with bit ranges, single-bit flags, relative bit counts (`+N`), and automatic `bitsPerRow` wrapping.
    18. Radar charts (`radar` / `radar-beta`) with radial axes, circular/polygonal graticules, and multi-curve overlays.
    19. Block diagrams (`block` / `block-beta`) with `columns N` grid layout, spans, nested composite containers, 15 block shapes, and directional block arrows.
    20. Architecture diagrams (`architecture` / `architecture-beta`) with groups, services, junctions, built-in vector icons (`cloud`, `database`, `disk`, `internet`, `server`), and port-to-port routing.
    21. Treemap diagrams (`treemap` / `treemap-beta`) with hierarchical sections and squarified proportional leaf tiles.
    22. C4 diagrams (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`) with persons, systems, containers, components, databases, queues, boundaries, and `Rel` / `RelIndex`.
    23. ZenUML sequence diagrams (`zenuml`) with participant stereotypes, nested call activations, control flow blocks (`if`/`else`, `while`/`loop`, `opt`, `par`, `try`/`catch`/`finally`), and inline comments.
  - **Interactive UI & Accessibility**: pinch and `Cmd`/`Ctrl`+scroll zoom, drag pan, double-tap reset, `onNodeClick` (`MaradonaNodeClickEvent`), screen-reader node traversal semantics, rich HTML/Markdown labels, icon glyph badges (`fa:`, `mdi:`, `::icon()`), inline images (`LocalMaradonaImageResolver`), and viewport-lazy composition (`viewportCullingEnabled`).
- **Material 3 Theme Connector (`dev.sebastiano.maradona:maradona-material3`)** for Desktop, Android, and iOS:
  - `ProvideMaterial3MaradonaTheme`, `rememberMaterial3MaradonaTheme`, and `MaradonaTheme.fromMaterial3(colorScheme, typography)` with host token overrides.
- **JetBrains Jewel Theme Connector (`dev.sebastiano.maradona:maradona-jewel`)** for Desktop JVM (JDK 25+):
  - `ProvideJewelMaradonaTheme`, `rememberJewelMaradonaTheme`, and `MaradonaTheme.fromJewel(...)` supporting standalone `IntUiTheme` and IntelliJ Platform `SwingBridgeTheme` without adding Jewel dependencies to Android or iOS artifacts.
