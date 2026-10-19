package dev.maradona.layout.dagre

/**
 * dagre's crossing minimisation for compound graphs: each layer is a small
 * compound graph whose children are the movable nodes (nested like the
 * clusters), clusters contribute their border nodes to the barycentre, and a
 * constraint graph keeps sibling clusters from interleaving.
 *
 * A port of `order/build-layer-graph.js`, `order/sort-subgraph.js`,
 * `order/resolve-conflicts.js` and `order/add-subgraph-constraints.js`.
 */

private const val LAYER_ROOT = "_layer_root"

private class CompoundEntry(val v: String, val i: Int) {
    var barycenter: Float? = null
    var weight: Float = 0f
    var vs: MutableList<String> = mutableListOf(v)
    var indegree: Int = 0
    val incoming: MutableList<CompoundEntry> = mutableListOf()
    val outgoing: MutableList<CompoundEntry> = mutableListOf()
    var merged: Boolean = false
}

/**
 * One layer to sort, as dagre builds it: the nodes in the rank, their parent
 * (the layer root or a cluster), and the edges from the neighbouring rank that
 * drive the barycentres.
 */
internal class CompoundLayerGraph(
    val g: DagreGraph,
    private val rank: Int,
    incoming: Boolean,
    private val originalParents: Map<String, String?>,
) {
    private val parents = HashMap<String, String>()
    private val constraints = HashMap<String, MutableList<Pair<String, Float>>>()

    init {
        for ((v, node) in g.nodes) {
            val inRank = node.rank == rank ||
                (node.minRank != null && node.maxRank != null &&
                    rank >= node.minRank!! && rank <= node.maxRank!!)
            if (!inRank) continue
            parents[v] = originalParents[v] ?: LAYER_ROOT
            val incident = if (incoming) g.inEdges(v) else g.outEdges(v)
            for (e in incident) {
                val u = if (e.v == v) e.w else e.v
                constraints.getOrPut(v) { mutableListOf() } += u to e.weight
            }
        }
    }

    fun childrenOf(v: String): List<String> = parents.keys.filter { parents[it] == v }

    fun constraintsFor(v: String): List<Pair<Int, Float>> =
        constraints[v].orEmpty().mapNotNull { (u, w) -> g.node(u).order?.let { it to w } }

    companion object {
        fun forRank(g: DagreGraph, rank: Int, incoming: Boolean): CompoundLayerGraph {
            val parents = g.nodes.keys.associateWith { g.parentOf(it) }
            return CompoundLayerGraph(g, rank, incoming, parents)
        }
    }
}

/** `order/index.js` for compound graphs. */
internal fun orderCompound(g: DagreGraph) {
    val max = g.maxRank()
    val down = (1..max).map { CompoundLayerGraph.forRank(g, it, incoming = true) }
    val up = (max - 1 downTo 0).map { CompoundLayerGraph.forRank(g, it, incoming = false) }

    var layering = initOrderCompound(g)
    assignOrder(g, layering)

    var bestCrossings = Int.MAX_VALUE
    var best: List<List<String>>? = null
    var i = 0
    var lastBest = 0
    while (lastBest < 4) {
        sweep(if (i % 2 == 1) down else up, biasRight = i % 4 >= 2)
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

private fun sweep(layers: List<CompoundLayerGraph>, biasRight: Boolean) {
    for (lg in layers) {
        val constraintGraph = DagreGraph(directed = true, multigraph = false)
        val sorted = sortSubgraph(lg, LAYER_ROOT, constraintGraph, biasRight)
        for ((index, v) in sorted.vs.withIndex()) if (lg.g.hasNode(v)) lg.g.node(v).order = index
        addSubgraphConstraints(lg, constraintGraph, sorted.vs)
    }
}

private fun initOrderCompound(g: DagreGraph): List<List<String>> {
    val visited = HashSet<String>()
    val maxRank = g.nodes.values.mapNotNull { it.rank }.maxOrNull() ?: 0
    val layers = MutableList(maxRank + 1) { mutableListOf<String>() }
    val simple = g.nodes.keys.filter { g.childrenOf(it).isEmpty() }

    fun dfs(v: String) {
        if (!visited.add(v)) return
        layers[g.node(v).rank!!] += v
        for (w in g.successors(v)) dfs(w)
    }
    for (v in simple.sortedBy { g.node(it).rank ?: 0 }) dfs(v)
    return layers
}

/** `sort-subgraph.js`. */
private fun sortSubgraph(
    lg: CompoundLayerGraph,
    v: String,
    cg: DagreGraph,
    biasRight: Boolean,
): CompoundEntry {
    val node = if (lg.g.hasNode(v)) lg.g.node(v) else null
    val bl = node?.borderLeft?.get(node.minRank ?: -1)
    val br = node?.borderRight?.get(node.minRank ?: -1)

    var movable = lg.childrenOf(v)
    if (bl != null) movable = movable.filter { it != bl && it != br }

    val entries = movable.mapIndexed { index, child ->
        val entry = CompoundEntry(child, index)
        val constraints = lg.constraintsFor(child)
        if (constraints.isNotEmpty()) {
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
        }
        entry
    }

    // Nested clusters are sorted first, then expanded in place.
    val subgraphs = HashMap<String, CompoundEntry>()
    for (entry in entries) {
        if (lg.childrenOf(entry.v).isNotEmpty()) {
            val result = sortSubgraph(lg, entry.v, cg, biasRight)
            subgraphs[entry.v] = result
            if (result.barycenter != null) mergeBarycenters(entry, result)
        }
    }

    val resolved = resolveConflicts(entries, cg)
    val expanded = resolved.map { entry ->
        entry.vs = entry.vs.flatMap { member -> subgraphs[member]?.vs ?: listOf(member) }.toMutableList()
        entry
    }

    val result = sortEntries(expanded, biasRight)

    if (bl != null) {
        val brValue = requireNotNull(br)
        result.vs = (listOf(bl) + result.vs + listOf(brValue)).toMutableList()
        val blPredecessors = lg.g.predecessors(bl)
        val brPredecessors = lg.g.predecessors(brValue)
        if (blPredecessors.isNotEmpty() && brPredecessors.isNotEmpty()) {
            val blOrder = lg.g.node(blPredecessors.first()).order ?: 0
            val brOrder = lg.g.node(brPredecessors.first()).order ?: 0
            val barycenter = result.barycenter ?: 0f
            result.barycenter = (barycenter * result.weight + blOrder + brOrder) / (result.weight + 2f)
            result.weight += 2f
        }
    }
    return result
}

private fun mergeBarycenters(target: CompoundEntry, other: CompoundEntry) {
    val otherBarycenter = other.barycenter ?: return
    val targetBarycenter = target.barycenter
    target.barycenter = if (targetBarycenter != null) {
        (targetBarycenter * target.weight + otherBarycenter * other.weight) / (target.weight + other.weight)
    } else {
        otherBarycenter
    }
    target.weight += other.weight
}

/** `resolve-conflicts.js`: coalesce entries that would violate a cluster constraint. */
private fun resolveConflicts(entries: List<CompoundEntry>, cg: DagreGraph): List<CompoundEntry> {
    val mapped = LinkedHashMap<String, CompoundEntry>()
    for (entry in entries) mapped[entry.v] = entry
    for (e in cg.edges.values) {
        val from = mapped[e.v] ?: continue
        val to = mapped[e.w] ?: continue
        to.indegree++
        from.outgoing += to
    }

    fun merge(target: CompoundEntry, source: CompoundEntry) {
        var sum = 0f
        var weight = 0f
        val targetBarycenter = target.barycenter
        if (target.weight != 0f && targetBarycenter != null) {
            sum += targetBarycenter * target.weight
            weight += target.weight
        }
        val sourceBarycenter = source.barycenter
        if (source.weight != 0f && sourceBarycenter != null) {
            sum += sourceBarycenter * source.weight
            weight += source.weight
        }
        target.vs = (source.vs + target.vs).toMutableList()
        if (weight != 0f) target.barycenter = sum / weight
        target.weight = weight
        source.merged = true
    }

    val sources = ArrayDeque(mapped.values.filter { it.indegree == 0 })
    val resolved = mutableListOf<CompoundEntry>()
    while (sources.isNotEmpty()) {
        val entry = sources.removeFirst()
        resolved += entry
        for (u in entry.incoming.reversed()) {
            if (u.merged) continue
            val entryBarycenter = entry.barycenter
            val uBarycenter = u.barycenter
            if (uBarycenter == null || entryBarycenter == null || uBarycenter >= entryBarycenter) {
                merge(entry, u)
            }
        }
        for (w in entry.outgoing) {
            w.incoming += entry
            w.indegree--
            if (w.indegree == 0) sources.addLast(w)
        }
    }
    return resolved.filter { !it.merged }
}

/** `sort.js`. */
private fun sortEntries(entries: List<CompoundEntry>, biasRight: Boolean): CompoundEntry {
    val sortable = entries.filter { it.barycenter != null }
    val unsortable = entries.filter { it.barycenter == null }.sortedByDescending { it.i }.toMutableList()
    val flattened = mutableListOf<String>()
    var sum = 0f
    var weight = 0f

    val comparator = Comparator<CompoundEntry> { a, b ->
        val ac = a.barycenter!!
        val bc = b.barycenter!!
        when {
            ac < bc -> -1
            ac > bc -> 1
            !biasRight -> a.i - b.i
            else -> b.i - a.i
        }
    }

    fun consumeUnsortable(start: Int): Int {
        var index = start
        while (unsortable.isNotEmpty() && unsortable.last().i <= index) {
            flattened += unsortable.removeAt(unsortable.size - 1).vs
            index++
        }
        return index
    }

    var vsIndex = consumeUnsortable(0)
    for (entry in sortable.sortedWith(comparator)) {
        vsIndex += entry.vs.size
        flattened += entry.vs
        sum += entry.barycenter!! * entry.weight
        weight += entry.weight
        vsIndex = consumeUnsortable(vsIndex)
    }

    val result = CompoundEntry("", 0)
    result.vs = flattened
    if (weight != 0f) {
        result.barycenter = sum / weight
        result.weight = weight
    }
    return result
}

/** `add-subgraph-constraints.js`. */
private fun addSubgraphConstraints(lg: CompoundLayerGraph, cg: DagreGraph, vs: List<String>) {
    val g = lg.g
    val previous = HashMap<String, String>()
    var rootPrevious: String? = null

    for (v in vs) {
        var child: String? = g.parentOf(v)
        while (child != null) {
            val parent = g.parentOf(child)
            val previousChild: String?
            if (parent != null) {
                previousChild = previous[parent]
                previous[parent] = child
            } else {
                previousChild = rootPrevious
                rootPrevious = child
            }
            if (previousChild != null && previousChild != child) {
                if (!cg.hasEdge(previousChild!!, child)) {
                    cg.setEdge(previousChild, child, DagreEdge(previousChild, child))
                }
                break
            }
            child = parent
        }
    }
}
