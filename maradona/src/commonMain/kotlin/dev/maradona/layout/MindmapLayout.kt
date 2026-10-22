package dev.maradona.layout

import dev.maradona.model.MindmapDiagram
import dev.maradona.model.MindmapNode
import dev.maradona.model.MindmapShape
import dev.maradona.model.NodeShape
import kotlin.math.max
import kotlin.math.min

data class PositionedMindmapNode(
    val node: MindmapNode,
    val rect: DiagramRect,
    val depth: Int,
    val branchIndex: Int,
)

data class PositionedMindmapEdge(
    val fromId: String,
    val toId: String,
    val branchIndex: Int,
    val depth: Int,
    val points: List<DiagramPoint>,
)

data class MindmapDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val nodes: List<PositionedMindmapNode>,
    val edges: List<PositionedMindmapEdge>,
) {
    fun node(idOrLabel: String): PositionedMindmapNode? =
        nodes.firstOrNull { it.node.id == idOrLabel } ?: nodes.firstOrNull { it.node.label == idOrLabel }
}

/**
 * Iterative bidirectional tidy-tree layout for Mermaid `mindmap` diagrams.
 *
 * The root sits at the center; top-level branches are balanced across the right and
 * left sides of the root, and each subtree is sized bottom-up and placed top-down
 * in O(N) time and O(1) call-stack depth without sibling or cousin overlaps.
 */
object MindmapLayout {

    fun layout(
        diagram: MindmapDiagram,
        nodeSize: (MindmapNode) -> DiagramSize = { n -> defaultNodeSize(n, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(nodeGap = 18f, layerGap = 54f),
    ): MindmapDiagramLayout {
        if (diagram.nodes.isEmpty()) {
            return MindmapDiagramLayout(
                width = 0f,
                height = 0f,
                titleRect = null,
                nodes = emptyList(),
                edges = emptyList(),
            )
        }

        val vGap = max(12f * options.density, options.nodeGap)
        val hGap = max(36f * options.density, options.layerGap)

        val nodesById = diagram.nodes.associateBy { it.id }
        val childrenByParent = diagram.nodes.groupBy { it.parentId }
        val rootCandidates = childrenByParent[null].orEmpty().ifEmpty { listOf(diagram.nodes.first()) }
        val root = rootCandidates.first()

        // Iterative pre-order traversal to establish depths and avoid recursion on deep trees.
        val preOrder = ArrayList<MindmapNode>(diagram.nodes.size)
        val depthById = HashMap<String, Int>()
        val visited = HashSet<String>()
        val stack = ArrayDeque<Pair<MindmapNode, Int>>()
        for (r in rootCandidates.asReversed()) {
            stack.addLast(r to 0)
        }
        while (stack.isNotEmpty()) {
            val (cur, d) = stack.removeLast()
            if (!visited.add(cur.id)) continue
            preOrder.add(cur)
            depthById[cur.id] = d
            val kids = childrenByParent[cur.id].orEmpty()
            for (k in kids.asReversed()) {
                if (k.id !in visited) stack.addLast(k to (d + 1))
            }
        }
        // Include any disconnected nodes if present.
        for (n in diagram.nodes) {
            if (visited.add(n.id)) {
                preOrder.add(n)
                depthById[n.id] = 1
            }
        }

        val boxSizes = HashMap<String, DiagramSize>(preOrder.size)
        for (n in preOrder) {
            val raw = nodeSize(n)
            val sz = if (n.shape == MindmapShape.Circle) {
                val d = max(max(raw.width, raw.height), kotlin.math.hypot(raw.width * 0.78f, raw.height * 0.78f) + 16f * options.density)
                DiagramSize(d, d)
            } else {
                raw
            }
            boxSizes[n.id] = sz
        }

        // Bottom-up subtree height computation in reverse pre-order.
        val subtreeHeight = HashMap<String, Float>(preOrder.size)
        for (n in preOrder.asReversed()) {
            val sz = boxSizes.getValue(n.id)
            val kids = childrenByParent[n.id].orEmpty()
            if (kids.isEmpty()) {
                subtreeHeight[n.id] = sz.height
            } else {
                val kidsSpan = kids.sumOf { (subtreeHeight[it.id] ?: 0f).toDouble() }.toFloat() +
                    (kids.size - 1) * vGap
                subtreeHeight[n.id] = max(sz.height, kidsSpan)
            }
        }

        // Partition root's direct children into right and left branches balanced by subtree height.
        val topBranches = (childrenByParent[root.id].orEmpty() + rootCandidates.drop(1))
        val rightTop = mutableListOf<MindmapNode>()
        val leftTop = mutableListOf<MindmapNode>()
        var rightSum = 0f
        var leftSum = 0f
        val branchIndexById = HashMap<String, Int>(preOrder.size)
        branchIndexById[root.id] = -1
        for ((bIdx, child) in topBranches.withIndex()) {
            branchIndexById[child.id] = bIdx
            val h = subtreeHeight[child.id] ?: 0f
            if (bIdx == 0 || rightSum <= leftSum) {
                rightTop += child
                rightSum += h + vGap
            } else {
                leftTop += child
                leftSum += h + vGap
            }
        }
        // Propagate branchIndex and side (+1 = right, -1 = left) down the tree in pre-order.
        val sideById = HashMap<String, Int>(preOrder.size)
        sideById[root.id] = 0
        for (child in rightTop) sideById[child.id] = 1
        for (child in leftTop) sideById[child.id] = -1
        for (n in preOrder) {
            val pId = n.parentId ?: continue
            if (n.id !in branchIndexById) {
                branchIndexById[n.id] = branchIndexById[pId] ?: 0
            }
            if (n.id !in sideById) {
                sideById[n.id] = sideById[pId]?.takeIf { it != 0 } ?: 1
            }
        }

        val rectsById = HashMap<String, DiagramRect>(preOrder.size)
        val bandTopById = HashMap<String, Float>(preOrder.size)

        val rootSz = boxSizes.getValue(root.id)
        rectsById[root.id] = DiagramRect(-rootSz.width / 2f, -rootSz.height / 2f, rootSz.width, rootSz.height)

        fun placeSideTopBranches(branches: List<MindmapNode>, side: Int) {
            if (branches.isEmpty()) return
            val totalSpan = branches.sumOf { (subtreeHeight[it.id] ?: 0f).toDouble() }.toFloat() +
                (branches.size - 1) * vGap
            var curBandTop = -totalSpan / 2f
            val rootRect = rectsById.getValue(root.id)
            for (b in branches) {
                val bH = subtreeHeight.getValue(b.id)
                val bSz = boxSizes.getValue(b.id)
                bandTopById[b.id] = curBandTop
                val nodeY = curBandTop + (bH - bSz.height) / 2f
                val nodeX = if (side >= 0) {
                    rootRect.right + hGap
                } else {
                    rootRect.x - hGap - bSz.width
                }
                rectsById[b.id] = DiagramRect(nodeX, nodeY, bSz.width, bSz.height)
                curBandTop += bH + vGap
            }
        }

        placeSideTopBranches(rightTop, side = 1)
        placeSideTopBranches(leftTop, side = -1)

        // Iterative top-down placement for all deeper descendants.
        for (parent in preOrder) {
            if (parent.id == root.id) continue
            val kids = childrenByParent[parent.id].orEmpty()
            if (kids.isEmpty()) continue
            val pRect = rectsById[parent.id] ?: continue
            val side = sideById[parent.id] ?: 1
            val kidsSpan = kids.sumOf { (subtreeHeight[it.id] ?: 0f).toDouble() }.toFloat() +
                (kids.size - 1) * vGap
            var curBandTop = pRect.centerY - kidsSpan / 2f
            for (k in kids) {
                val kH = subtreeHeight.getValue(k.id)
                val kSz = boxSizes.getValue(k.id)
                bandTopById[k.id] = curBandTop
                val nodeY = curBandTop + (kH - kSz.height) / 2f
                val nodeX = if (side >= 0) {
                    pRect.right + hGap
                } else {
                    pRect.x - hGap - kSz.width
                }
                rectsById[k.id] = DiagramRect(nodeX, nodeY, kSz.width, kSz.height)
                curBandTop += kH + vGap
            }
        }

        fun outlineShape(shape: MindmapShape): NodeShape = when (shape) {
            MindmapShape.Circle, MindmapShape.Bang -> NodeShape.Circle
            MindmapShape.Hexagon -> NodeShape.Hexagon
            MindmapShape.Square -> NodeShape.Rect
            MindmapShape.Default, MindmapShape.Rounded, MindmapShape.Cloud -> NodeShape.RoundedRect
        }

        val rawEdges = mutableListOf<PositionedMindmapEdge>()
        for (n in preOrder) {
            val pId = n.parentId ?: continue
            val pNode = nodesById[pId] ?: continue
            val pRect = rectsById[pId] ?: continue
            val cRect = rectsById[n.id] ?: continue
            val side = sideById[n.id] ?: 1
            val pStart = NodeOutline.intersect(
                outlineShape(pNode.shape),
                pRect,
                DiagramPoint(if (side >= 0) pRect.right + 100f else pRect.x - 100f, pRect.centerY),
                options.density,
            )
            val pEnd = NodeOutline.intersect(
                outlineShape(n.shape),
                cRect,
                DiagramPoint(if (side >= 0) cRect.x - 100f else cRect.right + 100f, cRect.centerY),
                options.density,
            )
            val midX = (pStart.x + pEnd.x) / 2f
            val pts = listOf(
                pStart,
                DiagramPoint(midX, pStart.y),
                DiagramPoint(midX, pEnd.y),
                pEnd,
            )
            rawEdges += PositionedMindmapEdge(
                fromId = pId,
                toId = n.id,
                branchIndex = branchIndexById[n.id] ?: 0,
                depth = depthById[n.id] ?: 1,
                points = pts,
            )
        }

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (r in rectsById.values) {
            minX = min(minX, r.x)
            minY = min(minY, r.y)
            maxX = max(maxX, r.right)
            maxY = max(maxY, r.bottom)
        }
        if (!minX.isFinite()) {
            minX = 0f
            minY = 0f
            maxX = 100f
            maxY = 100f
        }

        val titleGap = if (diagram.title != null && titleSize != null) {
            titleSize.height + 16f * options.density
        } else 0f
        val contentW = max(maxX - minX, titleSize?.width ?: 0f)
        val dx = options.padding - minX + max(0f, (contentW - (maxX - minX)) / 2f)
        val dy = options.padding + titleGap - minY
        fun DiagramRect.shifted() = copy(x = x + dx, y = y + dy)
        fun DiagramPoint.shifted() = DiagramPoint(x + dx, y + dy)

        val finalWidth = contentW + options.padding * 2f
        val finalHeight = (maxY - minY) + titleGap + options.padding * 2f
        val titleRect = if (diagram.title != null && titleSize != null) {
            DiagramRect(
                x = (finalWidth - titleSize.width) / 2f,
                y = options.padding,
                width = titleSize.width,
                height = titleSize.height,
            )
        } else null

        val positionedNodes = preOrder.mapNotNull { n ->
            val r = rectsById[n.id]?.shifted() ?: return@mapNotNull null
            PositionedMindmapNode(
                node = n,
                rect = r,
                depth = depthById[n.id] ?: 0,
                branchIndex = branchIndexById[n.id] ?: -1,
            )
        }
        val positionedEdges = rawEdges.map { e ->
            e.copy(points = e.points.map { it.shifted() })
        }

        return MindmapDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            titleRect = titleRect,
            nodes = positionedNodes,
            edges = positionedEdges,
        )
    }

    internal fun defaultNodeSize(node: MindmapNode, density: Float): DiagramSize {
        val lines = node.label.replace("**", "").replace("*", "").lines()
        val maxLen = lines.maxOfOrNull { it.length } ?: 4
        val padX = when (node.shape) {
            MindmapShape.Circle, MindmapShape.Bang -> 20f * density
            MindmapShape.Hexagon, MindmapShape.Cloud -> 22f * density
            else -> 16f * density
        }
        val padY = 10f * density
        val iconExtraW = if (!node.icon.isNullOrBlank()) 18f * density else 0f
        val textW = maxLen * 7.8f * density + iconExtraW
        val textH = lines.size * 18f * density
        val w = max(68f * density, textW + padX * 2f)
        val h = max(32f * density, textH + padY * 2f)
        return when (node.shape) {
            MindmapShape.Circle -> {
                val d = max(max(w, h), kotlin.math.hypot(textW, textH) + padY * 2f)
                DiagramSize(d, d)
            }
            MindmapShape.Bang, MindmapShape.Cloud -> DiagramSize(w * 1.22f, h * 1.25f)
            else -> DiagramSize(w, h)
        }
    }
}
