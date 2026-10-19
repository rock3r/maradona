package dev.maradona.layout.dagre

/**
 * dagre's ranking: longest path as the initial solution, then a feasible tight
 * tree, then network simplex to remove negative cut values. A direct port of
 * `rank/util.js` and `rank/feasible-tree.js` and `rank/network-simplex.js`.
 */

internal fun slack(g: DagreGraph, e: DagreEdge): Int =
    g.node(e.w).rank!! - g.node(e.v).rank!! - e.minlen

/** `rank/util.js: longestPath`. */
internal fun longestPath(g: DagreGraph) {
    val visited = HashSet<String>()

    fun dfs(v: String): Int {
        val label = g.node(v)
        if (v in visited) return label.rank!!
        visited += v
        var rank = g.outEdges(v).map { dfs(it.w) - it.minlen }.minOrNull() ?: 0
        if (g.outEdges(v).isEmpty()) rank = 0
        label.rank = rank
        return rank
    }

    for (v in g.sources()) dfs(v)
}

/** `util.simplify`: collapse multi-edges into one weighted edge. */
internal fun simplify(g: DagreGraph): DagreGraph {
    val out = DagreGraph(multigraph = false)
    out.rankdir = g.rankdir
    out.nodesep = g.nodesep
    out.edgesep = g.edgesep
    out.ranksep = g.ranksep
    out.marginx = g.marginx
    out.marginy = g.marginy
    out.align = g.align
    out.ranker = g.ranker
    out.acyclicer = g.acyclicer
    for ((v, label) in g.nodes) out.setNode(v, label)
    for (e in g.edgeList()) {
        val existing = out.edge(e.v, e.w)
        if (existing == null) {
            val copy = DagreEdge(e.v, e.w).also {
                it.weight = e.weight
                it.minlen = e.minlen
                it.width = e.width
                it.height = e.height
                it.labelpos = e.labelpos
                it.labeloffset = e.labeloffset
                it.labelRank = e.labelRank
            }
            out.setEdge(e.v, e.w, copy)
        } else {
            existing.weight += e.weight
            existing.minlen = maxOf(existing.minlen, e.minlen)
        }
    }
    return out
}

/** `rank/feasible-tree.js`. Returns the tight tree (undirected). */
internal fun feasibleTree(g: DagreGraph): DagreGraph {
    val t = DagreGraph(directed = false, multigraph = false)
    val start = g.nodes.keys.first()
    val size = g.nodeCount()
    t.setNode(start, DagreNode())

    fun tightTree(): Int {
        fun dfs(v: String) {
            for (e in g.nodeEdges(v)) {
                val w = if (v == e.v) e.w else e.v
                if (!t.hasNode(w) && slack(g, e) == 0) {
                    t.setNode(w, DagreNode())
                    t.setEdge(v, w, DagreEdge(v, w))
                    dfs(w)
                }
            }
        }
        for (v in t.nodes.keys.toList()) dfs(v)
        return t.nodeCount()
    }

    while (tightTree() < size) {
        val candidates = g.edgeList().filter { t.hasNode(it.v) != t.hasNode(it.w) }
        val edge = candidates.minByOrNull { slack(g, it) } ?: break
        val delta = if (t.hasNode(edge.v)) slack(g, edge) else -slack(g, edge)
        for (v in t.nodes.keys) g.node(v).rank = g.node(v).rank!! + delta
    }
    return t
}

/** `rank/network-simplex.js`. */
internal fun networkSimplex(gOriginal: DagreGraph) {
    val g = simplify(gOriginal)
    longestPath(g)
    val t = feasibleTree(g)
    initLowLimValues(t)
    initCutValues(t, g)

    while (true) {
        val e = leaveEdge(t) ?: break
        val f = enterEdge(t, g, e) ?: break
        exchangeEdges(t, g, e, f)
    }
    // Copy the ranks back: simplify() worked on copies of the labels only when
    // we created new edges, but nodes are shared, so ranks are already visible
    // on `gOriginal`. Nothing else to copy.
}

private fun isTreeEdge(tree: DagreGraph, u: String, v: String): Boolean = tree.hasEdge(u, v)

private fun isDescendant(vLow: Int, vLim: Int, rootLow: Int, rootLim: Int): Boolean =
    rootLow <= vLim && vLim <= rootLim

private fun DagreGraph.postorder(starts: List<String>): List<String> {
    val visited = HashSet<String>()
    val out = mutableListOf<String>()
    fun dfs(v: String) {
        if (!visited.add(v)) return
        for (w in neighbors(v)) dfs(w)
        out += v
    }
    for (v in starts) dfs(v)
    return out
}

private fun DagreGraph.preorder(start: String): List<String> {
    val visited = HashSet<String>()
    val out = mutableListOf<String>()
    fun dfs(v: String) {
        if (!visited.add(v)) return
        out += v
        for (w in neighbors(v)) dfs(w)
    }
    dfs(start)
    return out
}

private fun initLowLimValues(tree: DagreGraph, root: String? = null) {
    val visited = HashSet<String>()
    var counter = 1

    fun dfs(v: String, parent: String?): Int {
        val low = counter
        visited += v
        for (w in tree.neighbors(v)) {
            if (w !in visited) counter = dfs(w, v)
        }
        val label = tree.node(v)
        label.low = low
        label.lim = counter++
        label.parent = parent
        return counter
    }

    dfs(root ?: tree.nodes.keys.first(), null)
}

private fun initCutValues(t: DagreGraph, g: DagreGraph) {
    val vs = t.postorder(t.nodes.keys.toList()).dropLast(1)
    for (v in vs) {
        val childLab = t.node(v)
        val parent = childLab.parent
        val treeEdge = t.edge(v, parent ?: continue) ?: continue
        treeEdge.cutvalue = calcCutValue(t, g, v)
    }
}

private fun calcCutValue(t: DagreGraph, g: DagreGraph, child: String): Int {
    val childLab = t.node(child)
    val parent = childLab.parent ?: return 0
    var childIsTail = true
    var graphEdge = g.edge(child, parent)
    if (graphEdge == null) {
        childIsTail = false
        graphEdge = g.edge(parent, child) ?: return 0
    }
    var cutValue = graphEdge.weight.toInt()
    for (e in g.nodeEdges(child)) {
        val isOutEdge = e.v == child
        val other = if (isOutEdge) e.w else e.v
        if (other != parent) {
            val pointsToHead = isOutEdge == childIsTail
            val otherWeight = g.edge(e.v, e.w, e.name)!!.weight.toInt()
            cutValue += if (pointsToHead) otherWeight else -otherWeight
            if (isTreeEdge(t, child, other)) {
                val otherCut = t.edge(child, other)?.cutvalue ?: 0
                cutValue += if (pointsToHead) -otherCut else otherCut
            }
        }
    }
    return cutValue
}

private fun leaveEdge(tree: DagreGraph): DagreEdge? = tree.edgeList().firstOrNull { it.cutvalue < 0 }

private fun enterEdge(t: DagreGraph, g: DagreGraph, edge: DagreEdge): DagreEdge? {
    var v = edge.v
    var w = edge.w
    if (!g.hasEdge(v, w)) {
        v = edge.w
        w = edge.v
    }
    val vLabel = t.node(v)
    val wLabel = t.node(w)
    val flip = vLabel.lim > wLabel.lim
    val tail = if (flip) wLabel else vLabel

    val candidates = g.edgeList().filter { candidate ->
        val vIsDesc = isDescendant(t.node(candidate.v).low, t.node(candidate.v).lim, tail.low, tail.lim)
        val wIsDesc = isDescendant(t.node(candidate.w).low, t.node(candidate.w).lim, tail.low, tail.lim)
        flip == vIsDesc && flip != wIsDesc
    }
    return candidates.minByOrNull { slack(g, it) }
}

private fun exchangeEdges(t: DagreGraph, g: DagreGraph, e: DagreEdge, f: DagreEdge) {
    t.removeEdge(e.v, e.w)
    t.setEdge(f.v, f.w, DagreEdge(f.v, f.w))
    initLowLimValues(t)
    initCutValues(t, g)
    updateRanks(t, g)
}

private fun updateRanks(t: DagreGraph, g: DagreGraph) {
    val root = t.nodes.keys.firstOrNull { g.node(it).parent == null } ?: return
    val vs = t.preorder(root).drop(1)
    for (v in vs) {
        val parent = t.node(v).parent ?: continue
        var edge = g.edge(v, parent)
        var flipped = false
        if (edge == null) {
            edge = g.edge(parent, v) ?: continue
            flipped = true
        }
        g.node(v).rank = g.node(parent).rank!! + if (flipped) edge.minlen else -edge.minlen
    }
}
