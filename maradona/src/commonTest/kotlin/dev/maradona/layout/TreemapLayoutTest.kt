package dev.maradona.layout

import dev.maradona.parse.TreemapParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TreemapLayoutTest {

    @Test
    fun squarifiesSectionsAndLeavesWithinBounds() {
        val diagram = TreemapParser.parse(
            """
            treemap-beta
            title Engineering Budget
            "Engineering"
              "Platform"
                "Compute": 45
                "Storage": 28
              "Product"
                "Web App": 36
                "Mobile": 24
            "Operations"
              "Security": 22
              "Support": 15
            """.trimIndent(),
        )

        val layout = TreemapLayout.layout(diagram)
        assertNotNull(layout.titleRect)
        assertEquals(4, layout.sections.size)
        assertEquals(6, layout.leaves.size)

        // Larger weight leaves inside the same parent section have larger area.
        val compute = layout.leaves.first { it.node.name == "Compute" }
        val storage = layout.leaves.first { it.node.name == "Storage" }
        assertTrue(compute.rect.width * compute.rect.height > storage.rect.width * storage.rect.height)

        // Sibling leaf tiles inside a section do not overlap.
        assertTrue(!rectsOverlap(compute.rect, storage.rect))

        // Every leaf is contained inside its root section.
        val engSec = layout.sections.first { it.node.name == "Engineering" }
        assertTrue(compute.rect.x >= engSec.rect.x - 0.5f && compute.rect.right <= engSec.rect.right + 0.5f)
        assertTrue(compute.rect.y >= engSec.rect.y - 0.5f && compute.rect.bottom <= engSec.rect.bottom + 0.5f)

        // Engineering (sum = 133) has larger section area than Operations (sum = 37).
        val opsSec = layout.sections.first { it.node.name == "Operations" }
        assertEquals(133.0, engSec.totalValue, 1e-4)
        assertEquals(37.0, opsSec.totalValue, 1e-4)
        assertTrue(engSec.rect.width * engSec.rect.height > opsSec.rect.width * opsSec.rect.height)
    }

    @Test
    fun formatsValuesWithD3AndCurrencySpecs() {
        assertEquals("1,234,567", TreemapLayout.formatValue(1234567.0, ","))
        assertEquals("$1,250", TreemapLayout.formatValue(1250.0, "$0,0"))
        assertEquals("$1,250.50", TreemapLayout.formatValue(1250.5, "$,.2f"))
        assertEquals("42.5%", TreemapLayout.formatValue(0.425, ".1%"))
        assertEquals("$1.5M", TreemapLayout.formatValue(1_500_000.0, "$.2s"))
        assertEquals("120k", TreemapLayout.formatValue(123_456.7, ".2s"))
        assertEquals("123.5k", TreemapLayout.formatValue(123_456.7, ".4s"))
        assertEquals("1.2m", TreemapLayout.formatValue(0.0012, ".2s"))
        assertTrue(TreemapLayout.formatValue(0.00001, ",") != "0")
        assertTrue(TreemapLayout.formatValue(1e20, ",").isNotEmpty())
    }

    @Test
    fun handlesExtremeAndSkewedWeightsWithoutNaNOrHeaderOverlap() {
        val extreme = TreemapParser.parse(
            """
            treemap-beta
            "Section 1"
              "A": 1e308
              "B": 1e308
            "Section 2"
              "C": 1e308
            """.trimIndent(),
        )
        val extremeLayout = TreemapLayout.layout(extreme)
        for (sec in extremeLayout.sections) {
            assertTrue(sec.rect.x.isFinite() && sec.rect.y.isFinite())
            assertTrue(sec.rect.width.isFinite() && sec.rect.height.isFinite())
        }
        for (leaf in extremeLayout.leaves) {
            assertTrue(leaf.rect.x.isFinite() && leaf.rect.y.isFinite())
            assertTrue(leaf.rect.width.isFinite() && leaf.rect.height.isFinite())
        }

        // Verify small sections never place child leaves overlapping their section header.
        val nested = TreemapParser.parse(
            """
            ---
            config:
              treemap:
                width: 240
                height: 120
            ---
            treemap-beta
            "Big": 500
            "SmallSection"
              "TinyA": 10
              "TinyB": 10
            """.trimIndent(),
        )
        for (d in listOf(0.1f, 1f, 5f)) {
            val l = TreemapLayout.layout(nested, options = LayoutOptions(density = d))
            val sec = l.sections.single()
            for (leaf in l.leaves.filter { it.node.name.startsWith("Tiny") }) {
                assertTrue(leaf.rect.y >= sec.headerRect.bottom - 0.01f)
            }
        }

        // Preserve relative weights for small-valued sections below 0.001
        val tinySections = TreemapParser.parse(
            """
            ---
            config:
              treemap:
                padding: 0
            ---
            treemap-beta
            "SecSmall"
              "A": 0.0001
            "SecLarge"
              "B": 0.0009
            """.trimIndent(),
        )
        val tinyLayout = TreemapLayout.layout(tinySections)
        val secSmall = tinyLayout.sections.first { it.node.name == "SecSmall" }
        val secLarge = tinyLayout.sections.first { it.node.name == "SecLarge" }
        val areaSmall = secSmall.rect.width * secSmall.rect.height
        val areaLarge = secLarge.rect.width * secLarge.rect.height
        assertEquals(9.0, (areaLarge / areaSmall).toDouble(), 0.05)

        // Preserve relative weights for large-valued sections above 1e140
        val hugeSections = TreemapParser.parse(
            """
            ---
            config:
              treemap:
                padding: 0
            ---
            treemap-beta
            "Sec141"
              "A": 1e141
            "Sec142"
              "B": 1e142
            """.trimIndent(),
        )
        val hugeLayout = TreemapLayout.layout(hugeSections)
        val sec141 = hugeLayout.sections.first { it.node.name == "Sec141" }
        val sec142 = hugeLayout.sections.first { it.node.name == "Sec142" }
        val area141 = sec141.rect.width * sec141.rect.height
        val area142 = sec142.rect.width * sec142.rect.height
        assertEquals(10.0, (area142 / area141).toDouble(), 0.05)
    }

    @Test
    fun scalesWithDensity() {
        val diagram = TreemapParser.parse(
            """
            treemap
            "Section"
              "A": 50
              "B": 30
            """.trimIndent(),
        )
        val l1 = TreemapLayout.layout(diagram, options = LayoutOptions(density = 1f))
        val l2 = TreemapLayout.layout(diagram, options = LayoutOptions(density = 2f))
        assertTrue(abs(l2.width - l1.width * 2f) < 1f)
        assertTrue(abs(l2.height - l1.height * 2f) < 1f)
    }

    private fun rectsOverlap(a: DiagramRect, b: DiagramRect): Boolean {
        val eps = 0.5f
        return a.x + eps < b.right && a.right - eps > b.x &&
            a.y + eps < b.bottom && a.bottom - eps > b.y
    }
}
