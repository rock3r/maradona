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
import dev.maradona.layout.RequirementDiagramLayout
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
}
