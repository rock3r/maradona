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

    @Test
    fun classDefAndInlineClass() {
        val chart = FlowchartParser.parse(
            """
            flowchart LR
              A[First]:::hot --> B[Second] --> C[Third]
              classDef default fill:#eeeeee,stroke:#444444
              classDef hot fill:#ff0000,stroke:#990000,stroke-width:3px
              classDef cool fill:#00ff00,color:#003300
              class B cool
              style A fill:#ffcccc
            """.trimIndent(),
        )
        val a = chart.nodes.first { it.id == "A" }
        val b = chart.nodes.first { it.id == "B" }
        val c = chart.nodes.first { it.id == "C" }
        // Explicit style overrides classDef fill, while keeping classDef stroke & strokeWidth
        assertEquals("#ffcccc", a.style?.fill)
        assertEquals("#990000", a.style?.stroke)
        assertEquals("3px", a.style?.strokeWidth)
        assertEquals("#00ff00", b.style?.fill)
        assertEquals("#444444", b.style?.stroke)
        assertEquals("#003300", b.style?.textColor)
        assertEquals("#eeeeee", c.style?.fill)
        assertEquals("#444444", c.style?.stroke)
    }

    @Test
    fun linkStyleAndBidirectionalHeads() {
        val chart = FlowchartParser.parse(
            """
            flowchart LR
              A o--o B
              B x--x C
              linkStyle default stroke:#888888,stroke-width:2px
              linkStyle 1 stroke:#ff0000
            """.trimIndent(),
        )
        assertEquals(ArrowHead.Circle, chart.edges[0].startHead)
        assertEquals(ArrowHead.Circle, chart.edges[0].endHead)
        assertEquals("#888888", chart.edges[0].style?.stroke)
        assertEquals("2px", chart.edges[0].style?.strokeWidth)
        assertEquals(ArrowHead.Cross, chart.edges[1].startHead)
        assertEquals(ArrowHead.Cross, chart.edges[1].endHead)
        assertEquals("#ff0000", chart.edges[1].style?.stroke)
        assertEquals("2px", chart.edges[1].style?.strokeWidth)
    }

    @Test
    fun frontmatterInitDirectiveAndV2ShapeMetadata() {
        val chart = FlowchartParser.parse(
            """
            ---
            title: Example
            config:
              layout: dagre
            ---
            %%{init: {
              'theme': 'default'
            }}%%
            flowchart TD
              A@{ shape: cyl, label: "Orders DB" } e1@--> B@{ shape: hex, label: 'Gateway } 100%%' }
              e1@{ animate: true }
            """.trimIndent(),
        )
        assertEquals(2, chart.nodes.size)
        val a = chart.nodes.first { it.id == "A" }
        val b = chart.nodes.first { it.id == "B" }
        assertEquals(NodeShape.Cylinder, a.shape)
        assertEquals("Orders DB", a.label)
        assertEquals(NodeShape.Hexagon, b.shape)
        assertEquals("Gateway } 100%%", b.label)
    }

    @Test
    fun edgeIncidentOnSubgraphIdPreservesSubgraphTarget() {
        val chart = FlowchartParser.parse(
            """
            flowchart TD
              subgraph one[Group One]
                A[Don't panic] --> two
              end
              subgraph two[Group Two]
                B[Second]
              end
              Start --> one
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B", "Start"), chart.nodes.map { it.id })
        assertEquals("Don't panic", chart.nodes.first { it.id == "A" }.label)
        assertEquals("two", chart.edges.first().to)
        val incoming = chart.edges.last()
        assertEquals("Start", incoming.from)
        assertEquals("one", incoming.to)
    }
}
