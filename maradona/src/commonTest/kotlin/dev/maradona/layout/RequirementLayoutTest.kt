package dev.maradona.layout

import dev.maradona.parse.RequirementParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequirementLayoutTest {

    @Test
    fun positionsRequirementAndElementBoxesAndRoutesRelationsWithinBounds() {
        val diagram = RequirementParser.parse(
            """
            requirementDiagram
              title Checkout Requirements
              requirement checkout_req {
                id: 1
                text: Orders must be payable online.
                risk: high
                verifymethod: test
              }
              functionalRequirement payment_req {
                id: 1.1
                text: Card payments must be authorized.
                risk: high
                verifymethod: test
              }
              element checkout_service {
                type: service
                docRef: src/checkout
              }
              checkout_req - contains -> payment_req
              checkout_service - satisfies -> payment_req
            """.trimIndent(),
        )
        val layout = RequirementLayout.layout(diagram)

        assertNotNull(layout.titleRect)
        assertEquals(3, layout.items.size)
        assertEquals(2, layout.relations.size)

        val checkout = assertNotNull(layout.itemNode("checkout_req"))
        val payment = assertNotNull(layout.itemNode("payment_req"))
        val service = assertNotNull(layout.itemNode("checkout_service"))

        // In TB direction, parent/source items sit above payment_req.
        assertTrue(checkout.rect.bottom < payment.rect.y)
        assertTrue(service.rect.bottom < payment.rect.y)

        // All item rects and relation label rects lie within [0, width] x [0, height].
        for (pi in layout.items) {
            assertTrue(pi.rect.x >= 0f && pi.rect.right <= layout.width + 0.01f)
            assertTrue(pi.rect.y >= 0f && pi.rect.bottom <= layout.height + 0.01f)
        }
        for (pr in layout.relations) {
            assertTrue(pr.points.size >= 2)
            val lr = assertNotNull(pr.labelRect)
            assertTrue(lr.x >= 0f && lr.right <= layout.width + 0.01f)
            assertTrue(lr.y >= 0f && lr.bottom <= layout.height + 0.01f)
        }

        // Default measurements scale linearly with density = 2f.
        val layout2x = RequirementLayout.layout(diagram, options = LayoutOptions(nodeGap = 48f, layerGap = 56f, density = 2f))
        assertEquals(layout.width * 2f, layout2x.width, 0.05f)
        assertEquals(layout.height * 2f, layout2x.height, 0.05f)

        // Multiple self-relations on a node do not intersect neighboring node boxes.
        val selfLoopLayout = RequirementLayout.layout(
            RequirementParser.parse(
                """
                requirementDiagram
                  requirement r1 {
                    id: 1
                  }
                  requirement r2 {
                    id: 2
                  }
                  requirement r3 {
                    id: 3
                  }
                  r1 - contains -> r3
                  r2 - contains -> r3
                  r1 - traces -> r1
                  r1 - refines -> r1
                """.trimIndent(),
            ),
        )
        val r2Rect = assertNotNull(selfLoopLayout.itemNode("r2")).rect
        for (pr in selfLoopLayout.relations.filter { it.relation.source == "r1" && it.relation.target == "r1" }) {
            for (i in 0 until pr.points.lastIndex) {
                assertTrue(
                    !ClassLayout.segmentIntersectsRect(pr.points[i], pr.points[i + 1], r2Rect),
                    "Self-loop segment $i on r1 intersected neighbor r2 ($r2Rect)",
                )
            }
        }
    }
}
