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

        // Verify Class members, Pie slices, Sankey links, wrapped labels, and XML control char stripping.
        val classSvg = MaradonaSvgExporter.exportToSvg(samples[1])
        assertTrue(classSvg.contains("+String id"), "Expected class attribute in SVG")
        assertTrue(classSvg.contains("+submit()"), "Expected class method in SVG")

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
                Root --> Free[Unclustered Peer]
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
        val freeNode = assertNotNull(nestedLayout.node("Free"))

        fun rectsOverlap(a: dev.maradona.layout.DiagramRect, b: dev.maradona.layout.DiagramRect): Boolean =
            a.x < b.right && a.right > b.x && a.y < b.bottom && a.bottom > b.y

        assertFalse(rectsOverlap(innerA.rect, innerB.rect), "Nested siblings innerA and innerB must not overlap")
        assertFalse(rectsOverlap(outerLeft.rect, outerRight.rect), "Parent outerLeft and sibling outerRight must not overlap after inner expansion")
        assertFalse(rectsOverlap(outerLeft.rect, freeNode.rect), "outerLeft must not overlap unclustered Free node")
        assertFalse(rectsOverlap(outerRight.rect, freeNode.rect), "outerRight must not overlap unclustered Free node")
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
