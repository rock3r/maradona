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

    @Test
    fun visualInvariantsForMultilineActorNestedRectsAndLongSectionLabels() {
        val source = """
            ---
            title: Order Checkout Flow
            ---
            sequenceDiagram
              autonumber
              actor User as Mobile User<br/>(iOS & Android)
              participant UI as Compose UI
              participant VM as ViewModel
              participant API as Backend API
              User->>UI: Tap checkout
              UI->>+VM: Submit cart
              rect rgb(234, 230, 216)
                loop Retry up to 3 times
                  VM->>+API: POST /orders
                  alt Payment authorized
                    rect rgb(211, 229, 245)
                      API-->>-VM: 201 Created
                    end
                  else Gateway timeout or transient 5xx error<br/>requiring exponential backoff
                    API--xVM: 504 Gateway Timeout
                  end
                end
              end
              VM->>VM: Validate receipt
              VM-->>-UI: Order confirmed
        """.trimIndent()
        val diagram = SequenceParser.parse(source)
        assertEquals("Order Checkout Flow", diagram.title)
        val options = SequenceLayoutOptions()
        val layout = SequenceLayout.layout(
            diagram = diagram,
            participantLabelSize = { p ->
                val lines = p.label.split("\n")
                DiagramSize(lines.maxOf { it.length } * 8f, lines.size * 20f)
            },
            messageLabelSize = { _, msg ->
                val lines = msg.text.split("\n")
                DiagramSize(lines.maxOf { it.length } * 7f, lines.size * 18f)
            },
            noteTextSize = { _, note -> DiagramSize(note.text.length * 7f, 18f) },
            blockLabelSize = { _, _, text ->
                val lines = text.split("\n")
                DiagramSize(lines.maxOf { it.length } * 7f, lines.size * 16f)
            },
            titleSize = diagram.title?.let { DiagramSize(it.length * 9f, 22f) },
            options = options,
        )

        // 1. YAML frontmatter title sits strictly above all top participant boxes.
        val titleRect = requireNotNull(layout.titleRect)
        assertTrue(titleRect.bottom < layout.participants.minOf { it.topRect.y })

        // 2. Multiline actor box reserves 48dp stick-figure band + full 2-line label height + wrapPadding.
        val user = layout.participants.first { it.participant.id == "User" }
        assertTrue(user.topRect.height >= 48f * options.density + 40f + options.wrapPadding)

        // 3. Nested blocks are emitted parent-before-child so outer rect draws behind inner rect,
        //    and blocks spanning VM..API stay strictly to the right of UI's lifeline.
        assertEquals(listOf("rect", "loop", "alt", "rect"), layout.blocks.map { it.tag })
        val ui = layout.participants.first { it.participant.id == "UI" }
        val vm = layout.participants.first { it.participant.id == "VM" }
        val api = layout.participants.first { it.participant.id == "API" }
        for (i in 0 until layout.blocks.lastIndex) {
            val parent = layout.blocks[i].rect
            val child = layout.blocks[i + 1].rect
            assertTrue(parent.x < child.x && parent.right > child.right)
            assertTrue(parent.y < child.y && parent.bottom > child.bottom)
            assertEquals(child.x - parent.x, parent.right - child.right, 0.5f)
        }
        assertTrue(layout.blocks.first().rect.x > ui.lifelineX + 20f)

        // 4. Long multiline `else` divider label fits inside `alt` and clears VM's activation bar & API's lifeline.
        val altBlock = layout.blocks[2]
        val divider = altBlock.dividers.single()
        val divLabel = requireNotNull(divider.labelRect)
        assertTrue(divLabel.height > 20f, "Expected 2-line divider label height, got ${divLabel.height}")
        assertTrue(divLabel.x >= altBlock.rect.x && divLabel.right <= altBlock.rect.right)
        val vmActivation = layout.activations.first { it.participantId == "VM" }
        assertTrue(
            divLabel.x >= vmActivation.rect.right + 4f && divLabel.right <= api.lifelineX - 4f,
            "Divider label $divLabel overlaps VM activation ${vmActivation.rect} or API lifeline ${api.lifelineX}",
        )
        val msg504 = layout.messages.first { it.statement.text.contains("504") }
        assertTrue(divLabel.bottom <= msg504.labelRect.y, "Divider label $divLabel overlaps message ${msg504.labelRect}")
    }

    @Test
    fun boxGroupingEnclosesSelectedParticipantsFromHeaderToFooter() {
        val diagram = SequenceParser.parse(
            """
            sequenceDiagram
              participant A
              box rgb(211, 229, 245) Backend Services
                participant B
                participant C
              end
              A->>B: Call
              B->>C: Forward
            """.trimIndent(),
        )
        val layout = SequenceLayout.layout(
            diagram = diagram,
            participantLabelSize = { p -> DiagramSize(p.label.length * 8f, 20f) },
            messageLabelSize = { _, msg -> DiagramSize(msg.text.length * 7f, 18f) },
            noteTextSize = { _, note -> DiagramSize(note.text.length * 7f, 18f) },
            blockLabelSize = { _, _, text -> DiagramSize(text.length * 7f, 16f) },
            boxLabelSize = { _, box -> DiagramSize((box.label?.length ?: 0) * 8f, 18f) },
        )
        assertEquals(1, layout.boxes.size)
        val box = layout.boxes.single()
        val pa = layout.participants[0]
        val pb = layout.participants[1]
        val pc = layout.participants[2]
        assertTrue(box.rect.x > pa.topRect.right, "Box ${box.rect} should not enclose ungrouped participant A ${pa.topRect}")
        assertTrue(box.rect.x < pb.topRect.x && box.rect.right > pc.topRect.right)
        assertTrue(box.rect.y < pb.topRect.y && box.rect.bottom > requireNotNull(pb.bottomRect).bottom)
        val boxLabel = requireNotNull(box.labelRect)
        assertTrue(boxLabel.bottom <= pb.topRect.y)
    }
}
