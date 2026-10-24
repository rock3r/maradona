package dev.maradona.layout

import dev.maradona.model.ArchitectureAlignDir
import dev.maradona.model.ArchitectureDiagram
import dev.maradona.model.ArchitectureEdge
import dev.maradona.model.ArchitectureGroup
import dev.maradona.model.ArchitectureJunction
import dev.maradona.model.ArchitecturePortDir
import dev.maradona.model.ArchitectureService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Positioned service node in a laid-out [ArchitectureDiagram].
 */
data class PositionedArchitectureService(
    val service: ArchitectureService,
    val iconRect: DiagramRect,
    val labelRect: DiagramRect?,
    val bounds: DiagramRect,
)

/**
 * Positioned junction node in a laid-out [ArchitectureDiagram].
 */
data class PositionedArchitectureJunction(
    val junction: ArchitectureJunction,
    val center: DiagramPoint,
    val rect: DiagramRect,
)

/**
 * Positioned group container in a laid-out [ArchitectureDiagram].
 */
data class PositionedArchitectureGroup(
    val group: ArchitectureGroup,
    val rect: DiagramRect,
    val iconRect: DiagramRect?,
    val labelRect: DiagramRect?,
)

/**
 * Positioned port-to-port edge in a laid-out [ArchitectureDiagram].
 */
data class PositionedArchitectureEdge(
    val edge: ArchitectureEdge,
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect?,
)

/**
 * Complete layout geometry for an [ArchitectureDiagram].
 */
data class ArchitectureDiagramLayout(
    val width: Float,
    val height: Float,
    val groups: List<PositionedArchitectureGroup>,
    val services: List<PositionedArchitectureService>,
    val junctions: List<PositionedArchitectureJunction>,
    val edges: List<PositionedArchitectureEdge>,
    val titleRect: DiagramRect?,
)

/**
 * Computes spatial grid coordinates, group enclosures, and port-to-port edge routing
 * for Mermaid `architecture` / `architecture-beta` diagrams.
 */
object ArchitectureLayout {

    private const val OUTER_MARGIN = 20f
    private const val TITLE_GAP = 16f
    private const val SERVICE_LABEL_GAP = 6f
    private const val JUNCTION_SIZE = 14f

    fun defaultLabelSize(text: String, density: Float = 1f): DiagramSize {
        if (text.isEmpty()) return DiagramSize(0f, 0f)
        val lines = text.lines()
        val maxCols = lines.maxOfOrNull { it.length } ?: 0
        return DiagramSize(
            width = (maxCols * 7.2f + 6f) * density,
            height = (lines.size * 15f + 2f) * density,
        )
    }

    fun defaultTitleSize(text: String, density: Float = 1f): DiagramSize =
        DiagramSize((text.length * 9.5f + 8f) * density, 22f * density)

    fun layout(
        diagram: ArchitectureDiagram,
        serviceLabelSize: (String, String) -> DiagramSize = { _, lbl -> defaultLabelSize(lbl, 1f) },
        groupLabelSize: (String, String) -> DiagramSize = { _, lbl -> defaultLabelSize(lbl, 1f) },
        edgeLabelSize: (String) -> DiagramSize = { lbl -> defaultLabelSize(lbl, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): ArchitectureDiagramLayout {
        val d = options.density.coerceAtLeast(0.01f)
        val cfg = diagram.config
        val iconSize = cfg.iconSize.coerceAtLeast(1f) * d
        val nodeSpacing = cfg.nodeSpacing.coerceAtLeast(0f) * d
        val groupPad = cfg.padding.coerceAtLeast(0f) * d
        val margin = OUTER_MARGIN * d

        val titleText = diagram.title?.takeIf { it.isNotBlank() }
        val resolvedTitleSize = if (titleText != null) {
            titleSize ?: defaultTitleSize(titleText, d)
        } else {
            null
        }
        val titleTopSpace = if (resolvedTitleSize != null) resolvedTitleSize.height + TITLE_GAP * d else 0f

        val gridCoords = computeNodeGridCoordinates(diagram)

        // Compute cell pitch from maximum service dimensions plus spacing.
        var maxServiceW = iconSize
        var maxServiceH = iconSize
        val serviceLblSizes = HashMap<String, DiagramSize>()
        for (svc in diagram.services) {
            val lbl = svc.title?.takeIf { it.isNotBlank() }
            if (lbl != null) {
                val sz = serviceLabelSize(svc.id, lbl)
                serviceLblSizes[svc.id] = sz
                maxServiceW = max(maxServiceW, max(iconSize, sz.width))
                maxServiceH = max(maxServiceH, iconSize + SERVICE_LABEL_GAP * d + sz.height)
            }
        }

        // Add extra spacing when edges carry labels or cross group boundaries (scaled by maximum group nesting depth),
        // or when group headers are wide.
        val maxEdgeLblW = diagram.edges.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }
            .maxOfOrNull { edgeLabelSize(it).width + 24f * d } ?: 0f
        val parentGroupMap = diagram.groups.associate { it.id to it.parentId }
        fun groupDepth(gId: String): Int {
            var depth = 0
            var cur: String? = gId
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                depth++
                cur = parentGroupMap[cur]
            }
            return depth
        }
        val maxGroupDepth = diagram.groups.maxOfOrNull { groupDepth(it.id) } ?: 0
        val extraGroupGapX = maxGroupDepth * (groupPad * 2.2f + 18f * d)
        val extraGroupGapY = maxGroupDepth * (groupPad * 2.2f + 32f * d)
        var maxGroupHeaderW = 0f
        for (grp in diagram.groups) {
            val lblText = grp.title?.takeIf { it.isNotBlank() } ?: grp.id
            val lblSz = groupLabelSize(grp.id, lblText)
            val hasIcon = !grp.icon.isNullOrBlank()
            val headerW = (if (hasIcon) 24f * d else 0f) + lblSz.width + 16f * d
            maxGroupHeaderW = max(maxGroupHeaderW, headerW)
        }

        val pitchX = maxOf(
            maxServiceW + nodeSpacing + extraGroupGapX,
            iconSize + maxEdgeLblW,
            if (maxGroupDepth > 0) maxGroupHeaderW + nodeSpacing + extraGroupGapX else 0f,
        )
        val pitchY = maxServiceH + nodeSpacing + extraGroupGapY

        // Compute initial unshifted node centers from grid coordinates.
        val rawCenterById = LinkedHashMap<String, DiagramPoint>()
        for ((id, cell) in gridCoords) {
            rawCenterById[id] = DiagramPoint(
                x = cell.first * pitchX,
                y = cell.second * pitchY,
            )
        }

        // Build unshifted services and junctions.
        val rawServices = diagram.services.map { svc ->
            val center = rawCenterById[svc.id] ?: DiagramPoint(0f, 0f)
            val iconRect = DiagramRect(
                x = center.x - iconSize / 2f,
                y = center.y - iconSize / 2f,
                width = iconSize,
                height = iconSize,
            )
            val lblSz = serviceLblSizes[svc.id]
            val lblRect = if (lblSz != null) {
                DiagramRect(
                    x = center.x - lblSz.width / 2f,
                    y = iconRect.bottom + SERVICE_LABEL_GAP * d,
                    width = lblSz.width,
                    height = lblSz.height,
                )
            } else {
                null
            }
            val bounds = if (lblRect != null) {
                val left = min(iconRect.x, lblRect.x)
                val right = max(iconRect.right, lblRect.right)
                DiagramRect(
                    x = left,
                    y = iconRect.y,
                    width = right - left,
                    height = lblRect.bottom - iconRect.y,
                )
            } else {
                iconRect
            }
            PositionedArchitectureService(
                service = svc,
                iconRect = iconRect,
                labelRect = lblRect,
                bounds = bounds,
            )
        }

        val jSize = JUNCTION_SIZE * d
        val rawJunctions = diagram.junctions.map { j ->
            val center = rawCenterById[j.id] ?: DiagramPoint(0f, 0f)
            PositionedArchitectureJunction(
                junction = j,
                center = center,
                rect = DiagramRect(
                    x = center.x - jSize / 2f,
                    y = center.y - jSize / 2f,
                    width = jSize,
                    height = jSize,
                ),
            )
        }

        // Compute group bounding boxes bottom-up (children before parents).
        val rawGroupsById = LinkedHashMap<String, PositionedArchitectureGroup>()
        val groupIconBoxSize = 18f * d
        val groupHeaderGap = 6f * d
        var emptyGroupCursorX = listOfNotNull(
            rawServices.maxOfOrNull { it.bounds.right },
            rawJunctions.maxOfOrNull { it.rect.right },
        ).maxOrNull()?.let { it + pitchX } ?: 0f

        fun computeGroupBox(group: ArchitectureGroup): PositionedArchitectureGroup {
            rawGroupsById[group.id]?.let { return it }
            val childGroups = diagram.groups.filter { it.parentId == group.id }.map { computeGroupBox(it) }
            val memberServices = rawServices.filter { it.service.parentId == group.id }
            val memberJunctions = rawJunctions.filter { it.junction.parentId == group.id }

            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY

            for (ms in memberServices) {
                minX = min(minX, ms.bounds.x)
                minY = min(minY, ms.bounds.y)
                maxX = max(maxX, ms.bounds.right)
                maxY = max(maxY, ms.bounds.bottom)
            }
            for (mj in memberJunctions) {
                minX = min(minX, mj.rect.x)
                minY = min(minY, mj.rect.y)
                maxX = max(maxX, mj.rect.right)
                maxY = max(maxY, mj.rect.bottom)
            }
            for (cg in childGroups) {
                minX = min(minX, cg.rect.x)
                minY = min(minY, cg.rect.y)
                maxX = max(maxX, cg.rect.right)
                maxY = max(maxY, cg.rect.bottom)
            }

            val lblText = group.title?.takeIf { it.isNotBlank() } ?: group.id
            val lblSz = groupLabelSize(group.id, lblText)
            val hasIcon = !group.icon.isNullOrBlank()
            val headerH = max(if (hasIcon) groupIconBoxSize else 0f, lblSz.height) + 8f * d
            val headerW = (if (hasIcon) groupIconBoxSize + groupHeaderGap else 0f) + lblSz.width + 16f * d

            val isEmptyGroup = !minX.isFinite()
            if (isEmptyGroup) {
                val center = rawCenterById[group.id]
                if (center != null) {
                    val w = max(iconSize, headerW)
                    minX = center.x - w / 2f
                    maxX = center.x + w / 2f
                    minY = center.y - iconSize / 2f
                    maxY = center.y + iconSize / 2f
                } else {
                    minX = emptyGroupCursorX + groupPad
                    minY = groupPad + headerH
                    maxX = minX + max(iconSize, headerW)
                    maxY = minY + iconSize
                }
            }

            val boxLeft = minX - groupPad
            val boxTop = minY - groupPad - headerH
            val boxWidth = max(maxX - minX + groupPad * 2f, headerW + groupPad)
            val boxHeight = (maxY + groupPad) - boxTop
            if (isEmptyGroup && rawCenterById[group.id] == null) {
                emptyGroupCursorX = boxLeft + boxWidth + nodeSpacing.coerceAtLeast(24f * d)
            }

            val rect = DiagramRect(boxLeft, boxTop, boxWidth, boxHeight)
            val iconRect = if (hasIcon) {
                DiagramRect(
                    x = rect.x + 8f * d,
                    y = rect.y + 6f * d,
                    width = groupIconBoxSize,
                    height = groupIconBoxSize,
                )
            } else {
                null
            }
            val labelRect = DiagramRect(
                x = if (iconRect != null) iconRect.right + groupHeaderGap else rect.x + 8f * d,
                y = rect.y + 6f * d + (max(if (hasIcon) groupIconBoxSize else 0f, lblSz.height) - lblSz.height) / 2f,
                width = lblSz.width,
                height = lblSz.height,
            )

            val pg = PositionedArchitectureGroup(
                group = group,
                rect = rect,
                iconRect = iconRect,
                labelRect = labelRect,
            )
            rawGroupsById[group.id] = pg
            return pg
        }

        val rawGroups = diagram.groups.map { computeGroupBox(it) }
        val serviceById = rawServices.associateBy { it.service.id }
        val junctionById = rawJunctions.associateBy { it.junction.id }

        // Route edges between ports.
        val rawEdges = diagram.edges.map { edge ->
            val startPt = resolvePortEndpoint(
                nodeId = edge.lhsId,
                dir = edge.lhsDir,
                useGroupBorder = edge.lhsGroup,
                hasArrow = edge.lhsInto,
                serviceById = serviceById,
                junctionById = junctionById,
                groupById = rawGroupsById,
                d = d,
            )
            val endPt = resolvePortEndpoint(
                nodeId = edge.rhsId,
                dir = edge.rhsDir,
                useGroupBorder = edge.rhsGroup,
                hasArrow = edge.rhsInto,
                serviceById = serviceById,
                junctionById = junctionById,
                groupById = rawGroupsById,
                d = d,
            )
            val pts = routeArchitectureEdgePoints(
                start = startPt,
                startDir = edge.lhsDir,
                end = endPt,
                endDir = edge.rhsDir,
                detourClearance = iconSize * 0.72f + 16f * d,
                d = d,
            )
            val midPt = computePolylineMidpoint(pts)
            val lblRect = edge.title?.takeIf { it.isNotBlank() }?.let { lbl ->
                val sz = edgeLabelSize(lbl)
                val padX = 8f * d
                val padY = 4f * d
                DiagramRect(
                    x = midPt.x - (sz.width + padX) / 2f,
                    y = midPt.y - (sz.height + padY) / 2f,
                    width = sz.width + padX,
                    height = sz.height + padY,
                )
            }
            PositionedArchitectureEdge(
                edge = edge,
                points = pts,
                labelRect = lblRect,
            )
        }

        // Compute global bounding box and shift everything so top-left is at (margin, margin + titleTopSpace).
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (s in rawServices) {
            minX = min(minX, s.bounds.x)
            minY = min(minY, s.bounds.y)
            maxX = max(maxX, s.bounds.right)
            maxY = max(maxY, s.bounds.bottom)
        }
        for (j in rawJunctions) {
            minX = min(minX, j.rect.x)
            minY = min(minY, j.rect.y)
            maxX = max(maxX, j.rect.right)
            maxY = max(maxY, j.rect.bottom)
        }
        for (g in rawGroups) {
            minX = min(minX, g.rect.x)
            minY = min(minY, g.rect.y)
            maxX = max(maxX, g.rect.right)
            maxY = max(maxY, g.rect.bottom)
        }
        for (e in rawEdges) {
            for (p in e.points) {
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            val lr = e.labelRect
            if (lr != null) {
                minX = min(minX, lr.x)
                minY = min(minY, lr.y)
                maxX = max(maxX, lr.right)
                maxY = max(maxY, lr.bottom)
            }
        }

        if (!minX.isFinite()) {
            minX = 0f
            minY = 0f
            maxX = 160f * d
            maxY = 100f * d
        }

        val contentW = (maxX - minX).coerceAtLeast(1f)
        val contentH = (maxY - minY).coerceAtLeast(1f)
        val minTitleW = if (resolvedTitleSize != null) resolvedTitleSize.width + margin * 2f else 0f
        val totalWidth = max(contentW + margin * 2f, minTitleW)
        val totalHeight = margin + titleTopSpace + contentH + margin

        val shiftX = (totalWidth - contentW) / 2f - minX
        val shiftY = margin + titleTopSpace - minY

        fun DiagramRect.shifted(): DiagramRect = copy(x = x + shiftX, y = y + shiftY)
        fun DiagramPoint.shifted(): DiagramPoint = DiagramPoint(x + shiftX, y + shiftY)

        val finalGroups = rawGroups.map { g ->
            g.copy(
                rect = g.rect.shifted(),
                iconRect = g.iconRect?.shifted(),
                labelRect = g.labelRect?.shifted(),
            )
        }
        val finalServices = rawServices.map { s ->
            s.copy(
                iconRect = s.iconRect.shifted(),
                labelRect = s.labelRect?.shifted(),
                bounds = s.bounds.shifted(),
            )
        }
        val finalJunctions = rawJunctions.map { j ->
            j.copy(
                center = j.center.shifted(),
                rect = j.rect.shifted(),
            )
        }
        val finalEdges = rawEdges.map { e ->
            e.copy(
                points = e.points.map { it.shifted() },
                labelRect = e.labelRect?.shifted(),
            )
        }
        val titleRect = if (resolvedTitleSize != null) {
            DiagramRect(
                x = (totalWidth - resolvedTitleSize.width) / 2f,
                y = margin,
                width = resolvedTitleSize.width,
                height = resolvedTitleSize.height,
            )
        } else {
            null
        }

        return ArchitectureDiagramLayout(
            width = totalWidth,
            height = totalHeight,
            groups = finalGroups,
            services = finalServices,
            junctions = finalJunctions,
            edges = finalEdges,
            titleRect = titleRect,
        )
    }

    private fun computeNodeGridCoordinates(diagram: ArchitectureDiagram): Map<String, Pair<Float, Float>> {
        val rawNodeIds = mutableListOf<String>()
        val parentByNode = HashMap<String, String?>()
        for (s in diagram.services) {
            rawNodeIds += s.id
            parentByNode[s.id] = s.parentId
        }
        for (j in diagram.junctions) {
            rawNodeIds += j.id
            parentByNode[j.id] = j.parentId
        }
        // Include empty leaf groups in grid packing so empty child groups stay inside their parent group
        // alongside sibling services and never span across unrelated ungrouped nodes.
        val nonEmptyGroupIds = HashSet<String>()
        for (s in diagram.services) s.parentId?.let { nonEmptyGroupIds += it }
        for (j in diagram.junctions) j.parentId?.let { nonEmptyGroupIds += it }
        for (g in diagram.groups) g.parentId?.let { nonEmptyGroupIds += it }
        for (g in diagram.groups) {
            if (g.id !in nonEmptyGroupIds) {
                rawNodeIds += g.id
                parentByNode[g.id] = g.parentId
            }
        }
        if (rawNodeIds.isEmpty()) return emptyMap()

        // Validate that `align` directives do not contradict each other or edges.
        dev.maradona.parse.ArchitectureParser.validateAlignmentsAndEdges(diagram)

        val groupOrder = diagram.groups.mapIndexed { idx, g -> g.id to idx }.toMap()
        val parentGroupMap = diagram.groups.associate { it.id to it.parentId }
        fun topGroupSortKey(nodeId: String): Int {
            var cur = parentByNode[nodeId] ?: return Int.MAX_VALUE
            var top = cur
            val seen = HashSet<String>()
            while (true) {
                if (!seen.add(cur)) break
                val next = parentGroupMap[cur] ?: break
                top = next
                cur = next
            }
            return groupOrder[top] ?: (Int.MAX_VALUE - 1)
        }

        // Order seeds so nodes belonging to the same group are visited together.
        val nodeIds = rawNodeIds.sortedWith(
            compareBy<String> { topGroupSortKey(it) }
                .thenBy { groupOrder[parentByNode[it]] ?: Int.MAX_VALUE },
        )

        // Merge compatible overlapping `align row` and `align column` directives into unified chains,
        // keeping same-group nodes contiguous when topological order has ties.
        val mergedAlignments = mergeOverlappingAlignments(diagram.alignments, parentByNode, ::topGroupSortKey)

        data class NeighborStep(
            val targetId: String,
            val dx: Int,
            val dy: Int,
            val fromAlign: Boolean,
        )

        val adj = LinkedHashMap<String, MutableList<NeighborStep>>()
        for (id in nodeIds) adj[id] = mutableListOf()

        for (align in mergedAlignments) {
            for (i in 0 until align.members.size - 1) {
                val a = align.members[i]
                val b = align.members[i + 1]
                val dx = if (align.direction == ArchitectureAlignDir.Row) 1 else 0
                val dy = if (align.direction == ArchitectureAlignDir.Column) 1 else 0
                adj[a]?.add(NeighborStep(b, dx, dy, fromAlign = true))
                adj[b]?.add(NeighborStep(a, -dx, -dy, fromAlign = true))
            }
        }

        for (edge in diagram.edges) {
            if (edge.lhsId !in adj || edge.rhsId !in adj) continue
            if (edge.lhsDir == edge.rhsDir) {
                val isX = edge.lhsDir == ArchitecturePortDir.L || edge.lhsDir == ArchitecturePortDir.R
                val dx = if (isX) 0 else 1
                val dy = if (isX) 1 else 0
                adj[edge.lhsId]?.add(NeighborStep(edge.rhsId, dx, dy, fromAlign = false))
                adj[edge.rhsId]?.add(NeighborStep(edge.lhsId, -dx, -dy, fromAlign = false))
                continue
            }
            val (dx, dy) = stepForDirPair(edge.lhsDir, edge.rhsDir)
            adj[edge.lhsId]?.add(NeighborStep(edge.rhsId, dx, dy, fromAlign = false))
            adj[edge.rhsId]?.add(NeighborStep(edge.lhsId, -dx, -dy, fromAlign = false))
        }

        val coords = LinkedHashMap<String, Pair<Float, Float>>()
        val visited = HashSet<String>()
        var compOffsetX = 0f

        for (startId in nodeIds) {
            if (startId in visited) continue
            val compCoords = LinkedHashMap<String, Pair<Int, Int>>()
            val occupied = HashSet<Pair<Int, Int>>()
            val queue = ArrayDeque<String>()
            compCoords[startId] = 0 to 0
            occupied += (0 to 0)
            visited += startId
            queue.addLast(startId)

            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                val (ux, uy) = compCoords.getValue(u)
                val steps = adj[u].orEmpty().sortedByDescending { it.fromAlign }
                for (step in steps) {
                    if (step.targetId in visited) continue
                    var cx = ux + step.dx
                    var cy = uy + step.dy
                    while ((cx to cy) in occupied) {
                        if (abs(step.dx) >= abs(step.dy)) {
                            cy += 1
                        } else {
                            cx += 1
                        }
                    }
                    compCoords[step.targetId] = cx to cy
                    occupied += (cx to cy)
                    visited += step.targetId
                    queue.addLast(step.targetId)
                }
            }

            val floatComp = LinkedHashMap<String, Pair<Float, Float>>()
            for ((k, v) in compCoords) {
                floatComp[k] = v.first.toFloat() to v.second.toFloat()
            }
            val rowGroupByNode = HashMap<String, List<String>>()
            val colGroupByNode = HashMap<String, List<String>>()
            for (align in mergedAlignments) {
                val present = align.members.filter { it in floatComp }
                if (present.size < 2) continue
                for (m in present) {
                    if (align.direction == ArchitectureAlignDir.Row) {
                        rowGroupByNode[m] = present
                    } else {
                        colGroupByNode[m] = present
                    }
                }
            }
            repeat(4) {
                for (align in mergedAlignments) {
                    val present = align.members.filter { it in floatComp }
                    if (present.size < 2) continue
                    if (align.direction == ArchitectureAlignDir.Row) {
                        val commonY = present.map { floatComp.getValue(it).second }.average().toFloat()
                        val startX = present.minOf { floatComp.getValue(it).first }
                        for ((idx, mId) in present.withIndex()) {
                            val old = floatComp.getValue(mId)
                            val newX = startX + idx
                            val dxShift = newX - old.first
                            floatComp[mId] = newX to commonY
                            if (abs(dxShift) > 1e-4f) {
                                for (colPeer in colGroupByNode[mId].orEmpty()) {
                                    if (colPeer != mId) {
                                        val pOld = floatComp.getValue(colPeer)
                                        floatComp[colPeer] = (pOld.first + dxShift) to pOld.second
                                    }
                                }
                            }
                        }
                    } else {
                        val commonX = present.map { floatComp.getValue(it).first }.average().toFloat()
                        val startY = present.minOf { floatComp.getValue(it).second }
                        for ((idx, mId) in present.withIndex()) {
                            val old = floatComp.getValue(mId)
                            val newY = startY + idx
                            val dyShift = newY - old.second
                            floatComp[mId] = commonX to newY
                            if (abs(dyShift) > 1e-4f) {
                                for (rowPeer in rowGroupByNode[mId].orEmpty()) {
                                    if (rowPeer != mId) {
                                        val pOld = floatComp.getValue(rowPeer)
                                        floatComp[rowPeer] = pOld.first to (pOld.second + dyShift)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val rowAlignedNodes = mergedAlignments
                .filter { it.direction == ArchitectureAlignDir.Row }
                .flatMap { it.members }
                .toSet()
            val colAlignedNodes = mergedAlignments
                .filter { it.direction == ArchitectureAlignDir.Column }
                .flatMap { it.members }
                .toSet()

            // Center unconstrained fan-in/fan-out targets along the perpendicular axis when connected to an aligned group.
            for (u in compCoords.keys) {
                val neighbors = adj[u].orEmpty().filter { !it.fromAlign }
                if (neighbors.size >= 2) {
                    val allVertical = neighbors.all { it.dx == 0 && it.dy != 0 }
                    val allHorizontal = neighbors.all { it.dy == 0 && it.dx != 0 }
                    val cur = floatComp.getValue(u)
                    if (allVertical && u !in colAlignedNodes && u !in rowAlignedNodes) {
                        val avgX = neighbors.mapNotNull { floatComp[it.targetId]?.first }.average().toFloat()
                        val conflict = floatComp.any { (otherId, pos) ->
                            otherId != u && abs(pos.first - avgX) < 0.25f && abs(pos.second - cur.second) < 0.25f
                        }
                        if (!conflict) {
                            floatComp[u] = avgX to cur.second
                        }
                    } else if (allHorizontal && u !in rowAlignedNodes && u !in colAlignedNodes) {
                        val avgY = neighbors.mapNotNull { floatComp[it.targetId]?.second }.average().toFloat()
                        val conflict = floatComp.any { (otherId, pos) ->
                            otherId != u && abs(pos.first - cur.first) < 0.25f && abs(pos.second - avgY) < 0.25f
                        }
                        if (!conflict) {
                            floatComp[u] = cur.first to avgY
                        }
                    }
                }
            }

            val minX = floatComp.values.minOf { it.first }
            val maxX = floatComp.values.maxOf { it.first }
            val minY = floatComp.values.minOf { it.second }
            val maxY = floatComp.values.maxOf { it.second }

            val compGroups = floatComp.keys.mapNotNull { parentByNode[it] }.distinct()
            val singleGroup = compGroups.singleOrNull()
            val existingGroupEntries = if (singleGroup != null) {
                coords.filter { (existingId, _) -> parentByNode[existingId] == singleGroup }
            } else {
                emptyMap()
            }

            if (existingGroupEntries.isNotEmpty()) {
                // Pack disconnected member of the same group directly below existing members of that group
                // so cross-group neighbors in earlier components are never trapped inside the group box.
                val anchorX = existingGroupEntries.values.minOf { it.first }
                val nextY = existingGroupEntries.values.maxOf { it.second } + 1f
                for ((id, pos) in floatComp) {
                    coords[id] = (pos.first - minX + anchorX) to (pos.second - minY + nextY)
                }
                compOffsetX = max(compOffsetX, anchorX + (maxX - minX) + 2f)
            } else {
                for ((id, pos) in floatComp) {
                    coords[id] = (pos.first - minX + compOffsetX) to (pos.second - minY)
                }
                compOffsetX += (maxX - minX) + 2f
            }
        }

        // Ensure no non-member node sits inside any group's grid bounding box.
        fun isInsideGroupHierarchy(nodeId: String, groupId: String): Boolean {
            var cur = parentByNode[nodeId]
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                if (cur == groupId) return true
                cur = parentGroupMap[cur]
            }
            return false
        }
        for (grp in diagram.groups) {
            val memberIds = nodeIds.filter { isInsideGroupHierarchy(it, grp.id) }
            if (memberIds.isEmpty()) continue
            val gMinX = memberIds.minOf { coords.getValue(it).first }
            val gMaxX = memberIds.maxOf { coords.getValue(it).first }
            val gMinY = memberIds.minOf { coords.getValue(it).second }
            val gMaxY = memberIds.maxOf { coords.getValue(it).second }
            val intruders = nodeIds.filter { id ->
                !isInsideGroupHierarchy(id, grp.id) &&
                    coords.getValue(id).first in (gMinX - 0.1f)..(gMaxX + 0.1f) &&
                    coords.getValue(id).second in (gMinY - 0.1f)..(gMaxY + 0.1f)
            }
            if (intruders.isNotEmpty()) {
                val minIntruderX = intruders.minOf { coords.getValue(it).first }
                val pushRight = (gMaxX + 1f) - minIntruderX
                if (pushRight > 0f) {
                    for (id in nodeIds) {
                        if (!isInsideGroupHierarchy(id, grp.id) && coords.getValue(id).first >= minIntruderX - 0.01f) {
                            val old = coords.getValue(id)
                            coords[id] = (old.first + pushRight) to old.second
                        }
                    }
                }
            }
        }

        return coords
    }

    private fun mergeOverlappingAlignments(
        alignments: List<dev.maradona.model.ArchitectureAlignment>,
        parentByNode: Map<String, String?>,
        topGroupSortKey: (String) -> Int,
    ): List<dev.maradona.model.ArchitectureAlignment> {
        val result = mutableListOf<dev.maradona.model.ArchitectureAlignment>()
        for (dir in listOf(ArchitectureAlignDir.Row, ArchitectureAlignDir.Column)) {
            val sameDir = alignments.filter { it.direction == dir }
            if (sameDir.isEmpty()) continue
            val parent = LinkedHashMap<String, String>()
            fun find(x: String): String {
                var r = x
                while (parent[r] != null && parent[r] != r) {
                    r = parent.getValue(r)
                }
                var cur = x
                while (cur != r) {
                    val nxt = parent.getValue(cur)
                    parent[cur] = r
                    cur = nxt
                }
                return r
            }
            fun union(a: String, b: String) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[rb] = ra
            }
            val orderEdges = LinkedHashMap<String, MutableSet<String>>()
            for (a in sameDir) {
                for (m in a.members) {
                    parent.putIfAbsent(m, m)
                    orderEdges.putIfAbsent(m, LinkedHashSet())
                }
                for (i in 0 until a.members.size - 1) {
                    val u = a.members[i]
                    val v = a.members[i + 1]
                    union(u, v)
                    orderEdges.getValue(u) += v
                }
            }
            val groups = parent.keys.groupBy { find(it) }
            for ((_, members) in groups) {
                if (members.size < 2) continue
                val inDegree = members.associateWith { 0 }.toMutableMap()
                for (u in members) {
                    for (v in orderEdges[u].orEmpty()) {
                        if (v in inDegree) inDegree[v] = inDegree.getValue(v) + 1
                    }
                }
                val ready = members.filter { inDegree[it] == 0 }.toMutableList()
                val sorted = mutableListOf<String>()
                while (ready.isNotEmpty()) {
                    val prevGroup = sorted.lastOrNull()?.let { parentByNode[it] }
                    ready.sortWith(
                        compareBy<String> { if (prevGroup != null && parentByNode[it] == prevGroup) 0 else 1 }
                            .thenBy { topGroupSortKey(it) },
                    )
                    val u = ready.removeAt(0)
                    sorted += u
                    for (v in orderEdges[u].orEmpty()) {
                        if (v in inDegree) {
                            val deg = inDegree.getValue(v) - 1
                            inDegree[v] = deg
                            if (deg == 0) ready.add(v)
                        }
                    }
                }
                for (m in members) {
                    if (m !in sorted) sorted += m
                }
                result += dev.maradona.model.ArchitectureAlignment(direction = dir, members = sorted)
            }
        }
        return result
    }

    private fun stepForDirPair(lhs: ArchitecturePortDir, rhs: ArchitecturePortDir): Pair<Int, Int> {
        val lhsX = lhs == ArchitecturePortDir.L || lhs == ArchitecturePortDir.R
        val rhsX = rhs == ArchitecturePortDir.L || rhs == ArchitecturePortDir.R
        val lhsY = !lhsX
        val rhsY = !rhsX
        return when {
            lhsX && rhsY -> {
                val dx = if (lhs == ArchitecturePortDir.L) -1 else 1
                // In Mermaid's shiftPositionByArchitectureDirectionPair, y+1 is UP (screen dy = -1), y-1 is DOWN (screen dy = +1):
                // rhs == T means entering rhs from its Top port, so rhs is BELOW lhs (screen dy = +1).
                val dy = if (rhs == ArchitecturePortDir.T) 1 else -1
                dx to dy
            }
            lhsX && rhsX -> {
                val dx = if (lhs == ArchitecturePortDir.L) -1 else 1
                dx to 0
            }
            lhsY && rhsX -> {
                val dx = if (rhs == ArchitecturePortDir.L) 1 else -1
                val dy = if (lhs == ArchitecturePortDir.T) -1 else 1
                dx to dy
            }
            else -> {
                val dy = if (lhs == ArchitecturePortDir.T) -1 else 1
                0 to dy
            }
        }
    }

    private fun resolvePortEndpoint(
        nodeId: String,
        dir: ArchitecturePortDir,
        useGroupBorder: Boolean,
        hasArrow: Boolean,
        serviceById: Map<String, PositionedArchitectureService>,
        junctionById: Map<String, PositionedArchitectureJunction>,
        groupById: Map<String, PositionedArchitectureGroup>,
        d: Float,
    ): DiagramPoint {
        val svc = serviceById[nodeId]
        val junc = junctionById[nodeId]
        val grp = groupById[nodeId]

        val baseRect: DiagramRect
        val isJunction: Boolean
        val parentGroupId: String?
        when {
            svc != null -> {
                baseRect = if (dir == ArchitecturePortDir.B && svc.labelRect != null) {
                    DiagramRect(
                        x = svc.iconRect.x,
                        y = svc.iconRect.y,
                        width = svc.iconRect.width,
                        height = svc.bounds.height,
                    )
                } else {
                    svc.iconRect
                }
                isJunction = false
                parentGroupId = svc.service.parentId
            }
            junc != null -> {
                baseRect = junc.rect
                isJunction = true
                parentGroupId = junc.junction.parentId
            }
            grp != null -> {
                baseRect = grp.rect
                isJunction = false
                parentGroupId = grp.group.parentId
            }
            else -> return DiagramPoint(0f, 0f)
        }

        if (useGroupBorder && parentGroupId != null) {
            val parentGrp = groupById[parentGroupId]
            if (parentGrp != null) {
                return when (dir) {
                    ArchitecturePortDir.L -> DiagramPoint(parentGrp.rect.x, baseRect.centerY)
                    ArchitecturePortDir.R -> DiagramPoint(parentGrp.rect.right, baseRect.centerY)
                    ArchitecturePortDir.T -> DiagramPoint(baseRect.centerX, parentGrp.rect.y)
                    ArchitecturePortDir.B -> DiagramPoint(baseRect.centerX, parentGrp.rect.bottom)
                }
            }
        }

        if (isJunction && !hasArrow) {
            return DiagramPoint(baseRect.centerX, baseRect.centerY)
        }

        return when (dir) {
            ArchitecturePortDir.L -> DiagramPoint(baseRect.x, baseRect.centerY)
            ArchitecturePortDir.R -> DiagramPoint(baseRect.right, baseRect.centerY)
            ArchitecturePortDir.T -> DiagramPoint(baseRect.centerX, baseRect.y)
            ArchitecturePortDir.B -> DiagramPoint(baseRect.centerX, baseRect.bottom)
        }
    }

    private fun routeArchitectureEdgePoints(
        start: DiagramPoint,
        startDir: ArchitecturePortDir,
        end: DiagramPoint,
        endDir: ArchitecturePortDir,
        detourClearance: Float = 56f,
        d: Float = 1f,
    ): List<DiagramPoint> {
        val stub = 20f * d
        if (startDir == endDir) {
            return when (startDir) {
                ArchitecturePortDir.R -> {
                    if (abs(start.y - end.y) < detourClearance) {
                        val sStubX = start.x + stub
                        val eStubX = end.x + stub
                        val detourY = max(start.y, end.y) + detourClearance
                        listOf(
                            start,
                            DiagramPoint(sStubX, start.y),
                            DiagramPoint(sStubX, detourY),
                            DiagramPoint(eStubX, detourY),
                            DiagramPoint(eStubX, end.y),
                            end,
                        )
                    } else {
                        val outX = max(start.x, end.x) + stub
                        listOf(start, DiagramPoint(outX, start.y), DiagramPoint(outX, end.y), end)
                    }
                }
                ArchitecturePortDir.L -> {
                    if (abs(start.y - end.y) < detourClearance) {
                        val sStubX = start.x - stub
                        val eStubX = end.x - stub
                        val detourY = max(start.y, end.y) + detourClearance
                        listOf(
                            start,
                            DiagramPoint(sStubX, start.y),
                            DiagramPoint(sStubX, detourY),
                            DiagramPoint(eStubX, detourY),
                            DiagramPoint(eStubX, end.y),
                            end,
                        )
                    } else {
                        val outX = min(start.x, end.x) - stub
                        listOf(start, DiagramPoint(outX, start.y), DiagramPoint(outX, end.y), end)
                    }
                }
                ArchitecturePortDir.B -> {
                    if (abs(start.x - end.x) < detourClearance) {
                        val sStubY = start.y + stub
                        val eStubY = end.y + stub
                        val detourX = max(start.x, end.x) + detourClearance
                        listOf(
                            start,
                            DiagramPoint(start.x, sStubY),
                            DiagramPoint(detourX, sStubY),
                            DiagramPoint(detourX, eStubY),
                            DiagramPoint(end.x, eStubY),
                            end,
                        )
                    } else {
                        val outY = max(start.y, end.y) + stub
                        listOf(start, DiagramPoint(start.x, outY), DiagramPoint(end.x, outY), end)
                    }
                }
                ArchitecturePortDir.T -> {
                    if (abs(start.x - end.x) < detourClearance) {
                        val sStubY = start.y - stub
                        val eStubY = end.y - stub
                        val detourX = max(start.x, end.x) + detourClearance
                        listOf(
                            start,
                            DiagramPoint(start.x, sStubY),
                            DiagramPoint(detourX, sStubY),
                            DiagramPoint(detourX, eStubY),
                            DiagramPoint(end.x, eStubY),
                            end,
                        )
                    } else {
                        val outY = min(start.y, end.y) - stub
                        listOf(start, DiagramPoint(start.x, outY), DiagramPoint(end.x, outY), end)
                    }
                }
            }
        }

        val startXAxis = startDir == ArchitecturePortDir.L || startDir == ArchitecturePortDir.R
        val endXAxis = endDir == ArchitecturePortDir.L || endDir == ArchitecturePortDir.R
        val isXY = startXAxis != endXAxis

        if (!isXY) {
            if (startXAxis) {
                val forward = (startDir == ArchitecturePortDir.R && end.x >= start.x) ||
                    (startDir == ArchitecturePortDir.L && end.x <= start.x)
                if (forward) {
                    if (abs(start.y - end.y) < 1f) return listOf(start, end)
                    val midX = (start.x + end.x) / 2f
                    return listOf(start, DiagramPoint(midX, start.y), DiagramPoint(midX, end.y), end)
                }
                // Backward horizontal connection (e.g. b:R --> L:c when c is left of b): route via outward stubs and external lane.
                val sStubX = start.x + if (startDir == ArchitecturePortDir.R) stub else -stub
                val eStubX = end.x + if (endDir == ArchitecturePortDir.R) stub else -stub
                val detourY = max(start.y, end.y) + detourClearance
                return listOf(
                    start,
                    DiagramPoint(sStubX, start.y),
                    DiagramPoint(sStubX, detourY),
                    DiagramPoint(eStubX, detourY),
                    DiagramPoint(eStubX, end.y),
                    end,
                )
            } else {
                val forward = (startDir == ArchitecturePortDir.B && end.y >= start.y) ||
                    (startDir == ArchitecturePortDir.T && end.y <= start.y)
                if (forward) {
                    if (abs(start.x - end.x) < 1f) return listOf(start, end)
                    val midY = (start.y + end.y) / 2f
                    return listOf(start, DiagramPoint(start.x, midY), DiagramPoint(end.x, midY), end)
                }
                // Backward vertical connection: route via outward stubs and external vertical lane.
                val sStubY = start.y + if (startDir == ArchitecturePortDir.B) stub else -stub
                val eStubY = end.y + if (endDir == ArchitecturePortDir.B) stub else -stub
                val detourX = max(start.x, end.x) + detourClearance
                return listOf(
                    start,
                    DiagramPoint(start.x, sStubY),
                    DiagramPoint(detourX, sStubY),
                    DiagramPoint(detourX, eStubY),
                    DiagramPoint(end.x, eStubY),
                    end,
                )
            }
        }

        if (startXAxis) {
            // startDir is L or R, endDir is T or B
            val startOutwardOk = (startDir == ArchitecturePortDir.R && end.x >= start.x + stub) ||
                (startDir == ArchitecturePortDir.L && end.x <= start.x - stub)
            val endOutwardOk = (endDir == ArchitecturePortDir.T && start.y <= end.y - stub) ||
                (endDir == ArchitecturePortDir.B && start.y >= end.y + stub)
            if (startOutwardOk && endOutwardOk) {
                return listOf(start, DiagramPoint(end.x, start.y), end)
            }
            val sStubX = start.x + if (startDir == ArchitecturePortDir.R) stub else -stub
            val eStubY = end.y + if (endDir == ArchitecturePortDir.B) stub else -stub
            return listOf(
                start,
                DiagramPoint(sStubX, start.y),
                DiagramPoint(sStubX, eStubY),
                DiagramPoint(end.x, eStubY),
                end,
            )
        } else {
            // startDir is T or B, endDir is L or R
            val startOutwardOk = (startDir == ArchitecturePortDir.B && end.y >= start.y + stub) ||
                (startDir == ArchitecturePortDir.T && end.y <= start.y - stub)
            val endOutwardOk = (endDir == ArchitecturePortDir.L && start.x <= end.x - stub) ||
                (endDir == ArchitecturePortDir.R && start.x >= end.x + stub)
            if (startOutwardOk && endOutwardOk) {
                return listOf(start, DiagramPoint(start.x, end.y), end)
            }
            val sStubY = start.y + if (startDir == ArchitecturePortDir.B) stub else -stub
            val eStubX = end.x + if (endDir == ArchitecturePortDir.R) stub else -stub
            return listOf(
                start,
                DiagramPoint(start.x, sStubY),
                DiagramPoint(eStubX, sStubY),
                DiagramPoint(eStubX, end.y),
                end,
            )
        }
    }

    private fun computePolylineMidpoint(pts: List<DiagramPoint>): DiagramPoint {
        if (pts.isEmpty()) return DiagramPoint(0f, 0f)
        if (pts.size == 1) return pts[0]
        var totalLen = 0f
        val segLens = FloatArray(pts.size - 1)
        for (i in 0 until pts.size - 1) {
            val dx = pts[i + 1].x - pts[i].x
            val dy = pts[i + 1].y - pts[i].y
            val len = kotlin.math.hypot(dx, dy)
            segLens[i] = len
            totalLen += len
        }
        val half = totalLen / 2f
        var acc = 0f
        for (i in segLens.indices) {
            val l = segLens[i]
            if (acc + l >= half && l > 0.001f) {
                val t = (half - acc) / l
                return DiagramPoint(
                    x = pts[i].x + (pts[i + 1].x - pts[i].x) * t,
                    y = pts[i].y + (pts[i + 1].y - pts[i].y) * t,
                )
            }
            acc += l
        }
        return pts[pts.size / 2]
    }
}
