package dev.maradona.model

data class PieSlice(
    val id: String,
    val label: String,
    val value: Double,
)

data class PieDiagram(
    val slices: List<PieSlice>,
    val title: String? = null,
    val showData: Boolean = false,
    val textPosition: Float = 0.75f,
    val donutHole: Float = 0f,
    val highlightSlice: String? = null,
    val outerStrokeWidth: Float = 2f,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
