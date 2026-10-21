package dev.maradona.model

/** Flow direction declared after `graph` / `flowchart`. */
enum class Direction { TB, BT, LR, RL }

enum class NodeShape {
    Rect, RoundedRect, Stadium, Subroutine, Cylinder,
    Circle, DoubleCircle, Diamond, Hexagon,
    Parallelogram, ParallelogramAlt, Trapezoid, TrapezoidAlt,
    Asymmetric,
}

enum class EdgeStroke { Solid, Dotted, Thick, Invisible }

enum class ArrowHead { None, Arrow, Cross, Circle }

/** Raw CSS-like colours from a `style` or `classDef` statement, kept as hex/named strings. */
data class NodeStyle(
    val fill: String? = null,
    val stroke: String? = null,
    val textColor: String? = null,
    /** `stroke-width` from a `style` or `classDef` statement, e.g. "2px" or "2". */
    val strokeWidth: String? = null,
) {
    /** Merges [override] on top of this style. */
    fun merge(override: NodeStyle?): NodeStyle {
        if (override == null) return this
        return NodeStyle(
            fill = override.fill ?: fill,
            stroke = override.stroke ?: stroke,
            textColor = override.textColor ?: textColor,
            strokeWidth = override.strokeWidth ?: strokeWidth,
        )
    }
}

/** Raw CSS-like properties from a `linkStyle` statement. */
data class EdgeStyle(
    val stroke: String? = null,
    val strokeWidth: String? = null,
) {
    fun merge(override: EdgeStyle?): EdgeStyle {
        if (override == null) return this
        return EdgeStyle(
            stroke = override.stroke ?: stroke,
            strokeWidth = override.strokeWidth ?: strokeWidth,
        )
    }
}

data class FlowNode(
    val id: String,
    val label: String = id,
    val shape: NodeShape = NodeShape.Rect,
    val subgraphId: String? = null,
    val style: NodeStyle? = null,
)

data class FlowEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    val stroke: EdgeStroke = EdgeStroke.Solid,
    val startHead: ArrowHead = ArrowHead.None,
    val endHead: ArrowHead = ArrowHead.Arrow,
    val style: EdgeStyle? = null,
)

data class Subgraph(
    val id: String,
    val label: String = id,
    val parentId: String? = null,
    val direction: Direction? = null,
)

data class Flowchart(
    val direction: Direction = Direction.TB,
    val nodes: List<FlowNode> = emptyList(),
    val edges: List<FlowEdge> = emptyList(),
    val subgraphs: List<Subgraph> = emptyList(),
)
