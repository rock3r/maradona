package dev.maradona.layout

import dev.maradona.model.ClassNode
import dev.maradona.parse.ClassParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassLayoutTest {

    private fun fakeMetrics(node: ClassNode): ClassBoxMetrics {
        val titleW = node.displayTitle.length * 8f + 24f
        val attrW = (node.attributes.maxOfOrNull { it.displayText.length } ?: 0) * 7f + 24f
        val methW = (node.methods.maxOfOrNull { it.displayText.length } ?: 0) * 7f + 24f
        val w = maxOf(100f, titleW, attrW, methW)
        val hdrH = (node.annotations.size * 16f) + 32f
        val attrH = if (node.attributes.isEmpty()) 8f else node.attributes.size * 18f + 12f
        val methH = if (node.methods.isEmpty()) 8f else node.methods.size * 18f + 12f
        return ClassBoxMetrics(w, hdrH + attrH + methH, hdrH, attrH, methH)
    }

    @Test
    fun classesNamespacesNotesAndRelationsDoNotOverlapAndStayInBounds() {
        val diagram = ClassParser.parse(
            """
            ---
            title: Commerce Domain
            ---
            classDiagram
              namespace Domain.Orders {
                class Customer {
                  +String id
                  +String email
                }
                class Order {
                  +String id
                  +total() Money
                }
              }
              class Payment {
                <<interface>>
                +authorize()* bool
              }
              note for Order "Immutable after placement"
              Customer "1" --> "*" Order : places
              Order ..|> Payment : settled by
            """.trimIndent(),
        )
        val layout = ClassLayout.layout(
            diagram = diagram,
            classBoxSize = ::fakeMetrics,
            relationLabelSize = { _, rel -> rel.label?.let { DiagramSize(it.length * 7f, 16f) } },
            titleSize = diagram.title?.let { DiagramSize(it.length * 9f, 22f) },
        )
        assertEquals(3, layout.classes.size)
        assertEquals(2, layout.namespaces.size)
        assertEquals(1, layout.notes.size)
        assertEquals(2, layout.relations.size)

        // Title sits strictly above all namespaces and classes.
        val titleRect = requireNotNull(layout.titleRect)
        assertTrue(titleRect.bottom <= layout.namespaces.minOf { it.rect.y })

        // Outer namespace `Domain` precedes and strictly encloses inner `Domain.Orders`.
        assertEquals(listOf("Domain", "Domain.Orders"), layout.namespaces.map { it.namespace.id })
        val outerNs = layout.namespaces[0].rect
        val innerNs = layout.namespaces[1].rect
        assertTrue(outerNs.x < innerNs.x && outerNs.right > innerNs.right)
        assertTrue(outerNs.y < innerNs.y && outerNs.bottom > innerNs.bottom)

        // Member classes sit strictly inside `Domain.Orders`, while non-member classes and notes
        // (`Payment` and the top-level `note for Order`) do not overlap any namespace box.
        for (id in listOf("Customer", "Order")) {
            val r = layout.classNode(id)!!.rect
            assertTrue(r.x >= innerNs.x && r.right <= innerNs.right && r.y >= innerNs.y && r.bottom <= innerNs.bottom)
        }
        val paymentRect = layout.classNode("Payment")!!.rect
        val noteRect = layout.notes.single().rect
        for (ns in layout.namespaces) {
            val nr = ns.rect
            val payOverlap = paymentRect.x < nr.right && nr.x < paymentRect.right && paymentRect.y < nr.bottom && nr.y < paymentRect.bottom
            val noteOverlap = noteRect.x < nr.right && nr.x < noteRect.right && noteRect.y < nr.bottom && nr.y < noteRect.bottom
            assertTrue(!payOverlap, "Non-member class Payment ($paymentRect) overlaps namespace ${ns.namespace.id} ($nr)")
            assertTrue(!noteOverlap, "Non-member note ($noteRect) overlaps namespace ${ns.namespace.id} ($nr)")
        }

        // Class boxes do not overlap each other or the note box.
        val allBoxes = layout.classes.map { it.node.id to it.rect } + layout.notes.map { it.note.id to it.rect }
        for (i in allBoxes.indices) for (j in i + 1 until allBoxes.size) {
            val a = allBoxes[i].second
            val b = allBoxes[j].second
            val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
            assertTrue(!overlap, "Boxes overlap: ${allBoxes[i].first} ($a) vs ${allBoxes[j].first} ($b)")
        }

        // Relation endpoints attach to source/target class box outlines, and cardinalities sit outside class boxes.
        val placesRel = layout.relations.first()
        val custRect = layout.classNode("Customer")!!.rect
        val orderRect = layout.classNode("Order")!!.rect
        val fromCard = requireNotNull(placesRel.fromCardinalityRect)
        val toCard = requireNotNull(placesRel.toCardinalityRect)
        val fromOverlapsCust = fromCard.x < custRect.right && custRect.x < fromCard.right &&
            fromCard.y < custRect.bottom && custRect.y < fromCard.bottom
        val toOverlapsOrder = toCard.x < orderRect.right && orderRect.x < toCard.right &&
            toCard.y < orderRect.bottom && orderRect.y < toCard.bottom
        assertTrue(!fromOverlapsCust, "fromCardinality $fromCard overlaps Customer $custRect")
        assertTrue(!toOverlapsOrder, "toCardinality $toCard overlaps Order $orderRect")
    }

    @Test
    fun wideNamespaceEvictsMultipleExternalNodesWithoutStackingThem() {
        val diagram = ClassParser.parse(
            """
            classDiagram
              namespace Empty {
              }
              namespace First {
                class OutsideA
              }
              namespace VeryWideEnterpriseNamespaceTitleForTesting {
                class InsideB1
                class InsideB2
              }
              class OutsideC
              class OutsideD
              InsideB1 --> InsideB2
              OutsideC --> OutsideD : link
            """.trimIndent(),
        )
        val layout = ClassLayout.layout(
            diagram = diagram,
            classBoxSize = ::fakeMetrics,
            relationLabelSize = { _, rel -> rel.label?.let { DiagramSize(it.length * 8f, 16f) } },
            namespaceLabelSize = { ns -> DiagramSize(ns.label.length * 14f, 20f) },
        )
        val emptyNs = layout.namespaces.first { it.namespace.id == "Empty" }.rect
        val firstNs = layout.namespaces.first { it.namespace.id == "First" }.rect
        val wideNs = layout.namespaces.first { it.namespace.id == "VeryWideEnterpriseNamespaceTitleForTesting" }.rect
        val outA = layout.classNode("OutsideA")!!.rect
        val inB = layout.classNode("InsideB1")!!.rect
        val outC = layout.classNode("OutsideC")!!.rect
        // `First` must still enclose `OutsideA` after `VeryWide...` evicts it, and sibling namespaces (including `Empty`) must not overlap.
        assertTrue(outA.x >= firstNs.x && outA.right <= firstNs.right, "OutsideA ($outA) not inside First ($firstNs)")
        assertTrue(inB.x >= wideNs.x && inB.right <= wideNs.right, "InsideB1 ($inB) not inside WideNs ($wideNs)")
        val emptyOverlapsWide = emptyNs.x < wideNs.right && wideNs.x < emptyNs.right && emptyNs.y < wideNs.bottom && wideNs.y < emptyNs.bottom
        assertTrue(!emptyOverlapsWide, "Empty namespace ($emptyNs) overlaps WideNs ($wideNs)")
        val nsOverlap = firstNs.x < wideNs.right && wideNs.x < firstNs.right && firstNs.y < wideNs.bottom && wideNs.y < firstNs.bottom
        val cOverlapsWide = outC.x < wideNs.right && wideNs.x < outC.right && outC.y < wideNs.bottom && wideNs.y < outC.bottom
        val aOverlapsC = outA.x < outC.right && outC.x < outA.right && outA.y < outC.bottom && outC.y < outA.bottom
        assertTrue(!nsOverlap, "Sibling namespaces overlap: First ($firstNs) vs WideNs ($wideNs)")
        assertTrue(!cOverlapsWide, "OutsideC ($outC) overlaps WideNs ($wideNs)")
        assertTrue(!aOverlapsC, "OutsideA ($outA) and OutsideC ($outC) overlap")
        val extRel = layout.relations.first { it.relation.from == "OutsideC" }
        val extLabel = requireNotNull(extRel.labelRect)
        assertTrue(extLabel.x >= wideNs.right, "Displaced relation label $extLabel remained inside WideNs $wideNs")
        assertTrue(extRel.points.all { it.x >= wideNs.right }, "Displaced relation points ${extRel.points} remained inside WideNs $wideNs")
    }

    @Test
    fun selfRelationLabelDoesNotOverlapRightNeighborClass() {
        val diagram = ClassParser.parse(
            """
            classDiagram
              class A
              class B
              A --> A : self-reference-with-long-label
              A --> A : second-self-reference
            """.trimIndent(),
        )
        val layout = ClassLayout.layout(
            diagram = diagram,
            classBoxSize = ::fakeMetrics,
            relationLabelSize = { _, rel -> rel.label?.let { DiagramSize(140f, 20f) } },
        )
        val rectB = layout.classNode("B")!!.rect
        val l0 = requireNotNull(layout.relations[0].labelRect)
        val l1 = requireNotNull(layout.relations[1].labelRect)
        val l0OverlapsL1 = l0.x < l1.right && l1.x < l0.right && l0.y < l1.bottom && l1.y < l0.bottom
        assertTrue(!l0OverlapsL1, "Multiple self-relation labels overlap: $l0 vs $l1")
        for (selfLabel in listOf(l0, l1)) {
            val overlaps = selfLabel.x < rectB.right && rectB.x < selfLabel.right &&
                selfLabel.y < rectB.bottom && rectB.y < selfLabel.bottom
            assertTrue(!overlaps, "Self-relation label ($selfLabel) overlaps neighbor class B ($rectB)")
        }
    }
}
