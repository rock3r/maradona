package dev.maradona.parse

import dev.maradona.model.Direction
import dev.maradona.model.StateKind
import dev.maradona.model.StateNotePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StateParserTest {

    @Test
    fun parsesBasicStateDiagramAndDispatchesFromMaradonaParser() {
        val src = """
            ---
            title: Checkout State Machine
            ---
            stateDiagram-v2
                direction LR
                [*] --> Idle
                Idle : Waiting for cart
                Idle : Cart is empty
                Idle --> Validating : checkout
                state "Order Validating" as Validating
                Validating --> [*] : cancel
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.StateModel>(model)
        val d = model.diagram
        assertEquals("Checkout State Machine", d.title)
        assertEquals(Direction.LR, d.direction)

        val idle = assertNotNull(d.state("Idle"))
        assertEquals(listOf("Waiting for cart", "Cart is empty"), idle.descriptions)

        val validating = assertNotNull(d.state("Validating"))
        assertEquals("Order Validating", validating.label)

        val startState = d.states.firstOrNull { it.kind == StateKind.Start }
        val endState = d.states.firstOrNull { it.kind == StateKind.End }
        assertNotNull(startState)
        assertNotNull(endState)
        assertEquals(3, d.transitions.size)
        assertEquals("checkout", d.transitions[1].label)
        assertEquals("cancel", d.transitions[2].label)
    }

    @Test
    fun parsesCompositeStatesConcurrentRegionsChoiceForkJoinAndNotes() {
        val src = """
            stateDiagram-v2
                state fork_state <<fork>>
                state join_state [[join]]
                state is_valid <<choice>>

                [*] --> fork_state
                fork_state --> Active
                state "Active Session" as Active {
                    direction TB
                    [*] --> Auth
                    Auth --> Ready : token ok
                    --
                    [*] --> Syncing
                    Syncing --> Synced : done
                }
                Active --> is_valid
                is_valid --> join_state : [ok]
                join_state --> [*]

                note right of Active : Monitored session
                note left of is_valid
                    Evaluates fraud
                    and inventory
                end note
                classDef highlight fill:#d3e5f5,stroke:#205ea6
                class Auth highlight
        """.trimIndent()

        val d = StateParser.parse(src)
        assertEquals(StateKind.Fork, assertNotNull(d.state("fork_state")).kind)
        assertEquals(StateKind.Join, assertNotNull(d.state("join_state")).kind)
        assertEquals(StateKind.Choice, assertNotNull(d.state("is_valid")).kind)

        val active = assertNotNull(d.state("Active"))
        assertEquals(StateKind.Composite, active.kind)
        assertEquals("Active Session", active.label)
        assertEquals(Direction.TB, active.direction)
        assertEquals(2, active.regionCount)

        val auth = assertNotNull(d.state("Auth"))
        assertEquals("Active", auth.parentId)
        assertEquals(0, auth.regionIndex)
        assertEquals("#d3e5f5", auth.style?.fill)

        val syncing = assertNotNull(d.state("Syncing"))
        assertEquals("Active", syncing.parentId)
        assertEquals(1, syncing.regionIndex)

        assertEquals(2, d.notes.size)
        assertEquals(StateNotePosition.Right, d.notes[0].position)
        assertEquals("Active", d.notes[0].forStateId)
        assertEquals("Monitored session", d.notes[0].text)
        assertEquals(StateNotePosition.Left, d.notes[1].position)
        assertEquals("is_valid", d.notes[1].forStateId)
        assertEquals("Evaluates fraud\nand inventory", d.notes[1].text)
    }

    @Test
    fun preservesQuotesArrowsAndBracesInsideQuotedStringsAndUnquotedLabels() {
        val src = """
            stateDiagram-v2
                state "A --> B {quoted} \"ok\"" as S1
                S1 --> S2 : a --> b : c {payload}
                S2 : description with {braces}
                state Container {
                    BareOne
                    --
                    BareChoice <<choice>>
                }
                note right of S1
                    literal {brace} inside note
                end note
        """.trimIndent()

        val d = StateParser.parse(src)
        val s1 = assertNotNull(d.state("S1"))
        assertEquals("A --> B {quoted} \"ok\"", s1.label)
        assertEquals(1, d.transitions.size)
        assertEquals("S1", d.transitions[0].from)
        assertEquals("S2", d.transitions[0].to)
        assertEquals("a --> b : c {payload}", d.transitions[0].label)

        val s2 = assertNotNull(d.state("S2"))
        assertEquals(listOf("description with {braces}"), s2.descriptions)

        val bareOne = assertNotNull(d.state("BareOne"))
        assertEquals("Container", bareOne.parentId)
        assertEquals(0, bareOne.regionIndex)

        val bareChoice = assertNotNull(d.state("BareChoice"))
        assertEquals("Container", bareChoice.parentId)
        assertEquals(1, bareChoice.regionIndex)
        assertEquals(StateKind.Choice, bareChoice.kind)

        assertEquals(1, d.notes.size)
        assertEquals("literal {brace} inside note", d.notes[0].text)
    }

    @Test
    fun preservesOwnershipOnTransitionReferencesAndStripsInlineClassesFromLabels() {
        val src = """
            stateDiagram-v2
                classDef red fill:#ffdddd,stroke:#cc0000
                state Outside:::red
                state Outer {
                    state Inner {
                        Leaf --> Outer
                        Leaf --> Outside
                    }
                }
        """.trimIndent()

        val d = StateParser.parse(src)
        val outside = assertNotNull(d.state("Outside"))
        assertEquals("Outside", outside.label)
        assertEquals("#ffdddd", outside.style?.fill)
        assertEquals(null, outside.parentId)

        val outer = assertNotNull(d.state("Outer"))
        assertEquals(null, outer.parentId)

        val inner = assertNotNull(d.state("Inner"))
        assertEquals("Outer", inner.parentId)

        val leaf = assertNotNull(d.state("Leaf"))
        assertEquals("Inner", leaf.parentId)

        // Ensure layout completes cleanly without parent cycles.
        val layout = dev.maradona.layout.StateLayout.layout(d)
        assertTrue(layout.width > 0f && layout.height > 0f)

        // Forward styling directives (`class A red` before `state C { A --> B }`) must not prevent A from belonging to C,
        // and quoted labels containing `<<choice>>` must stay literal normal states.
        val forwardStyled = StateParser.parse(
            """
            stateDiagram-v2
                classDef red fill:#ffdddd
                class A red
                state "Wait <<choice>> here" as QuotedLiteral
                state C {
                    A --> B
                }
            """.trimIndent(),
        )
        val stateA = assertNotNull(forwardStyled.state("A"))
        assertEquals("C", stateA.parentId)
        assertEquals("#ffdddd", stateA.style?.fill)

        val quoted = assertNotNull(forwardStyled.state("QuotedLiteral"))
        assertEquals(StateKind.Normal, quoted.kind)
        assertEquals("Wait <<choice>> here", quoted.label)

        // Leading blank and comment lines before YAML frontmatter must still parse frontmatter metadata.
        val leadingBlankFm = StateParser.parse(
            "\n  \n%% leading comment\n---\ntitle: Leading Blank Title\nhideEmptyDescription: true\n---\nstateDiagram-v2\nA --> B",
        )
        assertEquals("Leading Blank Title", leadingBlankFm.title)
        assertTrue(leadingBlankFm.hideEmptyDescription)
        assertEquals(listOf("A", "B"), leadingBlankFm.states.map { it.id })
    }
}
