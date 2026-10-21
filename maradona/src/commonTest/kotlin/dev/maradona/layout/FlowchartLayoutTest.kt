package dev.maradona.layout

import dev.maradona.model.Direction
import dev.maradona.model.FlowNode
import dev.maradona.parse.FlowchartParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowchartLayoutTest {

    private fun fakeSize(node: FlowNode): DiagramSize =
        DiagramSize(width = 40f + node.label.length * 8f, height = 36f)

    private fun layoutOf(source: String): DiagramLayout {
        val chart = FlowchartParser.parse(source)
        return FlowchartLayout.layout(chart, ::fakeSize, { edge ->
            edge.label?.let { DiagramSize(it.length * 7f, 16f) }
        })
    }

    @Test
    fun nodesDoNotOverlap() {
        val layout = layoutOf(
            """
            flowchart TD
              A[Start] --> B{Decision point?}
              B -- Yes --> C[Long branch label here]
              B -- No --> D[Other branch]
              C --> E([Done])
              D --> E
            """.trimIndent(),
        )
        assertEquals(5, layout.nodes.size)
        for (i in layout.nodes.indices) for (j in i + 1 until layout.nodes.size) {
            val a = layout.nodes[i].rect
            val b = layout.nodes[j].rect
            val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
            assertTrue(!overlap, "Nodes overlap: ${layout.nodes[i].node.id} vs ${layout.nodes[j].node.id}")
        }
        // TD: start above decision above done.
        assertTrue(layout.node("A")!!.rect.y < layout.node("B")!!.rect.y)
        assertTrue(layout.node("B")!!.rect.y < layout.node("E")!!.rect.y)
        assertTrue(layout.width > 0f && layout.height > 0f)
    }

    @Test
    fun cyclesTerminate() {
        val layout = layoutOf(
            """
            flowchart TD
              A --> B --> C --> A
              C --> D
            """.trimIndent(),
        )
        assertEquals(4, layout.nodes.size)
        assertEquals(4, layout.edges.size)
    }

    @Test
    fun leftRightTransposes() {
        val layout = layoutOf(
            """
            flowchart LR
              A[Left] --> B[Middle] --> C[Right]
            """.trimIndent(),
        )
        assertEquals(Direction.LR, layout.direction)
        assertTrue(layout.node("A")!!.rect.x < layout.node("B")!!.rect.x)
        assertTrue(layout.node("B")!!.rect.x < layout.node("C")!!.rect.x)
        // Node box size is preserved, not rotated.
        val expected = fakeSize(FlowNode(id = "A", label = "Left"))
        assertEquals(expected.width, layout.node("A")!!.rect.width)
        assertEquals(expected.height, layout.node("A")!!.rect.height)
    }

    @Test
    fun longEdgesRouteThroughLayers() {
        val layout = layoutOf(
            """
            flowchart TD
              A --> B --> C --> D
              A --> D
            """.trimIndent(),
        )
        val longEdge = layout.edges.last()
        assertTrue(longEdge.points.size >= 4, "Long edge should have bend points: ${longEdge.points}")
    }

    @Test
    fun edgeLabelsPositioned() {
        val layout = layoutOf(
            """
            flowchart TD
              A -- Yes --> B
            """.trimIndent(),
        )
        assertTrue(layout.edges.single().labelRect != null)
    }

    @Test
    fun disconnectedNodesAndSelfLoop() {
        val layout = layoutOf(
            """
            flowchart TD
              A[Alone]
              B --> B
            """.trimIndent(),
        )
        assertEquals(2, layout.nodes.size)
        assertEquals(1, layout.edges.size)
        assertTrue(layout.edges.single().points.size >= 4)
    }

    @Test
    fun subgraphRectContainsMembers() {
        val layout = layoutOf(
            """
            flowchart TD
              subgraph g[Group]
                A --> B
              end
              B --> C
            """.trimIndent(),
        )
        val sg = layout.subgraphs.single().rect
        for (id in listOf("A", "B")) {
            val r = layout.node(id)!!.rect
            assertTrue(r.x >= sg.x && r.y >= sg.y && r.right <= sg.right && r.bottom <= sg.bottom)
        }
    }

    @Test
    fun edgeIncidentOnSubgraphAttachesToClusterBoundary() {
        val layout = layoutOf(
            """
            flowchart TD
              Start --> g
              subgraph g[Group]
                A --> B
              end
              g --> End
            """.trimIndent(),
        )
        val sg = layout.subgraphs.single().rect
        val intoSg = layout.edges.first { it.edge.to == "g" }
        val fromSg = layout.edges.first { it.edge.from == "g" }
        assertEquals(sg.y, intoSg.points.last().y, 0.5f)
        assertEquals(sg.bottom, fromSg.points.first().y, 0.5f)
    }

    @Test
    fun emptySubgraphAndSelfLoopLabelFitWithinBounds() {
        val emptySg = layoutOf(
            """
            flowchart TD
              subgraph empty[Empty Group]
              end
            """.trimIndent(),
        )
        assertEquals(1, emptySg.subgraphs.size)
        assertTrue(emptySg.width > 0f && emptySg.height > 0f)

        val selfLoop = layoutOf(
            """
            flowchart TD
              A[Node] -- loop label --> A
            """.trimIndent(),
        )
        val label = selfLoop.edges.single().labelRect!!
        assertTrue(label.right <= selfLoop.width && label.y >= 0f)
    }
}
