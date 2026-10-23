package dev.maradona.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class QuadrantParserTest {

    @Test
    fun parsesAxesQuadrantsPointsClassesAndInlineStyles() {
        val src = """
            ---
            config:
              quadrantChart:
                chartWidth: 400
                chartHeight: 400
            ---
            quadrantChart
              title Reach and engagement of campaigns
              x-axis Low Reach --> High Reach
              y-axis Low Engagement --> "High Engagement ❤"
              quadrant-1 We should expand
              quadrant-2 Need to promote
              quadrant-3 Re-evaluate
              quadrant-4 May be improved
              Campaign A: [0.9, 0.0] radius: 12
              Campaign B:::class1: [0.8, 0.1] color: #ff3300, radius: 10
              Campaign C: [0.7, 0.2] radius: 25, color: #00ff33, stroke-color: #10f0f0
              Campaign D: [0.6, 0.3] radius: 15, stroke-color: #00ff0f, stroke-width: 5px ,color: #ff33f0
              Campaign E:::class2: [0.5, 0.4]
              classDef class1 color: #109060
              classDef class2 color: #908342, radius : 10, stroke-color: #310085, stroke-width: 10px
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.QuadrantModel>(model)
        val d = model.diagram
        assertEquals("Reach and engagement of campaigns", d.title)
        assertEquals(400f, d.chartWidth)
        assertEquals(400f, d.chartHeight)
        assertEquals("Low Reach", d.xAxisLeftText)
        assertEquals("High Reach", d.xAxisRightText)
        assertEquals("Low Engagement", d.yAxisBottomText)
        assertEquals("High Engagement ❤", d.yAxisTopText)
        assertEquals("We should expand", d.quadrant1Text)
        assertEquals("Need to promote", d.quadrant2Text)
        assertEquals("Re-evaluate", d.quadrant3Text)
        assertEquals("May be improved", d.quadrant4Text)
        assertEquals(5, d.points.size)

        // Inline color overrides classDef color on Campaign B.
        val b = assertNotNull(d.point("Campaign B"))
        assertEquals("#ff3300", b.style?.color)
        assertEquals(10f, b.style?.radius)

        // Forward-referenced classDef class2 applies to Campaign E.
        val e = assertNotNull(d.point("Campaign E"))
        assertEquals("#908342", e.style?.color)
        assertEquals(10f, e.style?.radius)
        assertEquals("#310085", e.style?.strokeColor)
        assertEquals(10f, e.style?.strokeWidth)
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            QuadrantParser.parse(
                """
                quadrantChart
                    Bad Point: [1.5, 0.5]
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            QuadrantParser.parse(
                """
                quadrantChart
                    Bad Point: [0.5, -0.1]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun supportsSemicolonStatementsQuotedPointLabelsAndLongAxisArrows() {
        val model = MaradonaParser.parse(
            """
            quadrantChart; x-axis Low ---> High
            "Service:::v2": [0.2, 0.3]; "Range: [old, new]": [0.7, 0.8]
            """.trimIndent(),
        )
        assertIs<MaradonaDiagramModel.QuadrantModel>(model)
        val d = model.diagram
        assertEquals("Low", d.xAxisLeftText)
        assertEquals("High", d.xAxisRightText)
        assertEquals(2, d.points.size)
        assertEquals("Service:::v2", d.points[0].label)
        assertEquals(null, d.points[0].className)
        assertEquals("Range: [old, new]", d.points[1].label)
    }
}
