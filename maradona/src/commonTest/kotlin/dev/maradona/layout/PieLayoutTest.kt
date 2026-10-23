package dev.maradona.layout

import dev.maradona.parse.PieParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PieLayoutTest {

    @Test
    fun laysOutSlicesDonutHoleHighlightAndLegendWithoutOverlaps() {
        val diagram = PieParser.parse(
            """
            ---
            config:
              pie:
                textPosition: 0.5
                donutHole: 0.2
                highlightSlice: Potassium
            ---
            pie showData
                title Key elements in Product X
                "Calcium" : 42.96
                "Potassium" : 50.05
                "Magnesium" : 10.01
                "Iron" :  5
            """.trimIndent(),
        )

        val layout = PieLayout.layout(diagram)
        assertTrue(layout.width > 0f && layout.height > 0f)
        assertTrue(layout.innerRadius > 0f)
        assertEquals(4, layout.slices.size)
        assertEquals(4, layout.legendItems.size)

        val totalSweep = layout.slices.sumOf { it.sweepAngleDeg.toDouble() }.toFloat()
        assertTrue(abs(totalSweep - 360f) < 0.01f, "Total pie sweep must equal 360 degrees, got $totalSweep")

        val potassium = layout.slices.first { it.slice.label == "Potassium" }
        assertTrue(potassium.isHighlighted)
        assertTrue(
            potassium.explodeOffset.x != 0f || potassium.explodeOffset.y != 0f,
            "Highlighted slice must have non-zero explode offset",
        )

        assertEquals("Calcium [42.96]", layout.legendItems[0].displayText)
        assertEquals("Iron [5]", layout.legendItems[3].displayText)

        // All visible percentage labels must be non-overlapping and inside layout bounds.
        val pctRects = layout.slices.mapNotNull { it.pctLabelRect }
        assertEquals(4, pctRects.size)
        for (i in pctRects.indices) {
            val a = pctRects[i]
            assertTrue(a.x >= 0f && a.right <= layout.width && a.y >= 0f && a.bottom <= layout.height)
            for (j in i + 1 until pctRects.size) {
                val b = pctRects[j]
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertTrue(!overlap, "Pie percentage labels $a and $b must not overlap")
            }
        }

        // Legend items must stay within layout bounds and not overlap vertically.
        for (i in layout.legendItems.indices) {
            val item = layout.legendItems[i]
            assertTrue(item.swatchRect.x > layout.center.x + layout.outerRadius)
            assertTrue(item.labelRect.right <= layout.width + 0.5f)
            if (i + 1 < layout.legendItems.size) {
                val next = layout.legendItems[i + 1]
                assertTrue(item.labelRect.bottom < next.labelRect.y, "Legend items must not overlap vertically")
            }
        }

        val titleRect = assertNotNull(layout.titleRect)
        assertTrue(titleRect.x >= 0f && titleRect.right <= layout.width + 0.5f)
    }

    @Test
    fun handlesLargeAndTinyNumbersOverflowSafeFractionsAndOversizedLabels() {
        assertEquals("3000000000", PieLayout.formatSliceValue(3_000_000_000.0))
        assertEquals("30000000.5", PieLayout.formatSliceValue(30_000_000.5))
        assertEquals("0.001", PieLayout.formatSliceValue(0.001))
        assertEquals("0.0000014", PieLayout.formatSliceValue(0.0000014))

        val hugeDiagram = PieParser.parse(
            """
            pie
                "Left" : 1e308
                "Right" : 1e308
            """.trimIndent(),
        )
        val hugeLayout = PieLayout.layout(
            diagram = hugeDiagram,
            pctLabelSize = { _, _ -> DiagramSize(200f, 16f) },
        )
        assertEquals(2, hugeLayout.slices.size)
        assertTrue(abs(hugeLayout.slices[0].sweepAngleDeg - 180f) < 0.01f)
        assertTrue(abs(hugeLayout.slices[1].sweepAngleDeg - 180f) < 0.01f)
        for (s in hugeLayout.slices) {
            val pr = assertNotNull(s.pctLabelRect)
            assertTrue(pr.x >= 0f && pr.right <= hugeLayout.width + 0.5f, "Oversized pct label must stay in horizontal bounds: $pr")
            assertTrue(pr.y >= 0f && pr.bottom <= hugeLayout.height + 0.5f, "Oversized pct label must stay in vertical bounds: $pr")
        }
    }
}
