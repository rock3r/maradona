package dev.maradona.layout.dagre

/**
 * dagre's crossing minimisation: initial order by DFS, then alternating
 * down/up sweeps that sort each layer by barycentre, keeping the layering with
 * the fewest weighted crossings. A port of the `order` directory.
 */

private class Entry(val v: String, val i: Int) {
    var barycenter: Float? = null
    var weight: Float = 0f
    var vs: MutableList<String> = mutableListOf(v)
}

internal fun order(g: DagreGraph) {
    val max = g.maxRank()
    val downLayerGraphs = (1..max).map { LayerGraph(g, it, incoming = true) }
    val upLayerGraphs = (max - 1 downTo 0).map { LayerGraph(g, it, incoming = false) }

    var layering = initOrder(g)
    assignOrder(g, layering)

    var bestCrossings = Int.MAX_VALUE
    var best: List<List<String>>? = null
    var i = 0
    var lastBest = 0
    while (lastBest < 4) {
        sweep(if (i % 2 == 1) downLayerGraphs else upLayerGraphs, biasRight = i % 4 >= 2)
        layering = g.layerMatrix()
        val cc = crossCount(g, layering)
        if (cc < bestCrossings) {
            lastBest = 0
            best = layering
            bestCrossings = cc
        }
        i++
        lastBest++
    }
    assignOrder(g, best ?: layering)
}

private fun assignOrder(g: DagreGraph, layering: List<List<String>>) {
    for (layer in layering) for ((index, v) in layer.withIndex()) g.node(v).order = index
}

/** One layer to sort: the nodes in it, and the edges to its neighbour rank. */
private class LayerGraph(
    val g: DagreGraph,
    val rank: Int,
    val incoming: Boolean,
) {
    val movable: List<String> = g.nodes.keys.filter { g.node(it).rank == rank }

    /** Edges that constrain this layer's order, and the neighbour's order. */
    fun constraintsFor(v: String): List<Pair<Int, Float>> =
        if (incoming) {
            g.inEdges(v).mapNotNull { e -> g.node(e.v).order?.let { it to e.weight } }
        } else {
            g.outEdges(v).mapNotNull { e -> g.node(e.w).order?.let { it to e.weight } }
        }
}

private fun sweep(graphs: List<LayerGraph>, biasRight: Boolean) {
    for (lg in graphs) {
        val sorted = sortSubgraph(lg, biasRight)
        for ((index, v) in sorted.vs.withIndex()) lg.g.node(v).order = index
    }
}

private fun initOrder(g: DagreGraph): List<List<String>> {
    val visited = HashSet<String>()
    val maxRank = g.nodes.values.mapNotNull { it.rank }.maxOrNull() ?: 0
    val layers = MutableList(maxRank + 1) { mutableListOf<String>() }

    fun dfs(v: String) {
        if (!visited.add(v)) return
        layers[g.node(v).rank!!] += v
        for (w in g.successors(v)) dfs(w)
    }

    for (v in g.nodes.keys.sortedBy { g.node(it).rank ?: 0 }) dfs(v)
    return layers
}

private fun sortSubgraph(lg: LayerGraph, biasRight: Boolean): Entry {
    val entries = lg.movable.mapIndexed { index, v -> barycenter(lg, v, index) }
    // Without cluster constraints there is nothing to resolve; dagre's
    // resolveConflicts is a pass-through when the constraint graph is empty.
    return sort(entries, biasRight)
}

private fun barycenter(lg: LayerGraph, v: String, index: Int): Entry {
    val entry = Entry(v, index)
    val constraints = lg.constraintsFor(v)
    if (constraints.isEmpty()) return entry
    var sum = 0f
    var weight = 0f
    for ((order, w) in constraints) {
        sum += w * order
        weight += w
    }
    if (weight > 0f) {
        entry.barycenter = sum / weight
        entry.weight = weight
    }
    return entry
}

private fun sort(entries: List<Entry>, biasRight: Boolean): Entry {
    val sortable = entries.filter { it.barycenter != null }
    val unsortable = entries.filter { it.barycenter == null }.sortedByDescending { it.i }.toMutableList()

    val flattened = mutableListOf<String>()
    var sum = 0f
    var weight = 0f

    val comparator = Comparator<Entry> { a, b ->
        val ac = a.barycenter!!
        val bc = b.barycenter!!
        when {
            ac < bc -> -1
            ac > bc -> 1
            !biasRight -> a.i - b.i
            else -> b.i - a.i
        }
    }

    fun consumeUnsortable(startIndex: Int): Int {
        var idx = startIndex
        while (unsortable.isNotEmpty() && unsortable.last().i <= idx) {
            flattened += unsortable.removeAt(unsortable.size - 1).vs
            idx++
        }
        return idx
    }

    var vsIndex = consumeUnsortable(0)
    for (entry in sortable.sortedWith(comparator)) {
        vsIndex += entry.vs.size
        flattened += entry.vs
        sum += entry.barycenter!! * entry.weight
        weight += entry.weight
        vsIndex = consumeUnsortable(vsIndex)
    }

    val result = Entry("", 0)
    result.vs = flattened
    if (weight != 0f) {
        result.barycenter = sum / weight
        result.weight = weight
    }
    return result
}

/** `order/cross-count.js`: weighted crossings between consecutive layers. */
internal fun crossCount(g: DagreGraph, layering: List<List<String>>): Int {
    var cc = 0
    for (i in 1 until layering.size) cc += twoLayerCrossCount(g, layering[i - 1], layering[i])
    return cc
}

private fun twoLayerCrossCount(g: DagreGraph, north: List<String>, south: List<String>): Int {
    val southPos = south.withIndex().associate { (i, v) -> v to i }
    val southEntries = mutableListOf<Pair<Int, Float>>()
    for (v in north) {
        southEntries += g.outEdges(v)
            .mapNotNull { e -> southPos[e.w]?.let { pos -> pos to e.weight } }
            .sortedBy { it.first }
    }

    var firstIndex = 1
    while (firstIndex < south.size) firstIndex = firstIndex shl 1
    val treeSize = 2 * firstIndex - 1
    firstIndex -= 1
    val tree = FloatArray(treeSize)

    var cc = 0f
    for ((pos, weight) in southEntries) {
        var index = pos + firstIndex
        tree[index] += weight
        var weightSum = 0f
        while (index > 0) {
            if (index % 2 == 1) weightSum += tree[index + 1]
            index = (index - 1) shr 1
            tree[index] += weight
        }
        cc += weight * weightSum
    }
    return cc.toInt()
}
