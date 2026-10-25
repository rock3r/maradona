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
    fun wrapsLongLabelsStripsIllegalXmlCharsAndExportsAll23DiagramFamilies() {
        val wrappedAndControlSvg = MaradonaSvgExporter.exportToSvg(
            "flowchart TD\n    Long[\"Open https://example.com/a/very/long/unbroken/resource/path/that/exceeds/wrapping/width\u0001 and wraps onto multiple SVG text lines\"]",
        )
        assertFalse(wrappedAndControlSvg.contains("\u0001"), "Expected illegal XML control char U+0001 to be stripped")
        val textTagCount = Regex("<text ").findAll(wrappedAndControlSvg).count()
        assertTrue(textTagCount >= 3, "Expected long flowchart label with oversized second token to wrap into at least 3 <text> lines, got $textTagCount")

        // 9. Mindmap
        val mindmapSvg = MaradonaSvgExporter.exportToSvg(
            """
            mindmap
              root((Maradona))
                Core[Flowcharts]
                :::urgent
                Extended{{All 23}}
            """.trimIndent(),
        )
        assertTrue(mindmapSvg.contains("class=\"mindmap-node\""), "Expected mindmap nodes")
        assertTrue(mindmapSvg.contains("class=\"mindmap-edge\""), "Expected mindmap edges")

        // 10. Gantt (with excludes weekends, critical/done/active status, and external task label)
        val ganttSvg = MaradonaSvgExporter.exportToSvg(
            """
            gantt
                title Release Plan
                dateFormat YYYY-MM-DD
                excludes weekends
                section Phase 1
                Critical Task :crit, active, t1, 2026-10-01, 5d
                Milestone 1   :milestone, m1, after t1, 0d
                click t1 href "https://example.com/t1"
            """.trimIndent(),
        )
        assertTrue(ganttSvg.contains("class=\"gantt-excluded-band\""), "Expected Gantt excluded-day shading")
        assertTrue(ganttSvg.contains("class=\"gantt-task\" data-id=\"t1\""), "Expected Gantt task t1")
        assertTrue(ganttSvg.contains("<a href=\"https://example.com/t1\">"), "Expected Gantt task click link")

        // 11. Pie (including single-slice donut annulus)
        val pieDonutSvg = MaradonaSvgExporter.exportToSvg(
            """
            ---
            config:
              pie:
                donutHole: 0.4
            ---
            pie showData
                title Single Slice Donut
                "Only" : 100
            """.trimIndent(),
        )
        assertTrue(pieDonutSvg.contains("fill-rule=\"evenodd\""), "Expected single-slice donut to render annulus with evenodd")

        // 12. GitGraph (with HIGHLIGHT, REVERSE, MERGE, and showCommitLabel: false)
        val gitSvg = MaradonaSvgExporter.exportToSvg(
            """
            gitGraph
                commit id: "c1" tag: "v1.0"
                branch feat
                commit id: "c2" type: HIGHLIGHT
                checkout main
                commit id: "c3" type: REVERSE
                merge feat id: "m1"
            """.trimIndent(),
        )
        assertTrue(gitSvg.contains("data-commit-type=\"highlight\""), "Expected HIGHLIGHT commit marker")
        assertTrue(gitSvg.contains("data-commit-type=\"reverse\""), "Expected REVERSE commit marker")
        assertTrue(gitSvg.contains("data-commit-type=\"merge\""), "Expected MERGE commit marker")

        val gitHiddenLabelsSvg = MaradonaSvgExporter.exportToSvg(
            """
            ---
            config:
              gitGraph:
                showCommitLabel: false
            ---
            gitGraph
                commit id: "secret-sha"
            """.trimIndent(),
        )
        assertFalse(gitHiddenLabelsSvg.contains(">secret-sha</text>"), "Expected showCommitLabel: false to omit commit label text")

        // 13. Timeline (with sections, periods, and events)
        val timelineSvg = MaradonaSvgExporter.exportToSvg(
            """
            timeline
                title Product Roadmap
                section Q4
                    2026 : Launch : Scale
            """.trimIndent(),
        )
        assertTrue(timelineSvg.contains(">2026</text>"), "Expected timeline period")
        assertTrue(timelineSvg.contains(">Launch</text>") && timelineSvg.contains(">Scale</text>"), "Expected timeline events")

        // 14. Journey
        val journeySvg = MaradonaSvgExporter.exportToSvg(
            """
            journey
                title Onboarding
                section Signup
                    Create account: 5: User
                    Verify email: 2: User
            """.trimIndent(),
        )
        assertTrue(journeySvg.contains("class=\"journey-face\" data-mood=\"happy\""), "Expected happy mood face")
        assertTrue(journeySvg.contains("class=\"journey-face\" data-mood=\"sad\""), "Expected sad mood face")

        // 15. Quadrant (points drawn above quadrant backgrounds)
        val quadrantSvg = MaradonaSvgExporter.exportToSvg(
            """
            quadrantChart
                title Campaign Matrix
                x-axis Low --> High
                y-axis Small --> Large
                quadrant-1 Leader
                Campaign A: [0.75, 0.80]
            """.trimIndent(),
        )
        val qPointIdx = quadrantSvg.indexOf("class=\"quadrant-point\"")
        assertTrue(qPointIdx > 0, "Expected quadrant data point circle")

        // 16. XY Chart (axes, ticks, rotated Y-axis title, bars before lines, single-point line marker, plotColorPalette)
        val xySvg = MaradonaSvgExporter.exportToSvg(
            """
            ---
            config:
              themeVariables:
                xyChart:
                  plotColorPalette: "#10B981, #EF4444"
            ---
            xychart-beta
                title "Revenue"
                x-axis [Jan]
                y-axis "USD (k)" 0 --> 100
                bar [40]
                line [65]
            """.trimIndent(),
        )
        val barIdx = xySvg.indexOf("class=\"xy-bar\"")
        val linePtIdx = xySvg.indexOf("class=\"xy-line-point\"")
        assertTrue(barIdx in 1 until linePtIdx, "Expected XY bar to be emitted before XY line point marker")
        assertTrue(xySvg.contains("transform=\"rotate(-90"), "Expected rotated Y-axis title")
        assertTrue(xySvg.contains("fill=\"#10B981\""), "Expected XY bar to use plotColorPalette")
        assertTrue(xySvg.contains("fill=\"#EF4444\""), "Expected XY line point to use plotColorPalette")

        // 17. Kanban (with priority stripe and ticket link)
        val kanbanSvg = MaradonaSvgExporter.exportToSvg(
            """
            ---
            config:
              kanban:
                ticketBaseUrl: 'https://issues.example.com/#TICKET#'
            ---
            kanban
              todo[Todo]
                t1[Fix bug]@{ ticket: MRD-1, assigned: 'rock3r', priority: 'Very High' }
            """.trimIndent(),
        )
        assertTrue(kanbanSvg.contains("class=\"kanban-priority-stripe\" data-priority=\"veryhigh\""), "Expected Kanban priority stripe")
        assertTrue(kanbanSvg.contains("<a href=\"https://issues.example.com/MRD-1\">"), "Expected Kanban ticket link")

        // 18. Sankey (with gradient linkColor and custom nodeColors)
        val sankeySvg = MaradonaSvgExporter.exportToSvg(
            """
            ---
            config:
              sankey:
                linkColor: gradient
                nodeColors:
                  Solar: '#f59e0b'
                  Grid: '#3b82f6'
            ---
            sankey-beta
              Solar,Grid,120
              Grid,Homes,100
            """.trimIndent(),
        )
        assertTrue(sankeySvg.contains("class=\"sankey-link\""), "Expected Sankey cubic Bezier link")
        assertTrue(sankeySvg.contains("<linearGradient id=\"sankey-grad-0\""), "Expected Sankey gradient definition")
        assertTrue(sankeySvg.contains("stroke=\"url(#sankey-grad-0)\""), "Expected Sankey link to use gradient stroke")
        assertTrue(sankeySvg.contains("fill=\"#F59E0B\""), "Expected Sankey node to use configured nodeColors")
        assertTrue(sankeySvg.contains("class=\"sankey-node\""), "Expected Sankey node")

        // 19. Packet (including narrow single-bit field whose label is truncated to fit)
        val packetSvg = MaradonaSvgExporter.exportToSvg(
            """
            packet-beta
              0-15: "Source Port"
              16: "VeryLongSingleBitFlagLabel"
            """.trimIndent(),
        )
        assertTrue(packetSvg.contains("class=\"packet-block\""), "Expected packet block")
        assertTrue(packetSvg.contains("Source Port"), "Expected packet field label")
        assertFalse(packetSvg.contains(">VeryLongSingleBitFlagLabel</text>"), "Expected narrow 1-bit packet field label to be truncated")

        // 20. Radar (with custom cScale0 palette)
        val radarSvg = MaradonaSvgExporter.exportToSvg(
            """
            ---
            config:
              themeVariables:
                cScale0: '#8B5CF6'
            ---
            radar-beta
              axis a["Speed"], b["Reliability"], c["Memory"]
              curve m["Maradona"]{90, 95, 88}
            """.trimIndent(),
        )
        assertTrue(radarSvg.contains("class=\"radar-curve\""), "Expected radar curve polygon")
        assertTrue(radarSvg.contains("stroke=\"#8B5CF6\""), "Expected radar curve to use custom cScale0 palette")

        // 21. Block (with composite container, diamond, and multidirectional block arrow)
        val blockSvg = MaradonaSvgExporter.exportToSvg(
            """
            block-beta
              columns 3
              A{"Decision"} arr<["Go"]>(x, down) B("Action")
            """.trimIndent(),
        )
        assertTrue(blockSvg.contains("class=\"block-arrow\" data-dir=\"x,down\""), "Expected multidirectional block arrow")

        // 22. Treemap (including small leaf with long name that gets constrained to tile bounds)
        val treemapSvg = MaradonaSvgExporter.exportToSvg(
            """
            treemap-beta
            "Engineering"
                "Frontend": 99
                "VeryLongTinyMicroserviceLeafNameThatMustBeClippedOrSuppressed": 1
            """.trimIndent(),
        )
        assertTrue(treemapSvg.contains("class=\"treemap-section\""), "Expected treemap section")
        assertTrue(treemapSvg.contains("class=\"treemap-leaf\""), "Expected treemap leaf")
        assertFalse(
            treemapSvg.contains(">VeryLongTinyMicroserviceLeafNameThatMustBeClippedOrSuppressed</text>"),
            "Expected tiny treemap leaf label to be constrained to tile bounds",
        )
    }

    @Test
    fun exportsSequenceClassStateErRequirementC4ArchitectureAndZenUmlToSvg() {
        // 1. Sequence
        val seqSvg = MaradonaSvgExporter.exportToSvg(
            """
            sequenceDiagram
                autonumber
                box rgb(224, 242, 254) Client Tier
                    actor User
                    participant Web as Web App
                end
                participant API as Auth API
                links Web: {"Dashboard": "https://app.example.com"}
                rect rgb(241, 245, 249)
                    User->>+Web: Open Login<br/>Page
                    Web->>Web: Validate input
                end
                alt Valid credentials
                    Web->>+API: POST /login
                    API-->>-Web: 200 OK (JWT)
                else Invalid credentials
                    API--xWeb: 401 Unauthorized
                end
                Note over User,Web: Session established
            """.trimIndent(),
        )
        assertTrue(seqSvg.contains("Client Tier"), "Expected sequence box group label")
        assertTrue(seqSvg.contains("class=\"participant\" data-id=\"User\""), "Expected Actor User in SVG")
        assertTrue(seqSvg.contains("<a href=\"https://app.example.com\">"), "Expected participant link in SVG")
        assertTrue(seqSvg.contains(">alt</text>"), "Expected alt block pentagon tag")
        assertTrue(seqSvg.contains("[Valid credentials]"), "Expected alt condition")
        assertTrue(seqSvg.contains("[Invalid credentials]"), "Expected else divider condition")
        assertTrue(seqSvg.contains(">1. Open Login</text>") && seqSvg.contains(">Page</text>"), "Expected multiline message label")
        assertTrue(seqSvg.contains("data-marker=\"cross\""), "Expected -x cross marker")
        assertTrue(seqSvg.contains("Session established"), "Expected sequence note")

        // 2. Class
        val classSvg = MaradonaSvgExporter.exportToSvg(
            """
            classDiagram
                namespace Domain {
                    class Order {
                        <<AggregateRootApplicationService>>
                        +String id$
                        +process()* void
                    }
                    class LineItem {
                        +int qty
                    }
                }
                Order "1" *-- "many" LineItem : contains
                Order <|-- PriorityOrder
                Order ()-- OrderApi
                note for Order "Core root"
                link Order "https://example.com/order" "Order docs" _blank
            """.trimIndent(),
        )
        assertTrue(classSvg.contains("Domain"), "Expected namespace label")
        assertTrue(classSvg.contains("«AggregateRootApplicationService»"), "Expected class stereotype")
        assertTrue(classSvg.contains("text-decoration=\"underline\""), "Expected static member underline")
        assertTrue(classSvg.contains("font-style=\"italic\""), "Expected abstract member italics")
        assertTrue(classSvg.contains("data-marker=\"filled-diamond\""), "Expected composition marker")
        assertTrue(classSvg.contains("data-marker=\"hollow-triangle\""), "Expected inheritance marker")
        assertTrue(classSvg.contains("data-marker=\"lollipop\""), "Expected lollipop marker")
        assertTrue(classSvg.contains("<a href=\"https://example.com/order\" target=\"_blank\">"), "Expected class link")
        assertTrue(classSvg.contains("Core root"), "Expected class note")

        // 3. State
        val stateSvg = MaradonaSvgExporter.exportToSvg(
            """
            stateDiagram-v2
                [*] --> Active
                state Active {
                    [*] --> Running
                    --
                    [*] --> Monitoring
                }
                state Check <<choice>>
                Active --> Check : verify
                Check --> [*]
            """.trimIndent(),
        )
        assertTrue(stateSvg.contains("class=\"composite-state\" data-id=\"Active\""), "Expected composite state")
        assertTrue(stateSvg.contains("class=\"region-divider\""), "Expected concurrent region divider")
        assertTrue(stateSvg.contains("data-kind=\"start\""), "Expected start pseudostate")
        assertTrue(stateSvg.contains("data-kind=\"end\""), "Expected end pseudostate")
        assertTrue(stateSvg.contains("data-kind=\"choice\""), "Expected choice diamond pseudostate")

        // 4. ER
        val erSvg = MaradonaSvgExporter.exportToSvg(
            """
            erDiagram
                CUSTOMER ||--o{ ORDER : places
                CUSTOMER {
                    string id PK "UUID"
                    string email UK
                }
                ORDER {
                    string id PK
                }
            """.trimIndent(),
        )
        assertTrue(erSvg.contains("class=\"er-entity\" data-id=\"CUSTOMER\""), "Expected CUSTOMER entity")
        assertTrue(erSvg.contains("string id PK &quot;UUID&quot;"), "Expected CUSTOMER attribute row")
        assertTrue(erSvg.contains("data-marker=\"er-exactly-one\""), "Expected || cardinality marker")
        assertTrue(erSvg.contains("data-marker=\"er-zero-or-more\""), "Expected o{ cardinality marker")

        // 5. Requirement
        val reqSvg = MaradonaSvgExporter.exportToSvg(
            """
            requirementDiagram
                requirement auth_req {
                    id: REQ-1
                    text: Users must authenticate.
                    risk: high
                    verifymethod: test
                }
                element auth_svc {
                    type: service
                    docRef: https://docs.example.com/auth
                }
                auth_req - contains -> auth_svc
            """.trimIndent(),
        )
        assertTrue(reqSvg.contains("&lt;&lt;Requirement&gt;&gt;"), "Expected Requirement stereotype")
        assertFalse(reqSvg.contains("&lt;&lt;&lt;&lt;Requirement&gt;&gt;&gt;&gt;"), "Requirement stereotype must not double-wrap <<...>>")
        assertTrue(reqSvg.contains("data-marker=\"contains-circle-cross\""), "Expected contains source marker")
        assertTrue(reqSvg.contains("<a href=\"https://docs.example.com/auth\">"), "Expected Element docRef link")

        // 6. C4
        val c4Svg = MaradonaSvgExporter.exportToSvg(
            """
            C4Dynamic
                title Checkout Dynamic Flow
                Person(user, "Customer", "Web buyer who browses the catalog and submits orders")
                System_Boundary(b1, "Commerce") {
                    Container(web, "Storefront", "Kotlin/Compose", "UI", ${'$'}link="https://shop.example.com")
                    ContainerDb(db, "Orders DB", "PostgreSQL", "Stores orders")
                }
                System_Ext(pay, "Stripe", "Payments")
                RelIndex(1, user, web, "Submits order", "HTTPS")
                RelIndex(2, web, db, "Writes row", "JDBC")
                RelIndex(3, web, pay, "Charges card", "REST")
            """.trimIndent(),
        )
        assertTrue(c4Svg.contains("data-kind=\"person\""), "Expected C4 person element")
        assertFalse(c4Svg.contains(">Web buyer who browses the catalog and submits orders</text>"), "Expected long C4 description to wrap across multiple SVG text lines")
        assertTrue(c4Svg.contains("data-kind=\"container_db\""), "Expected C4 database element")
        assertTrue(c4Svg.contains("data-kind=\"system_ext\""), "Expected C4 external system element")
        assertTrue(c4Svg.contains("1: Submits order"), "Expected RelIndex prefix in C4 SVG")
        assertTrue(c4Svg.contains("<a href=\"https://shop.example.com\">"), "Expected C4 element link")

        // 7. Architecture
        val archSvg = MaradonaSvgExporter.exportToSvg(
            """
            architecture-beta
                group cloud(cloud)[Production Cloud]
                service gw(internet)[API Gateway] in cloud
                service db(database)[Orders DB] in cloud
                junction j1 in cloud
                gw:R --> L:j1
                j1:R --> L:db
            """.trimIndent(),
        )
        assertTrue(archSvg.contains("class=\"arch-group\""), "Expected architecture group")
        assertTrue(archSvg.contains("class=\"arch-service\" data-id=\"gw\""), "Expected architecture service")
        assertTrue(archSvg.contains("class=\"arch-junction\""), "Expected architecture junction")

        // 8. ZenUML
        val zenSvg = MaradonaSvgExporter.exportToSvg(
            """
            zenuml
                title Order Service
                @Actor Client
                @Database OrderDb
                // Validate incoming request payload and verify signature before querying the database
                Client->OrderDb.findOrder(id) {
                    if (found) {
                        return order
                    } else {
                        return null
                    }
                }
            """.trimIndent(),
        )
        assertTrue(zenSvg.contains("«Actor»"), "Expected ZenUML Actor stereotype")
        assertTrue(zenSvg.contains("«Database»"), "Expected ZenUML Database stereotype")
        assertFalse(
            zenSvg.contains(">Validate incoming request payload and verify signature before querying the database</text>"),
            "Expected long ZenUML comment to wrap across multiple SVG text lines",
        )
        assertTrue(zenSvg.contains("findOrder(id)"), "Expected ZenUML message")
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
