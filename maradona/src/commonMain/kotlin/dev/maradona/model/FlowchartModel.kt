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

/** Raw CSS-like colours from a `style` statement, kept as hex strings. */
data class NodeStyle(
    val fill: String? = null,
    val stroke: String? = null,
    val textColor: String? = null,
)

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
