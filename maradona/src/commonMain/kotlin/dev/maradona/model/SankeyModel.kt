package dev.maradona.model

enum class SankeyNodeAlignment {
    Justify,
    Left,
    Right,
    Center,
}

enum class SankeyLabelStyle {
    Legacy,
    Outlined,
}

data class SankeyNode(
    val id: String,
    val color: String? = null,
)

data class SankeyLink(
    val source: String,
    val target: String,
    val value: Double,
)

data class SankeyDiagram(
    val nodes: List<SankeyNode>,
    val links: List<SankeyLink>,
    val chartWidth: Float = 640f,
    val chartHeight: Float = 400f,
    val nodeWidth: Float = 12f,
    val nodePadding: Float = 16f,
    val nodeAlignment: SankeyNodeAlignment = SankeyNodeAlignment.Justify,
    val linkColor: String = "gradient",
    val showValues: Boolean = true,
    val prefix: String = "",
    val suffix: String = "",
    val labelStyle: SankeyLabelStyle = SankeyLabelStyle.Legacy,
    val nodeColors: Map<String, String> = emptyMap(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
) {
    fun node(id: String): SankeyNode? = nodes.firstOrNull { it.id == id }
}
