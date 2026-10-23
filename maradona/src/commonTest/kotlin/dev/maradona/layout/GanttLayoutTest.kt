package dev.maradona.layout

import dev.maradona.model.GanttDiagram
import dev.maradona.model.GanttTask
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

        // Programmatic GanttDiagram with task-owned sections and empty diagram.sections must include all tasks.
        val progDiagram = GanttDiagram(
            tasks = listOf(
                GanttTask(
                    id = "t1",
                    label = "Backend Task",
                    section = "Backend",
                    startMs = 0L,
                    endMs = GanttParser.DAY_MS * 2L,
                ),
            ),
        )
        val progLayout = GanttLayout.layout(progDiagram)
        assertEquals(1, progLayout.tasks.size)
        assertEquals(1, progLayout.sections.size)
        assertEquals("Backend", progLayout.sections.single().name)

        // Millisecond epoch charts (dateFormat x) must generate multiple sub-day ticks.
        val msEpochDiagram = GanttParser.parse(
            """
            gantt
                dateFormat x
                A :a, 0, 10000
            """.trimIndent(),
        )
        val msEpochLayout = GanttLayout.layout(msEpochDiagram)
        assertTrue(msEpochLayout.ticks.size >= 4, "Expected sub-day millisecond ticks for dateFormat x, got ${msEpochLayout.ticks.map { it.label }}")

        // Tall/multiline task labels must expand row heights so adjacent labels never overlap vertically.
        val tallLayout = GanttLayout.layout(
            diagram = GanttParser.parse(
                """
                gantt
                    dateFormat YYYY-MM-DD
                    Task 1 :t1, 2024-01-01, 2d
                    Task 2 :t2, 2024-01-01, 2d
                """.trimIndent(),
            ),
            taskLabelSize = { DiagramSize(120f, 56f) },
        )
        val t1Pos = assertNotNull(tallLayout.task("t1"))
        val t2Pos = assertNotNull(tallLayout.task("t2"))
        assertTrue(t1Pos.labelRect.bottom < t2Pos.labelRect.y, "Tall task labels on adjacent rows must not overlap vertically")

        // `tickInterval 4weeks` starting 2024-01-01 must step by 28 days (2024-01-29), not calendar 1st-of-month.
        val fourWeekDiagram = GanttParser.parse(
            """
            gantt
                dateFormat YYYY-MM-DD
                tickInterval 4weeks
                A :a, 2024-01-01, 60d
            """.trimIndent(),
        )
        val fourWeekLabels = GanttLayout.candidateTicks(fourWeekDiagram).map { it.second }
        assertTrue("2024-01-29" in fourWeekLabels, "Expected 28-day tick 2024-01-29 in $fourWeekLabels")

        // `tickInterval 2months` starting 2024-01-01 must advance by 2 calendar months (Jan, Mar, May, Jul).
        val twoMonthDiagram = GanttParser.parse(
            """
            gantt
                dateFormat YYYY-MM-DD
                tickInterval 2months
                A :a, 2024-01-01, 2024-07-01
            """.trimIndent(),
        )
        val twoMonthLabels = GanttLayout.candidateTicks(twoMonthDiagram).map { it.second }
        assertEquals(listOf("2024-01-01", "2024-03-01", "2024-05-01", "2024-07-01"), twoMonthLabels)

        // Zero-length `tickInterval 0ms` must fall back to positive automatic ticks without dividing by zero.
        val zeroTickDiagram = GanttParser.parse(
            """
            gantt
                dateFormat x
                tickInterval 0ms
                A :a, 0, 10
            """.trimIndent(),
        )
        val zeroTickLayout = GanttLayout.layout(zeroTickDiagram)
        assertTrue(zeroTickLayout.ticks.isNotEmpty())

        // Year-long chart with `tickInterval 1week` and `weekday monday` must keep all thinned ticks on Monday.
        val yearWeeklyDiagram = GanttParser.parse(
            """
            gantt
                dateFormat YYYY-MM-DD
                tickInterval 1week
                weekday monday
                YearTask :y1, 2024-01-01, 2025-01-01
            """.trimIndent(),
        )
        val yearWeeklyTicks = GanttLayout.candidateTicks(yearWeeklyDiagram)
        // All ticks except a trailing end-of-range tick must land on Monday (dow == 1).
        for ((ms, lbl) in yearWeeklyTicks.dropLast(1)) {
            assertEquals(1, GanttParser.dayOfWeek(ms.floorDiv(GanttParser.DAY_MS)), "Expected Monday for thinned weekly tick $lbl")
        }

        // Intraday `YYYY-MM-DD HH:mm` chart (1-hour span) must span its actual 1-hour window and generate sub-day ticks.
        val intradayDiagram = GanttParser.parse(
            """
            gantt
                dateFormat YYYY-MM-DD HH:mm
                axisFormat %H:%M
                Morning :m1, 2024-01-01 09:00, 2024-01-01 10:00
            """.trimIndent(),
        )
        val intradayLayout = GanttLayout.layout(intradayDiagram)
        val m1Pos = assertNotNull(intradayLayout.task("m1"))
        assertTrue(m1Pos.barRect.width > 400f, "Intraday 1h task should span the full timeline track, got width=${m1Pos.barRect.width}")
        assertTrue(intradayLayout.ticks.size >= 4, "Expected sub-hour ticks for 1h intraday chart, got ${intradayLayout.ticks.map { it.label }}")
    }
}
