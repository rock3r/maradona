package dev.maradona.layout

import dev.maradona.model.Direction
import dev.maradona.model.FlowEdge
import dev.maradona.model.FlowNode
import dev.maradona.model.Flowchart
import dev.maradona.model.Subgraph

data class DiagramSize(val width: Float, val height: Float)

data class DiagramPoint(val x: Float, val y: Float)

data class DiagramRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

data class PositionedNode(val node: FlowNode, val rect: DiagramRect)

data class PositionedEdge(
    val edge: FlowEdge,
    /** Orthogonal polyline from the source border to the target border. */
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect? = null,
)

data class PositionedSubgraph(val subgraph: Subgraph, val rect: DiagramRect)

data class DiagramLayout(
    val width: Float,
    val height: Float,
    val direction: Direction,
    val nodes: List<PositionedNode>,
    val edges: List<PositionedEdge>,
    val subgraphs: List<PositionedSubgraph>,
) {
    fun node(id: String): PositionedNode? = nodes.firstOrNull { it.node.id == id }
}

data class LayoutOptions(
    // Mermaid configures dagre with nodeSpacing/rankSpacing 50, but dagre halves
    // ranksep and doubles every edge's minlen (to make room for edge labels), so
    // the gap actually drawn between node borders is 40. Measured from the
    // goldens: 40.0 units for both axes.
    val nodeGap: Float = 40f,
    val layerGap: Float = 40f,
    val padding: Float = 8f,
    /** Extra rank a labelled edge gets: dagre's label dummy, whose height is the
     * label height plus twice its edgesep (20). Measured: 61.5 units. */
    val edgeLabelRankGap: Float = 61.5f,
    val subgraphPadding: Float = 16f,
    val subgraphLabelHeight: Float = 28f,
    /** Force a direction regardless of the source (used for narrow-screen adaption). */
    val directionOverride: Direction? = null,
)
