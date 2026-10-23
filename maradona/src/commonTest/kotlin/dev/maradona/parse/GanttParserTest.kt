package dev.maradona.parse

import dev.maradona.model.GanttTaskStatus
import dev.maradona.model.GanttWeekendStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GanttParserTest {

    @Test
    fun parsesSectionsTagsDependenciesForwardUntilAndExcludes() {
        val src = """
            gantt
                dateFormat  YYYY-MM-DD
                title       Adding GANTT diagram functionality to mermaid
                excludes    weekends

                section A section
                Completed task            :done,    des1, 2014-01-06,2014-01-08
                Active task               :active,  des2, 2014-01-09, 3d
                Future task               :         des3, after des2, 5d
                Future task2              :         des4, after des3, 5d

                section Critical tasks
                Completed task in the critical line :crit, done, 2014-01-06,24h
                Implement parser and jison          :crit, done, after des1, 2d
                Create tests for parser             :crit, active, 3d
                Future task in critical line        :crit, 5d
                Create tests for renderer           :2d
                Add to mermaid                      :until isadded
                Functionality added                 :milestone, isadded, 2014-01-25, 0d
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.GanttModel>(model)
        val d = model.diagram
        assertEquals("Adding GANTT diagram functionality to mermaid", d.title)
        assertEquals(listOf("A section", "Critical tasks"), d.sections)
        assertEquals(listOf("weekends"), d.excludes)

        val des1 = assertNotNull(d.task("des1"))
        assertEquals(GanttTaskStatus.Done, des1.status)
        assertEquals(GanttParser.parseTimestamp("2014-01-06", "YYYY-MM-DD"), des1.startMs)
        assertEquals(GanttParser.parseTimestamp("2014-01-08", "YYYY-MM-DD"), des1.endMs)

        // 2014-01-09 is Thursday; 3 working days skipping Sat/Sun ends on Tuesday 2014-01-14.
        val des2 = assertNotNull(d.task("des2"))
        assertEquals(GanttTaskStatus.Active, des2.status)
        assertEquals(GanttParser.parseTimestamp("2014-01-14", "YYYY-MM-DD"), des2.endMs)

        // Forward reference `:until isadded` resolves to isadded's start date (2014-01-25).
        val isadded = assertNotNull(d.task("isadded"))
        assertTrue(isadded.isMilestone)
        val addToMermaid = d.tasks.first { it.label == "Add to mermaid" }
        assertEquals(isadded.startMs, addToMermaid.endMs)
    }

    @Test
    fun parsesMultiTargetAfterAndUntilAndTimeAndEpochAndFrontmatter() {
        val multiDep = GanttParser.parse(
            """
            gantt
                apple :a, 2017-07-20, 1w
                banana :crit, b, 2017-07-23, 1d
                cherry :active, c, after b a, 1d
                kiwi   :d, 2017-07-20, until b c
            """.trimIndent(),
        )
        val a = assertNotNull(multiDep.task("a"))
        val b = assertNotNull(multiDep.task("b"))
        val c = assertNotNull(multiDep.task("c"))
        val d = assertNotNull(multiDep.task("d"))
        assertEquals(a.endMs, c.startMs, "after b a should start at max(end(b), end(a))")
        assertEquals(b.startMs, d.endMs, "until b c should end at min(start(b), start(c))")

        val fridayWeekend = GanttParser.parse(
            """
            gantt
                dateFormat YYYY-MM-DD
                excludes weekends
                weekend friday
                A task :a1, 2024-01-04, 2d
            """.trimIndent(),
        )
        assertEquals(GanttWeekendStart.Friday, fridayWeekend.weekend)
        // 2024-01-04 is Thursday; with Friday+Saturday weekend, day 2 of work is Sunday 2024-01-07 (ends 2024-01-08).
        assertEquals(
            GanttParser.parseTimestamp("2024-01-08", "YYYY-MM-DD"),
            assertNotNull(fridayWeekend.task("a1")).endMs,
        )

        val frontmatterGantt = GanttParser.parse(
            """
            ---
            title: Ignored if specified in chart
            displayMode: compact
            config:
              themeCSS: "
                #item36 { fill: CadetBlue }
              "
              gantt:
                topAxis: true
            ---
            gantt
                title Timeline - Gantt Sampler
                dateFormat YYYY
                axisFormat %y
                section Issue19062
                71 : item71, 1900, 1930
                9  : vert, 1906, 1s
            """.trimIndent(),
        )
        assertEquals("Timeline - Gantt Sampler", frontmatterGantt.title)
        assertTrue(frontmatterGantt.compact)
        assertTrue(frontmatterGantt.topAxis)
        assertEquals(2, frontmatterGantt.tasks.size)
        assertTrue(frontmatterGantt.tasks.last().isVerticalMarker)
    }

    @Test
    fun supportsCustomDateFormatsFractionalWorkingDurationsAxisFormatsAndRejectsCycles() {
        val compactInit = GanttParser.parse(
            """
            %%{init: {'gantt': {'displayMode': 'compact', 'topAxis': true}}}%%
            gantt
                dateFormat YYYYMMDD
                A :a, 20261001, 2d
            """.trimIndent(),
        )
        assertTrue(compactInit.compact)
        assertTrue(compactInit.topAxis)
        val aTask = assertNotNull(compactInit.task("a"))
        assertEquals(GanttParser.daysFromCivil(2026, 10, 1) * GanttParser.DAY_MS, aTask.startMs)
        assertEquals(GanttParser.daysFromCivil(2026, 10, 3) * GanttParser.DAY_MS, aTask.endMs)

        // Fractional duration 1.5d starting Friday 2024-01-05 with weekends excluded ends Monday 2024-01-08 12:00.
        val frac = GanttParser.parse(
            """
            gantt
                dateFormat YYYY-MM-DD
                excludes weekends
                A :a, 2024-01-05, 1.5d
            """.trimIndent(),
        )
        val fracTask = assertNotNull(frac.task("a"))
        assertEquals(
            GanttParser.daysFromCivil(2024, 1, 8) * GanttParser.DAY_MS + 12L * 3_600_000L,
            fracTask.endMs,
        )

        // Axis format directives (%m-%d %H:%M and %b %d).
        val ts = GanttParser.daysFromCivil(2024, 1, 5) * GanttParser.DAY_MS + (9L * 3600L + 30L * 60L) * 1000L
        assertEquals("01-05 09:30", GanttParser.formatTickLabel(ts, "YYYY-MM-DD HH:mm", "%m-%d %H:%M"))
        assertEquals("Jan 05", GanttParser.formatTickLabel(ts, "YYYY-MM-DD", "%b %d"))

        // Cyclic dependency must fail rather than returning arbitrary dates.
        assertFailsWith<IllegalArgumentException> {
            GanttParser.parse(
                """
                gantt
                    A :a, after b, 1d
                    B :b, after a, 1d
                """.trimIndent(),
            )
        }

        // Plain YAML scalar containing an embedded double quote (e.g. `6" screen`) must not swallow closing `---`.
        val withQuoteInPlainYaml = MaradonaParser.parse(
            """
            ---
            title: 6" screen rollout
            ---
            gantt
                dateFormat YYYY-MM-DD
                A :a, 2024-01-01, 2d
            """.trimIndent(),
        )
        assertIs<MaradonaDiagramModel.GanttModel>(withQuoteInPlainYaml)
        assertEquals("6\" screen rollout", withQuoteInPlainYaml.diagram.title)
    }
}
