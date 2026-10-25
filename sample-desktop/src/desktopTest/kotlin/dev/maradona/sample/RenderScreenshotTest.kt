package dev.maradona.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders example diagrams headlessly to PNGs under build/screenshots/.
 * Doubles as a smoke test that the whole composable pipeline runs.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RenderScreenshotTest {

    private fun renderToPng(name: String, source: String, theme: MaradonaTheme, width: Int = 900, height: Int = 700): File {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1.5f)) {
            MaradonaDiagram(source = source, theme = theme, modifier = Modifier.fillMaxSize(), zoomEnabled = false)
        }
        try {
            // Several frames so measurement and fit-to-size settle.
            repeat(4) { frame -> scene.render((frame + 1) * 16_000_000L) }
            val data = scene.render(100_000_000L).encodeToData(EncodedImageFormat.PNG)
            requireNotNull(data) { "PNG encode failed for $name" }
            val out = File("build/screenshots/$name.png")
            out.parentFile.mkdirs()
            out.writeBytes(data.bytes)
            assertTrue(out.length() > 5_000, "Screenshot $name suspiciously small: ${out.length()} bytes")
            return out
        } finally {
            scene.close()
        }
    }

    @Test
    fun basicLight() {
        renderToPng(
            "basic-light",
            """
            flowchart TD
              A[Mermaid text] --> B{Flowchart?}
              B -- Yes --> C[Maradona]
              B -- No --> D[Pele]
              C --> E([Composables])
              D --> E
            """.trimIndent(),
            MaradonaTheme.light(),
        )
    }

    @Test
    fun shapesDark() {
        renderToPng(
            "shapes-dark",
            """
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
            MaradonaTheme.dark(),
            width = 1400,
            height = 400,
        )
    }

    @Test
    fun edgesAndCycles() {
        renderToPng(
            "edges",
            """
            flowchart TD
              A[Start] ==> B[Thick]
              B -. dotted .-> C{Dotted?}
              C -->|pipe label| D[Cross]
              C --o E[Circle head]
              D --x F[End]
              E --- F
              F --> A
            """.trimIndent(),
            MaradonaTheme.light(),
        )
    }

    @Test
    fun subgraph() {
        renderToPng(
            "subgraph",
            """
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
            MaradonaTheme.light(),
        )
    }

    @Test
    fun sequenceDiagram() {
        val png = renderToPng(
            "sequence",
            """
            ---
            title: Order Checkout Flow
            ---
            sequenceDiagram
              autonumber
              actor User as Mobile User<br/>(iOS & Android)
              participant UI as Compose UI
              participant VM as ViewModel
              participant API as Backend API
              User->>UI: Tap checkout
              UI->>+VM: Submit cart
              rect rgb(234, 230, 216)
                loop Retry up to 3 times
                  VM->>+API: POST /orders
                  alt Payment authorized
                    rect rgb(211, 229, 245)
                      API-->>-VM: 201 Created
                    end
                  else Gateway timeout or transient 5xx error<br/>requiring exponential backoff
                    API--xVM: 504 Gateway Timeout
                  end
                end
              end
              VM->>VM: Validate receipt
              VM-->>-UI: Order confirmed
              Note left of UI: State updated
              Note over User,UI: Pan & pinch-zoom active
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1150,
            height = 950,
        )
        // Verify both nested rect background colors are visible in the final rendered pixels
        // (guards against outer rect painting over inner rect).
        val img = ImageIO.read(png)
        var outerRectPixels = 0
        var innerRectPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 234) <= 3 && abs(g - 230) <= 3 && abs(b - 216) <= 3) outerRectPixels++
                if (abs(r - 211) <= 3 && abs(g - 229) <= 3 && abs(b - 245) <= 3) innerRectPixels++
            }
        }
        assertTrue(outerRectPixels > 2_000, "Outer rect rgb(234, 230, 216) not visible ($outerRectPixels px)")
        assertTrue(innerRectPixels > 2_000, "Inner rect rgb(211, 229, 245) occluded ($innerRectPixels px)")
    }

    @Test
    fun classDiagram() {
        val png = renderToPng(
            "class",
            """
            ---
            title: Order Domain Model
            ---
            classDiagram
              namespace Commerce {
                class Customer {
                  +String id
                  +String email
                  +placeOrder(Cart cart) Order
                }
                class Order {
                  +String id
                  +Date placedAt
                  +total() Money
                }
              }
              class LineItem {
                +int quantity
                +subtotal() Money
              }
              class PaymentGateway~T~ {
                <<interface>>
                +authorize(Money amount)* T
              }
              note for Order "Validated before settlement"
              Customer "1" --> "*" Order : places
              Order "1" *-- "1..*" LineItem : contains
              Order ..|> PaymentGateway : settled by
              style Order fill:#d3e5f5,stroke:#205ea6
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1100,
            height = 850,
        )
        // Verify styled Order fill (#d3e5f5 = rgb(211, 229, 245)) and sticky note fill (#fff5ad = rgb(255, 245, 173))
        // are both visibly rendered in class.png.
        val img = ImageIO.read(png)
        var orderFillPixels = 0
        var noteFillPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 211) <= 3 && abs(g - 229) <= 3 && abs(b - 245) <= 3) orderFillPixels++
                if (abs(r - 255) <= 3 && abs(g - 245) <= 3 && abs(b - 173) <= 3) noteFillPixels++
            }
        }
        assertTrue(orderFillPixels > 2_000, "Styled Order box rgb(211, 229, 245) not rendered ($orderFillPixels px)")
        assertTrue(noteFillPixels > 1_000, "Class note rgb(255, 245, 173) not rendered ($noteFillPixels px)")
    }

    @Test
    fun stateDiagram() {
        val png = renderToPng(
            "state",
            """
            ---
            title: Checkout State Machine
            ---
            stateDiagram-v2
              [*] --> Idle
              Idle : Waiting for cart
              Idle --> Processing : submit
              state "Order Processing" as Processing {
                [*] --> Validating
                Validating : Check inventory
                Validating --> Charging : valid
                Charging --> [*] : captured
                --
                [*] --> Auditing
                Auditing --> [*] : logged
              }
              state Decision <<choice>>
              Processing --> Decision : settled
              Decision --> Fulfilled : [approved]
              Decision --> Declined : [rejected]
              Fulfilled --> [*]
              Declined --> [*]
              note right of Processing : Concurrent payment & audit
              style Fulfilled fill:#d3e5f5,stroke:#205ea6
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1100,
            height = 900,
        )
        val img = ImageIO.read(png)
        var fulfilledFillPixels = 0
        var noteFillPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 211) <= 3 && abs(g - 229) <= 3 && abs(b - 245) <= 3) fulfilledFillPixels++
                if (abs(r - 255) <= 3 && abs(g - 245) <= 3 && abs(b - 173) <= 3) noteFillPixels++
            }
        }
        assertTrue(fulfilledFillPixels > 1_000, "Styled Fulfilled state rgb(211, 229, 245) not rendered ($fulfilledFillPixels px)")
        assertTrue(noteFillPixels > 1_000, "State note rgb(255, 245, 173) not rendered ($noteFillPixels px)")
    }

    @Test
    fun erDiagram() {
        val png = renderToPng(
            "er",
            """
            ---
            title: Commerce Entity Relationship Model
            ---
            erDiagram
              subgraph commerce [Commerce Domain]
                CUSTOMER ||--o{ ORDER : places
                ORDER ||--|{ LINE_ITEM : contains
                CUSTOMER {
                  string id PK "Customer UUID"
                  string name
                  string email UK
                }
                ORDER {
                  string id PK
                  string customerId FK
                  date placedAt
                }
                LINE_ITEM {
                  string orderId PK, FK
                  string productSku PK, FK
                  int quantity
                  float pricePerUnit
                }
              }
              PRODUCT ||--o{ LINE_ITEM : "appears in"
              PRODUCT {
                string sku PK
                string title
                float unitPrice
              }
              style ORDER fill:#d3e5f5,stroke:#205ea6
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1100,
            height = 900,
        )
        val img = ImageIO.read(png)
        var orderFillPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 211) <= 3 && abs(g - 229) <= 3 && abs(b - 245) <= 3) orderFillPixels++
            }
        }
        assertTrue(orderFillPixels > 1_500, "Styled ORDER entity rgb(211, 229, 245) not rendered ($orderFillPixels px)")
    }

    @Test
    fun mindmapDiagram() {
        val png = renderToPng(
            "mindmap",
            """
            ---
            title: Maradona Architecture Mindmap
            ---
            mindmap
              root((Maradona))
                Diagrams
                  Flowcharts[Flowcharts]
                  Sequence(Sequence)
                  Class[Class Diagrams]
                  State(State Diagrams)
                  ER{{ER Diagrams}}
                Engine
                  Parser[Hand-written Parsers]
                  Layout)Sugiyama & Tidy Tree(
                  Render))Compose Multiplatform((
                Targets
                  Desktop[Desktop JVM]
                  :::urgent
                  Android(Android)
                  iOS(iOS arm64)
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1150,
            height = 750,
        )
        val img = ImageIO.read(png)
        var urgentFillPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 255) <= 3 && abs(g - 235) <= 3 && abs(b - 233) <= 3) urgentFillPixels++
            }
        }
        assertTrue(urgentFillPixels > 500, "Urgent mindmap node rgb(255, 235, 233) not rendered ($urgentFillPixels px)")
    }

    @Test
    fun ganttDiagram() {
        val png = renderToPng(
            "gantt",
            """
            gantt
                title Maradona Release Roadmap
                dateFormat YYYY-MM-DD
                excludes weekends

                section Core Engine
                Flowchart & DagrePort     :done,         fc, 2026-10-01, 5d
                Sequence & Class Layouts  :done,         sc, after fc,   4d

                section Extended Families
                State & ER Diagrams       :active,       se, after sc,   4d
                Mindmap & Gantt           :crit, active, mg, after se,   3d

                section Delivery
                Oracle Visual Parity      :              ov, after mg,   2d
                v0.7 Release              :milestone,    m1, after ov,   0d
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1150,
            height = 520,
        )
        val img = ImageIO.read(png)
        var critActivePixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 244) <= 6 && abs(g - 196) <= 6 && abs(b - 193) <= 6) critActivePixels++
            }
        }
        assertTrue(critActivePixels > 400, "Critical active Gantt bar not rendered ($critActivePixels px)")
    }

    @Test
    fun pieDiagram() {
        val png = renderToPng(
            "pie",
            """
            ---
            config:
              pie:
                textPosition: 0.55
                donutHole: 0.25
                highlightSlice: Flowcharts
              themeVariables:
                pieOuterStrokeWidth: "2px"
            ---
            pie showData
                title Maradona Diagram Usage
                "Flowcharts" : 42.5
                "Sequence" : 24
                "Class & ER" : 17.5
                "State & Gantt" : 10
                "Mindmap & Pie" : 6
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 820,
            height = 460,
        )
        val img = ImageIO.read(png)
        var cobaltSlicePixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 149) <= 5 && abs(g - 184) <= 5 && abs(b - 219) <= 5) cobaltSlicePixels++
            }
        }
        assertTrue(cobaltSlicePixels > 2_000, "Primary pie slice not rendered ($cobaltSlicePixels px)")
    }

    @Test
    fun gitGraphDiagram() {
        val png = renderToPng(
            "gitgraph",
            """
            ---
            title: Maradona Branch & Release History
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
            MaradonaTheme.light(),
            width = 1150,
            height = 460,
        )
        val img = ImageIO.read(png)
        var tagBadgePixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 249) <= 3 && abs(g - 240) <= 3 && abs(b - 210) <= 3) tagBadgePixels++
            }
        }
        assertTrue(tagBadgePixels > 300, "GitGraph tag badge rgb(249, 240, 210) not rendered ($tagBadgePixels px)")
    }

    @Test
    fun timelineDiagram() {
        val png = renderToPng(
            "timeline",
            """
            timeline
                title Evolution of Maradona
                section Foundation
                    v0.1 : Flowchart Parser
                         : Dagre Layout Port
                    v0.3 : Sequence Diagrams : Oracle Viewer
                section Relational & Trees
                    v0.5 : Class & State Diagrams
                         : ER Crow's-Foot Tables
                    v0.7 : Bidirectional Mindmaps
                section Timelines & Charts
                    v0.9 : Gantt Schedules : Donut Pie Charts
                    v1.0 : GitGraph & Timeline
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1200,
            height = 460,
        )
        val img = ImageIO.read(png)
        var sectionBannerPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 199) <= 3 && abs(g - 223) <= 3 && abs(b - 242) <= 3) sectionBannerPixels++
            }
        }
        assertTrue(sectionBannerPixels > 1_500, "Timeline section banner rgb(199, 223, 242) not rendered ($sectionBannerPixels px)")
    }

    @Test
    fun journeyDiagram() {
        val png = renderToPng(
            "journey",
            """
            journey
                title Adopting Maradona in a Compose App
                section Evaluate
                  Browse Pele examples: 5: Developer
                  Try WebView fallback: 1: Developer, QA
                section Integrate
                  Add maradona dependency: 5: Developer
                  Render live Mermaid: 5: Developer, Designer
                section Ship
                  Run Desktop & iOS checks: 3: Developer, QA
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1150,
            height = 440,
        )
        val img = ImageIO.read(png)
        var facePixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 255) <= 4 && abs(g - 245) <= 4 && abs(b - 173) <= 4) facePixels++
            }
        }
        assertTrue(facePixels > 500, "Journey mood face not rendered ($facePixels px)")
    }

    @Test
    fun quadrantChartDiagram() {
        val png = renderToPng(
            "quadrant",
            """
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
                Campaign C:::star: [0.76, 0.72]
                Campaign D: [0.78, 0.34]
                Campaign E: [0.35, 0.78]
                classDef star color: #d14d41, radius: 9, stroke-color: #af3029, stroke-width: 2px
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 760,
            height = 680,
        )
        val img = ImageIO.read(png)
        var q1Pixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 222) <= 5 && abs(g - 235) <= 5 && abs(b - 241) <= 5) q1Pixels++
            }
        }
        assertTrue(q1Pixels > 5_000, "Quadrant Q1 fill not rendered ($q1Pixels px)")
    }

    @Test
    fun xyChartDiagram() {
        val png = renderToPng(
            "xychart",
            """
            xychart-beta
                title "Monthly Revenue & Target"
                x-axis "Month" [Jan, Feb, Mar, Apr, May, Jun]
                y-axis "Revenue (${'$'}k)" 0 --> 120
                bar "Actual" [52, 68, 74, 91, 86, 108]
                line "Target" [50, 60, 75, 85, 95 "Q2 Goal", 110]
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 820,
            height = 540,
        )
        val img = ImageIO.read(png)
        var barBluePixels = 0
        var lineOrangePixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0x20) <= 4 && abs(g - 0x5E) <= 4 && abs(b - 0xA6) <= 4) barBluePixels++
                if (abs(r - 0xDA) <= 4 && abs(g - 0x70) <= 4 && abs(b - 0x2C) <= 4) lineOrangePixels++
            }
        }
        assertTrue(barBluePixels > 3_000, "XY chart bar fill #205EA6 not rendered ($barBluePixels px)")
        assertTrue(lineOrangePixels > 150, "XY chart line stroke #DA702C not rendered ($lineOrangePixels px)")
    }

    @Test
    fun kanbanDiagram() {
        val png = renderToPng(
            "kanban",
            """
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
            MaradonaTheme.light(),
            width = 860,
            height = 420,
        )
        val img = ImageIO.read(png)
        var colBlueTintPixels = 0
        var priorityRedPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 224) <= 5 && abs(g - 236) <= 5 && abs(b - 241) <= 5) colBlueTintPixels++
                if (abs(r - 0xD1) <= 4 && abs(g - 0x4D) <= 4 && abs(b - 0x41) <= 4) priorityRedPixels++
            }
        }
        assertTrue(colBlueTintPixels > 2_000, "Kanban column fill not rendered ($colBlueTintPixels px)")
        assertTrue(priorityRedPixels > 40, "Kanban Very High priority stripe #D14D41 not rendered ($priorityRedPixels px)")
    }

    @Test
    fun sankeyDiagram() {
        val png = renderToPng(
            "sankey",
            """
            sankey-beta
              Solar,Electricity grid,120
              Wind,Electricity grid,290
              Hydro,Electricity grid,90
              Electricity grid,Industry,240
              Electricity grid,"Heating & cooling, homes",180
              Electricity grid,Losses,80
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 840,
            height = 480,
        )
        val img = ImageIO.read(png)
        var nodeBluePixels = 0
        var nodeOrangePixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0x20) <= 4 && abs(g - 0x5E) <= 4 && abs(b - 0xA6) <= 4) nodeBluePixels++
                if (abs(r - 0xDA) <= 4 && abs(g - 0x70) <= 4 && abs(b - 0x2C) <= 4) nodeOrangePixels++
            }
        }
        assertTrue(nodeBluePixels > 200, "Sankey node fill #205EA6 not rendered ($nodeBluePixels px)")
        assertTrue(nodeOrangePixels > 400, "Sankey node fill #DA702C not rendered ($nodeOrangePixels px)")
    }

    @Test
    fun requirementDiagram() {
        val png = renderToPng(
            "requirement",
            """
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
            MaradonaTheme.light(),
            width = 780,
            height = 480,
        )
        val img = ImageIO.read(png)
        var reqHeaderBluePixels = 0
        var elemHeaderMintPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0xDB) <= 4 && abs(g - 0xEA) <= 4 && abs(b - 0xFE) <= 4) reqHeaderBluePixels++
                if (abs(r - 0xE8) <= 3 && abs(g - 0xF4) <= 3 && abs(b - 0xEE) <= 3) elemHeaderMintPixels++
            }
        }
        assertTrue(reqHeaderBluePixels > 2_000, "Requirement header fill #DBEAFE not rendered ($reqHeaderBluePixels px)")
        assertTrue(elemHeaderMintPixels > 1_000, "Element header fill #E8F4EE not rendered ($elemHeaderMintPixels px)")
    }

    @Test
    fun packetDiagram() {
        val png = renderToPng(
            "packet",
            """
            ---
            title: "TCP Packet Header"
            ---
            packet-beta
              0-15: "Source Port"
              16-31: "Destination Port"
              32-63: "Sequence Number"
              64-95: "Acknowledgment Number"
              96-99: "Data Offset"
              100-105: "Reserved"
              106: "URG"
              107: "ACK"
              108: "PSH"
              109: "RST"
              110: "SYN"
              111: "FIN"
              112-127: "Window"
              128-143: "Checksum"
              144-159: "Urgent Pointer"
              160-191: "(Options and Padding)"
              192-255: "Data (variable length)"
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 1120,
            height = 540,
        )
        val img = ImageIO.read(png)
        var multiBitFillPixels = 0
        var singleBitFillPixels = 0
        var borderPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0xF2) <= 3 && abs(g - 0xF0) <= 3 && abs(b - 0xE5) <= 3) multiBitFillPixels++
                if (abs(r - 0xEA) <= 3 && abs(g - 0xE6) <= 3 && abs(b - 0xD8) <= 3) singleBitFillPixels++
                if (abs(r - 0x8A) <= 4 && abs(g - 0x88) <= 4 && abs(b - 0x78) <= 4) borderPixels++
            }
        }
        assertTrue(multiBitFillPixels > 50_000, "Packet multi-bit block fill not rendered ($multiBitFillPixels px)")
        assertTrue(singleBitFillPixels > 1_000, "Packet 1-bit flag block fill not rendered ($singleBitFillPixels px)")
        assertTrue(borderPixels > 2_000, "Packet block borders not rendered ($borderPixels px)")
    }

    @Test
    fun radarDiagram() {
        val png = renderToPng(
            "radar",
            """
            radar-beta
              title "Academic Performance Comparison"
              axis m["Math"], s["Science"], e["English"]
              axis h["History"], g["Geography"], a["Art"]
              curve a["Alice"]{85, 90, 80, 70, 75, 90}
              curve b["Bob"]{70, 75, 85, 80, 90, 85}
              graticule polygon
              max 100
              min 0
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 760,
            height = 540,
        )
        val img = ImageIO.read(png)
        var blueCurvePixels = 0
        var orangeCurvePixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0x20) <= 5 && abs(g - 0x5E) <= 5 && abs(b - 0xA6) <= 5) blueCurvePixels++
                if (abs(r - 0xDA) <= 5 && abs(g - 0x70) <= 5 && abs(b - 0x2C) <= 5) orangeCurvePixels++
            }
        }
        assertTrue(blueCurvePixels > 400, "Radar first curve stroke #205EA6 not rendered ($blueCurvePixels px)")
        assertTrue(orangeCurvePixels > 400, "Radar second curve stroke #DA702C not rendered ($orangeCurvePixels px)")
    }

    @Test
    fun blockDiagram() {
        val png = renderToPng(
            "block",
            """
            block-beta
              columns 3
              Frontend["Frontend Web"] arrow1<["HTTPS"]>(right) Backend["API Gateway"]
              space:2 arrow2<["SQL"]>(down)
              block:storage:2
                columns 2
                Cache(["Redis Cache"]) Queue[["Event Queue"]]
              end
              Database[("Orders DB")]
              storage --> Database
              classDef primary fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
              classDef dbStyle fill:#dcfce7,stroke:#16a34a,color:#14532d
              class Frontend,Backend primary
              class Database dbStyle
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 780,
            height = 460,
        )
        val img = ImageIO.read(png)
        var primaryBluePixels = 0
        var dbGreenPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0xDB) <= 3 && abs(g - 0xEA) <= 3 && abs(b - 0xFE) <= 3) primaryBluePixels++
                if (abs(r - 0xDC) <= 3 && abs(g - 0xFC) <= 3 && abs(b - 0xE7) <= 3) dbGreenPixels++
            }
        }
        assertTrue(primaryBluePixels > 2_000, "Block primary class fill #DBEAFE not rendered ($primaryBluePixels px)")
        assertTrue(dbGreenPixels > 1_000, "Block database class fill #DCFCE7 not rendered ($dbGreenPixels px)")
    }

    @Test
    fun architectureDiagram() {
        val png = renderToPng(
            "architecture",
            """
            architecture-beta
              group api(cloud)[Cloud API]
              service gateway(internet)[Gateway] in api
              service app(server)[App Server] in api
              service db(database)[Primary DB] in api
              service storage(disk)[Storage] in api
              gateway:R --> L:app
              app:R --> L:db
              app:B --> T:storage
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 780,
            height = 480,
        )
        val img = ImageIO.read(png)
        var iconTileBluePixels = 0
        var whiteIconStrokePixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0x08) <= 4 && abs(g - 0x7E) <= 4 && abs(b - 0xBF) <= 4) iconTileBluePixels++
                if (r >= 250 && g >= 250 && b >= 250) whiteIconStrokePixels++
            }
        }
        assertTrue(iconTileBluePixels > 4_000, "Architecture icon tile #087EBF not rendered ($iconTileBluePixels px)")
        assertTrue(whiteIconStrokePixels > 300, "Architecture white vector icons not rendered ($whiteIconStrokePixels px)")
    }

    @Test
    fun treemapDiagram() {
        val png = renderToPng(
            "treemap",
            """
            treemap-beta
            title Engineering Budget Allocation
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
            MaradonaTheme.light(),
            width = 760,
            height = 460,
        )
        val img = ImageIO.read(png)
        var sectionBlueBorderPixels = 0
        var sectionOrangeBorderPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0x20) <= 8 && abs(g - 0x5E) <= 8 && abs(b - 0xA6) <= 8) sectionBlueBorderPixels++
                if (abs(r - 0xDA) <= 8 && abs(g - 0x70) <= 8 && abs(b - 0x2C) <= 8) sectionOrangeBorderPixels++
            }
        }
        assertTrue(sectionBlueBorderPixels > 200, "Treemap blue section border #205EA6 not rendered ($sectionBlueBorderPixels px)")
        assertTrue(sectionOrangeBorderPixels > 100, "Treemap orange section border #DA702C not rendered ($sectionOrangeBorderPixels px)")
    }

    @Test
    fun c4Diagram() {
        val png = renderToPng(
            "c4",
            """
            C4Context
              title Internet Banking System Context
              Person(customer, "Banking Customer", "A customer of the bank")
              Enterprise_Boundary(bank, "BigBank plc") {
                System(banking, "Internet Banking", "Provides account balances and payments")
                SystemDb(core, "Mainframe Banking", "Stores core customer accounts")
              }
              System_Ext(mail, "E-mail System", "Microsoft Exchange")
              Rel(customer, banking, "Views balances", "HTTPS")
              Rel(banking, core, "Reads/Writes", "JDBC")
              Rel(banking, mail, "Sends e-mail", "SMTP")
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 860,
            height = 560,
        )
        val img = ImageIO.read(png)
        var personNavyPixels = 0
        var systemBluePixels = 0
        var externalGreyPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0x08) <= 4 && abs(g - 0x42) <= 4 && abs(b - 0x7B) <= 4) personNavyPixels++
                if (abs(r - 0x11) <= 4 && abs(g - 0x68) <= 4 && abs(b - 0xBD) <= 4) systemBluePixels++
                if (abs(r - 0x99) <= 4 && abs(g - 0x99) <= 4 && abs(b - 0x99) <= 4) externalGreyPixels++
            }
        }
        assertTrue(personNavyPixels > 1_500, "C4 Person navy fill #08427B not rendered ($personNavyPixels px)")
        assertTrue(systemBluePixels > 3_000, "C4 System royal blue fill #1168BD not rendered ($systemBluePixels px)")
        assertTrue(externalGreyPixels > 1_500, "C4 System_Ext grey fill #999999 not rendered ($externalGreyPixels px)")
    }

    @Test
    fun zenUmlDiagram() {
        val png = renderToPng(
            "zenuml",
            """
            zenuml
              title Order Checkout Flow
              @Actor Client
              @Boundary OrderController
              @Database OrderDb
              Client->OrderController.checkout(cartId) {
                order = OrderDb.saveOrder(cartId)
                if (order != null) {
                  return order
                } else {
                  return error
                }
              }
            """.trimIndent(),
            MaradonaTheme.light(),
            width = 760,
            height = 480,
        )
        val img = ImageIO.read(png)
        var activationBluePixels = 0
        var primaryCardFillPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0xDB) <= 4 && abs(g - 0xEA) <= 4 && abs(b - 0xFE) <= 4) activationBluePixels++
                if (abs(r - 0xF2) <= 4 && abs(g - 0xF0) <= 4 && abs(b - 0xE5) <= 4) primaryCardFillPixels++
            }
        }
        assertTrue(activationBluePixels > 400, "ZenUML activation bar #DBEAFE not rendered ($activationBluePixels px)")
        assertTrue(primaryCardFillPixels > 1_500, "ZenUML participant header card fill not rendered ($primaryCardFillPixels px)")
    }

    @Test
    fun jewelDarkAndLightIntegration() {
        val jewelDark = MaradonaTheme.jewelDark()
        val jewelLight = MaradonaTheme.jewelLight()
        val scene = ImageComposeScene(width = 1280, height = 760, density = Density(1.5f)) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left half: Jewel Dark (IntUiDark) provided via LocalMaradonaTheme
                CompositionLocalProvider(LocalMaradonaTheme provides jewelDark) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(jewelDark.background)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MaradonaDiagram(
                            source = """
                                sequenceDiagram
                                  participant IDE as Jewel Toolwindow
                                  participant M as MaradonaDiagram
                                  IDE->>+M: Provide LocalMaradonaTheme
                                  Note right of M: IntUiDark #1E1F22 / #3574F0
                                  M-->>-IDE: Native Compose Canvas
                            """.trimIndent(),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            zoomEnabled = false,
                        )
                        MaradonaDiagram(
                            source = """
                                architecture-beta
                                  group ide(cloud)[IntelliJ Platform]
                                  service plugin(server)[Plugin Host] in ide
                                  service cache(database)[Index Cache] in ide
                                  plugin:R --> L:cache
                            """.trimIndent(),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            zoomEnabled = false,
                        )
                    }
                }
                // Right half: Jewel Light (IntUiLight) provided via LocalMaradonaTheme
                CompositionLocalProvider(LocalMaradonaTheme provides jewelLight) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(jewelLight.background)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MaradonaDiagram(
                            source = """
                                C4Context
                                  title Jewel Light C4 Integration
                                  Person(dev, "Plugin Author", "Embeds Maradona in Jewel")
                                  System(maradona, "Maradona", "Host-themed Mermaid renderer")
                                  System_Ext(ij, "IntelliJ SDK", "Jewel IntUi tokens")
                                  Rel(dev, maradona, "Provides LocalMaradonaTheme")
                                  Rel(maradona, ij, "Matches IDE palette")
                            """.trimIndent(),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            zoomEnabled = false,
                        )
                        MaradonaDiagram(
                            source = """
                                gitGraph
                                  commit id: "init" tag: "v1.0"
                                  branch jewel-theme
                                  commit id: "tokens" type: HIGHLIGHT
                                  checkout main
                                  merge jewel-theme id: "ship"
                            """.trimIndent(),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            zoomEnabled = false,
                        )
                    }
                }
            }
        }
        val out = File("build/screenshots/jewel-integration.png")
        try {
            repeat(4) { frame -> scene.render((frame + 1) * 16_000_000L) }
            val data = scene.render(100_000_000L).encodeToData(EncodedImageFormat.PNG)
            requireNotNull(data)
            out.parentFile.mkdirs()
            out.writeBytes(data.bytes)
        } finally {
            scene.close()
        }
        val img = ImageIO.read(out)
        var jewelDarkBgPixels = 0
        var jewelDarkCardPixels = 0
        var jewelBlueAccentPixels = 0
        var jewelLightBgPixels = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - 0x1E) <= 3 && abs(g - 0x1F) <= 3 && abs(b - 0x22) <= 3) jewelDarkBgPixels++
                if (abs(r - 0x2B) <= 3 && abs(g - 0x2D) <= 3 && abs(b - 0x30) <= 3) jewelDarkCardPixels++
                if (abs(r - 0x35) <= 5 && abs(g - 0x74) <= 5 && abs(b - 0xF0) <= 5) jewelBlueAccentPixels++
                if (r >= 252 && g >= 252 && b >= 252) jewelLightBgPixels++
            }
        }
        assertTrue(jewelDarkBgPixels > 50_000, "Jewel Dark background #1E1F22 not rendered ($jewelDarkBgPixels px)")
        assertTrue(jewelDarkCardPixels > 1_000, "Jewel Dark surface #2B2D30 not rendered ($jewelDarkCardPixels px)")
        assertTrue(jewelBlueAccentPixels > 2_000, "Jewel accent #3574F0 not rendered ($jewelBlueAccentPixels px)")
        assertTrue(jewelLightBgPixels > 50_000, "Jewel Light background #FFFFFF not rendered ($jewelLightBgPixels px)")
    }
}
