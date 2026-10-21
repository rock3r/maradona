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
        if (chart.nodes.isEmpty() && chart.subgraphs.isEmpty()) {
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
        return canonical.transformed(direction).attachOutlines(options)
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
            val sizesById = chart.nodes.associate { it.id to sizes.getValue(it.id) }.toMutableMap()
            val subgraphIds = chart.subgraphs.map { it.id }.toSet()
            // Only leaf empty subgraphs get a synthetic dagre node; parent subgraphs
            // include their descendant leaf empty subgraphs in their member list so
            // nested empty subgraphs sit inside their parent box.
            val leafEmptySubgraphIds = chart.subgraphs
                .filter { sg ->
                    chart.nodes.none { it.subgraphId == sg.id } &&
                        chart.subgraphs.none { it.parentId == sg.id }
                }
                .map { it.id }
                .toSet()
            for (sgId in leafEmptySubgraphIds) {
                sizesById.getOrPut(sgId) { DiagramSize(120f, 54f) }
            }
            fun membersOf(sgId: String): List<String> {
                val direct = chart.nodes.filter { it.subgraphId == sgId }.map { it.id }
                val selfLeaf = if (sgId in leafEmptySubgraphIds) listOf(sgId) else emptyList()
                val nested = chart.subgraphs.filter { it.parentId == sgId }.flatMap { membersOf(it.id) }
                return direct + selfLeaf + nested
            }
            val dagreNodes = sizesById.map { (id, sz) -> id to (sz.width to sz.height) }

            fun resolveSubgraphEndpoint(id: String, isSource: Boolean): String {
                if (id in sizesById) return id
                if (id in subgraphIds) {
                    val members = membersOf(id)
                    if (members.isNotEmpty()) return if (isSource) members.last() else members.first()
                }
                return id
            }

            // A cluster occupies space of its own, not just its children, so an
            // edge that crosses a cluster boundary gets an extra rank for it.
            // One rank is what mermaid's dagre layout shows: 290 units between a
            // cluster's last child and the next cluster's first, against 240 for
            // a plain rank step at these sizes.
            val clusterOf = chart.nodes.associate { it.id to it.subgraphId }
            val boundaryRanks = if (chart.subgraphs.isEmpty()) 0 else 1
            fun minlenFor(fromId: String, toId: String): Int {
                if (boundaryRanks == 0) return 1
                val from = clusterOf[fromId] ?: if (fromId in subgraphIds) fromId else null
                val to = clusterOf[toId] ?: if (toId in subgraphIds) toId else null
                return if (from != to && (from != null || to != null)) 1 + boundaryRanks else 1
            }

            val dagreEdges = chart.edges
                .withIndex()
                .mapNotNull { (index, edge) ->
                    if (edge.from == edge.to) return@mapNotNull null
                    val from = resolveSubgraphEndpoint(edge.from, isSource = true)
                    val to = resolveSubgraphEndpoint(edge.to, isSource = false)
                    if (from == to || from !in sizesById || to !in sizesById) return@mapNotNull null
                    val label = edgeLabelSize(edge)
                    dev.maradona.layout.dagre.Dagre.EdgeSpec(
                        from = from,
                        to = to,
                        name = "e$index",
                        minlen = minlenFor(from, to),
                        labelWidth = label?.width ?: 0f,
                        labelHeight = label?.height ?: 0f,
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
                    val size = edgeLabelSize(edge)
                    val rect = size?.let { DiagramRect(0f, 0f, it.width, it.height) }
                    return@mapIndexed PositionedEdge(edge, emptyList(), rect)
                }
                val path = result.paths["e$index"]
                val waypoints = path?.points?.map { DiagramPoint(it.first, it.second) }.orEmpty()
                val labelSize = edgeLabelSize(edge)
                val labelRect = labelSize?.let { size ->
                    val centre = path?.labelCentre
                        ?: waypoints.getOrNull(waypoints.size / 2)?.let { it.x to it.y }
                    centre?.let {
                        DiagramRect(it.first - size.width / 2f, it.second - size.height / 2f, size.width, size.height)
                    }
                }
                dev.maradona.layout.PositionedEdge(edge, waypoints, labelRect)
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
            // Bounds come from nodes, clusters and edge labels - not from the
            // routed polylines: dagre positions from node and label geometry, so
            // counting a line that swings wide would shift the whole diagram.
            positionedEdges.forEach { edge ->
                if (edge.edge.from != edge.edge.to) edge.labelRect?.let(::include)
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
                val selfLeaf = if (chart.nodes.none { it.subgraphId == sgId } && chart.subgraphs.none { it.parentId == sgId }) {
                    listOf(sgId)
                } else {
                    emptyList()
                }
                val nested = chart.subgraphs.filter { it.parentId == sgId }.flatMap { membersOf(it.id) }
                return direct + selfLeaf + nested
            }
            // Build bottom-up so a parent subgraph's box encloses its nested child subgraphs' padded boxes.
            val built = HashMap<String, DiagramRect>()
            fun buildOne(sg: dev.maradona.model.Subgraph): DiagramRect? {
                built[sg.id]?.let { return it }
                val directRects = chart.nodes.filter { it.subgraphId == sg.id }.mapNotNull { nodeRects[it.id] }
                val childSgRects = chart.subgraphs.filter { it.parentId == sg.id }.mapNotNull { buildOne(it) }
                val selfSynthetic = if (directRects.isEmpty() && childSgRects.isEmpty()) {
                    listOfNotNull(nodeRects[sg.id])
                } else {
                    emptyList()
                }
                val rects = directRects + childSgRects + selfSynthetic
                if (rects.isEmpty()) return null
                val minX = rects.minOf { it.x } - options.subgraphPaddingX
                val minY = rects.minOf { it.y } - options.subgraphPaddingY
                val maxX = rects.maxOf { it.right } + options.subgraphPaddingX
                val maxY = rects.maxOf { it.bottom } + options.subgraphPaddingY
                return DiagramRect(minX, minY, maxX - minX, maxY - minY).also { built[sg.id] = it }
            }
            return chart.subgraphs.mapNotNull { sg ->
                val rect = buildOne(sg) ?: return@mapNotNull null
                PositionedSubgraph(sg, rect)
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

/**
 * Attaches edge endpoints to the actual node shape outlines in final (unrotated)
 * diagram coordinates, and routes self-loops around the final node boxes.
 */
private fun DiagramLayout.attachOutlines(options: LayoutOptions): DiagramLayout {
    val nodeById = nodes.associateBy { it.node.id }
    val subgraphById = subgraphs.associateBy { it.subgraph.id }
    fun DiagramRect.contains(p: DiagramPoint): Boolean =
        p.x >= x && p.x <= right && p.y >= y && p.y <= bottom

    var hasSelfLoop = false
    val updatedEdges = edges.map { positioned ->
        val edge = positioned.edge
        val fromNode = nodeById[edge.from]
        val fromSg = if (fromNode == null) subgraphById[edge.from] else null
        val fromRect = fromNode?.rect ?: fromSg?.rect ?: return@map positioned
        val fromShape = fromNode?.node?.shape ?: dev.maradona.model.NodeShape.Rect

        val toNode = nodeById[edge.to]
        val toSg = if (toNode == null) subgraphById[edge.to] else null
        val toRect = toNode?.rect ?: toSg?.rect ?: return@map positioned
        val toShape = toNode?.node?.shape ?: dev.maradona.model.NodeShape.Rect

        if (edge.from == edge.to) {
            hasSelfLoop = true
            val r = fromRect
            val out = 28f * options.density
            val points = listOf(
                DiagramPoint(r.right, r.centerY),
                DiagramPoint(r.right + out, r.centerY),
                DiagramPoint(r.right + out, r.y - out),
                DiagramPoint(r.centerX + r.width / 4f, r.y - out),
                DiagramPoint(r.centerX + r.width / 4f, r.y),
            )
            val labelRect = positioned.labelRect?.let {
                DiagramRect(r.right + out - it.width / 2f, r.centerY - it.height / 2f, it.width, it.height)
            }
            return@map positioned.copy(points = points, labelRect = labelRect)
        }
        val pts = positioned.points.toMutableList()
        if (pts.size < 2) return@map positioned
        // When an edge starts or ends on a subgraph, drop interior waypoints that
        // sit inside the subgraph box so the edge terminates on the cluster boundary.
        if (fromSg != null) {
            while (pts.size > 2 && fromRect.contains(pts[1])) {
                pts.removeAt(1)
            }
        }
        if (toSg != null) {
            while (pts.size > 2 && toRect.contains(pts[pts.lastIndex - 1])) {
                pts.removeAt(pts.lastIndex - 1)
            }
        }
        pts[0] = NodeOutline.intersect(fromShape, fromRect, pts[1], options.density)
        pts[pts.lastIndex] = NodeOutline.intersect(toShape, toRect, pts[pts.lastIndex - 1], options.density)
        positioned.copy(points = pts)
    }
    if (!hasSelfLoop) return copy(edges = updatedEdges)

    // Ensure self-loop polylines and labels fit within the layout bounds.
    var minX = options.padding
    var minY = options.padding
    var maxX = width - options.padding
    var maxY = height - options.padding
    for (e in updatedEdges) {
        if (e.edge.from != e.edge.to) continue
        for (p in e.points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        e.labelRect?.let { r ->
            if (r.x < minX) minX = r.x
            if (r.y < minY) minY = r.y
            if (r.right > maxX) maxX = r.right
            if (r.bottom > maxY) maxY = r.bottom
        }
    }
    val dx = options.padding - minX
    val dy = options.padding - minY
    fun DiagramRect.shifted() = if (dx == 0f && dy == 0f) this else copy(x = x + dx, y = y + dy)
    return DiagramLayout(
        width = (maxX - minX) + options.padding * 2,
        height = (maxY - minY) + options.padding * 2,
        direction = direction,
        nodes = if (dx == 0f && dy == 0f) nodes else nodes.map { it.copy(rect = it.rect.shifted()) },
        edges = if (dx == 0f && dy == 0f) updatedEdges else updatedEdges.map {
            it.copy(
                points = it.points.map { p -> DiagramPoint(p.x + dx, p.y + dy) },
                labelRect = it.labelRect?.shifted(),
            )
        },
        subgraphs = if (dx == 0f && dy == 0f) subgraphs else subgraphs.map { it.copy(rect = it.rect.shifted()) },
    )
}
