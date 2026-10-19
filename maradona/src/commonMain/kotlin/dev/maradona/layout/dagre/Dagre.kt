package dev.maradona.layout.dagre

/**
 * dagre's layout pipeline, ported so Maradona's geometry matches mermaid's
 * (mermaid lays flowcharts out with dagre). The step order follows
 * `dagre/layout.js`; each step is a port of the corresponding source file.
 *
 * Spacing is what mermaid draws: `nodesep` 50, `edgesep` 20, `ranksep` 40.
 * dagre halves `ranksep` and doubles every `minlen` to make room for edge
 * labels, so node borders end up 40 apart and a labelled edge gets a rank of
 * its own for the label.
 */
internal object Dagre {

    /** A cluster: its id, the nodes it contains, and its label height. */
    class ClusterSpec(val id: String, val labelHeight: Float)

    class EdgeSpec(
        val from: String,
        val to: String,
        val name: String? = null,
        val weight: Float = 1f,
        val minlen: Int = 1,
        val labelWidth: Float = 0f,
        val labelHeight: Float = 0f,
        /** dagre's labelpos. Mermaid centres flowchart edge labels, i.e. "c". */
        val labelpos: String = "c",
        val labeloffset: Float = 10f,
    )

    class NodeBox(val x: Float, val y: Float, val width: Float, val height: Float) {
        val left: Float get() = x - width / 2f
        val top: Float get() = y - height / 2f
        val right: Float get() = x + width / 2f
        val bottom: Float get() = y + height / 2f
    }

    /** A polyline dagre routed, in the coordinates of [Result]. */
    class EdgePath(val points: List<Pair<Float, Float>>, val labelCentre: Pair<Float, Float>?)

    class Result(
        val nodes: Map<String, NodeBox>,
        val paths: Map<String, EdgePath>,
        val clusters: Map<String, NodeBox>,
        val width: Float,
        val height: Float,
    )

    fun layout(
        nodes: List<Pair<String, Pair<Float, Float>>>,
        edges: List<EdgeSpec>,
        clusters: List<ClusterSpec> = emptyList(),
        clusterOf: (String) -> String? = { null },
        nodesep: Float = 50f,
        ranksep: Float = 40f,
        edgesep: Float = 20f,
        marginx: Float = 8f,
        marginy: Float = 8f,
    ): Result {
        // mermaid does not use dagre's compound (nested-cluster) layout for
        // flowcharts: it lays each cluster out as its own dagre graph and
        // composes the results. Until that composition is ported, clusters are
        // laid out as plain nodes and their boxes are derived from their
        // members, so `clusters` is accepted but only used for bookkeeping.
        val compound = false && clusters.isNotEmpty()
        val g = DagreGraph(multigraph = true, compound = compound)
        g.nodesep = nodesep
        g.ranksep = ranksep
        g.edgesep = edgesep
        g.marginx = marginx
        g.marginy = marginy
        g.rankdir = "tb"

        for ((id, size) in nodes) g.setNode(id, DagreNode(size.first, size.second))
        if (compound) {
            for (cluster in clusters) {
                // A cluster is a node whose children are its members; its box is
                // derived from the border nodes after positioning.
                val label = DagreNode(0f, cluster.labelHeight)
                g.setNode(cluster.id, label)
            }
            for ((id, _) in nodes) clusterOf(id)?.let { g.setParent(id, it) }
        }
        for (edge in edges) {
            val label = DagreEdge(edge.from, edge.to, edge.name)
            label.weight = edge.weight
            label.minlen = edge.minlen
            label.width = edge.labelWidth
            label.height = edge.labelHeight
            label.labelpos = edge.labelpos
            label.labeloffset = edge.labeloffset
            g.setEdge(edge.from, edge.to, label, edge.name)
        }

        makeSpaceForEdgeLabels(g)
        val reversedEdges = runAcyclic(g)
        if (compound) g.runNestingGraph()
        // dagre ranks the non-compound view: only leaves get a rank, clusters
        // derive theirs from their nesting border nodes.
        rank(if (compound) g.asNonCompound() else g)
        injectEdgeLabelProxies(g)
        g.removeEmptyRanks()
        if (compound) g.cleanupNestingGraph()
        g.normalizeRanks()
        assignRankMinMax(g)
        removeEdgeLabelProxies(g)
        val dummyChains = normalize(g)
        if (compound) g.parentDummyChains(dummyChains)
        if (compound) g.addBorderSegments()
        if (compound) orderCompound(g) else order(g)
        adjustCoordinateSystem(g)
        position(g)
        if (compound) g.removeBorderNodes()
        undoNormalize(g, dummyChains)
        fixupEdgeLabelCoords(g)
        undoCoordinateSystem(g)
        val bounds = translateGraph(g)
        assignNodeIntersects(g)
        reversePointsForReversedEdges(g)
        undoAcyclic(g, reversedEdges)

        val nodeBoxes = HashMap<String, NodeBox>()
        val clusterBoxes = HashMap<String, NodeBox>()
        for ((v, node) in g.nodes) {
            if (node.isDummy) continue
            val box = NodeBox(node.x, node.y, node.width, node.height)
            if (g.childrenOf(v).isNotEmpty()) clusterBoxes[v] = box else nodeBoxes[v] = box
        }
        val paths = LinkedHashMap<String, EdgePath>()
        for ((key, e) in g.edges) {
            val id = key.name ?: "${e.v}->${e.w}"
            val centre = if (e.hasLabel) e.x to e.y else e.points.getOrNull(e.points.size / 2)
            paths[id] = EdgePath(e.points.toList(), centre)
        }
        return Result(nodeBoxes, paths, clusterBoxes, bounds.first, bounds.second)
    }

    // --- steps, in dagre's order ---

    /** `makeSpaceForEdgeLabels`. */
    private fun makeSpaceForEdgeLabels(g: DagreGraph) {
        g.ranksep /= 2f
        for (e in g.edges.values) {
            e.minlen *= 2
            if (e.labelpos.lowercase() != "c") {
                e.width += e.labeloffset
            }
        }
    }

    /** `acyclic.run` with dagre's default DFS feedback arc set. */
    private fun runAcyclic(g: DagreGraph): List<Pair<DagreEdge, String>> {
        val fas = mutableListOf<DagreEdge>()
        val stack = HashSet<String>()
        val visited = HashSet<String>()

        fun dfs(v: String) {
            if (!visited.add(v)) return
            stack += v
            for (e in g.outEdges(v)) {
                if (e.w in stack) fas += e else dfs(e.w)
            }
            stack -= v
        }
        for (v in g.nodes.keys.toList()) dfs(v)

        val reversed = mutableListOf<Pair<DagreEdge, String>>()
        fas.forEachIndexed { index, e ->
            g.removeEdge(e.v, e.w, e.name)
            e.forwardName = e.name
            e.reversed = true
            val name = "rev$index"
            val label = DagreEdge(e.w, e.v, name)
            label.weight = e.weight
            label.minlen = e.minlen
            label.width = e.width
            label.height = e.height
            label.labelpos = e.labelpos
            label.labeloffset = e.labeloffset
            g.setEdge(e.w, e.v, label, name)
            reversed += e to name
        }
        return reversed
    }

    private fun undoAcyclic(g: DagreGraph, reversed: List<Pair<DagreEdge, String>>) {
        for ((e, name) in reversed) {
            // The route was computed for the reversed edge; hand its geometry
            // back to the original (in the original direction) before dropping
            // the reversed copy, or that edge renders with no path at all.
            val reversedEdge = g.edge(e.w, e.v, name)
            if (reversedEdge != null) e.points = reversedEdge.points
            g.removeEdge(e.w, e.v, name)
            e.reversed = true
            g.setEdge(e.v, e.w, e, e.forwardName)
        }
    }

    /** `rank/index.js`, defaulting to network simplex. */
    private fun rank(g: DagreGraph) {
        when (g.ranker) {
            "longest-path" -> longestPath(g)
            else -> networkSimplex(g)
        }
    }

    /** `injectEdgeLabelProxies`: a marker node so the label's rank is not collapsed. */
    private fun injectEdgeLabelProxies(g: DagreGraph) {
        for (e in g.edgeList()) {
            if (e.width == 0f || e.height == 0f) continue
            val vRank = g.node(e.v).rank!!
            val wRank = g.node(e.w).rank!!
            val proxy = DagreNode()
            proxy.rank = (wRank - vRank) / 2 + vRank
            proxy.dummy = "edge-proxy"
            proxy.edgeObj = e
            g.setNode("_ep${g.nodes.size}", proxy)
        }
    }

    /** `removeEdgeLabelProxies`: keep the rank, drop the node. */
    private fun removeEdgeLabelProxies(g: DagreGraph) {
        for ((name, node) in g.nodes.toList()) {
            if (node.dummy != "edge-proxy") continue
            node.edgeObj?.labelRank = node.rank
            g.removeNode(name)
        }
    }

    /**
     * `assignRankMinMax`: a cluster's rank span comes from the nesting border
     * nodes that `runNestingGraph` added, which is what later gives it a box.
     * Plain nodes need no bookkeeping.
     */
    private fun assignRankMinMax(g: DagreGraph) {
        for (node in g.nodes.values) {
            val top = node.borderTop ?: continue
            val bottom = node.borderBottom ?: continue
            node.minRank = g.node(top).rank
            node.maxRank = g.node(bottom).rank
        }
    }

    /** `normalize.run`: one dummy node per skipped rank, and one for the label. */
    private fun normalize(g: DagreGraph): List<String> {
        val chains = mutableListOf<String>()
        for (e in g.edgeList()) {
            val wRank = g.node(e.w).rank!!
            var vRank = g.node(e.v).rank!!
            if (wRank == vRank + 1) continue

            g.removeEdge(e.v, e.w, e.name)
            var previous = e.v
            var first: String? = null
            var index = 0
            vRank++
            while (vRank < wRank) {
                val dummy = DagreNode()
                dummy.rank = vRank
                dummy.dummy = "edge"
                dummy.edgeLabel = e
                dummy.edgeObj = e
                if (vRank == e.labelRank) {
                    dummy.width = e.width
                    dummy.height = e.height
                    dummy.dummy = "edge-label"
                    dummy.labelpos = e.labelpos
                }
                val name = "_d${g.nodes.size}_$index"
                g.setNode(name, dummy)
                val segment = DagreEdge(previous, name)
                segment.weight = e.weight
                g.setEdge(previous, name, segment)
                if (first == null) {
                    first = name
                    chains += name
                }
                previous = name
                vRank++
                index++
            }
            val last = DagreEdge(previous, e.w)
            last.weight = e.weight
            g.setEdge(previous, e.w, last)
            if (first != null) e.labelNode = g.node(first)
        }
        return chains
    }

    /** `normalize.undo`: fold each dummy chain back into its edge. */
    private fun undoNormalize(g: DagreGraph, chains: List<String>) {
        for (start in chains) {
            var name = start
            var node = g.node(name)
            val original = node.edgeLabel ?: continue
            val edgeObj = node.edgeObj ?: continue
            // normalize removed the edge and replaced it with its segments; the
            // original has to be put back, or the edge simply disappears.
            g.setEdge(edgeObj.v, edgeObj.w, original, edgeObj.name)
            original.points = mutableListOf()
            while (node.isDummy) {
                val next = g.successors(name).firstOrNull() ?: break
                original.points += node.x to node.y
                if (node.dummy == "edge-label") {
                    original.hasLabel = true
                    original.x = node.x
                    original.y = node.y
                    original.width = node.width
                    original.height = node.height
                }
                g.removeNode(name)
                name = next
                node = g.node(name)
            }
        }
    }

    private fun adjustCoordinateSystem(g: DagreGraph) {
        // Only TB is supported here, so nothing to swap.
    }

    private fun undoCoordinateSystem(g: DagreGraph) = adjustCoordinateSystem(g)

    /** `fixupEdgeLabelCoords`: labelpos l/r offsets; "c" needs no adjustment. */
    private fun fixupEdgeLabelCoords(g: DagreGraph) {
        for (e in g.edges.values) {
            if (!e.hasLabel) continue
            when (e.labelpos.lowercase()) {
                "l" -> e.x -= e.width / 2f + e.labeloffset
                "r" -> e.x += e.width / 2f + e.labeloffset
            }
        }
    }

    /** `translateGraph`: shift so the drawing starts at the margin. */
    private fun translateGraph(g: DagreGraph): Pair<Float, Float> {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (node in g.nodes.values) {
            minX = minOf(minX, node.x - node.width / 2f)
            maxX = maxOf(maxX, node.x + node.width / 2f)
            minY = minOf(minY, node.y - node.height / 2f)
            maxY = maxOf(maxY, node.y + node.height / 2f)
        }
        minX -= g.marginx
        minY -= g.marginy
        for (node in g.nodes.values) {
            node.x -= minX
            node.y -= minY
        }
        for (e in g.edges.values) {
            e.points = e.points.map { (x, y) -> x - minX to y - minY }.toMutableList()
            if (e.hasLabel) {
                e.x -= minX
                e.y -= minY
            }
        }
        return (maxX - minX + g.marginx) to (maxY - minY + g.marginy)
    }

    /**
     * `assignNodeIntersects`: the polyline runs between node borders, so the
     * chain dagre routed (node centres and dummy nodes) is extended with the
     * two points where it leaves and enters a node.
     */
    private fun assignNodeIntersects(g: DagreGraph) {
        for (e in g.edges.values) {
            val from = g.node(e.v)
            val to = g.node(e.w)
            if (e.points.isEmpty()) {
                e.points = mutableListOf(
                    intersectRect(from, to.x, to.y),
                    intersectRect(to, from.x, from.y),
                )
            } else {
                val first = e.points.first()
                val last = e.points.last()
                e.points.add(0, intersectRect(from, first.first, first.second))
                e.points.add(intersectRect(to, last.first, last.second))
            }
        }
    }

    /** dagre's util.intersectRect: where the line to `point` leaves the node box. */
    private fun intersectRect(node: DagreNode, px: Float, py: Float): Pair<Float, Float> {
        val dx = px - node.x
        val dy = py - node.y
        var w = node.width / 2f
        var h = node.height / 2f
        val sx: Float
        val sy: Float
        if (kotlin.math.abs(dy) * w > kotlin.math.abs(dx) * h) {
            if (dy < 0) h = -h
            sx = if (dy == 0f) 0f else h * dx / dy
            sy = h
        } else {
            if (dx < 0) w = -w
            sx = w
            sy = if (dx == 0f) 0f else w * dy / dx
        }
        return (node.x + sx) to (node.y + sy)
    }

    private fun reversePointsForReversedEdges(g: DagreGraph) {
        for (e in g.edges.values) if (e.reversed) e.points.reverse()
    }
}
