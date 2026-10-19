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

        /** A real node or a dummy routing node within one layer. */
        private sealed interface Item {
            data class Real(val id: String) : Item
            data class Dummy(val edgeIndex: Int, val layer: Int) : Item
        }

        fun compute(): DiagramLayout {
            val graphEdges = chart.edges.filter { it.from != it.to }
            val effective = breakCycles(graphEdges)
            val layerOf = assignLayers(effective)

            // Build layered items with dummies for edges spanning > 1 layer.
            val layers = mutableMapOf<Int, MutableList<Item>>()
            for (id in nodeIds) layers.getOrPut(layerOf.getValue(id)) { mutableListOf() } += Item.Real(id)
            // Chains per original graph edge: item keys from source to target.
            val chains = HashMap<Int, List<Item>>()
            for (eff in effective) {
                val fromLayer = layerOf.getValue(eff.from)
                val toLayer = layerOf.getValue(eff.to)
                val chain = mutableListOf<Item>(Item.Real(eff.from))
                for (layer in fromLayer + 1 until toLayer) {
                    val dummy = Item.Dummy(eff.index, layer)
                    layers.getOrPut(layer) { mutableListOf() } += dummy
                    chain += dummy
                }
                chain += Item.Real(eff.to)
                chains[eff.index] = chain
            }
            val orderedLayers: List<MutableList<Item>> = layers.keys.sorted().map { layers.getValue(it) }

            reduceCrossings(orderedLayers, chains)
            val centers = assignCoordinates(orderedLayers, layerOf, chains)

            // Node rects.
            val nodeRects = HashMap<String, DiagramRect>()
            for ((layerIdx, items) in orderedLayers.withIndex()) {
                for (item in items) {
                    if (item is Item.Real) {
                        val size = sizes.getValue(item.id)
                        val c = centers.getValue(item)
                        nodeRects[item.id] = DiagramRect(c.x - size.width / 2f, c.y - size.height / 2f, size.width, size.height)
                    }
                }
            }

            val positionedEdges = buildEdges(effective, chains, centers, nodeRects, orderedLayers)
            val subgraphRects = buildSubgraphs(nodeRects)

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

        private fun breakCycles(edges: List<dev.maradona.model.FlowEdge>): List<EffEdge> {
            val adjacency = HashMap<String, MutableList<Pair<String, Int>>>()
            // Use indices into chart.edges so chains map back to the right edge.
            val chartIndices = chart.edges.withIndex().filter { it.value.from != it.value.to }.map { it.index }
            for (idx in chartIndices) {
                val e = chart.edges[idx]
                adjacency.getOrPut(e.from) { mutableListOf() } += e.to to idx
            }
            val reversed = HashSet<Int>()
            val state = HashMap<String, Int>() // 0=unseen 1=in-stack 2=done
            fun dfs(node: String) {
                state[node] = 1
                for ((next, idx) in adjacency[node].orEmpty()) {
                    when (state[next] ?: 0) {
                        1 -> reversed += idx
                        0 -> dfs(next)
                    }
                }
                state[node] = 2
            }
            for (id in nodeIds) if ((state[id] ?: 0) == 0) dfs(id)
            return chartIndices.map { idx ->
                val e = chart.edges[idx]
                if (idx in reversed) EffEdge(e.to, e.from, idx, reversed = true)
                else EffEdge(e.from, e.to, idx, reversed = false)
            }
        }

        private fun assignLayers(effective: List<EffEdge>): Map<String, Int> {
            val layer = HashMap<String, Int>()
            nodeIds.forEach { layer[it] = 0 }
            val incoming = HashMap<String, MutableList<String>>()
            val indegree = HashMap<String, Int>()
            nodeIds.forEach { indegree[it] = 0 }
            for (e in effective) {
                incoming.getOrPut(e.to) { mutableListOf() } += e.from
                indegree[e.to] = (indegree[e.to] ?: 0) + 1
            }
            val outgoing = HashMap<String, MutableList<String>>()
            for (e in effective) outgoing.getOrPut(e.from) { mutableListOf() } += e.to
            val queue = ArrayDeque(nodeIds.filter { (indegree[it] ?: 0) == 0 })
            val pending = indegree.toMutableMap()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val preds = incoming[node].orEmpty()
                if (preds.isNotEmpty()) layer[node] = preds.maxOf { layer.getValue(it) } + 1
                for (next in outgoing[node].orEmpty()) {
                    pending[next] = (pending[next] ?: 0) - 1
                    if (pending[next] == 0) queue.addLast(next)
                }
            }
            return layer
        }

        private fun neighboursOf(chains: Map<Int, List<Item>>): Pair<Map<Item, List<Item>>, Map<Item, List<Item>>> {
            val preds = HashMap<Item, MutableList<Item>>()
            val succs = HashMap<Item, MutableList<Item>>()
            for (chain in chains.values) {
                for (i in 0 until chain.lastIndex) {
                    succs.getOrPut(chain[i]) { mutableListOf() } += chain[i + 1]
                    preds.getOrPut(chain[i + 1]) { mutableListOf() } += chain[i]
                }
            }
            return preds to succs
        }

        private fun reduceCrossings(layers: List<MutableList<Item>>, chains: Map<Int, List<Item>>) {
            val (preds, succs) = neighboursOf(chains)
            fun barycenter(item: Item, neighbours: Map<Item, List<Item>>, refLayer: List<Item>): Float {
                val ns = neighbours[item].orEmpty()
                if (ns.isEmpty()) return -1f
                val index = refLayer.withIndex().associate { it.value to it.index }
                val positions = ns.mapNotNull { index[it] }
                return if (positions.isEmpty()) -1f else positions.average().toFloat()
            }
            repeat(8) { sweep ->
                if (sweep % 2 == 0) {
                    for (li in 1 until layers.size) {
                        val ref = layers[li - 1].toList()
                        val current = layers[li]
                        val keyed = current.withIndex().map { (i, item) ->
                            val b = barycenter(item, preds, ref)
                            item to (if (b < 0f) i.toFloat() else b)
                        }
                        current.clear()
                        current += keyed.sortedBy { it.second }.map { it.first }
                    }
                } else {
                    for (li in layers.size - 2 downTo 0) {
                        val ref = layers[li + 1].toList()
                        val current = layers[li]
                        val keyed = current.withIndex().map { (i, item) ->
                            val b = barycenter(item, succs, ref)
                            item to (if (b < 0f) i.toFloat() else b)
                        }
                        current.clear()
                        current += keyed.sortedBy { it.second }.map { it.first }
                    }
                }
            }
        }

        private fun itemWidth(item: Item): Float = when (item) {
            is Item.Real -> sizes.getValue(item.id).width
            is Item.Dummy -> 0f
        }

        private fun assignCoordinates(
            layers: List<MutableList<Item>>,
            layerOf: Map<String, Int>,
            chains: Map<Int, List<Item>>,
        ): Map<Item, DiagramPoint> {
            val centerX = HashMap<Item, Float>()
            val layerWidths = layers.map { items ->
                items.sumOf { itemWidth(it).toDouble() }.toFloat() +
                    options.nodeGap * max(0, items.size - 1)
            }
            val maxWidth = layerWidths.maxOrNull() ?: 0f
            for ((li, items) in layers.withIndex()) {
                var x = (maxWidth - layerWidths[li]) / 2f
                for (item in items) {
                    val w = itemWidth(item)
                    centerX[item] = x + w / 2f
                    x += w + options.nodeGap
                }
            }
            // Alignment sweeps: pull each item to the centre of its
            // neighbours (dagre's median alignment), then restore the minimum
            // gaps. A parent with one child ends up over it, with two it sits
            // between them.
            val (preds, succs) = neighboursOf(chains)
            val labelled = labelledRanks(layerOf)

            fun align(items: List<Item>, neighbours: Map<Item, List<Item>>) {
                for (item in items) {
                    val ns = neighbours[item].orEmpty().mapNotNull { centerX[it] }
                    if (ns.isNotEmpty()) centerX[item] = ns.average().toFloat()
                }
                for (i in 1 until items.size) {
                    val min = centerX.getValue(items[i - 1]) + itemWidth(items[i - 1]) / 2f + options.nodeGap + itemWidth(items[i]) / 2f
                    if (centerX.getValue(items[i]) < min) centerX[items[i]] = min
                }
                for (i in items.size - 2 downTo 0) {
                    val maxC = centerX.getValue(items[i + 1]) - itemWidth(items[i + 1]) / 2f - options.nodeGap - itemWidth(items[i]) / 2f
                    if (centerX.getValue(items[i]) > maxC) centerX[items[i]] = maxC
                }
            }
            repeat(4) {
                for (items in layers) align(items, preds)
                for (items in layers.asReversed()) align(items, succs)
            }
            val minLeft = layers.flatten().minOfOrNull { centerX.getValue(it) - itemWidth(it) / 2f } ?: 0f
            val result = HashMap<Item, DiagramPoint>()
            for ((li, items) in layers.withIndex()) {
                val cy = layerTop(li, layers, labelled) + layerHeight(li, layers) / 2f
                for (item in items) {
                    result[item] = DiagramPoint(centerX.getValue(item) - minLeft, cy)
                }
            }
            return result
        }

        private fun layerHeight(layerIdx: Int, layers: List<List<Item>>): Float =
            layers[layerIdx].maxOfOrNull {
                if (it is Item.Real) sizes.getValue(it.id).height else 0f
            } ?: 0f

        /**
         * Ranks an edge label occupies: dagre inserts a dummy node for every
         * labelled edge, so the gap between its endpoints is two rank
         * separations rather than one. Without this, diagrams with edge labels
         * come out visibly shorter than mermaid's.
         */
        private fun labelledRanks(layerOf: Map<String, Int>): Set<Int> =
            chart.edges.filter { it.label != null }.mapNotNull { edge ->
                val from = layerOf[edge.from] ?: return@mapNotNull null
                val to = layerOf[edge.to] ?: return@mapNotNull null
                if (from == to) null else minOf(from, to)
            }.toSet()

        private fun layerTop(layerIdx: Int, layers: List<List<Item>>, labelled: Set<Int>): Float {
            var y = 0f
            for (i in 0 until layerIdx) {
                y += layerHeight(i, layers) + options.layerGap
                if (i in labelled) y += options.edgeLabelRankGap
            }
            return y
        }

        private fun buildEdges(
            effective: List<EffEdge>,
            chains: Map<Int, List<Item>>,
            centers: Map<Item, DiagramPoint>,
            nodeRects: Map<String, DiagramRect>,
            layers: List<List<Item>>,
        ): List<PositionedEdge> {
            val out = mutableListOf<PositionedEdge>()
            val effByIndex = effective.associateBy { it.index }
            for ((idx, edge) in chart.edges.withIndex()) {
                if (edge.from == edge.to) {
                    nodeRects[edge.from]?.let { r -> out += selfLoop(edge, r) }
                    continue
                }
                val eff = effByIndex[idx] ?: continue
                val chain = chains[idx] ?: continue
                val fromRect = nodeRects.getValue(eff.from)
                val toRect = nodeRects.getValue(eff.to)
                val waypoints = mutableListOf(DiagramPoint(fromRect.centerX, fromRect.bottom))
                for (item in chain.subList(1, chain.lastIndex)) {
                    waypoints += centers.getValue(item)
                }
                waypoints += DiagramPoint(toRect.centerX, toRect.y)
                var points = orthogonalize(waypoints)
                if (eff.reversed) points = points.reversed()
                val labelSize = edgeLabelSize(edge)
                val labelRect = labelSize?.let { size ->
                    val mid = points[points.size / 2]
                    DiagramRect(mid.x - size.width / 2f, mid.y - size.height / 2f, size.width, size.height)
                }
                out += PositionedEdge(edge, points, labelRect)
            }
            return out
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

        private fun buildSubgraphs(nodeRects: Map<String, DiagramRect>): List<PositionedSubgraph> {
            if (chart.subgraphs.isEmpty()) return emptyList()
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
