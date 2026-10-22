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
              namespace VeryWideFirstNamespaceTitle {
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
        val firstNs = layout.namespaces.first { it.namespace.id == "VeryWideFirstNamespaceTitle" }.rect
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
              A "zero-or-many" --> A : self-reference-with-long-label
              A --> A : second-self-reference
            """.trimIndent(),
        )
        val layout = ClassLayout.layout(
            diagram = diagram,
            classBoxSize = ::fakeMetrics,
            relationLabelSize = { idx, rel -> rel.label?.let { DiagramSize(140f, if (idx == 0) 100f else 20f) } },
        )
        val rectB = layout.classNode("B")!!.rect
        val l0 = requireNotNull(layout.relations[0].labelRect)
        val c0 = requireNotNull(layout.relations[0].fromCardinalityRect)
        val l1 = requireNotNull(layout.relations[1].labelRect)
        val c0OverlapsL0 = c0.x < l0.right && l0.x < c0.right && c0.y < l0.bottom && l0.y < c0.bottom
        assertTrue(!c0OverlapsL0, "Self-relation cardinality ($c0) overlaps its label ($l0)")
        val l0OverlapsL1 = l0.x < l1.right && l1.x < l0.right && l0.y < l1.bottom && l1.y < l0.bottom
        assertTrue(!l0OverlapsL1, "Multiple self-relation labels overlap: $l0 vs $l1")
        for (selfLabel in listOf(l0, l1)) {
            val overlaps = selfLabel.x < rectB.right && rectB.x < selfLabel.right &&
                selfLabel.y < rectB.bottom && rectB.y < selfLabel.bottom
            assertTrue(!overlaps, "Self-relation label ($selfLabel) overlaps neighbor class B ($rectB)")
        }

        // Wide cardinality on an LR relation stays outside both endpoint class boxes.
        val lrDiagram = ClassParser.parse(
            """
            classDiagram
              direction LR
              A "zero-or-many" --> "exactly-one" B
            """.trimIndent(),
        )
        val lrLayout = ClassLayout.layout(diagram = lrDiagram, classBoxSize = ::fakeMetrics)
        val lrRel = lrLayout.relations.single()
        val lrA = lrLayout.classNode("A")!!.rect
        val lrB = lrLayout.classNode("B")!!.rect
        val lrFrom = requireNotNull(lrRel.fromCardinalityRect)
        val lrTo = requireNotNull(lrRel.toCardinalityRect)
        val fromOverlapsA = lrFrom.x < lrA.right && lrA.x < lrFrom.right && lrFrom.y < lrA.bottom && lrA.y < lrFrom.bottom
        val toOverlapsB = lrTo.x < lrB.right && lrB.x < lrTo.right && lrTo.y < lrB.bottom && lrB.y < lrTo.bottom
        assertTrue(!fromOverlapsA, "Wide LR fromCardinality ($lrFrom) overlaps A ($lrA)")
        assertTrue(!toOverlapsB, "Wide LR toCardinality ($lrTo) overlaps B ($lrB)")

        // Three self-relations on a child class B below A reserve vertical clearance above B so loops do not enter A.
        val multiSelfDiagram = ClassParser.parse(
            """
            classDiagram
              A --> B
              B --> B : r1
              B --> B : r2
              B --> B : r3
            """.trimIndent(),
        )
        val multiSelfLayout = ClassLayout.layout(diagram = multiSelfDiagram, classBoxSize = ::fakeMetrics)
        val topA = multiSelfLayout.classNode("A")!!.rect
        for (rel in multiSelfLayout.relations.filter { it.relation.from == "B" }) {
            for (pt in rel.points) {
                assertTrue(pt.y > topA.bottom, "Self-loop point $pt on B intrudes into preceding class A ($topA)")
            }
        }

        // Self-loop inside a namespace stays below the namespace's top header band, and multiline TB cardinalities do not overlap the relation label.
        val nsSelfDiagram = ClassParser.parse(
            """
            classDiagram
              namespace CommerceDomain {
                class A
              }
              class B
              A --> A : self
              A "one<br/>two<br/>three" --> "one<br/>two<br/>three" B : link
            """.trimIndent(),
        )
        val nsSelfLayout = ClassLayout.layout(
            diagram = nsSelfDiagram,
            classBoxSize = ::fakeMetrics,
            relationLabelSize = { _, rel -> rel.label?.let { DiagramSize(100f, 20f) } },
            cardinalitySize = { _, _, _ -> DiagramSize(35f, 42f) },
        )
        val nsRect = nsSelfLayout.namespaces.single().rect
        val selfRel = nsSelfLayout.relations.first { it.relation.from == "A" && it.relation.to == "A" }
        for (pt in selfRel.points) {
            assertTrue(pt.y >= nsRect.y + 30f, "Self-loop point $pt intrudes into namespace header band (${nsRect.y}..${nsRect.y + 30f})")
        }
        val abRel = nsSelfLayout.relations.first { it.relation.to == "B" }
        val abLabel = requireNotNull(abRel.labelRect)
        val abFromCard = requireNotNull(abRel.fromCardinalityRect)
        val abToCard = requireNotNull(abRel.toCardinalityRect)
        assertTrue(abFromCard.bottom <= abLabel.y || abFromCard.x >= abLabel.right || abLabel.x >= abFromCard.right)
        assertTrue(abLabel.bottom <= abToCard.y || abToCard.x >= abLabel.right || abLabel.x >= abToCard.right)

        // Cross-namespace TB relation entering a namespace from above must route around the centered namespace header,
        // including when smoothed with basisSpline across a wide 30-character namespace header.
        val crossHdrDiagram = ClassParser.parse(
            """
            classDiagram
                class A
                namespace VeryWideCommerceDomainTitle30C {
                    class B
                }
                A --> B : enters
            """.trimIndent(),
        )
        val crossHdrLayout = ClassLayout.layout(
            diagram = crossHdrDiagram,
            classBoxSize = { ClassBoxMetrics(100f, 50f, 24f, 13f, 13f) },
            relationLabelSize = { _, r -> r.label?.let { DiagramSize(48f, 16f) } },
        )
        val hdrRect = crossHdrLayout.namespaces.single().headerRect
        val crossRel = crossHdrLayout.relations.single()
        for (i in 0 until crossRel.points.lastIndex) {
            assertTrue(
                !ClassLayout.segmentIntersectsRect(crossRel.points[i], crossRel.points[i + 1], hdrRect),
                "Relation segment $i (${crossRel.points[i]} -> ${crossRel.points[i + 1]}) must not pass through namespace header $hdrRect",
            )
        }
        val splineSamples = ClassLayout.basisSplineSamples(crossRel.points)
        for (i in 0 until splineSamples.lastIndex) {
            assertTrue(
                !ClassLayout.segmentIntersectsRect(splineSamples[i], splineSamples[i + 1], hdrRect),
                "Smoothed spline segment $i (${splineSamples[i]} -> ${splineSamples[i + 1]}) must not pass through namespace header $hdrRect",
            )
        }
        val crossLbl = requireNotNull(crossRel.labelRect)
        val hdrOverlap = crossLbl.x < hdrRect.right && hdrRect.x < crossLbl.right &&
            crossLbl.y < hdrRect.bottom && hdrRect.y < crossLbl.bottom
        assertTrue(!hdrOverlap, "Relation label $crossLbl must not overlap namespace header $hdrRect")

        // External namespace with a self-relation must include its self-loop envelope when evicted by a sibling namespace.
        val extSelfNsDiagram = ClassParser.parse(
            """
            classDiagram
                namespace N {
                    class A
                    class C
                }
                namespace Other {
                    class B
                }
                A --> B : link
                B --> B : self
            """.trimIndent(),
        )
        val extSelfNsLayout = ClassLayout.layout(
            diagram = extSelfNsDiagram,
            classBoxSize = { ClassBoxMetrics(100f, 50f, 24f, 13f, 13f) },
            relationLabelSize = { _, r -> r.label?.let { DiagramSize(100f, 20f) } },
        )
        val nsNRect = extSelfNsLayout.namespaces.first { it.namespace.id == "N" }.rect
        val nsOtherRect = extSelfNsLayout.namespaces.first { it.namespace.id == "Other" }.rect
        val siblingOverlap = nsNRect.x < nsOtherRect.right && nsOtherRect.x < nsNRect.right &&
            nsNRect.y < nsOtherRect.bottom && nsOtherRect.y < nsNRect.bottom
        assertTrue(!siblingOverlap, "Sibling namespaces N ($nsNRect) and Other ($nsOtherRect) must not overlap")

        // LR namespace eviction that reverses endpoint order must reserve label clearance and reroute around intermediate classes.
        val lrEvictDiagram = ClassParser.parse(
            """
            classDiagram
                direction LR
                namespace N {
                    class A
                    class C
                }
                class B
                A --> B : link
                B --> C : link
            """.trimIndent(),
        )
        val lrEvictLayout = ClassLayout.layout(
            diagram = lrEvictDiagram,
            classBoxSize = { ClassBoxMetrics(100f, 50f, 24f, 13f, 13f) },
            relationLabelSize = { _, r -> r.label?.let { DiagramSize(100f, 20f) } },
        )
        val lrRectC = lrEvictLayout.classNode("C")!!.rect
        val lrRelAB = lrEvictLayout.relations.first { it.relation.from == "A" && it.relation.to == "B" }
        for (i in 0 until lrRelAB.points.lastIndex) {
            assertTrue(
                !ClassLayout.segmentIntersectsRect(lrRelAB.points[i], lrRelAB.points[i + 1], lrRectC),
                "A->B segment $i (${lrRelAB.points[i]} -> ${lrRelAB.points[i + 1]}) must not pass through C ($lrRectC)",
            )
        }
        for (rel in lrEvictLayout.relations) {
            val lbl = requireNotNull(rel.labelRect)
            for (pc in lrEvictLayout.classes) {
                val cOverlap = lbl.x < pc.rect.right && pc.rect.x < lbl.right &&
                    lbl.y < pc.rect.bottom && pc.rect.y < lbl.bottom
                assertTrue(!cOverlap, "Relation ${rel.relation.from}->${rel.relation.to} label $lbl must not overlap class ${pc.node.id} (${pc.rect})")
            }
        }

        // Tall source cardinality on a self-relation must reserve vertical clearance above classes below it.
        val tallSelfCardDiagram = ClassParser.parse(
            """
            classDiagram
                A --> B
                A "one<br/>two<br/>three<br/>four<br/>five<br/>six" --> A
            """.trimIndent(),
        )
        val tallSelfCardLayout = ClassLayout.layout(
            diagram = tallSelfCardDiagram,
            classBoxSize = { c ->
                if (c.id == "A") ClassBoxMetrics(100f, 50f, 24f, 13f, 13f)
                else ClassBoxMetrics(300f, 50f, 24f, 13f, 13f)
            },
            cardinalitySize = { _, _, _ -> DiagramSize(35f, 84f) },
        )
        val tallSelfRectB = tallSelfCardLayout.classNode("B")!!.rect
        val tallSelfCardRect = requireNotNull(
            tallSelfCardLayout.relations.first { it.relation.from == "A" && it.relation.to == "A" }.fromCardinalityRect,
        )
        val cardOverlapsB = tallSelfCardRect.x < tallSelfRectB.right && tallSelfRectB.x < tallSelfCardRect.right &&
            tallSelfCardRect.y < tallSelfRectB.bottom && tallSelfRectB.y < tallSelfCardRect.bottom
        assertTrue(!cardOverlapsB, "Self-relation source cardinality $tallSelfCardRect must not overlap class B ($tallSelfRectB)")

        // Diagonal detour around an intermediate class box must verify both entry and exit segments.
        val btDetourDiagram = ClassParser.parse(
            """
            classDiagram
                direction BT
                namespace N {
                    class C0
                    class C1
                }
                class C2
                class C3
                class C4
                class C5
                class C6
                C3 --> C6 : link
                C1 --> C2 : link
                C4 --> C2 : link
                C6 --> C5 : link
            """.trimIndent(),
        )
        val btDetourLayout = ClassLayout.layout(
            diagram = btDetourDiagram,
            classBoxSize = { ClassBoxMetrics(100f, 50f, 24f, 13f, 13f) },
            relationLabelSize = { _, r -> r.label?.let { DiagramSize(100f, 20f) } },
        )
        for (rel in btDetourLayout.relations) {
            for (pc in btDetourLayout.classes) {
                if (pc.node.id == rel.relation.from || pc.node.id == rel.relation.to) continue
                for (i in 0 until rel.points.lastIndex) {
                    assertTrue(
                        !ClassLayout.segmentIntersectsRect(rel.points[i], rel.points[i + 1], pc.rect),
                        "Relation ${rel.relation.from}->${rel.relation.to} segment $i (${rel.points[i]} -> ${rel.points[i + 1]}) must not intersect unrelated class ${pc.node.id} (${pc.rect})",
                    )
                }
            }
        }
    }
}
