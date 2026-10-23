package dev.maradona.parse

import dev.maradona.model.JourneyMood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JourneyParserTest {

    @Test
    fun parsesSectionsTasksScoresActorsAndAccessibility() {
        val src = """
            ---
            title: Fallback Title
            ---
            journey
                title My working day
                accTitle: Working day journey
                accDescr { Tracks morning and evening satisfaction }
                section Go to work
                  Make tea: 5: Me
                  Go upstairs: 3: Me
                  Do work: 1: Me, Cat;
                section Go home
                  Go downstairs: 5: Me # trailing hash comment
                  Sit down: 5
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.JourneyModel>(model)
        val d = model.diagram
        assertEquals("My working day", d.title)
        assertEquals("Working day journey", d.accTitle)
        assertEquals("Tracks morning and evening satisfaction", d.accDescr)
        assertEquals(listOf("Go to work", "Go home"), d.sections)
        assertEquals(listOf("Cat", "Me"), d.actors)
        assertEquals(5, d.tasks.size)

        assertEquals(JourneyMood.Happy, d.tasks[0].mood)
        assertEquals(JourneyMood.Neutral, d.tasks[1].mood)
        assertEquals(JourneyMood.Sad, d.tasks[2].mood)
        assertEquals(listOf("Me", "Cat"), d.tasks[2].actors)
        assertEquals(emptyList(), d.tasks[4].actors)
    }

    @Test
    fun rejectsMalformedTaskWithoutNumericScore() {
        assertFailsWith<IllegalArgumentException> {
            JourneyParser.parse(
                """
                journey
                    Make tea : not_a_number : Me
                """.trimIndent(),
            )
        }
    }

    @Test
    fun supportsTabsSpacedAccessibilityColonsAndHashCommentsWithoutLeadingSpace() {
        val model = MaradonaParser.parse(
            """
            journey
                title	Customer journey
                accTitle : Checkout experience
                accDescr {
                  Completion #1 }
                section	Checkout
                  Pay invoice: 5# completed
                  Send receipt: 5: Buyer# primary
            """.trimIndent(),
        )
        assertIs<MaradonaDiagramModel.JourneyModel>(model)
        val d = model.diagram
        assertEquals("Customer journey", d.title)
        assertEquals("Checkout experience", d.accTitle)
        assertEquals("Completion #1", d.accDescr)
        assertEquals(listOf("Checkout"), d.sections)
        assertEquals(2, d.tasks.size)
        assertEquals(5.0, d.tasks[0].score)
        assertEquals(emptyList(), d.tasks[0].actors)
        assertEquals(listOf("Buyer"), d.tasks[1].actors)
    }
}
