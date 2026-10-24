package dev.maradona.layout

import dev.maradona.model.ZenUmlMessageKind
import dev.maradona.parse.ZenUmlParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZenUmlLayoutTest {

    @Test
    fun positionsLifelinesNestedActivationsAndControlFrames() {
        val diagram = ZenUmlParser.parse(
            """
            zenuml
              title Checkout Sequence
              @Actor Client
              @Boundary OrderController
              @Database OrderDb
              Client->OrderController.checkout(cartId) {
                order = OrderDb.saveOrder(cartId)
                if (order != null) {
                  return order
                } else {
                  return error
                }
              }
            """.trimIndent(),
        )

        val layout = ZenUmlLayout.layout(diagram)
        assertNotNull(layout.titleRect)
        assertEquals(3, layout.participants.size)

        // Lifelines are ordered left-to-right and participant headers do not overlap
        val p0 = layout.participants[0]
        val p1 = layout.participants[1]
        val p2 = layout.participants[2]
        assertTrue(p0.lifelineX < p1.lifelineX && p1.lifelineX < p2.lifelineX)
        assertTrue(p0.topRect.right < p1.topRect.x)
        assertTrue(p1.topRect.right < p2.topRect.x)

        // Two activations: OrderController.checkout and OrderDb.saveOrder
        assertEquals(2, layout.activations.size)
        val controllerAct = layout.activations.first { it.participantId == "OrderController" }
        val dbAct = layout.activations.first { it.participantId == "OrderDb" }
        assertTrue(controllerAct.rect.y <= dbAct.rect.y && controllerAct.rect.bottom >= dbAct.rect.bottom)

        // Messages include checkout, saveOrder, automatic return of `order`, and the two branch returns
        assertEquals(5, layout.messages.size)
        assertEquals(ZenUmlMessageKind.SYNC, layout.messages[0].kind)
        assertEquals(ZenUmlMessageKind.SYNC, layout.messages[1].kind)
        assertEquals(ZenUmlMessageKind.RETURN, layout.messages[2].kind)

        // Control block encloses the if/else branches with 1 divider
        val blk = layout.blocks.single()
        assertEquals("if", blk.keyword)
        assertEquals("order != null", blk.condition)
        assertEquals(1, blk.dividers.size)
        assertTrue(blk.dividers[0].y > blk.rect.y && blk.dividers[0].y < blk.rect.bottom)
    }

    @Test
    fun handlesAsyncNestedBodiesAndExpandsControlFramesAroundWideSelfCalls() {
        val diagram = ZenUmlParser.parse(
            """
            zenuml
              A->B: start {
                if (ok) {
                  veryLongInternalMethodNameWithManyArguments(arg1, arg2, arg3)
                }
              }
              A->C.done()
            """.trimIndent(),
        )

        val layout = ZenUmlLayout.layout(diagram)
        assertEquals(3, layout.messages.size)
        assertEquals("start", layout.messages[0].displayText)
        assertEquals("veryLongInternalMethodNameWithManyArguments(arg1, arg2, arg3)", layout.messages[1].displayText)
        assertEquals("done()", layout.messages[2].displayText)

        // Control frame encloses the wide self-call label
        val blk = layout.blocks.single()
        val selfCallMsg = layout.messages[1]
        assertTrue(blk.rect.x <= selfCallMsg.labelRect.x && blk.rect.right >= selfCallMsg.labelRect.right)

        // Assigned call with explicit returns inside if/else branches does not synthesize an extra return arrow
        val branched = ZenUmlParser.parse(
            """
            zenuml
              x = A->B.call() {
                // check condition
                if (ok) {
                  return yes
                } else {
                  return no
                }
              }
            """.trimIndent(),
        )
        val branchedLayout = ZenUmlLayout.layout(branched)
        assertEquals(4, branchedLayout.messages.size)
        val blockNote = branchedLayout.messages.first { it.commentText == "check condition" }
        assertNotNull(blockNote.commentRect)
        assertEquals(listOf("x = call()", "", "yes", "no"), branchedLayout.messages.map { it.displayText })
    }

    @Test
    fun scalesWithDensity() {
        val diagram = ZenUmlParser.parse(
            """
            zenuml
              A->B.call() {
                return ok
              }
            """.trimIndent(),
        )
        val l1 = ZenUmlLayout.layout(diagram, options = LayoutOptions(density = 1f))
        val l2 = ZenUmlLayout.layout(diagram, options = LayoutOptions(density = 2f))
        assertTrue(abs(l2.width - l1.width * 2f) < 1f)
        assertTrue(abs(l2.height - l1.height * 2f) < 1f)
    }
}
