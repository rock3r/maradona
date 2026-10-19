package dev.maradona.layout.dagre

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * dagre's coordinate assignment: y from the rank heights, x from Brandes and
 * Köpf's "Fast and Simple Horizontal Coordinate Assignment" (with dagre's
 * conflict handling and the four candidate alignments averaged).
 * A port of `position/index.js` and `position/bk.js`.
 */

internal fun position(gOriginal: DagreGraph) {
    val g = gOriginal.asNonCompound()
    positionY(g)
    for ((v, x) in positionX(g)) g.node(v).x = x
}

private fun positionY(g: DagreGraph) {
    val layering = g.layerMatrix()
    var prevY = 0f
    for (layer in layering) {
        val maxHeight = layer.maxOf { g.node(it).height }
        for (v in layer) g.node(v).y = prevY + maxHeight / 2f
        prevY += maxHeight + g.ranksep
    }
}

/** Conflicts: type 1 (non-inner segments crossing inner ones), type 2 (inner segments crossing cluster borders). */
private class Conflicts {
    private val pairs = HashSet<Long>()

    private fun key(v: Int, w: Int): Long {
        val a = min(v, w).toLong()
        val b = max(v, w).toLong()
        return (a shl 32) or b
    }

    fun add(v: Int, w: Int) = pairs.add(key(v, w))
    fun has(v: Int, w: Int): Boolean = pairs.contains(key(v, w))
}

private fun positionX(g: DagreGraph): Map<String, Float> {
    val layering = g.layerMatrix()
    val conflicts = PreferencesConflictFinder.find(g, layering)

    // Four candidate alignments: up-left, up-right, down-left, down-right.
    val candidates = mutableMapOf<String, Map<String, Float>>()
    for (vertical in listOf("u", "d")) {
        var adjusted = if (vertical == "u") layering else layering.reversed()
        for (horizontal in listOf("l", "r")) {
            if (horizontal == "r") adjusted = adjusted.map { it.reversed() }
            val neighbourFn: (String) -> List<String> =
                if (vertical == "u") { v -> g.predecessors(v) } else { v -> g.successors(v) }
            val align = verticalAlignment(g, adjusted, conflicts, neighbourFn)
            var xs = horizontalCompaction(g, adjusted, align.root, align.align, reverseSep = horizontal == "r")
            if (horizontal == "r") xs = xs.mapValues { -it.value }
            candidates["$vertical$horizontal"] = xs
        }
    }

    val smallest = candidates.values.minBy { xs ->
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for ((v, x) in xs) {
            val half = g.node(v).width / 2f
            hi = max(x + half, hi)
            lo = min(x - half, lo)
        }
        hi - lo
    }
    alignCandidates(candidates, smallest)
    return balance(candidates)
}

private fun alignCandidates(
    candidates: MutableMap<String, Map<String, Float>>,
    alignTo: Map<String, Float>,
) {
    val alignToMin = alignTo.values.min()
    val alignToMax = alignTo.values.max()
    for (vertical in listOf("u", "d")) {
        for (horizontal in listOf("l", "r")) {
            val key = "$vertical$horizontal"
            val xs = candidates[key] ?: continue
            if (xs === alignTo) continue
            val delta = if (horizontal == "l") alignToMin - xs.values.min() else alignToMax - xs.values.max()
            if (delta != 0f) candidates[key] = xs.mapValues { it.value + delta }
        }
    }
}

/** Average of the two middle candidates (dagre's `balance`). */
private fun balance(candidates: Map<String, Map<String, Float>>): Map<String, Float> {
    val out = HashMap<String, Float>()
    val reference = candidates.getValue("ul")
    for (v in reference.keys) {
        val xs = candidates.values.map { it.getValue(v) }.sorted()
        out[v] = (xs[1] + xs[2]) / 2f
    }
    return out
}

private class Alignment(val root: Map<String, String>, val align: Map<String, String>)

/**
 * Tries to align each node with one of its median neighbours, forming vertical
 * blocks. Skips neighbours that would cross an inner segment, or that are
 * already aligned to a node further right.
 */
private fun verticalAlignment(
    g: DagreGraph,
    layering: List<List<String>>,
    conflicts: Conflicts,
    neighbourFn: (String) -> List<String>,
): Alignment {
    val root = HashMap<String, String>()
    val align = HashMap<String, String>()
    val pos = HashMap<String, Int>()
    val index = HashMap<String, Int>()
    for ((i, v) in g.nodes.keys.withIndex()) index[v] = i

    for (layer in layering) for ((order, v) in layer.withIndex()) {
        root[v] = v
        align[v] = v
        pos[v] = order
    }

    fun conflict(v: String, w: String): Boolean = conflicts.has(index.getValue(v), index.getValue(w))

    for (layer in layering) {
        var prevIdx = -1
        for (v in layer) {
            val ws = neighbourFn(v).filter { pos.containsKey(it) }.sortedBy { pos.getValue(it) }
            if (ws.isEmpty()) continue
            val mp = (ws.size - 1) / 2f
            var i = kotlin.math.floor(mp.toDouble()).toInt()
            val il = kotlin.math.ceil(mp.toDouble()).toInt()
            while (i <= il) {
                val w = ws[i]
                if (align.getValue(v) == v && prevIdx < pos.getValue(w) && !conflict(v, w)) {
                    align[w] = v
                    align[v] = root.getValue(w)
                    root[v] = root.getValue(w)
                    prevIdx = pos.getValue(w)
                }
                i++
            }
        }
    }
    return Alignment(root, align)
}

/**
 * Block compaction: build a graph of blocks, place each block as far left as
 * possible, then pull blocks right to remove slack (dagre's variant of BK).
 */
private fun horizontalCompaction(
    g: DagreGraph,
    layering: List<List<String>>,
    root: Map<String, String>,
    align: Map<String, String>,
    reverseSep: Boolean,
): Map<String, Float> {
    val blockGraph = DagreGraph(directed = true, multigraph = false)
    val borderType = if (reverseSep) "borderLeft" else "borderRight"

    for (layer in layering) {
        var u: String? = null
        for (v in layer) {
            val vRoot = root.getValue(v)
            blockGraph.setNode(vRoot, DagreNode())
            if (u != null) {
                val uRoot = root.getValue(u)
                val prevMax = blockGraph.edge(uRoot, vRoot)?.weight ?: 0f
                val sep = separation(g, v, u, reverseSep)
                val w = max(sep, prevMax)
                val label = blockGraph.edge(uRoot, vRoot) ?: DagreEdge(uRoot, vRoot)
                label.weight = w
                blockGraph.setEdge(uRoot, vRoot, label)
            }
            u = v
        }
    }

    val xs = HashMap<String, Float>()

    fun iterate(nextNodes: (String) -> List<String>, fn: (String) -> Unit) {
        val visited = HashSet<String>()
        val stack = blockGraph.nodes.keys.toMutableList()
        while (stack.isNotEmpty()) {
            val elem = stack.removeAt(stack.size - 1)
            if (elem in visited) {
                fn(elem)
            } else {
                visited += elem
                stack += elem
                stack += nextNodes(elem)
            }
        }
    }

    iterate({ blockGraph.predecessors(it) }) { elem ->
        xs[elem] = blockGraph.inEdges(elem).maxOfOrNull { xs.getValue(it.v) + it.weight } ?: 0f
    }
    iterate({ blockGraph.successors(it) }) { elem ->
        val minOut = blockGraph.outEdges(elem).minOfOrNull { xs.getValue(it.w) - it.weight }
        val node = g.node(elem)
        if (minOut != null && node.borderType != borderType) {
            xs[elem] = max(xs.getValue(elem), minOut)
        }
    }

    for ((v, r) in root) xs[v] = xs.getValue(r)
    return xs
}

/** dagre's `sep()`: the minimum centre distance between two adjacent nodes. */
private fun separation(g: DagreGraph, v: String, w: String, reverseSep: Boolean): Float {
    val vLabel = g.node(v)
    val wLabel = g.node(w)
    var sum = 0f
    var delta: Float

    sum += vLabel.width / 2f
    delta = when (vLabel.labelpos?.lowercase()) {
        "l" -> -vLabel.width / 2f
        "r" -> vLabel.width / 2f
        else -> 0f
    }
    if (delta != 0f) sum += if (reverseSep) delta else -delta

    sum += (if (vLabel.isDummy) g.edgesep else g.nodesep) / 2f
    sum += (if (wLabel.isDummy) g.edgesep else g.nodesep) / 2f

    sum += wLabel.width / 2f
    delta = when (wLabel.labelpos?.lowercase()) {
        "l" -> wLabel.width / 2f
        "r" -> -wLabel.width / 2f
        else -> 0f
    }
    if (delta != 0f) sum += if (reverseSep) delta else -delta
    return sum
}

/** dagre's `findType1Conflicts` / `findType2Conflicts`, combined. */
private object PreferencesConflictFinder {
    fun find(g: DagreGraph, layering: List<List<String>>): Conflicts {
        val conflicts = Conflicts()
        val index = HashMap<String, Int>()
        for ((i, v) in g.nodes.keys.withIndex()) index[v] = i

        fun add(v: String, w: String) = conflicts.add(index.getValue(v), index.getValue(w))

        // Type 1: a non-inner segment crossing an inner one.
        for (i in 1 until layering.size) {
            val prevLayer = layering[i - 1]
            var k0 = 0
            var scanPos = 0
            val prevLayerLength = prevLayer.size
            val lastNode = layering[i].lastOrNull() ?: continue

            for ((i2, v) in layering[i].withIndex()) {
                val w = otherInnerSegmentNode(g, v)
                val k1 = if (w != null) prevLayer.indexOf(w) else prevLayerLength
                if (w != null || v == lastNode) {
                    for (scanNode in layering[i].subList(scanPos, i2 + 1)) {
                        for (u in g.predecessors(scanNode)) {
                            val uPos = prevLayer.indexOf(u)
                            if ((uPos < k0 || k1 < uPos) && !(g.node(u).isDummy && g.node(scanNode).isDummy)) {
                                add(u, scanNode)
                            }
                        }
                    }
                    scanPos = i2 + 1
                    k0 = k1
                }
            }
        }

        // Type 2 conflicts (inner segments crossing cluster borders) only
        // arise for clusters, which are handled separately.
        return conflicts
    }

    private fun otherInnerSegmentNode(g: DagreGraph, v: String): String? {
        if (!g.node(v).isDummy) return null
        return g.predecessors(v).firstOrNull { g.node(it).isDummy }
    }
}
