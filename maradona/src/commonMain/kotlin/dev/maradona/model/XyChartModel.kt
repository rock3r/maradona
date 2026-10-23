package dev.maradona.model

enum class XyChartOrientation {
    Vertical,
    Horizontal,
}

sealed interface XyAxisSpec {
    val title: String?

    data class BandAxis(
        override val title: String? = null,
        val categories: List<String>,
    ) : XyAxisSpec

    data class LinearAxis(
        override val title: String? = null,
        val min: Double,
        val max: Double,
    ) : XyAxisSpec
}

enum class XyPlotType {
    Bar,
    Line,
}

data class XyDataPoint(
    val value: Double,
    val label: String? = null,
)

data class XyPlotSeries(
    val id: String,
    val type: XyPlotType,
    val title: String? = null,
    val points: List<XyDataPoint>,
    val color: String? = null,
)

data class XyChartDiagram(
    val plots: List<XyPlotSeries>,
    val xAxis: XyAxisSpec,
    val yAxis: XyAxisSpec.LinearAxis,
    val orientation: XyChartOrientation = XyChartOrientation.Vertical,
    val chartWidth: Float = 700f,
    val chartHeight: Float = 500f,
    val showTitle: Boolean = true,
    val showLegend: Boolean = true,
    val showDataLabel: Boolean = false,
    val showDataLabelOutsideBar: Boolean = false,
    val plotColorPalette: List<String> = emptyList(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
) {
    fun plot(title: String): XyPlotSeries? = plots.firstOrNull { it.title == title }
}
