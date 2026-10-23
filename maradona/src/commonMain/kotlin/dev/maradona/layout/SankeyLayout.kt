package dev.maradona.layout

import dev.maradona.model.SankeyDiagram
import dev.maradona.model.SankeyLabelStyle
import dev.maradona.model.SankeyLink
import dev.maradona.model.SankeyNode
import dev.maradona.model.SankeyNodeAlignment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class SankeyLabelAnchor {
    Start,
    End,
}

data class PositionedSankeyNode(
    val node: SankeyNode,
    val nodeIndex: Int,
    val layer: Int,
    val value: Double,
    val rect: DiagramRect,
    val labelText: String,
    val labelRect: DiagramRect,
    val labelAnchor: SankeyLabelAnchor,
)

data class PositionedSankeyLink(
    val link: SankeyLink,
    val linkIndex: Int,
    val sourceNodeIndex: Int,
    val targetNodeIndex: Int,
    val start: DiagramPoint,
    val control1: DiagramPoint,
    val control2: DiagramPoint,
    val end: DiagramPoint,
    val thickness: Float,
)

data class SankeyDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val plotRect: DiagramRect,
    val nodes: List<PositionedSankeyNode>,
    val links: List<PositionedSankeyLink>,
) {
    fun node(id: String): PositionedSankeyNode? = nodes.firstOrNull { it.node.id == id }
}

/**
 * Pure-Kotlin layout engine for Mermaid `sankey` / `sankey-beta` diagrams (inspired by `d3-sankey`).
 *
 * Computes topological node layers (`justify`, `left`, `right`, `center`), proportional node heights,
 * iterative barycentric vertical relaxation with collision resolution, stacked link ribbon offsets,
 * horizontal cubic-Bezier link control points, and 4-direction bounds normalization.
 */
object SankeyLayout {

    fun layout(
        diagram: SankeyDiagram,
        nodeLabelSize: ((String) -> DiagramSize)? = null,
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): SankeyDiagramLayout {
        val d = options.density
        val resolveLabelSize = nodeLabelSize ?: { s -> defaultLabelSize(s, d) }
        val outerPad = 20f * d
        val labelGap = 6f * d

        val resolvedTitleSize = if (!diagram.title.isNullOrBlank()) {
            titleSize ?: DiagramSize(max(120f * d, diagram.title.length * 9.5f * d), 24f * d)
        } else {
            null
        }

        var contentTopY = outerPad
        val titleTopY = contentTopY
        if (resolvedTitleSize != null) {
            contentTopY += resolvedTitleSize.height + 16f * d
        }

        val baseChartW = max(240f, diagram.chartWidth) * d
        val baseChartH = max(180f, diagram.chartHeight) * d

        if (diagram.nodes.isEmpty()) {
            val totalW = max(baseChartW, (resolvedTitleSize?.width ?: 0f) + outerPad * 2f)
            val totalH = max(baseChartH, contentTopY + outerPad + 80f * d)
            val titleRect = resolvedTitleSize?.let {
                DiagramRect((totalW - it.width) / 2f, titleTopY, it.width, it.height)
            }
            return SankeyDiagramLayout(
                width = totalW,
                height = totalH,
                titleRect = titleRect,
                plotRect = DiagramRect(outerPad, contentTopY, totalW - outerPad * 2f, totalH - contentTopY - outerPad),
                nodes = emptyList(),
                links = emptyList(),
            )
        }

        val nodeCount = diagram.nodes.size
        val indexById = HashMap<String, Int>(nodeCount)
        for ((i, n) in diagram.nodes.withIndex()) {
            indexById[n.id] = i
        }

        val maxLinkValue = diagram.links.maxOfOrNull { it.value }?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        val outgoingByNode = Array(nodeCount) { mutableListOf<Int>() }
        val incomingByNode = Array(nodeCount) { mutableListOf<Int>() }
        val inSums = DoubleArray(nodeCount)
        val outSums = DoubleArray(nodeCount)
        val normInSums = DoubleArray(nodeCount)
        val normOutSums = DoubleArray(nodeCount)

        for ((lIdx, link) in diagram.links.withIndex()) {
            val u = indexById[link.source] ?: continue
            val v = indexById[link.target] ?: continue
            outgoingByNode[u] += lIdx
            incomingByNode[v] += lIdx
            outSums[u] += link.value
            inSums[v] += link.value
            val normW = link.value / maxLinkValue
            normOutSums[u] += normW
            normInSums[v] += normW
        }

        val normNodeValues = DoubleArray(nodeCount) { idx ->
            max(normInSums[idx], normOutSums[idx]).coerceAtLeast(1e-12)
        }

        // 1. Compute longest-path depths from sources and heights from sinks (with early exit).
        val depths = IntArray(nodeCount)
        for (iter in 0 until nodeCount) {
            var changed = false
            for (link in diagram.links) {
                val u = indexById[link.source] ?: continue
                val v = indexById[link.target] ?: continue
                if (u == v) continue
                if (depths[u] + 1 > depths[v] && depths[u] + 1 < nodeCount) {
                    depths[v] = depths[u] + 1
                    changed = true
                }
            }
            if (!changed) break
        }

        val heights = IntArray(nodeCount)
        for (iter in 0 until nodeCount) {
            var changed = false
            for (link in diagram.links) {
                val u = indexById[link.source] ?: continue
                val v = indexById[link.target] ?: continue
                if (u == v) continue
                if (heights[v] + 1 > heights[u] && heights[v] + 1 < nodeCount) {
                    heights[u] = heights[v] + 1
                    changed = true
                }
            }
            if (!changed) break
        }

        val maxDepth = depths.maxOrNull() ?: 0
        val rawLayers = IntArray(nodeCount) { idx ->
            when (diagram.nodeAlignment) {
                SankeyNodeAlignment.Left -> depths[idx]
                SankeyNodeAlignment.Right -> max(0, maxDepth - heights[idx])
                SankeyNodeAlignment.Justify -> {
                    if (outgoingByNode[idx].isEmpty() && maxDepth > 0) maxDepth else depths[idx]
                }
                SankeyNodeAlignment.Center -> {
                    if (incomingByNode[idx].isNotEmpty()) {
                        depths[idx]
                    } else if (outgoingByNode[idx].isNotEmpty()) {
                        val minTargetDepth = outgoingByNode[idx].minOf { lIdx ->
                            val tIdx = indexById[diagram.links[lIdx].target] ?: 0
                            depths[tIdx]
                        }
                        max(0, minTargetDepth - 1)
                    } else {
                        0
                    }
                }
            }
        }

        // Compact occupied layers to 0 .. L-1.
        val distinctRawLayers = rawLayers.distinct().sorted()
        val compactLayerMap = HashMap<Int, Int>()
        for ((compactIdx, rawLayer) in distinctRawLayers.withIndex()) {
            compactLayerMap[rawLayer] = compactIdx
        }
        val layers = IntArray(nodeCount) { idx -> compactLayerMap[rawLayers[idx]] ?: 0 }
        val layerCount = max(1, distinctRawLayers.size)
        val nodesByLayer = Array(layerCount) { mutableListOf<Int>() }
        for (idx in 0 until nodeCount) {
            nodesByLayer[layers[idx]] += idx
        }

        val nodeWidthPx = max(4f, diagram.nodeWidth) * d
        val baseNodePadPx = max(0f, diagram.nodePadding) * d + (if (diagram.showValues) 12f * d else 0f)
        val maxNodesInLayer = nodesByLayer.maxOfOrNull { it.size } ?: 1

        val plotX = outerPad
        val plotY = contentTopY
        val minPlotW = max(200f * d, layerCount * (nodeWidthPx + 90f * d))
        val minPlotH = max(140f * d, maxNodesInLayer * 28f * d + max(0, maxNodesInLayer - 1) * baseNodePadPx)
        val plotW = max(minPlotW, baseChartW - outerPad * 2f)
        val plotH = max(minPlotH, baseChartH - contentTopY - outerPad)
        val plotRect = DiagramRect(plotX, plotY, plotW, plotH)

        // Horizontal positions per layer.
        val nodeX0 = FloatArray(nodeCount)
        val nodeX1 = FloatArray(nodeCount)
        val layerStepX = if (layerCount > 1) (plotW - nodeWidthPx) / (layerCount - 1).toFloat() else 0f
        for (idx in 0 until nodeCount) {
            val l = layers[idx]
            val x0 = plotX + l * layerStepX
            nodeX0[idx] = x0
            nodeX1[idx] = x0 + nodeWidthPx
        }

        // Vertical scale factor normKy (px per normalized flow unit) across all layers.
        val effectivePadByLayer = FloatArray(layerCount) { c ->
            val count = nodesByLayer[c].size
            if (count <= 1) 0f else min(baseNodePadPx, (plotH * 0.45f) / (count - 1).toFloat())
        }
        val normKy: Double = (0 until layerCount).minOf { c ->
            val ns = nodesByLayer[c]
            val totalVal = ns.sumOf { normNodeValues[it] }.coerceAtLeast(1e-12)
            val availH = max(20f * d, plotH - max(0, ns.size - 1) * effectivePadByLayer[c]).toDouble()
            availH / totalVal
        }

        val linkThickness = FloatArray(diagram.links.size) { lIdx ->
            val normW = diagram.links[lIdx].value / maxLinkValue
            max(1.2f * d, (normW * normKy).toFloat())
        }

        val nodeY0 = FloatArray(nodeCount)
        val nodeY1 = FloatArray(nodeCount)
        val nodeH = FloatArray(nodeCount) { idx ->
            val outThick = outgoingByNode[idx].sumOf { linkThickness[it].toDouble() }.toFloat()
            val inThick = incomingByNode[idx].sumOf { linkThickness[it].toDouble() }.toFloat()
            maxOf(3f * d, (normNodeValues[idx] * normKy).toFloat(), outThick, inThick)
        }
        val maxLayerRequiredH = (0 until layerCount).maxOfOrNull { c ->
            val ns = nodesByLayer[c]
            ns.sumOf { nodeH[it].toDouble() }.toFloat() + max(0, ns.size - 1) * effectivePadByLayer[c]
        } ?: plotH
        val effectivePlotH = max(plotH, maxLayerRequiredH)
        val effectivePlotRect = DiagramRect(plotX, plotY, plotW, effectivePlotH)

        // Initial vertical stacking within each layer.
        for (c in 0 until layerCount) {
            val ns = nodesByLayer[c]
            val pad = effectivePadByLayer[c]
            var curY = plotY
            for (u in ns) {
                nodeY0[u] = curY
                nodeY1[u] = curY + nodeH[u]
                curY = nodeY1[u] + pad
            }
            resolveLayerCollisions(ns, nodeY0, nodeY1, nodeH, pad, plotY, effectivePlotRect.bottom)
        }

        // Barycentric Gauss-Seidel relaxation (forward + backward sweeps).
        var alpha = 1f
        repeat(10) {
            alpha *= 0.85f
            // Left-to-right sweep: align with incoming neighbors.
            for (c in 1 until layerCount) {
                val ns = nodesByLayer[c]
                for (u in ns) {
                    var weightedSum = 0.0
                    var weightTotal = 0.0
                    for (lIdx in incomingByNode[u]) {
                        val link = diagram.links[lIdx]
                        val src = indexById[link.source] ?: continue
                        val srcMid = (nodeY0[src] + nodeY1[src]) / 2f
                        val normW = link.value / maxLinkValue
                        weightedSum += srcMid * normW
                        weightTotal += normW
                    }
                    if (weightTotal > 0.0) {
                        val targetMid = (weightedSum / weightTotal).toFloat()
                        val curMid = (nodeY0[u] + nodeY1[u]) / 2f
                        val dy = (targetMid - curMid) * alpha
                        nodeY0[u] += dy
                        nodeY1[u] = nodeY0[u] + nodeH[u]
                    }
                }
                ns.sortBy { (nodeY0[it] + nodeY1[it]) / 2f }
                resolveLayerCollisions(ns, nodeY0, nodeY1, nodeH, effectivePadByLayer[c], plotY, effectivePlotRect.bottom)
            }
            // Right-to-left sweep: align with outgoing neighbors.
            for (c in layerCount - 2 downTo 0) {
                val ns = nodesByLayer[c]
                for (u in ns) {
                    var weightedSum = 0.0
                    var weightTotal = 0.0
                    for (lIdx in outgoingByNode[u]) {
                        val link = diagram.links[lIdx]
                        val dst = indexById[link.target] ?: continue
                        val dstMid = (nodeY0[dst] + nodeY1[dst]) / 2f
                        val normW = link.value / maxLinkValue
                        weightedSum += dstMid * normW
                        weightTotal += normW
                    }
                    if (weightTotal > 0.0) {
                        val targetMid = (weightedSum / weightTotal).toFloat()
                        val curMid = (nodeY0[u] + nodeY1[u]) / 2f
                        val dy = (targetMid - curMid) * alpha
                        nodeY0[u] += dy
                        nodeY1[u] = nodeY0[u] + nodeH[u]
                    }
                }
                ns.sortBy { (nodeY0[it] + nodeY1[it]) / 2f }
                resolveLayerCollisions(ns, nodeY0, nodeY1, nodeH, effectivePadByLayer[c], plotY, effectivePlotRect.bottom)
            }
        }

        // Compute stacked vertical offsets at source and target nodes.
        val linkSourceY = FloatArray(diagram.links.size)
        val linkTargetY = FloatArray(diagram.links.size)

        for (u in 0 until nodeCount) {
            val outs = outgoingByNode[u]
            outs.sortWith(
                compareBy<Int> { lIdx ->
                    val dst = indexById[diagram.links[lIdx].target] ?: 0
                    nodeY0[dst]
                }.thenBy { it },
            )
            var sy = nodeY0[u]
            for (lIdx in outs) {
                val w = linkThickness[lIdx]
                linkSourceY[lIdx] = sy + w / 2f
                sy += w
            }

            val ins = incomingByNode[u]
            ins.sortWith(
                compareBy<Int> { lIdx ->
                    val src = indexById[diagram.links[lIdx].source] ?: 0
                    nodeY0[src]
                }.thenBy { it },
            )
            var ty = nodeY0[u]
            for (lIdx in ins) {
                val w = linkThickness[lIdx]
                linkTargetY[lIdx] = ty + w / 2f
                ty += w
            }
        }

        // Central layer for Outlined label placement (matching Mermaid findCentralNodeLayer).
        var centralLayer = 0
        var maxNodeValue = -1.0
        for (idx in 0 until nodeCount) {
            if (normNodeValues[idx] > maxNodeValue) {
                maxNodeValue = normNodeValues[idx]
                centralLayer = layers[idx]
            }
        }

        val positionedNodes = (0 until nodeCount).map { idx ->
            val node = diagram.nodes[idx]
            val v = max(inSums[idx], outSums[idx])
            val lblText = formatNodeLabel(node.id, v, diagram)
            val lblSz = resolveLabelSize(lblText)
            val rect = DiagramRect(
                x = nodeX0[idx],
                y = nodeY0[idx],
                width = nodeX1[idx] - nodeX0[idx],
                height = nodeY1[idx] - nodeY0[idx],
            )
            val placeLeft = when (diagram.labelStyle) {
                SankeyLabelStyle.Outlined -> layers[idx] < centralLayer
                SankeyLabelStyle.Legacy -> nodeX0[idx] >= effectivePlotRect.centerX
            }
            val anchor = if (placeLeft) SankeyLabelAnchor.End else SankeyLabelAnchor.Start
            val lx = if (placeLeft) {
                rect.x - labelGap - lblSz.width
            } else {
                rect.right + labelGap
            }
            val ly = rect.centerY - lblSz.height / 2f
            PositionedSankeyNode(
                node = node,
                nodeIndex = idx,
                layer = layers[idx],
                value = v,
                rect = rect,
                labelText = lblText,
                labelRect = DiagramRect(lx, ly, lblSz.width, lblSz.height),
                labelAnchor = anchor,
            )
        }

        val positionedLinks = diagram.links.mapIndexedNotNull { lIdx, link ->
            val u = indexById[link.source] ?: return@mapIndexedNotNull null
            val v = indexById[link.target] ?: return@mapIndexedNotNull null
            val sx = nodeX1[u]
            val tx = nodeX0[v]
            val sy = linkSourceY[lIdx]
            val ty = linkTargetY[lIdx]
            val midX = (sx + tx) / 2f
            PositionedSankeyLink(
                link = link,
                linkIndex = lIdx,
                sourceNodeIndex = u,
                targetNodeIndex = v,
                start = DiagramPoint(sx, sy),
                control1 = DiagramPoint(midX, sy),
                control2 = DiagramPoint(midX, ty),
                end = DiagramPoint(tx, ty),
                thickness = linkThickness[lIdx],
            )
        }

        val totalW = max(
            effectivePlotRect.right + outerPad,
            (resolvedTitleSize?.width ?: 0f) + outerPad * 2f,
        )
        val totalH = max(baseChartH, effectivePlotRect.bottom + outerPad)
        val titleRect = resolvedTitleSize?.let {
            val tx = (effectivePlotRect.centerX - it.width / 2f).coerceIn(outerPad, max(outerPad, totalW - outerPad - it.width))
            DiagramRect(tx, titleTopY, it.width, it.height)
        }

        val initial = SankeyDiagramLayout(
            width = totalW,
            height = totalH,
            titleRect = titleRect,
            plotRect = effectivePlotRect,
            nodes = positionedNodes,
            links = positionedLinks,
        )
        return normalizeLayoutBounds(initial, outerPad, baseChartW, baseChartH)
    }

    private fun resolveLayerCollisions(
        layerNodes: List<Int>,
        nodeY0: FloatArray,
        nodeY1: FloatArray,
        nodeH: FloatArray,
        pad: Float,
        topBound: Float,
        bottomBound: Float,
    ) {
        if (layerNodes.isEmpty()) return
        // Push down overlapping nodes from top to bottom.
        var y = topBound
        for (u in layerNodes) {
            val dy = y - nodeY0[u]
            if (dy > 0f) {
                nodeY0[u] += dy
                nodeY1[u] += dy
            }
            y = nodeY1[u] + pad
        }
        // Push up from bottom if the last node overflowed bottomBound.
        var overflow = nodeY1[layerNodes.last()] - bottomBound
        if (overflow > 0f) {
            var uLast = layerNodes.last()
            nodeY0[uLast] -= overflow
            nodeY1[uLast] -= overflow
            for (i in layerNodes.size - 2 downTo 0) {
                val u = layerNodes[i]
                val nextU = layerNodes[i + 1]
                val overlap = (nodeY1[u] + pad) - nodeY0[nextU]
                if (overlap > 0f) {
                    nodeY0[u] -= overlap
                    nodeY1[u] -= overlap
                } else {
                    break
                }
            }
            // Clamp top if needed.
            if (nodeY0[layerNodes.first()] < topBound) {
                var curY = topBound
                for (u in layerNodes) {
                    nodeY0[u] = curY
                    nodeY1[u] = curY + nodeH[u]
                    curY = nodeY1[u] + pad
                }
            }
        }
    }

    private fun normalizeLayoutBounds(
        layout: SankeyDiagramLayout,
        outerPad: Float,
        baseChartW: Float,
        baseChartH: Float,
    ): SankeyDiagramLayout {
        val allRects = buildList {
            layout.titleRect?.let { add(it) }
            add(layout.plotRect)
            layout.nodes.forEach {
                add(it.rect)
                add(it.labelRect)
            }
            layout.links.forEach { l ->
                val halfT = l.thickness / 2f
                val minX = min(l.start.x, l.end.x)
                val maxX = max(l.start.x, l.end.x)
                val minY = min(l.start.y, l.end.y) - halfT
                val maxY = max(l.start.y, l.end.y) + halfT
                add(DiagramRect(minX, minY, max(1f, maxX - minX), max(1f, maxY - minY)))
            }
        }
        val minX = allRects.minOfOrNull { it.x } ?: outerPad
        val minY = allRects.minOfOrNull { it.y } ?: outerPad
        val dx = if (minX < outerPad) outerPad - minX else 0f
        val dy = if (minY < outerPad) outerPad - minY else 0f

        fun DiagramPoint.shift(): DiagramPoint = DiagramPoint(x + dx, y + dy)
        fun DiagramRect.shift(): DiagramRect = DiagramRect(x + dx, y + dy, width, height)

        val shiftedNodes = layout.nodes.map {
            it.copy(
                rect = it.rect.shift(),
                labelRect = it.labelRect.shift(),
            )
        }
        val shiftedLinks = layout.links.map {
            it.copy(
                start = it.start.shift(),
                control1 = it.control1.shift(),
                control2 = it.control2.shift(),
                end = it.end.shift(),
            )
        }
        val maxRight = (allRects.maxOfOrNull { it.right } ?: 0f) + dx
        val maxBottom = (allRects.maxOfOrNull { it.bottom } ?: 0f) + dy
        val finalW = maxOf(baseChartW, layout.width + dx, maxRight + outerPad)
        val finalH = maxOf(baseChartH, layout.height + dy, maxBottom + outerPad)
        val shiftedTitle = layout.titleRect?.let { tr ->
            val cx = (shiftedNodes.minOfOrNull { it.rect.x } ?: outerPad).let { left ->
                val right = shiftedNodes.maxOfOrNull { it.rect.right } ?: (finalW - outerPad)
                (left + right) / 2f
            }
            val tx = (cx - tr.width / 2f).coerceIn(outerPad, max(outerPad, finalW - outerPad - tr.width))
            DiagramRect(tx, tr.y + dy, tr.width, tr.height)
        }

        return layout.copy(
            width = finalW,
            height = finalH,
            titleRect = shiftedTitle,
            plotRect = layout.plotRect.shift(),
            nodes = shiftedNodes,
            links = shiftedLinks,
        )
    }

    internal fun formatNodeLabel(nodeId: String, value: Double, diagram: SankeyDiagram): String {
        if (!diagram.showValues) return nodeId
        return "$nodeId\n${diagram.prefix}${formatFlowValue(value)}${diagram.suffix}"
    }

    internal fun formatFlowValue(value: Double): String {
        if (!value.isFinite() || value == 0.0) return "0"
        val absVal = abs(value)
        if (absVal >= 1e12 || absVal < 1e-4) {
            return value.toString()
        }
        val roundedInt = kotlin.math.round(value)
        if (abs(value - roundedInt) <= 1e-9 * max(1.0, absVal)) {
            return roundedInt.toLong().toString()
        }
        val scaled100 = (value * 100.0)
        if (scaled100.isFinite() && abs(scaled100) < 9e15) {
            val r = kotlin.math.round(scaled100) / 100.0
            val s = r.toString()
            return if (s.contains('.') && !s.contains('E') && !s.contains('e')) {
                s.trimEnd('0').trimEnd('.')
            } else {
                s
            }
        }
        return value.toString()
    }

    internal fun defaultLabelSize(text: String, density: Float): DiagramSize {
        val lines = text.lines()
        val maxLen = lines.maxOfOrNull { it.length } ?: 1
        return DiagramSize(
            width = max(24f * density, (maxLen * 7.2f + 8f) * density),
            height = (lines.size * 15.5f + 2f) * density,
        )
    }
}
