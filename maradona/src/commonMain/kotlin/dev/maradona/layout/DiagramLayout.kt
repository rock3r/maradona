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
    // mermaid's flowchart defaults: nodeSpacing / rankSpacing / diagramPadding.
    // Matching them is what makes our node positions comparable to its SVG.
    val nodeGap: Float = 50f,
    val layerGap: Float = 50f,
    val padding: Float = 8f,
    val subgraphPadding: Float = 16f,
    val subgraphLabelHeight: Float = 28f,
    /** Force a direction regardless of the source (used for narrow-screen adaption). */
    val directionOverride: Direction? = null,
)
