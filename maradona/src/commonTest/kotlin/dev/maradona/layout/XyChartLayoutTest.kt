package dev.maradona.layout

import dev.maradona.parse.XyChartParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XyChartLayoutTest {

    @Test
    fun verticalLayoutPositionsBarsLinesTicksLegendAndTitlesWithoutInvertedGeometry() {
        val diagram = XyChartParser.parse(
            """
            xychart-beta
              title "Monthly Revenue & Target"
              x-axis "Month" [Jan, Feb, Mar, Apr]
              y-axis "Revenue (${'$'}k)" 0 --> 100
              bar "Actual" [40, 60, 80, 100]
              line "Target" [35, 55, 75 "Q3 Goal", 95]
            """.trimIndent(),
        )
        val layout = XyChartLayout.layout(diagram)

        assertNotNull(layout.titleRect)
        assertEquals(4, layout.xTicks.size)
        assertTrue(layout.yTicks.size >= 3)
        assertEquals(2, layout.axisTitles.size)
        assertEquals(4, layout.bars.size)
        assertEquals(1, layout.lines.size)
        assertEquals(2, layout.legendItems.size)

        // In Vertical mode, higher Y values map to smaller Y coordinates (closer to plotRect.y),
        // and X categories increase monotonically left to right.
        for (i in 0 until layout.bars.size - 1) {
            val cur = layout.bars[i]
            val next = layout.bars[i + 1]
            assertTrue(next.rect.centerX > cur.rect.centerX, "Bar X should increase left-to-right")
            assertTrue(next.rect.height > cur.rect.height, "Higher value bar should be taller")
            assertTrue(next.rect.y < cur.rect.y, "Higher value bar top should be higher up")
        }

        val linePts = layout.lines.single().points
        assertEquals(4, linePts.size)
        val labeledPt = linePts[2]
        assertEquals("Q3 Goal", labeledPt.label)
        assertNotNull(labeledPt.labelRect)

        // Left Y-axis title is rotated -90 deg.
        val leftTitle = layout.axisTitles.first { it.side == XyAxisSide.Left }
        assertEquals(-90f, leftTitle.rotationDeg)
    }

    @Test
    fun horizontalLayoutSwapsAxesAndGrowsBarsLeftToRight() {
        val diagram = XyChartParser.parse(
            """
            xychart-beta horizontal
              title "Pipeline Throughput"
              x-axis "Stage" [Parse, Layout, Render]
              y-axis "Ops/sec" 0 --> 300
              bar "Throughput" [100, 200, 300]
              line "Baseline" [80, 160, 240]
            """.trimIndent(),
        )
        val layout = XyChartLayout.layout(diagram)

        assertEquals(3, layout.xTicks.size)
        assertTrue(layout.xTicks.all { it.side == XyAxisSide.Left })
        assertTrue(layout.yTicks.all { it.side == XyAxisSide.Top })

        // In Horizontal mode, categories run top-to-bottom and bar widths increase with value.
        for (i in 0 until layout.bars.size - 1) {
            val cur = layout.bars[i]
            val next = layout.bars[i + 1]
            assertTrue(next.rect.centerY > cur.rect.centerY, "Horizontal bars should stack top-to-bottom")
            assertTrue(next.rect.width > cur.rect.width, "Higher value horizontal bar should be wider")
        }
    }

    @Test
    fun handlesDenseNumericBarsSmallFractionalDomainsNegativeBarLabelsAndBounds() {
        val dense = XyChartParser.parse(
            """
            xychart-beta
              title "A Very Long XY Chart Title That Must Always Fit Inside Reported Layout Bounds Without Overflowing"
              x-axis 1 --> 20
              y-axis -100 --> 100
              bar "S1" [-80, -40, 20, 60, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, -10, -20, -30, -50, -70, -90]
            """.trimIndent()
                .let { base ->
                    "---\nconfig:\n  xyChart:\n    showDataLabel: true\n    showDataLabelOutsideBar: true\n---\n$base"
                },
        )
        val layout = XyChartLayout.layout(dense)

        // Adjacent bars on the numeric X-axis must not overlap.
        for (i in 0 until layout.bars.size - 1) {
            val a = layout.bars[i].rect
            val b = layout.bars[i + 1].rect
            assertTrue(a.right <= b.x + 0.01f, "Bar $i (${a.right}) overlapped bar ${i + 1} (${b.x})")
        }

        // Negative bar (-80) extends downward from the 0 baseline, and its outside label sits below its bottom edge.
        val negBar = layout.bars[0]
        val posBar = layout.bars[2]
        assertTrue(negBar.rect.bottom > posBar.rect.bottom, "Negative bar should extend below zero baseline")
        val negLbl = assertNotNull(negBar.dataLabelRect)
        val posLbl = assertNotNull(posBar.dataLabelRect)
        assertTrue(negLbl.y >= negBar.rect.bottom, "Negative outside bar label should sit below negative bar tip")
        assertTrue(posLbl.bottom <= posBar.rect.y, "Positive outside bar label should sit above positive bar tip")

        // Title and legend items fit inside layout width and height.
        val tr = assertNotNull(layout.titleRect)
        assertTrue(tr.x >= 0f && tr.right <= layout.width + 0.01f)
        assertTrue(layout.legendItems.all { it.labelRect.bottom <= layout.height + 0.01f })

        // Small nonzero domains and fractional/large number formatting preserve distinct values.
        val smallDomain = XyChartParser.parse(
            """
            xychart
              x-axis 0 --> 0.0000000005
              y-axis 0 --> 0.004
              line [0.001, 0.002, 0.004]
            """.trimIndent(),
        )
        val smallLayout = XyChartLayout.layout(smallDomain)
        val pts = smallLayout.lines.single().points
        assertTrue(pts.last().position.x > pts.first().position.x + 50f, "Small nonzero X domain should span plot width")
        assertTrue(pts.last().position.y < pts.first().position.y - 50f, "Small nonzero Y domain should span plot height")
        assertTrue(smallLayout.yTicks.map { it.label }.distinct().size >= 3, "Fractional Y ticks < 0.01 must format distinctly")
        assertEquals("1.0E20", XyChartLayout.formatNumber(1e20))

        // Nonpositive bar domain (-100 --> 0) anchors bars at the zero-side boundary (0) and narrow offset domain preserves tick precision.
        val nonPositive = XyChartParser.parse(
            """
            xychart-beta
              x-axis [A, B]
              y-axis 1000.001 --> 1000.005
              line [1000.001, 1000.005]
            """.trimIndent(),
        )
        val npLayout = XyChartLayout.layout(nonPositive)
        assertTrue(npLayout.yTicks.map { it.label }.distinct().size >= 4, "Narrow domain around 1000 must preserve fractional tick digits")

        val negBars = XyChartLayout.layout(
            XyChartParser.parse(
                """
                xychart-beta
                  x-axis [A, B]
                  y-axis -100 --> 0
                  bar [-80, -40]
                """.trimIndent(),
            ),
        )
        assertTrue(negBars.bars[0].rect.height > negBars.bars[1].rect.height, "-80 bar should be taller than -40 bar when anchored at 0")
    }
}
