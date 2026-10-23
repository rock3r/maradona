package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
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
                if (abs(r - 248) <= 3 && abs(g - 185) <= 3 && abs(b - 178) <= 3) critActivePixels++
            }
        }
        assertTrue(critActivePixels > 400, "Critical active Gantt bar rgb(248, 185, 178) not rendered ($critActivePixels px)")
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
                if (abs(r - 136) <= 3 && abs(g - 179) <= 3 && abs(b - 216) <= 3) cobaltSlicePixels++
            }
        }
        assertTrue(cobaltSlicePixels > 2_000, "Primary pie slice rgb(136, 179, 216) not rendered ($cobaltSlicePixels px)")
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
}
