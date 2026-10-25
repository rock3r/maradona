package dev.maradona.export

import dev.maradona.layout.DiagramSize
import dev.maradona.layout.FlowchartLayout
import dev.maradona.parse.FlowchartParser
import dev.maradona.render.MaradonaTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaradonaSvgExporterTest {

    @Test
    fun exportsFlowchartWithSubgraphsStylesAndClickableLinks() {
        val mmd = """
            flowchart TD
                subgraph sgA["Frontend &amp; UI"]
                    A["Login <Form>"]:::highlight --> B("Dashboard")
                end
                subgraph sgB["Backend API"]
                    C{{"Auth Gateway"}} --> D[("User DB")]
                end
                B ==>|HTTPS| C
                classDef highlight fill:#DBEAFE,stroke:#2563EB,color:#1E3A8A
                click A "https://example.com/login" "Open login page" _blank
        """.trimIndent()

        val svg = MaradonaSvgExporter.exportToSvg(mmd, MaradonaTheme.light())
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.endsWith("</svg>"))
        assertTrue(svg.contains("data-id=\"sgA\""))
        assertTrue(svg.contains("data-id=\"sgB\""))
        assertTrue(svg.contains("Login &lt;Form&gt;"))
        assertTrue(svg.contains("fill=\"#DBEAFE\""))
        assertTrue(svg.contains("stroke=\"#2563EB\""))
        assertTrue(svg.contains("<a href=\"https://example.com/login\" target=\"_blank\">"))
        assertTrue(svg.contains("<title>Open login page</title>"))
    }

    @Test
    fun exportsWithCustomJewelDarkTheme() {
        val mmd = """
            C4Context
                title Payment System Context
                Person(user, "Customer", "Uses checkout")
                System(pay, "Payment Gateway", "Processes cards", ${'$'}link="https://pay.example.com")
                Rel(user, pay, "Submits payment")
        """.trimIndent()

        val jewelDark = MaradonaTheme.jewelDark()
        val svg = MaradonaSvgExporter.exportToSvg(mmd, jewelDark)
        assertTrue(svg.contains("fill=\"#1E1F22\""), "Expected Jewel dark background #1E1F22 in SVG")
        assertTrue(svg.contains("<a href=\"https://pay.example.com\">"))
        assertTrue(svg.contains("Payment System Context"))
    }

    @Test
    fun exportsMultipleDiagramFamiliesToValidSvg() {
        val samples = listOf(
            """
            sequenceDiagram
                Alice->>Bob: Hello **Bob**
                Note right of Bob: Thinking
                Bob-->>Alice: Ack
            """.trimIndent(),
            """
            classDiagram
                class Order {
                    +String id
                    +submit()
                }
                class LineItem
                Order *-- LineItem : contains
                link Order "https://example.com/order" "Order docs"
            """.trimIndent(),
            """
            stateDiagram-v2
                [*] --> Idle
                Idle --> Processing : start
                Processing --> [*]
            """.trimIndent(),
            """
            erDiagram
                CUSTOMER ||--o{ ORDER : places
            """.trimIndent(),
            """
            architecture-beta
                group cloud(cloud)[Cloud]
                service api(server)[API] in cloud
                service db(database)[DB] in cloud
                api:R --> L:db
            """.trimIndent(),
            """
            zenuml
                title Checkout
                Client->OrderService.create() {
                    BillingService.charge()
                }
            """.trimIndent(),
        )

        for (src in samples) {
            val svg = MaradonaSvgExporter.exportToSvg(src)
            assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""), "Expected valid SVG header for:\n$src")
            assertTrue(svg.contains("</svg>"))
        }

        // Verify Class members, relation markers, Sequence blocks, ER attributes, XY chart axes/ticks, Timeline events, Pie slices, Sankey links, transparent/rgba colors, wrapped labels, and XML control char stripping.
        val seqSvg = MaradonaSvgExporter.exportToSvg(
            """
            sequenceDiagram
                Alice->>Alice: Self check
                Alice->>Bob: Request
                alt Cache hit
                    Bob-->>Alice: Fast response
                else Cache miss
                    Bob-->>Alice: Slow response
                end
            """.trimIndent(),
        )
        assertTrue(seqSvg.contains("[Cache hit]"), "Expected sequence alt block condition in SVG")
        assertTrue(seqSvg.contains("[Cache miss]"), "Expected sequence else divider label in SVG")

        val stateSvg = MaradonaSvgExporter.exportToSvg(
            """
            stateDiagram-v2
                state Check <<choice>>
                [*] --> Check
                Check --> Idle
                Idle --> [*]
            """.trimIndent(),
        )
        assertTrue(stateSvg.contains("<circle "), "Expected start/end circle pseudonodes in state SVG")
        assertTrue(stateSvg.contains("<polygon "), "Expected choice diamond polygon in state SVG")

        val erSvg = MaradonaSvgExporter.exportToSvg(
            """
            erDiagram
                CUSTOMER ||--o{ ORDER : places
                CUSTOMER {
                    string id PK "Primary key"
                    string email UK
                }
            """.trimIndent(),
        )
        assertTrue(erSvg.contains("string id PK"), "Expected ER entity attribute in SVG")
        assertTrue(erSvg.contains("string email UK"), "Expected ER entity second attribute in SVG")
        assertTrue(erSvg.contains("data-marker=\"er-exactly-one\""), "Expected ER exactly-one cardinality marker in SVG")
        assertTrue(erSvg.contains("data-marker=\"er-zero-or-more\""), "Expected ER zero-or-more cardinality marker in SVG")

        val donutSingleSvg = MaradonaSvgExporter.exportToSvg(
            """
            ---
            config:
              pie:
                donutHole: 0.5
            ---
            pie title Single Donut
                "Only" : 100
            """.trimIndent(),
        )
        assertTrue(donutSingleSvg.contains("fill-rule=\"evenodd\""), "Expected single-slice donut to render annulus with fill-rule=\"evenodd\"")

        val quadrantSvg = MaradonaSvgExporter.exportToSvg(
            """
            quadrantChart
                title Reach vs Engagement
                x-axis Low --> High
                y-axis Low --> High
                Campaign A: [0.3, 0.7]
            """.trimIndent(),
        )
        assertTrue(quadrantSvg.contains("class=\"quadrant-point\""), "Expected quadrant data point circle in SVG")

        val ganttSvg = MaradonaSvgExporter.exportToSvg(
            """
            gantt
                title Release Plan
                dateFormat YYYY-MM-DD
                section Phase 1
                Design :a1, 2026-01-01, 2d
                Ship :milestone, m1, 2026-01-03, 0d
            """.trimIndent(),
        )
        assertTrue(ganttSvg.contains("Phase 1") && ganttSvg.contains("Design") && ganttSvg.contains("Ship"), "Expected Gantt section and task labels in SVG")

        val gitHiddenLabelsSvg = MaradonaSvgExporter.exportToSvg(
            """
            ---
            config:
              gitGraph:
                showCommitLabel: false
            ---
            gitGraph
                commit id: "secret-sha" tag: "v1.0"
            """.trimIndent(),
        )
        assertFalse(gitHiddenLabelsSvg.contains("secret-sha"), "Expected showCommitLabel: false to omit commit ID in SVG")
        assertTrue(gitHiddenLabelsSvg.contains("v1.0"), "Expected commit tag v1.0 in SVG")

        val xySvg = MaradonaSvgExporter.exportToSvg(
            """
            xychart-beta
                title "Quarterly Revenue"
                x-axis "Quarter" [Q1, Q2, Q3]
                y-axis "Revenue (M)" 0 --> 100
                bar [30, 60, 90]
                line [50, 70, 85]
            """.trimIndent(),
        )
        assertTrue(xySvg.contains("Quarter"), "Expected XY chart X-axis title in SVG")
        assertTrue(xySvg.contains("Revenue (M)"), "Expected XY chart Y-axis title in SVG")
        assertTrue(xySvg.contains("transform=\"rotate(-90 "), "Expected rotated Y-axis title in SVG")
        assertTrue(xySvg.contains("class=\"xy-line-point\""), "Expected XY line point markers in SVG")
        assertTrue(xySvg.contains("Q1") && xySvg.contains("Q2") && xySvg.contains("Q3"), "Expected XY chart X-axis tick labels in SVG")

        val classSvg = MaradonaSvgExporter.exportToSvg(
            """
            classDiagram
                class Order {
                    +String id
                    +submit()
                }
                class LineItem
                class SpecialOrder
                Order *-- LineItem : contains
                Order <|-- SpecialOrder
                link Order "https://example.com/order" "Order docs"
            """.trimIndent(),
        )
        assertTrue(classSvg.contains("+String id"), "Expected class attribute in SVG")
        assertTrue(classSvg.contains("+submit()"), "Expected class method in SVG")
        assertTrue(classSvg.contains("data-marker=\"filled-diamond\""), "Expected composition diamond marker in SVG")
        assertTrue(classSvg.contains("data-marker=\"hollow-triangle\""), "Expected extension hollow-triangle marker in SVG")

        val timelineSvg = MaradonaSvgExporter.exportToSvg(
            """
            timeline
                section Roadmap
                2026 : Launch : Scale
            """.trimIndent(),
        )
        assertTrue(timelineSvg.contains("Roadmap"), "Expected timeline section in SVG")
        assertTrue(timelineSvg.contains("2026"), "Expected timeline period in SVG")
        assertTrue(timelineSvg.contains("Launch"), "Expected timeline event 'Launch' in SVG")
        assertTrue(timelineSvg.contains("Scale"), "Expected timeline event 'Scale' in SVG")

        val alphaSvg = MaradonaSvgExporter.exportToSvg(
            """
            flowchart LR
                A[ Ghost ] --> B[ Translucent ]
                style A fill:transparent,stroke:#2563EB
                style B fill:#2563EB80,stroke:#1D4ED8
            """.trimIndent(),
        )
        assertTrue(alphaSvg.contains("fill=\"none\""), "Expected fill:transparent to serialize as fill=\"none\"")
        assertTrue(alphaSvg.contains("fill=\"rgba(37,99,235,"), "Expected 8-digit hex alpha to serialize as rgba(...)")

        val pieSvg = MaradonaSvgExporter.exportToSvg(
            """
            pie title Cloud Spend
                "Compute" : 65
                "Storage" : 35
            """.trimIndent(),
        )
        assertTrue(pieSvg.contains("class=\"pie-slice\""), "Expected pie slice path in SVG")
        assertTrue(pieSvg.contains("65%"), "Expected pie slice percentage in SVG")

        val sankeySvg = MaradonaSvgExporter.exportToSvg(
            """
            sankey-beta
            Solar,Grid,120
            Wind,Grid,80
            """.trimIndent(),
        )
        assertTrue(sankeySvg.contains("class=\"sankey-link\""), "Expected sankey link ribbon in SVG")

        val wrappedAndControlSvg = MaradonaSvgExporter.exportToSvg(
            "flowchart TD\n    Long[\"This is a very long flowchart node label that exceeds the default two hundred pixel wrapping width\u0001 and wraps onto multiple SVG text lines\"]",
        )
        assertFalse(wrappedAndControlSvg.contains("\u0001"), "Expected illegal XML control char U+0001 to be stripped")
        val textTagCount = Regex("<text ").findAll(wrappedAndControlSvg).count()
        assertTrue(textTagCount >= 2, "Expected long flowchart label to wrap into at least 2 <text> lines, got $textTagCount")
    }

    @Test
    fun siblingSubgraphsWithInterleavedDeclarationsDoNotOverlap() {
        val mmd = """
            flowchart TB
                Start([Entry]) --> A1[Alpha 1]
                Start --> B1[Beta 1]
                Start --> A2[Alpha 2]
                Start --> B2[Beta 2]
                A1 --> A3[Alpha 3]
                A2 --> A3
                B1 --> B3[Beta 3]
                B2 --> B3
                subgraph sgA [Alpha Cluster]
                    A1
                    A2
                    A3
                end
                subgraph sgB [Beta Cluster]
                    B1
                    B2
                    B3
                end
        """.trimIndent()

        val chart = FlowchartParser.parse(mmd)
        val layout = FlowchartLayout.layout(
            chart = chart,
            nodeSize = { DiagramSize(100f, 44f) },
            edgeLabelSize = { null },
        )
        val sgA = layout.subgraphs.find { it.subgraph.id == "sgA" }
        val sgB = layout.subgraphs.find { it.subgraph.id == "sgB" }
        assertNotNull(sgA)
        assertNotNull(sgB)
        assertTrue(sgA.subgraph.label == "Alpha Cluster")
        assertTrue(sgB.subgraph.label == "Beta Cluster")

        for (id in listOf("A1", "A2", "A3")) {
            val n = assertNotNull(layout.node(id))
            assertTrue(
                n.rect.x >= sgA.rect.x && n.rect.right <= sgA.rect.right &&
                    n.rect.y >= sgA.rect.y && n.rect.bottom <= sgA.rect.bottom,
                "Expected $id (${n.rect}) inside sgA (${sgA.rect})",
            )
        }
        for (id in listOf("B1", "B2", "B3")) {
            val n = assertNotNull(layout.node(id))
            assertTrue(
                n.rect.x >= sgB.rect.x && n.rect.right <= sgB.rect.right &&
                    n.rect.y >= sgB.rect.y && n.rect.bottom <= sgB.rect.bottom,
                "Expected $id (${n.rect}) inside sgB (${sgB.rect})",
            )
        }

        val overlapsHorizontally = sgA.rect.x < sgB.rect.right && sgA.rect.right > sgB.rect.x
        val overlapsVertically = sgA.rect.y < sgB.rect.bottom && sgA.rect.bottom > sgB.rect.y
        assertFalse(
            overlapsHorizontally && overlapsVertically,
            "Sibling subgraphs sgA (${sgA.rect}) and sgB (${sgB.rect}) must not overlap when node edges are interleaved",
        )

        // Nested sibling subgraphs inside a parent subgraph + unclustered node on the same rank must not overlap.
        val nestedMmd = """
            flowchart TB
                Root([Root]) --> N1[Inner A1]
                Root --> M1[Inner B1]
                Root --> N2[Inner A2]
                Root --> M2[Inner B2]
                Root --> P1[Peer Cluster Node]
                Root --> Free1[Unclustered Peer 1]
                Root --> Free2[Unclustered Peer 2]
                subgraph outerLeft [Outer Left]
                    subgraph innerA [Inner A]
                        N1
                        N2
                    end
                    subgraph innerB [Inner B]
                        M1
                        M2
                    end
                end
                subgraph outerRight [Outer Right]
                    P1
                end
        """.trimIndent()
        val nestedChart = FlowchartParser.parse(nestedMmd)
        val nestedLayout = FlowchartLayout.layout(
            chart = nestedChart,
            nodeSize = { DiagramSize(100f, 44f) },
            edgeLabelSize = { null },
        )
        val innerA = assertNotNull(nestedLayout.subgraphs.find { it.subgraph.id == "innerA" })
        val innerB = assertNotNull(nestedLayout.subgraphs.find { it.subgraph.id == "innerB" })
        val outerLeft = assertNotNull(nestedLayout.subgraphs.find { it.subgraph.id == "outerLeft" })
        val outerRight = assertNotNull(nestedLayout.subgraphs.find { it.subgraph.id == "outerRight" })
        val freeNode1 = assertNotNull(nestedLayout.node("Free1"))
        val freeNode2 = assertNotNull(nestedLayout.node("Free2"))

        fun rectsOverlap(a: dev.maradona.layout.DiagramRect, b: dev.maradona.layout.DiagramRect): Boolean =
            a.x < b.right && a.right > b.x && a.y < b.bottom && a.bottom > b.y

        assertFalse(rectsOverlap(innerA.rect, innerB.rect), "Nested siblings innerA and innerB must not overlap")
        assertFalse(rectsOverlap(outerLeft.rect, outerRight.rect), "Parent outerLeft and sibling outerRight must not overlap after inner expansion")
        assertFalse(rectsOverlap(outerLeft.rect, freeNode1.rect), "outerLeft must not overlap unclustered Free1 node")
        assertFalse(rectsOverlap(outerRight.rect, freeNode1.rect), "outerRight must not overlap unclustered Free1 node")
        assertFalse(rectsOverlap(freeNode1.rect, freeNode2.rect), "Shifted unclustered Free1 and Free2 nodes must not overlap each other")
    }

    @Test
    fun scalesToLargeMultiSubgraphFlowchartAndExportsCleanly() {
        val sb = StringBuilder("flowchart TB\n")
        val clusterCount = 6
        val nodesPerCluster = 20
        for (c in 0 until clusterCount) {
            sb.append("    subgraph cluster_$c [Cluster $c]\n")
            for (i in 0 until nodesPerCluster) {
                val id = "N_${c}_$i"
                sb.append("        $id[Node $c-$i]\n")
                if (i > 0) {
                    val prev = "N_${c}_${i - 1}"
                    sb.append("        $prev --> $id\n")
                }
                if (i > 2 && i % 4 == 0) {
                    val skip = "N_${c}_${i - 3}"
                    sb.append("        $skip -.-> $id\n")
                }
            }
            sb.append("    end\n")
            if (c > 0) {
                sb.append("    N_${c - 1}_${nodesPerCluster - 1} ==> N_${c}_0\n")
            }
        }

        val svg = MaradonaSvgExporter.exportToSvg(sb.toString())
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("data-id=\"cluster_0\""))
        assertTrue(svg.contains("data-id=\"cluster_5\""))
        assertTrue(svg.contains("data-id=\"N_5_19\""))
    }
}
