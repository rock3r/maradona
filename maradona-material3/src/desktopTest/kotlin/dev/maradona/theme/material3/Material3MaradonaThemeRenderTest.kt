package dev.maradona.theme.material3

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class Material3MaradonaThemeRenderTest {

    @Test
    fun provideMaterial3MaradonaThemePropagatesLiveMaterialThemeToDiagram() {
        val customScheme = darkColorScheme(
            background = Color(0xFF12161F),
            surfaceContainerHigh = Color(0xFF1E2636),
            outline = Color(0xFF5A6B8C),
            onSurface = Color(0xFFE5ECF6),
            primary = Color(0xFF38BDF8),
        )
        var capturedTheme: MaradonaTheme? = null

        val scene = ImageComposeScene(width = 640, height = 360, density = Density(1.5f)) {
            MaterialTheme(colorScheme = customScheme) {
                ProvideMaterial3MaradonaTheme(
                    override = { it.copy(edgeLabelBackground = Color(0xFF161C28)) },
                ) {
                    capturedTheme = LocalMaradonaTheme.current
                    MaradonaDiagram(
                        source = """
                            flowchart LR
                              M3[MaterialTheme] --> Connector[ProvideMaterial3MaradonaTheme]
                              Connector --> Diagram([MaradonaDiagram])
                        """.trimIndent(),
                        modifier = Modifier.fillMaxSize(),
                        zoomEnabled = false,
                    )
                }
            }
        }
        try {
            repeat(3) { frame -> scene.render((frame + 1) * 16_000_000L) }
            val png = scene.render(80_000_000L).encodeToData(EncodedImageFormat.PNG)
            assertNotNull(png)
            val resolved = assertNotNull(capturedTheme)
            assertEquals(Color(0xFF12161F), resolved.background)
            assertEquals(Color(0xFF1E2636), resolved.nodeFill)
            assertEquals(Color(0xFF5A6B8C), resolved.nodeBorder)
            assertEquals(Color(0xFFE5ECF6), resolved.nodeText)
            assertEquals(Color(0xFF38BDF8), resolved.accentBorder)
            assertEquals(Color(0xFF161C28), resolved.edgeLabelBackground)
        } finally {
            scene.close()
        }
    }
}
