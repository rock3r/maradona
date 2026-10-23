package dev.maradona.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme

private val examples = linkedMapOf(
    "Basic" to """
        flowchart TD
          A[Mermaid text] --> B{Flowchart?}
          B -- Yes --> C[Maradona]
          B -- No --> D[Pele]
          C --> E([Composables])
          D --> E
    """.trimIndent(),
    "Shapes" to """
        flowchart LR
          A[Rect] --> B(Rounded)
          B --> C([Stadium])
          C --> D[(Database)]
          D --> E((Circle))
          E --> F{Decision}
          F --> G{{Hexagon}}
          G --> H[/Parallelogram/]
          H --> I[[Subroutine]]
          I --> J>Flag]
    """.trimIndent(),
    "Edges" to """
        flowchart TD
          A[Start] ==> B[Thick]
          B -. dotted .-> C{Dotted?}
          C -->|pipe label| D[Cross]
          C --o E[Circle head]
          D --x F[End]
          E --- F
          F --> A
    """.trimIndent(),
    "Subgraph" to """
        flowchart TD
          subgraph client[Client]
            UI[Compose UI] --> VM[ViewModel]
          end
          subgraph engine[Maradona]
            P[Parser] --> L[Sugiyama layout]
            L --> R[MaradonaLayout]
          end
          VM --> P
          R --> UI
          style R fill:#d3e5f5,stroke:#205ea6
    """.trimIndent(),
    "Sequence" to """
        sequenceDiagram
          autonumber
          actor User
          participant UI as Compose UI
          participant VM as ViewModel
          participant API as Backend API
          User->>UI: Open diagram
          UI->>+VM: Parse source
          VM->>+API: Fetch config
          API-->>-VM: 200 OK
          VM-->>-UI: Layout ready
          Note over User,UI: Pan & pinch-zoom active
    """.trimIndent(),
    "Class" to """
        classDiagram
          class Customer {
            +String name
            +String email
          }
          class Order {
            +String id
            +total() Money
          }
          class LineItem {
            +int quantity
          }
          class Payment {
            <<interface>>
            +authorize()* bool
          }
          Customer "1" --> "*" Order : places
          Order "1" *-- "*" LineItem : contains
          Order ..|> Payment : settled by
    """.trimIndent(),
    "State" to """
        stateDiagram-v2
          [*] --> Idle
          Idle --> Processing : submit
          state Processing {
            [*] --> Validating
            Validating --> Charging : valid
            Charging --> [*] : paid
          }
          Processing --> Completed : success
          Processing --> Failed : error
          Completed --> [*]
    """.trimIndent(),
    "ER" to """
        erDiagram
          CUSTOMER ||--o{ ORDER : places
          ORDER ||--|{ LINE_ITEM : contains
          PRODUCT ||--o{ LINE_ITEM : "appears in"
          CUSTOMER {
            string id PK
            string name
            string email UK
          }
          ORDER {
            string id PK
            string customerId FK
            date placedAt
          }
          LINE_ITEM {
            int quantity
            float pricePerUnit
          }
    """.trimIndent(),
    "Mindmap" to """
        mindmap
          root((Maradona))
            Diagrams
              Flowcharts
              Sequence
              Class
              State
              ER
            Architecture
              Pure Kotlin Parser
              Sugiyama & Tidy Tree
              Compose Multiplatform
            Targets
              Desktop
              Android
              iOS
    """.trimIndent(),
    "Gantt" to """
        gantt
          title Maradona Release Roadmap
          dateFormat YYYY-MM-DD
          excludes weekends
          section Core Engine
            Flowchart & DagrePort     :done,    fc,  2026-10-01, 5d
            Sequence & Class Layouts  :done,    sc,  after fc, 4d
          section Extended Families
            State & ER Diagrams       :active,  se,  after sc, 4d
            Mindmap & Gantt           :crit, active, mg, after se, 3d
          section Delivery
            Oracle Visual Parity      :         ov,  after mg, 2d
            v0.7 Release              :milestone, m1, after ov, 0d
    """.trimIndent(),
    "Pie" to """
        ---
        config:
          pie:
            donutHole: 0.25
            highlightSlice: Flowcharts
        ---
        pie showData
          title Maradona Diagram Usage
          "Flowcharts" : 42
          "Sequence" : 24
          "Class & ER" : 18
          "State & Gantt" : 10
          "Mindmap & Pie" : 6
    """.trimIndent(),
    "GitGraph" to """
        ---
        title: Maradona Branch History
        ---
        gitGraph
          commit id: "init" tag: "v0.1"
          branch develop
          commit id: "dagre-port"
          branch feature
          commit id: "splines" type: HIGHLIGHT
          checkout develop
          merge feature id: "merge-feat"
          checkout main
          commit id: "hotfix" type: REVERSE
          merge develop id: "release" tag: "v0.9"
          branch release
          cherry-pick id: "splines"
          commit id: "ship"
    """.trimIndent(),
    "Timeline" to """
        timeline
          title Evolution of Maradona
          section Foundation
            v0.1 : Flowchart Parser : Dagre Layout Port
            v0.3 : Sequence Diagrams : Oracle Viewer
          section Relational & Trees
            v0.5 : Class & State Diagrams
                 : ER Crow's-Foot Tables
            v0.7 : Bidirectional Mindmaps
          section Timelines & Charts
            v0.9 : Gantt Schedules : Donut Pie Charts
            v1.0 : GitGraph & Timeline
    """.trimIndent(),
)

private class SampleState(
    val dark: Boolean,
    val source: String,
    val onDarkChange: (Boolean) -> Unit,
    val onSourceChange: (String) -> Unit,
)

/** Live Mermaid editor + diagram, shared by the desktop, Android, and iOS samples. */
@Composable
fun MaradonaSampleApp(
    oracleChart: String? = null,
    oracleZoom: String? = null,
    oraclePanX: Float? = null,
    oraclePanY: Float? = null,
    oracleDensity: Float? = null,
    oracleSeq: Int = 0,
) {
    if (oracleChart != null) {
        OracleMode(oracleChart, oracleZoom, oraclePanX, oraclePanY, oracleDensity, oracleSeq)
        return
    }
    var dark by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(examples.getValue("Basic")) }
    val state = SampleState(
        dark = dark,
        source = source,
        onDarkChange = { dark = it },
        onSourceChange = { source = it },
    )

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            if (maxWidth >= 600.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    EditorPanel(state, modifier = Modifier.width(380.dp).fillMaxHeight())
                    Diagram(state, modifier = Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Diagram(state, modifier = Modifier.weight(1f).fillMaxWidth())
                    EditorPanel(state, modifier = Modifier.fillMaxWidth(), compact = true)
                }
            }
        }
    }
}

@Composable
private fun OracleMode(
    chart: String,
    zoom: String?,
    panX: Float? = null,
    panY: Float? = null,
    requestedDensity: Float? = null,
    seq: Int = 0,
) {
    val source = OracleCharts.all[chart]
    val gold = OracleGoldSizes.content[chart]
    if (source == null || gold == null) {
        Text("Unknown oracle chart '$chart'", modifier = Modifier.padding(24.dp))
        return
    }
    // Pass 1: measure the natural layout size at the device's own density.
    // Skipped when the capture script passes a calibrated density. Keyed by
    // chart: the activity is reused across charts and intents.
    var natural by remember(chart) { mutableStateOf<IntSize?>(null) }
    val measured = natural
    val match = requestedDensity
        ?: measured?.let { minOf(gold.width / it.width.toFloat(), gold.height / it.height.toFloat()) }
    if (match == null) {
        MaradonaDiagram(
            source = source,
            theme = MaradonaTheme.light(),
            modifier = Modifier.fillMaxSize(),
            zoomEnabled = false,
            autoDirection = false,
            initialScale = 1f,
            onNaturalSize = { if (it.width > 0 && it.height > 0) natural = it },
        )
        return
    }
    // Pass 2: render at a density that makes our content the golden's pixel
    // size, so the oracle compares the two 1:1 on every platform. Keyed so a
    // fresh diagram applies its initial view against the settled density.
    val base = LocalDensity.current
    // seq forces a fresh composition for every capture, even when a tile is
    // captured twice with identical parameters (the app would otherwise skip
    // recomposition and the capture script would wait forever for its log line).
    key(chart, zoom, panX, panY, match, seq) {
        CompositionLocalProvider(
            LocalDensity provides Density(base.density * match, base.fontScale),
        ) {
            MaradonaDiagram(
                source = source,
                theme = MaradonaTheme.light(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                // Mermaid does not re-flow a horizontal chart into a vertical one
                // when the viewport is narrow, and the oracle compares against
                // mermaid: keep the layout identical at every zoom level.
                autoDirection = false,
                // Absolute, so 100% means 1:1 with the golden on every platform.
                initialScale = when (zoom) {
                    "min" -> 0.1f
                    "max" -> 5f
                    else -> 1f
                },
                initialPan = if (panX != null && panY != null) Offset(panX, panY) else null,
                onNaturalSize = { size ->
                    // `canvas` is the diagram's own size in px at this density;
                    // the capture scripts frame and compare against it. The
                    // zoom/pan identify the line, so a stale one is never read.
                    oracleLog(
                        "MARADONA_ORACLE chart=$chart canvas=${size.width}x${size.height} " +
                            "density=$match zoom=${zoom ?: "100"} " +
                            "pan=${panX ?: -1},${panY ?: -1} seq=$seq",
                    )
                },
            )
        }
    }
}

@Composable
private fun Diagram(state: SampleState, modifier: Modifier = Modifier) {
    MaradonaDiagram(
        source = state.source,
        theme = if (state.dark) MaradonaTheme.dark() else MaradonaTheme.light(),
        modifier = modifier,
    )
}

@Composable
private fun EditorPanel(state: SampleState, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Maradona sample", style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((name, text) in examples) {
                OutlinedButton(onClick = { state.onSourceChange(text) }) { Text(name) }
            }
        }
        TextField(
            value = state.source,
            onValueChange = state.onSourceChange,
            modifier = if (compact) {
                Modifier.fillMaxWidth().height(140.dp)
            } else {
                Modifier.weight(1f).fillMaxHeight()
            },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            label = { Text("Mermaid flowchart") },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = state.dark, onCheckedChange = state.onDarkChange)
            Text("Dark theme")
            Button(onClick = { state.onSourceChange(examples.getValue("Basic")) }) { Text("Reset") }
        }
        Text(
            "Pan by dragging. Pinch or ⌘/Ctrl+scroll to zoom. Double-tap to reset.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
