package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.maradona.render.DiagramZoom
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/**
 * Renders every oracle chart (the .mmd files in oracle/charts) at every zoom
 * level into
 * oracle/out/desktop/<chart>-<zoom>.png, for comparison against the mermaid
 * goldens in oracle/goldens/.
 *
 * Two passes per chart: first a natural-size render in an oversized viewport
 * whose content bbox gives the diagram's natural pixel size; then each zoom
 * level gets a viewport sized so the whole zoomed diagram is captured:
 * with viewport >= natural the fit scale is 1, so Min/Max map to exactly
 * 0.1x/5x of natural size.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OracleGoldenTest {

    private val chartsDir = File("../oracle/charts")
    private val outDir = File("../oracle/out/desktop")

    private fun renderPng(source: String, zoom: DiagramZoom, width: Int, height: Int): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            MaradonaDiagram(
                source = source,
                theme = MaradonaTheme.light(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialZoom = zoom,
            )
        }
        try {
            // Several frames so parse + measure + initial-zoom settle.
            var image = scene.render(16_000_000L)
            repeat(6) { frame -> image = scene.render((frame + 2) * 16_000_000L) }
            val data = image.encodeToData(EncodedImageFormat.PNG)
            return requireNotNull(data) { "PNG encode failed" }.bytes
        } finally {
            scene.close()
        }
    }

    /** Size of the non-background content, found by scanning for pixels differing from the corner. */
    private fun contentSize(png: ByteArray): Pair<Int, Int> {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val bg = image.getRGB(0, 0)
        fun diffAt(x: Int, y: Int): Int {
            val p = image.getRGB(x, y)
            return maxOf(
                abs((p shr 16 and 0xFF) - (bg shr 16 and 0xFF)),
                abs((p shr 8 and 0xFF) - (bg shr 8 and 0xFF)),
                abs((p and 0xFF) - (bg and 0xFF)),
            )
        }
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (diffAt(x, y) > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        require(maxX >= 0) { "no content found" }
        return (maxX - minX + 1) to (maxY - minY + 1)
    }

    @Test
    fun renderOracleCharts() {
        val charts = chartsDir.listFiles { f -> f.extension == "mmd" }!!.sortedBy { it.name }
        require(charts.isNotEmpty()) { "no charts in ${chartsDir.absolutePath}" }
        outDir.mkdirs()
        for (chart in charts) {
            val source = chart.readText()
            val (natW, natH) = contentSize(renderPng(source, DiagramZoom.Natural, 3000, 3000))
            val margin = 80
            val sizes = mapOf(
                // Fixed reference viewport: exercises shrink-to-fit for big charts.
                "fit" to (700 to 500),
                "100" to (natW + margin to natH + margin),
                "min" to (natW + margin to natH + margin),
                "max" to (ceil(natW * 5f).toInt() + margin to ceil(natH * 5f).toInt() + margin),
            )
            val zoomLevels = mapOf(
                "fit" to DiagramZoom.Fit,
                "100" to DiagramZoom.Natural,
                "min" to DiagramZoom.Min,
                "max" to DiagramZoom.Max,
            )
            for ((name, zoom) in zoomLevels) {
                val (w, h) = sizes.getValue(name)
                val png = renderPng(source, zoom, w, h)
                File(outDir, "${chart.nameWithoutExtension}-$name.png").writeBytes(png)
            }
            println("oracle: ${chart.name} natural=${natW}x${natH}")
        }
    }
}
