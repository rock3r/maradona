package dev.maradona.layout

import dev.maradona.layout.dagre.Dagre
import dev.maradona.model.Direction
import dev.maradona.model.NodeShape
import dev.maradona.model.RequirementDiagram
import dev.maradona.model.RequirementDiagramItem
import dev.maradona.model.RequirementRelation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RequirementBoxMetrics(
    val width: Float,
    val height: Float,
    val headerHeight: Float,
    val stereotypeHeight: Float,
    val titleHeight: Float,
    val bodyLineHeights: List<Float> = emptyList(),
)

data class PositionedRequirementItem(
    val item: RequirementDiagramItem,
    val rect: DiagramRect,
    val headerHeight: Float,
    val stereotypeHeight: Float,
    val titleHeight: Float,
    val bodyLineHeights: List<Float>,
)

data class PositionedRequirementRelation(
    val index: Int,
    val relation: RequirementRelation,
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect?,
)

data class RequirementDiagramLayout(
    val width: Float,
    val height: Float,
    val direction: Direction,
    val titleRect: DiagramRect?,
    val items: List<PositionedRequirementItem>,
    val relations: List<PositionedRequirementRelation>,
) {
    fun itemNode(name: String): PositionedRequirementItem? = items.firstOrNull { it.item.name == name }
}

/**
 * Layered (Sugiyama / Dagre) layout engine for Mermaid `requirementDiagram` diagrams.
 *
 * Positions requirement and element boxes (with `<<Stereotype>>` + name header compartment
 * and attribute body compartment) and routes relationship edges (`<<contains>>`, `<<satisfies>>`,
 * `<<derives>>`, `<<refines>>`, `<<verifies>>`, `<<copies>>`, `<<traces>>`) across
 * `TB`, `BT`, `LR`, and `RL` directions.
 */
object RequirementLayout {

    fun layout(
        diagram: RequirementDiagram,
        itemBoxSize: ((RequirementDiagramItem) -> RequirementBoxMetrics)? = null,
        relationLabelSize: ((Int, RequirementRelation) -> DiagramSize)? = null,
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(nodeGap = 48f, layerGap = 56f),
    ): RequirementDiagramLayout {
        val d = options.density
        val outerPad = options.padding * d
        val nodeGapPx = options.nodeGap * d
        val layerGapPx = options.layerGap * d
        val direction = options.directionOverride ?: diagram.direction
        val resolveBoxSize = itemBoxSize ?: { item -> defaultBoxMetrics(item, d) }
        val resolveRelLabelSize = relationLabelSize ?: { _, rel -> defaultRelationLabelSize(rel.label, d) }

        val resolvedTitleSize = if (!diagram.title.isNullOrBlank()) {
            titleSize ?: DiagramSize(max(120f * d, diagram.title.length * 9.5f * d), 24f * d)
        } else {
            null
        }

        val allItems = diagram.items
        if (allItems.isEmpty()) {
            val totalW = (resolvedTitleSize?.width ?: 0f) + outerPad * 2f
            val totalH = (resolvedTitleSize?.height ?: 0f) + outerPad * 2f
            val tr = resolvedTitleSize?.let {
                DiagramRect(outerPad, outerPad, it.width, it.height)
            }
            return RequirementDiagramLayout(
                width = totalW,
                height = totalH,
                direction = direction,
                titleRect = tr,
                items = emptyList(),
                relations = emptyList(),
            )
        }

        val metricsById = allItems.associate { it.name to resolveBoxSize(it) }
        val swapped = direction == Direction.LR || direction == Direction.RL
        fun DiagramSize.maybeSwapped() = if (swapped) DiagramSize(height, width) else this

        val dagreNodes = allItems.map { item ->
            val m = metricsById.getValue(item.name)
            val sz = DiagramSize(m.width, m.height).maybeSwapped()
            item.name to (sz.width to sz.height)
        }
        val itemNames = allItems.map { it.name }.toSet()

        val dagreEdges = mutableListOf<Dagre.EdgeSpec>()
        for ((idx, rel) in diagram.relations.withIndex()) {
            if (rel.source == rel.target || rel.source !in itemNames || rel.target !in itemNames) continue
            val rawLbl = resolveRelLabelSize(idx, rel)
            val effLbl = DiagramSize(
                width = rawLbl.width + 24f * d,
                height = rawLbl.height + 24f * d,
            ).maybeSwapped()
            dagreEdges += Dagre.EdgeSpec(
                from = rel.source,
                to = rel.target,
                name = "r$idx",
                minlen = 1,
                labelWidth = effLbl.width,
                labelHeight = effLbl.height,
            )
        }

        val dagreResult = Dagre.layout(
            nodes = dagreNodes,
            edges = dagreEdges,
            nodesep = nodeGapPx,
            ranksep = layerGapPx,
        )

        val rawRects = HashMap<String, DiagramRect>()
        var canonMaxY = 0f
        for ((id, box) in dagreResult.nodes) {
            val r = DiagramRect(box.left, box.top, box.width, box.height)
            rawRects[id] = r
            canonMaxY = max(canonMaxY, r.bottom)
        }
        val canonH = max(1f, canonMaxY)

        fun transformPoint(p: DiagramPoint): DiagramPoint = when (direction) {
            Direction.TB -> p
            Direction.BT -> DiagramPoint(p.x, canonH - p.y)
            Direction.LR -> DiagramPoint(p.y, p.x)
            Direction.RL -> DiagramPoint(canonH - p.y, p.x)
        }

        fun transformRect(r: DiagramRect): DiagramRect = when (direction) {
            Direction.TB -> r
            Direction.BT -> DiagramRect(r.x, canonH - r.y - r.height, r.width, r.height)
            Direction.LR -> DiagramRect(r.y, r.x, r.height, r.width)
            Direction.RL -> DiagramRect(canonH - r.y - r.height, r.x, r.height, r.width)
        }

        val origRectById = rawRects.mapValues { (_, r) -> transformRect(r) }
        val itemRectById = origRectById.toMutableMap()

        val nonSelfPairCounts = HashMap<Pair<String, String>, Int>()
        val nonSelfPairOrdByIdx = HashMap<Int, Int>()
        val selfCountByItem = HashMap<String, Int>()
        val selfOrdByIdx = HashMap<Int, Int>()
        for ((idx, rel) in diagram.relations.withIndex()) {
            if (rel.source == rel.target) {
                val ord = selfCountByItem[rel.source] ?: 0
                selfOrdByIdx[idx] = ord
                selfCountByItem[rel.source] = ord + 1
            } else {
                val pairKey = if (rel.source <= rel.target) (rel.source to rel.target) else (rel.target to rel.source)
                val ord = nonSelfPairCounts[pairKey] ?: 0
                nonSelfPairOrdByIdx[idx] = ord
                nonSelfPairCounts[pairKey] = ord + 1
            }
        }

        fun selfEnvelopeForRect(itemId: String, base: DiagramRect): DiagramRect {
            var minX = base.x
            var minY = base.y
            var maxX = base.right
            var maxY = base.bottom
            for ((idx, rel) in diagram.relations.withIndex()) {
                if (rel.source != itemId || rel.target != itemId) continue
                val selfOrd = selfOrdByIdx[idx] ?: 0
                val lSize = resolveRelLabelSize(idx, rel)
                val outX = (32f + selfOrd * 22f) * d
                val outY = (32f + selfOrd * 20f) * d
                val labelRight = base.right + outX + 4f * d + lSize.width
                minY = min(minY, base.y - outY - lSize.height / 2f)
                maxX = max(maxX, labelRight)
            }
            return DiagramRect(minX, minY, maxX - minX, maxY - minY)
        }

        // Reserve clearance around any item with self-relations so self-loops never cross neighboring boxes.
        for (itemId in itemNames) {
            if ((selfCountByItem[itemId] ?: 0) == 0) continue
            var selfRect = itemRectById[itemId] ?: continue
            var env = selfEnvelopeForRect(itemId, selfRect)
            val gap = nodeGapPx * 0.5f

            val aboveMaxBottom = itemRectById.entries
                .filter { (k, r) ->
                    k != itemId && r.centerY < selfRect.centerY &&
                        r.x < env.right + gap && env.x - gap < r.right
                }
                .maxOfOrNull { (k, r) -> selfEnvelopeForRect(k, r).bottom }
            if (aboveMaxBottom != null && aboveMaxBottom + gap > env.y) {
                val shiftDown = (aboveMaxBottom + gap) - env.y
                val thresholdY = selfRect.y - 0.5f
                for ((k, r) in itemRectById.entries.toList()) {
                    if (r.y >= thresholdY) {
                        itemRectById[k] = r.copy(y = r.y + shiftDown)
                    }
                }
                selfRect = itemRectById.getValue(itemId)
                env = selfEnvelopeForRect(itemId, selfRect)
            }

            val rightNeighborMinX = itemRectById.entries
                .filter { (k, r) ->
                    k != itemId && r.centerX > selfRect.centerX &&
                        r.y < env.bottom && env.y < r.bottom
                }
                .minOfOrNull { (k, r) -> selfEnvelopeForRect(k, r).x }
            if (rightNeighborMinX != null && rightNeighborMinX < env.right + gap) {
                val shiftRight = (env.right + gap) - rightNeighborMinX
                for ((k, r) in itemRectById.entries.toList()) {
                    if (k != itemId && r.x >= rightNeighborMinX - 0.5f && r.y < env.bottom && env.y < r.bottom) {
                        itemRectById[k] = r.copy(x = r.x + shiftRight)
                    }
                }
            }
        }

        val positionedItems = allItems.mapNotNull { item ->
            val r = itemRectById[item.name] ?: return@mapNotNull null
            val m = metricsById.getValue(item.name)
            PositionedRequirementItem(
                item = item,
                rect = r,
                headerHeight = m.headerHeight,
                stereotypeHeight = m.stereotypeHeight,
                titleHeight = m.titleHeight,
                bodyLineHeights = m.bodyLineHeights,
            )
        }

        val occupiedTextRects = positionedItems.map { it.rect }.toMutableList()

        fun deconflictTextRect(initial: DiagramRect): DiagramRect {
            val pad = 4f * d
            var cur = initial
            repeat(24) {
                val hit = occupiedTextRects.firstOrNull { rectsOverlap(cur, it) } ?: return cur
                val candidates = listOf(
                    cur.copy(y = hit.bottom + pad),
                    cur.copy(y = hit.y - cur.height - pad),
                    cur.copy(x = hit.right + pad),
                    cur.copy(x = hit.x - cur.width - pad),
                )
                val nonOverlapping = candidates
                    .filter { cand -> occupiedTextRects.none { rectsOverlap(cand, it) } }
                    .minByOrNull { cand ->
                        val dx = cand.centerX - initial.centerX
                        val dy = cand.centerY - initial.centerY
                        dx * dx + dy * dy
                    }
                if (nonOverlapping != null) return nonOverlapping
                cur = candidates.minBy { cand ->
                    val overlaps = occupiedTextRects.count { rectsOverlap(cand, it) }
                    val dx = cand.centerX - initial.centerX
                    val dy = cand.centerY - initial.centerY
                    overlaps * 1_000_000f + dx * dx + dy * dy
                }
            }
            return cur
        }

        val positionedRelations = diagram.relations.mapIndexed { idx, rel ->
            val fromRect = itemRectById[rel.source]
            val toRect = itemRectById[rel.target]
            val lSize = resolveRelLabelSize(idx, rel)
            if (fromRect == null || toRect == null) {
                return@mapIndexed PositionedRequirementRelation(idx, rel, emptyList(), null)
            }

            if (rel.source == rel.target) {
                val selfOrd = selfOrdByIdx[idx] ?: 0
                val outX = (32f + selfOrd * 22f) * d
                val outY = (32f + selfOrd * 20f) * d
                val startY = (fromRect.centerY + selfOrd * 10f * d).coerceAtMost(fromRect.bottom - 6f * d)
                val endX = (fromRect.centerX + fromRect.width / 4f - selfOrd * 14f * d).coerceAtLeast(fromRect.x + 8f * d)
                val pts = mutableListOf(
                    DiagramPoint(fromRect.right, startY),
                    DiagramPoint(fromRect.right + outX, startY),
                    DiagramPoint(fromRect.right + outX, fromRect.y - outY),
                    DiagramPoint(endX, fromRect.y - outY),
                    DiagramPoint(endX, fromRect.y),
                )
                pts[0] = NodeOutline.intersect(NodeShape.Rect, fromRect, pts[1], d)
                pts[pts.lastIndex] = NodeOutline.intersect(NodeShape.Rect, toRect, pts[pts.lastIndex - 1], d)
                val labelRect = deconflictTextRect(
                    DiagramRect(fromRect.right + outX + 4f * d, fromRect.y - outY / 2f - lSize.height / 2f, lSize.width, lSize.height),
                ).also(occupiedTextRects::add)
                return@mapIndexed PositionedRequirementRelation(idx, rel, pts, labelRect)
            }

            val origFrom = origRectById[rel.source] ?: fromRect
            val origTo = origRectById[rel.target] ?: toRect
            val dxFrom = fromRect.centerX - origFrom.centerX
            val dyFrom = fromRect.centerY - origFrom.centerY
            val dxTo = toRect.centerX - origTo.centerX
            val dyTo = toRect.centerY - origTo.centerY

            val pairKey = if (rel.source <= rel.target) (rel.source to rel.target) else (rel.target to rel.source)
            val pairTotal = nonSelfPairCounts[pairKey] ?: 1
            val pairOrd = nonSelfPairOrdByIdx[idx] ?: 0
            val ep = dagreResult.paths["r$idx"]

            val pts = if (pairTotal > 1 || ep == null || ep.points.size < 2) {
                if (pairTotal > 1) {
                    val canonFrom = itemRectById.getValue(pairKey.first)
                    val canonTo = itemRectById.getValue(pairKey.second)
                    val vx = canonTo.centerX - canonFrom.centerX
                    val vy = canonTo.centerY - canonFrom.centerY
                    val vLen = sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
                    val nx = -vy / vLen
                    val ny = vx / vLen
                    val offset = (pairOrd - (pairTotal - 1) / 2f) * 28f * d
                    val midX = (fromRect.centerX + toRect.centerX) / 2f + nx * offset
                    val midY = (fromRect.centerY + toRect.centerY) / 2f + ny * offset
                    mutableListOf(
                        DiagramPoint(fromRect.centerX, fromRect.centerY),
                        DiagramPoint(midX, midY),
                        DiagramPoint(toRect.centerX, toRect.centerY),
                    )
                } else {
                    mutableListOf(
                        DiagramPoint(fromRect.centerX, fromRect.centerY),
                        DiagramPoint(toRect.centerX, toRect.centerY),
                    )
                }
            } else {
                ep.points.mapIndexed { k, (px, py) ->
                    val tp = transformPoint(DiagramPoint(px, py))
                    val t = if (ep.points.size > 1) k.toFloat() / ep.points.lastIndex else 0.5f
                    DiagramPoint(
                        x = tp.x + dxFrom + (dxTo - dxFrom) * t,
                        y = tp.y + dyFrom + (dyTo - dyFrom) * t,
                    )
                }.toMutableList()
            }
            pts[0] = NodeOutline.intersect(NodeShape.Rect, fromRect, pts[1], d)
            pts[pts.lastIndex] = NodeOutline.intersect(NodeShape.Rect, toRect, pts[pts.lastIndex - 1], d)

            val pad = 12f * d
            val obstacles = positionedItems
                .filter { it.item.name != rel.source && it.item.name != rel.target }
                .map { pi ->
                    val r = pi.rect
                    r to DiagramRect(r.x - pad, r.y - pad, r.width + pad * 2f, r.height + pad * 2f)
                }
            var rerouted = pairTotal > 1
            repeat(2) {
                var passChanged = false
                for ((innerRect, guardRect) in obstacles) {
                    if (ClassLayout.detourAroundRect(pts, guardRect, innerRect, d)) {
                        rerouted = true
                        passChanged = true
                    }
                }
                if (passChanged && pts.size >= 2) {
                    pts[0] = NodeOutline.intersect(NodeShape.Rect, fromRect, pts[1], d)
                    pts[pts.lastIndex] = NodeOutline.intersect(NodeShape.Rect, toRect, pts[pts.lastIndex - 1], d)
                }
                if (!passChanged) return@repeat
            }

            val defaultCentre = if (!rerouted && ep?.labelCentre != null) {
                val tl = transformPoint(DiagramPoint(ep.labelCentre.first, ep.labelCentre.second))
                DiagramPoint(tl.x + (dxFrom + dxTo) / 2f, tl.y + (dyFrom + dyTo) / 2f)
            } else {
                polylineMidpoint(pts)
            }
            val candidate = DiagramRect(
                defaultCentre.x - lSize.width / 2f,
                defaultCentre.y - lSize.height / 2f,
                lSize.width,
                lSize.height,
            )
            val labelRect = deconflictTextRect(candidate).also(occupiedTextRects::add)
            PositionedRequirementRelation(idx, rel, pts, labelRect)
        }

        // Normalize bounds to start at options.padding (with optional title above).
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        fun includeRect(r: DiagramRect) {
            minX = min(minX, r.x)
            minY = min(minY, r.y)
            maxX = max(maxX, r.right)
            maxY = max(maxY, r.bottom)
        }
        fun includePoint(p: DiagramPoint) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        positionedItems.forEach { includeRect(it.rect) }
        positionedRelations.forEach { pr ->
            pr.points.forEach(::includePoint)
            pr.labelRect?.let(::includeRect)
        }
        if (!minX.isFinite()) {
            minX = 0f
            minY = 0f
            maxX = 100f
            maxY = 100f
        }

        val titleGap = resolvedTitleSize?.let { it.height + 16f * d } ?: 0f
        val contentW = max(maxX - minX, resolvedTitleSize?.width ?: 0f)
        val dx = outerPad - minX + max(0f, (contentW - (maxX - minX)) / 2f)
        val dy = outerPad + titleGap - minY
        fun DiagramRect.shifted() = copy(x = x + dx, y = y + dy)
        fun DiagramPoint.shifted() = DiagramPoint(x + dx, y + dy)

        val finalWidth = contentW + outerPad * 2f
        val finalHeight = (maxY - minY) + titleGap + outerPad * 2f
        val titleRect = resolvedTitleSize?.let {
            DiagramRect((finalWidth - it.width) / 2f, outerPad, it.width, it.height)
        }

        return RequirementDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            direction = direction,
            titleRect = titleRect,
            items = positionedItems.map { it.copy(rect = it.rect.shifted()) },
            relations = positionedRelations.map { pr ->
                pr.copy(
                    points = pr.points.map { it.shifted() },
                    labelRect = pr.labelRect?.shifted(),
                )
            },
        )
    }

    internal fun defaultBoxMetrics(item: RequirementDiagramItem, density: Float): RequirementBoxMetrics {
        val padX = 14f * density
        val padY = 10f * density
        val lineGap = 4f * density
        val stereoW = (item.stereotype.length * 6.8f + 8f) * density
        val stereoH = 15f * density
        val titleW = (item.name.length * 8.0f + 8f) * density
        val titleH = 18f * density
        val headerH = padY + stereoH + lineGap + titleH + padY
        val bodyLines = item.bodyLines
        if (bodyLines.isEmpty()) {
            val w = maxOf(130f * density, stereoW + padX * 2f, titleW + padX * 2f)
            return RequirementBoxMetrics(
                width = w,
                height = headerH,
                headerHeight = headerH,
                stereotypeHeight = stereoH,
                titleHeight = titleH,
                bodyLineHeights = emptyList(),
            )
        }
        val maxBodyW = bodyLines.maxOf { (it.length * 6.8f + 8f) * density }
        val bodyHeights = bodyLines.map { 16f * density }
        val bodyTotalH = padY + bodyHeights.sum() + (bodyHeights.size - 1) * lineGap + padY
        val w = maxOf(150f * density, stereoW + padX * 2f, titleW + padX * 2f, maxBodyW + padX * 2f)
        return RequirementBoxMetrics(
            width = w,
            height = headerH + bodyTotalH,
            headerHeight = headerH,
            stereotypeHeight = stereoH,
            titleHeight = titleH,
            bodyLineHeights = bodyHeights,
        )
    }

    internal fun defaultRelationLabelSize(text: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(48f * density, (text.length * 6.8f + 12f) * density),
            height = 18f * density,
        )

    private fun rectsOverlap(a: DiagramRect, b: DiagramRect): Boolean =
        a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    private fun polylineMidpoint(pts: List<DiagramPoint>): DiagramPoint {
        if (pts.isEmpty()) return DiagramPoint(0f, 0f)
        if (pts.size == 1) return pts[0]
        var total = 0f
        val segLengths = FloatArray(pts.size - 1)
        for (i in 0 until pts.lastIndex) {
            val dx = pts[i + 1].x - pts[i].x
            val dy = pts[i + 1].y - pts[i].y
            val len = sqrt(dx * dx + dy * dy)
            segLengths[i] = len
            total += len
        }
        if (total <= 1e-3f) return pts[pts.size / 2]
        val half = total / 2f
        var acc = 0f
        for (i in segLengths.indices) {
            val len = segLengths[i]
            if (acc + len >= half && len > 1e-3f) {
                val t = (half - acc) / len
                return DiagramPoint(
                    x = pts[i].x + (pts[i + 1].x - pts[i].x) * t,
                    y = pts[i].y + (pts[i + 1].y - pts[i].y) * t,
                )
            }
            acc += len
        }
        return pts[pts.size / 2]
    }
}
