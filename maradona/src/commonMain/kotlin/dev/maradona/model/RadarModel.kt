package dev.maradona.model

/**
 * Graticule shape for a Mermaid `radar` / `radar-beta` diagram.
 */
enum class RadarGraticule {
    Circle,
    Polygon,
}

/**
 * Configuration and display options for a [RadarDiagram].
 */
data class RadarConfig(
    val width: Float = 600f,
    val height: Float = 600f,
    val marginTop: Float = 50f,
    val marginRight: Float = 50f,
    val marginBottom: Float = 50f,
    val marginLeft: Float = 50f,
    val showLegend: Boolean = true,
    val ticks: Int = 5,
    val max: Double? = null,
    val min: Double = 0.0,
    val graticule: RadarGraticule = RadarGraticule.Circle,
    val axisScaleFactor: Float = 1.0f,
    val axisLabelFactor: Float = 1.05f,
    val curveTension: Float = 0.17f,
    val curveOpacity: Float = 0.25f,
    val curveStrokeWidth: Float = 2.2f,
    val customPalette: Map<Int, String> = emptyMap(),
)

/**
 * A single radial axis in a [RadarDiagram].
 */
data class RadarAxis(
    val id: String,
    val label: String,
)

/**
 * A data curve plotted across the axes of a [RadarDiagram].
 *
 * [entries] is ordered to match [RadarDiagram.axes] 1-to-1.
 */
data class RadarCurve(
    val id: String,
    val label: String,
    val entries: List<Double>,
)

/**
 * Parsed representation of a Mermaid `radar` / `radar-beta` diagram.
 */
data class RadarDiagram(
    val axes: List<RadarAxis>,
    val curves: List<RadarCurve>,
    val config: RadarConfig = RadarConfig(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
