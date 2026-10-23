package dev.maradona.layout

import dev.maradona.parse.QuadrantParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuadrantLayoutTest {

    @Test
    fun laysOutQuadrantsAxesAndPointsWithoutLabelOverlaps() {
        val diagram = QuadrantParser.parse(
            """
            quadrantChart
                title Reach and engagement of campaigns
                x-axis Low Reach --> High Reach
                y-axis Low Engagement --> High Engagement
                quadrant-1 We should expand
                quadrant-2 Need to promote
                quadrant-3 Re-evaluate
                quadrant-4 May be improved
                Campaign A: [0.3, 0.6]
                Campaign B: [0.45, 0.23]
                Campaign C: [0.57, 0.69]
                Campaign D: [0.78, 0.34]
                Campaign E: [0.40, 0.34]
                Campaign F: [0.35, 0.78]
            """.trimIndent(),
        )

        val layout = QuadrantLayout.layout(diagram)
        assertTrue(layout.width > 0f && layout.height > 0f)
        assertEquals(4, layout.quadrants.size)
        assertEquals(4, layout.axisLabels.size)
        assertEquals(6, layout.points.size)

        // Q1 is top-right, Q2 is top-left, Q3 is bottom-left, Q4 is bottom-right.
        val q1 = layout.quadrants.first { it.quadrantNumber == 1 }
        val q2 = layout.quadrants.first { it.quadrantNumber == 2 }
        val q3 = layout.quadrants.first { it.quadrantNumber == 3 }
        val q4 = layout.quadrants.first { it.quadrantNumber == 4 }
        assertTrue(q1.rect.x > q2.rect.x && q1.rect.y == q2.rect.y)
        assertTrue(q3.rect.x == q2.rect.x && q3.rect.y > q2.rect.y)
        assertTrue(q4.rect.x > q3.rect.x && q4.rect.y == q3.rect.y)

        // Campaign C [0.57, 0.69] must be in Q1 (top-right).
        val cPt = assertNotNull(layout.point("Campaign C"))
        assertTrue(cPt.center.x > q1.rect.x && cPt.center.x < q1.rect.right)
        assertTrue(cPt.center.y > q1.rect.y && cPt.center.y < q1.rect.bottom)

        // All point labels and quadrant labels must be within bounds and never overlap each other.
        val textRects = buildList {
            for (q in layout.quadrants) q.labelRect?.let { add("q${q.quadrantNumber}" to it) }
            for (p in layout.points) add("pt:${p.point.label}" to p.labelRect)
        }
        for (i in textRects.indices) {
            val (nameA, a) = textRects[i]
            assertTrue(a.x >= 0f && a.right <= layout.width + 0.5f && a.y >= 0f && a.bottom <= layout.height + 0.5f, "$nameA out of bounds")
            for (j in i + 1 until textRects.size) {
                val (nameB, b) = textRects[j]
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertTrue(!overlap, "Quadrant labels $nameA ($a) and $nameB ($b) must not overlap")
            }
        }

        // Twenty coincident points must still receive non-overlapping labels.
        val denseSrc = buildString {
            appendLine("quadrantChart")
            for (i in 1..20) {
                appendLine("P$i: [0.5, 0.5]")
            }
        }
        val denseLayout = QuadrantLayout.layout(QuadrantParser.parse(denseSrc))
        assertEquals(20, denseLayout.points.size)
        for (i in denseLayout.points.indices) {
            for (j in i + 1 until denseLayout.points.size) {
                val a = denseLayout.points[i].labelRect
                val b = denseLayout.points[j].labelRect
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertTrue(!overlap, "Coincident point labels $a and $b must not overlap")
            }
        }
    }
}
