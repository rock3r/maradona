package dev.maradona.export

import dev.maradona.layout.DiagramSize
import dev.maradona.layout.FlowchartLayout
import dev.maradona.parse.FlowchartParser
import dev.maradona.render.MaradonaTheme
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
        assertTrue(svg.contains("<ellipse "), "Expected cylinder cap ellipse in SVG")
        assertTrue(svg.contains("<a href=\"https://example.com/login\" target=\"_blank\">"))
        assertTrue(svg.contains("<title>Open login page</title>"))
    }

    @Test
    fun exportsWithCustomJewelDarkThemeAndAlphaColors() {
        val mmd = """
            flowchart LR
                A[Ghost] -->|Line 1<br/>Line 2| B[/Parallelogram/]
                style A fill:transparent,stroke:#3574F0
                style B fill:#3574F080,stroke:#3574F0
        """.trimIndent()

        val jewelDark = MaradonaTheme.jewelDark()
        val svg = MaradonaSvgExporter.exportToSvg(mmd, jewelDark)
        assertTrue(svg.contains("fill=\"#1E1F22\""), "Expected Jewel dark background #1E1F22 in SVG")
        assertTrue(svg.contains("fill=\"none\""), "Expected fill:transparent to serialize as fill=\"none\"")
        assertTrue(svg.contains("fill=\"rgba(53,116,240,"), "Expected 8-digit hex alpha to serialize as rgba(...)")
        assertTrue(svg.contains(">Line 1</text>") && svg.contains(">Line 2</text>"), "Expected multiline edge label in SVG")
    }

    @Test
    fun wrapsLongLabelsStripsIllegalXmlCharsAndRejectsNonFlowchart() {
        val wrappedAndControlSvg = MaradonaSvgExporter.exportToSvg(
            "flowchart TD\n    Long[\"Open https://example.com/a/very/long/unbroken/resource/path/that/exceeds/wrapping/width\u0001 and wraps onto multiple SVG text lines\"]",
        )
        assertFalse(wrappedAndControlSvg.contains("\u0001"), "Expected illegal XML control char U+0001 to be stripped")
        val textTagCount = Regex("<text ").findAll(wrappedAndControlSvg).count()
        assertTrue(textTagCount >= 3, "Expected long flowchart label with oversized second token to wrap into at least 3 <text> lines, got $textTagCount")

        assertFailsWith<UnsupportedOperationException> {
            MaradonaSvgExporter.exportToSvg("sequenceDiagram\n    Alice->>Bob: Hi")
        }
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

        // Nested sibling subgraphs inside a parent subgraph + unclustered nodes on the same rank must not overlap.
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

        // Cross-subgraph edges after sibling separation must not pass through unrelated nodes.
        val crossEdgeMmd = """
            flowchart TB
                subgraph s0
                    n0
                    n1
                    n3
                    n4
                end
                subgraph s1
                    n2
                    n6
                end
                n0 --> n1
                n0 --> n3
                n1 --> n4
                n3 --> n6
        """.trimIndent()
        val crossChart = FlowchartParser.parse(crossEdgeMmd)
        val crossLayout = FlowchartLayout.layout(
            chart = crossChart,
            nodeSize = { DiagramSize(100f, 44f) },
            edgeLabelSize = { null },
        )
        for (pe in crossLayout.edges) {
            for (pn in crossLayout.nodes) {
                if (pn.node.id == pe.edge.from || pn.node.id == pe.edge.to) continue
                for (i in 0 until pe.points.lastIndex) {
                    assertFalse(
                        dev.maradona.layout.ClassLayout.segmentIntersectsRect(pe.points[i], pe.points[i + 1], pn.rect),
                        "Edge ${pe.edge.from}->${pe.edge.to} segment (${pe.points[i]} -> ${pe.points[i + 1]}) must not intersect unrelated node ${pn.node.id} (${pn.rect})",
                    )
                }
            }
        }

        // Repositioned cross-subgraph routes must be included in the normalized layout bounds.
        val boundsEdgeMmd = """
            flowchart TB
                subgraph s1
                    n0
                    n3
                    n6
                end
                subgraph s0
                    n1
                    n2
                    n4
                    n5
                    n7
                end
                n0 -->|L| n3
                n0 -->|L| n4
                n0 -->|L| n5
                n0 -->|L| n7
                n2 -->|L| n6
                n5 -->|L| n6
                n5 -->|L| n7
                n6 -->|L| n7
        """.trimIndent()
        val boundsChart = FlowchartParser.parse(boundsEdgeMmd)
        val boundsLayout = FlowchartLayout.layout(
            chart = boundsChart,
            nodeSize = { DiagramSize(100f, 44f) },
            edgeLabelSize = { DiagramSize(40f, 18f) },
        )
        for (pe in boundsLayout.edges) {
            for (p in pe.points) {
                assertTrue(
                    p.x >= 0f && p.x <= boundsLayout.width && p.y >= 0f && p.y <= boundsLayout.height,
                    "Edge ${pe.edge.from}->${pe.edge.to} point $p must fit within layout bounds (${boundsLayout.width}x${boundsLayout.height})",
                )
            }
        }
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
