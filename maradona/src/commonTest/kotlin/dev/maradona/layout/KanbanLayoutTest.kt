package dev.maradona.layout

import dev.maradona.parse.KanbanParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KanbanLayoutTest {

    @Test
    fun positionsColumnsLeftToRightAndStacksCardsWithoutOverlap() {
        val diagram = KanbanParser.parse(
            """
            kanban
              title "Sprint Board"
              todo[Todo]
                t1[Write parser]@{ ticket: MRD-14, assigned: 'rock3r', priority: 'High' }
                t2[Write layout engine with a longer description that wraps across multiple lines inside the card]@{ priority: 'Very High' }
              inprog[In Progress]
                t3[Render composables]@{ ticket: MRD-15, assigned: 'rock3r', priority: 'Low' }
              done[Done]
            """.trimIndent(),
        )
        val layout = KanbanLayout.layout(diagram)

        assertNotNull(layout.titleRect)
        assertEquals(3, layout.columns.size)

        // Columns increase monotonically left-to-right without overlapping.
        for (i in 0 until layout.columns.size - 1) {
            val cur = layout.columns[i].rect
            val next = layout.columns[i + 1].rect
            assertTrue(cur.right < next.x, "Column $i (${cur.right}) overlapped column ${i + 1} (${next.x})")
        }

        // Tasks inside the first column stack vertically inside the column rect.
        val firstCol = layout.columns[0]
        assertEquals(2, firstCol.tasks.size)
        val c0 = firstCol.tasks[0]
        val c1 = firstCol.tasks[1]
        assertTrue(c0.cardRect.bottom < c1.cardRect.y, "Tasks should stack vertically with a gap")
        assertTrue(c1.cardRect.bottom <= firstCol.rect.bottom, "Tasks must fit inside their column rect")

        // Ticket and assigned rects sit inside the card and do not overlap horizontally.
        val tktRect = assertNotNull(c0.ticketRect)
        val asgRect = assertNotNull(c0.assignedRect)
        assertTrue(tktRect.right <= asgRect.x, "Ticket and assigned badges must not overlap")
        assertTrue(tktRect.x >= c0.cardRect.x && asgRect.right <= c0.cardRect.right)

        // Default measurements scale linearly with LayoutOptions(density = 2f).
        val layout2x = KanbanLayout.layout(diagram, options = LayoutOptions(density = 2f))
        assertEquals(layout.width * 2f, layout2x.width, 0.01f)
        assertEquals(layout.height * 2f, layout2x.height, 0.01f)
    }
}
