package dev.maradona.sample

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import dev.maradona.theme.material3.ProvideMaterial3MaradonaTheme

val sampleExamples: Map<String, String> = linkedMapOf(
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
    "Journey" to """
        journey
          title Adopting Maradona in a Compose App
          section Evaluate
            Browse Pele examples: 5: Developer
            Try WebView fallback: 1: Developer, QA
          section Integrate
            Add maradona dependency: 5: Developer
            Render live Mermaid: 5: Developer, Designer
          section Ship
            Run Desktop & iOS checks: 4: Developer, QA
    """.trimIndent(),
    "Quadrant" to """
        quadrantChart
          title Reach and Engagement of Campaigns
          x-axis Low Reach --> High Reach
          y-axis Low Engagement --> High Engagement
          quadrant-1 Expand & Scale
          quadrant-2 Promote Reach
          quadrant-3 Re-evaluate
          quadrant-4 Improve Content
          Campaign A: [0.30, 0.60]
          Campaign B: [0.45, 0.23]
          Campaign C:::star: [0.76, 0.78]
          Campaign D: [0.78, 0.34]
          Campaign E: [0.35, 0.78]
          classDef star color: #d14d41, radius: 8, stroke-color: #af3029, stroke-width: 2px
    """.trimIndent(),
    "XY Chart" to """
        xychart-beta
          title "Monthly Revenue & Target"
          x-axis "Month" [Jan, Feb, Mar, Apr, May, Jun]
          y-axis "Revenue (${'$'}k)" 0 --> 120
          bar "Actual" [52, 68, 74, 91, 86, 108]
          line "Target" [50, 60, 75, 85, 95 "Q2 Goal", 110]
    """.trimIndent(),
    "Kanban" to """
        ---
        config:
          kanban:
            ticketBaseUrl: 'https://mermaidchart.atlassian.net/browse/#TICKET#'
        ---
        kanban
          todo[Todo]
            [Write Kanban parser]@{ ticket: MRD-14, assigned: 'rock3r', priority: 'High' }
            docs[Update README examples]@{ priority: 'Low' }
          inprog[In Progress]
            layout[Implement Kanban column & card layout]@{ ticket: MRD-15, assigned: 'rock3r', priority: 'Very High' }
          done[Done]
            xy[Ship XY chart support]@{ ticket: MRD-13, assigned: 'rock3r' }
    """.trimIndent(),
    "Sankey" to """
        sankey-beta
          Solar,Electricity grid,120
          Wind,Electricity grid,290
          Hydro,Electricity grid,90
          Electricity grid,Industry,240
          Electricity grid,"Heating & cooling, homes",180
          Electricity grid,Losses,80
    """.trimIndent(),
    "Requirement" to """
        requirementDiagram
          requirement checkout_req {
            id: 1
            text: Orders must be payable online.
            risk: high
            verifymethod: test
          }
          functionalRequirement payment_req {
            id: 1.1
            text: Card payments must be authorized.
            risk: medium
            verifymethod: demonstration
          }
          element checkout_service {
            type: service
            docRef: src/checkout
          }
          checkout_req - contains -> payment_req
          checkout_service - satisfies -> payment_req
    """.trimIndent(),
    "Packet" to """
        packet-beta
          title "UDP & Header Segment"
          0-15: "Source Port"
          16-31: "Destination Port"
          32-47: "Length"
          48-63: "Checksum"
          +6: "Reserved"
          70: "URG"
          71: "ACK"
          +24: "Options / Payload"
    """.trimIndent(),
    "Radar" to """
        radar-beta
          title "Engineering Team Assessment"
          axis m["Math"], s["Science"], e["English"]
          axis h["History"], g["Geography"], a["Art"]
          curve alice["Alice"]{85, 90, 80, 70, 75, 90}
          curve bob["Bob"]{70, 75, 85, 80, 90, 85}
          graticule polygon
          max 100
          min 0
    """.trimIndent(),
    "Block" to """
        block-beta
          columns 3
          Frontend["Frontend Web"] arrow1<["HTTPS"]>(right) Backend["API Gateway"]
          space:2 arrow2<["SQL"]>(down)
          Cache(["Redis Cache"]) arrow3<["Read"]>(left) Database[("Orders DB")]
          classDef primary fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
          classDef storage fill:#dcfce7,stroke:#16a34a,color:#14532d
          class Frontend,Backend primary
          class Cache,Database storage
    """.trimIndent(),
    "Architecture" to """
        architecture-beta
          group api(cloud)[Cloud API]
          service gateway(internet)[Gateway] in api
          service app(server)[App Server] in api
          service db(database)[Primary DB] in api
          service cache(disk)[Object Store] in api
          gateway:R --> L:app
          app:R --> L:db
          app:B --> T:cache
    """.trimIndent(),
    "Treemap" to """
        treemap-beta
        "Engineering"
          "Platform"
            "Compute": 45
            "Storage": 28
          "Product"
            "Web App": 36
            "Mobile": 24
        "Operations"
          "Security": 22
          "Support": 15
    """.trimIndent(),
    "C4" to """
        C4Context
          title Internet Banking System
          Person(customer, "Banking Customer", "A customer of the bank")
          Enterprise_Boundary(bank, "BigBank plc") {
            System(banking, "Internet Banking", "Provides account balances and payments")
            SystemDb(core, "Mainframe Banking", "Core customer and account store")
          }
          System_Ext(mail, "E-mail System", "Microsoft Exchange")
          Rel(customer, banking, "Views balances", "HTTPS")
          Rel(banking, core, "Reads/Writes", "JDBC")
          Rel(banking, mail, "Sends e-mail", "SMTP")
    """.trimIndent(),
    "ZenUML" to """
        zenuml
          title Order Checkout Service
          @Actor Client
          @Boundary OrderController
          @Database OrderDb
          Client->OrderController.checkout(cartId) {
            order = OrderDb.saveOrder(cartId)
            if (order != null) {
              return order
            } else {
              return null
            }
          }
    """.trimIndent(),
)

enum class SampleThemePreset(
    val displayName: String,
    val theme: MaradonaTheme,
    val colorScheme: ColorScheme,
    val usesMaterial3Connector: Boolean = false,
) {
    Light(
        displayName = "Light",
        theme = MaradonaTheme.light(),
        colorScheme = lightColorScheme(
            primary = Color(0xFF205EA6),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFF24837B),
            onSecondary = Color(0xFFFFFFFF),
            background = Color(0xFFFFFCF0),
            onBackground = Color(0xFF100F0F),
            surface = Color(0xFFF2F0E5),
            onSurface = Color(0xFF100F0F),
            surfaceVariant = Color(0xFFEAE6D8),
            onSurfaceVariant = Color(0xFF403E3C),
            surfaceContainerHigh = Color(0xFFF2F0E5),
            surfaceContainerLow = Color(0xFFEAE6D8),
            surfaceContainerHighest = Color(0xFFEAE6D8),
            outline = Color(0xFF8A8878),
            outlineVariant = Color(0xFFCECDC3),
            error = Color(0xFFB3261E),
        ),
    ),
    Dark(
        displayName = "Dark",
        theme = MaradonaTheme.dark(),
        colorScheme = darkColorScheme(
            primary = Color(0xFF4385BE),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFF3AA99F),
            onSecondary = Color(0xFF100F0F),
            background = Color(0xFF100F0F),
            onBackground = Color(0xFFCECDC3),
            surface = Color(0xFF1C1B1A),
            onSurface = Color(0xFFCECDC3),
            surfaceVariant = Color(0xFF282726),
            onSurfaceVariant = Color(0xFFB8B5A8),
            surfaceContainerHigh = Color(0xFF282726),
            surfaceContainerLow = Color(0xFF1C1B1A),
            surfaceContainerHighest = Color(0xFF343331),
            outline = Color(0xFF6F6E69),
            outlineVariant = Color(0xFF403E3C),
            error = Color(0xFFF2B8B5),
        ),
    ),
    MaterialLight(
        displayName = "M3 Light",
        theme = MaradonaTheme.light(),
        colorScheme = lightColorScheme(),
        usesMaterial3Connector = true,
    ),
    MaterialDark(
        displayName = "M3 Dark",
        theme = MaradonaTheme.dark(),
        colorScheme = darkColorScheme(),
        usesMaterial3Connector = true,
    ),
    JewelLight(
        displayName = "Jewel Light",
        theme = MaradonaTheme.jewelLight(),
        colorScheme = lightColorScheme(
            primary = Color(0xFF3574F0),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFF00796B),
            onSecondary = Color(0xFFFFFFFF),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF1E1F22),
            surface = Color(0xFFF7F8FA),
            onSurface = Color(0xFF1E1F22),
            surfaceVariant = Color(0xFFEBECF0),
            onSurfaceVariant = Color(0xFF6C707E),
            surfaceContainerHigh = Color(0xFFF7F8FA),
            surfaceContainerLow = Color(0xFFEBECF0),
            surfaceContainerHighest = Color(0xFFDFE1E5),
            outline = Color(0xFFC9CCD6),
            outlineVariant = Color(0xFFD3D5DB),
            error = Color(0xFFDB3B4B),
        ),
    ),
    JewelDark(
        displayName = "Jewel Dark",
        theme = MaradonaTheme.jewelDark(),
        colorScheme = darkColorScheme(
            primary = Color(0xFF3574F0),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFF389FD6),
            onSecondary = Color(0xFFFFFFFF),
            background = Color(0xFF1E1F22),
            onBackground = Color(0xFFBCBEC4),
            surface = Color(0xFF2B2D30),
            onSurface = Color(0xFFBCBEC4),
            surfaceVariant = Color(0xFF25262A),
            onSurfaceVariant = Color(0xFF868A91),
            surfaceContainerHigh = Color(0xFF2B2D30),
            surfaceContainerLow = Color(0xFF25262A),
            surfaceContainerHighest = Color(0xFF393B40),
            outline = Color(0xFF4E5157),
            outlineVariant = Color(0xFF43454A),
            error = Color(0xFFF75464),
        ),
    ),
}

private class SampleState(
    val themePreset: SampleThemePreset,
    val selectedExample: String?,
    val source: String,
    val onThemePresetChange: (SampleThemePreset) -> Unit,
    val onExampleSelect: (String, String) -> Unit,
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
    initialExample: String? = null,
    initialThemePreset: SampleThemePreset = SampleThemePreset.Light,
    onThemePresetChanged: ((SampleThemePreset) -> Unit)? = null,
    hostThemeWrapper: (@Composable (SampleThemePreset, @Composable () -> Unit) -> Unit)? = null,
) {
    if (oracleChart != null) {
        OracleMode(oracleChart, oracleZoom, oraclePanX, oraclePanY, oracleDensity, oracleSeq)
        return
    }
    var themePreset by remember(initialThemePreset) { mutableStateOf(initialThemePreset) }
    val resolvedInitialExample = initialExample?.takeIf { it in sampleExamples } ?: "Basic"
    val requestedSource = sampleExamples.getValue(resolvedInitialExample)
    var selectedExample by remember(initialExample) { mutableStateOf<String?>(resolvedInitialExample) }
    var source by remember(initialExample) { mutableStateOf(requestedSource) }

    LaunchedEffect(themePreset) {
        onThemePresetChanged?.invoke(themePreset)
    }

    val state = SampleState(
        themePreset = themePreset,
        selectedExample = selectedExample,
        source = source,
        onThemePresetChange = { themePreset = it },
        onExampleSelect = { name, text ->
            selectedExample = name
            source = text
        },
        onSourceChange = { newText ->
            source = newText
            selectedExample = sampleExamples.entries.firstOrNull { it.value == newText }?.key
        },
    )

    val defaultWrapper: @Composable (SampleThemePreset, @Composable () -> Unit) -> Unit = { preset, content ->
        if (preset.usesMaterial3Connector) {
            ProvideMaterial3MaradonaTheme(content = content)
        } else {
            CompositionLocalProvider(LocalMaradonaTheme provides preset.theme, content = content)
        }
    }
    val activeWrapper = hostThemeWrapper ?: defaultWrapper

    MaterialTheme(colorScheme = themePreset.colorScheme) {
        activeWrapper(themePreset) {
            Surface(
                modifier = Modifier.fillMaxSize().testTag("sample-root-surface"),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
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
        modifier = modifier,
    )
}

@Composable
private fun SelectableChipButton(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val semanticsModifier = Modifier
        .heightIn(min = 32.dp)
        .testTag(testTag)
        .semantics {
            role = Role.Tab
            this.selected = selected
            stateDescription = if (selected) "Selected" else "Not selected"
        }
    val compactPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    if (selected) {
        Button(
            onClick = onClick,
            modifier = semanticsModifier,
            contentPadding = compactPadding,
        ) {
            Text(label, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = semanticsModifier,
            contentPadding = compactPadding,
        ) {
            Text(label, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorPanel(state: SampleState, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        modifier = modifier.testTag("sample-editor-panel"),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Maradona sample",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((name, text) in sampleExamples) {
                    SelectableChipButton(
                        label = name,
                        selected = state.selectedExample == name,
                        testTag = "example-btn-$name",
                        onClick = { state.onExampleSelect(name, text) },
                    )
                }
            }
            TextField(
                value = state.source,
                onValueChange = state.onSourceChange,
                modifier = if (compact) {
                    Modifier.fillMaxWidth().height(110.dp)
                } else {
                    Modifier.weight(1f).fillMaxHeight()
                },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                label = { Text("Mermaid diagram") },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (preset in SampleThemePreset.entries) {
                    SelectableChipButton(
                        label = preset.displayName,
                        selected = state.themePreset == preset,
                        testTag = "theme-btn-${preset.name}",
                        onClick = { state.onThemePresetChange(preset) },
                    )
                }
                OutlinedButton(
                    onClick = { state.onExampleSelect("Basic", sampleExamples.getValue("Basic")) },
                    modifier = Modifier.heightIn(min = 32.dp).testTag("reset-btn"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Reset", fontSize = 12.sp)
                }
            }
            Text(
                text = "Pan by dragging. Pinch or ⌘/Ctrl+scroll to zoom. Double-tap to reset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
