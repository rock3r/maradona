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
}
