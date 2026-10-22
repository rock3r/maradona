package dev.maradona.layout

import dev.maradona.model.Direction
import dev.maradona.model.ErDiagram
import dev.maradona.model.ErEntity
import dev.maradona.model.ErRelationship
import dev.maradona.model.ErSubgraph
import dev.maradona.model.NodeShape
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ErEntityMetrics(
    val width: Float,
    val height: Float,
    val headerHeight: Float,
    val rowHeights: List<Float> = emptyList(),
    val colWidths: List<Float> = emptyList(),
)

data class PositionedErEntity(
    val entity: ErEntity,
    val rect: DiagramRect,
    val headerHeight: Float,
    val rowHeights: List<Float>,
    val colWidths: List<Float>,
)

data class PositionedErRelationship(
    val index: Int,
    val relationship: ErRelationship,
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect?,
)

data class PositionedErSubgraph(
    val subgraph: ErSubgraph,
    val rect: DiagramRect,
    val headerHeight: Float,
    val headerRect: DiagramRect = DiagramRect(rect.x, rect.y, rect.width, headerHeight),
)

data class ErDiagramLayout(
    val width: Float,
    val height: Float,
    val direction: Direction,
    val titleRect: DiagramRect?,
    val entities: List<PositionedErEntity>,
    val relationships: List<PositionedErRelationship>,
    val subgraphs: List<PositionedErSubgraph>,
) {
    fun entityNode(id: String): PositionedErEntity? = entities.firstOrNull { it.entity.id == id }
    fun subgraphNode(id: String): PositionedErSubgraph? = subgraphs.firstOrNull { it.subgraph.id == id }
}

/**
 * Iterative bottom-up Sugiyama (Dagre) layout for Mermaid `erDiagram` diagrams.
 *
 * Each `subgraph` lays out its internal entities and child subgraphs first (deepest-first),
 * then acts as a single solid node in its parent scope's Dagre pass, followed by a single
 * O(N) top-down coordinate translation.
 */
object ErLayout {

    private class ScopePlacement(
        val width: Float,
        val height: Float,
        val entityRects: MutableMap<String, DiagramRect>,
        val subgraphRects: MutableMap<String, DiagramRect>,
        val subgraphHeaderHeights: MutableMap<String, Float>,
        val subgraphTitleRects: MutableMap<String, DiagramRect>,
        val origItemRects: MutableMap<String, DiagramRect>,
        val dagrePaths: MutableMap<Int, dev.maradona.layout.dagre.Dagre.EdgePath>,
    ) {
        fun translate(dx: Float, dy: Float) {
            if (dx == 0f && dy == 0f) return
            for ((k, r) in entityRects.entries.toList()) {
                entityRects[k] = r.copy(x = r.x + dx, y = r.y + dy)
            }
            for ((k, r) in subgraphRects.entries.toList()) {
                subgraphRects[k] = r.copy(x = r.x + dx, y = r.y + dy)
            }
            for ((k, r) in subgraphTitleRects.entries.toList()) {
                subgraphTitleRects[k] = r.copy(x = r.x + dx, y = r.y + dy)
            }
            for ((k, r) in origItemRects.entries.toList()) {
                origItemRects[k] = r.copy(x = r.x + dx, y = r.y + dy)
            }
            for ((k, ep) in dagrePaths.entries.toList()) {
                dagrePaths[k] = dev.maradona.layout.dagre.Dagre.EdgePath(
                    points = ep.points.map { (px, py) -> (px + dx) to (py + dy) },
                    labelCentre = ep.labelCentre?.let { (lx, ly) -> (lx + dx) to (ly + dy) },
                )
            }
        }
    }

    fun layout(
        diagram: ErDiagram,
        entityBoxSize: (ErEntity) -> ErEntityMetrics = { e -> defaultEntityMetrics(e, 1f) },
        relationshipLabelSize: (Int, ErRelationship) -> DiagramSize? = { _, rel ->
            rel.label?.takeIf { it.isNotEmpty() }?.let { lbl ->
                val lines = lbl.lines()
                DiagramSize(lines.maxOf { it.length } * 7.5f + 10f, lines.size * 16f + 4f)
            }
        },
        subgraphLabelSize: (ErSubgraph) -> DiagramSize = { sg ->
            val lines = sg.label.lines()
            DiagramSize(max(40f, lines.maxOf { it.length } * 8f), max(18f, lines.size * 18f))
        },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(nodeGap = 52f, layerGap = 58f, subgraphPaddingX = 28f),
    ): ErDiagramLayout {
        val rootDirection = options.directionOverride ?: diagram.direction
        if (diagram.entities.isEmpty() && diagram.subgraphs.isEmpty()) {
            return ErDiagramLayout(
                width = 0f,
                height = 0f,
                direction = rootDirection,
                titleRect = null,
                entities = emptyList(),
                relationships = emptyList(),
                subgraphs = emptyList(),
            )
        }

        val entityById = diagram.entities.associateBy { it.id }
        val subgraphById = diagram.subgraphs.associateBy { it.id }
        val metricsById = diagram.entities.associate { it.id to entityBoxSize(it) }
        val sgLabelSizes = diagram.subgraphs.associate { it.id to subgraphLabelSize(it) }

        val entitiesByScope = diagram.entities.groupBy { it.subgraphId }
        val subgraphsByScope = diagram.subgraphs.groupBy { it.parentId }

        // Precompute cumulative self-relationship ordinals and label Y offsets per endpoint ID.
        val selfOrdByRelIdx = HashMap<Int, Int>()
        val selfLabelOffsetYByRelIdx = HashMap<Int, Float>()
        val selfCountByItem = HashMap<String, Int>()
        val selfCumLabelOffsetYByItem = HashMap<String, Float>()
        for ((idx, rel) in diagram.relationships.withIndex()) {
            if (rel.from == rel.to) {
                val ord = selfCountByItem[rel.from] ?: 0
                selfOrdByRelIdx[idx] = ord
                selfCountByItem[rel.from] = ord + 1
                val curOff = selfCumLabelOffsetYByItem[rel.from] ?: 0f
                selfLabelOffsetYByRelIdx[idx] = curOff
                val lH = relationshipLabelSize(idx, rel)?.height ?: (16f * options.density)
                selfCumLabelOffsetYByItem[rel.from] = curOff + lH + 6f * options.density
            }
        }

        fun itemParentId(id: String): String? =
            entityById[id]?.subgraphId ?: subgraphById[id]?.parentId

        // Lift `itemId` (entity or subgraph) through parent links until its parent is `scopeParentId`.
        fun liftToScope(itemId: String, scopeParentId: String?): String? {
            if (itemId !in entityById && itemId !in subgraphById) return null
            var cur = itemId
            val visited = HashSet<String>()
            while (itemParentId(cur) != scopeParentId) {
                if (!visited.add(cur)) return null
                cur = itemParentId(cur) ?: return null
            }
            return cur
        }

        fun selfEnvelopeForRect(itemId: String, base: DiagramRect): DiagramRect {
            var minX = base.x
            var minY = base.y
            var maxX = base.right
            var maxY = base.bottom
            for ((idx, rel) in diagram.relationships.withIndex()) {
                if (rel.from != itemId || rel.to != itemId) continue
                val selfOrd = selfOrdByRelIdx[idx] ?: 0
                val lSize = relationshipLabelSize(idx, rel)
                val outX = (32f + selfOrd * 22f) * options.density
                val outY = (32f + selfOrd * 20f) * options.density
                val labelRight = base.right + outX + (lSize?.let { 4f * options.density + it.width } ?: 0f)
                val labelBottom = lSize?.let {
                    base.y + (selfLabelOffsetYByRelIdx[idx] ?: 0f) + it.height
                } ?: base.bottom
                minY = min(minY, base.y - outY)
                maxX = max(maxX, labelRight)
                maxY = max(maxY, labelBottom)
            }
            return DiagramRect(minX, minY, maxX - minX, maxY - minY)
        }

        val subgraphPlacements = HashMap<String, Pair<DiagramPoint, ScopePlacement>>()
        val subgraphSizes = HashMap<String, DiagramSize>()
        val subgraphHeaderHeights = HashMap<String, Float>()
        val subgraphTitleWidths = HashMap<String, Float>()

        fun layoutScope(scopeParentId: String?, scopeDirection: Direction): ScopePlacement {
            val directEntities = entitiesByScope[scopeParentId].orEmpty()
            val directSubgraphs = subgraphsByScope[scopeParentId].orEmpty()
            val directItemIds = (directEntities.map { it.id } + directSubgraphs.map { it.id }).toSet()

            val swapped = scopeDirection == Direction.LR || scopeDirection == Direction.RL
            fun DiagramSize.maybeSwapped() = if (swapped) DiagramSize(height, width) else this

            val dagreNodes = LinkedHashMap<String, Pair<Float, Float>>()
            for (e in directEntities) {
                val m = metricsById.getValue(e.id)
                val sz = DiagramSize(m.width, m.height).maybeSwapped()
                dagreNodes[e.id] = sz.width to sz.height
            }
            for (sg in directSubgraphs) {
                val sz = subgraphSizes.getValue(sg.id).maybeSwapped()
                dagreNodes[sg.id] = sz.width to sz.height
            }

            if (dagreNodes.isEmpty()) {
                return ScopePlacement(
                    width = 0f,
                    height = 0f,
                    entityRects = mutableMapOf(),
                    subgraphRects = mutableMapOf(),
                    subgraphHeaderHeights = mutableMapOf(),
                    subgraphTitleRects = mutableMapOf(),
                    origItemRects = mutableMapOf(),
                    dagrePaths = mutableMapOf(),
                )
            }

            val dagreEdges = mutableListOf<dev.maradona.layout.dagre.Dagre.EdgeSpec>()
            for ((idx, rel) in diagram.relationships.withIndex()) {
                val u = liftToScope(rel.from, scopeParentId) ?: continue
                val v = liftToScope(rel.to, scopeParentId) ?: continue
                if (u == v || u !in dagreNodes || v !in dagreNodes) continue
                val isDirect = rel.from == u && rel.to == v
                val rawLbl = relationshipLabelSize(idx, rel)
                // Add room for crow's-foot markers on both ends (18px each side = 36px along rank).
                val effLabel = DiagramSize(
                    width = (rawLbl?.width ?: 0f) + 32f * options.density,
                    height = (rawLbl?.height ?: 0f) + 32f * options.density,
                ).maybeSwapped()
                dagreEdges += dev.maradona.layout.dagre.Dagre.EdgeSpec(
                    from = u,
                    to = v,
                    name = if (isDirect) "r$idx" else "lift$idx",
                    minlen = if (isDirect && u !in subgraphById && v !in subgraphById) 1 else 2,
                    labelWidth = effLabel.width,
                    labelHeight = effLabel.height,
                )
            }

            val dagreResult = dev.maradona.layout.dagre.Dagre.layout(
                nodes = dagreNodes.map { (k, v) -> k to v },
                edges = dagreEdges,
                nodesep = options.nodeGap,
                ranksep = options.layerGap,
            )

            val rawRects = HashMap<String, DiagramRect>()
            for ((id, box) in dagreResult.nodes) {
                rawRects[id] = DiagramRect(box.left, box.top, box.width, box.height)
            }

            var canonMaxY = 0f
            for (r in rawRects.values) {
                canonMaxY = max(canonMaxY, r.bottom)
            }
            val canonH = max(1f, canonMaxY)

            fun transformPoint(p: DiagramPoint): DiagramPoint = when (scopeDirection) {
                Direction.TB -> p
                Direction.BT -> DiagramPoint(p.x, canonH - p.y)
                Direction.LR -> DiagramPoint(p.y, p.x)
                Direction.RL -> DiagramPoint(canonH - p.y, p.x)
            }

            fun transformRect(r: DiagramRect): DiagramRect = when (scopeDirection) {
                Direction.TB -> r
                Direction.BT -> DiagramRect(r.x, canonH - r.y - r.height, r.width, r.height)
                Direction.LR -> DiagramRect(r.y, r.x, r.height, r.width)
                Direction.RL -> DiagramRect(canonH - r.y - r.height, r.x, r.height, r.width)
            }

            val origRects = rawRects.mapValues { (_, r) -> transformRect(r) }
            val finalRects = origRects.toMutableMap()

            // Reserve clearance around any direct item with self-relationships.
            for (itemId in directItemIds) {
                val hasSelf = diagram.relationships.any { it.from == itemId && it.to == itemId }
                if (!hasSelf) continue
                var selfRect = finalRects[itemId] ?: continue
                var env = selfEnvelopeForRect(itemId, selfRect)
                val gap = options.nodeGap * 0.5f

                val aboveMaxBottom = finalRects.entries
                    .filter { (k, r) ->
                        k != itemId && r.centerY < selfRect.centerY &&
                            r.x < env.right + gap && env.x - gap < r.right
                    }
                    .maxOfOrNull { (k, r) -> selfEnvelopeForRect(k, r).bottom }
                if (aboveMaxBottom != null && aboveMaxBottom + gap > env.y) {
                    val shiftDown = (aboveMaxBottom + gap) - env.y
                    val thresholdY = selfRect.y - 0.5f
                    for ((k, r) in finalRects.entries.toList()) {
                        if (r.y >= thresholdY) {
                            finalRects[k] = r.copy(y = r.y + shiftDown)
                        }
                    }
                    selfRect = finalRects.getValue(itemId)
                    env = selfEnvelopeForRect(itemId, selfRect)
                }

                val belowMinTop = finalRects.entries
                    .filter { (k, r) ->
                        k != itemId && r.centerY > selfRect.centerY &&
                            r.x < env.right + gap && env.x - gap < r.right
                    }
                    .minOfOrNull { (k, r) -> selfEnvelopeForRect(k, r).y }
                if (belowMinTop != null && belowMinTop < env.bottom + gap) {
                    val shiftDown = (env.bottom + gap) - belowMinTop
                    for ((k, r) in finalRects.entries.toList()) {
                        if (k != itemId && r.y >= belowMinTop - 0.5f) {
                            finalRects[k] = r.copy(y = r.y + shiftDown)
                        }
                    }
                }

                val rightNeighborMinX = finalRects.entries
                    .filter { (k, r) ->
                        k != itemId && r.centerX > selfRect.centerX &&
                            r.y < env.bottom && env.y < r.bottom
                    }
                    .minOfOrNull { (k, r) -> selfEnvelopeForRect(k, r).x }
                if (rightNeighborMinX != null && rightNeighborMinX < env.right + gap) {
                    val shift = (env.right + gap) - rightNeighborMinX
                    for ((k, r) in finalRects.entries.toList()) {
                        if (k != itemId && r.x >= rightNeighborMinX - 0.5f && r.y < env.bottom && env.y < r.bottom) {
                            finalRects[k] = r.copy(x = r.x + shift)
                        }
                    }
                }
            }

            val entityRects = LinkedHashMap<String, DiagramRect>()
            val subgraphRects = LinkedHashMap<String, DiagramRect>()
            val sgHdrHeights = LinkedHashMap<String, Float>()
            val sgTitleRects = LinkedHashMap<String, DiagramRect>()
            val origItemRects = LinkedHashMap<String, DiagramRect>()
            val dagrePaths = LinkedHashMap<Int, dev.maradona.layout.dagre.Dagre.EdgePath>()

            for ((idx, _) in diagram.relationships.withIndex()) {
                val ep = dagreResult.paths["r$idx"] ?: continue
                dagrePaths[idx] = dev.maradona.layout.dagre.Dagre.EdgePath(
                    points = ep.points.map { (px, py) ->
                        val tp = transformPoint(DiagramPoint(px, py))
                        tp.x to tp.y
                    },
                    labelCentre = ep.labelCentre?.let { (lx, ly) ->
                        val tl = transformPoint(DiagramPoint(lx, ly))
                        tl.x to tl.y
                    },
                )
            }

            for (e in directEntities) {
                entityRects[e.id] = finalRects.getValue(e.id)
                origItemRects[e.id] = origRects.getValue(e.id)
            }
            for (sg in directSubgraphs) {
                val r = finalRects.getValue(sg.id)
                val hdrH = subgraphHeaderHeights.getValue(sg.id)
                val titleW = subgraphTitleWidths.getValue(sg.id)
                val padHdrY = min(4f * options.density, hdrH * 0.2f)
                subgraphRects[sg.id] = r
                origItemRects[sg.id] = origRects.getValue(sg.id)
                sgHdrHeights[sg.id] = hdrH
                sgTitleRects[sg.id] = DiagramRect(
                    x = r.centerX - titleW / 2f,
                    y = r.y + padHdrY,
                    width = titleW,
                    height = max(1f, hdrH - padHdrY * 2f),
                )
            }

            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (itemId in directItemIds) {
                val base = finalRects.getValue(itemId)
                val env = selfEnvelopeForRect(itemId, base)
                minX = min(minX, env.x)
                minY = min(minY, env.y)
                maxX = max(maxX, env.right)
                maxY = max(maxY, env.bottom)
            }
            for ((idx, rel) in diagram.relationships.withIndex()) {
                if (rel.from == rel.to || rel.from !in directItemIds || rel.to !in directItemIds) continue
                val fR = finalRects[rel.from] ?: continue
                val tR = finalRects[rel.to] ?: continue
                val oF = origRects[rel.from] ?: fR
                val oT = origRects[rel.to] ?: tR
                val dFx = fR.centerX - oF.centerX
                val dFy = fR.centerY - oF.centerY
                val dTx = tR.centerX - oT.centerX
                val dTy = tR.centerY - oT.centerY
                val ep = dagrePaths[idx]
                if (ep != null) {
                    for ((k, pt) in ep.points.withIndex()) {
                        val t = if (ep.points.size > 1) k.toFloat() / ep.points.lastIndex else 0.5f
                        val px = pt.first + dFx + (dTx - dFx) * t
                        val py = pt.second + dFy + (dTy - dFy) * t
                        minX = min(minX, px)
                        minY = min(minY, py)
                        maxX = max(maxX, px)
                        maxY = max(maxY, py)
                    }
                }
                val lSz = relationshipLabelSize(idx, rel)
                if (lSz != null) {
                    val cx = ep?.labelCentre?.let { it.first + (dFx + dTx) / 2f } ?: ((fR.centerX + tR.centerX) / 2f)
                    val cy = ep?.labelCentre?.let { it.second + (dFy + dTy) / 2f } ?: ((fR.centerY + tR.centerY) / 2f)
                    minX = min(minX, cx - lSz.width / 2f)
                    minY = min(minY, cy - lSz.height / 2f)
                    maxX = max(maxX, cx + lSz.width / 2f)
                    maxY = max(maxY, cy + lSz.height / 2f)
                }
            }
            if (!minX.isFinite()) {
                minX = 0f
                minY = 0f
                maxX = 0f
                maxY = 0f
            }

            val placement = ScopePlacement(
                width = max(0f, maxX - minX),
                height = max(0f, maxY - minY),
                entityRects = entityRects,
                subgraphRects = subgraphRects,
                subgraphHeaderHeights = sgHdrHeights,
                subgraphTitleRects = sgTitleRects,
                origItemRects = origItemRects,
                dagrePaths = dagrePaths,
            )
            placement.translate(-minX, -minY)
            return placement
        }

        fun subgraphDepth(sg: ErSubgraph): Int {
            var d = 0
            var p = sg.parentId
            val visited = HashSet<String>()
            while (p != null && visited.add(p)) {
                d++
                p = subgraphById[p]?.parentId
            }
            return d
        }

        fun effectiveDirection(sg: ErSubgraph): Direction {
            val chain = mutableListOf<ErSubgraph>()
            var cur: ErSubgraph? = sg
            val visited = HashSet<String>()
            while (cur != null && visited.add(cur.id)) {
                chain += cur
                cur = cur.parentId?.let { subgraphById[it] }
            }
            var dir = rootDirection
            for (anc in chain.asReversed()) {
                if (anc.direction != null) dir = anc.direction
            }
            return dir
        }

        // Lay out subgraphs iteratively bottom-up (deepest first).
        val subgraphsDeepestFirst = diagram.subgraphs.sortedByDescending { subgraphDepth(it) }
        for (sg in subgraphsDeepestFirst) {
            val sgDir = effectiveDirection(sg)
            val lSz = sgLabelSizes[sg.id] ?: DiagramSize(60f, 18f)
            val hdrH = max(26f * options.density, lSz.height + 10f * options.density)
            val titleW = max(40f * options.density, lSz.width)
            subgraphHeaderHeights[sg.id] = hdrH
            subgraphTitleWidths[sg.id] = titleW

            val inner = layoutScope(sg.id, sgDir)
            val padX = options.subgraphPaddingX
            val padTop = 14f * options.density
            val padBottom = options.subgraphPaddingY * 0.65f
            if (inner.entityRects.isEmpty() && inner.subgraphRects.isEmpty()) {
                val w = max(120f * options.density, titleW + padX * 2f)
                val h = hdrH + 36f * options.density
                subgraphSizes[sg.id] = DiagramSize(w, h)
                subgraphPlacements[sg.id] = DiagramPoint(padX, hdrH + padTop) to inner
            } else {
                val w = max(titleW + padX * 2f, inner.width + padX * 2f)
                val h = hdrH + padTop + inner.height + padBottom
                val offsetX = (w - inner.width) / 2f
                val offsetY = hdrH + padTop
                subgraphSizes[sg.id] = DiagramSize(w, h)
                subgraphPlacements[sg.id] = DiagramPoint(offsetX, offsetY) to inner
            }
        }

        val rootPlacement = layoutScope(null, rootDirection)

        // Single top-down O(N) pass to translate each subgraph's direct members into global coordinates.
        for (sg in subgraphsDeepestFirst.asReversed()) {
            val sgRect = rootPlacement.subgraphRects[sg.id] ?: continue
            val (offset, inner) = subgraphPlacements[sg.id] ?: continue
            inner.translate(sgRect.x + offset.x, sgRect.y + offset.y)
            rootPlacement.entityRects.putAll(inner.entityRects)
            rootPlacement.subgraphRects.putAll(inner.subgraphRects)
            rootPlacement.subgraphHeaderHeights.putAll(inner.subgraphHeaderHeights)
            rootPlacement.subgraphTitleRects.putAll(inner.subgraphTitleRects)
            rootPlacement.origItemRects.putAll(inner.origItemRects)
            rootPlacement.dagrePaths.putAll(inner.dagrePaths)
        }

        val positionedEntities = diagram.entities.mapNotNull { e ->
            val r = rootPlacement.entityRects[e.id] ?: return@mapNotNull null
            val m = metricsById.getValue(e.id)
            PositionedErEntity(
                entity = e,
                rect = r,
                headerHeight = m.headerHeight,
                rowHeights = m.rowHeights,
                colWidths = m.colWidths,
            )
        }
        val positionedSubgraphs = subgraphsDeepestFirst.asReversed().mapNotNull { sg ->
            val r = rootPlacement.subgraphRects[sg.id] ?: return@mapNotNull null
            val hdrH = rootPlacement.subgraphHeaderHeights[sg.id] ?: 26f
            val hdrRect = rootPlacement.subgraphTitleRects[sg.id] ?: DiagramRect(r.x, r.y, r.width, hdrH)
            PositionedErSubgraph(
                subgraph = sg,
                rect = r,
                headerHeight = hdrH,
                headerRect = hdrRect,
            )
        }

        val itemRectById = HashMap<String, DiagramRect>().apply {
            for (pe in positionedEntities) put(pe.entity.id, pe.rect)
            for (psg in positionedSubgraphs) put(psg.subgraph.id, psg.rect)
        }

        fun ancestorsOf(itemId: String): Set<String> {
            val out = HashSet<String>()
            var cur = itemParentId(itemId)
            while (cur != null && out.add(cur)) {
                cur = itemParentId(cur)
            }
            return out
        }

        val occupiedTextRects = mutableListOf<DiagramRect>().apply {
            for (pe in positionedEntities) add(pe.rect)
            for (psg in positionedSubgraphs) {
                add(DiagramRect(psg.rect.x, psg.rect.y, psg.rect.width, psg.headerHeight))
            }
        }

        fun deconflictTextRect(initial: DiagramRect): DiagramRect {
            val pad = 4f * options.density
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

        val nonSelfPairCounts = HashMap<Pair<String, String>, Int>()
        val nonSelfPairOrdByRelIdx = HashMap<Int, Int>()
        for ((idx, rel) in diagram.relationships.withIndex()) {
            if (rel.from == rel.to) continue
            val pairKey = if (rel.from <= rel.to) (rel.from to rel.to) else (rel.to to rel.from)
            val ord = nonSelfPairCounts[pairKey] ?: 0
            nonSelfPairOrdByRelIdx[idx] = ord
            nonSelfPairCounts[pairKey] = ord + 1
        }

        val positionedRelationships = diagram.relationships.mapIndexed { idx, rel ->
            val fromRect = itemRectById[rel.from]
            val toRect = itemRectById[rel.to]
            val lSize = relationshipLabelSize(idx, rel)
            if (fromRect == null || toRect == null) {
                return@mapIndexed PositionedErRelationship(idx, rel, emptyList(), null)
            }

            if (rel.from == rel.to) {
                val selfOrd = selfOrdByRelIdx[idx] ?: 0
                val outX = (32f + selfOrd * 22f) * options.density
                val outY = (32f + selfOrd * 20f) * options.density
                val startY = (fromRect.centerY + selfOrd * 10f * options.density).coerceAtMost(fromRect.bottom - 6f * options.density)
                val endX = (fromRect.centerX + fromRect.width / 4f - selfOrd * 14f * options.density).coerceAtLeast(fromRect.x + 8f * options.density)
                val pts = mutableListOf(
                    DiagramPoint(fromRect.right, startY),
                    DiagramPoint(fromRect.right + outX, startY),
                    DiagramPoint(fromRect.right + outX, fromRect.y - outY),
                    DiagramPoint(endX, fromRect.y - outY),
                    DiagramPoint(endX, fromRect.y),
                )
                pts[0] = NodeOutline.intersect(NodeShape.Rect, fromRect, pts[1], options.density)
                pts[pts.lastIndex] = NodeOutline.intersect(NodeShape.Rect, toRect, pts[pts.lastIndex - 1], options.density)
                val labelRect = lSize?.let {
                    val labelY = fromRect.y + (selfLabelOffsetYByRelIdx[idx] ?: 0f)
                    deconflictTextRect(
                        DiagramRect(fromRect.right + outX + 4f * options.density, labelY, it.width, it.height),
                    ).also(occupiedTextRects::add)
                }
                return@mapIndexed PositionedErRelationship(idx, rel, pts, labelRect)
            }

            val origFrom = rootPlacement.origItemRects[rel.from] ?: fromRect
            val origTo = rootPlacement.origItemRects[rel.to] ?: toRect
            val dxFrom = fromRect.centerX - origFrom.centerX
            val dyFrom = fromRect.centerY - origFrom.centerY
            val dxTo = toRect.centerX - origTo.centerX
            val dyTo = toRect.centerY - origTo.centerY
            val sameScope = itemParentId(rel.from) == itemParentId(rel.to)
            val orderReversed = !sameScope ||
                ((origTo.centerX - origFrom.centerX) * (toRect.centerX - fromRect.centerX) < -1f) ||
                ((origTo.centerY - origFrom.centerY) * (toRect.centerY - fromRect.centerY) < -1f)

            val pairKey = if (rel.from <= rel.to) (rel.from to rel.to) else (rel.to to rel.from)
            val pairTotal = nonSelfPairCounts[pairKey] ?: 1
            val pairOrd = nonSelfPairOrdByRelIdx[idx] ?: 0

            val path = rootPlacement.dagrePaths[idx]
            val pts = if (orderReversed || path == null || path.points.size < 2) {
                if (pairTotal > 1) {
                    val canonFrom = itemRectById.getValue(pairKey.first)
                    val canonTo = itemRectById.getValue(pairKey.second)
                    val vx = canonTo.centerX - canonFrom.centerX
                    val vy = canonTo.centerY - canonFrom.centerY
                    val vLen = sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
                    val nx = -vy / vLen
                    val ny = vx / vLen
                    val offset = (pairOrd - (pairTotal - 1) / 2f) * 26f * options.density
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
                path.points.mapIndexed { k, (px, py) ->
                    val t = if (path.points.size > 1) k.toFloat() / path.points.lastIndex else 0.5f
                    DiagramPoint(
                        x = px + dxFrom + (dxTo - dxFrom) * t,
                        y = py + dyFrom + (dyTo - dyFrom) * t,
                    )
                }.toMutableList()
            }
            pts[0] = NodeOutline.intersect(NodeShape.Rect, fromRect, pts[1], options.density)
            pts[pts.lastIndex] = NodeOutline.intersect(NodeShape.Rect, toRect, pts[pts.lastIndex - 1], options.density)

            var rerouted = orderReversed
            val endpointAncestors = ancestorsOf(rel.from) + ancestorsOf(rel.to)
            val pad = 14f * options.density
            val detourObstacles = buildList {
                for (pe in positionedEntities) {
                    if (pe.entity.id != rel.from && pe.entity.id != rel.to) {
                        val obs = pe.rect
                        add(obs to DiagramRect(obs.x - pad, obs.y - pad, obs.width + pad * 2f, obs.height + pad * 2f))
                    }
                }
                for (psg in positionedSubgraphs) {
                    if (psg.subgraph.id == rel.from || psg.subgraph.id == rel.to) continue
                    if (psg.subgraph.id in endpointAncestors) {
                        val hdr = psg.headerRect
                        if (hdr.width > 0f && hdr.height > 0f) {
                            val clearPadX = min(8f * options.density, max(4f * options.density, (psg.rect.width - hdr.width) * 0.25f))
                            val clearPadY = 6f * options.density
                            add(
                                hdr to DiagramRect(
                                    x = hdr.x - clearPadX,
                                    y = hdr.y - clearPadY,
                                    width = hdr.width + clearPadX * 2f,
                                    height = hdr.height + clearPadY * 2f,
                                ),
                            )
                        }
                    } else {
                        val obs = psg.rect
                        add(obs to DiagramRect(obs.x - pad, obs.y - pad, obs.width + pad * 2f, obs.height + pad * 2f))
                    }
                }
            }

            repeat(2) {
                var passChanged = false
                for ((innerRect, guardRect) in detourObstacles) {
                    if (ClassLayout.detourAroundRect(pts, guardRect, innerRect, options.density)) {
                        rerouted = true
                        passChanged = true
                    }
                }
                if ((passChanged || rerouted) && pts.size >= 2) {
                    val newStart = NodeOutline.intersect(NodeShape.Rect, fromRect, pts[1], options.density)
                    if (detourObstacles.none { (inner, _) -> ClassLayout.segmentIntersectsRect(newStart, pts[1], inner) }) {
                        pts[0] = newStart
                    }
                    val newEnd = NodeOutline.intersect(NodeShape.Rect, toRect, pts[pts.lastIndex - 1], options.density)
                    if (detourObstacles.none { (inner, _) -> ClassLayout.segmentIntersectsRect(pts[pts.lastIndex - 1], newEnd, inner) }) {
                        pts[pts.lastIndex] = newEnd
                    }
                }
                if (!passChanged) return@repeat
            }

            val labelRect = lSize?.let { size ->
                val defaultCentre = if (!rerouted && path?.labelCentre != null) {
                    DiagramPoint(
                        path.labelCentre.first + (dxFrom + dxTo) / 2f,
                        path.labelCentre.second + (dyFrom + dyTo) / 2f,
                    )
                } else {
                    polylineMidpoint(pts)
                }
                var candidate = DiagramRect(defaultCentre.x - size.width / 2f, defaultCentre.y - size.height / 2f, size.width, size.height)
                if (rerouted || occupiedTextRects.any { rectsOverlap(candidate, it) }) {
                    val segMidCandidates = (0 until pts.lastIndex)
                        .map { i ->
                            val a = pts[i]
                            val b = pts[i + 1]
                            val len = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                            val mid = DiagramPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            len to DiagramRect(mid.x - size.width / 2f, mid.y - size.height / 2f, size.width, size.height)
                        }
                        .sortedByDescending { it.first }
                    val clearCandidate = segMidCandidates.firstOrNull { (_, r) -> occupiedTextRects.none { rectsOverlap(r, it) } }?.second
                    if (clearCandidate != null) {
                        candidate = clearCandidate
                    }
                }
                deconflictTextRect(candidate).also(occupiedTextRects::add)
            }

            PositionedErRelationship(idx, rel, pts, labelRect)
        }

        // Final normalization so all elements sit at >= options.padding (with optional title above).
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
        positionedEntities.forEach { includeRect(it.rect) }
        positionedSubgraphs.forEach { includeRect(it.rect) }
        positionedRelationships.forEach { r ->
            r.points.forEach(::includePoint)
            r.labelRect?.let(::includeRect)
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

        return ErDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            direction = rootDirection,
            titleRect = titleRect,
            entities = positionedEntities.map { pe -> pe.copy(rect = pe.rect.shifted()) },
            relationships = positionedRelationships.map { pr ->
                pr.copy(
                    points = pr.points.map { it.shifted() },
                    labelRect = pr.labelRect?.shifted(),
                )
            },
            subgraphs = positionedSubgraphs.map { psg ->
                psg.copy(
                    rect = psg.rect.shifted(),
                    headerRect = psg.headerRect.shifted(),
                )
            },
        )
    }

    internal fun defaultEntityMetrics(e: ErEntity, density: Float): ErEntityMetrics {
        val padX = 12f * density
        val headerLines = e.label.lines()
        val headerW = headerLines.maxOf { it.length } * 8.5f * density + padX * 2f
        val headerH = max(34f * density, headerLines.size * 18f * density + 14f * density)
        if (e.attributes.isEmpty()) {
            return ErEntityMetrics(
                width = max(100f * density, headerW),
                height = headerH,
                headerHeight = headerH,
            )
        }
        val hasKeys = e.attributes.any { it.keys.isNotEmpty() }
        val hasComments = e.attributes.any { !it.comment.isNullOrEmpty() }
        val col0W = e.attributes.maxOf { it.type.length * 7f * density } + padX * 1.5f
        val col1W = e.attributes.maxOf { it.name.length * 7.5f * density } + padX * 1.5f
        val col2W = if (hasKeys) {
            e.attributes.maxOf { it.keys.joinToString(", ").length * 7f * density } + padX * 1.5f
        } else 0f
        val col3W = if (hasComments) {
            e.attributes.maxOf { (it.comment?.length ?: 0) * 7f * density } + padX * 1.5f
        } else 0f
        val rawCols = listOfNotNull(
            col0W,
            col1W,
            col2W.takeIf { hasKeys },
            col3W.takeIf { hasComments },
        )
        val sumCols = rawCols.sum()
        val width = maxOf(110f * density, headerW, sumCols)
        val extraPerCol = if (width > sumCols && rawCols.isNotEmpty()) (width - sumCols) / rawCols.size else 0f
        val colWidths = rawCols.map { it + extraPerCol }
        val rowHeights = e.attributes.map { 24f * density }
        val totalH = headerH + rowHeights.sum()
        return ErEntityMetrics(
            width = width,
            height = totalH,
            headerHeight = headerH,
            rowHeights = rowHeights,
            colWidths = colWidths,
        )
    }

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
