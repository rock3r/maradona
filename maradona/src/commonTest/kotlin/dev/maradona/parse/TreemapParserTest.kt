package dev.maradona.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TreemapParserTest {

    @Test
    fun parsesNestedSectionsAndLeaves() {
        val diagram = TreemapParser.parse(
            """
            treemap-beta
            title Engineering Budget
            accTitle: Budget Treemap
            accDescr: Breakdown of engineering and operations budget
            "Engineering"
                "Platform"
                    "Compute": 45
                    "Storage": 28
                "Product"
                    "Web App": 36
                    "Mobile": 24.5
            "Operations"
                "Security": 22
                "Support": 15
            """.trimIndent(),
        )

        assertEquals("Engineering Budget", diagram.title)
        assertEquals("Budget Treemap", diagram.accTitle)
        assertEquals("Breakdown of engineering and operations budget", diagram.accDescr)
        assertEquals(2, diagram.roots.size)

        val eng = diagram.roots[0]
        assertEquals("Engineering", eng.name)
        assertEquals(2, eng.children.size)

        val platform = eng.children[0]
        assertEquals("Platform", platform.name)
        assertEquals(2, platform.children.size)
        assertEquals("Compute", platform.children[0].name)
        assertEquals(45.0, platform.children[0].value)
        assertEquals("Storage", platform.children[1].name)
        assertEquals(28.0, platform.children[1].value)

        val product = eng.children[1]
        assertEquals("Product", product.name)
        assertEquals(2, product.children.size)
        assertEquals(24.5, product.children[1].value)

        val ops = diagram.roots[1]
        assertEquals("Operations", ops.name)
        assertEquals(2, ops.children.size)
    }

    @Test
    fun parsesClassDefAndInlineDecorators() {
        val diagram = TreemapParser.parse(
            """
            treemap
            classDef highlight fill:#DBEAFE,stroke:#1D4ED8,color:#1E3A8A,stroke-width:2px,font-weight:bold
            "Department":::highlight
              "Team A": 60:::highlight
              "Team B", 40
            """.trimIndent(),
        )

        val root = diagram.roots.single()
        assertEquals("Department", root.name)
        assertEquals("highlight", root.classSelector)
        assertNotNull(root.style)
        assertEquals("#DBEAFE", root.style.fill)
        assertEquals("#1D4ED8", root.style.stroke)
        assertEquals("#1E3A8A", root.style.textColor)
        assertEquals(2f, root.style.strokeWidth)
        assertEquals(true, root.style.fontWeightBold)

        val teamA = root.children[0]
        assertEquals("Team A", teamA.name)
        assertEquals(60.0, teamA.value)
        assertEquals("#DBEAFE", teamA.style?.fill)

        val teamB = root.children[1]
        assertEquals("Team B", teamB.name)
        assertEquals(40.0, teamB.value)
    }

    @Test
    fun parsesInitDirectiveAndFrontmatterConfig() {
        val diagram = TreemapParser.parse(
            """
            ---
            title: Revenue Breakdown
            config:
              treemap:
                width: 800
                height: 500
                padding: 12
                diagramPadding: 16
                showValues: false
                valueFormat: "$0,0"
            ---
            treemap-beta
            "North America": 1200
            "Europe": 850
            """.trimIndent(),
        )

        assertEquals("Revenue Breakdown", diagram.title)
        assertEquals(800f, diagram.config.width)
        assertEquals(500f, diagram.config.height)
        assertEquals(12f, diagram.config.padding)
        assertEquals(16f, diagram.config.diagramPadding)
        assertEquals(false, diagram.config.showValues)
        assertEquals("$0,0", diagram.config.valueFormat)
        assertEquals(2, diagram.roots.size)

        val initDiagram = TreemapParser.parse(
            """
            %%{init: {"treemap": {"padding": 14, "showValues": false, "valueFormat": ".2s"}}}%%
            treemap-beta
            "A": 1
            """.trimIndent(),
        )
        assertEquals(14f, initDiagram.config.padding)
        assertEquals(false, initDiagram.config.showValues)
        assertEquals(".2s", initDiagram.config.valueFormat)
    }

    @Test
    fun topLevelMaradonaParserDispatchesTreemap() {
        val model = MaradonaParser.parse(
            """
            treemap-beta
            "Root"
              "Child": 10
            """.trimIndent(),
        )
        assertTrue(model is MaradonaDiagramModel.TreemapModel)
        assertEquals(1, model.diagram.roots.size)
    }

    @Test
    fun rejectsNonTreemapHeader() {
        assertFailsWith<IllegalArgumentException> {
            TreemapParser.parse("flowchart TD\n  A --> B")
        }
    }
}
