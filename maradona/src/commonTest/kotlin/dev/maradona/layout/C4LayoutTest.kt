package dev.maradona.layout

import dev.maradona.parse.C4Parser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C4LayoutTest {

    @Test
    fun enclosesNestedBoundariesAndElementsWithoutOverlap() {
        val diagram = C4Parser.parse(
            """
            C4Context
              title Internet Banking Context
              Person(customer, "Banking Customer", "A customer of the bank")
              Enterprise_Boundary(bank, "BigBank plc") {
                System(banking, "Internet Banking", "Online banking portal")
                System_Boundary(backoffice, "Backoffice") {
                  SystemDb(core, "Mainframe", "Core accounts")
                  SystemQueue(events, "Event Bus", "Kafka")
                }
              }
              System_Ext(mail, "E-mail System", "Exchange")
              Rel(customer, banking, "Uses", "HTTPS")
              BiRel(banking, core, "Reads/Writes", "JDBC")
              Rel_Back(mail, banking, "Sends e-mail", "SMTP")
            """.trimIndent(),
        )

        val layout = C4Layout.layout(diagram)
        assertNotNull(layout.titleRect)
        assertEquals(5, layout.elements.size)
        assertEquals(2, layout.boundaries.size)
        assertEquals(3, layout.relationships.size)

        val bankBnd = layout.boundaries.first { it.boundary.id == "bank" }
        val backofficeBnd = layout.boundaries.first { it.boundary.id == "backoffice" }

        // Nested boundary is strictly inside outer boundary and below outer boundary header
        assertTrue(backofficeBnd.rect.x >= bankBnd.rect.x && backofficeBnd.rect.right <= bankBnd.rect.right)
        assertTrue(backofficeBnd.rect.y >= bankBnd.headerRect.bottom && backofficeBnd.rect.bottom <= bankBnd.rect.bottom)

        // Child elements are contained inside their parent boundary and below its header
        val coreEl = layout.elements.first { it.element.id == "core" }
        val eventsEl = layout.elements.first { it.element.id == "events" }
        assertTrue(coreEl.rect.x >= backofficeBnd.rect.x && coreEl.rect.right <= backofficeBnd.rect.right)
        assertTrue(coreEl.rect.y >= backofficeBnd.headerRect.bottom && coreEl.rect.bottom <= backofficeBnd.rect.bottom)
        assertTrue(!rectsOverlap(coreEl.rect, eventsEl.rect))

        // Relationships have proper arrow flags
        val usesRel = layout.relationships[0]
        assertEquals(false, usesRel.startArrow)
        assertEquals(true, usesRel.endArrow)

        val biRel = layout.relationships[1]
        assertEquals(true, biRel.startArrow)
        assertEquals(true, biRel.endArrow)

        val backRel = layout.relationships[2]
        assertEquals(true, backRel.startArrow)
        assertEquals(false, backRel.endArrow)
    }

    @Test
    fun separatesOppositeDirectionParallelEdgesSelfLoopsAndDirectionalHints() {
        val diagram = C4Parser.parse(
            """
            C4Dynamic
              Container(a, "Service A", "Kotlin")
              Container(b, "Service B", "Kotlin")
              Container(c, "Service C", "Kotlin")
              Rel(a, b, "Request")
              Rel(b, a, "Response")
              Rel(a, a, "Self Retry")
              Rel_U(b, c, "Notifies Above")
              UpdateRelStyle(a, b, ${'$'}offsetX="280")
            """.trimIndent(),
        )
        val layout = C4Layout.layout(diagram)
        val req = layout.relationships[0]
        val resp = layout.relationships[1]
        val selfRel = layout.relationships[2]

        // Opposite-direction parallel relationships do not share the same midpoint/label position
        assertNotNull(req.labelRect)
        assertNotNull(resp.labelRect)
        assertTrue(!rectsOverlap(req.labelRect, resp.labelRect))

        // Self-relationship has 3 distinct loop points extending outside the card
        assertEquals(3, selfRel.points.size)
        val aRect = layout.elements.first { it.element.id == "a" }.rect
        assertTrue(selfRel.points[1].x > aRect.right)

        // Rel_U(b, c) places c in an earlier row above b
        val bRect = layout.elements.first { it.element.id == "b" }.rect
        val cRect = layout.elements.first { it.element.id == "c" }.rect
        assertTrue(cRect.centerY < bRect.centerY)

        // Offset relationship label is unioned into overall diagram bounds
        assertTrue(req.labelRect.x >= 0f && req.labelRect.right <= layout.width)
        assertTrue(req.labelRect.y >= 0f && req.labelRect.bottom <= layout.height)

        // Skip-level relationship A->C in a vertical chain A->B, B->C, A->C detours around B
        val chain = C4Parser.parse(
            """
            C4Context
              System(a, "A")
              System(b, "B")
              System(c, "C")
              Rel(a, b, "Step 1")
              Rel(b, c, "Step 2")
              Rel(a, c, "Skip")
            """.trimIndent(),
        )
        val chainLayout = C4Layout.layout(chain)
        val bCard = chainLayout.elements.first { it.element.id == "b" }.rect
        val skipRel = chainLayout.relationships.first { it.relationship.label == "Skip" }
        assertNotNull(skipRel.labelRect)
        assertTrue(!rectsOverlap(skipRel.labelRect, bCard))

        // Parallel relationships without manual offsets do not overlap each other's labels,
        // self-relationship labels stay outside their source card, and boundary children honor Rel_U.
        val boundaryRelDiag = C4Parser.parse(
            """
            C4Container
              System_Boundary(b, "Boundary") {
                Container(a, "A", "Kotlin")
                Container(b2, "B", "Kotlin")
              }
              Rel(a, b2, "Request")
              Rel(a, b2, "Response")
              Rel(a, a, "Retries the failed operation")
              Rel_U(a, b2, "Upward")
            """.trimIndent(),
        )
        val brLayout = C4Layout.layout(
            boundaryRelDiag,
            options = LayoutOptions(directionOverride = dev.maradona.model.Direction.TB),
        )
        val r0 = brLayout.relationships[0].labelRect!!
        val r1 = brLayout.relationships[1].labelRect!!
        val selfLbl = brLayout.relationships[2].labelRect!!
        val aCard = brLayout.elements.first { it.element.id == "a" }.rect
        val b2Card = brLayout.elements.first { it.element.id == "b2" }.rect
        assertTrue(!rectsOverlap(r0, r1))
        assertTrue(!rectsOverlap(selfLbl, aCard))
        assertTrue(b2Card.centerY < aCard.centerY)

        // Transitive Rel_R constraints (Rel_R(a, b) and Rel_R(c, a)) produce order c, a, b;
        // multiple self-loops with different label widths do not overlap.
        val multiBandDiag = C4Parser.parse(
            """
            C4Context
              System(a, "A")
              System(b, "B")
              System(c, "C")
              Rel_R(a, b, "Calls")
              Rel_R(c, a, "Calls")
              Rel(a, a, "Retries the failed operation")
              Rel(a, a, "Retry")
            """.trimIndent(),
        )
        val mbLayout = C4Layout.layout(multiBandDiag)
        val mbA = mbLayout.elements.first { it.element.id == "a" }.rect
        val mbB = mbLayout.elements.first { it.element.id == "b" }.rect
        val mbC = mbLayout.elements.first { it.element.id == "c" }.rect
        assertTrue(mbC.centerX < mbA.centerX && mbA.centerX < mbB.centerX)
        val self0 = mbLayout.relationships[2].labelRect!!
        val self1 = mbLayout.relationships[3].labelRect!!
        assertTrue(!rectsOverlap(self0, self1))
        val self1Pts = mbLayout.relationships[3].points
        val self1ApexX = 0.25f * self1Pts[0].x + 0.5f * self1Pts[1].x + 0.25f * self1Pts[2].x
        assertEquals(self1.centerX, self1ApexX, 0.5f)

        // Rel_D(a, b) followed by Rel_R(b, c) places a above a row containing b and c
        val downThenRight = C4Parser.parse(
            """
            C4Context
              System(a, "A")
              System(b, "B")
              System(c, "C")
              Rel_D(a, b, "Down")
              Rel_R(b, c, "Right")
            """.trimIndent(),
        )
        val dtrLayout = C4Layout.layout(downThenRight)
        val dtrA = dtrLayout.elements.first { it.element.id == "a" }.rect
        val dtrB = dtrLayout.elements.first { it.element.id == "b" }.rect
        val dtrC = dtrLayout.elements.first { it.element.id == "c" }.rect
        assertTrue(dtrA.centerY < dtrB.centerY)
        assertEquals(dtrB.centerY, dtrC.centerY, 0.5f)
        assertTrue(dtrB.centerX < dtrC.centerX)
        // Self-loop label on `a` does not overlap neighbor `b` in the same row
        assertTrue(!rectsOverlap(self0, mbB))

        // Boundaries connected by Rel_D(b1, b2) place b2 below b1
        val bndRelDiag = C4Parser.parse(
            """
            C4Context
              System_Boundary(b1, "Boundary 1") {
                System(s1, "S1")
              }
              System_Boundary(b2, "Boundary 2") {
                System(s2, "S2")
              }
              Rel_D(b1, b2, "Calls")
            """.trimIndent(),
        )
        val bndRelLayout = C4Layout.layout(bndRelDiag)
        val b1Rect = bndRelLayout.boundaries.first { it.boundary.id == "b1" }.rect
        val b2Rect = bndRelLayout.boundaries.first { it.boundary.id == "b2" }.rect
        assertTrue(b1Rect.centerY < b2Rect.centerY)

        // Nested boundaries under direction LR with Rel_R(a, b) place b to the right of a
        val nestedLrDiag = C4Parser.parse(
            """
            C4Deployment
              direction LR
              Deployment_Node(outer, "Outer") {
                Deployment_Node(a, "Node A") {
                  Container(c1, "C1")
                }
                Deployment_Node(b, "Node B") {
                  Container(c2, "C2")
                }
              }
              Rel_R(a, b, "Calls")
            """.trimIndent(),
        )
        val nlrLayout = C4Layout.layout(nestedLrDiag)
        val naRect = nlrLayout.boundaries.first { it.boundary.id == "a" }.rect
        val nbRect = nlrLayout.boundaries.first { it.boundary.id == "b" }.rect
        assertTrue(naRect.centerX < nbRect.centerX)
        assertTrue(nlrLayout.width > 0f && nlrLayout.height > 0f)
    }

    @Test
    fun scalesWithDensity() {
        val diagram = C4Parser.parse(
            """
            C4Container
              Person(u, "User")
              Container(app, "App", "Kotlin")
              Rel(u, app, "Uses")
            """.trimIndent(),
        )
        val l1 = C4Layout.layout(diagram, options = LayoutOptions(density = 1f))
        val l2 = C4Layout.layout(diagram, options = LayoutOptions(density = 2f))
        assertTrue(abs(l2.width - l1.width * 2f) < 1f)
        assertTrue(abs(l2.height - l1.height * 2f) < 1f)
    }

    private fun rectsOverlap(a: DiagramRect, b: DiagramRect): Boolean {
        val eps = 0.5f
        return a.x + eps < b.right && a.right - eps > b.x &&
            a.y + eps < b.bottom && a.bottom - eps > b.y
    }
}
