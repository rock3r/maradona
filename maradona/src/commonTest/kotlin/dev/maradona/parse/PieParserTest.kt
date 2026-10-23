package dev.maradona.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PieParserTest {

    @Test
    fun parsesBasicPieAndDonutFrontmatterConfigAndColonsInQuotedLabels() {
        val basic = MaradonaParser.parse(
            """
            pie title Pets adopted by volunteers
                "Dogs" : 386
                "Cats" : 85
                "Rats: Small" : 15
            """.trimIndent(),
        )
        assertIs<MaradonaDiagramModel.PieModel>(basic)
        val d1 = basic.diagram
        assertEquals("Pets adopted by volunteers", d1.title)
        assertFalse(d1.showData)
        assertEquals(3, d1.slices.size)
        assertEquals("Dogs", d1.slices[0].label)
        assertEquals(386.0, d1.slices[0].value)
        assertEquals("Rats: Small", d1.slices[2].label)
        assertEquals(15.0, d1.slices[2].value)

        val configured = PieParser.parse(
            """
            ---
            config:
              pie:
                textPosition: 0.5
                donutHole: 0.2
                highlightSlice: Potassium
              themeVariables:
                pieOuterStrokeWidth: "5px"
            ---
            pie showData
                title Key elements in Product X
                "Calcium" : 42.96
                "Potassium" : 50.05
                "Magnesium" : 10.01
                "Iron" :  5
            """.trimIndent(),
        )
        assertEquals("Key elements in Product X", configured.title)
        assertTrue(configured.showData)
        assertEquals(0.5f, configured.textPosition)
        assertEquals(0.2f, configured.donutHole)
        assertEquals("Potassium", configured.highlightSlice)
        assertEquals(5f, configured.outerStrokeWidth)
        assertEquals(4, configured.slices.size)
        assertEquals(42.96, configured.slices[0].value)
    }

    @Test
    fun acceptsZeroValuedSlicesDeduplicatesLabelsAndRejectsNegativeValues() {
        val zeroAndDup = PieParser.parse(
            """
            pie
                "Completed" : 10
                "Completed" : 90
                "Pending" : 0
            """.trimIndent(),
        )
        assertEquals(2, zeroAndDup.slices.size)
        assertEquals("Completed", zeroAndDup.slices[0].label)
        assertEquals(10.0, zeroAndDup.slices[0].value, "First value must win for duplicate slice labels")
        assertEquals("Pending", zeroAndDup.slices[1].label)
        assertEquals(0.0, zeroAndDup.slices[1].value)

        assertFailsWith<IllegalArgumentException> {
            PieParser.parse(
                """
                pie
                    "Negative" : -10
                """.trimIndent(),
            )
        }
    }
}
