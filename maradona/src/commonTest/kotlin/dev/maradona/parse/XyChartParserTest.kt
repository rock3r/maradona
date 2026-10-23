package dev.maradona.parse

import dev.maradona.model.XyAxisSpec
import dev.maradona.model.XyChartOrientation
import dev.maradona.model.XyPlotType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XyChartParserTest {

    @Test
    fun parsesCategoricalBarAndLineWithPointLabelsAndFrontmatterConfig() {
        val src = """
            ---
            config:
              xyChart:
                width: 800
                height: 480
                showDataLabel: true
                showDataLabelOutsideBar: true
              themeVariables:
                xyChart:
                  plotColorPalette: "#d14d41, rgb(32, 94, 166), #24837b"
            ---
            xychart-beta
              title "Sales Revenue"
              accTitle: Quarterly Sales Chart
              accDescr {
                Compares monthly bar revenue against target line
              }
              x-axis "Month" [jan, "feb, 26", mar, apr]
              y-axis "Revenue (in $)" 4000 --> 11000
              bar "Actual" [5000, 6000, 7500, 8200, 9999]
              line "Target" [5500, 6200 "Q1 Peak", 7200, 8500]
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.XyChartModel>(model)
        val d = model.diagram
        assertEquals("Sales Revenue", d.title)
        assertEquals("Quarterly Sales Chart", d.accTitle)
        assertEquals("Compares monthly bar revenue against target line", d.accDescr)
        assertEquals(XyChartOrientation.Vertical, d.orientation)
        assertEquals(800f, d.chartWidth)
        assertEquals(480f, d.chartHeight)
        assertTrue(d.showDataLabel)
        assertTrue(d.showDataLabelOutsideBar)
        assertEquals(listOf("#d14d41", "rgb(32, 94, 166)", "#24837b"), d.plotColorPalette)

        val xa = assertIs<XyAxisSpec.BandAxis>(d.xAxis)
        assertEquals("Month", xa.title)
        assertEquals(listOf("jan", "feb, 26", "mar", "apr"), xa.categories)

        assertEquals("Revenue (in $)", d.yAxis.title)
        assertEquals(4000.0, d.yAxis.min)
        assertEquals(11000.0, d.yAxis.max)

        assertEquals(2, d.plots.size)
        val bar = assertNotNull(d.plot("Actual"))
        assertEquals(XyPlotType.Bar, bar.type)
        assertEquals("#d14d41", bar.color)
        // Extra 5th data point is truncated to the 4 categories on the band X-axis.
        assertEquals(4, bar.points.size)
        assertEquals(5000.0, bar.points[0].value)
        assertEquals(8200.0, bar.points[3].value)

        val line = assertNotNull(d.plot("Target"))
        assertEquals(XyPlotType.Line, line.type)
        assertEquals("rgb(32, 94, 166)", line.color)
        assertEquals(4, line.points.size)
        assertEquals("Q1 Peak", line.points[1].label)
    }

    @Test
    fun parsesHorizontalOrientationLinearXAxisAutoYRangeAndMultilineArrays() {
        val src = """
            %%{init: {"xyChart": {"chartOrientation": "horizontal"}}}%%
            xychart
              x-axis "Time (s)" 0 --> 60
              y-axis "Speed"
              line [
                10,
                25 "boost",
                40
              ]
        """.trimIndent()

        val d = XyChartParser.parse(src)
        assertEquals(XyChartOrientation.Horizontal, d.orientation)
        val xa = assertIs<XyAxisSpec.LinearAxis>(d.xAxis)
        assertEquals("Time (s)", xa.title)
        assertEquals(0.0, xa.min)
        assertEquals(60.0, xa.max)

        // Y-axis range auto-inferred from [10, 25, 40] while preserving title "Speed".
        assertEquals("Speed", d.yAxis.title)
        assertEquals(10.0, d.yAxis.min)
        assertEquals(40.0, d.yAxis.max)

        assertEquals(1, d.plots.size)
        assertEquals(3, d.plots[0].points.size)
        assertEquals("boost", d.plots[0].points[1].label)
    }

    @Test
    fun rejectsMalformedArraysAndNonFiniteNumbers() {
        assertFailsWith<IllegalArgumentException> {
            XyChartParser.parse(
                """
                xychart-beta
                  bar []
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            XyChartParser.parse(
                """
                xychart-beta
                  y-axis 0 --> NaN
                  bar [10, 20]
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            XyChartParser.parse(
                """
                xychart-beta
                  bar [10, nope]
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            XyChartParser.parse(
                """
                xychart-beta
                  y-axis [a, b]
                  bar [10, 20]
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            XyChartParser.parse(
                """
                xychart
                  accDescr {
                    Unclosed description
                  bar [10, 20]
                """.trimIndent(),
            )
        }
    }
}
