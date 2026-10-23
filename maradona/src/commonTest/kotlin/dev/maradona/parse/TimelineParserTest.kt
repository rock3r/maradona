package dev.maradona.parse

import dev.maradona.model.TimelineDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineParserTest {

    @Test
    fun parsesSectionsPeriodsContinuationEventsAndDirections() {
        val src = """
            ---
            config:
              timeline:
                disableMulticolor: true
            ---
            timeline TD
                title MermaidChart 2023 Timeline
                section 2023 Q1 <br> Release Personal Tier
                    Bullet 1 : sub-point 1a : sub-point 1b
                             : sub-point 1c
                    Bullet 2 : sub-point 2a : sub-point 2b
                section 2023 Q2 <br> Release XYZ Tier
                    Bullet 3 : sub-point <br> 3a : sub-point 3b
                             : sub-point 3c
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.TimelineModel>(model)
        val d = model.diagram
        assertEquals("MermaidChart 2023 Timeline", d.title)
        assertEquals(TimelineDirection.TD, d.direction)
        assertTrue(d.disableMulticolor)
        assertEquals(listOf("2023 Q1 \n Release Personal Tier", "2023 Q2 \n Release XYZ Tier"), d.sections)
        assertEquals(3, d.periods.size)
        assertEquals("Bullet 1", d.periods[0].title)
        assertEquals(listOf("sub-point 1a", "sub-point 1b", "sub-point 1c"), d.periods[0].events)
        assertEquals(listOf("sub-point \n 3a", "sub-point 3b", "sub-point 3c"), d.periods[2].events)
    }

    @Test
    fun rejectsOrphanEventContinuationWithoutPrecedingPeriod() {
        assertFailsWith<IllegalArgumentException> {
            TimelineParser.parse(
                """
                timeline
                    : Orphan event
                """.trimIndent(),
            )
        }
    }

    @Test
    fun preservesApostrophesUrlAndTimeColonsAndInlineAccDescrBlocks() {
        val d = TimelineParser.parse(
            """
            timeline
                accDescr { A release timeline }
                Company's launch : First release
                2025 : Customer's feedback : Visit https://example.com : Starts at 10:30
            """.trimIndent(),
        )
        assertEquals("A release timeline", d.accDescr)
        assertEquals(2, d.periods.size)
        assertEquals("Company's launch", d.periods[0].title)
        assertEquals(listOf("First release"), d.periods[0].events)
        assertEquals("2025", d.periods[1].title)
        assertEquals(
            listOf("Customer's feedback", "Visit https://example.com", "Starts at 10:30"),
            d.periods[1].events,
        )
    }
}
