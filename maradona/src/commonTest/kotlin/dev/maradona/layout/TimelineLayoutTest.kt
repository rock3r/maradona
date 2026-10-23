package dev.maradona.layout

import dev.maradona.parse.TimelineParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineLayoutTest {

    @Test
    fun laysOutHorizontalAndVerticalTimelinesWithoutCardOverlaps() {
        val lrDiagram = TimelineParser.parse(
            """
            timeline
                title Industrial Revolution
                section 17th-20th century
                    Industry 1.0 : Machinery, Water power, Steam <br>power
                    Industry 2.0 : Electricity, Mass production
                section 21st century
                    Industry 4.0 : Internet
                                 : Robotics
                    Industry 5.0 : Artificial intelligence
            """.trimIndent(),
        )

        val lrLayout = TimelineLayout.layout(lrDiagram)
        assertTrue(lrLayout.width > 0f && lrLayout.height > 0f)
        assertEquals(2, lrLayout.sections.size)
        assertEquals(4, lrLayout.periods.size)

        val lrBoxes = buildList {
            for (s in lrLayout.sections) add("sec:${s.name}" to s.rect)
            for (p in lrLayout.periods) {
                add("period:${p.period.title}" to p.rect)
                for (ev in p.events) add("ev:${p.period.id}:${ev.eventIndex}" to ev.rect)
            }
        }
        for (i in lrBoxes.indices) {
            val (nameA, a) = lrBoxes[i]
            assertTrue(a.x >= 0f && a.right <= lrLayout.width + 0.5f && a.y >= 0f && a.bottom <= lrLayout.height + 0.5f, "$nameA out of bounds")
            for (j in i + 1 until lrBoxes.size) {
                val (nameB, b) = lrBoxes[j]
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertTrue(!overlap, "Horizontal timeline boxes $nameA ($a) and $nameB ($b) must not overlap")
            }
        }

        val tdDiagram = TimelineParser.parse(
            """
            timeline TD
                title Vertical Timeline
                section Phase 1
                    Step A : Task 1 : Task 2
                    Step B : Task 3
            """.trimIndent(),
        )
        val tdLayout = TimelineLayout.layout(tdDiagram)
        assertEquals(1, tdLayout.sections.size)
        assertEquals(2, tdLayout.periods.size)
        assertTrue(tdLayout.periods[0].rect.bottom < tdLayout.periods[1].rect.y, "In TD timeline, Step A must be above Step B")
    }
}
