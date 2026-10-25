package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.maradona.layout.C4DiagramLayout
import dev.maradona.layout.ClassDiagramLayout
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.GitGraphDiagramLayout
import dev.maradona.layout.JourneyDiagramLayout
import dev.maradona.layout.PacketDiagramLayout
import dev.maradona.layout.PieDiagramLayout
import dev.maradona.layout.QuadrantDiagramLayout
import dev.maradona.layout.RadarDiagramLayout
import dev.maradona.layout.RequirementDiagramLayout
import dev.maradona.layout.SankeyDiagramLayout
import dev.maradona.layout.TimelineDiagramLayout
import dev.maradona.layout.TreemapDiagramLayout
import dev.maradona.layout.XyChartDiagramLayout
import dev.maradona.layout.ZenUmlDiagramLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.render.DiagramZoom
import dev.maradona.render.MaradonaDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalComposeUiApi::class)
class AccessibilityAndClickTest {

    @Test
    fun flowchartNodeClickDispatchesUrlTooltipAndCallbackEvents() {
        var layout: DiagramLayout? = null
        val clicked = mutableListOf<MaradonaNodeClickEvent>()
        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    flowchart TD
                      A[Mermaid Docs] --> B[Run Action]
                      click A "https://mermaid.js.org" "Documentation" _blank
                      click B call handleAction("B") "Run callback"
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onLayout = { layout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> scene.render((frame + 1) * 16_000_000L) }
            val currentLayout = assertNotNull(layout)
            val offsetX = (800f - currentLayout.width) / 2f
            val offsetY = (600f - currentLayout.height) / 2f

            val nodeA = currentLayout.nodes.first { it.node.id == "A" }
            val posA = Offset(offsetX + nodeA.rect.centerX, offsetY + nodeA.rect.centerY)
            scene.sendPointerEvent(PointerEventType.Press, posA)
            scene.sendPointerEvent(PointerEventType.Release, posA)
            scene.render(100_000_000L)

            assertEquals(1, clicked.size)
            assertEquals("A", clicked[0].nodeId)
            assertEquals("Mermaid Docs", clicked[0].label)
            assertEquals("https://mermaid.js.org", clicked[0].url)
            assertEquals("Documentation", clicked[0].tooltip)
            assertEquals("_blank", clicked[0].target)

            val nodeB = currentLayout.nodes.first { it.node.id == "B" }
            val posB = Offset(offsetX + nodeB.rect.centerX, offsetY + nodeB.rect.centerY)
            scene.sendPointerEvent(PointerEventType.Press, posB)
            scene.sendPointerEvent(PointerEventType.Release, posB)
            scene.render(120_000_000L)

            assertEquals(2, clicked.size)
            assertEquals("B", clicked[1].nodeId)
            assertEquals("handleAction(\"B\")", clicked[1].callback)
            assertEquals("Run callback", clicked[1].tooltip)
        } finally {
            scene.close()
        }
    }

    @Test
    fun classC4AndRequirementNodesDispatchLinkEventsOnClick() {
        var classLayout: ClassDiagramLayout? = null
        val clicked = mutableListOf<MaradonaNodeClickEvent>()
        val classScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    classDiagram
                      class Order {
                        +String id
                      }
                      link Order "https://example.com/order" "Order details" _blank
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onClassLayout = { classLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> classScene.render((frame + 1) * 16_000_000L) }
            val cl = assertNotNull(classLayout)
            val ox = (800f - cl.width) / 2f
            val oy = (600f - cl.height) / 2f
            val orderBox = cl.classes.first { it.node.id == "Order" }.rect
            val pos = Offset(ox + orderBox.centerX, oy + orderBox.centerY)
            classScene.sendPointerEvent(PointerEventType.Press, pos)
            classScene.sendPointerEvent(PointerEventType.Release, pos)
            classScene.render(100_000_000L)

            assertEquals(1, clicked.size)
            assertEquals("Order", clicked[0].nodeId)
            assertEquals("https://example.com/order", clicked[0].url)
            assertEquals("Order details", clicked[0].tooltip)
        } finally {
            classScene.close()
        }

        clicked.clear()
        var c4Layout: C4DiagramLayout? = null
        val c4Scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    C4Context
                      System(banking, "Internet Banking", "Core web app", ${'$'}link="https://bank.example.com")
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onC4Layout = { c4Layout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> c4Scene.render((frame + 1) * 16_000_000L) }
            val c4l = assertNotNull(c4Layout)
            val ox = (800f - c4l.width) / 2f
            val oy = (600f - c4l.height) / 2f
            val elBox = c4l.elements.first { it.element.id == "banking" }.rect
            val pos = Offset(ox + elBox.centerX, oy + elBox.centerY)
            c4Scene.sendPointerEvent(PointerEventType.Press, pos)
            c4Scene.sendPointerEvent(PointerEventType.Release, pos)
            c4Scene.render(100_000_000L)

            assertEquals(1, clicked.size)
            assertEquals("banking", clicked[0].nodeId)
            assertEquals("https://bank.example.com", clicked[0].url)
        } finally {
            c4Scene.close()
        }

        clicked.clear()
        var reqLayout: RequirementDiagramLayout? = null
        val reqScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    requirementDiagram
                      element auth_service {
                        type: service
                        docRef: https://docs.example.com/auth
                      }
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onRequirementLayout = { reqLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> reqScene.render((frame + 1) * 16_000_000L) }
            val rl = assertNotNull(reqLayout)
            val ox = (800f - rl.width) / 2f
            val oy = (600f - rl.height) / 2f
            val itemBox = rl.items.first { it.item.name == "auth_service" }.rect
            val pos = Offset(ox + itemBox.centerX, oy + itemBox.centerY)
            reqScene.sendPointerEvent(PointerEventType.Press, pos)
            reqScene.sendPointerEvent(PointerEventType.Release, pos)
            reqScene.render(100_000_000L)

            assertEquals(1, clicked.size)
            assertEquals("auth_service", clicked[0].nodeId)
            assertEquals("https://docs.example.com/auth", clicked[0].url)
        } finally {
            reqScene.close()
        }
    }

    @Test
    fun remainingElevenDiagramFamiliesDispatchOnNodeClickEvents() {
        val clicked = mutableListOf<MaradonaNodeClickEvent>()

        // 1. Pie (click legend item)
        var pieLayout: PieDiagramLayout? = null
        val pieScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    pie
                      "Kotlin" : 75
                      "Python" : 25
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onPieLayout = { pieLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> pieScene.render((frame + 1) * 16_000_000L) }
            val pl = assertNotNull(pieLayout)
            val ox = (800f - pl.width) / 2f
            val oy = (600f - pl.height) / 2f
            val li = pl.legendItems.first()
            val pos = Offset(ox + li.labelRect.centerX, oy + li.labelRect.centerY)
            pieScene.sendPointerEvent(PointerEventType.Press, pos)
            pieScene.sendPointerEvent(PointerEventType.Release, pos)
            pieScene.render(100_000_000L)
            assertEquals("Kotlin", clicked.last().nodeId)
        } finally {
            pieScene.close()
        }

        // 2. GitGraph (click commit node)
        var gitLayout: GitGraphDiagramLayout? = null
        val gitScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    gitGraph
                      commit id: "init-1"
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onGitGraphLayout = { gitLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> gitScene.render((frame + 1) * 16_000_000L) }
            val gl = assertNotNull(gitLayout)
            val ox = (800f - gl.width) / 2f
            val oy = (600f - gl.height) / 2f
            val cRect = gl.commits.first().nodeRect
            val pos = Offset(ox + cRect.centerX, oy + cRect.centerY)
            gitScene.sendPointerEvent(PointerEventType.Press, pos)
            gitScene.sendPointerEvent(PointerEventType.Release, pos)
            gitScene.render(100_000_000L)
            assertEquals("init-1", clicked.last().nodeId)
        } finally {
            gitScene.close()
        }

        // 3. Timeline (click period card)
        var timelineLayout: TimelineDiagramLayout? = null
        val timelineScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    timeline
                      2026 : Launch v1
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onTimelineLayout = { timelineLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> timelineScene.render((frame + 1) * 16_000_000L) }
            val tl = assertNotNull(timelineLayout)
            val ox = (800f - tl.width) / 2f
            val oy = (600f - tl.height) / 2f
            val pRect = tl.periods.first().rect
            val pos = Offset(ox + pRect.centerX, oy + pRect.centerY)
            timelineScene.sendPointerEvent(PointerEventType.Press, pos)
            timelineScene.sendPointerEvent(PointerEventType.Release, pos)
            timelineScene.render(100_000_000L)
            assertEquals("2026", clicked.last().label)
        } finally {
            timelineScene.close()
        }

        // 4. Journey (click task card)
        var journeyLayout: JourneyDiagramLayout? = null
        val journeyScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    journey
                      section Onboarding
                        Sign up: 5: Alice
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onJourneyLayout = { journeyLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> journeyScene.render((frame + 1) * 16_000_000L) }
            val jl = assertNotNull(journeyLayout)
            val ox = (800f - jl.width) / 2f
            val oy = (600f - jl.height) / 2f
            val tRect = jl.tasks.first().cardRect
            val pos = Offset(ox + tRect.centerX, oy + tRect.centerY)
            journeyScene.sendPointerEvent(PointerEventType.Press, pos)
            journeyScene.sendPointerEvent(PointerEventType.Release, pos)
            journeyScene.render(100_000_000L)
            assertEquals("Sign up", clicked.last().label)
        } finally {
            journeyScene.close()
        }

        // 5. Quadrant (click data point)
        var quadrantLayout: QuadrantDiagramLayout? = null
        val quadrantScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    quadrantChart
                      Core Platform: [0.8, 0.9]
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onQuadrantLayout = { quadrantLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> quadrantScene.render((frame + 1) * 16_000_000L) }
            val ql = assertNotNull(quadrantLayout)
            val ox = (800f - ql.width) / 2f
            val oy = (600f - ql.height) / 2f
            val pt = ql.points.first()
            val pos = Offset(ox + pt.center.x, oy + pt.center.y)
            quadrantScene.sendPointerEvent(PointerEventType.Press, pos)
            quadrantScene.sendPointerEvent(PointerEventType.Release, pos)
            quadrantScene.render(100_000_000L)
            assertEquals("Core Platform", clicked.last().label)
        } finally {
            quadrantScene.close()
        }

        // 6. XY Chart (click bar)
        var xyLayout: XyChartDiagramLayout? = null
        val xyScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    xychart-beta
                      x-axis [Q1]
                      y-axis 0 --> 100
                      bar [80]
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onXyChartLayout = { xyLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> xyScene.render((frame + 1) * 16_000_000L) }
            val xl = assertNotNull(xyLayout)
            val ox = (800f - xl.width) / 2f
            val oy = (600f - xl.height) / 2f
            val bRect = xl.bars.first().rect
            val pos = Offset(ox + bRect.centerX, oy + bRect.centerY)
            xyScene.sendPointerEvent(PointerEventType.Press, pos)
            xyScene.sendPointerEvent(PointerEventType.Release, pos)
            xyScene.render(100_000_000L)
            assertEquals("plot_0_0", clicked.last().nodeId)
        } finally {
            xyScene.close()
        }

        // 7. Sankey (click node bar)
        var sankeyLayout: SankeyDiagramLayout? = null
        val sankeyScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    sankey-beta
                      Solar,Grid,100
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onSankeyLayout = { sankeyLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> sankeyScene.render((frame + 1) * 16_000_000L) }
            val sl = assertNotNull(sankeyLayout)
            val ox = (800f - sl.width) / 2f
            val oy = (600f - sl.height) / 2f
            val nRect = sl.nodes.first { it.node.id == "Solar" }.rect
            val pos = Offset(ox + nRect.centerX, oy + nRect.centerY)
            sankeyScene.sendPointerEvent(PointerEventType.Press, pos)
            sankeyScene.sendPointerEvent(PointerEventType.Release, pos)
            sankeyScene.render(100_000_000L)
            assertEquals("Solar", clicked.last().nodeId)
        } finally {
            sankeyScene.close()
        }

        // 8. Packet (click packet field block)
        var packetLayout: PacketDiagramLayout? = null
        val packetScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    packet-beta
                      0-15: "SrcPort"
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onPacketLayout = { packetLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> packetScene.render((frame + 1) * 16_000_000L) }
            val pkl = assertNotNull(packetLayout)
            val ox = (800f - pkl.width) / 2f
            val oy = (600f - pkl.height) / 2f
            val bRect = pkl.blocks.first().rect
            val pos = Offset(ox + bRect.centerX, oy + bRect.centerY)
            packetScene.sendPointerEvent(PointerEventType.Press, pos)
            packetScene.sendPointerEvent(PointerEventType.Release, pos)
            packetScene.render(100_000_000L)
            assertEquals("SrcPort", clicked.last().label)
        } finally {
            packetScene.close()
        }

        // 9. Radar (click axis label)
        var radarLayout: RadarDiagramLayout? = null
        val radarScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    radar-beta
                      axis speed["Speed"], rel["Reliability"], mem["Memory"]
                      curve m["Maradona"]{90, 95, 88}
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onRadarLayout = { radarLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> radarScene.render((frame + 1) * 16_000_000L) }
            val rdl = assertNotNull(radarLayout)
            val ox = (800f - rdl.width) / 2f
            val oy = (600f - rdl.height) / 2f
            val axRect = rdl.axes.first().labelRect
            val pos = Offset(ox + axRect.centerX, oy + axRect.centerY)
            radarScene.sendPointerEvent(PointerEventType.Press, pos)
            radarScene.sendPointerEvent(PointerEventType.Release, pos)
            radarScene.render(100_000_000L)
            assertEquals("speed", clicked.last().nodeId)
        } finally {
            radarScene.close()
        }

        // 10. Treemap (click leaf tile)
        var treemapLayout: TreemapDiagramLayout? = null
        val treemapScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    treemap-beta
                    "Platform"
                        "Core": 60
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onTreemapLayout = { treemapLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> treemapScene.render((frame + 1) * 16_000_000L) }
            val tml = assertNotNull(treemapLayout)
            val ox = (800f - tml.width) / 2f
            val oy = (600f - tml.height) / 2f
            val lRect = tml.leaves.first().rect
            val pos = Offset(ox + lRect.centerX, oy + lRect.centerY)
            treemapScene.sendPointerEvent(PointerEventType.Press, pos)
            treemapScene.sendPointerEvent(PointerEventType.Release, pos)
            treemapScene.render(100_000_000L)
            assertEquals("Core", clicked.last().nodeId)
        } finally {
            treemapScene.close()
        }

        // 11. ZenUML (click participant card)
        var zenLayout: ZenUmlDiagramLayout? = null
        val zenScene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            MaradonaDiagram(
                source = """
                    zenuml
                      Client->Server.ping() {
                        return pong
                      }
                """.trimIndent(),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                initialScale = 1f,
                onZenUmlLayout = { zenLayout = it },
                onNodeClick = { clicked += it },
            )
        }
        try {
            repeat(4) { frame -> zenScene.render((frame + 1) * 16_000_000L) }
            val zl = assertNotNull(zenLayout)
            val ox = (800f - zl.width) / 2f
            val oy = (600f - zl.height) / 2f
            val pRect = zl.participants.first { it.participant.id == "Client" }.topRect
            val pos = Offset(ox + pRect.centerX, oy + pRect.centerY)
            zenScene.sendPointerEvent(PointerEventType.Press, pos)
            zenScene.sendPointerEvent(PointerEventType.Release, pos)
            zenScene.render(100_000_000L)
            assertEquals("Client", clicked.last().nodeId)
        } finally {
            zenScene.close()
        }
    }
}
