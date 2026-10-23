package dev.maradona.parse

import dev.maradona.model.RadarGraticule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RadarParserTest {

    @Test
    fun parsesPositionalAxesAndCurvesWithFrontmatterAndPolygonGraticule() {
        val model = MaradonaParser.parse(
            """
            ---
            title: "Grades"
            ---
            radar-beta
              axis m["Math"], s["Science"], e["English"]
              axis h["History"], g["Geography"], a["Art"]
              curve a["Alice"]{85, 90, 80, 70, 75, 90}
              curve b["Bob"]{70, 75, 85, 80, 90, 85}
              graticule polygon
              max 100
              min 0
            """.trimIndent(),
        )
        val radarModel = assertIs<MaradonaDiagramModel.RadarModel>(model)
        val diagram = radarModel.diagram

        assertEquals("Grades", diagram.title)
        assertEquals(6, diagram.axes.size)
        assertEquals("m", diagram.axes[0].id)
        assertEquals("Math", diagram.axes[0].label)
        assertEquals("a", diagram.axes[5].id)
        assertEquals("Art", diagram.axes[5].label)

        assertEquals(2, diagram.curves.size)
        assertEquals("a", diagram.curves[0].id)
        assertEquals("Alice", diagram.curves[0].label)
        assertEquals(listOf(85.0, 90.0, 80.0, 70.0, 75.0, 90.0), diagram.curves[0].entries)
        assertEquals(listOf(70.0, 75.0, 85.0, 80.0, 90.0, 85.0), diagram.curves[1].entries)

        assertEquals(RadarGraticule.Polygon, diagram.config.graticule)
        assertEquals(100.0, diagram.config.max)
        assertEquals(0.0, diagram.config.min)
    }

    @Test
    fun parsesKeyedCurveEntriesMultilineBracesAndThemeVariables() {
        val diagram = RadarParser.parse(
            """
            ---
            config: { radar: { axisScaleFactor: 0.9,
                               curveTension: 0.1 },
                      themeVariables: { cScale1: "#00ff00",
                                        cScale2: "#0000ff",
                                        radar: { curveOpacity: 0 } } }
            ---
            %% disabled: %%{init: {'radar': {'showLegend': false}}}%%
            radar-beta:
              title Restaurant's 'Top' Comparison
              accTitle: Radar Accessibility
              accDescr {
                Compares restaurants across
                four axes.
              }
              axis food['Food %%{init: {}}%% Quality'], service["Service"], price, ambiance["Ambiance #1"]
              curve r1["Bistro"] {
                ambiance: 4,
                food: 5,
                price 2,
                service: 3
              }, r2["Diner"]{3, 4, 5, 2}
              ticks 64, showLegend false
            """.trimIndent(),
        )

        assertEquals("Restaurant's 'Top' Comparison", diagram.title)
        assertEquals("Radar Accessibility", diagram.accTitle)
        assertEquals("Compares restaurants across\nfour axes.", diagram.accDescr)
        assertEquals(4, diagram.axes.size)
        assertEquals("Food %%{init: {}}%% Quality", diagram.axes[0].label)
        assertEquals("price", diagram.axes[2].label)
        assertEquals("Ambiance #1", diagram.axes[3].label)

        assertEquals(2, diagram.curves.size)
        // Keyed entries are reordered to match axis declaration order (food, service, price, ambiance).
        assertEquals(listOf(5.0, 3.0, 2.0, 4.0), diagram.curves[0].entries)
        assertEquals(listOf(3.0, 4.0, 5.0, 2.0), diagram.curves[1].entries)

        assertEquals(0.9f, diagram.config.axisScaleFactor)
        assertEquals(0.1f, diagram.config.curveTension)
        assertEquals(0f, diagram.config.curveOpacity)
        assertEquals(mapOf(1 to "#00ff00", 2 to "#0000ff"), diagram.config.customPalette)
        // ticks > 32 is capped at MAX_TICKS (32).
        assertEquals(32, diagram.config.ticks)
        assertEquals(false, diagram.config.showLegend)
    }

    @Test
    fun rejectsMismatchedEntriesUnknownAxesAndInvalidOptions() {
        // Wrong number of positional entries
        assertFailsWith<IllegalArgumentException> {
            RadarParser.parse(
                """
                radar-beta
                  axis A, B, C
                  curve c1{1, 2}
                """.trimIndent(),
            )
        }
        // Missing axis in keyed entries
        assertFailsWith<IllegalArgumentException> {
            RadarParser.parse(
                """
                radar-beta
                  axis A, B, C
                  curve c1{A: 1, B: 2}
                """.trimIndent(),
            )
        }
        // Unknown axis in keyed entries
        assertFailsWith<IllegalArgumentException> {
            RadarParser.parse(
                """
                radar-beta
                  axis A, B
                  curve c1{A: 1, B: 2, Z: 3}
                """.trimIndent(),
            )
        }
        // max < min
        assertFailsWith<IllegalArgumentException> {
            RadarParser.parse(
                """
                radar-beta
                  axis A, B, C
                  curve c1{1, 2, 3}
                  min 50
                  max 10
                """.trimIndent(),
            )
        }
        // Invalid graticule
        assertFailsWith<IllegalArgumentException> {
            RadarParser.parse(
                """
                radar-beta
                  axis A, B, C
                  graticule triangle
                """.trimIndent(),
            )
        }
    }
}
