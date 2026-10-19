package dev.maradona.layout.dagre

/**
 * dagre's compound-graph support: the nesting graph that reserves a cluster's
 * vertical extent, the border nodes that bound it horizontally, and the
 * extraction of the cluster's box once everything is positioned.
 * A port of `nesting-graph.js`, `add-border-segments.js` and the border parts of
 * `layout.js`.
 */

/** `nesting-graph.js: run`. */
internal fun DagreGraph.runNestingGraph() {
    val root = addDummyNode("root", DagreNode(), "_root")
    val depths = treeDepths()
    val height = (depths.values.maxOrNull() ?: 1) - 1
    val nodeSep = 2 * height + 1
    nestingRoot = root

    for (e in edges.values) e.minlen *= nodeSep
    val weight = edges.values.sumOf { it.weight.toDouble() }.toFloat() + 1f

    for (child in topLevelChildren()) dfsNesting(root, nodeSep, weight, height, depths, child)
    nodeRankFactor = nodeSep
}

/** The nodes that have no parent: the top-level items, clusters included. */
private fun DagreGraph.topLevelChildren(): List<String> = nodes.keys.filter { parentOf(it) == null }

private fun DagreGraph.treeDepths(): Map<String, Int> {
    val depths = HashMap<String, Int>()
    fun dfs(v: String, depth: Int) {
        val children = childrenOf(v)
        if (children.isNotEmpty()) for (child in children) dfs(child, depth + 1)
        depths[v] = depth
    }
    for (v in topLevelChildren()) dfs(v, 1)
    return depths
}

private fun DagreGraph.dfsNesting(
    root: String,
    nodeSep: Int,
    weight: Float,
    height: Int,
    depths: Map<String, Int>,
    v: String,
) {
    val children = childrenOf(v)
    if (children.isEmpty()) {
        if (v != root) {
            val e = DagreEdge(root, v)
            e.weight = 0f
            e.minlen = nodeSep
            e.nestingEdge = true
            setEdge(root, v, e)
        }
        return
    }

    val top = addDummyNode("border", DagreNode(), "_bt")
    val bottom = addDummyNode("border", DagreNode(), "_bb")
    val label = node(v)
    setParent(top, v)
    label.borderTop = top
    setParent(bottom, v)
    label.borderBottom = bottom

    for (child in children) {
        dfsNesting(root, nodeSep, weight, height, depths, child)
        val childNode = node(child)
        val childTop = childNode.borderTop ?: child
        val childBottom = childNode.borderBottom ?: child
        val thisWeight = if (childNode.borderTop != null) weight else 2f * weight
        val minlen = if (childTop != childBottom) 1 else height - (depths[v] ?: 1) + 1

        val topEdge = DagreEdge(top, childTop)
        topEdge.weight = thisWeight
        topEdge.minlen = minlen
        topEdge.nestingEdge = true
        setEdge(top, childTop, topEdge)

        val bottomEdge = DagreEdge(childBottom, bottom)
        bottomEdge.weight = thisWeight
        bottomEdge.minlen = minlen
        bottomEdge.nestingEdge = true
        setEdge(childBottom, bottom, bottomEdge)
    }

    if (parentOf(v) == null) {
        val e = DagreEdge(root, top)
        e.weight = 0f
        e.minlen = height + (depths[v] ?: 1)
        e.nestingEdge = true
        setEdge(root, top, e)
    }
}

/** `nesting-graph.js: cleanup`. */
internal fun DagreGraph.cleanupNestingGraph() {
    val root = nestingRoot ?: return
    removeNode(root)
    nestingRoot = null
    for (key in edges.keys.filter { edges.getValue(it).nestingEdge }) edges.remove(key)
}

/** `add-border-segments.js`. */
internal fun DagreGraph.addBorderSegments() {
    fun dfs(v: String) {
        val children = childrenOf(v)
        for (child in children) dfs(child)
        val node = node(v)
        val minRank = node.minRank ?: return
        val maxRank = node.maxRank ?: return
        node.borderLeft.clear()
        node.borderRight.clear()
        for (rank in minRank..maxRank) {
            addBorderNode("borderLeft", "_bl", v, node, rank)
            addBorderNode("borderRight", "_br", v, node, rank)
        }
    }
    for (v in nodes.keys.filter { childrenOf(it).isNotEmpty() }.toList()) dfs(v)
}

private fun DagreGraph.addBorderNode(prop: String, prefix: String, sg: String, sgNode: DagreNode, rank: Int) {
    val label = DagreNode(0f, 0f)
    label.rank = rank
    label.borderType = prop
    val map = if (prop == "borderLeft") sgNode.borderLeft else sgNode.borderRight
    val prev = map[rank - 1]
    val curr = addDummyNode("border", label, prefix)
    map[rank] = curr
    setParent(curr, sg)
    if (prev != null) setEdge(prev, curr, DagreEdge(prev, curr).also { it.weight = 1f })
}

/** `parent-dummy-chains`: give every edge dummy the parent of the node it follows. */
internal fun DagreGraph.parentDummyChains(dummyChains: List<String>) {
    if (dummyChains.isEmpty()) return
    for (start in dummyChains) {
        var v = start
        var node = node(v)
        val edgeObj = node.edgeObj ?: continue
        // Ascend from the edge's source to its target, adopting parents.
        var previousParent = parentOf(edgeObj.v)
        while (true) {
            val current = node
            val parent = parentOf(current.edgeObj?.v ?: edgeObj.v)
            if (current.parent == null) setParent(v, parent ?: previousParent)
            previousParent = parentOf(v) ?: previousParent
            if (current.rank != null && current.rank == node(edgeObj.w).rank) break
            val next = successors(v).firstOrNull() ?: break
            v = next
            node = node(v)
            if (node.dummy == null) break
        }
    }
}

/** `removeBorderNodes`: turn the border nodes into each cluster's box. */
internal fun DagreGraph.removeBorderNodes() {
    for (v in nodes.keys.toList()) {
        if (childrenOf(v).isEmpty()) continue
        val node = node(v)
        val top = node.borderTop ?: continue
        val bottom = node.borderBottom ?: continue
        val left = node.borderLeft.toSortedMap().values.lastOrNull() ?: continue
        val right = node.borderRight.toSortedMap().values.lastOrNull() ?: continue
        val l = node(left)
        val r = node(right)
        val t = node(top)
        val b = node(bottom)
        node.width = kotlin.math.abs(r.x - l.x)
        node.height = kotlin.math.abs(b.y - t.y)
        node.x = l.x + node.width / 2f
        node.y = t.y + node.height / 2f
    }
    for (v in nodes.keys.toList()) if (node(v).dummy == "border") removeNode(v)
}
