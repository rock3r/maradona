package dev.maradona.layout

import dev.maradona.model.RadarGraticule
import dev.maradona.parse.RadarParser
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RadarLayoutTest {

    @Test
    fun laysOutAxesCurvesAndLegendWithinCanvasBounds() {
        val diagram = RadarParser.parse(
            """
            radar-beta
              title "Restaurant Comparison"
              axis food["Food Quality"], service["Service"], price["Price"], ambiance["Ambiance"]
              curve a["Restaurant A"]{4, 3, 2, 4}
              curve b["Restaurant B"]{3, 4, 3, 3}
              graticule polygon
              max 5
              min 0
            """.trimIndent(),
        )
        val layout = RadarLayout.layout(diagram)

        assertNotNull(layout.titleRect)
        assertEquals(RadarGraticule.Polygon, layout.graticule)
        assertEquals(4, layout.axes.size)
        assertEquals(2, layout.curves.size)
        assertEquals(2, layout.legendItems.size)

        // Axis 0 points straight up from center (x == center.x, y < center.y).
        val axis0 = layout.axes[0]
        assertEquals(layout.center.x, axis0.lineEnd.x, 0.5f)
        assertTrue(axis0.lineEnd.y < layout.center.y)

        // Curve A has value 4 on axis 0 with max=5, min=0 -> radial distance == 0.8 * radius.
        val pt0 = layout.curves[0].points[0]
        val dist0 = hypot(pt0.x - layout.center.x, pt0.y - layout.center.y)
        assertEquals(layout.radius * 0.8f, dist0, 0.5f)

        // All axis labels and legend items fit inside [0, width] x [0, height], and legend does not overlap axis labels.
        for (pa in layout.axes) {
            assertTrue(pa.labelRect.x >= 0f && pa.labelRect.right <= layout.width + 0.5f)
            assertTrue(pa.labelRect.y >= 0f && pa.labelRect.bottom <= layout.height + 0.5f)
            for (li in layout.legendItems) {
                assertTrue(li.swatchRect.x > pa.labelRect.right)
            }
        }
    }
}
