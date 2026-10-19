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

/**
 * `parent-dummy-chains`: re-parent each edge dummy to the innermost cluster that
 * both of its edge's endpoints live in, by walking their lowest common ancestor.
 * Without it, cross-cluster edges' dummies stay parented to nothing and the
 * ordering constraints that keep a cluster's members together are lost.
 */
internal fun DagreGraph.parentDummyChains(dummyChains: List<String>) {
    if (dummyChains.isEmpty() || !hasAnyCluster()) return
    val postorder = postorderNumbers()
    for (start in dummyChains) {
        val first = node(start)
        val edgeObj = first.edgeObj ?: continue
        val (path, lca) = findPath(postorder, edgeObj.v, edgeObj.w)
        var pathIndex = 0
        var ascending = true
        var current = start
        while (current != edgeObj.w) {
            val current_node = node(current)
            val rank = current_node.rank ?: break
            if (ascending) {
                while (pathIndex < path.size) {
                    val candidate = path[pathIndex]
                    if (candidate == lca) break
                    val candidateMax = candidate?.let { node(it).maxRank } ?: 0
                    if (candidateMax < rank) pathIndex++ else break
                }
                if (pathIndex < path.size && path[pathIndex] == lca && lca == null) ascending = false
                if (pathIndex < path.size && path.getOrNull(pathIndex) == lca && lca == null) ascending = false
                if (lca == null) ascending = false
            }
            if (!ascending) {
                while (pathIndex < path.size - 1) {
                    val next = path[pathIndex + 1]
                    val nextMin = next?.let { node(it).minRank ?: 0 } ?: 0
                    if (nextMin <= rank) pathIndex++ else break
                }
            }
            val parent = path.getOrNull(pathIndex)
            if (parent != null) setParent(current, parent)
            current = successors(current).firstOrNull() ?: break
            if (!node(current).isDummy) break
        }
    }
}

private fun DagreGraph.hasAnyCluster(): Boolean = nodes.keys.any { childrenOf(it).isNotEmpty() }

/** `parent-dummy-chains.js: postorder`. */
private fun DagreGraph.postorderNumbers(): Map<String, Pair<Int, Int>> {
    val result = HashMap<String, Pair<Int, Int>>()
    var limit = 0
    fun dfs(v: String) {
        val low = limit
        for (child in childrenOf(v)) dfs(child)
        result[v] = low to limit++
    }
    for (v in nodes.keys.filter { parentOf(it) == null }) dfs(v)
    return result
}

/** `parent-dummy-chains.js: findPath`. */
private fun DagreGraph.findPath(
    postorder: Map<String, Pair<Int, Int>>,
    v: String,
    w: String,
): Pair<List<String?>, String?> {
    val vPath = mutableListOf<String?>()
    val wPath = mutableListOf<String?>()
    val low = minOf(postorder[v]?.first ?: 0, postorder[w]?.first ?: 0)
    val lim = maxOf(postorder[v]?.second ?: 0, postorder[w]?.second ?: 0)

    var parent: String? = v
    do {
        parent = parent?.let { parentOf(it) }
        vPath += parent
    } while (parent != null && ((postorder[parent]?.first ?: 0) > low || lim > (postorder[parent]?.second ?: 0)))
    val lca = parent

    parent = w
    while (true) {
        parent = parent?.let { parentOf(it) }
        if (parent == lca) break
        if (parent == null) break
        wPath += parent
    }
    return (vPath + wPath.reversed()) to lca
}

/** `removeBorderNodes`: turn the border nodes into each cluster's box. */
internal fun DagreGraph.removeBorderNodes() {
    for (v in nodes.keys.toList()) {
        if (childrenOf(v).isEmpty()) continue
        val node = node(v)
        val top = node.borderTop ?: continue
        val bottom = node.borderBottom ?: continue
        val left = node.borderLeft.keys.maxOrNull()?.let { node.borderLeft[it] } ?: continue
        val right = node.borderRight.keys.maxOrNull()?.let { node.borderRight[it] } ?: continue
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
