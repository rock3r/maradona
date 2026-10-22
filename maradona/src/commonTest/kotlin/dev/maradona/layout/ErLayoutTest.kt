package dev.maradona.layout

import dev.maradona.model.Direction
import dev.maradona.parse.ErParser
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ErLayoutTest {

    @Test
    fun laysOutEntitiesSubgraphsAndRelationshipsWithoutOverlap() {
        val diagram = ErParser.parse(
            """
            erDiagram
                subgraph commerce [Commerce Domain]
                    CUSTOMER ||--o{ ORDER : places
                    ORDER ||--|{ LINE_ITEM : contains
                    CUSTOMER {
                        string id PK
                        string email UK
                    }
                    ORDER {
                        string id PK
                        date placedAt
                    }
                    LINE_ITEM {
                        int quantity
                        float price
                    }
                }
                subgraph catalog [Catalog Domain]
                    PRODUCT {
                        string sku PK
                        string title
                    }
                }
                PRODUCT ||--o{ LINE_ITEM : "appears in"
                commerce ||--|| catalog : syncs
            """.trimIndent(),
        )

        val layout = ErLayout.layout(diagram)
        assertTrue(layout.width > 0f && layout.height > 0f)

        val commerce = assertNotNull(layout.subgraphNode("commerce"))
        val catalog = assertNotNull(layout.subgraphNode("catalog"))
        val customer = assertNotNull(layout.entityNode("CUSTOMER"))
        val order = assertNotNull(layout.entityNode("ORDER"))
        val lineItem = assertNotNull(layout.entityNode("LINE_ITEM"))
        val product = assertNotNull(layout.entityNode("PRODUCT"))

        // Subgraph commerce must enclose its member entities below its header band.
        for (member in listOf(customer, order, lineItem)) {
            assertTrue(member.rect.x >= commerce.rect.x && member.rect.right <= commerce.rect.right)
            assertTrue(member.rect.y >= commerce.rect.y + commerce.headerHeight)
            assertTrue(member.rect.bottom <= commerce.rect.bottom)
        }
        assertTrue(product.rect.x >= catalog.rect.x && product.rect.right <= catalog.rect.right)

        // Sibling subgraphs commerce and catalog must not overlap.
        val sgOverlap = commerce.rect.x < catalog.rect.right && catalog.rect.x < commerce.rect.right &&
            commerce.rect.y < catalog.rect.bottom && catalog.rect.y < commerce.rect.bottom
        assertTrue(!sgOverlap, "Sibling subgraphs commerce (${commerce.rect}) and catalog (${catalog.rect}) must not overlap")

        // Cross-subgraph relationship PRODUCT -> LINE_ITEM must not pass through commerce's header title or any unrelated entity.
        val crossRel = layout.relationships.first { it.relationship.from == "PRODUCT" && it.relationship.to == "LINE_ITEM" }
        val spline = ClassLayout.basisSplineSamples(crossRel.points)
        for (i in 0 until spline.lastIndex) {
            assertTrue(
                !ClassLayout.segmentIntersectsRect(spline[i], spline[i + 1], commerce.headerRect),
                "Cross-subgraph ER relationship spline segment $i intersects commerce header ${commerce.headerRect}",
            )
            for (pe in layout.entities) {
                if (pe.entity.id == "PRODUCT" || pe.entity.id == "LINE_ITEM") continue
                assertTrue(
                    !ClassLayout.segmentIntersectsRect(spline[i], spline[i + 1], pe.rect),
                    "Cross-subgraph ER relationship spline segment $i intersects unrelated entity ${pe.entity.id} (${pe.rect})",
                )
            }
        }
    }

    @Test
    fun supportsDirectionsAndStacksSelfRelationshipLabels() {
        val diagram = ErParser.parse(
            """
            erDiagram
                EMPLOYEE ||--o{ EMPLOYEE : manages
                EMPLOYEE ||--o| EMPLOYEE : mentors
                EMPLOYEE ||--|{ DEPARTMENT : belongs_to
            """.trimIndent(),
        )
        val tb = ErLayout.layout(diagram, options = LayoutOptions(directionOverride = Direction.TB))
        val bt = ErLayout.layout(diagram, options = LayoutOptions(directionOverride = Direction.BT))
        val lr = ErLayout.layout(diagram, options = LayoutOptions(directionOverride = Direction.LR))
        val rl = ErLayout.layout(diagram, options = LayoutOptions(directionOverride = Direction.RL))

        assertTrue(tb.entityNode("EMPLOYEE")!!.rect.centerY < tb.entityNode("DEPARTMENT")!!.rect.centerY)
        assertTrue(bt.entityNode("EMPLOYEE")!!.rect.centerY > bt.entityNode("DEPARTMENT")!!.rect.centerY)
        assertTrue(lr.entityNode("EMPLOYEE")!!.rect.centerX < lr.entityNode("DEPARTMENT")!!.rect.centerX)
        assertTrue(rl.entityNode("EMPLOYEE")!!.rect.centerX > rl.entityNode("DEPARTMENT")!!.rect.centerX)

        val l0 = assertNotNull(tb.relationships[0].labelRect)
        val l1 = assertNotNull(tb.relationships[1].labelRect)
        val overlap = l0.x < l1.right && l1.x < l0.right && l0.y < l1.bottom && l1.y < l0.bottom
        assertTrue(!overlap, "Self-relationship labels must not overlap: $l0 vs $l1")

        // Parallel cross-subgraph relationships between the same endpoints must receive distinct routes.
        val parallelCross = ErParser.parse(
            """
            erDiagram
                subgraph left
                    A
                end
                subgraph right
                    B
                end
                A ||--o{ B : owns
                A |o..|| B : reviews
            """.trimIndent(),
        )
        val pcLayout = ErLayout.layout(parallelCross)
        val p0 = pcLayout.relationships[0].points
        val p1 = pcLayout.relationships[1].points
        assertTrue(p0 != p1, "Parallel cross-subgraph relationships must have distinct routes: $p0 vs $p1")
    }
}
