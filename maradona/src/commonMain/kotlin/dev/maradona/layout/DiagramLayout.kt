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
    /**
     * Padding mermaid's edge-label box carries around the text on each side.
     * Measured from the goldens: a one-line label whose drawn background is 20
     * units tall occupies a 60-unit rank, which is exactly what dagre produces
     * when the label is passed as 60 tall. Density-dependent, like every other
     * value here.
     */
    val edgeLabelPadding: Float = 20f,
    // Measured from mermaid's clusters: 24 units of padding all round, plus a
    // 24-unit band at the top for the cluster label.
    val subgraphPadding: Float = 24f,
    val subgraphLabelHeight: Float = 24f,
    /** Force a direction regardless of the source (used for narrow-screen adaption). */
    val directionOverride: Direction? = null,
)
