package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.maradona.export.MaradonaSvgExporter
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.parse.FlowchartParser
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaLayout
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class ViewportLazyCullingTest {

    private fun buildLargeFlowchartSource(clusters: Int = 6, nodesPerCluster: Int = 40): String {
        val sb = StringBuilder("flowchart TB\n")
        for (c in 0 until clusters) {
            sb.append("  subgraph sg_$c [Cluster $c]\n")
            for (i in 0 until nodesPerCluster) {
                val id = "N_${c}_$i"
                sb.append("    $id[Service $c-$i]\n")
                if (i > 0) {
                    val prev = "N_${c}_${i - 1}"
                    sb.append("    $prev -->|step $i| $id\n")
                }
            }
            sb.append("  end\n")
            if (c > 0) {
                sb.append("  N_${c - 1}_${nodesPerCluster - 1} ==> N_${c}_0\n")
            }
        }
        return sb.toString()
    }

    @Test
    fun viewportLazyCullingComposesOnlyVisibleNodesMatchesPixelsAndPreservesHitTestingAndExport() {
        val source = buildLargeFlowchartSource(clusters = 6, nodesPerCluster = 35) // 210 nodes
        val chart = FlowchartParser.parse(source)

        var culledStats: MaradonaViewportStats? = null
        var unculledStats: MaradonaViewportStats? = null
        var culledLayout: DiagramLayout? = null
        val clicked = mutableListOf<MaradonaNodeClickEvent>()

        val culledPng: ByteArray
        val culledScene = ImageComposeScene(width = 600, height = 450, density = Density(1f)) {
            MaradonaDiagram(
                source = source,
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                viewportCullingEnabled = true,
                initialScale = 1.5f,
                initialPan = Offset(0.5f, 0.1f),
                onLayout = { culledLayout = it },
                onViewportStats = { culledStats = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            var img = culledScene.render(16_000_000L)
            repeat(5) { f -> img = culledScene.render((f + 2) * 16_000_000L) }
            culledPng = requireNotNull(img.encodeToData(EncodedImageFormat.PNG)).bytes

            val stats = assertNotNull(culledStats)
            assertEquals(210, stats.totalNodes)
            assertTrue(
                stats.composedNodes in 1..45,
                "Expected viewport-lazy composition to compose only visible nodes (got ${stats.composedNodes}/${stats.totalNodes})",
            )
            assertTrue(
                stats.drawnEdges in 1..60,
                "Expected underlay culling to draw only viewport-intersecting edges (got ${stats.drawnEdges}/${stats.totalEdges})",
            )

            // Verify hit-testing (`onNodeClick`) on a visible node inside the panned viewport
            val layout = assertNotNull(culledLayout)
            val rangeX = layout.width - 600f
            val rangeY = layout.height - 450f
            val panX = if (rangeX > 0f) rangeX / 2f - 0.5f * rangeX else 0f
            val panY = if (rangeY > 0f) rangeY / 2f - 0.1f * rangeY else 0f
            val transX = (600f - layout.width) / 2f + panX
            val transY = (450f - layout.height) / 2f + panY

            val visibleNode = layout.nodes.first { pn ->
                val sx = transX + pn.rect.centerX
                val sy = transY + pn.rect.centerY
                sx in 80f..520f && sy in 80f..370f
            }
            val clickPos = Offset(transX + visibleNode.rect.centerX, transY + visibleNode.rect.centerY)
            culledScene.sendPointerEvent(PointerEventType.Press, clickPos)
            culledScene.sendPointerEvent(PointerEventType.Release, clickPos)
            culledScene.render(200_000_000L)

            assertEquals(1, clicked.size, "Expected click on visible node ${visibleNode.node.id} to dispatch event")
            assertEquals(visibleNode.node.id, clicked.first().nodeId)

            // Verify SVG export on the layout still exports all 210 nodes
            val svg = MaradonaSvgExporter.exportFlowchartToSvg(chart, layout)
            assertTrue(svg.contains("data-id=\"N_0_0\""))
            assertTrue(svg.contains("data-id=\"N_5_34\""))
        } finally {
            culledScene.close()
        }

        val unculledPng: ByteArray
        val unculledScene = ImageComposeScene(width = 600, height = 450, density = Density(1f)) {
            MaradonaDiagram(
                source = source,
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                viewportCullingEnabled = false,
                initialScale = 1.5f,
                initialPan = Offset(0.5f, 0.1f),
                onViewportStats = { unculledStats = it },
            )
        }
        try {
            var img = unculledScene.render(16_000_000L)
            repeat(5) { f -> img = unculledScene.render((f + 2) * 16_000_000L) }
            unculledPng = requireNotNull(img.encodeToData(EncodedImageFormat.PNG)).bytes
            val uStats = assertNotNull(unculledStats)
            assertEquals(210, uStats.composedNodes)
            assertEquals(uStats.totalEdges, uStats.drawnEdges)
        } finally {
            unculledScene.close()
        }

        // Pixel-for-pixel equality between viewport-culled and unculled renders
        val imgA = ImageIO.read(ByteArrayInputStream(culledPng))
        val imgB = ImageIO.read(ByteArrayInputStream(unculledPng))
        assertEquals(imgA.width, imgB.width)
        assertEquals(imgA.height, imgB.height)
        var diffPixels = 0
        for (y in 0 until imgA.height) {
            for (x in 0 until imgA.width) {
                if (imgA.getRGB(x, y) != imgB.getRGB(x, y)) diffPixels++
            }
        }
        assertEquals(0, diffPixels, "Viewport-culled render must match unculled render pixel-for-pixel")
    }

    @Test
    fun edgeCrossingViewportBetweenTwoOffscreenNodesIsDrawn() {
        // Long chain A0 -> A1 -> ... -> A12 plus a direct long-span edge A0 --> A12.
        // When viewing only the middle band where A0 and A12 are both off-screen,
        // the long edge A0 --> A12 crosses the viewport and must still be drawn.
        val sb = StringBuilder("flowchart TB\n")
        for (i in 0..12) {
            sb.append("  A$i[Step $i]\n")
            if (i > 0) sb.append("  A${i - 1} --> A$i\n")
        }
        sb.append("  A0 ==> A12\n")
        val chart = FlowchartParser.parse(sb.toString())

        var fullLayout: DiagramLayout? = null
        val fullScene = ImageComposeScene(width = 600, height = 2000, density = Density(1f)) {
            MaradonaLayout(
                chart = chart,
                visibleViewport = null,
                onLayout = { fullLayout = it },
            )
        }
        val layout = try {
            fullScene.render(16_000_000L)
            assertNotNull(fullLayout)
        } finally {
            fullScene.close()
        }

        val nodeA0 = layout.nodes.first { it.node.id == "A0" }.rect
        val nodeA12 = layout.nodes.first { it.node.id == "A12" }.rect
        val midY = (nodeA0.bottom + nodeA12.y) / 2f
        // Viewport band in the middle: excludes both A0 and A12
        val midViewport = DiagramRect(x = 0f, y = midY - 60f, width = layout.width, height = 120f)
        assertTrue(!nodeA0.intersects(midViewport), "A0 must be above midViewport")
        assertTrue(!nodeA12.intersects(midViewport), "A12 must be below midViewport")

        val longEdge = layout.edges.first { it.edge.from == "A0" && it.edge.to == "A12" }
        assertTrue(
            longEdge.bounds(20f).intersects(midViewport),
            "Long edge A0 ==> A12 between off-screen endpoints must intersect midViewport",
        )

        var midStats: MaradonaViewportStats? = null
        val midScene = ImageComposeScene(width = 600, height = 2000, density = Density(1f)) {
            MaradonaLayout(
                chart = chart,
                visibleViewport = midViewport,
                onViewportStats = { midStats = it },
            )
        }
        try {
            midScene.render(16_000_000L)
            val st = assertNotNull(midStats)
            assertTrue(st.composedNodes in 1..3, "Only middle nodes should be composed (got ${st.composedNodes})")
            assertTrue(st.drawnEdges >= 2, "Crossing edges must be drawn even when endpoints are off-screen")
        } finally {
            midScene.close()
        }
    }

    @Test
    fun benchmarkViewportLazyCompositionAcrossPanSteps() {
        val source = buildLargeFlowchartSource(clusters = 8, nodesPerCluster = 50) // 400 nodes
        val panSteps = (0 until 8).map { i -> Offset(0.5f, i / 7f) }

        // Warmup both paths once so JIT compilation does not skew measurements
        for (cull in listOf(false, true)) {
            val warmup = ImageComposeScene(width = 700, height = 500, density = Density(1f)) {
                MaradonaDiagram(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    zoomEnabled = false,
                    viewportCullingEnabled = cull,
                    initialScale = 2f,
                    initialPan = Offset(0.5f, 0.1f),
                )
            }
            try {
                warmup.render(16_000_000L)
                warmup.render(32_000_000L)
            } finally {
                warmup.close()
            }
        }

        var unculledStats: MaradonaViewportStats? = null
        val unculledNs = measureNanoTime {
            for (pan in panSteps) {
                val scene = ImageComposeScene(width = 700, height = 500, density = Density(1f)) {
                    MaradonaDiagram(
                        source = source,
                        modifier = Modifier.fillMaxSize(),
                        zoomEnabled = false,
                        viewportCullingEnabled = false,
                        initialScale = 2f,
                        initialPan = pan,
                        onViewportStats = { unculledStats = it },
                    )
                }
                try {
                    scene.render(16_000_000L)
                    scene.render(32_000_000L)
                } finally {
                    scene.close()
                }
            }
        }
        val unculledElapsedMs = unculledNs / 1_000_000.0

        var culledStats: MaradonaViewportStats? = null
        val culledNs = measureNanoTime {
            for (pan in panSteps) {
                val scene = ImageComposeScene(width = 700, height = 500, density = Density(1f)) {
                    MaradonaDiagram(
                        source = source,
                        modifier = Modifier.fillMaxSize(),
                        zoomEnabled = false,
                        viewportCullingEnabled = true,
                        initialScale = 2f,
                        initialPan = pan,
                        onViewportStats = { culledStats = it },
                    )
                }
                try {
                    scene.render(16_000_000L)
                    scene.render(32_000_000L)
                } finally {
                    scene.close()
                }
            }
        }
        val culledElapsedMs = culledNs / 1_000_000.0

        val cStats = assertNotNull(culledStats)
        val uStats = assertNotNull(unculledStats)
        val speedup = unculledElapsedMs / culledElapsedMs.coerceAtLeast(0.01)

        // Also measure warm in-session panning across 8 viewport windows (reusing cached Dagre layout)
        val chart = FlowchartParser.parse(source)
        var warmPanIdx by mutableStateOf(0)
        var warmStats: MaradonaViewportStats? = null
        val warmScene = ImageComposeScene(width = 700, height = 500, density = Density(1f)) {
            val step = warmPanIdx
            MaradonaLayout(
                chart = chart,
                visibleViewport = DiagramRect(x = 0f, y = step * 400f, width = 700f, height = 500f),
                onViewportStats = { warmStats = it },
            )
        }
        val warmPanElapsedMs = try {
            warmScene.render(16_000_000L)
            val ns = measureNanoTime {
                for (i in 0 until panSteps.size) {
                    warmPanIdx = i
                    warmScene.render((i + 2) * 16_000_000L)
                }
            }
            ns / 1_000_000.0
        } finally {
            warmScene.close()
        }
        val wStats = assertNotNull(warmStats)
        val warmSpeedup = unculledElapsedMs / warmPanElapsedMs.coerceAtLeast(0.01)
        println(
            "BENCHMARK 400-node flowchart (8 zoomed viewport renders): " +
                "cold_unculled=${"%.2f".format(unculledElapsedMs)}ms (${"%.2f".format(unculledElapsedMs / panSteps.size)}ms/view, composed=${uStats.composedNodes}/${uStats.totalNodes}, edges=${uStats.drawnEdges}/${uStats.totalEdges}), " +
                "cold_culled=${"%.2f".format(culledElapsedMs)}ms (${"%.2f".format(culledElapsedMs / panSteps.size)}ms/view, composed=${cStats.composedNodes}/${cStats.totalNodes}, edges=${cStats.drawnEdges}/${cStats.totalEdges}, speedup=${"%.2f".format(speedup)}x), " +
                "warm_pan_culled=${"%.2f".format(warmPanElapsedMs)}ms (${"%.2f".format(warmPanElapsedMs / panSteps.size)}ms/frame, composed=${wStats.composedNodes}/${wStats.totalNodes}, edges=${wStats.drawnEdges}/${wStats.totalEdges}, speedup=${"%.1f".format(warmSpeedup)}x)",
        )
        assertTrue(cStats.composedNodes < uStats.composedNodes / 4)
        assertTrue(cStats.drawnEdges < uStats.drawnEdges / 4)
    }
}
