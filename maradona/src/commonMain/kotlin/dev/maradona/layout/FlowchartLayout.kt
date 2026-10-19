package dev.maradona.layout

import dev.maradona.model.Direction
import dev.maradona.model.FlowNode
import dev.maradona.model.Flowchart
import kotlin.math.max

/**
 * Layered (Sugiyama) layout for flowcharts.
 *
 * Pipeline: cycle breaking (DFS back edges) → longest-path layering →
 * dummy nodes for long edges → barycentre crossing reduction →
 * coordinate assignment with neighbour alignment → orthogonal routing.
 *
 * Everything is computed in canonical TB space, then transformed for
 * BT / LR / RL. Node and edge-label sizes are supplied by the caller,
 * so the Compose layer can pass real measured sizes.
 */
object FlowchartLayout {

    fun layout(
        chart: Flowchart,
        nodeSize: (FlowNode) -> DiagramSize,
        edgeLabelSize: (dev.maradona.model.FlowEdge) -> DiagramSize?,
        options: LayoutOptions = LayoutOptions(),
    ): DiagramLayout {
        val direction = options.directionOverride ?: chart.direction
        if (chart.nodes.isEmpty()) {
            return DiagramLayout(0f, 0f, direction, emptyList(), emptyList(), emptyList())
        }
        // For horizontal directions, run canonical layout with swapped sizes so a
        // pure transpose of the result yields correct (unrotated) node boxes.
        val swapped = direction == Direction.LR || direction == Direction.RL
        fun DiagramSize.maybeSwapped() = if (swapped) DiagramSize(height, width) else this
        val canonical = CanonicalLayout(
            chart,
            { nodeSize(it).maybeSwapped() },
            { edgeLabelSize(it)?.maybeSwapped() },
            options,
        ).compute()
        return canonical.transformed(direction)
    }

    private class CanonicalLayout(
        private val chart: Flowchart,
        nodeSize: (FlowNode) -> DiagramSize,
        private val edgeLabelSize: (dev.maradona.model.FlowEdge) -> DiagramSize?,
        private val options: LayoutOptions,
    ) {
        private val sizes: Map<String, DiagramSize> = chart.nodes.associate { it.id to nodeSize(it) }
        private val nodeIds: List<String> = chart.nodes.map { it.id }

        /** Edge in effective (cycle-broken) direction. */
        private data class EffEdge(val from: String, val to: String, val index: Int, val reversed: Boolean)


        fun compute(): DiagramLayout {
            val sizesById = chart.nodes.associate { it.id to sizes.getValue(it.id) }
            val shapes = chart.nodes.associate { it.id to it.shape }
            val dagreNodes = chart.nodes.map { it.id to (sizesById.getValue(it.id).width to sizesById.getValue(it.id).height) }
            val dagreEdges = chart.edges
                .withIndex()
                .filter { it.value.from != it.value.to }
                .map { (index, edge) ->
                    val label = edgeLabelSize(edge)
                    dev.maradona.layout.dagre.Dagre.EdgeSpec(
                        from = edge.from,
                        to = edge.to,
                        name = "e$index",
                        labelWidth = label?.width ?: 0f,
                        labelHeight = label?.let { it.height + options.edgeLabelPadding * 2 } ?: 0f,
                    )
                }

            val result = dev.maradona.layout.dagre.Dagre.layout(
                nodes = dagreNodes,
                edges = dagreEdges,
                clusters = chart.subgraphs.map {
                    dev.maradona.layout.dagre.Dagre.ClusterSpec(it.id, 0f)
                },
                clusterOf = { id -> chart.nodes.firstOrNull { it.id == id }?.subgraphId },
                nodesep = options.nodeGap,
                ranksep = options.layerGap,
            )

            val nodeRects = HashMap<String, DiagramRect>()
            for ((id, box) in result.nodes) {
                nodeRects[id] = DiagramRect(box.left, box.top, box.width, box.height)
            }

            val positionedEdges = chart.edges.mapIndexed { index, edge ->
                if (edge.from == edge.to) {
                    return@mapIndexed selfLoop(edge, nodeRects.getValue(edge.from))
                }
                val path = result.paths["e$index"]
                var waypoints = path?.points?.map { DiagramPoint(it.first, it.second) }.orEmpty()
                // Attach to the shapes' outlines, not their bounding boxes:
                // mermaid draws connections to the geometry it renders.
                if (waypoints.size >= 2) {
                    val fromRect = nodeRects.getValue(edge.from)
                    val toRect = nodeRects.getValue(edge.to)
                    val fromShape = shapes.getValue(edge.from)
                    val toShape = shapes.getValue(edge.to)
                    waypoints = waypoints.toMutableList().also { points ->
                        points[0] = NodeOutline.intersect(fromShape, fromRect, points[1])
                        points[points.lastIndex] = NodeOutline.intersect(toShape, toRect, points[points.lastIndex - 1])
                    }
                }
                var points = orthogonalize(waypoints)
                val labelSize = edgeLabelSize(edge)
                val labelRect = labelSize?.let { size ->
                    val centre = path?.labelCentre
                        ?: waypoints.getOrNull(waypoints.size / 2)?.let { it.x to it.y }
                    centre?.let {
                        DiagramRect(it.first - size.width / 2f, it.second - size.height / 2f, size.width, size.height)
                    }
                }
                dev.maradona.layout.PositionedEdge(edge, points, labelRect)
            }

            val subgraphRects = buildSubgraphs(nodeRects, result.clusters)

            // Normalise so the minimum coordinate sits at [options.padding].
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = 0f
            var maxY = 0f
            fun include(r: DiagramRect) {
                if (r.x < minX) minX = r.x
                if (r.y < minY) minY = r.y
                if (r.right > maxX) maxX = r.right
                if (r.bottom > maxY) maxY = r.bottom
            }
            nodeRects.values.forEach(::include)
            subgraphRects.forEach { include(it.rect) }
            positionedEdges.forEach { edge ->
                edge.points.forEach { p ->
                    if (p.x < minX) minX = p.x
                    if (p.y < minY) minY = p.y
                    if (p.x > maxX) maxX = p.x
                    if (p.y > maxY) maxY = p.y
                }
                edge.labelRect?.let(::include)
            }
            val dx = options.padding - minX
            val dy = options.padding - minY
            fun DiagramRect.shifted() = copy(x = x + dx, y = y + dy)

            return DiagramLayout(
                width = (maxX - minX) + options.padding * 2,
                height = (maxY - minY) + options.padding * 2,
                direction = Direction.TB,
                nodes = chart.nodes.map { PositionedNode(it, nodeRects.getValue(it.id).shifted()) },
                edges = positionedEdges.map {
                    it.copy(
                        points = it.points.map { p -> DiagramPoint(p.x + dx, p.y + dy) },
                        labelRect = it.labelRect?.shifted(),
                    )
                },
                subgraphs = subgraphRects.map { it.copy(rect = it.rect.shifted()) },
            )
        }

        private fun orthogonalize(waypoints: List<DiagramPoint>): List<DiagramPoint> {
            if (waypoints.size < 2) return waypoints
            val out = mutableListOf(waypoints.first())
            for (i in 0 until waypoints.lastIndex) {
                val a = waypoints[i]
                val b = waypoints[i + 1]
                if (kotlin.math.abs(a.x - b.x) > 0.5f) {
                    val midY = (a.y + b.y) / 2f
                    out += DiagramPoint(a.x, midY)
                    out += DiagramPoint(b.x, midY)
                }
                out += b
            }
            return out
        }

        private fun selfLoop(edge: dev.maradona.model.FlowEdge, r: DiagramRect): PositionedEdge {
            val out = 28f
            val points = listOf(
                DiagramPoint(r.right, r.centerY),
                DiagramPoint(r.right + out, r.centerY),
                DiagramPoint(r.right + out, r.y - out),
                DiagramPoint(r.centerX + r.width / 4f, r.y - out),
                DiagramPoint(r.centerX + r.width / 4f, r.y),
            )
            val labelSize = edgeLabelSize(edge)
            val labelRect = labelSize?.let {
                DiagramRect(r.right + out - it.width / 2f, r.centerY - it.height / 2f, it.width, it.height)
            }
            return PositionedEdge(edge, points, labelRect)
        }

        private fun buildSubgraphs(
            nodeRects: Map<String, DiagramRect>,
            clusterBoxes: Map<String, dev.maradona.layout.dagre.Dagre.NodeBox>,
        ): List<PositionedSubgraph> {
            if (chart.subgraphs.isEmpty()) return emptyList()
            // dagre's compound layout already positions every cluster from its
            // border nodes, so its box is authoritative.
            if (clusterBoxes.isNotEmpty()) {
                return chart.subgraphs.mapNotNull { sg ->
                    val box = clusterBoxes[sg.id] ?: return@mapNotNull null
                    PositionedSubgraph(sg, DiagramRect(box.left, box.top, box.width, box.height))
                }
            }
            fun membersOf(sgId: String): List<String> {
                val direct = chart.nodes.filter { it.subgraphId == sgId }.map { it.id }
                val nested = chart.subgraphs.filter { it.parentId == sgId }.flatMap { membersOf(it.id) }
                return direct + nested
            }
            return chart.subgraphs.mapNotNull { sg ->
                val rects = membersOf(sg.id).mapNotNull { nodeRects[it] }
                if (rects.isEmpty()) return@mapNotNull null
                val minX = rects.minOf { it.x } - options.subgraphPadding
                val minY = rects.minOf { it.y } - options.subgraphPadding - options.subgraphLabelHeight
                val maxX = rects.maxOf { it.right } + options.subgraphPadding
                val maxY = rects.maxOf { it.bottom } + options.subgraphPadding
                PositionedSubgraph(sg, DiagramRect(minX, minY, maxX - minX, maxY - minY))
            }
        }
    }
}

// -- Direction transforms ----------------------------------------------------

private fun DiagramLayout.transformed(direction: Direction): DiagramLayout {
    if (direction == Direction.TB) return this
    val w = width
    val h = height
    fun point(p: DiagramPoint): DiagramPoint = when (direction) {
        Direction.TB -> p
        Direction.BT -> DiagramPoint(p.x, h - p.y)
        Direction.LR -> DiagramPoint(p.y, p.x)
        Direction.RL -> DiagramPoint(h - p.y, p.x)
    }
    fun rect(r: DiagramRect): DiagramRect = when (direction) {
        Direction.TB -> r
        Direction.BT -> DiagramRect(r.x, h - r.y - r.height, r.width, r.height)
        Direction.LR -> DiagramRect(r.y, r.x, r.height, r.width)
        Direction.RL -> DiagramRect(h - r.y - r.height, r.x, r.height, r.width)
    }
    return DiagramLayout(
        width = if (direction == Direction.BT) w else h,
        height = if (direction == Direction.BT) h else w,
        direction = direction,
        nodes = nodes.map { it.copy(rect = rect(it.rect)) },
        edges = edges.map { it.copy(points = it.points.map(::point), labelRect = it.labelRect?.let(::rect)) },
        subgraphs = subgraphs.map { it.copy(rect = rect(it.rect)) },
    )
}
