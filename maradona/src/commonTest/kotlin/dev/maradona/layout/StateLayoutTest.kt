package dev.maradona.layout

import dev.maradona.model.Direction
import dev.maradona.parse.StateParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StateLayoutTest {

    @Test
    fun laysOutCompositeStatesConcurrentRegionsAndPseudostatesWithoutOverlap() {
        val diagram = StateParser.parse(
            """
            stateDiagram-v2
                [*] --> Idle
                Idle --> Processing : submit
                state Processing {
                    [*] --> Validating
                    Validating --> Charging : valid
                    Charging --> [*] : paid
                    --
                    [*] --> Auditing
                    Auditing --> [*] : logged
                }
                state Review <<choice>>
                Processing --> Review : complete
                Review --> Approved : [ok]
                Review --> Rejected : [fail]
                Approved --> [*]
                Rejected --> [*]
                note right of Processing : Tracked in audit log
            """.trimIndent(),
        )

        val layout = StateLayout.layout(diagram)
        assertTrue(layout.width > 0f && layout.height > 0f)

        val processing = assertNotNull(layout.stateNode("Processing"))
        val validating = assertNotNull(layout.stateNode("Validating"))
        val charging = assertNotNull(layout.stateNode("Charging"))
        val auditing = assertNotNull(layout.stateNode("Auditing"))
        val idle = assertNotNull(layout.stateNode("Idle"))

        // Composite state Processing must strictly enclose all its children below its header band.
        for (child in listOf(validating, charging, auditing)) {
            assertTrue(child.rect.x >= processing.rect.x, "${child.state.id} left outside Processing")
            assertTrue(child.rect.right <= processing.rect.right, "${child.state.id} right outside Processing")
            assertTrue(
                child.rect.y >= processing.rect.y + processing.headerHeight,
                "${child.state.id} (${child.rect}) intrudes into Processing header band (${processing.rect.y}..${processing.rect.y + processing.headerHeight})",
            )
            assertTrue(child.rect.bottom <= processing.rect.bottom, "${child.state.id} bottom outside Processing")
        }

        // Concurrent regions 0 and 1 must be separated horizontally with a divider between them.
        assertEquals(1, processing.regionDividers.size)
        val divX = processing.regionDividers.single().first.x
        assertTrue(validating.rect.right < divX && auditing.rect.x > divX)

        // External state Idle must not overlap Processing.
        val idleOverlapsProcessing = idle.rect.x < processing.rect.right && processing.rect.x < idle.rect.right &&
            idle.rect.y < processing.rect.bottom && processing.rect.y < idle.rect.bottom
        assertTrue(!idleOverlapsProcessing, "External state Idle (${idle.rect}) overlaps Processing (${processing.rect})")

        // No transition segment may pass through an unrelated leaf state or composite header title.
        for (tr in layout.transitions) {
            assertTrue(tr.points.size >= 2)
            val samples = ClassLayout.basisSplineSamples(tr.points)
            for (i in 0 until samples.lastIndex) {
                assertTrue(
                    !ClassLayout.segmentIntersectsRect(samples[i], samples[i + 1], processing.headerRect),
                    "Transition ${tr.transition.from}->${tr.transition.to} spline segment $i intersects Processing header ${processing.headerRect}",
                )
            }
        }
    }

    @Test
    fun stacksSelfTransitionLabelsAndAvoidsCompositeHeadersOnCrossBoundaryTransitions() {
        val diagram = StateParser.parse(
            """
            stateDiagram-v2
                Outside --> Inside : enter
                state VeryWideCompositeStateHeaderTitle {
                    Inside --> Inside : first self transition
                    Inside --> Inside : second self transition
                }
                class B
                VeryWideCompositeStateHeaderTitle --> B
            """.trimIndent(),
        )

        val layout = StateLayout.layout(
            diagram = diagram,
            transitionLabelSize = { idx, tr ->
                tr.label?.let { DiagramSize(120f, if (idx == 1) 80f else 20f) }
            },
        )
        val comp = assertNotNull(layout.stateNode("VeryWideCompositeStateHeaderTitle"))
        val inside = assertNotNull(layout.stateNode("Inside"))

        // Both self-transitions and their labels on Inside must be enclosed inside comp.rect below its header.
        val selfTransitions = layout.transitions.filter { it.transition.from == "Inside" && it.transition.to == "Inside" }
        assertEquals(2, selfTransitions.size)
        val l0 = assertNotNull(selfTransitions[0].labelRect)
        val l1 = assertNotNull(selfTransitions[1].labelRect)
        val labelsOverlap = l0.x < l1.right && l1.x < l0.right && l0.y < l1.bottom && l1.y < l0.bottom
        assertTrue(!labelsOverlap, "Self-transition labels overlap: $l0 vs $l1")
        assertTrue(l0.right <= comp.rect.right && l1.right <= comp.rect.right)
        assertTrue(l1.bottom <= comp.rect.bottom)
        for (st in selfTransitions) {
            for (pt in st.points) {
                assertTrue(pt.y >= comp.rect.y + comp.headerHeight, "Self-loop point $pt intrudes into composite header")
            }
        }

        // Cross-boundary transition Outside -> Inside must detour around comp.headerRect (including smoothed spline).
        val enterTr = layout.transitions.first { it.transition.from == "Outside" && it.transition.to == "Inside" }
        val spline = ClassLayout.basisSplineSamples(enterTr.points)
        for (i in 0 until spline.lastIndex) {
            assertTrue(
                !ClassLayout.segmentIntersectsRect(spline[i], spline[i + 1], comp.headerRect),
                "Outside->Inside spline segment $i (${spline[i]} -> ${spline[i + 1]}) crosses composite header ${comp.headerRect}",
            )
        }
        assertTrue(inside.rect.x >= comp.rect.x && inside.rect.right <= comp.rect.right)
    }

    @Test
    fun supportsAllFourDirections() {
        val src = """
            stateDiagram-v2
                [*] --> A
                A --> B : step
                B --> [*]
        """.trimIndent()
        val diagram = StateParser.parse(src)
        val tb = StateLayout.layout(diagram, options = LayoutOptions(directionOverride = Direction.TB))
        val bt = StateLayout.layout(diagram, options = LayoutOptions(directionOverride = Direction.BT))
        val lr = StateLayout.layout(diagram, options = LayoutOptions(directionOverride = Direction.LR))
        val rl = StateLayout.layout(diagram, options = LayoutOptions(directionOverride = Direction.RL))

        assertTrue(tb.stateNode("A")!!.rect.centerY < tb.stateNode("B")!!.rect.centerY)
        assertTrue(bt.stateNode("A")!!.rect.centerY > bt.stateNode("B")!!.rect.centerY)
        assertTrue(lr.stateNode("A")!!.rect.centerX < lr.stateNode("B")!!.rect.centerX)
        assertTrue(rl.stateNode("A")!!.rect.centerX > rl.stateNode("B")!!.rect.centerX)

        // Attached notes are placed on their physical left/right side, wide internal transition labels
        // stay inside their composite container, and choice self-loop endpoints touch the diamond outline.
        val edgeCases = StateParser.parse(
            """
            stateDiagram-v2
                state C {
                    A --> B : This transition label is much wider than either state
                }
                state Decision <<choice>>
                Decision --> Decision : retry
                note left of C : left note
                note right of C : right note
            """.trimIndent(),
        )
        val ecLayout = StateLayout.layout(edgeCases)
        val compC = assertNotNull(ecLayout.stateNode("C"))
        val abLbl = assertNotNull(ecLayout.transitions.first { it.transition.from == "A" }.labelRect)
        assertTrue(abLbl.x >= compC.rect.x && abLbl.right <= compC.rect.right, "Wide internal label $abLbl extends outside composite C (${compC.rect})")

        val leftNote = ecLayout.notes.first { it.note.text == "left note" }.rect
        val rightNote = ecLayout.notes.first { it.note.text == "right note" }.rect
        assertTrue(leftNote.right < compC.rect.x, "Left note ($leftNote) must be physically left of C (${compC.rect})")
        assertTrue(rightNote.x > compC.rect.right, "Right note ($rightNote) must be physically right of C (${compC.rect})")

        val decRect = assertNotNull(ecLayout.stateNode("Decision")).rect
        val decSelf = ecLayout.transitions.first { it.transition.from == "Decision" && it.transition.to == "Decision" }
        val endPt = decSelf.points.last()
        val diamondDist = kotlin.math.abs(endPt.x - decRect.centerX) / (decRect.width / 2f) +
            kotlin.math.abs(endPt.y - decRect.centerY) / (decRect.height / 2f)
        assertTrue(kotlin.math.abs(diamondDist - 1f) < 0.05f, "Choice self-loop tip $endPt must lie on diamond outline of $decRect (L1=$diamondDist)")

        // Deeply nested composite hierarchy (2,000 levels) lays out iteratively without overflowing the call stack.
        val deepDiagram = StateParser.parse(
            buildString {
                appendLine("stateDiagram-v2")
                for (i in 0 until 2_000) appendLine("state C$i {")
                appendLine("Leaf")
                for (i in 0 until 2_000) appendLine("}")
            },
        )
        val deepLayout = StateLayout.layout(deepDiagram)
        assertTrue(deepLayout.width > 0f && deepLayout.height > 0f)
    }
}
