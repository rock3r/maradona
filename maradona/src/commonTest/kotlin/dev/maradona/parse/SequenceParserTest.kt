package dev.maradona.parse

import dev.maradona.model.NotePlacement
import dev.maradona.model.ParticipantKind
import dev.maradona.model.SequenceBlockKind
import dev.maradona.model.SequenceHead
import dev.maradona.model.SequenceStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SequenceParserTest {

    @Test
    fun participantsMessagesActivationsAndNotes() {
        val diagram = SequenceParser.parse(
            """
            ---
            title: Order Flow
            ---
            sequenceDiagram
              autonumber
              actor Customer
              participant Web as Web app
              participant API as API gateway
              Customer->>+Web: Place order
              Web->>API: POST /orders
              activate API
              API-->>Web: 201 Created
              deactivate API
              Web-->>-Customer: Order confirmed
              Note over Customer,API: One order, one transaction
            """.trimIndent(),
        )
        assertEquals("Order Flow", diagram.title)
        assertTrue(diagram.autonumber)
        assertEquals(listOf("Customer", "Web", "API"), diagram.participants.map { it.id })
        assertEquals(ParticipantKind.Actor, diagram.participants[0].kind)
        assertEquals("Web app", diagram.participants[1].label)
        assertEquals("API gateway", diagram.participants[2].label)

        val firstMsg = diagram.statements[0] as SequenceStatement.Message
        assertEquals("Customer", firstMsg.from)
        assertEquals("Web", firstMsg.to)
        assertTrue(firstMsg.activateTarget)
        assertEquals(SequenceHead.Filled, firstMsg.arrow.head)

        val note = diagram.statements.last() as SequenceStatement.Note
        assertEquals(NotePlacement.Over, note.placement)
        assertEquals(listOf("Customer", "API"), note.participants)
        assertEquals("One order, one transaction", note.text)
    }

    @Test
    fun blocksAndArrowVariants() {
        val diagram = SequenceParser.parse(
            """
            sequenceDiagram
              Alice->Bob: Open arrow
              Bob-->Alice: Dashed open
              Alice-xBob: Cross
              Bob--)Alice: Async
              Alice<<-->>Bob: Bidirectional
              alt Valid
                Bob->>Alice: OK
              else Invalid
                Bob--xAlice: Error
              end
            """.trimIndent(),
        )
        assertEquals(2, diagram.participants.size)
        val m0 = diagram.statements[0] as SequenceStatement.Message
        val m1 = diagram.statements[1] as SequenceStatement.Message
        val m2 = diagram.statements[2] as SequenceStatement.Message
        val m3 = diagram.statements[3] as SequenceStatement.Message
        val m4 = diagram.statements[4] as SequenceStatement.Message
        assertEquals(SequenceHead.Open, m0.arrow.head)
        assertTrue(m1.arrow.dashed)
        assertEquals(SequenceHead.Cross, m2.arrow.head)
        assertEquals(SequenceHead.Async, m3.arrow.head)
        assertTrue(m4.arrow.bidirectional)

        val block = diagram.statements[5] as SequenceStatement.Block
        assertEquals(SequenceBlockKind.Alt, block.kind)
        assertEquals("Valid", block.label)
        assertEquals(2, block.sections.size)
        assertEquals("Invalid", block.sections[1].label)
    }

    @Test
    fun topLevelDispatcherRecognizesBothTypes() {
        val seq = MaradonaParser.parse("sequenceDiagram\n  A->>B: hi")
        val flow = MaradonaParser.parse("flowchart TD\n  A --> B")
        assertTrue(seq is MaradonaDiagramModel.SequenceModel)
        assertTrue(flow is MaradonaDiagramModel.FlowchartModel)
    }

    @Test
    fun semicolonDelimitedStatementsAndParticipantBoxes() {
        val diagram = SequenceParser.parse(
            """
            sequenceDiagram; participant A as Alice;
              box Purple Team B
                participant B
                participant C
              end
              A->>B: first#59;second; B->>C: "two; still two"; C-->>A: A &amp; B
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B", "C"), diagram.participants.map { it.id })
        assertEquals(1, diagram.boxes.size)
        val box = diagram.boxes.single()
        assertEquals("Purple", box.color)
        assertEquals("Team B", box.label)
        assertEquals(listOf("B", "C"), box.participantIds)
        assertEquals(3, diagram.statements.size)
        val m0 = diagram.statements[0] as SequenceStatement.Message
        val m1 = diagram.statements[1] as SequenceStatement.Message
        val m2 = diagram.statements[2] as SequenceStatement.Message
        assertEquals("first;second", m0.text)
        assertEquals("two; still two", m1.text)
        assertEquals("A & B", m2.text)
    }
}
