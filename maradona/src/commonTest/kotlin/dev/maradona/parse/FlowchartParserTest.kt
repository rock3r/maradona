package dev.maradona.parse

import dev.maradona.model.ArrowHead
import dev.maradona.model.Direction
import dev.maradona.model.EdgeStroke
import dev.maradona.model.NodeShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowchartParserTest {

    @Test
    fun basicChainWithDecision() {
        val chart = FlowchartParser.parse(
            """
            flowchart TD
              A[Mermaid text] --> B{Flowchart?}
              B -- Yes --> C[Pele]
              B -- No --> D[Mermaid]
              C --> E([SVG])
              D --> E
            """.trimIndent(),
        )
        assertEquals(Direction.TB, chart.direction)
        assertEquals(listOf("A", "B", "C", "D", "E"), chart.nodes.map { it.id })
        assertEquals(NodeShape.Diamond, chart.nodes.first { it.id == "B" }.shape)
        assertEquals(NodeShape.Stadium, chart.nodes.first { it.id == "E" }.shape)
        assertEquals(5, chart.edges.size)
        assertEquals("Yes", chart.edges[1].label)
        assertEquals("No", chart.edges[2].label)
        assertEquals(ArrowHead.Arrow, chart.edges[0].endHead)
    }

    @Test
    fun shapes() {
        val chart = FlowchartParser.parse(
            """
            graph LR
              A[rect] --> B(rounded) --> C[(db)] --> D((circle)) --> E{{hex}} --> F[/para/] --> G[[sub]] --> H>flag]
            """.trimIndent(),
        )
        assertEquals(Direction.LR, chart.direction)
        val byId = chart.nodes.associate { it.id to it.shape }
        assertEquals(NodeShape.Rect, byId["A"])
        assertEquals(NodeShape.RoundedRect, byId["B"])
        assertEquals(NodeShape.Cylinder, byId["C"])
        assertEquals(NodeShape.Circle, byId["D"])
        assertEquals(NodeShape.Hexagon, byId["E"])
        assertEquals(NodeShape.Parallelogram, byId["F"])
        assertEquals(NodeShape.Subroutine, byId["G"])
        assertEquals(NodeShape.Asymmetric, byId["H"])
    }

    @Test
    fun edgeStylesAndHeads() {
        val chart = FlowchartParser.parse(
            """
            flowchart TD
              A ==> B
              B -.-> C
              C --- D
              D --x E
              E --o F
              F <--> G
            """.trimIndent(),
        )
        assertEquals(EdgeStroke.Thick, chart.edges[0].stroke)
        assertEquals(EdgeStroke.Dotted, chart.edges[1].stroke)
        assertEquals(ArrowHead.None, chart.edges[2].endHead)
        assertEquals(ArrowHead.Cross, chart.edges[3].endHead)
        assertEquals(ArrowHead.Circle, chart.edges[4].endHead)
        assertEquals(ArrowHead.Arrow, chart.edges[5].startHead)
        assertEquals(ArrowHead.Arrow, chart.edges[5].endHead)
    }

    @Test
    fun pipeLabelsAndGroups() {
        val chart = FlowchartParser.parse(
            """
            flowchart TD
              A & B -->|both| C
            """.trimIndent(),
        )
        assertEquals(2, chart.edges.size)
        assertTrue(chart.edges.all { it.label == "both" && it.to == "C" })
    }

    @Test
    fun quotedLabelsAndMarkup() {
        val chart = FlowchartParser.parse(
            """
            flowchart TD
              A["Hello; world<br><b>bold</b>"] --> B
            """.trimIndent(),
        )
        assertEquals("Hello; world\n**bold**", chart.nodes.first().label)
    }

    @Test
    fun subgraphAndStyle() {
        val chart = FlowchartParser.parse(
            """
            flowchart TD
              subgraph engine[Engine]
                P[Parser] --> L[Layout]
              end
              L --> R[Render]
              style R fill:#ddeeff,stroke:#205ea6,color:#111111
            """.trimIndent(),
        )
        assertEquals(1, chart.subgraphs.size)
        assertEquals("Engine", chart.subgraphs[0].label)
        assertEquals("engine", chart.nodes.first { it.id == "P" }.subgraphId)
        assertEquals(null, chart.nodes.first { it.id == "R" }.subgraphId)
        val style = chart.nodes.first { it.id == "R" }.style
        assertNotNull(style)
        assertEquals("#ddeeff", style.fill)
    }

    @Test
    fun commentsAndSemicolons() {
        val chart = FlowchartParser.parse(
            """
            flowchart LR %% direction comment
              A --> B; B --> C %% trailing
            """.trimIndent(),
        )
        assertEquals(3, chart.nodes.size)
        assertEquals(2, chart.edges.size)
    }

    @Test
    fun errorsHaveLineNumbers() {
        val ex = assertFailsWith<MaradonaParseException> {
            FlowchartParser.parse("flowchart TD\n  A[unclosed --> B")
        }
        assertEquals(2, ex.line)
    }

    @Test
    fun rejectsWrongDiagramType() {
        assertFailsWith<MaradonaParseException> {
            FlowchartParser.parse("sequenceDiagram\n  A->>B: hi")
        }
    }
}
