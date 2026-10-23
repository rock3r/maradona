package dev.maradona.layout

import dev.maradona.model.GitBranch
import dev.maradona.model.GitCommit
import dev.maradona.model.GitGraphDiagram
import dev.maradona.model.GitGraphDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class PositionedGitBranch(
    val branch: GitBranch,
    val laneIndex: Int,
    val labelRect: DiagramRect?,
    val lineStart: DiagramPoint,
    val lineEnd: DiagramPoint,
)

data class PositionedGitCommit(
    val commit: GitCommit,
    val laneIndex: Int,
    val center: DiagramPoint,
    val nodeRect: DiagramRect,
    val commitLabelRect: DiagramRect?,
    val commitLabelPillSize: DiagramSize? = null,
    val commitLabelAngleDeg: Float = 0f,
    val tagRect: DiagramRect?,
)

data class PositionedGitEdge(
    val fromCommitId: String,
    val toCommitId: String,
    val branchLaneIndex: Int,
    val isCherryPick: Boolean,
    val points: List<DiagramPoint>,
)

data class GitGraphDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val direction: GitGraphDirection,
    val rotateCommitLabel: Boolean,
    val branches: List<PositionedGitBranch>,
    val commits: List<PositionedGitCommit>,
    val edges: List<PositionedGitEdge>,
) {
    fun commit(id: String): PositionedGitCommit? = commits.firstOrNull { it.commit.id == id }
    fun branch(name: String): PositionedGitBranch? = branches.firstOrNull { it.branch.name == name }
}

/**
 * Pure-Kotlin layout engine for Mermaid `gitGraph` diagrams.
 *
 * Supports `LR`, `TB`, and `BT` directions, sequential and `parallelCommits` slot positioning,
 * explicit branch ordering, commit ID labels (rotated and horizontal), tag badges, and
 * deconflicted label/tag placement with 4-direction bounds translation.
 */
object GitGraphLayout {

    internal fun shouldShowCommitLabel(diagram: GitGraphDiagram, commit: GitCommit): Boolean =
        diagram.showCommitLabel && ((!commit.isMerge && !commit.isCherryPick) || commit.hasExplicitId)

    fun layout(
        diagram: GitGraphDiagram,
        branchLabelSize: (String) -> DiagramSize = { s -> defaultBadgeSize(s, 1f) },
        commitLabelSize: (GitCommit) -> DiagramSize = { c -> defaultCommitLabelSize(c.id, 1f) },
        tagLabelSize: (GitCommit) -> DiagramSize = { c -> defaultBadgeSize(c.tag.orEmpty(), 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): GitGraphDiagramLayout {
        val d = options.density
        val outerPad = 22f * d
        val nodeRadius = 9f * d
        val nodeDiameter = nodeRadius * 2f

        val resolvedTitleSize = titleSize ?: diagram.title?.let {
            DiagramSize(max(120f * d, it.length * 9f * d), 24f * d)
        }

        // Union declared branches with any branch referenced by commits.
        val declaredByName = diagram.branches.associateBy { it.name }
        val allBranchNames = (diagram.branches.map { it.name } + diagram.commits.map { it.branch }).distinct()
        val orderedBranches = allBranchNames.mapIndexed { idx, name ->
            declaredByName[name] ?: GitBranch(name = name, order = null, insertionIndex = diagram.branches.size + idx)
        }
        val laneIndexByBranch = orderedBranches.withIndex().associate { (i, b) -> b.name to i }

        var contentTopY = outerPad
        val titleTopY = contentTopY
        if (resolvedTitleSize != null) {
            contentTopY += resolvedTitleSize.height + 16f * d
        }

        val isHorizontal = diagram.direction == GitGraphDirection.LR
        val laneGap = if (isHorizontal) 62f * d else 96f * d
        val seqStep = when {
            isHorizontal && diagram.showCommitLabel && !diagram.rotateCommitLabel -> 78f * d
            isHorizontal -> 58f * d
            else -> 54f * d
        }

        val maxBranchW = if (diagram.showBranches && orderedBranches.isNotEmpty()) {
            orderedBranches.maxOf { branchLabelSize(it.name).width } + 20f * d
        } else {
            0f
        }
        val maxBranchH = if (diagram.showBranches && orderedBranches.isNotEmpty()) {
            orderedBranches.maxOf { branchLabelSize(it.name).height } + 12f * d
        } else {
            0f
        }

        val maxSeq = diagram.commits.maxOfOrNull { it.seq } ?: 0
        val seqCount = (maxSeq + 1).coerceAtLeast(1)

        val trackLeadPad = 28f * d
        val trackTailPad = 28f * d
        val trackLength = trackLeadPad + max(0, seqCount - 1) * seqStep + trackTailPad

        val trackStartX: Float
        val trackStartY: Float
        if (isHorizontal) {
            trackStartX = outerPad + (if (diagram.showBranches) maxBranchW + 18f * d else 0f)
            trackStartY = contentTopY + 26f * d
        } else {
            trackStartX = outerPad + 36f * d
            trackStartY = contentTopY + (if (diagram.showBranches && diagram.direction == GitGraphDirection.TB) maxBranchH + 18f * d else 16f * d)
        }

        val branchPillSizes = orderedBranches.map { br ->
            val bSz = branchLabelSize(br.name)
            DiagramSize(bSz.width + 18f * d, bSz.height + 10f * d)
        }
        val laneCoords = FloatArray(orderedBranches.size)
        if (orderedBranches.isNotEmpty()) {
            if (isHorizontal) {
                laneCoords[0] = trackStartY
                for (i in 1 until orderedBranches.size) {
                    val minGap = if (diagram.showBranches) {
                        (branchPillSizes[i - 1].height + branchPillSizes[i].height) / 2f + 16f * d
                    } else {
                        0f
                    }
                    laneCoords[i] = laneCoords[i - 1] + max(laneGap, minGap)
                }
            } else {
                laneCoords[0] = if (diagram.showBranches) {
                    max(trackStartX, outerPad + branchPillSizes[0].width / 2f)
                } else {
                    trackStartX
                }
                for (i in 1 until orderedBranches.size) {
                    val minGap = if (diagram.showBranches) {
                        (branchPillSizes[i - 1].width + branchPillSizes[i].width) / 2f + 16f * d
                    } else {
                        0f
                    }
                    laneCoords[i] = laneCoords[i - 1] + max(laneGap, minGap)
                }
            }
        }

        fun laneCoord(laneIdx: Int): Float =
            if (laneIdx in laneCoords.indices) laneCoords[laneIdx]
            else if (isHorizontal) trackStartY + laneIdx * laneGap
            else trackStartX + laneIdx * laneGap

        fun seqCoord(seq: Int): Float =
            when (diagram.direction) {
                GitGraphDirection.LR -> trackStartX + trackLeadPad + seq * seqStep
                GitGraphDirection.TB -> trackStartY + trackLeadPad + seq * seqStep
                GitGraphDirection.BT -> trackStartY + trackLeadPad + (maxSeq - seq) * seqStep
            }

        val rawBranches = ArrayList<PositionedGitBranch>(orderedBranches.size)
        for ((laneIdx, br) in orderedBranches.withIndex()) {
            val lc = laneCoord(laneIdx)
            val bSz = branchLabelSize(br.name)
            val pillW = bSz.width + 18f * d
            val pillH = bSz.height + 10f * d
            if (isHorizontal) {
                val lblRect = if (diagram.showBranches) {
                    DiagramRect(
                        x = outerPad,
                        y = lc - pillH / 2f,
                        width = pillW,
                        height = pillH,
                    )
                } else {
                    null
                }
                rawBranches += PositionedGitBranch(
                    branch = br,
                    laneIndex = laneIdx,
                    labelRect = lblRect,
                    lineStart = DiagramPoint(trackStartX, lc),
                    lineEnd = DiagramPoint(trackStartX + trackLength, lc),
                )
            } else {
                val lblRect = if (diagram.showBranches) {
                    val pillY = if (diagram.direction == GitGraphDirection.TB) {
                        contentTopY
                    } else {
                        trackStartY + trackLength + 16f * d
                    }
                    DiagramRect(
                        x = lc - pillW / 2f,
                        y = pillY,
                        width = pillW,
                        height = pillH,
                    )
                } else {
                    null
                }
                rawBranches += PositionedGitBranch(
                    branch = br,
                    laneIndex = laneIdx,
                    labelRect = lblRect,
                    lineStart = DiagramPoint(lc, trackStartY),
                    lineEnd = DiagramPoint(lc, trackStartY + trackLength),
                )
            }
        }

        // First compute commit centers, node boxes, and edges so label/tag placement avoids both nodes and cross-branch curves.
        val commitCenters = HashMap<String, DiagramPoint>(diagram.commits.size)
        val commitNodeRects = ArrayList<DiagramRect>(diagram.commits.size)
        for (c in diagram.commits) {
            val laneIdx = laneIndexByBranch[c.branch] ?: 0
            val lc = laneCoord(laneIdx)
            val sc = seqCoord(c.seq)
            val pt = if (isHorizontal) DiagramPoint(sc, lc) else DiagramPoint(lc, sc)
            commitCenters[c.id] = pt
            commitNodeRects += DiagramRect(
                x = pt.x - nodeRadius,
                y = pt.y - nodeRadius,
                width = nodeDiameter,
                height = nodeDiameter,
            )
        }

        val commitsModelById = diagram.commits.associateBy { it.id }
        val nodeRectByCommitId = diagram.commits.indices.associate { i -> diagram.commits[i].id to commitNodeRects[i] }

        fun curveHitsUnrelatedCommit(
            fromId: String,
            toId: String,
            p0: DiagramPoint,
            c1: DiagramPoint,
            c2: DiagramPoint,
            p1: DiagramPoint,
        ): Boolean {
            val pad = 4f * d
            val samples = sampleCubicBezier(p0, c1, c2, p1, steps = 24)
            for ((cid, nRect) in nodeRectByCommitId) {
                if (cid == fromId || cid == toId) continue
                for (pt in samples) {
                    if (pt.x >= nRect.x - pad && pt.x <= nRect.right + pad &&
                        pt.y >= nRect.y - pad && pt.y <= nRect.bottom + pad
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        val bendFractions = floatArrayOf(0.5f, 0.25f, 0.75f, 0.14f, 0.86f, 0.05f, 0.95f)
        val rawEdges = ArrayList<PositionedGitEdge>(diagram.commits.size * 2)
        for (c in diagram.commits) {
            val pcLane = laneIndexByBranch[c.branch] ?: 0
            val p1 = commitCenters.getValue(c.id)
            for ((pIdx, pId) in c.parents.withIndex()) {
                val parentModel = commitsModelById[pId] ?: continue
                val parentLane = laneIndexByBranch[parentModel.branch] ?: 0
                val p0 = commitCenters[pId] ?: continue
                val edgeLaneIdx = if (pIdx > 0 && (c.isMerge || c.isCherryPick)) {
                    parentLane
                } else {
                    pcLane
                }
                val pts = if (parentLane == pcLane) {
                    listOf(p0, p1)
                } else if (isHorizontal) {
                    val candidates = buildList {
                        for (f in bendFractions) {
                            val bx = p0.x + (p1.x - p0.x) * f
                            add(DiagramPoint(bx, p0.y) to DiagramPoint(bx, p1.y))
                        }
                        add(DiagramPoint(p0.x, p1.y) to DiagramPoint(p0.x + (p1.x - p0.x) * 0.2f, p1.y))
                        add(DiagramPoint(p0.x + (p1.x - p0.x) * 0.8f, p0.y) to DiagramPoint(p1.x, p0.y))
                    }
                    val (c1, c2) = candidates.firstOrNull { (a, b) ->
                        !curveHitsUnrelatedCommit(pId, c.id, p0, a, b, p1)
                    } ?: candidates.first()
                    listOf(p0, c1, c2, p1)
                } else {
                    val candidates = buildList {
                        for (f in bendFractions) {
                            val by = p0.y + (p1.y - p0.y) * f
                            add(DiagramPoint(p0.x, by) to DiagramPoint(p1.x, by))
                        }
                        add(DiagramPoint(p1.x, p0.y) to DiagramPoint(p1.x, p0.y + (p1.y - p0.y) * 0.2f))
                        add(DiagramPoint(p0.x, p0.y + (p1.y - p0.y) * 0.8f) to DiagramPoint(p0.x, p1.y))
                    }
                    val (c1, c2) = candidates.firstOrNull { (a, b) ->
                        !curveHitsUnrelatedCommit(pId, c.id, p0, a, b, p1)
                    } ?: candidates.first()
                    listOf(p0, c1, c2, p1)
                }
                rawEdges += PositionedGitEdge(
                    fromCommitId = pId,
                    toCommitId = c.id,
                    branchLaneIndex = edgeLaneIdx,
                    isCherryPick = c.isCherryPick && pIdx > 0,
                    points = pts,
                )
            }
        }

        val occupiedRects = ArrayList<DiagramRect>(commitNodeRects.size * 3)
        occupiedRects.addAll(commitNodeRects)
        for (rb in rawBranches) {
            rb.labelRect?.let { occupiedRects += it }
        }

        val crossBranchEdgeSamples: List<List<DiagramPoint>> = rawEdges
            .filter { it.points.size == 4 }
            .map { e -> sampleCubicBezier(e.points[0], e.points[1], e.points[2], e.points[3], steps = 16) }

        fun conflictsWithNodesOrEdges(rect: DiagramRect, pad: Float): Boolean {
            if (occupiedRects.any { overlaps(it, rect, pad) }) return true
            for (samples in crossBranchEdgeSamples) {
                for (pt in samples) {
                    if (pt.x >= rect.x - pad && pt.x <= rect.right + pad &&
                        pt.y >= rect.y - pad && pt.y <= rect.bottom + pad
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        val rawCommits = ArrayList<PositionedGitCommit>(diagram.commits.size)
        for ((idx, c) in diagram.commits.withIndex()) {
            val laneIdx = laneIndexByBranch[c.branch] ?: 0
            val center = commitCenters.getValue(c.id)
            val nodeRect = commitNodeRects[idx]

            val labelAngleDeg = if (isHorizontal && diagram.rotateCommitLabel) -28f else 0f
            val commitLblPair: Pair<DiagramRect, DiagramSize>? = if (shouldShowCommitLabel(diagram, c)) {
                val sz = commitLabelSize(c)
                val pillW = sz.width + 8f * d
                val pillH = sz.height + 4f * d
                val pillSz = DiagramSize(pillW, pillH)
                val rad = abs(labelAngleDeg).toDouble() * PI / 180.0
                val boxW = if (labelAngleDeg != 0f) {
                    (pillW * cos(rad) + pillH * sin(rad)).toFloat()
                } else {
                    pillW
                }
                val boxH = if (labelAngleDeg != 0f) {
                    (pillW * sin(rad) + pillH * cos(rad)).toFloat()
                } else {
                    pillH
                }
                val candidates = if (isHorizontal) {
                    listOf(
                        DiagramRect(center.x - boxW / 2f, nodeRect.bottom + 5f * d, boxW, boxH),
                        DiagramRect(center.x - boxW - 4f * d, nodeRect.bottom + 5f * d, boxW, boxH),
                        DiagramRect(center.x + 4f * d, nodeRect.bottom + 5f * d, boxW, boxH),
                        DiagramRect(center.x - boxW / 2f, nodeRect.y - boxH - 5f * d, boxW, boxH),
                    )
                } else {
                    listOf(
                        DiagramRect(nodeRect.right + 7f * d, center.y - boxH / 2f, boxW, boxH),
                        DiagramRect(nodeRect.x - boxW - 7f * d, center.y - boxH / 2f, boxW, boxH),
                    )
                }
                var chosen = candidates.firstOrNull { !conflictsWithNodesOrEdges(it, 1.5f * d) }
                if (chosen == null) {
                    var fallback = candidates.first()
                    var guard = 0
                    while (guard < 12 && occupiedRects.any { overlaps(it, fallback, 2f * d) }) {
                        fallback = fallback.copy(y = fallback.y + (boxH + 3f * d))
                        guard++
                    }
                    chosen = fallback
                }
                occupiedRects += chosen
                chosen to pillSz
            } else {
                null
            }

            val tagRect: DiagramRect? = c.tag?.takeIf { it.isNotBlank() }?.let {
                val sz = tagLabelSize(c)
                val boxW = sz.width + 12f * d
                val boxH = sz.height + 6f * d
                val candidates = if (isHorizontal) {
                    listOf(
                        DiagramRect(center.x - boxW / 2f, nodeRect.y - boxH - 5f * d, boxW, boxH),
                        DiagramRect(center.x - boxW - 4f * d, nodeRect.y - boxH - 5f * d, boxW, boxH),
                        DiagramRect(center.x + 4f * d, nodeRect.y - boxH - 5f * d, boxW, boxH),
                    )
                } else {
                    listOf(
                        DiagramRect(nodeRect.x - boxW - 7f * d, center.y - boxH / 2f, boxW, boxH),
                        DiagramRect(nodeRect.right + 7f * d, center.y - boxH / 2f, boxW, boxH),
                    )
                }
                var chosen = candidates.firstOrNull { !conflictsWithNodesOrEdges(it, 1.5f * d) }
                if (chosen == null) {
                    var fallback = candidates.first()
                    var guard = 0
                    while (guard < 12 && occupiedRects.any { overlaps(it, fallback, 2f * d) }) {
                        fallback = fallback.copy(y = fallback.y - (boxH + 3f * d))
                        guard++
                    }
                    chosen = fallback
                }
                occupiedRects += chosen
                chosen
            }

            rawCommits += PositionedGitCommit(
                commit = c,
                laneIndex = laneIdx,
                center = center,
                nodeRect = nodeRect,
                commitLabelRect = commitLblPair?.first,
                commitLabelPillSize = commitLblPair?.second,
                commitLabelAngleDeg = if (commitLblPair != null) labelAngleDeg else 0f,
                tagRect = tagRect,
            )
        }

        // Compute 4-direction bounds and translate if any tag or label overflowed left (`< outerPad`) or top (`< contentTopY`).
        val allRects = buildList {
            for (rb in rawBranches) {
                rb.labelRect?.let { add(it) }
                add(DiagramRect(min(rb.lineStart.x, rb.lineEnd.x), min(rb.lineStart.y, rb.lineEnd.y), max(1f, kotlin.math.abs(rb.lineEnd.x - rb.lineStart.x)), max(1f, kotlin.math.abs(rb.lineEnd.y - rb.lineStart.y))))
            }
            for (rc in rawCommits) {
                add(rc.nodeRect)
                rc.commitLabelRect?.let { add(it) }
                rc.tagRect?.let { add(it) }
            }
        }

        val minX = allRects.minOfOrNull { it.x } ?: outerPad
        val minY = allRects.minOfOrNull { it.y } ?: contentTopY
        val shiftX = if (minX < outerPad) outerPad - minX else 0f
        val shiftY = if (minY < contentTopY) contentTopY - minY else 0f

        fun DiagramPoint.shifted() = DiagramPoint(x + shiftX, y + shiftY)
        fun DiagramRect.shifted() = copy(x = x + shiftX, y = y + shiftY)

        val positionedBranches = rawBranches.map { b ->
            b.copy(
                labelRect = b.labelRect?.shifted(),
                lineStart = b.lineStart.shifted(),
                lineEnd = b.lineEnd.shifted(),
            )
        }
        val positionedCommits = rawCommits.map { c ->
            c.copy(
                center = c.center.shifted(),
                nodeRect = c.nodeRect.shifted(),
                commitLabelRect = c.commitLabelRect?.shifted(),
                tagRect = c.tagRect?.shifted(),
            )
        }
        val positionedEdges = rawEdges.map { e ->
            e.copy(points = e.points.map { it.shifted() })
        }

        val maxRight = allRects.maxOfOrNull { it.right + shiftX } ?: (outerPad + 200f * d)
        val maxBottom = allRects.maxOfOrNull { it.bottom + shiftY } ?: (contentTopY + 120f * d)
        val titleMinWidth = resolvedTitleSize?.let { it.width + outerPad * 2f } ?: 0f

        val finalWidth = max(titleMinWidth, maxRight + outerPad)
        val finalHeight = maxBottom + outerPad

        val titleRect = resolvedTitleSize?.let {
            DiagramRect(
                x = max(outerPad, (finalWidth - it.width) / 2f),
                y = titleTopY,
                width = it.width,
                height = it.height,
            )
        }

        return GitGraphDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            titleRect = titleRect,
            direction = diagram.direction,
            rotateCommitLabel = diagram.rotateCommitLabel,
            branches = positionedBranches,
            commits = positionedCommits,
            edges = positionedEdges,
        )
    }

    internal fun sampleCubicBezier(
        p0: DiagramPoint,
        p1: DiagramPoint,
        p2: DiagramPoint,
        p3: DiagramPoint,
        steps: Int,
    ): List<DiagramPoint> =
        (2 until steps - 1).map { s ->
            val t = s.toFloat() / steps.toFloat()
            val u = 1f - t
            val x = u * u * u * p0.x + 3f * u * u * t * p1.x + 3f * u * t * t * p2.x + t * t * t * p3.x
            val y = u * u * u * p0.y + 3f * u * u * t * p1.y + 3f * u * t * t * p2.y + t * t * t * p3.y
            DiagramPoint(x, y)
        }

    private fun overlaps(a: DiagramRect, b: DiagramRect, pad: Float): Boolean =
        a.x - pad < b.right && b.x - pad < a.right && a.y - pad < b.bottom && b.y - pad < a.bottom

    internal fun defaultBadgeSize(label: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(28f * density, label.length * 7.2f * density),
            height = 16f * density,
        )

    internal fun defaultCommitLabelSize(label: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(24f * density, label.length * 6.8f * density),
            height = 14f * density,
        )
}
