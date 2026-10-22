package dev.maradona.layout

import dev.maradona.model.MindmapShape
import dev.maradona.parse.MindmapParser
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MindmapLayoutTest {

    @Test
    fun laysOutBidirectionalBranchesWithoutNodeOverlaps() {
        val diagram = MindmapParser.parse(
            """
            mindmap
              root(("Hawaiʻi
              Islands
              Archipelago"))
                History
                  Old legends
                  ::icon(fa fa-book)
                  Voyaging
                    Polynesian navigators
                    bangNode))Compose Multiplatform((
                Nature
                  Mountains and volcanoes
                  cloudNode)Ocean and Reef Life(
                    Sea turtles
                    Monk seals
                    Parrotfish
                Culture
                  Hula and lei
                  ʻUkulele
            """.trimIndent(),
        )

        val layout = MindmapLayout.layout(diagram)
        assertTrue(layout.width > 0f && layout.height > 0f)

        val root = assertNotNull(layout.node("root"))
        // Multiline circle diameter must enclose the diagonal of the multi-line text block.
        assertTrue(root.rect.width > 110f, "Multiline circle root should expand to enclose text diagonal: ${root.rect}")
        val history = assertNotNull(layout.node("History"))
        val nature = assertNotNull(layout.node("Nature"))
        val culture = assertNotNull(layout.node("Culture"))

        // With 3 branches, at least one branch is placed to the right of root and at least one to the left.
        val branchXs = listOf(history.rect.centerX, nature.rect.centerX, culture.rect.centerX)
        assertTrue(branchXs.any { it > root.rect.right }, "At least one branch must be to the right of root")
        assertTrue(branchXs.any { it < root.rect.x }, "At least one branch must be to the left of root")

        val bang = layout.nodes.first { it.node.shape == MindmapShape.Bang }
        assertTrue(
            bang.rect.width * 0.84f > bang.node.label.length * 7.8f,
            "Bang inner valley width should comfortably clear long label text",
        )

        // All edges must attach at horizontal side outlines (pStart.y == pRect.centerY, pEnd.y == cRect.centerY).
        val nodesById = layout.nodes.associateBy { it.node.id }
        for (edge in layout.edges) {
            val fromNode = nodesById.getValue(edge.fromId)
            val toNode = nodesById.getValue(edge.toId)
            assertTrue(
                kotlin.math.abs(edge.points.first().y - fromNode.rect.centerY) < 1f,
                "Mindmap edge start should attach at parent vertical center",
            )
            assertTrue(
                kotlin.math.abs(edge.points.last().y - toNode.rect.centerY) < 1f,
                "Mindmap edge end should attach at child vertical center",
            )
        }

        // No two mindmap nodes may overlap.
        for (i in layout.nodes.indices) {
            for (j in i + 1 until layout.nodes.size) {
                val a = layout.nodes[i]
                val b = layout.nodes[j]
                val overlap = a.rect.x < b.rect.right && b.rect.x < a.rect.right &&
                    a.rect.y < b.rect.bottom && b.rect.y < a.rect.bottom
                assertTrue(!overlap, "Mindmap nodes ${a.node.label} (${a.rect}) and ${b.node.label} (${b.rect}) must not overlap")
            }
        }
    }

    @Test
    fun laysOutDeepTreeIterativelyWithoutStackOverflow() {
        val deepDiagram = MindmapParser.parse(
            buildString {
                appendLine("mindmap")
                for (i in 0 until 2_000) {
                    append(" ".repeat(i + 1))
                    appendLine("N$i")
                }
            },
        )
        val layout = MindmapLayout.layout(deepDiagram)
        assertTrue(layout.width > 0f && layout.height > 0f)
        assertTrue(layout.nodes.size == 2_000)
    }
}
