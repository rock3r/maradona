package dev.maradona.model

data class QuadrantPointStyle(
    val color: String? = null,
    val radius: Float? = null,
    val strokeColor: String? = null,
    val strokeWidth: Float? = null,
) {
    fun merge(override: QuadrantPointStyle?): QuadrantPointStyle {
        if (override == null) return this
        return QuadrantPointStyle(
            color = override.color ?: color,
            radius = override.radius ?: radius,
            strokeColor = override.strokeColor ?: strokeColor,
            strokeWidth = override.strokeWidth ?: strokeWidth,
        )
    }
}

data class QuadrantPoint(
    val id: String,
    val label: String,
    val x: Double,
    val y: Double,
    val className: String? = null,
    val style: QuadrantPointStyle? = null,
)

data class QuadrantDiagram(
    val points: List<QuadrantPoint>,
    val quadrant1Text: String? = null,
    val quadrant2Text: String? = null,
    val quadrant3Text: String? = null,
    val quadrant4Text: String? = null,
    val xAxisLeftText: String? = null,
    val xAxisRightText: String? = null,
    val yAxisBottomText: String? = null,
    val yAxisTopText: String? = null,
    val chartWidth: Float = 500f,
    val chartHeight: Float = 500f,
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
) {
    fun point(label: String): QuadrantPoint? = points.firstOrNull { it.label == label }
}
