package dev.maradona.layout.dagre

/**
 * The small graph dagre's algorithms are written against.
 *
 * A direct port of dagre needs mutable node and edge labels (it annotates them
 * with ranks, orders, dummy flags, coordinates as it goes), so nodes and edges
 * are mutable classes rather than the immutable model types. Insertion order is
 * preserved, as dagre relies on it.
 */
internal class DagreNode(
    var width: Float = 0f,
    var height: Float = 0f,
) {
    var rank: Int? = null
    var order: Int? = null
    var x: Float = 0f
    var y: Float = 0f

    /** null for real nodes; "edge", "edge-label" or "border" for dagre's dummies. */
    var dummy: String? = null
    var labelpos: String? = null
    var borderType: String? = null

    // Cluster bookkeeping (compound graphs).
    var minRank: Int? = null
    var maxRank: Int? = null
    var borderTop: String? = null
    var borderBottom: String? = null
    var borderLeft: MutableMap<Int, String> = mutableMapOf()
    var borderRight: MutableMap<Int, String> = mutableMapOf()

    /** For dummy nodes standing in for an edge label. */
    var edgeLabel: DagreEdge? = null
    var edgeObj: DagreEdge? = null
    var parent: String? = null
    var low: Int = 0
    var lim: Int = 0

    val isDummy: Boolean get() = dummy != null

    fun copyLabel(): DagreNode = DagreNode(width, height).also {
        it.rank = rank
        it.order = order
        it.dummy = dummy
        it.labelpos = labelpos
        it.borderType = borderType
        it.minRank = minRank
        it.maxRank = maxRank
        it.parent = parent
    }
}

internal class DagreEdge(
    var v: String,
    var w: String,
    var name: String? = null,
) {
    var weight: Float = 1f
    var minlen: Int = 1
    var width: Float = 0f
    var height: Float = 0f
    var labelpos: String = "r"
    var labeloffset: Float = 10f
    var labelRank: Int? = null

    // acyclic.run marks reversed edges; the oracle compares the original ones.
    var reversed: Boolean = false
    var forwardName: String? = null

    var points: MutableList<Pair<Float, Float>> = mutableListOf()

    /** dagre's nesting edges, removed once the layout is done. */
    var nestingEdge: Boolean = false
    var cutvalue: Int = 0

    /** The dummy node standing in for this edge's label, if it has one. */
    var labelNode: DagreNode? = null

    /** Label coordinates, as dagre keeps them on the edge after normalize.undo. */
    var hasLabel: Boolean = false
    var x: Float = 0f
    var y: Float = 0f
}

/** (v, w, name) — dagre's edge identity in a multigraph. */
internal data class EdgeKey(val v: String, val w: String, val name: String?)

internal class DagreGraph(
    private val directed: Boolean = true,
    private val multigraph: Boolean = true,
    private val compound: Boolean = false,
) {
    val nodes = LinkedHashMap<String, DagreNode>()
    val edges = LinkedHashMap<EdgeKey, DagreEdge>()
    private val children = LinkedHashMap<String, MutableList<String>>()
    private val parents = LinkedHashMap<String, String>()

    var rankdir: String = "tb"
    var nodesep: Float = 50f
    var edgesep: Float = 20f
    var ranksep: Float = 40f
    var marginx: Float = 8f
    var marginy: Float = 8f
    var align: String? = null
    var acyclicer: String? = null
    var ranker: String? = null
    var nestingRoot: String? = null
    var nodeRankFactor: Int = 1

    /** dagre's util.addDummyNode: a uniquely named dummy node of the given kind. */
    fun addDummyNode(kind: String, label: DagreNode, prefix: String): String {
        var name: String
        var i = 0
        do {
            name = "$prefix${i++}"
        } while (hasNode(name))
        label.dummy = kind
        setNode(name, label)
        return name
    }

    fun setNode(v: String, label: DagreNode = DagreNode()): DagreNode {
        val existing = nodes[v]
        if (existing != null && existing === label) return existing
        nodes[v] = label
        if (compound) children.getOrPut(v) { mutableListOf() }
        return label
    }

    fun node(v: String): DagreNode = nodes.getValue(v)
    fun hasNode(v: String): Boolean = nodes.containsKey(v)
    fun nodeCount(): Int = nodes.size

    fun removeNode(v: String) {
        nodes.remove(v)
        edges.keys.filter { it.v == v || it.w == v }.forEach { edges.remove(it) }
        children.remove(v)
        parents.remove(v)
    }

    fun setEdge(v: String, w: String, label: DagreEdge = DagreEdge(v, w), name: String? = null): DagreEdge {
        if (!directed && v > w) return setEdge(w, v, label, name)
        label.v = v
        label.w = w
        label.name = name
        edges[EdgeKey(v, w, if (multigraph) name else null)] = label
        return label
    }

    fun edge(v: String, w: String, name: String? = null): DagreEdge? =
        edges[EdgeKey(v, w, if (multigraph) name else null)]
            ?: if (!directed) edges[EdgeKey(w, v, if (multigraph) name else null)] else null

    fun hasEdge(v: String, w: String, name: String? = null): Boolean = edge(v, w, name) != null
    fun removeEdge(v: String, w: String, name: String? = null) {
        edges.remove(EdgeKey(v, w, if (multigraph) name else null))
        if (!directed) edges.remove(EdgeKey(w, v, if (multigraph) name else null))
    }

    fun edgeList(): List<DagreEdge> = edges.values.toList()
    fun outEdges(v: String): List<DagreEdge> = edges.values.filter { it.v == v }
    fun inEdges(v: String): List<DagreEdge> = edges.values.filter { it.w == v }
    fun nodeEdges(v: String): List<DagreEdge> = edges.values.filter { it.v == v || it.w == v }

    fun successors(v: String): List<String> = outEdges(v).map { it.w }.distinct()
    fun predecessors(v: String): List<String> = if (directed) inEdges(v).map { it.v }.distinct() else successors(v)
    fun neighbors(v: String): List<String> = nodeEdges(v).map { if (it.v == v) it.w else it.v }.distinct()

    fun sources(): List<String> = nodes.keys.filter { inEdges(it).isEmpty() }
    fun sinks(): List<String> = nodes.keys.filter { outEdges(it).isEmpty() }

    // --- compound graph support ---
    fun setParent(v: String, parent: String?) {
        if (!compound) return
        val current = parents[v]
        if (current != null) children[current]?.remove(v)
        if (parent != null) {
            parents[v] = parent
            children.getOrPut(parent) { mutableListOf() } += v
        } else {
            parents.remove(v)
        }
    }

    fun parentOf(v: String): String? = parents[v]
    /** A snapshot: dagre's `g.children(v)` returns a fresh array, and callers
     * rely on that while they add nodes to the same cluster. */
    fun childrenOf(v: String): List<String> = children[v]?.toList() ?: emptyList()

    /** dagre's `asNonCompoundGraph`: only leaves, with the same edges. */
    fun asNonCompound(): DagreGraph {
        val out = DagreGraph(directed, multigraph, compound = false)
        out.rankdir = rankdir
        out.nodesep = nodesep
        out.edgesep = edgesep
        out.ranksep = ranksep
        out.marginx = marginx
        out.marginy = marginy
        out.align = align
        out.acyclicer = acyclicer
        out.ranker = ranker
        for ((v, label) in nodes) if (childrenOf(v).isEmpty()) out.setNode(v, label)
        for ((_, e) in edges) out.setEdge(e.v, e.w, e, e.name)
        return out
    }
}

/** dagre's util.buildLayerMatrix. */
internal fun DagreGraph.layerMatrix(): List<List<String>> {
    val maxRank = nodes.values.mapNotNull { it.rank }.maxOrNull() ?: return emptyList()
    if (maxRank < 0) return emptyList()
    val layering = MutableList(maxRank + 1) { mutableListOf<String>() }
    for ((v, node) in nodes) {
        val rank = node.rank ?: continue
        val order = node.order ?: 0
        val layer = layering[rank]
        while (layer.size <= order) layer.add("")
        layer[order] = v
    }
    return layering.map { layer -> layer.filter { it.isNotEmpty() } }
}

/** dagre's util.normalizeRanks. */
internal fun DagreGraph.normalizeRanks() {
    val min = nodes.values.mapNotNull { it.rank }.minOrNull() ?: return
    for (node in nodes.values) node.rank?.let { node.rank = it - min }
}

/**
 * dagre's util.removeEmptyRanks.
 *
 * Empty ranks are only compacted when they are not a multiple of
 * `nodeRankFactor`; without clusters that factor is 1, so nothing is compacted
 * (collapsing them would pull the ranks that minlen-2 edges create together and
 * halve every edge's length). With clusters the factor is the nesting
 * multiplier, so only the ranks the nesting edges reserve survive.
 */
internal fun DagreGraph.removeEmptyRanks() {
    val ranked = nodes.values.mapNotNull { it.rank }
    if (ranked.isEmpty()) return
    val offset = ranked.min()
    val layers = LinkedHashMap<Int, MutableList<String>>()
    // Cluster nodes have no rank of their own (only leaves are ranked), so they
    // take no part in layering.
    for ((v, node) in nodes) {
        val rank = node.rank ?: continue
        layers.getOrPut(rank - offset) { mutableListOf() } += v
    }
    var delta = 0
    val maxIndex = layers.keys.maxOrNull() ?: return
    for (index in 0..maxIndex) {
        val vs = layers[index]
        if (vs == null && index % nodeRankFactor != 0) {
            delta -= 1
        } else if (vs != null && delta != 0) {
            for (v in vs) nodes.getValue(v).rank = nodes.getValue(v).rank!! + delta
        }
    }
}

/** dagre's util.maxRank. */
internal fun DagreGraph.maxRank(): Int = nodes.values.mapNotNull { it.rank }.maxOrNull() ?: 0
