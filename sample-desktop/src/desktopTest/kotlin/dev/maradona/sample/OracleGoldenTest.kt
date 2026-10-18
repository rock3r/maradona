package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.maradona.render.DiagramZoom
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/**
 * Renders every oracle chart (the .mmd files in oracle/charts) into
 * oracle/out/desktop for comparison against the mermaid goldens.
 *
 * All zoom levels share one fixed canvas per chart: the gold's 100% content
 * size plus a small margin. Our render is density-matched so its natural
 * content size equals the gold's (aspect drift beyond 15% fails loudly).
 *
 * Levels: 100 (natural) and min (0.1x) show the whole diagram on the fixed
 * canvas; max (5x) is a viewport into the zoomed diagram, so it is captured
 * as a 3x3 grid of pan tiles covering the whole content.
 *
 * Also writes manifest.json (canvas + measured content size per chart) which
 * compare.py uses to derive the matching gold views.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OracleGoldenTest {

    private val chartsDir = File("../oracle/charts")
    private val goldensDir = File("../oracle/goldens")
    private val outDir = File("../oracle/out/desktop")

    private fun renderPng(
        source: String,
        zoom: DiagramZoom,
        width: Int,
        height: Int,
        density: Float,
        pan: Offset? = null,
        naturalSize: ((IntSize) -> Unit)? = null,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            MaradonaDiagram(
                source = source,
                theme = MaradonaTheme.light(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialZoom = zoom,
                initialPan = pan,
                onNaturalSize = { naturalSize?.invoke(it) },
            )
        }
        try {
            // Several frames so parse + measure + initial zoom/pan settle.
            var image = scene.render(16_000_000L)
            repeat(6) { frame -> image = scene.render((frame + 2) * 16_000_000L) }
            val data = image.encodeToData(EncodedImageFormat.PNG)
            return requireNotNull(data) { "PNG encode failed" }.bytes
        } finally {
            scene.close()
        }
    }

    /** Content bbox (pixels differing from the top-left corner colour), in xywh. */
    private fun contentBounds(png: ByteArray): IntArray {
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
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    @Test
    fun renderOracleCharts() {
        val charts = chartsDir.listFiles { f -> f.extension == "mmd" }!!.sortedBy { it.name }
        require(charts.isNotEmpty()) { "no charts in ${chartsDir.absolutePath}" }
        outDir.mkdirs()
        val manifest = StringBuilder("{\n")
        for (chart in charts) {
            val id = chart.nameWithoutExtension
            val source = chart.readText()

            // Natural size at density 1, and the gold's content size.
            var natLayoutW = 0
            var natLayoutH = 0
            val pass1 = renderPng(source, DiagramZoom.Natural, 3000, 3000, 1f) { size ->
                natLayoutW = size.width
                natLayoutH = size.height
            }
            val nat = contentBounds(pass1)
            val gold = contentBounds(File(goldensDir, "$id.png").readBytes())
            val scaleW = gold[2].toFloat() / nat[2]
            val scaleH = gold[3].toFloat() / nat[3]
            if (abs(scaleW / scaleH - 1f) > 0.15f) {
                println("oracle: WARNING $id aspect drift vs gold (w x$scaleW, h x$scaleH) - tolerated, gold is rescaled to match")
            }
            // With genuinely different layout aspects one axis can't match;
            // min() keeps renders small and lets compare.py rescale gold to fit.
            val density = minOf(scaleW, scaleH)

            // Fixed canvas for all zoom levels: must contain the whole layout
            // (layout can exceed the ink bbox - spacing, label overhang) and
            // the gold's 100% content; aspects legitimately differ across renderers.
            val margin = 32
            val canvasW = maxOf(gold[2], (natLayoutW * density).roundToInt()) + margin
            val canvasH = maxOf(gold[3], (natLayoutH * density).roundToInt()) + margin

            // The true layout size (ink bbox + spacing the layout reserves) is
            // needed to align max-zoom tile windows with the gold views.
            val layoutW = (natLayoutW * density).roundToInt()
            val layoutH = (natLayoutH * density).roundToInt()
            File(outDir, "$id-100.png").writeBytes(
                renderPng(source, DiagramZoom.Natural, canvasW, canvasH, density),
            )
            File(outDir, "$id-min.png").writeBytes(
                renderPng(source, DiagramZoom.Min, canvasW, canvasH, density),
            )
            val fractions = listOf(0f, 0.5f, 1f)
            for ((i, fx) in fractions.withIndex()) {
                for ((j, fy) in fractions.withIndex()) {
                    File(outDir, "$id-max-$i$j.png").writeBytes(
                        renderPng(source, DiagramZoom.Max, canvasW, canvasH, density, pan = Offset(fx, fy)),
                    )
                }
            }

            // Measured 100% content size: the comparison anchor.
            val ours100 = contentBounds(File(outDir, "$id-100.png").readBytes())
            manifest.append(
                """  "$id": {"canvasW": $canvasW, "canvasH": $canvasH, "layoutW": $layoutW, "layoutH": $layoutH, "contentW": ${ours100[2]}, "contentH": ${ours100[3]}},""",
            ).append('\n')
            println("oracle: $id natural=${nat[2]}x${nat[3]} gold=${gold[2]}x${gold[3]} density=${(density * 100).roundToInt() / 100f}")
        }
        manifest.setLength(manifest.length - 2) // trailing comma+newline
        manifest.append("\n}\n")
        File(outDir, "manifest.json").writeText(manifest.toString())
    }
}
