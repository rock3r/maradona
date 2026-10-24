package dev.maradona.model

/**
 * Custom CSS-like styling for a [TreemapNode] via `classDef` and `:::className`.
 */
data class TreemapNodeStyle(
    val fill: String? = null,
    val stroke: String? = null,
    val strokeWidth: Float? = null,
    val textColor: String? = null,
    val fontWeightBold: Boolean? = null,
) {
    fun merge(other: TreemapNodeStyle?): TreemapNodeStyle {
        if (other == null) return this
        return TreemapNodeStyle(
            fill = other.fill ?: fill,
            stroke = other.stroke ?: stroke,
            strokeWidth = other.strokeWidth ?: strokeWidth,
            textColor = other.textColor ?: textColor,
            fontWeightBold = other.fontWeightBold ?: fontWeightBold,
        )
    }
}

/**
 * A hierarchical section or leaf node in a [TreemapDiagram].
 */
data class TreemapNode(
    val name: String,
    val value: Double? = null,
    val children: List<TreemapNode> = emptyList(),
    val classSelector: String? = null,
    val style: TreemapNodeStyle? = null,
) {
    val isLeaf: Boolean
        get() = children.isEmpty()
}

/**
 * Layout and formatting configuration for a [TreemapDiagram].
 */
data class TreemapConfig(
    val padding: Float = 10f,
    val diagramPadding: Float = 8f,
    val showValues: Boolean = true,
    val width: Float = 760f,
    val height: Float = 440f,
    val borderWidth: Float = 1.5f,
    val valueFontSize: Float = 12f,
    val labelFontSize: Float = 14f,
    val valueFormat: String = ",",
)

/**
 * Parsed representation of a Mermaid `treemap` / `treemap-beta` diagram.
 */
data class TreemapDiagram(
    val roots: List<TreemapNode>,
    val config: TreemapConfig = TreemapConfig(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
