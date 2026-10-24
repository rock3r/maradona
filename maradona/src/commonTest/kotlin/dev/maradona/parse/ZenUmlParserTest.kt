package dev.maradona.parse

import dev.maradona.model.ZenUmlControlKind
import dev.maradona.model.ZenUmlMessageKind
import dev.maradona.model.ZenUmlParticipantStereotype
import dev.maradona.model.ZenUmlStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZenUmlParserTest {

    @Test
    fun parsesParticipantsGroupsNestedSyncCallsAndControlBlocks() {
        val diagram = ZenUmlParser.parse(
            """
            zenuml
              title Order Service Flow
              @Actor Client
              group "Backend Services" {
                @Boundary OrderController as "Order API"
                @Database OrderDb
              }
              // Client initiates checkout
              Client->OrderController.checkout(cartId) {
                session = new CheckoutSession(cartId)
                order = OrderDb.saveOrder(cartId)
                if (order != null) {
                  OrderController->Client: Order Confirmed
                  return order
                } else if (retryable) {
                  retryCheckout(cartId)
                } else {
                  return null
                }
              }
            """.trimIndent(),
        )

        assertEquals("Order Service Flow", diagram.title)
        assertEquals(1, diagram.groups.size)
        assertEquals("Backend Services", diagram.groups[0].name)
        assertEquals(listOf("OrderController", "OrderDb"), diagram.groups[0].participantIds)

        assertEquals(4, diagram.participants.size)
        assertEquals(ZenUmlParticipantStereotype.ACTOR, diagram.participants[0].stereotype)
        assertEquals("Order API", diagram.participants[1].label)
        assertEquals(ZenUmlParticipantStereotype.BOUNDARY, diagram.participants[1].stereotype)
        assertEquals(ZenUmlParticipantStereotype.DATABASE, diagram.participants[2].stereotype)
        assertEquals("CheckoutSession", diagram.participants[3].id)

        val rootMsg = diagram.statements.single() as ZenUmlStatement.Message
        assertEquals("Client", rootMsg.from)
        assertEquals("OrderController", rootMsg.to)
        assertEquals("checkout(cartId)", rootMsg.text)
        assertEquals("Client initiates checkout", rootMsg.comment)
        assertEquals(3, rootMsg.nestedStatements.size)

        val createMsg = rootMsg.nestedStatements[0] as ZenUmlStatement.Message
        assertEquals(ZenUmlMessageKind.CREATE, createMsg.kind)
        assertEquals("OrderController", createMsg.from)
        assertEquals("CheckoutSession", createMsg.to)
        assertEquals("session", createMsg.returnVar)

        val saveMsg = rootMsg.nestedStatements[1] as ZenUmlStatement.Message
        assertEquals(ZenUmlMessageKind.SYNC, saveMsg.kind)
        assertEquals("OrderController", saveMsg.from)
        assertEquals("OrderDb", saveMsg.to)
        assertEquals("order", saveMsg.returnVar)

        val altBlock = rootMsg.nestedStatements[2] as ZenUmlStatement.ControlBlock
        assertEquals(ZenUmlControlKind.ALT, altBlock.kind)
        assertEquals(3, altBlock.branches.size)
        assertEquals("order != null", altBlock.branches[0].condition)
        assertEquals("retryable", altBlock.branches[1].condition)
        assertEquals(null, altBlock.branches[2].condition)
    }

    @Test
    fun parsesTryCatchFinallyLoopsOptAndParBlocks() {
        val diagram = ZenUmlParser.parse(
            """
            zenuml
              @Starter(Client)
              try {
                while (hasItems) {
                  Worker.processNext()
                }
              } catch (err) {
                Logger.logError(err)
              } finally {
                Worker.cleanup()
              }
            """.trimIndent(),
        )

        val tryBlock = diagram.statements.single() as ZenUmlStatement.ControlBlock
        assertEquals(ZenUmlControlKind.TRY, tryBlock.kind)
        assertEquals(3, tryBlock.branches.size)
        assertEquals("try", tryBlock.branches[0].keyword)
        assertEquals("catch", tryBlock.branches[1].keyword)
        assertEquals("err", tryBlock.branches[1].condition)
        assertEquals("finally", tryBlock.branches[2].keyword)

        val loopBlock = tryBlock.branches[0].statements.single() as ZenUmlStatement.ControlBlock
        assertEquals(ZenUmlControlKind.LOOP, loopBlock.kind)
        assertEquals("hasItems", loopBlock.branches[0].condition)
    }

    @Test
    fun resolvesExplicitCallReturnFramesTrailingCommentsAndMultilineAtReturn() {
        val diagram = ZenUmlParser.parse(
            """
            zenuml
              title Checkout (v2) -> Order Service
              A->B.call() {
                if (ready) {
                  C->D.work() {
                    return stepDone
                  }
                }
                // comment before else
                else {
                  return allDone
                }
                // B finishes here
              }
              @return
              B->A: asyncReply
              A->C.next()
            """.trimIndent(),
        )

        assertEquals("Checkout (v2) -> Order Service", diagram.title)
        // Only A, B, C, D should exist (no bogus _STARTER_)
        assertEquals(listOf("A", "B", "C", "D"), diagram.participants.map { it.id })

        val outerCall = diagram.statements[0] as ZenUmlStatement.Message
        val altBlock = outerCall.nestedStatements[0] as ZenUmlStatement.ControlBlock
        assertEquals(2, altBlock.branches.size)
        val innerCall = altBlock.branches[0].statements.single() as ZenUmlStatement.Message
        val innerReturn = innerCall.nestedStatements.single() as ZenUmlStatement.Message
        assertEquals("D", innerReturn.from)
        assertEquals("C", innerReturn.to)
        assertEquals("stepDone", innerReturn.text)

        val elseReturn = altBlock.branches[1].statements.single() as ZenUmlStatement.Message
        assertEquals("B", elseReturn.from)
        assertEquals("A", elseReturn.to)
        assertEquals("allDone", elseReturn.text)
        assertEquals("comment before else", elseReturn.comment)

        // Trailing comment inside A->B.call() is flushed as a NoteComment on B, not leaked onto @return or A->C.next()
        val trailingNote = outerCall.nestedStatements[1] as ZenUmlStatement.NoteComment
        assertEquals("B finishes here", trailingNote.text)
        assertEquals("B", trailingNote.onParticipant)

        // Multiline `@return` followed by `B->A: asyncReply` becomes a RETURN message
        val asyncReply = diagram.statements[1] as ZenUmlStatement.Message
        assertEquals(ZenUmlMessageKind.RETURN, asyncReply.kind)
        assertEquals("B", asyncReply.from)
        assertEquals("A", asyncReply.to)
        assertEquals("asyncReply", asyncReply.text)
        assertEquals(null, asyncReply.comment)

        // Variable named `title` in assignment is parsed as a call, not consumed as diagram title
        val titleAssignDiag = ZenUmlParser.parse(
            """
            zenuml
              title = A->B.getTitle()
            """.trimIndent(),
        )
        assertEquals(null, titleAssignDiag.title)
        val titleCall = titleAssignDiag.statements.single() as ZenUmlStatement.Message
        assertEquals("title", titleCall.returnVar)
        assertEquals("getTitle()", titleCall.text)

        // Return expression containing `->` inside quotes is accepted
        val arrowReturnDiag = ZenUmlParser.parse(
            """
            zenuml
              A as "Client (web): API"
              A->B.call() {
                return "A->B"
              }
            """.trimIndent(),
        )
        assertEquals("Client (web): API", arrowReturnDiag.participants.first { it.id == "A" }.label)
        val arrowRetMsg = (arrowReturnDiag.statements.single() as ZenUmlStatement.Message).nestedStatements.single() as ZenUmlStatement.Message
        assertEquals(ZenUmlMessageKind.RETURN, arrowRetMsg.kind)
        assertEquals("A->B", arrowRetMsg.text)

        // Apostrophes in inline async message text do not swallow closing braces
        val apostropheDiag = ZenUmlParser.parse("zenuml\n  A->B.call() { B->A: Here's the result }")
        val apRoot = apostropheDiag.statements.single() as ZenUmlStatement.Message
        val apInner = apRoot.nestedStatements.single() as ZenUmlStatement.Message
        assertEquals("Here's the result", apInner.text)
    }

    @Test
    fun topLevelMaradonaParserDispatchesZenUml() {
        val model = MaradonaParser.parse(
            """
            zenuml
              A->B.ping()
            """.trimIndent(),
        )
        assertTrue(model is MaradonaDiagramModel.ZenUmlModel)
        assertEquals(2, model.diagram.participants.size)
    }

    @Test
    fun rejectsUnclosedBraceOrNonZenUmlHeader() {
        assertFailsWith<IllegalArgumentException> {
            ZenUmlParser.parse("flowchart TD\n  A --> B")
        }
        assertFailsWith<IllegalArgumentException> {
            ZenUmlParser.parse("zenuml\n  A->B.call() {\n    B->C.inner()")
        }
    }
}
