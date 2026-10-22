package dev.maradona.layout

import dev.maradona.model.Direction
import dev.maradona.model.NodeShape
import dev.maradona.model.StateDiagram
import dev.maradona.model.StateKind
import dev.maradona.model.StateNode
import dev.maradona.model.StateNote
import dev.maradona.model.StateNotePosition
import dev.maradona.model.StateTransition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class StateBoxMetrics(
    val width: Float,
    val height: Float,
    val headerHeight: Float,
    val descriptionHeight: Float = 0f,
)

data class PositionedStateNode(
    val state: StateNode,
    val rect: DiagramRect,
    val headerHeight: Float,
    val headerRect: DiagramRect = DiagramRect(rect.x, rect.y, rect.width, headerHeight),
    val regionDividers: List<Pair<DiagramPoint, DiagramPoint>> = emptyList(),
)

data class PositionedStateTransition(
    val index: Int,
    val transition: StateTransition,
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect?,
)

data class PositionedStateNote(
    val index: Int,
    val note: StateNote,
    val rect: DiagramRect,
    val connectorPoints: List<DiagramPoint>,
)

data class StateDiagramLayout(
    val width: Float,
    val height: Float,
    val direction: Direction,
    val titleRect: DiagramRect?,
    val states: List<PositionedStateNode>,
    val transitions: List<PositionedStateTransition>,
    val notes: List<PositionedStateNote>,
) {
    fun stateNode(id: String): PositionedStateNode? = states.firstOrNull { it.state.id == id }
}

/**
 * Recursive bottom-up Sugiyama (Dagre) layout for Mermaid `stateDiagram` and `stateDiagram-v2`.
 *
 * Each composite state lays out its internal regions first so its exact outer bounding box
 * (including its header band, internal states, notes, and self-transition loops/labels) is
 * treated as a single solid node by its parent scope's Dagre pass.
 */
object StateLayout {

    private class RegionPlacement(
        val width: Float,
        val height: Float,
        val stateRects: MutableMap<String, DiagramRect>,
        val stateHeaderHeights: MutableMap<String, Float>,
        val stateTitleRects: MutableMap<String, DiagramRect>,
        val stateDividers: MutableMap<String, List<Pair<DiagramPoint, DiagramPoint>>>,
        val noteRects: MutableMap<Int, DiagramRect>,
        val origStateRects: MutableMap<String, DiagramRect>,
        val dagrePaths: MutableMap<Int, dev.maradona.layout.dagre.Dagre.EdgePath>,
    ) {
        fun translate(dx: Float, dy: Float) {
            if (dx == 0f && dy == 0f) return
            for ((k, r) in stateRects.entries.toList()) {
                stateRects[k] = r.copy(x = r.x + dx, y = r.y + dy)
            }
            for ((k, r) in stateTitleRects.entries.toList()) {
                stateTitleRects[k] = r.copy(x = r.x + dx, y = r.y + dy)
            }
            for ((k, divs) in stateDividers.entries.toList()) {
                stateDividers[k] = divs.map { (a, b) ->
                    DiagramPoint(a.x + dx, a.y + dy) to DiagramPoint(b.x + dx, b.y + dy)
                }
            }
            for ((k, r) in noteRects.entries.toList()) {
                noteRects[k] = r.copy(x = r.x + dx, y = r.y + dy)
            }
            for ((k, r) in origStateRects.entries.toList()) {
                origStateRects[k] = r.copy(x = r.x + dx, y = r.y + dy)
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
        diagram: StateDiagram,
        stateBoxSize: (StateNode) -> StateBoxMetrics = { s -> defaultStateMetrics(s, 1f) },
        transitionLabelSize: (Int, StateTransition) -> DiagramSize? = { _, tr ->
            tr.label?.takeIf { it.isNotEmpty() }?.let { lbl ->
                val lines = lbl.lines()
                DiagramSize(lines.maxOf { it.length } * 7.5f + 10f, lines.size * 16f + 4f)
            }
        },
        noteBoxSize: (Int, StateNote) -> DiagramSize = { _, note ->
            val lines = note.text.lines()
            DiagramSize(max(80f, lines.maxOf { it.length } * 7f + 20f), max(32f, lines.size * 16f + 12f))
        },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(nodeGap = 44f, layerGap = 48f),
    ): StateDiagramLayout {
        val rootDirection = options.directionOverride ?: diagram.direction
        if (diagram.states.isEmpty() && diagram.notes.isEmpty()) {
            return StateDiagramLayout(
                width = 0f,
                height = 0f,
                direction = rootDirection,
                titleRect = null,
                states = emptyList(),
                transitions = emptyList(),
                notes = emptyList(),
            )
        }

        val stateById = diagram.states.associateBy { it.id }
        val baseMetricsById = diagram.states.associate { it.id to stateBoxSize(it) }
        val noteSizes = diagram.notes.mapIndexed { idx, n -> noteBoxSize(idx, n) }

        // Precompute cumulative self-transition ordinals and label Y offsets per state.
        val selfOrdByTransIdx = HashMap<Int, Int>()
        val selfLabelOffsetYByTransIdx = HashMap<Int, Float>()
        val selfCountByState = HashMap<String, Int>()
        val selfCumLabelOffsetYByState = HashMap<String, Float>()
        for ((idx, tr) in diagram.transitions.withIndex()) {
            if (tr.from == tr.to) {
                val ord = selfCountByState[tr.from] ?: 0
                selfOrdByTransIdx[idx] = ord
                selfCountByState[tr.from] = ord + 1
                val curOff = selfCumLabelOffsetYByState[tr.from] ?: 0f
                selfLabelOffsetYByTransIdx[idx] = curOff
                val lH = transitionLabelSize(idx, tr)?.height ?: (16f * options.density)
                selfCumLabelOffsetYByState[tr.from] = curOff + lH + 6f * options.density
            }
        }

        // Resolve the effective `(parentId, regionIndex)` for each note (inheriting from `forStateId` when present).
        fun noteScope(note: StateNote): Pair<String?, Int> {
            val target = note.forStateId?.let { stateById[it] }
            return if (target != null) {
                target.parentId to target.regionIndex
            } else {
                note.parentId to note.regionIndex
            }
        }

        fun isComposite(s: StateNode): Boolean =
            s.kind == StateKind.Composite ||
                diagram.states.any { it.parentId == s.id } ||
                diagram.notes.any { noteScope(it).first == s.id }

        // Lift `stateId` through `parentId` links until its parent is `scopeParentId` (in `scopeRegion`).
        fun liftToScope(stateId: String, scopeParentId: String?, scopeRegion: Int): String? {
            var cur = stateById[stateId] ?: return null
            val visited = HashSet<String>()
            while (cur.parentId != scopeParentId) {
                if (!visited.add(cur.id)) return null
                val nextParent = cur.parentId ?: return null
                cur = stateById[nextParent] ?: return null
            }
            if (scopeParentId != null && cur.regionIndex != scopeRegion) return null
            return cur.id
        }

        fun selfEnvelopeForRect(stateId: String, base: DiagramRect): DiagramRect {
            var minX = base.x
            var minY = base.y
            var maxX = base.right
            var maxY = base.bottom
            for ((idx, tr) in diagram.transitions.withIndex()) {
                if (tr.from != stateId || tr.to != stateId) continue
                val selfOrd = selfOrdByTransIdx[idx] ?: 0
                val lSize = transitionLabelSize(idx, tr)
                val outX = (28f + selfOrd * 22f) * options.density
                val outY = (28f + selfOrd * 20f) * options.density
                val labelRight = base.right + outX + (lSize?.let { 4f * options.density + it.width } ?: 0f)
                val labelBottom = lSize?.let {
                    base.y + (selfLabelOffsetYByTransIdx[idx] ?: 0f) + it.height
                } ?: base.bottom
                minY = min(minY, base.y - outY)
                maxX = max(maxX, labelRight)
                maxY = max(maxY, labelBottom)
            }
            return DiagramRect(minX, minY, maxX - minX, maxY - minY)
        }

        val compositePlacements = HashMap<String, RegionPlacement>()
        val compositeSizes = HashMap<String, DiagramSize>()
        val compositeHeaderHeights = HashMap<String, Float>()
        val compositeTitleWidths = HashMap<String, Float>()
        val compositeDividers = HashMap<String, List<Pair<DiagramPoint, DiagramPoint>>>()

        lateinit var layoutCompositeStateFn: (StateNode, Direction) -> Unit

        fun layoutRegion(
            scopeParentId: String?,
            scopeRegion: Int,
            scopeDirection: Direction,
        ): RegionPlacement {
            val directStates = diagram.states.filter {
                it.parentId == scopeParentId && (scopeParentId == null || it.regionIndex == scopeRegion)
            }
            val directNotes = diagram.notes.withIndex().filter { (_, n) ->
                val (pId, reg) = noteScope(n)
                pId == scopeParentId && (scopeParentId == null || reg == scopeRegion)
            }

            // First, recursively lay out any composite child states in this scope.
            for (child in directStates) {
                if (isComposite(child) && child.id !in compositeSizes) {
                    layoutCompositeStateFn(child, scopeDirection)
                }
            }

            val swapped = scopeDirection == Direction.LR || scopeDirection == Direction.RL
            fun DiagramSize.maybeSwapped() = if (swapped) DiagramSize(height, width) else this

            val standaloneNotes = directNotes.filter { (_, n) -> n.forStateId == null }
            val attachedNotes = directNotes.filter { (_, n) -> n.forStateId != null }

            val dagreNodes = LinkedHashMap<String, Pair<Float, Float>>()
            for (s in directStates) {
                val sz = if (isComposite(s)) {
                    compositeSizes.getValue(s.id)
                } else {
                    val m = baseMetricsById.getValue(s.id)
                    if ((s.kind == StateKind.Fork || s.kind == StateKind.Join) && swapped) {
                        DiagramSize(m.height, m.width)
                    } else {
                        DiagramSize(m.width, m.height)
                    }
                }.maybeSwapped()
                dagreNodes["s:${s.id}"] = sz.width to sz.height
            }
            for ((noteIdx, n) in standaloneNotes) {
                val sz = noteSizes[noteIdx].maybeSwapped()
                dagreNodes["n:${n.id}"] = sz.width to sz.height
            }

            if (dagreNodes.isEmpty() && attachedNotes.isEmpty()) {
                return RegionPlacement(
                    width = 0f,
                    height = 0f,
                    stateRects = mutableMapOf(),
                    stateHeaderHeights = mutableMapOf(),
                    stateTitleRects = mutableMapOf(),
                    stateDividers = mutableMapOf(),
                    noteRects = mutableMapOf(),
                    origStateRects = mutableMapOf(),
                    dagrePaths = mutableMapOf(),
                )
            }

            val dagreEdges = mutableListOf<dev.maradona.layout.dagre.Dagre.EdgeSpec>()
            for ((idx, tr) in diagram.transitions.withIndex()) {
                val u = liftToScope(tr.from, scopeParentId, scopeRegion) ?: continue
                val v = liftToScope(tr.to, scopeParentId, scopeRegion) ?: continue
                if (u == v) continue
                val fromKey = "s:$u"
                val toKey = "s:$v"
                if (fromKey !in dagreNodes || toKey !in dagreNodes) continue
                val isDirect = tr.from == u && tr.to == v
                val lbl = transitionLabelSize(idx, tr)?.maybeSwapped()
                dagreEdges += dev.maradona.layout.dagre.Dagre.EdgeSpec(
                    from = fromKey,
                    to = toKey,
                    name = if (isDirect) "t$idx" else "lift$idx",
                    minlen = if (isDirect && !isComposite(stateById.getValue(u)) && !isComposite(stateById.getValue(v))) 1 else 2,
                    labelWidth = lbl?.width ?: 0f,
                    labelHeight = lbl?.height ?: 0f,
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

            var canonMaxX = 0f
            var canonMaxY = 0f
            for (r in rawRects.values) {
                canonMaxX = max(canonMaxX, r.right)
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

            // Reserve clearance above, below, and to the right of any direct state with self-transitions.
            val directStateIds = directStates.map { it.id }.toSet()
            for ((idx, tr) in diagram.transitions.withIndex()) {
                if (tr.from != tr.to || tr.from !in directStateIds) continue
                val selfOrd = selfOrdByTransIdx[idx] ?: 0
                val selfKey = "s:${tr.from}"
                var selfRect = finalRects[selfKey] ?: continue
                val lSize = transitionLabelSize(idx, tr)
                val outX = (28f + selfOrd * 22f) * options.density
                val outY = (28f + selfOrd * 20f) * options.density
                val labelRight = selfRect.right + outX + (lSize?.let { 4f * options.density + it.width } ?: 0f)
                val gap = options.nodeGap * 0.5f
                val neededRight = labelRight + gap
                val neededTopY = selfRect.y - outY

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

                val labelBottom = lSize?.let {
                    selfRect.y + (selfLabelOffsetYByTransIdx[idx] ?: 0f) + it.height
                } ?: selfRect.bottom
                val neededBottomY = max(selfRect.bottom, labelBottom)
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

                val selfBand = DiagramRect(selfRect.x, selfRect.y - outY, neededRight - selfRect.x, neededBottomY - (selfRect.y - outY))
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

            // Place attached notes physically to the left or right of their target state and reserve horizontal clearance.
            val noteOffsetYByTargetSide = HashMap<Pair<String, StateNotePosition>, Float>()
            for ((noteIdx, n) in attachedNotes) {
                val targetId = n.forStateId?.let { liftToScope(it, scopeParentId, scopeRegion) } ?: continue
                val targetKey = "s:$targetId"
                val targetRect = finalRects[targetKey] ?: continue
                val targetEnv = selfEnvelopeForRect(targetId, targetRect)
                val nSz = noteSizes[noteIdx]
                val sideKey = targetId to n.position
                val curOffY = noteOffsetYByTargetSide[sideKey] ?: 0f
                noteOffsetYByTargetSide[sideKey] = curOffY + nSz.height + 8f * options.density
                val noteGapX = 24f * options.density
                val gap = options.nodeGap * 0.5f
                val noteY = targetRect.centerY - nSz.height / 2f + curOffY
                val noteX = if (n.position == StateNotePosition.Left) {
                    targetEnv.x - noteGapX - nSz.width
                } else {
                    targetEnv.right + noteGapX
                }
                val nRect = DiagramRect(noteX, noteY, nSz.width, nSz.height)
                if (n.position == StateNotePosition.Right) {
                    val neededRight = nRect.right + gap
                    val rightNeighborMinX = finalRects.entries
                        .filter { (k, r) -> k != targetKey && r.x > targetRect.x && r.y < nRect.bottom && nRect.y < r.bottom }
                        .minOfOrNull { it.value.x }
                    if (rightNeighborMinX != null && rightNeighborMinX < neededRight) {
                        val shift = neededRight - rightNeighborMinX
                        for ((k, r) in finalRects.entries.toList()) {
                            if (k != targetKey && r.x >= rightNeighborMinX - 0.5f && r.y < nRect.bottom && nRect.y < r.bottom) {
                                finalRects[k] = r.copy(x = r.x + shift)
                            }
                        }
                    }
                } else {
                    val neededLeft = nRect.x - gap
                    val leftNeighborMaxRight = finalRects.entries
                        .filter { (k, r) -> k != targetKey && r.right < targetRect.right && r.y < nRect.bottom && nRect.y < r.bottom }
                        .maxOfOrNull { it.value.right }
                    if (leftNeighborMaxRight != null && leftNeighborMaxRight > neededLeft) {
                        val shift = leftNeighborMaxRight - neededLeft
                        for ((k, r) in finalRects.entries.toList()) {
                            if (k != targetKey && r.right <= leftNeighborMaxRight + 0.5f && r.y < nRect.bottom && nRect.y < r.bottom) {
                                finalRects[k] = r.copy(x = r.x - shift)
                            }
                        }
                    }
                }
                finalRects["n:${n.id}"] = nRect
            }

            // Collect all state rects, note rects, and transformed dagre paths into this RegionPlacement.
            val stateRects = LinkedHashMap<String, DiagramRect>()
            val stateHeaderHeights = LinkedHashMap<String, Float>()
            val stateTitleRects = LinkedHashMap<String, DiagramRect>()
            val stateDividers = LinkedHashMap<String, List<Pair<DiagramPoint, DiagramPoint>>>()
            val noteRects = LinkedHashMap<Int, DiagramRect>()
            val origStateRects = LinkedHashMap<String, DiagramRect>()
            val dagrePaths = LinkedHashMap<Int, dev.maradona.layout.dagre.Dagre.EdgePath>()

            for ((idx, tr) in diagram.transitions.withIndex()) {
                val ep = dagreResult.paths["t$idx"] ?: continue
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

            for (s in directStates) {
                val r = finalRects.getValue("s:${s.id}")
                val oR = origRects.getValue("s:${s.id}")
                stateRects[s.id] = r
                origStateRects[s.id] = oR
                if (isComposite(s)) {
                    val hdrH = compositeHeaderHeights.getValue(s.id)
                    val titleW = compositeTitleWidths.getValue(s.id)
                    stateHeaderHeights[s.id] = hdrH
                    val padHdrY = min(4f * options.density, hdrH * 0.2f)
                    stateTitleRects[s.id] = DiagramRect(
                        x = r.centerX - titleW / 2f,
                        y = r.y + padHdrY,
                        width = titleW,
                        height = max(1f, hdrH - padHdrY * 2f),
                    )
                    val inner = compositePlacements.getValue(s.id)
                    inner.translate(r.x, r.y)
                    stateRects.putAll(inner.stateRects)
                    stateHeaderHeights.putAll(inner.stateHeaderHeights)
                    stateTitleRects.putAll(inner.stateTitleRects)
                    stateDividers.putAll(inner.stateDividers)
                    stateDividers[s.id] = (compositeDividers[s.id] ?: emptyList()).map { (a, b) ->
                        DiagramPoint(a.x + r.x, a.y + r.y) to DiagramPoint(b.x + r.x, b.y + r.y)
                    }
                    noteRects.putAll(inner.noteRects)
                    origStateRects.putAll(inner.origStateRects)
                    dagrePaths.putAll(inner.dagrePaths)
                } else {
                    val m = baseMetricsById.getValue(s.id)
                    stateHeaderHeights[s.id] = m.headerHeight
                    stateTitleRects[s.id] = DiagramRect(r.x, r.y, r.width, m.headerHeight)
                }
            }
            for ((noteIdx, n) in directNotes) {
                finalRects["n:${n.id}"]?.let { noteRects[noteIdx] = it }
            }

            // Normalize region coordinates so minX == 0 and minY == 0 (including self-loops, internal paths, and transition labels).
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (s in directStates) {
                val env = selfEnvelopeForRect(s.id, stateRects.getValue(s.id))
                minX = min(minX, env.x)
                minY = min(minY, env.y)
                maxX = max(maxX, env.right)
                maxY = max(maxY, env.bottom)
            }
            for ((noteIdx, _) in directNotes) {
                val r = noteRects[noteIdx] ?: continue
                minX = min(minX, r.x)
                minY = min(minY, r.y)
                maxX = max(maxX, r.right)
                maxY = max(maxY, r.bottom)
            }
            for ((idx, tr) in diagram.transitions.withIndex()) {
                if (tr.from == tr.to || tr.from !in directStateIds || tr.to !in directStateIds) continue
                val fR = stateRects[tr.from] ?: continue
                val tR = stateRects[tr.to] ?: continue
                val oF = origStateRects[tr.from] ?: fR
                val oT = origStateRects[tr.to] ?: tR
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
                val lSz = transitionLabelSize(idx, tr)
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

            val placement = RegionPlacement(
                width = max(0f, maxX - minX),
                height = max(0f, maxY - minY),
                stateRects = stateRects,
                stateHeaderHeights = stateHeaderHeights,
                stateTitleRects = stateTitleRects,
                stateDividers = stateDividers,
                noteRects = noteRects,
                origStateRects = origStateRects,
                dagrePaths = dagrePaths,
            )
            placement.translate(-minX, -minY)
            return placement
        }

        layoutCompositeStateFn = fun(comp: StateNode, inheritedDirection: Direction) {
            val compDirection = comp.direction ?: inheritedDirection
            val baseM = baseMetricsById.getValue(comp.id)
            val hdrH = max(26f * options.density, baseM.headerHeight)
            val titleW = max(40f * options.density, baseM.width - 24f * options.density)
            compositeHeaderHeights[comp.id] = hdrH
            compositeTitleWidths[comp.id] = titleW

            val maxChildReg = diagram.states
                .filter { it.parentId == comp.id }
                .maxOfOrNull { it.regionIndex } ?: 0
            val regCount = max(comp.regionCount, maxChildReg + 1)
            val regions = (0 until regCount).map { rIdx ->
                layoutRegion(comp.id, rIdx, compDirection)
            }

            val padX = options.subgraphPaddingX
            val padTop = 16f * options.density
            val padBottom = options.subgraphPaddingY * 0.65f
            val regionGap = 24f * options.density

            val combined = RegionPlacement(
                width = 0f,
                height = 0f,
                stateRects = mutableMapOf(),
                stateHeaderHeights = mutableMapOf(),
                stateTitleRects = mutableMapOf(),
                stateDividers = mutableMapOf(),
                noteRects = mutableMapOf(),
                origStateRects = mutableMapOf(),
                dagrePaths = mutableMapOf(),
            )

            val nonEmptyRegions = regions.filter { it.stateRects.isNotEmpty() || it.noteRects.isNotEmpty() }
            if (nonEmptyRegions.isEmpty()) {
                val w = max(120f * options.density, titleW + padX * 2f)
                val h = hdrH + 36f * options.density
                compositePlacements[comp.id] = combined
                compositeSizes[comp.id] = DiagramSize(w, h)
                compositeDividers[comp.id] = emptyList()
                return
            }

            val innerWidth = nonEmptyRegions.sumOf { it.width.toDouble() }.toFloat() +
                max(0, nonEmptyRegions.size - 1) * regionGap
            val innerHeight = nonEmptyRegions.maxOf { it.height }
            val compWidth = max(titleW + padX * 2f, innerWidth + padX * 2f)
            val compHeight = hdrH + padTop + innerHeight + padBottom

            val startX = (compWidth - innerWidth) / 2f
            var curX = startX
            val dividers = mutableListOf<Pair<DiagramPoint, DiagramPoint>>()
            for ((idx, reg) in nonEmptyRegions.withIndex()) {
                val regY = hdrH + padTop
                reg.translate(curX, regY)
                combined.stateRects.putAll(reg.stateRects)
                combined.stateHeaderHeights.putAll(reg.stateHeaderHeights)
                combined.stateTitleRects.putAll(reg.stateTitleRects)
                combined.stateDividers.putAll(reg.stateDividers)
                combined.noteRects.putAll(reg.noteRects)
                combined.origStateRects.putAll(reg.origStateRects)
                combined.dagrePaths.putAll(reg.dagrePaths)
                curX += reg.width
                if (idx < nonEmptyRegions.lastIndex) {
                    val divX = curX + regionGap / 2f
                    dividers += DiagramPoint(divX, hdrH) to DiagramPoint(divX, compHeight)
                    curX += regionGap
                }
            }

            compositePlacements[comp.id] = combined
            compositeSizes[comp.id] = DiagramSize(compWidth, compHeight)
            compositeDividers[comp.id] = dividers
        }

        fun stateDepth(s: StateNode): Int {
            var d = 0
            var p = s.parentId
            val visited = HashSet<String>()
            while (p != null && visited.add(p)) {
                d++
                p = stateById[p]?.parentId
            }
            return d
        }

        fun effectiveDirection(s: StateNode): Direction {
            val chain = mutableListOf<StateNode>()
            var cur: StateNode? = s
            val visited = HashSet<String>()
            while (cur != null && visited.add(cur.id)) {
                chain += cur
                cur = cur.parentId?.let { stateById[it] }
            }
            var dir = rootDirection
            for (anc in chain.asReversed()) {
                if (anc.direction != null) dir = anc.direction
            }
            return dir
        }

        // Lay out all composite states iteratively bottom-up (deepest first) so `layoutRegion`
        // never recurses and cannot overflow the call stack on deeply nested diagrams.
        val compositesDeepestFirst = diagram.states
            .filter { isComposite(it) }
            .sortedByDescending { stateDepth(it) }
        for (comp in compositesDeepestFirst) {
            layoutCompositeStateFn(comp, effectiveDirection(comp))
        }

        val rootPlacement = layoutRegion(null, 0, rootDirection)

        val orderedStates = diagram.states.sortedWith(
            compareBy<StateNode> { if (isComposite(it)) 0 else 1 }.thenBy { stateDepth(it) },
        )
        val positionedStates = orderedStates.mapNotNull { s ->
            val r = rootPlacement.stateRects[s.id] ?: return@mapNotNull null
            val hdrH = rootPlacement.stateHeaderHeights[s.id] ?: r.height
            val hdrRect = rootPlacement.stateTitleRects[s.id] ?: DiagramRect(r.x, r.y, r.width, hdrH)
            val divs = rootPlacement.stateDividers[s.id] ?: emptyList()
            PositionedStateNode(
                state = s,
                rect = r,
                headerHeight = hdrH,
                headerRect = hdrRect,
                regionDividers = divs,
            )
        }
        val stateNodeById = positionedStates.associateBy { it.state.id }

        fun ancestorsOf(stateId: String?): Set<String> {
            val out = HashSet<String>()
            var cur = stateId?.let { stateById[it]?.parentId }
            while (cur != null && out.add(cur)) {
                cur = stateById[cur]?.parentId
            }
            return out
        }

        fun outlineShapeFor(kind: StateKind): NodeShape = when (kind) {
            StateKind.Start, StateKind.End -> NodeShape.Circle
            StateKind.Choice -> NodeShape.Diamond
            StateKind.Normal, StateKind.Fork, StateKind.Join, StateKind.Composite -> NodeShape.RoundedRect
        }

        val positionedNotes = diagram.notes.mapIndexed { idx, note ->
            val nRect = rootPlacement.noteRects[idx] ?: DiagramRect(0f, 0f, noteSizes[idx].width, noteSizes[idx].height)
            val targetNode = note.forStateId?.let { stateNodeById[it] }
            val connPts = if (targetNode != null) {
                val targetRect = targetNode.rect
                val targetShape = outlineShapeFor(targetNode.state.kind)
                val pts = mutableListOf(
                    DiagramPoint(nRect.centerX, nRect.centerY),
                    DiagramPoint(targetRect.centerX, targetRect.centerY),
                )
                pts[0] = NodeOutline.intersect(NodeShape.Rect, nRect, pts[1], options.density)
                pts[pts.lastIndex] = NodeOutline.intersect(targetShape, targetRect, pts[pts.lastIndex - 1], options.density)

                val targetAncestors = ancestorsOf(targetNode.state.id) + setOfNotNull( noteScope(note).first )
                val pad = 8f * options.density
                val noteObstacles = buildList {
                    for (ps in positionedStates) {
                        if (ps.state.id == targetNode.state.id) continue
                        if (isComposite(ps.state) && ps.state.id in targetAncestors) {
                            val hdr = ps.headerRect
                            if (hdr.width > 0f && hdr.height > 0f) {
                                add(hdr to DiagramRect(hdr.x - pad, hdr.y - 6f * options.density, hdr.width + pad * 2f, hdr.height + 12f * options.density))
                            }
                        } else {
                            val obs = ps.rect
                            add(obs to DiagramRect(obs.x - pad, obs.y - pad, obs.width + pad * 2f, obs.height + pad * 2f))
                        }
                    }
                    for ((otherIdx, otherRect) in rootPlacement.noteRects) {
                        if (otherIdx != idx) {
                            add(otherRect to DiagramRect(otherRect.x - pad, otherRect.y - pad, otherRect.width + pad * 2f, otherRect.height + pad * 2f))
                        }
                    }
                }
                repeat(2) {
                    var passChanged = false
                    for ((innerRect, guardRect) in noteObstacles) {
                        if (ClassLayout.detourAroundRect(pts, guardRect, innerRect, options.density)) {
                            passChanged = true
                        }
                    }
                    if (passChanged && pts.size >= 2) {
                        val newStart = NodeOutline.intersect(NodeShape.Rect, nRect, pts[1], options.density)
                        if (noteObstacles.none { (inner, _) -> ClassLayout.segmentIntersectsRect(newStart, pts[1], inner) }) {
                            pts[0] = newStart
                        }
                        val newEnd = NodeOutline.intersect(targetShape, targetRect, pts[pts.lastIndex - 1], options.density)
                        if (noteObstacles.none { (inner, _) -> ClassLayout.segmentIntersectsRect(pts[pts.lastIndex - 1], newEnd, inner) }) {
                            pts[pts.lastIndex] = newEnd
                        }
                    }
                    if (!passChanged) return@repeat
                }
                pts
            } else emptyList()
            PositionedStateNote(idx, note, nRect, connPts)
        }

        val occupiedTextRects = mutableListOf<DiagramRect>().apply {
            for (ps in positionedStates) {
                if (isComposite(ps.state)) {
                    // Reserve the composite state's header band so labels never sit on its title or divider.
                    add(DiagramRect(ps.rect.x, ps.rect.y, ps.rect.width, ps.headerHeight))
                } else {
                    add(ps.rect)
                }
            }
            for (pn in positionedNotes) add(pn.rect)
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

        val positionedTransitions = diagram.transitions.mapIndexed { idx, tr ->
            val fromNode = stateNodeById[tr.from]
            val toNode = stateNodeById[tr.to]
            val lSize = transitionLabelSize(idx, tr)
            if (fromNode == null || toNode == null) {
                return@mapIndexed PositionedStateTransition(idx, tr, emptyList(), null)
            }
            val fromRect = fromNode.rect
            val toRect = toNode.rect
            val fromShape = outlineShapeFor(fromNode.state.kind)
            val toShape = outlineShapeFor(toNode.state.kind)

            if (tr.from == tr.to) {
                val selfOrd = selfOrdByTransIdx[idx] ?: 0
                val outX = (28f + selfOrd * 22f) * options.density
                val outY = (28f + selfOrd * 20f) * options.density
                val startY = (fromRect.centerY + selfOrd * 10f * options.density).coerceAtMost(fromRect.bottom - 6f * options.density)
                val endX = (fromRect.centerX + fromRect.width / 4f - selfOrd * 14f * options.density).coerceAtLeast(fromRect.x + 8f * options.density)
                val pts = mutableListOf(
                    DiagramPoint(fromRect.right, startY),
                    DiagramPoint(fromRect.right + outX, startY),
                    DiagramPoint(fromRect.right + outX, fromRect.y - outY),
                    DiagramPoint(endX, fromRect.y - outY),
                    DiagramPoint(endX, fromRect.y),
                )
                pts[0] = NodeOutline.intersect(fromShape, fromRect, pts[1], options.density)
                pts[pts.lastIndex] = NodeOutline.intersect(toShape, toRect, pts[pts.lastIndex - 1], options.density)
                val labelRect = lSize?.let {
                    val labelY = fromRect.y + (selfLabelOffsetYByTransIdx[idx] ?: 0f)
                    deconflictTextRect(
                        DiagramRect(fromRect.right + outX + 4f * options.density, labelY, it.width, it.height),
                    ).also(occupiedTextRects::add)
                }
                return@mapIndexed PositionedStateTransition(idx, tr, pts, labelRect)
            }

            val origFrom = rootPlacement.origStateRects[tr.from] ?: fromRect
            val origTo = rootPlacement.origStateRects[tr.to] ?: toRect
            val dxFrom = fromRect.centerX - origFrom.centerX
            val dyFrom = fromRect.centerY - origFrom.centerY
            val dxTo = toRect.centerX - origTo.centerX
            val dyTo = toRect.centerY - origTo.centerY
            val sameScope = fromNode.state.parentId == toNode.state.parentId &&
                fromNode.state.regionIndex == toNode.state.regionIndex
            val orderReversed = !sameScope ||
                ((origTo.centerX - origFrom.centerX) * (toRect.centerX - fromRect.centerX) < -1f) ||
                ((origTo.centerY - origFrom.centerY) * (toRect.centerY - fromRect.centerY) < -1f)

            val path = rootPlacement.dagrePaths[idx]
            val pts = if (orderReversed || path == null || path.points.size < 2) {
                mutableListOf(
                    DiagramPoint(fromRect.centerX, fromRect.centerY),
                    DiagramPoint(toRect.centerX, toRect.centerY),
                )
            } else {
                path.points.mapIndexed { k, (px, py) ->
                    val t = if (path.points.size > 1) k.toFloat() / path.points.lastIndex else 0.5f
                    DiagramPoint(
                        x = px + dxFrom + (dxTo - dxFrom) * t,
                        y = py + dyFrom + (dyTo - dyFrom) * t,
                    )
                }.toMutableList()
            }
            pts[0] = NodeOutline.intersect(fromShape, fromRect, pts[1], options.density)
            pts[pts.lastIndex] = NodeOutline.intersect(toShape, toRect, pts[pts.lastIndex - 1], options.density)

            var rerouted = orderReversed
            val endpointAncestors = ancestorsOf(tr.from) + ancestorsOf(tr.to)
            val pad = 8f * options.density
            val detourObstacles = buildList {
                for (ps in positionedStates) {
                    if (ps.state.id == tr.from || ps.state.id == tr.to) continue
                    if (isComposite(ps.state) && ps.state.id in endpointAncestors) {
                        val hdr = ps.headerRect
                        if (hdr.width > 0f && hdr.height > 0f) {
                            val clearPadX = min(8f * options.density, max(4f * options.density, (ps.rect.width - hdr.width) * 0.25f))
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
                        val obs = ps.rect
                        add(obs to DiagramRect(obs.x - pad, obs.y - pad, obs.width + pad * 2f, obs.height + pad * 2f))
                    }
                }
                for (pn in positionedNotes) {
                    val obs = pn.rect
                    add(obs to DiagramRect(obs.x - pad, obs.y - pad, obs.width + pad * 2f, obs.height + pad * 2f))
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
                    val newStart = NodeOutline.intersect(fromShape, fromRect, pts[1], options.density)
                    if (detourObstacles.none { (inner, _) -> ClassLayout.segmentIntersectsRect(newStart, pts[1], inner) }) {
                        pts[0] = newStart
                    }
                    val newEnd = NodeOutline.intersect(toShape, toRect, pts[pts.lastIndex - 1], options.density)
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

            PositionedStateTransition(idx, tr, pts, labelRect)
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
        positionedStates.forEach { includeRect(it.rect) }
        positionedNotes.forEach { n ->
            includeRect(n.rect)
            n.connectorPoints.forEach(::includePoint)
        }
        positionedTransitions.forEach { t ->
            t.points.forEach(::includePoint)
            t.labelRect?.let(::includeRect)
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

        return StateDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            direction = rootDirection,
            titleRect = titleRect,
            states = positionedStates.map { ps ->
                ps.copy(
                    rect = ps.rect.shifted(),
                    headerRect = ps.headerRect.shifted(),
                    regionDividers = ps.regionDividers.map { (a, b) -> a.shifted() to b.shifted() },
                )
            },
            transitions = positionedTransitions.map { pt ->
                pt.copy(
                    points = pt.points.map { it.shifted() },
                    labelRect = pt.labelRect?.shifted(),
                )
            },
            notes = positionedNotes.map { pn ->
                pn.copy(
                    rect = pn.rect.shifted(),
                    connectorPoints = pn.connectorPoints.map { it.shifted() },
                )
            },
        )
    }

    internal fun defaultStateMetrics(s: StateNode, density: Float): StateBoxMetrics = when (s.kind) {
        StateKind.Start -> {
            val d = 18f * density
            StateBoxMetrics(d, d, d)
        }
        StateKind.End -> {
            val d = 22f * density
            StateBoxMetrics(d, d, d)
        }
        StateKind.Choice -> {
            val d = 28f * density
            StateBoxMetrics(d, d, d)
        }
        StateKind.Fork, StateKind.Join -> {
            StateBoxMetrics(72f * density, 8f * density, 8f * density)
        }
        StateKind.Normal, StateKind.Composite -> {
            val titleLines = s.label.lines()
            val titleW = titleLines.maxOf { it.length } * 8f * density + 24f * density
            val hdrH = max(32f * density, titleLines.size * 18f * density + 14f * density)
            if (s.descriptions.isEmpty()) {
                StateBoxMetrics(max(84f * density, titleW), hdrH, hdrH, 0f)
            } else {
                val descLines = s.descriptions.flatMap { it.lines() }
                val descW = descLines.maxOf { it.length } * 7f * density + 24f * density
                val descH = descLines.size * 16f * density + 12f * density
                val w = maxOf(84f * density, titleW, descW)
                StateBoxMetrics(w, hdrH + descH, hdrH, descH)
            }
        }
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
