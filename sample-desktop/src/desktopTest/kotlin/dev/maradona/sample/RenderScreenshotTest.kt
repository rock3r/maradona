package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders example diagrams headlessly to PNGs under build/screenshots/.
 * Doubles as a smoke test that the whole composable pipeline runs.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RenderScreenshotTest {

    private fun renderToPng(name: String, source: String, theme: MaradonaTheme, width: Int = 900, height: Int = 700) {
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
        } finally {
            scene.close()
        }
    }

    @Test
    fun basicLight() = renderToPng(
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

    @Test
    fun shapesDark() = renderToPng(
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

    @Test
    fun edgesAndCycles() = renderToPng(
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

    @Test
    fun subgraph() = renderToPng(
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
