# Supported Mermaid Diagram Syntax

Maradona parses and renders **23 Mermaid diagram families** in pure Kotlin (`dev.maradona.parse.MaradonaParser`). This document lists the supported headers, directives, constructs, and styling options for each family.

## Contents

1. [Flowcharts (`flowchart` / `graph`)](#1-flowcharts-flowchart--graph)
2. [Sequence Diagrams (`sequenceDiagram`)](#2-sequence-diagrams-sequencediagram)
3. [Class Diagrams (`classDiagram`)](#3-class-diagrams-classdiagram)
4. [State Diagrams (`stateDiagram` / `stateDiagram-v2`)](#4-state-diagrams-statediagram--statediagram-v2)
5. [Entity-Relationship Diagrams (`erDiagram`)](#5-entity-relationship-diagrams-erdiagram)
6. [Mindmaps (`mindmap`)](#6-mindmaps-mindmap)
7. [Gantt Charts (`gantt`)](#7-gantt-charts-gantt)
8. [Pie Charts (`pie`)](#8-pie-charts-pie)
9. [Git Graphs (`gitGraph`)](#9-git-graphs-gitgraph)
10. [Timelines (`timeline`)](#10-timelines-timeline)
11. [User Journeys (`journey`)](#11-user-journeys-journey)
12. [Quadrant Charts (`quadrantChart`)](#12-quadrant-charts-quadrantchart)
13. [XY Charts (`xychart` / `xychart-beta`)](#13-xy-charts-xychart--xychart-beta)
14. [Kanban Boards (`kanban`)](#14-kanban-boards-kanban)
15. [Sankey Diagrams (`sankey` / `sankey-beta`)](#15-sankey-diagrams-sankey--sankey-beta)
16. [Requirement Diagrams (`requirementDiagram`)](#16-requirement-diagrams-requirementdiagram)
17. [Packet Diagrams (`packet` / `packet-beta`)](#17-packet-diagrams-packet--packet-beta)
18. [Radar Charts (`radar` / `radar-beta`)](#18-radar-charts-radar--radar-beta)
19. [Block Diagrams (`block` / `block-beta`)](#19-block-diagrams-block--block-beta)
20. [Architecture Diagrams (`architecture` / `architecture-beta`)](#20-architecture-diagrams-architecture--architecture-beta)
21. [Treemap Diagrams (`treemap` / `treemap-beta`)](#21-treemap-diagrams-treemap--treemap-beta)
22. [C4 Diagrams (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`)](#22-c4-diagrams-c4context-c4container-c4component-c4dynamic-c4deployment)
23. [ZenUML Sequence Diagrams (`zenuml`)](#23-zenuml-sequence-diagrams-zenuml)

---

## 1. Flowcharts (`flowchart` / `graph`)

- **Directions:** `TB`/`TD`, `BT`, `LR`, `RL`
- **Node shapes:** rect, rounded, stadium, subroutine, cylinder, circle, double circle, diamond, hexagon, parallelogram, trapezoid, asymmetric (classic bracket syntax and Mermaid v2 `@{ shape: ..., label: "..." }` metadata)
- **Edges:** solid, dotted, thick, invisible; arrow, cross, and circle start/end heads; bidirectional edges (`<-->`, `o--o`, `x--x`); chained edges (`A --> B --> C`); grouped edges (`A & B --> C`); edge IDs (`e1@-->`)
- **Edge labels:** pipe (`-->|text|`) and inline (`-- text -->`)
- **Subgraphs:** nested `subgraph ... end` containers, sibling cluster separation, and edges incident on subgraph IDs
- **Styling:** `style` (`fill`, `stroke`, `color`, `stroke-width`), `classDef`, `class`, inline `:::className`, and `linkStyle` (`stroke`, `stroke-width`)
- **Directives:** YAML frontmatter (`--- ... ---`), `%%{init}%%` directives, `accTitle`, and `accDescr`
- **Interactive directives:** `click <id> "<url>" ["<tooltip>"] [_blank|_self]`, `click <id> href "<url>"`, and `click <id> call <callback>(...)`
- **Rich labels:** HTML (`<br>`, `<b>`, `<i>`, `<u>`, `<ins>`, `<s>`, `<code>`, `<sup>`, `<sub>`, `<small>`, `<mark>`, `<h1>`–`<h6>`), Markdown (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`), icon glyph prefixes (`fa:`, `mdi:`, `::icon(...)`), and inline images (`<img src="..."/>`, `![alt](url)`)
- **Topology:** cycles and self-loops

## 2. Sequence Diagrams (`sequenceDiagram`)

- **Participants:** `participant` and `actor` (stick figure) with optional `as` aliases, plus implicit participants from messages and notes, mirrored at top and bottom of lifelines
- **Messages:** `->>`, `-->>`, `->`, `-->`, `-x`, `--x`, `-)`, `--)`, `<<->>`, `<<-->>`, and self-messages (`A->>A: text`)
- **Activations:** `activate` / `deactivate` statements and `+` / `-` arrow shorthands, including nested stacked activations
- **Notes:** `Note left of`, `Note right of`, and `Note over` (single or spanning participants)
- **Control blocks:** `loop`, `alt`/`else`, `opt`, `par`/`and`, `critical`/`option`, `break`, `rect`, and `box` participant groupings
- **Directives & links:** `autonumber` badges, `title`, `link <Actor>: <Label> @ <URL>`, and `links <Actor>: {"Label": "URL"}`

## 3. Class Diagrams (`classDiagram`)

- **Headers:** `classDiagram` and `classDiagram-v2`
- **Classes:** `class Name`, `class Name["Label"]`, ``class `Name!` ``, generic types (`Square~Shape~` and nested `List~List~int~~`), annotations (`<<interface>>`, `<<enumeration>>`, etc.), `{ ... }` member blocks, and colon members (`A : +int x`)
- **Members:** visibility (`+`, `-`, `#`, `~`), attributes and methods with return types, and abstract (`*`, italic) / static (`$`, underlined) classifiers
- **Relationships:** inheritance/realization (`<|--`, `--|>`, `<|..`, `..|>`), composition (`*--`, `--*`), aggregation (`o--`, `--o`), association/dependency (`<--`, `-->`, `<..`, `..>`), links (`--`, `..`), lollipop interfaces (`()--`, `--()`), cardinalities (`"1" --> "*"`), and labels (`: label`)
- **Structure & styling:** notes (`note "..."` and `note for Class "..."`), `namespace` blocks (hierarchical and flat), directions (`TB`/`TD`, `BT`, `LR`, `RL`), `click` / `link` / `callback` directives, and `style` / `classDef` / `cssClass`

## 4. State Diagrams (`stateDiagram` / `stateDiagram-v2`)

- **Headers & directives:** `stateDiagram` and `stateDiagram-v2`, YAML frontmatter (`title`, `hideEmptyDescription`), `title`, `accTitle` / `accDescr`, `hide empty description`, and `direction TB|TD|BT|LR|RL` (top-level and per composite state)
- **States:** simple states (`StateId`), descriptions (`StateId : description`), aliased declarations (`state "Label" as s1`), and pseudostates (`[*]` start/end per scope, `<<choice>>` / `[[choice]]`, `<<fork>>` / `[[fork]]`, `<<join>>` / `[[join]]`)
- **Composite states:** `state Composite { ... }` with nested states, transitions, notes, and `--` / `---` concurrent regions
- **Transitions & styling:** `A --> B : label`, chained `A --> B --> C : label`, notes (`note right of`, `note left of`, multi-line `end note`, standalone `note "..." as N`), `click` directives, and styling (`classDef`, `class`, `cssClass`, `style`, inline `:::className`)

## 5. Entity-Relationship Diagrams (`erDiagram`)

- **Headers & directives:** `erDiagram`, YAML frontmatter (`title`), `title`, `accTitle` / `accDescr`, and `direction TB|TD|BT|LR|RL` (top-level and per `subgraph`)
- **Entities:** simple identifiers (`CUSTOMER`, `LINE-ITEM`), quoted names, bracket aliases (`p[Person]`, `a["Customer Account"]`), and `{ ... }` attribute tables (`type name [PK|FK|UK] ["comment"]`)
- **Relationships:** crow's-foot symbols (`||`, `|o`, `o|`, `}|`, `|{`, `}o`, `o{` with `--` identifying or `..` non-identifying) and natural-language aliases (`only one`, `zero or more`, `many(0)`, `1`, `0+`, `1+` with `to` / `optionally to`)
- **Subgraphs & styling:** nested `subgraph id [title] ... end` clusters (including relationships incident on subgraph IDs) and styling (`classDef`, `classDef default`, `class`, `cssClass`, `style`, inline `:::className`)

## 6. Mindmaps (`mindmap`)

- **Headers & directives:** `mindmap`, YAML frontmatter (`title`), `title`, and `accTitle` / `accDescr`
- **Hierarchy:** indentation-driven tree hierarchy (spaces and tabs, tolerant of uneven sibling indentation)
- **Shapes:** default, square (`[Label]`), rounded (`(Label)`), circle (`((Label))`), bang (`))Label((`), cloud (`)Label(`), and hexagon (`{{Label}}`)
- **Decorators & styling:** multi-line markdown labels (`` ["`**Bold** and *italic*`"] ``), `::icon(...)` and `:::class1 class2` decorators, and `classDef` / `class` / `style`

## 7. Gantt Charts (`gantt`)

- **Headers & directives:** `gantt`, YAML frontmatter (`title`, `displayMode: compact`, `topAxis`), `title`, `accTitle` / `accDescr`, `dateFormat` (`YYYY-MM-DD`, `HH:mm`, `YYYY`, `X`, `x`), `axisFormat`, `tickInterval`, `weekday`, `weekend` (`saturday` / `friday`), `excludes` (`weekends`, weekday names, `YYYY-MM-DD` dates), `inclusiveEndDates`, and `topAxis`
- **Tasks & sections:** `section Name`, task tags (`done`, `active`, `crit`, `milestone`, `vert`), explicit and auto-generated task IDs, dependencies (`after id1 id2`, `until id1 id2` including forward references), `click` directives, and `displayMode: compact` row packing

## 8. Pie Charts (`pie`)

- **Headers & directives:** `pie`, `pie showData`, `pie title <text>`, YAML frontmatter and `%%{init}%%` configuration (`textPosition`, `donutHole`, `highlightSlice`, `pieOuterStrokeWidth`), `title`, `showData`, and `accTitle` / `accDescr`
- **Slices:** `"Label" : value` entries with percentage labels, optional donut hole, highlighted exploded slices, and legend with optional `[value]` display

## 9. Git Graphs (`gitGraph`)

- **Headers & directives:** `gitGraph` (`LR`, `TB`, `BT`), YAML frontmatter and `%%{init}%%` configuration (`title`, `showBranches`, `showCommitLabel`, `rotateCommitLabel`, `parallelCommits`, `mainBranchName`, `mainBranchOrder`), `title`, and `accTitle` / `accDescr`
- **Statements:** `commit` (`id:`, `tag:`, `type: NORMAL | REVERSE | HIGHLIGHT`), `branch <name> [order: <int>]`, `checkout` / `switch`, `merge` (`id:`, `tag:`, `type:`), and `cherry-pick` (`id:`, `parent:`, `tag:`, `type:`)

## 10. Timelines (`timeline`)

- **Headers & directives:** `timeline` (`LR`, `TD`/`TB`), YAML frontmatter and `%%{init}%%` configuration (`title`, `disableMulticolor`), `title`, and `accTitle` / `accDescr`
- **Structure:** `section <title>`, periods and events (`period : event1 : event2`), continuation lines (`: event3`), and rich label formatting

## 11. User Journeys (`journey`)

- **Headers & directives:** `journey`, YAML frontmatter and `%%{init}%%` configuration (`title`), `title`, and `accTitle` / `accDescr`
- **Structure:** `section <title>` and tasks (`Task name : score [: actor1, actor2]`) with actor legend badges, actor indicator dots, score tracks, and satisfaction mood faces

## 12. Quadrant Charts (`quadrantChart`)

- **Headers & directives:** `quadrantChart`, YAML frontmatter and `%%{init}%%` configuration (`title`, `chartWidth`, `chartHeight`), `title`, and `accTitle` / `accDescr`
- **Axes & quadrants:** `x-axis <left> [--> <right>]`, `y-axis <bottom> [--> <top>]`, `quadrant-1`, `quadrant-2`, `quadrant-3`, and `quadrant-4`
- **Points & styling:** `Point[:::<class>] : [x, y] [color: ..., radius: ..., stroke-color: ..., stroke-width: ...]` and `classDef <name> ...`

## 13. XY Charts (`xychart` / `xychart-beta`)

- **Headers & directives:** `xychart` and `xychart-beta`, `horizontal` and `vertical` orientations, YAML frontmatter and `%%{init}%%` configuration (`title`, `width`, `height`, `chartOrientation`, `showTitle`, `showLegend`, `showDataLabel`, `showDataLabelOutsideBar`, `plotColorPalette`), `title`, and `accTitle` / `accDescr`
- **Axes:** categorical `x-axis [<title>] [cat1, cat2, ...]`, numeric `x-axis [<title>] <min> --> <max>`, and numeric `y-axis [<title>] [<min> --> <max>]` with automatic range and nice ticks
- **Series:** `bar [<title>] [v1, v2, ...]` (grouped when multiple bar series are present) and `line [<title>] [v1 ["pointLabel"], v2, ...]` with series legend

## 14. Kanban Boards (`kanban`)

- **Headers & directives:** `kanban`, YAML frontmatter and `%%{init}%%` configuration (`title`, `sectionWidth`, `ticketBaseUrl`), `title`, and `accTitle` / `accDescr`
- **Columns & cards:** indentation-based columns (`col[Column Title]`, `[Column Title]`, or bare `Todo`) and task cards (`task1[Description]`, `[Description]`)
- **Metadata & decorators:** `@{ ticket: ..., assigned: ..., priority: 'Very High' | 'High' | 'Medium' | 'Low' | 'Very Low', label: ..., icon: ... }`, `::icon(...)`, and `:::className`

## 15. Sankey Diagrams (`sankey` / `sankey-beta`)

- **Headers & directives:** `sankey` and `sankey-beta`, YAML frontmatter and `%%{init}%%` configuration (`title`, `width`, `height`, `nodeWidth`, `nodePadding`, `nodeAlignment`, `linkColor`, `showValues`, `prefix`, `suffix`, `labelStyle`, `nodeColors`), `title`, and `accTitle` / `accDescr`
- **Records:** RFC-4180 CSV flow records (`source,target,value`) with quoted fields and escaped `""` quotes, proportional node bars, and horizontal cubic-Bezier flow ribbons

## 16. Requirement Diagrams (`requirementDiagram`)

- **Headers & directives:** `requirementDiagram`, `direction TB|TD|BT|LR|RL`, YAML frontmatter and `%%{init}%%` configuration (`title`), `title`, and `accTitle` / `accDescr`
- **Blocks:** `requirement`, `functionalRequirement`, `interfaceRequirement`, `performanceRequirement`, `physicalRequirement`, `designConstraint` (with `id`, `text`, `risk`, `verifymethod`), and `element` (with `type`, `docRef`)
- **Relationships & styling:** `<src> - <rel> -> <dst>` and `<dst> <- <rel> - <src>` for `contains` (`⊕` marker), `copies`, `derives`, `satisfies`, `verifies`, `refines`, and `traces`, plus `classDef`, `class`, `:::className`, and `style`

## 17. Packet Diagrams (`packet` / `packet-beta`)

- **Headers & directives:** `packet` and `packet-beta`, YAML frontmatter and `%%{init}%%` configuration (`title`, `bitsPerRow`, `showBits`, `bitOrder`, `rowHeight`, `bitWidth`, `paddingX`, `paddingY`), `title`, and `accTitle` / `accDescr`
- **Fields:** bit-range fields (`<start>-<end>: "<label>"`), single-bit fields (`<bit>: "<label>"`), and relative bit-count fields (`+<bits>: "<label>"`) with automatic multi-row splitting across `bitsPerRow` boundaries and `ascending` / `descending` bit numbering

## 18. Radar Charts (`radar` / `radar-beta`)

- **Headers & directives:** `radar` and `radar-beta`, YAML frontmatter and `%%{init}%%` configuration (`title`, `width`, `height`, `marginTop`, `marginRight`, `marginBottom`, `marginLeft`, `showLegend`, `ticks`, `max`, `min`, `graticule`, `axisScaleFactor`, `axisLabelFactor`, `curveTension`, `curveOpacity`, `curveStrokeWidth`), `title`, and `accTitle` / `accDescr`
- **Axes & curves:** radial axes (`axis id["Label"], ...`), curves with positional (`curve id["Label"]{v1, v2, ...}`) or axis-keyed (`curve id["Label"]{axis1: v1, ...}`) entries, circular and polygonal graticules (`graticule circle | polygon`), and series legend

## 19. Block Diagrams (`block` / `block-beta`)

- **Headers & directives:** `block` and `block-beta`, YAML frontmatter and `%%{init}%%` configuration (`title`, `padding`), `title`, and `accTitle` / `accDescr`
- **Grid layout:** `columns <N | auto>`, `space` / `space:<N>`, column spans (`id:N`, `block:group:N`), and nested `block ... end` / `block:id ... end` composite containers
- **Shapes & edges:** all 15 block shapes (`[]`, `()`, `([])`, `[[]]`, `[()]`, `(())`, `((()))`, `>]`, `{}`, `{{}}`, `[/ /]`, `[\ \]`, `[/ \]`, `[\ /]`, and directional block arrows `<["Label"]>(right | left | up | down | x | y)`), connecting edges, and `classDef` / `class` / `:::className` / `style`

## 20. Architecture Diagrams (`architecture` / `architecture-beta`)

- **Headers & directives:** `architecture` and `architecture-beta`, YAML frontmatter and `%%{init}%%` configuration (`title`, `padding`, `iconSize`, `fontSize`, `nodeSpacing`), `title`, and `accTitle` / `accDescr`
- **Groups, services & junctions:** `group id(icon)[Title] [in parentGroup]`, `service id(icon | "iconText")[Title] [in parentGroup]`, and `junction id [in parentGroup]` with built-in vector icons (`cloud`, `database`, `disk`, `internet`, `server`)
- **Connections & alignment:** port-to-port connections (`lhsId({group})?:<L|R|T|B> (<)? ( -- | -[Label]- ) (>)? <L|R|T|B>:rhsId({group})?`) and `align row|column id1 id2 ...`

## 21. Treemap Diagrams (`treemap` / `treemap-beta`)

- **Headers & directives:** `treemap` and `treemap-beta`, YAML frontmatter and `%%{init}%%` configuration (`title`, `width`, `height`, `padding`, `diagramPadding`, `showValues`, `valueFontSize`, `labelFontSize`, `borderWidth`, `valueFormat`), `title`, and `accTitle` / `accDescr`
- **Hierarchy & styling:** indentation-based sections (`"Section"`) and leaf items (`"Leaf": <value>`) with squarified proportional tile layout, inline `:::className` decorators, and `classDef`

## 22. C4 Diagrams (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`)

- **Headers & directives:** `C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, and `C4Deployment`, `direction TB|TD|BT|LR|RL`, YAML frontmatter and `%%{init}%%` configuration (`title`, `c4ShapeInRow`, `c4BoundaryInRow`, `diagramMarginX`, `diagramMarginY`), `title`, and `accTitle` / `accDescr`
- **Elements:** `Person`, `Person_Ext`, `System`, `System_Ext`, `SystemDb`, `SystemDb_Ext`, `SystemQueue`, `SystemQueue_Ext`, `Container`, `Container_Ext`, `ContainerDb`, `ContainerDb_Ext`, `ContainerQueue`, `ContainerQueue_Ext`, `Component`, `Component_Ext`, `ComponentDb`, `ComponentDb_Ext`, `ComponentQueue`, and `ComponentQueue_Ext` (positional and `$key="value"` named arguments including `$link`)
- **Boundaries & deployment nodes:** `Boundary`, `Enterprise_Boundary`, `System_Boundary`, `Container_Boundary`, `Deployment_Node`, `Node`, `Node_L`, and `Node_R`
- **Relationships & overrides:** `Rel`, `BiRel`, `Rel_Back`, `Rel_U`/`Rel_Up`, `Rel_D`/`Rel_Down`, `Rel_L`/`Rel_Left`, `Rel_R`/`Rel_Right`, `RelIndex`, `UpdateElementStyle`, `UpdateBoundaryStyle`, `UpdateRelStyle`, and `UpdateLayoutConfig`

## 23. ZenUML Sequence Diagrams (`zenuml`)

- **Headers & directives:** `zenuml`, YAML frontmatter and `%%{init}%%` configuration (`title`, `mirrorParticipants`), `title`, and `accTitle` / `accDescr`
- **Participants & groups:** `@Actor`, `@Boundary`, `@Control`, `@Entity`, `@Database`, `@Collections`, `@Queue`, `@Starter(...)`, `A as "Alias"`, and `group Name { ... }`
- **Interactions & control flow:** synchronous calls (`A->B.method(args) { ... }`, `B.method(args) { ... }`, `ret = B.method(args)`), self-calls (`internalMethod(args)`), constructor calls (`p = new Service(args)`), asynchronous messages (`A->B: message`), return statements (`return value`, `@return A->B: value`), control blocks (`if` / `else if` / `else`, `while` / `forEach` / `for` / `loop`, `opt`, `par`, `try` / `catch` / `finally`), and `// comment` notes
