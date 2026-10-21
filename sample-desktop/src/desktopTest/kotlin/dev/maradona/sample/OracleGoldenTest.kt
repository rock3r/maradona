package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.maradona.layout.DiagramLayout
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/**
 * Renders every oracle chart (the .mmd files in oracle/charts) into
 * oracle/out/desktop.
 *
 * The renders are framed exactly like the device captures, so compare.py has
 * one comparison path for every platform:
 *
 *  * the render density is chosen so our content comes out at the golden's
 *    pixel size (the device app does the same),
 *  * the viewport is a fixed canvas per chart (the device's is the screen),
 *  * each level renders at an absolute scale (`initialScale`): 1x, 0.1x, 5x,
 *  * levels too big for the viewport are captured as pan tiles.
 *
 * Levels: 100 (1x, compared against the golden), min (0.1x, also against the
 * golden), max (5x, a 3x3 pan sample checked for scale consistency, since
 * mermaid cannot produce a meaningful 5x reference).
 *
 * Writes manifest.json (canvas, viewport and the pan fractions of every tile)
 * which compare.py replays to crop the matching view out of the golden.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OracleGoldenTest {

    /**
     * The same font the goldens are rendered with (see oracle/tools/golden.css),
     * so node boxes measured by the layout oracle are comparable.
     */
    private val oracleFontFamily: FontFamily by lazy {
        val typeface = requireNotNull(
            org.jetbrains.skia.FontMgr.default.makeFromFile(
                File("../oracle/fonts/Roboto-Regular.ttf").absolutePath,
            ),
        ) { "could not load oracle/fonts/Roboto-Regular.ttf" }
        FontFamily(androidx.compose.ui.text.platform.Typeface(typeface))
    }

    private val chartsDir = File("../oracle/charts")
    private val goldensDir = File("../oracle/goldens")
    private val outDir = File("../oracle/out/desktop")

    private fun renderPng(
        source: String,
        scale: Float,
        width: Int,
        height: Int,
        density: Float,
        pan: Offset? = null,
        naturalSize: ((IntSize) -> Unit)? = null,
        layout: ((DiagramLayout) -> Unit)? = null,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            MaradonaDiagram(
                source = source,
                theme = MaradonaTheme.light().copy(fontFamily = oracleFontFamily),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                // Mermaid does not re-flow a horizontal chart into a vertical one
                // when the viewport is narrow, and the oracle compares against
                // mermaid: keep the layout identical at every zoom level.
                autoDirection = false,
                initialScale = scale,
                initialPan = pan,
                onNaturalSize = { naturalSize?.invoke(it) },
                onLayout = { layout?.invoke(it) },
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
                kotlin.math.abs((p shr 16 and 0xFF) - (bg shr 16 and 0xFF)),
                kotlin.math.abs((p shr 8 and 0xFF) - (bg shr 8 and 0xFF)),
                kotlin.math.abs((p and 0xFF) - (bg and 0xFF)),
            )
        }
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                // Same threshold the oracle uses to measure the golden's ink,
                // so the density calibration compares like with like.
                if (diffAt(x, y) > 28) {
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

    /**
     * Maradona's intermediate representation, as the layout oracle compares it
     * against mermaid's SVG geometry: px at the oracle density, so the two are
     * directly comparable once the density-matched content sizes are aligned.
     */
    private fun layoutJson(layout: DiagramLayout, density: Float): String {
        fun rect(r: dev.maradona.layout.DiagramRect) =
            "[${r.x}, ${r.y}, ${r.width}, ${r.height}]"
        val nodes = layout.nodes.joinToString(", ") { positioned ->
            """{"id": "${positioned.node.id}", "label": "${positioned.node.label.replace("\"", "\\\"")}", "rect": ${rect(positioned.rect)}}"""
        }
        val edges = layout.edges.joinToString(", ") { positioned ->
            val points = positioned.points.joinToString(", ") { "[${it.x}, ${it.y}]" }
            val label = positioned.labelRect?.let { """[${it.x}, ${it.y}, ${it.width}, ${it.height}]""" } ?: "null"
            """{"from": "${positioned.edge.from}", "to": "${positioned.edge.to}", "label": ${positioned.edge.label?.let { "\"$it\"" } ?: "null"}, "points": [$points], "label_rect": $label}"""
        }
        val subgraphs = layout.subgraphs.joinToString(", ") { positioned ->
            """{"id": "${positioned.subgraph.id}", "label": "${positioned.subgraph.label}", "rect": ${rect(positioned.rect)}}"""
        }
        return """
            {
              "width": ${layout.width},
              "height": ${layout.height},
              "direction": "${layout.direction}",
              "density": $density,
              "nodes": [$nodes],
              "edges": [$edges],
              "subgraphs": [$subgraphs]
            }
        """.trimIndent() + "\n"
    }

    /** Pan fractions that cover `scaled` px in `view` px windows without gaps. */
    private fun coverFractions(scaled: Int, view: Int): List<Float> {
        val n = if (scaled <= view) 1 else maxOf(2, kotlin.math.ceil(scaled / view.toDouble()).toInt())
        return if (n == 1) listOf(0.5f) else (0 until n).map { it.toFloat() / (n - 1) }
    }

    @Test
    fun renderOracleCharts() {
        val charts = chartsDir.listFiles { f -> f.extension == "mmd" }!!.sortedBy { it.name }
        require(charts.isNotEmpty()) { "no charts in ${chartsDir.absolutePath}" }
        // Start clean: a stale render from a previous run must never be left for
        // the comparison scripts to measure.
        outDir.deleteRecursively()
        outDir.mkdirs()
        val manifest = StringBuilder("{\n  \"charts\": {\n")
        for (chart in charts) {
            val id = chart.nameWithoutExtension
            val source = chart.readText()

            // Natural layout size at density 1. The layout is unbounded, so a
            // modest scene is enough: it measures its full size regardless.
            var natW = 0
            var natH = 0
            renderPng(source, 1f, 400, 400, 1f, naturalSize = { size ->
                natW = size.width
                natH = size.height
            })
            require(natW > 0 && natH > 0) { "$id has no measurable layout" }
            val gold = contentBounds(File(goldensDir, "$id.png").readBytes())

            // The device app picks its density from the layout box, which
            // carries padding the golden's ink does not; measure the real ink
            // once and correct, exactly like the capture scripts do.
            val roughDensity = minOf(gold[2].toFloat() / natW, gold[3].toFloat() / natH)
            val roughW = (natW * roughDensity).roundToInt()
            val roughH = (natH * roughDensity).roundToInt()
            val roughInk = contentBounds(renderPng(source, 1f, roughW, roughH, roughDensity))
            val density = roughDensity * minOf(
                gold[2].toFloat() / roughInk[2],
                gold[3].toFloat() / roughInk[3],
            )
            val layoutW = (natW * density).roundToInt()
            val layoutH = (natH * density).roundToInt()
            val margin = 32
            val canvasW = maxOf(gold[2], layoutW) + margin
            val canvasH = maxOf(gold[3], layoutH) + margin

            var layoutDump: DiagramLayout? = null
            File(outDir, "$id-100.png").writeBytes(
                renderPng(source, 1f, canvasW, canvasH, density, layout = { layoutDump = it }),
            )
            File(outDir, "$id.layout.json").writeText(layoutJson(requireNotNull(layoutDump), density))
            File(outDir, "$id-min.png").writeBytes(renderPng(source, 0.1f, canvasW, canvasH, density))
            // The max tiles are windows into the 5x canvas and get stitched back
            // together, so the grid has to *cover* it: at 5x the canvas is several
            // times the viewport and a 3-per-axis sample would leave blank bands.
            val xs = coverFractions(canvasW * 5, canvasW)
            val ys = coverFractions(canvasH * 5, canvasH)
            val tiles = buildList {
                for ((i, fy) in ys.withIndex()) {
                    for ((j, fx) in xs.withIndex()) {
                        add("[$fx, $fy]")
                        File(outDir, "$id-max-$i-$j.png").writeBytes(
                            renderPng(source, 5f, canvasW, canvasH, density, pan = Offset(fx, fy)),
                        )
                    }
                }
            }

            val ours100 = contentBounds(File(outDir, "$id-100.png").readBytes())
            manifest.append(
                """    "$id": {"canvasW": $layoutW, "canvasH": $layoutH, """ +
                    """"viewportW": $canvasW, "viewportH": $canvasH, """ +
                    """"contentW": ${ours100[2]}, "contentH": ${ours100[3]}, """ +
                    """"tiles": {"100": [[0.5, 0.5]], "min": [[0.5, 0.5]], "max": [${tiles.joinToString(", ")}]}},""",
            ).append('\n')
            println(
                "oracle: $id layout=${layoutW}x$layoutH gold=${gold[2]}x${gold[3]} " +
                    "content=${ours100[2]}x${ours100[3]} viewport=${canvasW}x$canvasH",
            )
        }
        manifest.setLength(manifest.length - 2)
        manifest.append("\n  }\n}\n")
        File(outDir, "manifest.json").writeText(manifest.toString())
    }
}
