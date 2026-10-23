package dev.maradona.layout

import dev.maradona.parse.GanttParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GanttLayoutTest {

    @Test
    fun laysOutSectionsExcludedBandsMilestonesAndVertWithoutOverlap() {
        val diagram = GanttParser.parse(
            """
            gantt
                title Project Schedule
                dateFormat YYYY-MM-DD
                excludes weekends
                section Backend
                    Schema design  :done,   b1, 2026-10-01, 4d
                    API handlers   :active, b2, after b1, 4d
                section Frontend
                    UI components  :crit,   f1, 2026-10-05, 5d
                    Launch         :milestone, m1, after f1, 0d
                    Freeze         :vert,   v1, 2026-10-08, 0d
            """.trimIndent(),
        )

        val layout = GanttLayout.layout(diagram)
        assertTrue(layout.width > 0f && layout.height > 0f)
        assertEquals(2, layout.sections.size)
        assertTrue(layout.excludedBands.isNotEmpty(), "Expected weekend exclusion bands")
        assertEquals(4, layout.tasks.size)
        assertEquals(1, layout.verticalMarkers.size)
        assertTrue(layout.ticks.size >= 2)

        // All task bars and labels must stay within plotRect horizontally.
        for (pt in layout.tasks) {
            assertTrue(
                pt.barRect.x >= layout.plotRect.x && pt.barRect.right <= layout.plotRect.right + 0.5f,
                "Gantt bar ${pt.task.id} (${pt.barRect}) must stay inside plotRect (${layout.plotRect})",
            )
            assertTrue(
                pt.labelRect.x >= layout.plotRect.x && pt.labelRect.right <= layout.plotRect.right + 0.5f,
                "Gantt label ${pt.task.id} (${pt.labelRect}) must stay inside plotRect (${layout.plotRect})",
            )
        }

        // Task bars on distinct rows in non-compact mode must not overlap vertically.
        for (i in layout.tasks.indices) {
            for (j in i + 1 until layout.tasks.size) {
                val a = layout.tasks[i]
                val b = layout.tasks[j]
                val overlap = a.barRect.x < b.barRect.right && b.barRect.x < a.barRect.right &&
                    a.barRect.y < b.barRect.bottom && b.barRect.y < a.barRect.bottom
                assertTrue(!overlap, "Gantt bars ${a.task.id} and ${b.task.id} must not overlap")
            }
        }
    }

    @Test
    fun compactModePacksNonOverlappingTasksAndPreventsLabelCollision() {
        val diagram = GanttParser.parse(
            """
            ---
            displayMode: compact
            ---
            gantt
                dateFormat YYYY-MM-DD
                section Section
                A task       :a1, 2014-01-01, 10d
                Overlapping  :a2, 2014-01-05, 10d
                Later task   :a3, 2014-02-01, 10d
            """.trimIndent(),
        )

        val layout = GanttLayout.layout(diagram)
        val a1 = assertNotNull(layout.task("a1"))
        val a2 = assertNotNull(layout.task("a2"))
        val a3 = assertNotNull(layout.task("a3"))

        assertTrue(a1.rowIndex != a2.rowIndex, "Overlapping tasks a1 and a2 must be on different rows")
        assertEquals(a1.rowIndex, a3.rowIndex, "Far-apart non-overlapping tasks a1 and a3 should share row in compact mode")
        assertTrue(
            minOf(a3.barRect.x, a3.labelRect.x) > maxOf(a1.barRect.right, a1.labelRect.right),
            "Packed task a3 must start strictly to the right of a1's bar and label",
        )
    }

    @Test
    fun alignsWeeklyTicksToWeekdayAndKeepsWideTitlesAndVertLabelsInBounds() {
        val diagram = GanttParser.parse(
            """
            gantt
                title A Very Wide Gantt Chart Title That Exceeds Default Plot Width When Rendered
                dateFormat YYYY-MM-DD
                tickInterval 1week
                weekday monday
                Task A :a, 2024-01-01, 21d
                Early Vert Marker With Long Label :vert, v1, 2024-01-01, 0d
            """.trimIndent(),
        )
        val layout = GanttLayout.layout(
            diagram = diagram,
            titleSize = DiagramSize(1200f, 24f),
        )
        val titleRect = assertNotNull(layout.titleRect)
        assertTrue(titleRect.x >= 0f && titleRect.right <= layout.width + 0.5f, "Wide title must fit within layout.width")

        for (vt in layout.verticalMarkers) {
            assertTrue(vt.labelRect.x >= 0f && vt.labelRect.right <= layout.width + 0.5f, "Vert label must stay within layout.width")
        }
        for (tick in layout.ticks) {
            val dow = GanttParser.dayOfWeek(tick.timeMs.floorDiv(GanttParser.DAY_MS))
            assertEquals(1, dow, "Weekly ticks with weekday monday must align to Monday (dow=1), got dow=$dow for ${tick.label}")
        }
    }
}
