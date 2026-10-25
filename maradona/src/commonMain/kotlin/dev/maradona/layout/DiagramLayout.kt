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

    fun intersects(other: DiagramRect): Boolean =
        x <= other.right && right >= other.x && y <= other.bottom && bottom >= other.y

    fun inflate(margin: Float): DiagramRect =
        DiagramRect(x - margin, y - margin, width + margin * 2f, height + margin * 2f)
}

data class PositionedNode(val node: FlowNode, val rect: DiagramRect)

data class PositionedEdge(
    val edge: FlowEdge,
    /** Orthogonal polyline from the source border to the target border. */
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect? = null,
) {
    /** Axis-aligned bounding box of all route points and optional label rect, inflated by [margin]. */
    fun bounds(margin: Float = 0f): DiagramRect {
        if (points.isEmpty()) return DiagramRect(0f, 0f, 0f, 0f)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        labelRect?.let { lr ->
            if (lr.x < minX) minX = lr.x
            if (lr.right > maxX) maxX = lr.right
            if (lr.y < minY) minY = lr.y
            if (lr.bottom > maxY) maxY = lr.bottom
        }
        return DiagramRect(
            x = minX - margin,
            y = minY - margin,
            width = (maxX - minX) + margin * 2f,
            height = (maxY - minY) + margin * 2f,
        )
    }
}

/**
 * Diagnostic counters reported by [dev.maradona.render.MaradonaLayout] and
 * [dev.maradona.render.MaradonaDiagram] when viewport-lazy composition and
 * underlay culling are active.
 */
data class MaradonaViewportStats(
    val composedNodes: Int,
    val totalNodes: Int,
    val composedEdgeLabels: Int,
    val totalEdgeLabels: Int,
    val drawnEdges: Int,
    val totalEdges: Int,
    val drawnSubgraphs: Int,
    val totalSubgraphs: Int,
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
    // mermaid passes nodeSpacing/rankSpacing straight to dagre as nodesep and
    // ranksep (50 each). dagre then halves ranksep and doubles every minlen to
    // make room for edge labels, so the gap drawn between ranks is
    // 2 * ranksep/2 = 50 and the gap between siblings is nodesep = 50.
    val nodeGap: Float = 50f,
    val layerGap: Float = 50f,
    val padding: Float = 8f,

    // A cluster's box comes from dagre's border nodes: zero-width dummies half an
    // `edgesep` outside the outermost child on each side, and one rank above and
    // below the children vertically. The cluster label sits in the top band.
    // Measured from dagre's output: a cluster's border dummies sit ~40 units
    // outside its children horizontally and one rank (25) above and below.
    val subgraphPaddingX: Float = 40f,
    val subgraphPaddingY: Float = 25f,
    /** Render density (px per dp), used when a shape outline formula depends on dp width. */
    val density: Float = 1f,
    /** Force a direction regardless of the source (used for narrow-screen adaption). */
    val directionOverride: Direction? = null,
)
