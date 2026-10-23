package dev.maradona.parse

import dev.maradona.model.SankeyLabelStyle
import dev.maradona.model.SankeyNodeAlignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SankeyParserTest {

    @Test
    fun parsesCsvRecordsWithQuotedCommasEscapedQuotesAndFrontmatterConfig() {
        val src = """
            ---
            config:
              sankey:
                width: 720
                height: 440
                showValues: false
                nodeWidth: 15
                nodePadding: 20
                nodeAlignment: left
                labelStyle: outlined
                linkColor: source
                prefix: "${'$'}"
                suffix: " GWh"
                nodeColors:
                  Electricity grid: "#205ea6"
                  Losses: ""
            ---
            sankey-beta
              title Energy Flow, 2026
              %% source,target,value
              Electricity grid,Over generation / exports,104.453
              Pumped heat,"Heating and cooling, ""homes""${"\""},193.026
              Pumped heat,"Heating and cooling, commercial",70.672
              Electricity grid,Losses,56.691
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.SankeyModel>(model)
        val d = model.diagram
        assertEquals("Energy Flow, 2026", d.title)
        assertEquals(720f, d.chartWidth)
        assertEquals(440f, d.chartHeight)
        assertFalse(d.showValues)
        assertEquals(15f, d.nodeWidth)
        assertEquals(20f, d.nodePadding)
        assertEquals(SankeyNodeAlignment.Left, d.nodeAlignment)
        assertEquals(SankeyLabelStyle.Outlined, d.labelStyle)
        assertEquals("source", d.linkColor)
        assertEquals("$", d.prefix)
        assertEquals(" GWh", d.suffix)
        assertEquals(mapOf("Electricity grid" to "#205ea6"), d.nodeColors)

        assertEquals(6, d.nodes.size)
        assertEquals(4, d.links.size)
        assertEquals("Heating and cooling, \"homes\"", d.links[1].target)
        assertEquals(193.026, d.links[1].value)
        assertEquals("Heating and cooling, commercial", d.links[2].target)

        // Multiline quoted CSV fields preserve newlines and remain distinct from space-separated names.
        val multilineAndInit = SankeyParser.parse(
            """
            %%{init: {"sankey": {"nodeColors": {"North America": "#ff0000"}}}}%%
            sankey
              "North
            America",Sink,1
              North America,Sink,2
            """.trimIndent(),
        )
        assertEquals(3, multilineAndInit.nodes.size)
        assertEquals("North\nAmerica", multilineAndInit.nodes[0].id)
        assertEquals("North America", multilineAndInit.nodes[2].id)
        assertEquals("#ff0000", multilineAndInit.nodeColors["North America"])

        // A 3-field CSV record starting with `accDescr {` is parsed as a flow record, not swallowed as an accDescr block.
        val csvStartingWithAcc = SankeyParser.parse(
            """
            sankey
              accDescr {sector},Sink,10
            """.trimIndent(),
        )
        assertEquals(2, csvStartingWithAcc.nodes.size)
        assertEquals("accDescr {sector}", csvStartingWithAcc.links.single().source)
    }

    @Test
    fun rejectsMalformedCsvRecordsCyclesAndNonPositiveOrNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> {
            SankeyParser.parse(
                """
                sankey
                  A,B
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SankeyParser.parse(
                """
                sankey
                  A,B,-10
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SankeyParser.parse(
                """
                sankey
                  A,B,NaN
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SankeyParser.parse(
                """
                sankey
                  "Unclosed,B,10
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SankeyParser.parse(
                """
                sankey
                  A,A,10
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SankeyParser.parse(
                """
                sankey
                  A,B,10
                  B,C,10
                  C,A,10
                """.trimIndent(),
            )
        }
    }
}
