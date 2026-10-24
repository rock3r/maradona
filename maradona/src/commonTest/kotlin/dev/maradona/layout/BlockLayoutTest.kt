package dev.maradona.layout

import dev.maradona.parse.BlockParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockLayoutTest {

    @Test
    fun laysOutMultiColumnGridSpansAndNestedCompositesWithoutOverlap() {
        val diagram = BlockParser.parse(
            """
            block-beta
              columns 3
              a["Header"]:3
              block:group1:2
                columns 2
                h i j k
              end
              g["Side"]
              group1 --> g
            """.trimIndent(),
        )
        val layout = BlockLayout.layout(diagram)
        val byId = layout.blocks.associateBy { it.item.id }

        val a = byId.getValue("a")
        val group1 = byId.getValue("group1")
        val g = byId.getValue("g")
        val h = byId.getValue("h")
        val i = byId.getValue("i")
        val j = byId.getValue("j")
        val k = byId.getValue("k")

        // Header `a` spans all 3 columns; its left aligns with `group1` and its right aligns with `g`.
        assertEquals(group1.rect.x, a.rect.x, 0.05f)
        assertEquals(g.rect.right, a.rect.right, 0.05f)
        assertTrue(group1.rect.y > a.rect.bottom)

        // `group1` (2 cols) and `g` (1 col) sit on row 1 with `group1.right + pad == g.x`.
        assertEquals(group1.rect.y, g.rect.y, 0.05f)
        assertTrue(g.rect.x > group1.rect.right)

        // Children `h, i, j, k` are strictly inside `group1.rect` in a 2x2 grid.
        for (child in listOf(h, i, j, k)) {
            assertTrue(child.rect.x >= group1.rect.x && child.rect.right <= group1.rect.right + 0.05f)
            assertTrue(child.rect.y >= group1.rect.y && child.rect.bottom <= group1.rect.bottom + 0.05f)
        }
        assertTrue(i.rect.x > h.rect.right)
        assertTrue(j.rect.y > h.rect.bottom)
        assertEquals(h.rect.x, j.rect.x, 0.05f)
        assertEquals(i.rect.x, k.rect.x, 0.05f)

        val edge = layout.edges.single()
        assertEquals("group1", edge.edge.from)
        assertEquals("g", edge.edge.to)
        assertEquals(group1.rect.right, edge.points.first().x, 0.5f)
        assertEquals(g.rect.x, edge.points.last().x, 0.5f)
    }

    @Test
    fun clampsOverflowingSpanConsistentlyAndClipsShapeOutlines() {
        val diagram = BlockParser.parse(
            """
            block
              columns 2
              A[/"LeanRight"/] B["A considerably longer label in clamped span"]:2
              A --> B
              B -- "retry" --> B
            """.trimIndent(),
        )
        val layout = BlockLayout.layout(diagram)
        val byId = layout.blocks.associateBy { it.item.id }
        val a = byId.getValue("A")
        val b = byId.getValue("B")

        // B is at column 1 of a 2-column grid, so its effective span is clamped to 1 column,
        // and its cell width is large enough to hold B's label Rect.
        val bLabelRect = b.labelRect!!
        assertTrue(b.rect.width >= bLabelRect.width)

        // Horizontal edge leaving LeanRight (A) starts inset from A.rect.right (on the slanted right edge).
        val edge = layout.edges[0]
        assertTrue(edge.points.first().x < a.rect.right - 1f)
        assertTrue(edge.points.first().x > a.rect.centerX)

        // Self-edge on B routes outside B's right border and stays within layout.width.
        val selfEdge = layout.edges[1]
        assertEquals(4, selfEdge.points.size)
        assertTrue(selfEdge.points[1].x > b.rect.right)
        assertTrue(selfEdge.labelRect!!.x > b.rect.right)
        assertTrue(selfEdge.labelRect.right <= layout.width)
    }
}
