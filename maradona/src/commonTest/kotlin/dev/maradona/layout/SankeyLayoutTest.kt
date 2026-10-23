package dev.maradona.layout

import dev.maradona.parse.SankeyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SankeyLayoutTest {

    @Test
    fun positionsLayeredNodesAndProportionalLinksWithinBounds() {
        val diagram = SankeyParser.parse(
            """
            sankey-beta
              title Renewable Grid Flow
              Solar,Electricity grid,120
              Wind,Electricity grid,280
              Electricity grid,Industry,250
              Electricity grid,"Heating & cooling, homes",150
            """.trimIndent(),
        )
        val layout = SankeyLayout.layout(diagram)

        assertNotNull(layout.titleRect)
        assertEquals(5, layout.nodes.size)
        assertEquals(4, layout.links.size)

        val solar = assertNotNull(layout.node("Solar"))
        val wind = assertNotNull(layout.node("Wind"))
        val grid = assertNotNull(layout.node("Electricity grid"))
        val ind = assertNotNull(layout.node("Industry"))

        // Layers progress left to right: sources (0) -> grid (1) -> sinks (2).
        assertEquals(0, solar.layer)
        assertEquals(0, wind.layer)
        assertEquals(1, grid.layer)
        assertEquals(2, ind.layer)
        assertTrue(solar.rect.right < grid.rect.x)
        assertTrue(grid.rect.right < ind.rect.x)

        // Wind (280) has a taller node bar than Solar (120), and Grid (400) is taller than both.
        assertTrue(wind.rect.height > solar.rect.height)
        assertTrue(grid.rect.height > wind.rect.height)

        // All node rects and label rects fit inside [0, width] x [0, height].
        for (pn in layout.nodes) {
            assertTrue(pn.rect.x >= 0f && pn.rect.right <= layout.width + 0.01f)
            assertTrue(pn.rect.y >= 0f && pn.rect.bottom <= layout.height + 0.01f)
            assertTrue(pn.labelRect.x >= 0f && pn.labelRect.right <= layout.width + 0.01f)
            assertTrue(pn.labelRect.y >= 0f && pn.labelRect.bottom <= layout.height + 0.01f)
        }

        // Default measurements scale linearly with density = 2f.
        val layout2x = SankeyLayout.layout(diagram, options = LayoutOptions(density = 2f))
        assertEquals(layout.width * 2f, layout2x.width, 0.01f)
        assertEquals(layout.height * 2f, layout2x.height, 0.01f)

        // Small parallel links fit inside their source and target node bars, and huge finite flows (1e40) scale properly.
        val extreme = SankeyLayout.layout(
            SankeyParser.parse(
                """
                sankey
                  A,B,1
                  A,B,1
                  A,B,1
                  C,D,1e40
                """.trimIndent(),
            ),
        )
        for (l in extreme.links) {
            val srcRect = extreme.nodes[l.sourceNodeIndex].rect
            val dstRect = extreme.nodes[l.targetNodeIndex].rect
            val halfT = l.thickness / 2f
            assertTrue(l.start.y - halfT >= srcRect.y - 0.01f && l.start.y + halfT <= srcRect.bottom + 0.01f)
            assertTrue(l.end.y - halfT >= dstRect.y - 0.01f && l.end.y + halfT <= dstRect.bottom + 0.01f)
        }
        val cNode = assertNotNull(extreme.node("C"))
        assertTrue(cNode.rect.height > 100f, "Large finite flow 1e40 should fill available height")
        assertTrue(cNode.labelText.contains("1.0E40"))
    }
}
