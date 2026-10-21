package dev.maradona.layout

import dev.maradona.parse.SequenceParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SequenceLayoutTest {

    private fun layoutOf(source: String): SequenceDiagramLayout {
        val diagram = SequenceParser.parse(source)
        return SequenceLayout.layout(
            diagram = diagram,
            participantLabelSize = { p -> DiagramSize(p.label.length * 8f, 20f) },
            messageLabelSize = { _, msg -> DiagramSize(msg.text.length * 7f, 18f) },
            noteTextSize = { _, note -> DiagramSize(note.text.length * 7f, 18f) },
            blockLabelSize = { _, _, text -> DiagramSize(text.length * 7f, 16f) },
            titleSize = diagram.title?.let { DiagramSize(it.length * 9f, 22f) },
        )
    }

    @Test
    fun participantsOrderedLeftToRightAndMessagesOrderedTopToBottom() {
        val layout = layoutOf(
            """
            sequenceDiagram
              participant A as Client
              participant B as Server
              participant C as Database
              A->>+B: Request
              B->>C: Query
              C-->>B: Rows
              B-->>-A: Response
              Note over A,B: Cached
            """.trimIndent(),
        )
        assertEquals(3, layout.participants.size)
        assertTrue(layout.participants[0].lifelineX < layout.participants[1].lifelineX)
        assertTrue(layout.participants[1].lifelineX < layout.participants[2].lifelineX)
        assertEquals(4, layout.messages.size)
        for (i in 0 until layout.messages.lastIndex) {
            assertTrue(layout.messages[i].lineY < layout.messages[i + 1].lineY)
        }
        assertEquals(1, layout.activations.size)
        assertEquals("B", layout.activations.single().participantId)
        assertEquals(1, layout.notes.size)
        assertTrue(layout.width > 0f && layout.height > 0f)
    }

    @Test
    fun selfMessageAndBlocksExpandBounds() {
        val layout = layoutOf(
            """
            sequenceDiagram
              A->>A: Self check
              loop Poll
                A->>B: Ping
              end
            """.trimIndent(),
        )
        assertTrue(layout.messages.first().isSelf)
        assertEquals(1, layout.blocks.size)
        val block = layout.blocks.single().rect
        assertTrue(block.x >= 0f && block.right <= layout.width && block.bottom <= layout.height)
    }
}
