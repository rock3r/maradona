package dev.maradona.layout

import dev.maradona.model.ClassDiagram
import dev.maradona.model.ClassNamespace
import dev.maradona.model.ClassNode
import dev.maradona.model.ClassNote
import dev.maradona.model.ClassRelation
import dev.maradona.model.Direction
import dev.maradona.model.NodeShape
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ClassBoxMetrics(
    val width: Float,
    val height: Float,
    val headerHeight: Float,
    val attributesHeight: Float,
    val methodsHeight: Float,
)

data class PositionedClassNode(
    val node: ClassNode,
    val rect: DiagramRect,
    val headerHeight: Float,
    val attributesHeight: Float,
    val methodsHeight: Float,
)

data class PositionedClassRelation(
    val index: Int,
    val relation: ClassRelation,
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect?,
    val fromCardinalityRect: DiagramRect?,
    val toCardinalityRect: DiagramRect?,
)

data class PositionedClassNote(
    val index: Int,
    val note: ClassNote,
    val rect: DiagramRect,
    val connectorPoints: List<DiagramPoint>,
)

data class PositionedClassNamespace(
    val namespace: ClassNamespace,
    val rect: DiagramRect,
    val headerRect: DiagramRect = DiagramRect(rect.x, rect.y, 0f, 0f),
)

data class ClassDiagramLayout(
    val width: Float,
    val height: Float,
    val direction: Direction,
    val titleRect: DiagramRect?,
    val classes: List<PositionedClassNode>,
    val relations: List<PositionedClassRelation>,
    val notes: List<PositionedClassNote>,
    val namespaces: List<PositionedClassNamespace>,
) {
    fun classNode(id: String): PositionedClassNode? = classes.firstOrNull { it.node.id == id }
}

/**
 * Layered (Sugiyama / dagre) layout for Mermaid `classDiagram` diagrams.
 *
 * Positions UML class boxes (with header, attribute, and method compartments),
 * notes (including dotted connectors for `note for Class`), relationships with
 * midpoint labels and endpoint cardinalities, and hierarchical or flat
 * `namespace` cluster boxes across `TB`, `BT`, `LR`, and `RL` directions.
 */
object ClassLayout {

    fun layout(
        diagram: ClassDiagram,
        classBoxSize: (ClassNode) -> ClassBoxMetrics,
        relationLabelSize: (Int, ClassRelation) -> DiagramSize? = { _, _ -> null },
        cardinalitySize: (Int, Boolean, String) -> DiagramSize = { _, _, text ->
            DiagramSize(text.length * 7f, 14f)
        },
        noteBoxSize: (Int, ClassNote) -> DiagramSize = { _, note ->
            DiagramSize(max(80f, note.text.length * 7f + 20f), 32f)
        },
        namespaceLabelSize: (ClassNamespace) -> DiagramSize = { ns ->
            DiagramSize(ns.label.length * 8f, 18f)
        },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(nodeGap = 48f, layerGap = 54f),
    ): ClassDiagramLayout {
        val direction = options.directionOverride ?: diagram.direction
        if (diagram.classes.isEmpty() && diagram.notes.isEmpty() && diagram.namespaces.isEmpty()) {
            return ClassDiagramLayout(
                width = 0f,
                height = 0f,
                direction = direction,
                titleRect = null,
                classes = emptyList(),
                relations = emptyList(),
                notes = emptyList(),
                namespaces = emptyList(),
            )
        }
        val swapped = direction == Direction.LR || direction == Direction.RL
        fun DiagramSize.maybeSwapped() = if (swapped) DiagramSize(height, width) else this

        val metricsById = diagram.classes.associate { c -> c.id to classBoxSize(c) }
        val noteSizes = diagram.notes.mapIndexed { idx, n -> noteBoxSize(idx, n) }
        val nsLabelSizes = diagram.namespaces.associate { ns -> ns.id to namespaceLabelSize(ns) }

        val nsOrder = diagram.namespaces.withIndex().associate { (i, ns) -> ns.id to i }
        val orderedClasses = if (diagram.namespaces.isEmpty()) {
            diagram.classes
        } else {
            diagram.classes.sortedBy { c -> c.namespaceId?.let { nsOrder[it] } ?: Int.MAX_VALUE }
        }
        val dagreNodeSizes = LinkedHashMap<String, Pair<Float, Float>>()
        for (c in orderedClasses) {
            val m = metricsById.getValue(c.id)
            dagreNodeSizes["c:${c.id}"] = if (swapped) (m.height to m.width) else (m.width to m.height)
        }
        for ((idx, n) in diagram.notes.withIndex()) {
            val sz = noteSizes[idx].maybeSwapped()
            dagreNodeSizes["n:${n.id}"] = sz.width to sz.height
        }

        val leafEmptyNamespaces = diagram.namespaces.filter { ns ->
            diagram.classes.none { it.namespaceId == ns.id } &&
                diagram.notes.none { it.namespaceId == ns.id } &&
                diagram.namespaces.none { it.parentId == ns.id }
        }
        for (ns in leafEmptyNamespaces) {
            val lSz = nsLabelSizes[ns.id] ?: DiagramSize(80f, 18f)
            val boxSz = DiagramSize(max(120f, lSz.width + 32f * options.density), 54f * options.density).maybeSwapped()
            dagreNodeSizes["ns:${ns.id}"] = boxSz.width to boxSz.height
        }

        val nsByClass = diagram.classes.associate { it.id to it.namespaceId }
        val boundaryRanks = if (diagram.namespaces.isEmpty()) 0 else 1
        fun minlenFor(fromId: String, toId: String): Int {
            if (boundaryRanks == 0) return 1
            val nsFrom = nsByClass[fromId]
            val nsTo = nsByClass[toId]
            return if (nsFrom != nsTo && (nsFrom != null || nsTo != null)) 1 + boundaryRanks else 1
        }

        val dagreEdges = mutableListOf<dev.maradona.layout.dagre.Dagre.EdgeSpec>()
        for ((idx, rel) in diagram.relations.withIndex()) {
            if (rel.from == rel.to) continue
            val fromKey = "c:${rel.from}"
            val toKey = "c:${rel.to}"
            if (fromKey !in dagreNodeSizes || toKey !in dagreNodeSizes) continue
            val rawLabel = relationLabelSize(idx, rel)
            val fromCardSz = rel.fromCardinality?.let { cardinalitySize(idx, true, it) }
            val toCardSz = rel.toCardinality?.let { cardinalitySize(idx, false, it) }
            val effLabel = if (rawLabel != null || fromCardSz != null || toCardSz != null) {
                val cardW = (fromCardSz?.width ?: 0f) + (toCardSz?.width ?: 0f) +
                    (if (fromCardSz != null || toCardSz != null) 28f * options.density else 0f)
                val cardH = (fromCardSz?.height ?: 0f) + (toCardSz?.height ?: 0f) +
                    (if (fromCardSz != null || toCardSz != null) 28f * options.density else 0f)
                DiagramSize(
                    width = (rawLabel?.width ?: 0f) + cardW,
                    height = (rawLabel?.height ?: 0f) + cardH,
                ).maybeSwapped()
            } else null
            dagreEdges += dev.maradona.layout.dagre.Dagre.EdgeSpec(
                from = fromKey,
                to = toKey,
                name = "r$idx",
                minlen = minlenFor(rel.from, rel.to),
                labelWidth = effLabel?.width ?: 0f,
                labelHeight = effLabel?.height ?: 0f,
            )
        }
        for ((idx, note) in diagram.notes.withIndex()) {
            val targetId = note.forClassId ?: continue
            val fromKey = "n:${note.id}"
            val toKey = "c:$targetId"
            if (fromKey in dagreNodeSizes && toKey in dagreNodeSizes) {
                dagreEdges += dev.maradona.layout.dagre.Dagre.EdgeSpec(
                    from = fromKey,
                    to = toKey,
                    name = "nc$idx",
                    minlen = 1,
                )
            }
        }

        val dagreResult = dev.maradona.layout.dagre.Dagre.layout(
            nodes = dagreNodeSizes.map { (k, v) -> k to v },
            edges = dagreEdges,
            nodesep = options.nodeGap,
            ranksep = options.layerGap,
        )

        val rawRects = HashMap<String, DiagramRect>()
        for ((id, box) in dagreResult.nodes) {
            rawRects[id] = DiagramRect(box.left, box.top, box.width, box.height)
        }

        // Compute canonical bounds before direction transform.
        var canonMaxX = 0f
        var canonMaxY = 0f
        for (r in rawRects.values) {
            canonMaxX = max(canonMaxX, r.right)
            canonMaxY = max(canonMaxY, r.bottom)
        }
        val canonW = max(1f, canonMaxX)
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

        val origRects = rawRects.mapValues { (_, r) -> transformRect(r) }
        val finalRects = origRects.toMutableMap()

        // Reserve horizontal space to the right of any class with self-relations so their
        // self-loops and labels never overlap neighboring classes or notes on the same rank.
        val selfOrdByRelIdx = HashMap<Int, Int>()
        val selfLabelOffsetYByRelIdx = HashMap<Int, Float>()
        val selfCountByClass = HashMap<String, Int>()
        val selfCumOffsetYByClass = HashMap<String, Float>()
        for ((idx, rel) in diagram.relations.withIndex()) {
            if (rel.from == rel.to) {
                val ord = selfCountByClass[rel.from] ?: 0
                selfOrdByRelIdx[idx] = ord
                selfCountByClass[rel.from] = ord + 1
                val curOff = selfCumOffsetYByClass[rel.from] ?: 0f
                selfLabelOffsetYByRelIdx[idx] = curOff
                val lH = relationLabelSize(idx, rel)?.height ?: (16f * options.density)
                selfCumOffsetYByClass[rel.from] = curOff + lH + 6f * options.density
            }
        }
        for ((idx, rel) in diagram.relations.withIndex()) {
            if (rel.from != rel.to) continue
            val selfOrd = selfOrdByRelIdx[idx] ?: 0
            val selfKey = "c:${rel.from}"
            var selfRect = finalRects[selfKey] ?: continue
            val lSize = relationLabelSize(idx, rel)
            val fromCardSz = rel.fromCardinality?.let { cardinalitySize(idx, true, it) }
            val toCardSz = rel.toCardinality?.let { cardinalitySize(idx, false, it) }
            val outX = max((28f + selfOrd * 22f) * options.density, (fromCardSz?.width ?: 0f) + 12f * options.density)
            val outY = (28f + selfOrd * 20f) * options.density
            val endX = (selfRect.centerX + selfRect.width / 4f - selfOrd * 14f * options.density).coerceAtLeast(selfRect.x + 8f * options.density)
            val labelRight = selfRect.right + outX + (lSize?.let { 4f * options.density + it.width } ?: 0f)
            val fromCardRight = fromCardSz?.let { selfRect.right + 4f * options.density + it.width } ?: selfRect.right
            val toCardRight = toCardSz?.let { endX + 4f * options.density + it.width } ?: selfRect.right
            val gap = options.nodeGap * 0.5f
            val neededRight = maxOf(labelRight, fromCardRight, toCardRight) + gap
            val neededTopY = selfRect.y - max(outY, (toCardSz?.height ?: 0f) + 8f * options.density)

            // Reserve vertical clearance above `selfRect` so expanded self-loops never pass through preceding classes.
            val aboveMaxBottom = finalRects.entries
                .filter { (k, r) ->
                    k != selfKey && r.centerY < selfRect.centerY &&
                        r.x < neededRight && selfRect.x < r.right
                }
                .maxOfOrNull { it.value.bottom }
            if (aboveMaxBottom != null && aboveMaxBottom + gap > neededTopY) {
                val shiftDown = (aboveMaxBottom + gap) - neededTopY
                val thresholdY = selfRect.y - 0.5f
                for ((k, r) in finalRects.entries.toList()) {
                    if (r.y >= thresholdY) {
                        finalRects[k] = r.copy(y = r.y + shiftDown)
                    }
                }
                selfRect = finalRects.getValue(selfKey)
            }

            // Reserve vertical clearance below `selfRect` if staggered self-relation labels or source cardinalities extend past `selfRect.bottom`.
            val startY = (selfRect.centerY + selfOrd * 10f * options.density).coerceAtMost(selfRect.bottom - 6f * options.density)
            val fromCardBottom = fromCardSz?.let { startY + 4f * options.density + it.height } ?: selfRect.bottom
            val labelBottom = lSize?.let {
                selfRect.y + (selfLabelOffsetYByRelIdx[idx] ?: 0f) + it.height
            } ?: selfRect.bottom
            val neededBottomY = maxOf(selfRect.bottom, labelBottom, fromCardBottom)
            val belowMinTop = finalRects.entries
                .filter { (k, r) ->
                    k != selfKey && r.centerY > selfRect.centerY &&
                        r.x < neededRight && selfRect.x < r.right
                }
                .minOfOrNull { it.value.y }
            if (belowMinTop != null && belowMinTop < neededBottomY + gap) {
                val shiftDown = (neededBottomY + gap) - belowMinTop
                for ((k, r) in finalRects.entries.toList()) {
                    if (k != selfKey && r.y >= belowMinTop - 0.5f) {
                        finalRects[k] = r.copy(y = r.y + shiftDown)
                    }
                }
            }

            val selfBand = DiagramRect(selfRect.x, selfRect.y - outY, neededRight - selfRect.x, (neededBottomY - (selfRect.y - outY)))
            val rightNeighborMinX = finalRects.entries
                .filter { (k, r) -> k != selfKey && r.x > selfRect.x && r.y < selfBand.bottom && selfBand.y < r.bottom }
                .minOfOrNull { it.value.x }
            if (rightNeighborMinX != null && rightNeighborMinX < neededRight) {
                val shift = neededRight - rightNeighborMinX
                for ((k, r) in finalRects.entries.toList()) {
                    if (k != selfKey && r.x >= rightNeighborMinX - 0.5f && r.y < selfBand.bottom && selfBand.y < r.bottom) {
                        finalRects[k] = r.copy(x = r.x + shift)
                    }
                }
            }
        }

        // Helper: all namespace IDs enclosed by `rootNsId` (itself + descendants).
        fun enclosedNamespaceIds(rootNsId: String): Set<String> {
            val out = HashSet<String>()
            fun walk(id: String) {
                if (!out.add(id)) return
                for (child in diagram.namespaces) {
                    if (child.parentId == id) walk(child.id)
                }
            }
            walk(rootNsId)
            return out
        }

        // Build namespace cluster rectangles bottom-up in post-transform coordinates so
        // each namespace encloses its member classes, notes, and child namespaces, plus
        // top room for the namespace's title label. Pass 1 evicts any non-member nodes
        // (and sibling namespaces) that overlap a namespace box; Pass 2 recomputes all
        // namespace boxes from the final displaced node coordinates.
        val nsById = diagram.namespaces.associateBy { it.id }

        fun classWithSelfEnvelope(classId: String): DiagramRect? {
            val base = finalRects["c:$classId"] ?: return null
            var minX = base.x
            var minY = base.y
            var maxX = base.right
            var maxY = base.bottom
            for ((idx, rel) in diagram.relations.withIndex()) {
                if (rel.from != classId || rel.to != classId) continue
                val selfOrd = selfOrdByRelIdx[idx] ?: 0
                val lSize = relationLabelSize(idx, rel)
                val fromCardSz = rel.fromCardinality?.let { cardinalitySize(idx, true, it) }
                val toCardSz = rel.toCardinality?.let { cardinalitySize(idx, false, it) }
                val outX = max((28f + selfOrd * 22f) * options.density, (fromCardSz?.width ?: 0f) + 12f * options.density)
                val outY = (28f + selfOrd * 20f) * options.density
                val endX = (base.centerX + base.width / 4f - selfOrd * 14f * options.density).coerceAtLeast(base.x + 8f * options.density)
                val labelRight = base.right + outX + (lSize?.let { 4f * options.density + it.width } ?: 0f)
                val fromCardRight = fromCardSz?.let { base.right + 4f * options.density + it.width } ?: base.right
                val toCardRight = toCardSz?.let { endX + 4f * options.density + it.width } ?: base.right
                val topY = base.y - max(outY, (toCardSz?.height ?: 0f) + 8f * options.density)
                val startY = (base.centerY + selfOrd * 10f * options.density).coerceAtMost(base.bottom - 6f * options.density)
                val fromCardBottom = fromCardSz?.let { startY + 4f * options.density + it.height } ?: base.bottom
                val labelBottom = lSize?.let {
                    base.y + (selfLabelOffsetYByRelIdx[idx] ?: 0f) + it.height
                } ?: base.bottom
                minY = min(minY, topY)
                maxX = maxOf(maxX, labelRight, fromCardRight, toCardRight)
                maxY = maxOf(maxY, labelBottom, fromCardBottom)
            }
            return DiagramRect(minX, minY, maxX - minX, maxY - minY)
        }

        fun externalNodeEnvelope(
            baseRect: DiagramRect,
            classId: String?,
            nodeNsId: String?,
            memberNsIds: Set<String>,
        ): DiagramRect {
            val origBase = if (classId != null) finalRects["c:$classId"] ?: baseRect else baseRect
            val dx = baseRect.x - origBase.x
            val dy = baseRect.y - origBase.y
            var env = if (classId != null) {
                val rawEnv = classWithSelfEnvelope(classId) ?: baseRect
                rawEnv.copy(x = rawEnv.x + dx, y = rawEnv.y + dy)
            } else {
                baseRect
            }
            var cur = nodeNsId
            while (cur != null && cur !in memberNsIds) {
                val curMembers = enclosedNamespaceIds(cur)
                var minX = env.x
                var minY = env.y
                var maxX = env.right
                var maxY = env.bottom
                for (mc in diagram.classes) {
                    if (mc.namespaceId in curMembers) {
                        val mEnv = classWithSelfEnvelope(mc.id)?.let {
                            if (mc.id == classId) it.copy(x = it.x + dx, y = it.y + dy) else it
                        } ?: continue
                        minX = min(minX, mEnv.x)
                        minY = min(minY, mEnv.y)
                        maxX = max(maxX, mEnv.right)
                        maxY = max(maxY, mEnv.bottom)
                    }
                }
                for (mn in diagram.notes) {
                    if (mn.namespaceId in curMembers) {
                        val mRect = finalRects["n:${mn.id}"] ?: continue
                        minX = min(minX, mRect.x)
                        minY = min(minY, mRect.y)
                        maxX = max(maxX, mRect.right)
                        maxY = max(maxY, mRect.bottom)
                    }
                }
                val titleSz = nsLabelSizes[cur] ?: DiagramSize(0f, 0f)
                val padX = options.subgraphPaddingX
                val padBottom = options.subgraphPaddingY * 0.65f
                val padTop = max(options.subgraphPaddingY, titleSz.height + 12f * options.density)
                var left = minX - padX
                var right = maxX + padX
                val minW = titleSz.width + padX * 2f
                if (right - left < minW) {
                    val mid = (left + right) / 2f
                    left = mid - minW / 2f
                    right = mid + minW / 2f
                }
                val top = minY - padTop
                val bottom = maxY + padBottom
                env = DiagramRect(left, top, right - left, bottom - top)
                cur = nsById[cur]?.parentId
            }
            return env
        }

        val builtNsRects = HashMap<String, DiagramRect>()
        fun buildNamespaceRect(ns: ClassNamespace, evict: Boolean): DiagramRect? {
            builtNsRects[ns.id]?.let { return it }
            val childNsRects = diagram.namespaces.filter { it.parentId == ns.id }.mapNotNull { buildNamespaceRect(it, evict) }
            val directClassRects = diagram.classes.filter { it.namespaceId == ns.id }.mapNotNull { classWithSelfEnvelope(it.id) }
            val directNoteRects = diagram.notes.filter { it.namespaceId == ns.id }.mapNotNull { finalRects["n:${it.id}"] }
            val syntheticLeaf = if (directClassRects.isEmpty() && directNoteRects.isEmpty() && childNsRects.isEmpty()) {
                listOfNotNull(finalRects["ns:${ns.id}"])
            } else emptyList()
            val memberRects = directClassRects + directNoteRects + childNsRects + syntheticLeaf
            if (memberRects.isEmpty()) return null
            val titleSz = nsLabelSizes[ns.id] ?: DiagramSize(0f, 0f)
            val padX = options.subgraphPaddingX
            val padBottom = options.subgraphPaddingY * 0.65f
            val padTop = max(options.subgraphPaddingY, titleSz.height + 12f * options.density)
            var left = memberRects.minOf { it.x } - padX
            var right = memberRects.maxOf { it.right } + padX
            val minW = titleSz.width + padX * 2f
            if (right - left < minW) {
                val mid = (left + right) / 2f
                left = mid - minW / 2f
                right = mid + minW / 2f
            }
            val top = memberRects.minOf { it.y } - padTop
            val bottom = memberRects.maxOf { it.bottom } + padBottom
            val nsRect = DiagramRect(left, top, right - left, bottom - top)

            if (evict) {
                val memberNsIds = enclosedNamespaceIds(ns.id)
                val crossBoundaryLabelClearance = diagram.relations.withIndex()
                    .filter { (_, rel) ->
                        rel.from != rel.to &&
                            ((nsByClass[rel.from] in memberNsIds) != (nsByClass[rel.to] in memberNsIds))
                    }
                    .maxOfOrNull { (idx, rel) ->
                        val lW = relationLabelSize(idx, rel)?.width ?: 0f
                        val cFromW = rel.fromCardinality?.let { cardinalitySize(idx, true, it).width } ?: 0f
                        val cToW = rel.toCardinality?.let { cardinalitySize(idx, false, it).width } ?: 0f
                        if (lW > 0f || cFromW > 0f || cToW > 0f) {
                            lW + cFromW + cToW + 16f * options.density
                        } else 0f
                    } ?: 0f
                val gap = max(options.nodeGap * 0.5f, crossBoundaryLabelClearance)
                data class NonMember(
                    val key: String,
                    val classId: String?,
                    val nodeNsId: String?,
                    val rect: DiagramRect,
                    val paddedRect: DiagramRect,
                )
                val nonMembers = buildList {
                    for (c in diagram.classes) {
                        if (c.namespaceId in memberNsIds) continue
                        val k = "c:${c.id}"
                        val r = finalRects[k] ?: continue
                        add(NonMember(k, c.id, c.namespaceId, r, externalNodeEnvelope(r, c.id, c.namespaceId, memberNsIds)))
                    }
                    for (n in diagram.notes) {
                        if (n.namespaceId in memberNsIds) continue
                        val k = "n:${n.id}"
                        val r = finalRects[k] ?: continue
                        add(NonMember(k, null, n.namespaceId, r, externalNodeEnvelope(r, null, n.namespaceId, memberNsIds)))
                    }
                    for (emptyNs in leafEmptyNamespaces) {
                        if (emptyNs.id in memberNsIds) continue
                        val k = "ns:${emptyNs.id}"
                        val r = finalRects[k] ?: continue
                        add(NonMember(k, null, emptyNs.id, r, externalNodeEnvelope(r, null, emptyNs.id, memberNsIds)))
                    }
                }
                fun verticalOverlap(a: DiagramRect, b: DiagramRect): Boolean =
                    a.y < b.bottom && b.y < a.bottom

                val overlappingRight = nonMembers
                    .filter { rectsOverlap(it.paddedRect, nsRect) && it.rect.centerX >= nsRect.centerX }
                    .minByOrNull { it.paddedRect.x }
                if (overlappingRight != null) {
                    val minOverlapX = overlappingRight.paddedRect.x
                    val shiftRight = (nsRect.right + gap) - minOverlapX
                    if (shiftRight > 0f) {
                        for (nm in nonMembers) {
                            val r = finalRects[nm.key] ?: continue
                            if (nm.paddedRect.x >= minOverlapX - 0.5f && verticalOverlap(nm.paddedRect, nsRect)) {
                                finalRects[nm.key] = r.copy(x = r.x + shiftRight)
                            }
                        }
                    }
                }

                val overlappingLeft = nonMembers
                    .mapNotNull { nm ->
                        val r = finalRects[nm.key] ?: return@mapNotNull null
                        nm.copy(rect = r, paddedRect = externalNodeEnvelope(r, nm.classId, nm.nodeNsId, memberNsIds))
                    }
                    .filter { rectsOverlap(it.paddedRect, nsRect) && it.rect.centerX < nsRect.centerX }
                    .maxByOrNull { it.paddedRect.right }
                if (overlappingLeft != null) {
                    val maxOverlapRight = overlappingLeft.paddedRect.right
                    val shiftLeft = maxOverlapRight - (nsRect.x - gap)
                    if (shiftLeft > 0f) {
                        for (nm in nonMembers) {
                            val r = finalRects[nm.key] ?: continue
                            val env = externalNodeEnvelope(r, nm.classId, nm.nodeNsId, memberNsIds)
                            if (env.right <= maxOverlapRight + 0.5f && verticalOverlap(env, nsRect)) {
                                finalRects[nm.key] = r.copy(x = r.x - shiftLeft)
                            }
                        }
                    }
                }
            }

            return nsRect.also { builtNsRects[ns.id] = it }
        }
        // Emit namespaces parent-before-child so outer cluster backgrounds draw behind inner ones.
        fun nsDepth(ns: ClassNamespace): Int {
            var d = 0
            var p = ns.parentId
            while (p != null) {
                d++
                p = nsById[p]?.parentId
            }
            return d
        }
        val sortedNamespaces = diagram.namespaces.sortedBy { nsDepth(it) }
        for (ns in sortedNamespaces) {
            buildNamespaceRect(ns, evict = true)
        }
        builtNsRects.clear()
        val positionedNamespaces = sortedNamespaces.mapNotNull { ns ->
            val rect = buildNamespaceRect(ns, evict = false) ?: return@mapNotNull null
            val titleSz = nsLabelSizes[ns.id] ?: DiagramSize(0f, 0f)
            val headerRect = if (titleSz.width > 0f && titleSz.height > 0f) {
                DiagramRect(
                    x = rect.centerX - titleSz.width / 2f,
                    y = rect.y,
                    width = titleSz.width,
                    height = titleSz.height + 4f * options.density,
                )
            } else {
                DiagramRect(rect.centerX, rect.y, 0f, 0f)
            }
            PositionedClassNamespace(ns, rect, headerRect)
        }

        val positionedClasses = diagram.classes.map { c ->
            val m = metricsById.getValue(c.id)
            val rect = finalRects.getValue("c:${c.id}")
            PositionedClassNode(
                node = c,
                rect = rect,
                headerHeight = m.headerHeight,
                attributesHeight = m.attributesHeight,
                methodsHeight = m.methodsHeight,
            )
        }
        val classRectById = positionedClasses.associate { it.node.id to it.rect }

        val positionedNotes = diagram.notes.mapIndexed { idx, note ->
            val nRect = finalRects.getValue("n:${note.id}")
            val targetRect = note.forClassId?.let { classRectById[it] }
            val connPts = if (targetRect != null) {
                val pts = mutableListOf(
                    DiagramPoint(nRect.centerX, nRect.centerY),
                    DiagramPoint(targetRect.centerX, targetRect.centerY),
                )
                pts[0] = NodeOutline.intersect(NodeShape.Rect, nRect, pts[1], options.density)
                pts[pts.lastIndex] = NodeOutline.intersect(NodeShape.Rect, targetRect, pts[pts.lastIndex - 1], options.density)
                pts
            } else emptyList()
            PositionedClassNote(idx, note, nRect, connPts)
        }

        val positionedRelations = diagram.relations.mapIndexed { idx, rel ->
            val fromRect = classRectById[rel.from]
            val toRect = classRectById[rel.to]
            val lSize = relationLabelSize(idx, rel)
            if (fromRect == null || toRect == null) {
                return@mapIndexed PositionedClassRelation(idx, rel, emptyList(), null, null, null)
            }
            if (rel.from == rel.to) {
                val selfOrd = selfOrdByRelIdx[idx] ?: 0
                val fromCardSz = rel.fromCardinality?.let { cardinalitySize(idx, true, it) }
                val toCardSz = rel.toCardinality?.let { cardinalitySize(idx, false, it) }
                val outX = max((28f + selfOrd * 22f) * options.density, (fromCardSz?.width ?: 0f) + 12f * options.density)
                val outY = (28f + selfOrd * 20f) * options.density
                val startY = (fromRect.centerY + selfOrd * 10f * options.density).coerceAtMost(fromRect.bottom - 6f * options.density)
                val endX = (fromRect.centerX + fromRect.width / 4f - selfOrd * 14f * options.density).coerceAtLeast(fromRect.x + 8f * options.density)
                val pts = listOf(
                    DiagramPoint(fromRect.right, startY),
                    DiagramPoint(fromRect.right + outX, startY),
                    DiagramPoint(fromRect.right + outX, fromRect.y - outY),
                    DiagramPoint(endX, fromRect.y - outY),
                    DiagramPoint(endX, fromRect.y),
                )
                val fromCardRect = fromCardSz?.let { cSz ->
                    DiagramRect(fromRect.right + 4f * options.density, startY + 4f * options.density, cSz.width, cSz.height)
                }
                val toCardRect = toCardSz?.let { cSz ->
                    DiagramRect(endX + 4f * options.density, fromRect.y - cSz.height - 4f * options.density, cSz.width, cSz.height)
                }
                val labelRect = lSize?.let {
                    val labelY = fromRect.y + (selfLabelOffsetYByRelIdx[idx] ?: 0f)
                    DiagramRect(fromRect.right + outX + 4f * options.density, labelY, it.width, it.height)
                }
                return@mapIndexed PositionedClassRelation(idx, rel, pts, labelRect, fromCardRect, toCardRect)
            }

            val origFrom = origRects.getValue("c:${rel.from}")
            val origTo = origRects.getValue("c:${rel.to}")
            val dxFrom = fromRect.centerX - origFrom.centerX
            val dyFrom = fromRect.centerY - origFrom.centerY
            val dxTo = toRect.centerX - origTo.centerX
            val dyTo = toRect.centerY - origTo.centerY
            val orderReversed =
                ((origTo.centerX - origFrom.centerX) * (toRect.centerX - fromRect.centerX) < -1f) ||
                    ((origTo.centerY - origFrom.centerY) * (toRect.centerY - fromRect.centerY) < -1f)

            val path = dagreResult.paths["r$idx"]
            val pts = if (orderReversed) {
                mutableListOf(
                    DiagramPoint(fromRect.centerX, fromRect.centerY),
                    DiagramPoint(toRect.centerX, toRect.centerY),
                )
            } else {
                val rawPts = path?.points?.map { transformPoint(DiagramPoint(it.first, it.second)) }
                    ?.takeIf { it.size >= 2 }
                    ?: listOf(DiagramPoint(fromRect.centerX, fromRect.centerY), DiagramPoint(toRect.centerX, toRect.centerY))
                rawPts.mapIndexed { k, p ->
                    val t = if (rawPts.size > 1) k.toFloat() / rawPts.lastIndex else 0.5f
                    DiagramPoint(
                        x = p.x + dxFrom + (dxTo - dxFrom) * t,
                        y = p.y + dyFrom + (dyTo - dyFrom) * t,
                    )
                }.toMutableList()
            }
            pts[0] = NodeOutline.intersect(NodeShape.Rect, fromRect, pts[1], options.density)
            pts[pts.lastIndex] = NodeOutline.intersect(NodeShape.Rect, toRect, pts[pts.lastIndex - 1], options.density)

            var rerouted = orderReversed

            // Route around any unrelated class/note boxes or namespace headers that lie across the relation path.
            data class DetourObstacle(val innerRect: DiagramRect, val guardRect: DiagramRect)
            val detourObstacles = buildList {
                val pad = 8f * options.density
                for (pc in positionedClasses) {
                    if (pc.node.id != rel.from && pc.node.id != rel.to) {
                        val obs = pc.rect
                        add(
                            DetourObstacle(
                                innerRect = obs,
                                guardRect = DiagramRect(obs.x - pad, obs.y - pad, obs.width + pad * 2f, obs.height + pad * 2f),
                            ),
                        )
                    }
                }
                for (pn in positionedNotes) {
                    val obs = pn.rect
                    add(
                        DetourObstacle(
                            innerRect = obs,
                            guardRect = DiagramRect(obs.x - pad, obs.y - pad, obs.width + pad * 2f, obs.height + pad * 2f),
                        ),
                    )
                }
                for (ns in positionedNamespaces) {
                    val hdr = ns.headerRect
                    if (hdr.width <= 0f || hdr.height <= 0f) continue
                    val clearPadX = min(8f * options.density, max(4f * options.density, (ns.rect.width - hdr.width) * 0.25f))
                    val clearPadY = 6f * options.density
                    add(
                        DetourObstacle(
                            innerRect = hdr,
                            guardRect = DiagramRect(
                                x = hdr.x - clearPadX,
                                y = hdr.y - clearPadY,
                                width = hdr.width + clearPadX * 2f,
                                height = hdr.height + clearPadY * 2f,
                            ),
                        ),
                    )
                }
            }

            repeat(2) {
                var passChanged = false
                for (obstacle in detourObstacles) {
                    if (detourAroundRect(pts, obstacle.guardRect, obstacle.innerRect, options.density)) {
                        rerouted = true
                        passChanged = true
                    }
                }
                if ((passChanged || rerouted) && pts.size >= 2) {
                    val newStart = NodeOutline.intersect(NodeShape.Rect, fromRect, pts[1], options.density)
                    if (detourObstacles.none { segmentIntersectsRect(newStart, pts[1], it.innerRect) }) {
                        pts[0] = newStart
                    }
                    val newEnd = NodeOutline.intersect(NodeShape.Rect, toRect, pts[pts.lastIndex - 1], options.density)
                    if (detourObstacles.none { segmentIntersectsRect(pts[pts.lastIndex - 1], newEnd, it.innerRect) }) {
                        pts[pts.lastIndex] = newEnd
                    }
                }
                if (!passChanged) return@repeat
            }

            fun avoidNamespaceHeaders(r: DiagramRect): DiagramRect {
                var candidate = r
                for (ns in positionedNamespaces) {
                    val hdr = ns.headerRect
                    if (hdr.width > 0f && hdr.height > 0f && rectsOverlap(candidate, hdr)) {
                        candidate = if (candidate.centerX <= hdr.centerX) {
                            candidate.copy(x = hdr.x - candidate.width - 4f * options.density)
                        } else {
                            candidate.copy(x = hdr.right + 4f * options.density)
                        }
                    }
                }
                return candidate
            }

            fun overlapsAnyBoxOrHeader(r: DiagramRect): Boolean {
                if (positionedClasses.any { rectsOverlap(r, it.rect) }) return true
                if (positionedNotes.any { rectsOverlap(r, it.rect) }) return true
                if (positionedNamespaces.any { it.headerRect.width > 0f && it.headerRect.height > 0f && rectsOverlap(r, it.headerRect) }) return true
                return false
            }

            val labelRect = lSize?.let { size ->
                val defaultCentre = if (!rerouted && path?.labelCentre != null) {
                    val base = transformPoint(DiagramPoint(path.labelCentre.first, path.labelCentre.second))
                    DiagramPoint(base.x + (dxFrom + dxTo) / 2f, base.y + (dyFrom + dyTo) / 2f)
                } else {
                    polylineMidpoint(pts)
                }
                var candidate = avoidNamespaceHeaders(
                    DiagramRect(defaultCentre.x - size.width / 2f, defaultCentre.y - size.height / 2f, size.width, size.height),
                )
                if (rerouted || overlapsAnyBoxOrHeader(candidate)) {
                    val segMidCandidates = (0 until pts.lastIndex)
                        .map { i ->
                            val a = pts[i]
                            val b = pts[i + 1]
                            val len = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                            val mid = DiagramPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            len to avoidNamespaceHeaders(DiagramRect(mid.x - size.width / 2f, mid.y - size.height / 2f, size.width, size.height))
                        }
                        .sortedByDescending { it.first }
                    val clearCandidate = segMidCandidates.firstOrNull { !overlapsAnyBoxOrHeader(it.second) }?.second
                    if (clearCandidate != null) {
                        candidate = clearCandidate
                    }
                }
                candidate
            }
            var fromCardRect = rel.fromCardinality?.let { card ->
                val cSz = cardinalitySize(idx, true, card)
                avoidNamespaceHeaders(placeCardinality(pts.first(), pts[1], fromRect, cSz, options.density))
            }
            var toCardRect = rel.toCardinality?.let { card ->
                val cSz = cardinalitySize(idx, false, card)
                avoidNamespaceHeaders(placeCardinality(pts.last(), pts[pts.lastIndex - 1], toRect, cSz, options.density))
            }
            PositionedClassRelation(idx, rel, pts, labelRect, fromCardRect, toCardRect)
        }

        // Normalize coordinates so minX/minY sit at options.padding, with optional title above.
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
        positionedClasses.forEach { includeRect(it.rect) }
        positionedNotes.forEach { n ->
            includeRect(n.rect)
            n.connectorPoints.forEach(::includePoint)
        }
        positionedNamespaces.forEach { includeRect(it.rect) }
        positionedRelations.forEach { r ->
            r.points.forEach(::includePoint)
            r.labelRect?.let(::includeRect)
            r.fromCardinalityRect?.let(::includeRect)
            r.toCardinalityRect?.let(::includeRect)
        }
        if (!minX.isFinite()) {
            minX = 0f; minY = 0f; maxX = 0f; maxY = 0f
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

        return ClassDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            direction = direction,
            titleRect = titleRect,
            classes = positionedClasses.map { it.copy(rect = it.rect.shifted()) },
            relations = positionedRelations.map { r ->
                r.copy(
                    points = r.points.map { it.shifted() },
                    labelRect = r.labelRect?.shifted(),
                    fromCardinalityRect = r.fromCardinalityRect?.shifted(),
                    toCardinalityRect = r.toCardinalityRect?.shifted(),
                )
            },
            notes = positionedNotes.map { n ->
                n.copy(
                    rect = n.rect.shifted(),
                    connectorPoints = n.connectorPoints.map { it.shifted() },
                )
            },
            namespaces = positionedNamespaces.map {
                it.copy(rect = it.rect.shifted(), headerRect = it.headerRect.shifted())
            },
        )
    }

    private fun placeCardinality(
        anchor: DiagramPoint,
        toward: DiagramPoint,
        endpointRect: DiagramRect,
        size: DiagramSize,
        density: Float,
    ): DiagramRect {
        val dx = toward.x - anchor.x
        val dy = toward.y - anchor.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / len
        val uy = dy / len
        // Normal perpendicular to the segment (consistent upward/rightward bias).
        var nx = -uy
        var ny = ux
        if (ny > 0f || (ny == 0f && nx < 0f)) {
            nx = -nx
            ny = -ny
        }
        val along = 12f * density + kotlin.math.abs(ux) * (size.width / 2f) + kotlin.math.abs(uy) * (size.height / 2f)
        val perp = 12f * density + kotlin.math.abs(nx) * (size.width / 2f) + kotlin.math.abs(ny) * (size.height / 2f)
        val cx = anchor.x + ux * along + nx * perp
        val cy = anchor.y + uy * along + ny * perp
        var rect = DiagramRect(cx - size.width / 2f, cy - size.height / 2f, size.width, size.height)
        if (rectsOverlap(rect, endpointRect)) {
            val pad = 4f * density
            rect = if (kotlin.math.abs(ux) >= kotlin.math.abs(uy)) {
                if (ux >= 0f) rect.copy(x = max(rect.x, endpointRect.right + pad))
                else rect.copy(x = min(rect.x, endpointRect.x - rect.width - pad))
            } else {
                if (uy >= 0f) rect.copy(y = max(rect.y, endpointRect.bottom + pad))
                else rect.copy(y = min(rect.y, endpointRect.y - rect.height - pad))
            }
        }
        return rect
    }

    private fun rectsOverlap(a: DiagramRect, b: DiagramRect): Boolean =
        a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    private fun detourAroundRect(
        pts: MutableList<DiagramPoint>,
        guardRect: DiagramRect,
        innerRect: DiagramRect,
        density: Float,
    ): Boolean {
        val hits = (0 until pts.lastIndex).any { i -> segmentIntersectsRect(pts[i], pts[i + 1], innerRect) }
        if (!hits) return false

        val approachMarginY = 12f * density
        var k = 1
        while (k < pts.lastIndex) {
            val p = pts[k]
            if (
                p.x in guardRect.x..guardRect.right &&
                p.y in (guardRect.y - approachMarginY)..(guardRect.bottom + approachMarginY)
            ) {
                pts.removeAt(k)
            } else {
                k++
            }
        }

        val tl = DiagramPoint(guardRect.x, guardRect.y)
        val tr = DiagramPoint(guardRect.right, guardRect.y)
        val br = DiagramPoint(guardRect.right, guardRect.bottom)
        val bl = DiagramPoint(guardRect.x, guardRect.bottom)
        val cw = listOf(tl, tr, br, bl)
        val ccw = listOf(tl, bl, br, tr)
        val candidates = buildList {
            add(listOf(tl))
            add(listOf(tr))
            add(listOf(br))
            add(listOf(bl))
            for (i in 0 until 4) {
                add(listOf(cw[i], cw[(i + 1) % 4]))
                add(listOf(ccw[i], ccw[(i + 1) % 4]))
            }
            for (i in 0 until 4) {
                add(listOf(cw[i], cw[(i + 1) % 4], cw[(i + 2) % 4]))
                add(listOf(ccw[i], ccw[(i + 1) % 4], ccw[(i + 2) % 4]))
            }
        }

        fun dist(a: DiagramPoint, b: DiagramPoint): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            return sqrt(dx * dx + dy * dy)
        }

        fun chainLen(p0: DiagramPoint, wp: List<DiagramPoint>, p1: DiagramPoint): Float {
            var sum = dist(p0, wp.first()) + dist(wp.last(), p1)
            for (i in 0 until wp.lastIndex) sum += dist(wp[i], wp[i + 1])
            return sum
        }

        var changed = false
        var segIdx = 0
        while (segIdx < pts.lastIndex) {
            val p0 = pts[segIdx]
            val p1 = pts[segIdx + 1]
            if (segmentIntersectsRect(p0, p1, innerRect)) {
                val bestWp = candidates
                    .filter { wp ->
                        !segmentIntersectsRect(p0, wp.first(), innerRect) &&
                            !segmentIntersectsRect(wp.last(), p1, innerRect)
                    }
                    .minByOrNull { wp -> chainLen(p0, wp, p1) }
                if (bestWp != null) {
                    val doubleWp = bestWp.flatMap { listOf(it, it) }
                    val testPts = buildList {
                        addAll(pts.subList(0, segIdx + 1))
                        addAll(doubleWp)
                        addAll(pts.subList(segIdx + 1, pts.size))
                    }
                    val samples = basisSplineSamples(testPts)
                    val doubleClear = (0 until samples.lastIndex).none { i ->
                        segmentIntersectsRect(samples[i], samples[i + 1], innerRect)
                    }
                    val inserted = if (doubleClear) doubleWp else bestWp.flatMap { listOf(it, it, it) }
                    for ((offset, pt) in inserted.withIndex()) {
                        pts.add(segIdx + 1 + offset, pt)
                    }
                    changed = true
                    segIdx += inserted.size + 1
                } else {
                    segIdx++
                }
            } else {
                segIdx++
            }
        }
        return changed
    }

    internal fun basisSplineSamples(points: List<DiagramPoint>, stepsPerSpan: Int = 16): List<DiagramPoint> {
        val n = points.size
        if (n <= 2) return points
        val out = ArrayList<DiagramPoint>((n * stepsPerSpan) + 2)
        val p0 = points[0]
        val p1 = points[1]
        out.add(p0)
        var curX = (5f * p0.x + p1.x) / 6f
        var curY = (5f * p0.y + p1.y) / 6f
        out.add(DiagramPoint(curX, curY))
        fun sampleCubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val x0 = curX
            val y0 = curY
            for (s in 1..stepsPerSpan) {
                val t = s.toFloat() / stepsPerSpan
                val u = 1f - t
                val bx = u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3
                val by = u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3
                out.add(DiagramPoint(bx, by))
            }
            curX = x3
            curY = y3
        }
        for (i in 1 until n - 1) {
            val a = points[i - 1]
            val b = points[i]
            val c = points[i + 1]
            sampleCubic(
                (2f * a.x + b.x) / 3f, (2f * a.y + b.y) / 3f,
                (a.x + 2f * b.x) / 3f, (a.y + 2f * b.y) / 3f,
                (a.x + 4f * b.x + c.x) / 6f, (a.y + 4f * b.y + c.y) / 6f,
            )
        }
        val prev = points[n - 2]
        val last = points[n - 1]
        sampleCubic(
            (2f * prev.x + last.x) / 3f, (2f * prev.y + last.y) / 3f,
            (prev.x + 2f * last.x) / 3f, (prev.y + 2f * last.y) / 3f,
            (prev.x + 5f * last.x) / 6f, (prev.y + 5f * last.y) / 6f,
        )
        out.add(last)
        return out
    }

    internal fun segmentIntersectsRect(a: DiagramPoint, b: DiagramPoint, r: DiagramRect): Boolean {
        if (r.width <= 0f || r.height <= 0f) return false
        val dx = b.x - a.x
        val dy = b.y - a.y
        var tMin = 0f
        var tMax = 1f
        fun clip(p: Float, q: Float): Boolean {
            if (kotlin.math.abs(p) < 1e-6f) return q >= 0f
            val t = q / p
            if (p < 0f) {
                if (t > tMax) return false
                if (t > tMin) tMin = t
            } else {
                if (t < tMin) return false
                if (t < tMax) tMax = t
            }
            return true
        }
        return clip(-dx, a.x - r.x) &&
            clip(dx, r.right - a.x) &&
            clip(-dy, a.y - r.y) &&
            clip(dy, r.bottom - a.y) &&
            tMin <= tMax
    }

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
