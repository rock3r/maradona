package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
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

    private fun buildMultiItemSourcesForAll23Families(): Map<String, String> = linkedMapOf(
        "Flowchart" to buildLargeFlowchartSource(clusters = 4, nodesPerCluster = 15),
        "Sequence" to buildString {
            appendLine("sequenceDiagram")
            appendLine("  autonumber")
            for (i in 1..12) appendLine("  participant P$i as Service $i")
            for (i in 1..11) appendLine("  P$i->>P${i + 1}: Request payload $i")
            appendLine("  P1->>P12: Long span cross-viewport call")
        },
        "Class" to buildString {
            appendLine("classDiagram")
            for (i in 1..16) {
                appendLine("  class Entity$i {")
                appendLine("    +String id$i")
                appendLine("    +process$i() void")
                appendLine("  }")
                if (i > 1) appendLine("  Entity${i - 1} --> Entity$i : links $i")
            }
        },
        "State" to buildString {
            appendLine("stateDiagram-v2")
            appendLine("  [*] --> S1")
            for (i in 1..16) {
                appendLine("  S$i : Stage $i processing")
                if (i > 1) appendLine("  S${i - 1} --> S$i : step $i")
            }
            appendLine("  S16 --> [*]")
        },
        "ER" to buildString {
            appendLine("erDiagram")
            for (i in 1..14) {
                appendLine("  TABLE_$i {")
                appendLine("    string id PK")
                appendLine("    string name")
                appendLine("  }")
                if (i > 1) appendLine("  TABLE_${i - 1} ||--o{ TABLE_$i : refs_$i")
            }
        },
        "Mindmap" to buildString {
            appendLine("mindmap")
            appendLine("  root((Platform))")
            for (b in 1..6) {
                appendLine("    Branch_$b")
                for (l in 1..5) {
                    appendLine("      Leaf_${b}_$l")
                }
            }
        },
        "Gantt" to buildString {
            appendLine("gantt")
            appendLine("  title Multi-Phase Schedule")
            appendLine("  dateFormat YYYY-MM-DD")
            for (s in 1..4) {
                appendLine("  section Phase $s")
                for (t in 1..5) {
                    val day = ((s - 1) * 5 + t).toString().padStart(2, '0')
                    appendLine("    Task ${s}_$t :t_${s}_$t, 2026-10-$day, 2d")
                }
            }
        },
        "Pie" to buildString {
            appendLine("pie showData")
            appendLine("  title Segment Distribution")
            for (i in 1..12) appendLine("  \"Segment $i\" : ${i * 5}")
        },
        "GitGraph" to buildString {
            appendLine("gitGraph")
            for (i in 1..18) {
                appendLine("  commit id: \"c$i\"")
                if (i == 3) appendLine("  branch featureA")
                if (i == 8) {
                    appendLine("  checkout main")
                    appendLine("  merge featureA id: \"m1\"")
                }
            }
        },
        "Timeline" to buildString {
            appendLine("timeline")
            appendLine("  title Multi-Decade Product Timeline")
            for (s in 1..3) {
                appendLine("  section Era $s")
                for (p in 1..5) {
                    val idx = (s - 1) * 5 + p
                    appendLine("    20${(10 + idx)} : Milestone $idx A : Milestone $idx B")
                }
            }
        },
        "Journey" to buildString {
            appendLine("journey")
            appendLine("  title End-to-End Customer Journey")
            for (s in 1..4) {
                appendLine("  section Stage $s")
                for (t in 1..5) {
                    appendLine("    Action ${s}_$t: ${(t % 5) + 1}: User, Agent")
                }
            }
        },
        "Quadrant" to buildString {
            appendLine("quadrantChart")
            appendLine("  title Portfolio Matrix")
            appendLine("  x-axis Low --> High")
            appendLine("  y-axis Low --> High")
            for (i in 1..16) {
                val x = "%.2f".format(0.05 + (i % 4) * 0.28)
                val y = "%.2f".format(0.05 + ((i - 1) / 4) * 0.28)
                appendLine("  Item_$i: [$x, $y]")
            }
        },
        "XYChart" to buildString {
            appendLine("xychart-beta")
            appendLine("  title \"Extended Metrics\"")
            val cats = (1..14).joinToString(", ") { "M$it" }
            val vals = (1..14).joinToString(", ") { "${(it * 7) % 90 + 10}" }
            appendLine("  x-axis [$cats]")
            appendLine("  y-axis \"Score\" 0 --> 100")
            appendLine("  bar [$vals]")
            appendLine("  line [$vals]")
        },
        "Kanban" to buildString {
            appendLine("kanban")
            for (c in 1..5) {
                appendLine("  col$c[Column $c]")
                for (t in 1..5) {
                    appendLine("    task_${c}_$t[Card $c-$t]@{ ticket: KB-${c}$t, priority: 'High' }")
                }
            }
        },
        "Sankey" to buildString {
            appendLine("sankey-beta")
            for (i in 1..10) {
                appendLine("  Source_$i,Hub_${(i % 3) + 1},${i * 15}")
                appendLine("  Hub_${(i % 3) + 1},Sink_$i,${i * 12}")
            }
        },
        "Requirement" to buildString {
            appendLine("requirementDiagram")
            for (i in 1..12) {
                appendLine("  requirement req_$i {")
                appendLine("    id: R-$i")
                appendLine("    text: Requirement $i must be verified.")
                appendLine("    risk: medium")
                appendLine("    verifymethod: test")
                appendLine("  }")
                if (i > 1) appendLine("  req_${i - 1} - contains -> req_$i")
            }
        },
        "Packet" to buildString {
            appendLine("packet-beta")
            appendLine("  title \"Extended Frame Header\"")
            for (i in 0 until 20) {
                val start = i * 16
                val end = start + 15
                appendLine("  $start-$end: \"Field $i\"")
            }
        },
        "Radar" to buildString {
            appendLine("radar-beta")
            appendLine("  title \"System Capability Radar\"")
            for (i in 1..10) appendLine("  axis a$i[\"Axis $i\"]")
            appendLine("  curve c1[\"Baseline\"]{80, 70, 85, 60, 90, 75, 65, 88, 72, 84}")
            appendLine("  curve c2[\"Target\"]{92, 88, 95, 85, 96, 90, 86, 94, 89, 93}")
            appendLine("  max 100")
            appendLine("  min 0")
        },
        "Block" to buildString {
            appendLine("block-beta")
            appendLine("  columns 4")
            for (i in 1..20) {
                appendLine("  B$i[\"Block $i\"]")
            }
            appendLine("  B1 --> B20")
        },
        "Architecture" to buildString {
            appendLine("architecture-beta")
            for (g in 1..4) {
                appendLine("  group grp$g(cloud)[Region $g]")
                for (s in 1..4) {
                    val id = "svc_${g}_$s"
                    appendLine("  service $id(server)[Service $g-$s] in grp$g")
                    if (s > 1) appendLine("  svc_${g}_${s - 1}:R --> L:$id")
                }
            }
        },
        "Treemap" to buildString {
            appendLine("treemap-beta")
            for (s in 1..4) {
                appendLine("\"Division $s\"")
                for (l in 1..5) {
                    appendLine("  \"Team ${s}_$l\": ${(s * 10 + l * 3)}")
                }
            }
        },
        "C4" to buildString {
            appendLine("C4Context")
            appendLine("  title Enterprise Ecosystem")
            for (b in 1..3) {
                appendLine("  Enterprise_Boundary(bnd$b, \"Domain $b\") {")
                for (s in 1..5) {
                    val id = "sys_${b}_$s"
                    appendLine("    System($id, \"System $b-$s\", \"Core domain service $b-$s\")")
                }
                appendLine("  }")
            }
            appendLine("  Rel(sys_1_1, sys_3_5, \"Cross-domain replication\", \"gRPC\")")
        },
        "ZenUML" to buildString {
            appendLine("zenuml")
            appendLine("  title Distributed Transaction Flow")
            for (i in 1..10) appendLine("  @Boundary Service$i")
            for (i in 1..9) {
                appendLine("  Service$i->Service${i + 1}.step$i() {")
                appendLine("    return ok$i")
                appendLine("  }")
            }
        },
    )

    @Test
    fun all23FamiliesPerformViewportLazyCullingPreservePixelParityAccessibilityAndResetCleanly() {
        val sources = buildMultiItemSourcesForAll23Families()
        assertEquals(23, sources.size, "Expected all 23 Mermaid diagram families in culling suite")

        for ((family, source) in sources) {
            var culledStats: MaradonaViewportStats? = null
            var unculledStats: MaradonaViewportStats? = null
            val clicked = mutableListOf<MaradonaNodeClickEvent>()
            var resetKey by mutableIntStateOf(0)

            // 1. Render zoomed in (3.5x) and panned to the top-left corner in a compact 260x180 viewport
            //    so a portion of every family's diagram lies outside the viewport + overscan window.
            val testPan = when (family) {
                "XYChart" -> Offset(0.3f, 0.7f)
                "Radar" -> Offset(0.5f, 0.05f)
                "Sankey" -> Offset(0.05f, 0.1f)
                "ZenUML" -> Offset(0.2f, 0.02f)
                else -> Offset(0.3f, 0.2f)
            }
            val culledPng: ByteArray
            val culledScene = ImageComposeScene(width = 260, height = 180, density = Density(1f)) {
                MaradonaDiagram(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    zoomEnabled = true,
                    viewportCullingEnabled = true,
                    initialScale = 3.5f,
                    initialPan = testPan,
                    resetViewKey = resetKey,
                    onViewportStats = { culledStats = it },
                    onNodeClick = { clicked += it },
                )
            }
            try {
                var img = culledScene.render(16_000_000L)
                repeat(4) { f -> img = culledScene.render((f + 2) * 16_000_000L) }
                culledPng = requireNotNull(img.encodeToData(EncodedImageFormat.PNG)).bytes

                val cStats = assertNotNull(culledStats, "Expected onViewportStats to be reported for $family")
                assertTrue(
                    cStats.totalNodes >= 10,
                    "Expected at least 10 totalNodes in multi-item fixture for $family, got ${cStats.totalNodes}",
                )
                assertTrue(
                    cStats.composedNodes < cStats.totalNodes,
                    "Expected viewportCullingEnabled=true to lazily skip off-screen nodes for $family (composed=${cStats.composedNodes}, total=${cStats.totalNodes})",
                )
                assertTrue(
                    cStats.composedNodes >= 1,
                    "Expected at least 1 visible node composed for $family (got ${cStats.composedNodes})",
                )

                // Verify off-screen CustomAccessibilityActions are exposed on the diagram semantics tree
                fun collectCustomActionLabels(node: SemanticsNode, out: MutableList<String>) {
                    node.config.getOrNull(SemanticsActions.CustomActions)?.forEach { out += it.label }
                    for (child in node.children) collectCustomActionLabels(child, out)
                }
                val actionLabels = mutableListOf<String>()
                for (owner in culledScene.semanticsOwners) {
                    collectCustomActionLabels(owner.rootSemanticsNode, actionLabels)
                }
                val offscreenAction = actionLabels.firstOrNull { it.startsWith("Activate off-screen node") }
                assertNotNull(
                    offscreenAction,
                    "Expected off-screen CustomAccessibilityAction for $family when nodes are culled, got $actionLabels",
                )

                // Verify multi-frame stability when panned/culled and after resetViewKey increment
                val nextFramePng = requireNotNull(culledScene.render(10 * 16_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes
                assertTrue(
                    culledPng.contentEquals(nextFramePng),
                    "Expected settled culled frames to be byte-identical without vibration for $family",
                )
                resetKey++
                repeat(4) { f -> culledScene.render((f + 12) * 16_000_000L) }
                val afterResetPng = requireNotNull(culledScene.render(17 * 16_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes
                assertTrue(
                    culledPng.contentEquals(afterResetPng),
                    "Expected resetViewKey to restore exact initial zoomed/panned view for $family",
                )
            } finally {
                culledScene.close()
            }

            // 2. Render the exact same viewport with viewportCullingEnabled = false and verify pixel parity
            val unculledPng: ByteArray
            val unculledScene = ImageComposeScene(width = 260, height = 180, density = Density(1f)) {
                MaradonaDiagram(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    zoomEnabled = true,
                    viewportCullingEnabled = false,
                    initialScale = 3.5f,
                    initialPan = testPan,
                    onViewportStats = { unculledStats = it },
                    onNodeClick = {},
                )
            }
            try {
                var img = unculledScene.render(16_000_000L)
                repeat(4) { f -> img = unculledScene.render((f + 2) * 16_000_000L) }
                unculledPng = requireNotNull(img.encodeToData(EncodedImageFormat.PNG)).bytes
                val uStats = assertNotNull(unculledStats)
                assertEquals(
                    uStats.totalNodes,
                    uStats.composedNodes,
                    "Expected viewportCullingEnabled=false to compose all nodes for $family",
                )
            } finally {
                unculledScene.close()
            }

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
            assertEquals(
                0,
                diffPixels,
                "Expected viewportCullingEnabled=true to match viewportCullingEnabled=false pixel-for-pixel for $family (diffPixels=$diffPixels)",
            )
        }
    }
}
