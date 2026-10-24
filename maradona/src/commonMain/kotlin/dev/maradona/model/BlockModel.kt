package dev.maradona.model

/**
 * Visual shape of a block in a Mermaid `block` / `block-beta` diagram.
 */
enum class BlockShape {
    Rect,
    Round,
    Stadium,
    Subroutine,
    Cylinder,
    Circle,
    DoubleCircle,
    Asymmetric,
    Diamond,
    Hexagon,
    LeanRight,
    LeanLeft,
    Trapezoid,
    InvTrapezoid,
    BlockArrow,
    Composite,
    Space,
}

/**
 * Arrow direction token for a [BlockShape.BlockArrow] block (`<["Label"]>(right)`).
 */
enum class BlockArrowDir {
    Right,
    Left,
    Up,
    Down,
    X,
    Y,
}

/**
 * Resolved CSS-like styling for a [BlockItem].
 */
data class BlockNodeStyle(
    val fill: String? = null,
    val stroke: String? = null,
    val strokeWidth: Float? = null,
    val textColor: String? = null,
    val strokeDashArray: List<Float>? = null,
    val fontWeightBold: Boolean? = null,
) {
    fun merge(other: BlockNodeStyle?): BlockNodeStyle {
        if (other == null) return this
        return BlockNodeStyle(
            fill = other.fill ?: fill,
            stroke = other.stroke ?: stroke,
            strokeWidth = other.strokeWidth ?: strokeWidth,
            textColor = other.textColor ?: textColor,
            strokeDashArray = other.strokeDashArray ?: strokeDashArray,
            fontWeightBold = other.fontWeightBold ?: fontWeightBold,
        )
    }
}

/**
 * A node, spacer, or composite sub-block in a [BlockDiagram].
 */
data class BlockItem(
    val id: String,
    val label: String,
    val shape: BlockShape = BlockShape.Rect,
    val widthInColumns: Int = 1,
    val columns: Int = -1,
    val children: List<BlockItem> = emptyList(),
    val arrowDirections: List<BlockArrowDir> = emptyList(),
    val classes: List<String> = listOf("default"),
    val style: BlockNodeStyle? = null,
    val colorIndex: Int = 0,
)

/**
 * Line stroke style for a [BlockEdge].
 */
enum class BlockEdgePattern {
    Solid,
    Dotted,
    Thick,
    Invisible,
}

/**
 * Marker head at either end of a [BlockEdge].
 */
enum class BlockArrowHead {
    None,
    Point,
    Circle,
    Cross,
}

/**
 * Directed or undirected connection between two blocks in a [BlockDiagram].
 */
data class BlockEdge(
    val id: String,
    val from: String,
    val to: String,
    val label: String? = null,
    val pattern: BlockEdgePattern = BlockEdgePattern.Solid,
    val startHead: BlockArrowHead = BlockArrowHead.None,
    val endHead: BlockArrowHead = BlockArrowHead.Point,
)

/**
 * Layout configuration for a [BlockDiagram].
 */
data class BlockConfig(
    val padding: Float = 8f,
)

/**
 * Parsed representation of a Mermaid `block` / `block-beta` diagram.
 */
data class BlockDiagram(
    val rootColumns: Int = -1,
    val children: List<BlockItem>,
    val edges: List<BlockEdge> = emptyList(),
    val config: BlockConfig = BlockConfig(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
