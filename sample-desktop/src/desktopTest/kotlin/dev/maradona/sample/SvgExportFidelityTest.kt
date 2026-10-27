package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import dev.maradona.export.MaradonaSvgExporter
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.render.MaradonaDiagram
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.xml.sax.InputSource

@OptIn(ExperimentalComposeUiApi::class)
class SvgExportFidelityTest {

    @Test
    fun all23FamiliesExportWellFormedSvgWithExactSharedTextMeasurerGeometryMatch() {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }

        for ((name, source) in sampleExamples) {
            var composeLayoutSize: Pair<Int, Int>? = null
            var measuredSvg: String? = null

            val scene = ImageComposeScene(width = 1200, height = 900, density = Density(1f)) {
                val tm = rememberTextMeasurer()
                val dens = LocalDensity.current
                val theme = LocalMaradonaTheme.current
                if (measuredSvg == null) {
                    measuredSvg = MaradonaSvgExporter.exportToSvg(
                        source = source,
                        theme = theme,
                        textMeasurer = tm,
                        density = dens,
                    )
                }
                MaradonaDiagram(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    autoDirection = false,
                    initialScale = 1f,
                    onLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onSequenceLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onClassLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onStateLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onErLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onMindmapLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onGanttLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onPieLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onGitGraphLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onTimelineLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onJourneyLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onQuadrantLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onXyChartLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onKanbanLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onSankeyLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onRequirementLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onPacketLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onRadarLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onBlockLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onArchitectureLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onTreemapLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onC4Layout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                    onZenUmlLayout = { composeLayoutSize = ceil(it.width).toInt() to ceil(it.height).toInt() },
                )
            }
            try {
                repeat(3) { f -> scene.render((f + 1) * 16_000_000L) }
                val expectedSize = assertNotNull(composeLayoutSize, "Expected Compose layout callback for $name")
                val svg = assertNotNull(measuredSvg, "Expected measured SVG for $name")

                // Parse XML to prove well-formedness and inspect root <svg> dimensions
                val doc = dbf.newDocumentBuilder().parse(InputSource(StringReader(svg)))
                val root = doc.documentElement
                assertEquals("svg", root.tagName)
                val svgW = root.getAttribute("width").toInt()
                val svgH = root.getAttribute("height").toInt()
                assertEquals(
                    expectedSize,
                    svgW to svgH,
                    "Expected TextMeasurer-backed SVG export dimensions to match Compose layout exactly for $name",
                )
                assertTrue(
                    doc.getElementsByTagName("text").length >= 1,
                    "Expected at least 1 <text> node in exported SVG for $name",
                )
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun measuredFlowchartExportAtHighFontScaleWrapsToDensityConstraint() {
        val mmd = """
            flowchart LR
                A["Long wrapping label with many words to verify density wrapping width"]
        """.trimIndent()
        var svg: String? = null
        val scene = ImageComposeScene(width = 600, height = 400, density = Density(1f, 2f)) {
            val tm = rememberTextMeasurer()
            val dens = LocalDensity.current
            if (svg == null) {
                svg = MaradonaSvgExporter.exportToSvg(
                    source = mmd,
                    textMeasurer = tm,
                    density = dens,
                )
            }
        }
        try {
            scene.render(16_000_000L)
            val exported = assertNotNull(svg)
            val textCount = Regex("""<text\b""").findAll(exported).count()
            assertTrue(
                textCount >= 3,
                "Expected long label at Density(1f, 2f) to wrap against 120dp (120px) constraint into >= 3 lines, got $textCount",
            )
        } finally {
            scene.close()
        }
    }
}
