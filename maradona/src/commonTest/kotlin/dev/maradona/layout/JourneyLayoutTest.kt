package dev.maradona.layout

import dev.maradona.model.JourneyDiagram
import dev.maradona.model.JourneyTask
import dev.maradona.parse.JourneyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JourneyLayoutTest {

    @Test
    fun laysOutActorLegendSectionsTasksAndScoreFacesWithoutOverlap() {
        val diagram = JourneyParser.parse(
            """
            journey
                title My working day
                section Go to work
                  Make tea: 5: Me
                  Go upstairs: 3: Me
                  Do work: 1: Me, Cat
                section Go home
                  Go downstairs: 5: Me
                  Sit down: 5: Me
            """.trimIndent(),
        )

        val layout = JourneyLayout.layout(diagram)
        assertTrue(layout.width > 0f && layout.height > 0f)
        assertEquals(2, layout.actorLegend.size)
        assertEquals(2, layout.sections.size)
        assertEquals(5, layout.tasks.size)

        // Score 5 face must sit higher (smaller y) than score 3, which must sit higher than score 1.
        val t5 = layout.tasks[0]
        val t3 = layout.tasks[1]
        val t1 = layout.tasks[2]
        assertTrue(t5.faceCenter.y < t3.faceCenter.y, "Score 5 face must be higher than score 3 face")
        assertTrue(t3.faceCenter.y < t1.faceCenter.y, "Score 3 face must be higher than score 1 face")

        val boxes = buildList {
            for (al in layout.actorLegend) add("actor:${al.actor}" to al.labelRect)
            for (sec in layout.sections) add("sec:${sec.name}" to sec.rect)
            for (t in layout.tasks) add("task:${t.task.label}" to t.cardRect)
        }
        for (i in boxes.indices) {
            val (nameA, a) = boxes[i]
            assertTrue(a.x >= 0f && a.right <= layout.width + 0.5f && a.y >= 0f && a.bottom <= layout.height + 0.5f, "$nameA out of bounds")
            for (j in i + 1 until boxes.size) {
                val (nameB, b) = boxes[j]
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertTrue(!overlap, "Journey boxes $nameA ($a) and $nameB ($b) must not overlap")
            }
        }

        // Programmatic JourneyDiagram with task-owned actors/sections and empty diagram.actors/sections must include all items.
        val progDiagram = JourneyDiagram(
            tasks = listOf(
                JourneyTask(
                    id = "jt1",
                    label = "Checkout",
                    score = 4.0,
                    actors = listOf("Buyer"),
                    section = "Purchase",
                ),
            ),
        )
        val progLayout = JourneyLayout.layout(progDiagram)
        assertEquals(1, progLayout.actorLegend.size)
        assertEquals(1, progLayout.sections.size)
        assertEquals(1, progLayout.tasks.size)
    }
}
