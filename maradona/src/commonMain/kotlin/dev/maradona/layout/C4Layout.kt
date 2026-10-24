package dev.maradona.layout

import dev.maradona.model.C4Boundary
import dev.maradona.model.C4Diagram
import dev.maradona.model.C4Element
import dev.maradona.model.C4RelKind
import dev.maradona.model.C4Relationship
import dev.maradona.model.Direction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Positioned C4 element card in a laid-out [C4Diagram].
 */
data class PositionedC4Element(
    val element: C4Element,
    val rect: DiagramRect,
)

/**
 * Positioned C4 boundary or deployment node box in a laid-out [C4Diagram].
 */
data class PositionedC4Boundary(
    val boundary: C4Boundary,
    val depth: Int,
    val rect: DiagramRect,
    val headerRect: DiagramRect,
)

/**
 * Positioned relationship edge and label in a laid-out [C4Diagram].
 */
data class PositionedC4Relationship(
    val relationshipIndex: Int,
    val relationship: C4Relationship,
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect?,
    val startArrow: Boolean,
    val endArrow: Boolean,
)

/**
 * Complete layout geometry for a [C4Diagram].
 */
data class C4DiagramLayout(
    val width: Float,
    val height: Float,
    val elements: List<PositionedC4Element>,
    val boundaries: List<PositionedC4Boundary>,
    val relationships: List<PositionedC4Relationship>,
    val titleRect: DiagramRect?,
)

/**
 * Computes hierarchical boundary, element card, and relationship geometry for a [C4Diagram].
 */
object C4Layout {

    private const val DEFAULT_CARD_WIDTH = 190f
    private const val DEFAULT_CARD_HEIGHT = 104f
    private const val PERSON_EXTRA_HEIGHT = 18f
    private const val CARD_GAP_X = 48f
    private const val CARD_GAP_Y = 56f
    private const val BOUNDARY_PAD_X = 24f
    private const val BOUNDARY_PAD_BOTTOM = 24f
    private const val BOUNDARY_HEADER_HEIGHT = 38f
    private const val BOUNDARY_GAP_X = 44f
    private const val BOUNDARY_GAP_Y = 48f
    private const val TITLE_GAP = 18f

    fun defaultElementSize(element: C4Element, density: Float = 1f): DiagramSize {
        val labelLines = element.label.lines().size.coerceAtLeast(1)
        val descrLines = element.description?.lines()?.sumOf { (it.length / 26) + 1 } ?: 0
        val longestLine = max(
            element.label.lines().maxOfOrNull { it.length } ?: 10,
            (element.technology?.length ?: 0) + 14,
        )
        val w = max(DEFAULT_CARD_WIDTH, (longestLine * 7.2f + 36f).coerceAtMost(280f)) * density
        val personExtra = if (element.kind.isPerson) PERSON_EXTRA_HEIGHT else 0f
        val dbExtra = if (element.kind.isDatabase) 10f else 0f
        val h = (DEFAULT_CARD_HEIGHT + personExtra + dbExtra + (labelLines - 1) * 16f + max(0, descrLines - 1) * 14f) * density
        return DiagramSize(w, h)
    }

    fun defaultBoundaryHeaderSize(boundary: C4Boundary, density: Float = 1f): DiagramSize {
        val textLen = boundary.label.length + boundary.type.length + 5
        return DiagramSize(
            width = max(140f, textLen * 7.5f + 28f) * density,
            height = BOUNDARY_HEADER_HEIGHT * density,
        )
    }

    fun defaultRelLabelSize(rel: C4Relationship, density: Float = 1f): DiagramSize {
        val mainLen = rel.label.length + (rel.index?.let { it.length + 3 } ?: 0)
        val techLen = rel.technology?.let { it.length + 2 } ?: 0
        val maxChars = max(mainLen, techLen).coerceAtLeast(4)
        val lines = (if (rel.label.isNotBlank()) rel.label.lines().size else 1) +
            (if (!rel.technology.isNullOrBlank()) 1 else 0)
        return DiagramSize(
            width = (maxChars * 6.8f + 16f).coerceAtMost(220f) * density,
            height = (lines * 15f + 8f) * density,
        )
    }

    fun defaultTitleSize(text: String, density: Float = 1f): DiagramSize =
        DiagramSize((text.length * 9.5f + 12f) * density, 24f * density)

    private sealed class LayoutTreeItem {
        abstract val id: String
        abstract var width: Float
        abstract var height: Float

        class ElementNode(
            val element: C4Element,
            override var width: Float,
            override var height: Float,
        ) : LayoutTreeItem() {
            override val id: String get() = element.id
        }

        class BoundaryNode(
            val boundary: C4Boundary,
            val depth: Int,
            val headerSize: DiagramSize,
            val childElements: List<ElementNode>,
            val childBoundaries: List<BoundaryNode>,
            override var width: Float = 0f,
            override var height: Float = 0f,
            var localChildOffsets: Map<String, DiagramPoint> = emptyMap(),
        ) : LayoutTreeItem() {
            override val id: String get() = boundary.id
        }
    }

    fun layout(
        diagram: C4Diagram,
        elementSizes: Map<String, DiagramSize> = emptyMap(),
        boundaryHeaderSizes: Map<String, DiagramSize> = emptyMap(),
        relLabelSizes: Map<Int, DiagramSize> = emptyMap(),
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): C4DiagramLayout {
        val d = options.density.coerceAtLeast(0.01f)
        val effectiveDirection = options.directionOverride ?: diagram.direction
        val cfg = diagram.config
        val shapeInRow = cfg.c4ShapeInRow.coerceAtLeast(1)
        val boundaryInRow = cfg.c4BoundaryInRow.coerceAtLeast(1)
        val marginX = cfg.diagramMarginX.coerceAtLeast(12f) * d
        val marginY = cfg.diagramMarginY.coerceAtLeast(12f) * d
        val cardGapX = CARD_GAP_X * d
        val cardGapY = CARD_GAP_Y * d
        val bndPadX = BOUNDARY_PAD_X * d
        val bndPadBottom = BOUNDARY_PAD_BOTTOM * d
        val bndGapX = BOUNDARY_GAP_X * d
        val bndGapY = BOUNDARY_GAP_Y * d

        val titleText = diagram.title?.takeIf { it.isNotBlank() }
        val resolvedTitleSize = if (titleText != null) {
            titleSize ?: defaultTitleSize(titleText, d)
        } else {
            null
        }
        val titleTopSpace = if (resolvedTitleSize != null) resolvedTitleSize.height + TITLE_GAP * d else 0f

        val validBoundaryIds = diagram.boundaries.map { it.id }.toSet()
        val elementsByParent = diagram.elements.groupBy { el ->
            el.parentBoundaryId?.takeIf { it in validBoundaryIds }
        }
        val boundariesByParent = diagram.boundaries.groupBy { b ->
            b.parentBoundaryId?.takeIf { it in validBoundaryIds && it != b.id }
        }

        val visitedBoundaries = HashSet<String>()

        // Precompute horizontal extra width needed to the right of any node that has self-relationships
        val selfRelCounts = HashMap<String, Int>()
        val selfRelMaxLblW = HashMap<String, Float>()
        for ((idx, rel) in diagram.relationships.withIndex()) {
            if (rel.from == rel.to) {
                selfRelCounts[rel.from] = (selfRelCounts[rel.from] ?: 0) + 1
                val hasLbl = rel.label.isNotBlank() || !rel.technology.isNullOrBlank() || !rel.index.isNullOrBlank()
                val w = if (hasLbl) (relLabelSizes[idx] ?: defaultRelLabelSize(rel, d)).width else 0f
                selfRelMaxLblW[rel.from] = max(selfRelMaxLblW[rel.from] ?: 0f, w)
            }
        }
        val selfLoopExtraW = HashMap<String, Float>()
        for ((nodeId, count) in selfRelCounts) {
            val lblW = selfRelMaxLblW[nodeId] ?: 0f
            val loopStep = max(lblW + 24f * d, 42f * d)
            val rightmostX = 16f * d + lblW + 12f * d + (count - 1) * loopStep
            selfLoopExtraW[nodeId] = rightmostX + 12f * d
        }

        fun buildBoundarySubtree(b: C4Boundary, depth: Int): LayoutTreeItem.BoundaryNode {
            visitedBoundaries += b.id
            val hSize = boundaryHeaderSizes[b.id] ?: defaultBoundaryHeaderSize(b, d)
            val childEls = (elementsByParent[b.id] ?: emptyList()).map { el ->
                val sz = elementSizes[el.id] ?: defaultElementSize(el, d)
                LayoutTreeItem.ElementNode(el, sz.width, sz.height)
            }
            val childBnds = (boundariesByParent[b.id] ?: emptyList())
                .filter { it.id !in visitedBoundaries }
                .map { childB -> buildBoundarySubtree(childB, depth + 1) }

            val node = LayoutTreeItem.BoundaryNode(
                boundary = b,
                depth = depth,
                headerSize = hSize,
                childElements = childEls,
                childBoundaries = childBnds,
            )
            measureBoundaryNode(
                node = node,
                relationships = diagram.relationships,
                selfLoopExtraW = selfLoopExtraW,
                direction = effectiveDirection,
                shapeInRow = shapeInRow,
                boundaryInRow = boundaryInRow,
                cardGapX = cardGapX,
                cardGapY = cardGapY,
                bndGapX = bndGapX,
                bndGapY = bndGapY,
                bndPadX = bndPadX,
                bndPadBottom = bndPadBottom,
                d = d,
            )
            return node
        }

        val rootElements = (elementsByParent[null] ?: emptyList()).map { el ->
            val sz = elementSizes[el.id] ?: defaultElementSize(el, d)
            LayoutTreeItem.ElementNode(el, sz.width, sz.height)
        }
        val rootBoundaries = (boundariesByParent[null] ?: emptyList())
            .filter { it.id !in visitedBoundaries }
            .map { buildBoundarySubtree(it, depth = 1) }
        // Include any orphan boundary cycles if present
        val extraBoundaries = diagram.boundaries
            .filter { it.id !in visitedBoundaries }
            .map { buildBoundarySubtree(it, depth = 1) }
        val allRootBoundaries = rootBoundaries + extraBoundaries

        // Arrange root items into bands:
        // 1. Root non-external Person/System elements above/before boundaries
        // 2. Root boundaries in the middle
        // 3. Root external elements after/below boundaries (unless no boundaries exist, in which case rank by relationships)
        val elementRects = LinkedHashMap<String, DiagramRect>()
        val boundaryRects = LinkedHashMap<String, PositionedC4Boundary>()

        fun placeBoundarySubtree(node: LayoutTreeItem.BoundaryNode, x: Float, y: Float) {
            val rect = DiagramRect(x, y, node.width, node.height)
            val hRect = DiagramRect(
                x = x,
                y = y,
                width = min(node.width, node.headerSize.width + 16f * d),
                height = min(node.height, max(BOUNDARY_HEADER_HEIGHT * d, node.headerSize.height)),
            )
            boundaryRects[node.id] = PositionedC4Boundary(
                boundary = node.boundary,
                depth = node.depth,
                rect = rect,
                headerRect = hRect,
            )
            for (elNode in node.childElements) {
                val off = node.localChildOffsets[elNode.id] ?: DiagramPoint(bndPadX, hRect.height + 12f * d)
                elementRects[elNode.id] = DiagramRect(x + off.x, y + off.y, elNode.width, elNode.height)
            }
            for (childBnd in node.childBoundaries) {
                val off = node.localChildOffsets[childBnd.id] ?: DiagramPoint(bndPadX, hRect.height + 12f * d)
                placeBoundarySubtree(childBnd, x + off.x, y + off.y)
            }
        }

        // Build top-level rows of items
        val topBands = mutableListOf<List<LayoutTreeItem>>()
        if (allRootBoundaries.isEmpty()) {
            // Rank root elements by relationship topological depth or chunk by shapeInRow
            val rankedRows = rankItemsIntoRows(rootElements, diagram.relationships, shapeInRow, effectiveDirection)
            topBands.addAll(rankedRows)
        } else {
            val (leadingEls, trailingEls) = rootElements.partition { !it.element.kind.isExternal }
            topBands.addAll(rankItemsIntoRows(leadingEls, diagram.relationships, shapeInRow, effectiveDirection))
            topBands.addAll(rankItemsIntoRows(allRootBoundaries, diagram.relationships, boundaryInRow, effectiveDirection))
            topBands.addAll(rankItemsIntoRows(trailingEls, diagram.relationships, shapeInRow, effectiveDirection))
        }

        val isHorizontal = effectiveDirection == Direction.LR || effectiveDirection == Direction.RL
        val orderedBands = if (effectiveDirection == Direction.BT || effectiveDirection == Direction.RL) {
            topBands.asReversed()
        } else {
            topBands
        }

        fun itemAllocatedWidth(item: LayoutTreeItem): Float = item.width + (selfLoopExtraW[item.id] ?: 0f)

        val bandSizes = orderedBands.map { band ->
            if (!isHorizontal) {
                val w = band.sumOf { itemAllocatedWidth(it).toDouble() }.toFloat() + max(0, band.size - 1) * cardGapX
                val h = band.maxOfOrNull { it.height } ?: 0f
                DiagramSize(w, h)
            } else {
                val w = band.maxOfOrNull { itemAllocatedWidth(it) } ?: 0f
                val h = band.sumOf { it.height.toDouble() }.toFloat() + max(0, band.size - 1) * cardGapY
                DiagramSize(w, h)
            }
        }

        val contentWidth = if (!isHorizontal) {
            bandSizes.maxOfOrNull { it.width } ?: (200f * d)
        } else {
            bandSizes.sumOf { it.width.toDouble() }.toFloat() + max(0, bandSizes.size - 1) * cardGapX * 1.35f
        }
        val contentHeight = if (!isHorizontal) {
            bandSizes.sumOf { it.height.toDouble() }.toFloat() + max(0, bandSizes.size - 1) * cardGapY * 1.25f
        } else {
            bandSizes.maxOfOrNull { it.height } ?: (120f * d)
        }

        val minTitleWidth = if (resolvedTitleSize != null) resolvedTitleSize.width + marginX * 2f else 0f
        val totalWidth = max(contentWidth + marginX * 2f, minTitleWidth)
        val totalHeight = marginY + titleTopSpace + contentHeight + marginY

        val contentStartX = (totalWidth - contentWidth) / 2f
        val contentStartY = marginY + titleTopSpace

        if (!isHorizontal) {
            var curY = contentStartY
            for ((bIdx, band) in orderedBands.withIndex()) {
                val bSize = bandSizes[bIdx]
                var curX = contentStartX + (contentWidth - bSize.width) / 2f
                for (item in band) {
                    val itemY = curY + (bSize.height - item.height) / 2f
                    when (item) {
                        is LayoutTreeItem.ElementNode -> {
                            elementRects[item.id] = DiagramRect(curX, itemY, item.width, item.height)
                        }
                        is LayoutTreeItem.BoundaryNode -> {
                            placeBoundarySubtree(item, curX, itemY)
                        }
                    }
                    curX += itemAllocatedWidth(item) + cardGapX
                }
                curY += bSize.height + cardGapY * 1.25f
            }
        } else {
            var curX = contentStartX
            for ((bIdx, band) in orderedBands.withIndex()) {
                val bSize = bandSizes[bIdx]
                var curY = contentStartY + (contentHeight - bSize.height) / 2f
                for (item in band) {
                    val itemX = curX + (bSize.width - itemAllocatedWidth(item)) / 2f
                    when (item) {
                        is LayoutTreeItem.ElementNode -> {
                            elementRects[item.id] = DiagramRect(itemX, curY, item.width, item.height)
                        }
                        is LayoutTreeItem.BoundaryNode -> {
                            placeBoundarySubtree(item, itemX, curY)
                        }
                    }
                    curY += item.height + cardGapY
                }
                curX += bSize.width + cardGapX * 1.35f
            }
        }

        val positionedElements = diagram.elements.mapNotNull { el ->
            val r = elementRects[el.id] ?: return@mapNotNull null
            PositionedC4Element(element = el, rect = r)
        }
        val positionedBoundaries = diagram.boundaries
            .mapNotNull { b -> boundaryRects[b.id] }
            .sortedBy { it.depth }

        // Route relationships between elements (or boundaries)
        val pairCounts = HashMap<Pair<String, String>, Int>()
        val pairMaxLblWidth = HashMap<Pair<String, String>, Float>()
        val pairMaxLblHeight = HashMap<Pair<String, String>, Float>()
        for ((idx, rel) in diagram.relationships.withIndex()) {
            val k = if (rel.from <= rel.to) rel.from to rel.to else rel.to to rel.from
            pairCounts[k] = (pairCounts[k] ?: 0) + 1
            val hasLabel = rel.label.isNotBlank() || !rel.technology.isNullOrBlank() || !rel.index.isNullOrBlank()
            if (hasLabel) {
                val sz = relLabelSizes[idx] ?: defaultRelLabelSize(rel, d)
                pairMaxLblWidth[k] = max(pairMaxLblWidth[k] ?: 0f, sz.width)
                pairMaxLblHeight[k] = max(pairMaxLblHeight[k] ?: 0f, sz.height)
            }
        }
        val pairSeen = HashMap<Pair<String, String>, Int>()

        val positionedRelationships = mutableListOf<PositionedC4Relationship>()
        for ((relIdx, rel) in diagram.relationships.withIndex()) {
            val fromRect = elementRects[rel.from] ?: boundaryRects[rel.from]?.rect ?: continue
            val toRect = elementRects[rel.to] ?: boundaryRects[rel.to]?.rect ?: continue

            val pairKey = if (rel.from <= rel.to) rel.from to rel.to else rel.to to rel.from
            val totalForPair = pairCounts[pairKey] ?: 1
            val seenIdx = pairSeen[pairKey] ?: 0
            pairSeen[pairKey] = seenIdx + 1

            val hasLabel = rel.label.isNotBlank() || !rel.technology.isNullOrBlank() || !rel.index.isNullOrBlank()
            val lblSize = if (hasLabel) {
                relLabelSizes[relIdx] ?: defaultRelLabelSize(rel, d)
            } else {
                null
            }

            val points: List<DiagramPoint>
            val midAnchor: DiagramPoint
            if (rel.from == rel.to) {
                val loopSpread = 12f * d
                val lblW = max(lblSize?.width ?: 0f, pairMaxLblWidth[pairKey] ?: 0f)
                val loopStep = max(lblW + 24f * d, 42f * d)
                val anchorX = fromRect.right + 16f * d + lblW / 2f + seenIdx * loopStep
                val loopRightX = anchorX + lblW / 2f + 12f * d
                val pStart = DiagramPoint(fromRect.right, (fromRect.centerY - loopSpread).coerceIn(fromRect.y, fromRect.bottom))
                val pMid = DiagramPoint(loopRightX, fromRect.centerY)
                val pEnd = DiagramPoint(fromRect.right, (fromRect.centerY + loopSpread).coerceIn(fromRect.y, fromRect.bottom))
                points = listOf(pStart, pMid, pEnd)
                midAnchor = DiagramPoint(anchorX, fromRect.centerY)
            } else {
                val pStart = clipLineFromCenter(fromRect, DiagramPoint(toRect.centerX, toRect.centerY))
                val pEnd = clipLineFromCenter(toRect, DiagramPoint(fromRect.centerX, fromRect.centerY))

                // Use canonical endpoint order so opposite-direction relationships offset to opposite sides
                val canonStart = if (rel.from <= rel.to) pStart else pEnd
                val canonEnd = if (rel.from <= rel.to) pEnd else pStart
                val dx = canonEnd.x - canonStart.x
                val dy = canonEnd.y - canonStart.y
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val nx = -dy / len
                val ny = dx / len

                val maxLblW = pairMaxLblWidth[pairKey] ?: (64f * d)
                val maxLblH = pairMaxLblHeight[pairKey] ?: (28f * d)
                val pairStep = max(44f * d, abs(nx) * maxLblW + abs(ny) * maxLblH + 16f * d)
                val pairOffset = if (totalForPair > 1) {
                    (seenIdx - (totalForPair - 1) / 2f) * pairStep
                } else {
                    0f
                }

                // Check if the straight line segment intersects any intervening element card
                val lineMidX = (pStart.x + pEnd.x) / 2f
                val lineMidY = (pStart.y + pEnd.y) / 2f
                var obstacleCurveDisplacement = 0f
                for ((otherId, otherRect) in elementRects) {
                    if (otherId == rel.from || otherId == rel.to) continue
                    val padded = DiagramRect(
                        x = otherRect.x - 10f * d,
                        y = otherRect.y - 10f * d,
                        width = otherRect.width + 20f * d,
                        height = otherRect.height + 20f * d,
                    )
                    if (segmentIntersectsRect(pStart, pEnd, padded)) {
                        // Compute perpendicular distance from line midpoint needed to clear the obstacle
                        val halfExtent = abs(nx) * (otherRect.width / 2f) + abs(ny) * (otherRect.height / 2f)
                        val needed = halfExtent + 44f * d
                        if (needed > abs(obstacleCurveDisplacement)) {
                            obstacleCurveDisplacement = if (pairOffset < -0.5f) -needed else needed
                        }
                    }
                }

                val curveDisplacement = if (abs(obstacleCurveDisplacement) > 0.5f) {
                    obstacleCurveDisplacement + pairOffset
                } else {
                    pairOffset
                }

                if (abs(curveDisplacement) > 0.5f) {
                    val mid = DiagramPoint(
                        x = lineMidX + nx * curveDisplacement * 2f,
                        y = lineMidY + ny * curveDisplacement * 2f,
                    )
                    points = listOf(pStart, mid, pEnd)
                    midAnchor = DiagramPoint(
                        x = lineMidX + nx * curveDisplacement,
                        y = lineMidY + ny * curveDisplacement,
                    )
                } else {
                    points = listOf(pStart, pEnd)
                    midAnchor = DiagramPoint(lineMidX, lineMidY)
                }
            }

            val offX = (rel.style?.offsetX ?: 0f) * d
            val offY = (rel.style?.offsetY ?: 0f) * d
            val labelRect = if (lblSize != null) {
                DiagramRect(
                    x = midAnchor.x + offX - lblSize.width / 2f,
                    y = midAnchor.y + offY - lblSize.height / 2f,
                    width = lblSize.width,
                    height = lblSize.height,
                )
            } else {
                null
            }

            val startArrow = rel.kind == C4RelKind.BI_REL || rel.kind == C4RelKind.REL_BACK
            val endArrow = rel.kind != C4RelKind.REL_BACK

            positionedRelationships += PositionedC4Relationship(
                relationshipIndex = relIdx,
                relationship = rel,
                points = points,
                labelRect = labelRect,
                startArrow = startArrow,
                endArrow = endArrow,
            )
        }

        val initialTitleRect = if (resolvedTitleSize != null) {
            DiagramRect(
                x = (totalWidth - resolvedTitleSize.width) / 2f,
                y = marginY,
                width = resolvedTitleSize.width,
                height = resolvedTitleSize.height,
            )
        } else {
            null
        }

        // Union all positioned elements, boundaries, relationship points, and relationship label rects into final bounds.
        var minX = marginX
        var minY = marginY
        var maxX = totalWidth - marginX
        var maxY = totalHeight - marginY

        fun includeRect(r: DiagramRect) {
            minX = min(minX, r.x)
            minY = min(minY, r.y)
            maxX = max(maxX, r.right)
            maxY = max(maxY, r.bottom)
        }

        initialTitleRect?.let { includeRect(it) }
        for (pe in positionedElements) includeRect(pe.rect)
        for (pb in positionedBoundaries) includeRect(pb.rect)
        for (pr in positionedRelationships) {
            for (pt in pr.points) {
                minX = min(minX, pt.x)
                minY = min(minY, pt.y)
                maxX = max(maxX, pt.x)
                maxY = max(maxY, pt.y)
            }
            pr.labelRect?.let { includeRect(it) }
        }

        val shiftX = if (minX < marginX) marginX - minX else 0f
        val shiftY = if (minY < marginY) marginY - minY else 0f
        val finalWidth = max(totalWidth + shiftX, maxX + shiftX + marginX)
        val finalHeight = max(totalHeight + shiftY, maxY + shiftY + marginY)

        fun shiftRect(r: DiagramRect): DiagramRect =
            if (shiftX == 0f && shiftY == 0f) r else DiagramRect(r.x + shiftX, r.y + shiftY, r.width, r.height)

        return C4DiagramLayout(
            width = finalWidth,
            height = finalHeight,
            elements = positionedElements.map { it.copy(rect = shiftRect(it.rect)) },
            boundaries = positionedBoundaries.map {
                it.copy(rect = shiftRect(it.rect), headerRect = shiftRect(it.headerRect))
            },
            relationships = positionedRelationships.map { pr ->
                pr.copy(
                    points = if (shiftX == 0f && shiftY == 0f) {
                        pr.points
                    } else {
                        pr.points.map { DiagramPoint(it.x + shiftX, it.y + shiftY) }
                    },
                    labelRect = pr.labelRect?.let { shiftRect(it) },
                )
            },
            titleRect = initialTitleRect?.let { shiftRect(it) },
        )
    }

    private fun measureBoundaryNode(
        node: LayoutTreeItem.BoundaryNode,
        relationships: List<C4Relationship>,
        selfLoopExtraW: Map<String, Float>,
        direction: Direction,
        shapeInRow: Int,
        boundaryInRow: Int,
        cardGapX: Float,
        cardGapY: Float,
        bndGapX: Float,
        bndGapY: Float,
        bndPadX: Float,
        bndPadBottom: Float,
        d: Float,
    ) {
        val headerH = max(BOUNDARY_HEADER_HEIGHT * d, node.headerSize.height) + 10f * d
        val isHorizontal = direction == Direction.LR || direction == Direction.RL
        val rawElBands = rankItemsIntoRows(node.childElements, relationships, shapeInRow, direction)
        val elBands = if (direction == Direction.BT || direction == Direction.RL) rawElBands.asReversed() else rawElBands
        val rawBndRows = rankItemsIntoRows(node.childBoundaries, relationships, boundaryInRow, direction)
        val bndRows = if (direction == Direction.BT || direction == Direction.RL) rawBndRows.asReversed() else rawBndRows

        fun allocW(item: LayoutTreeItem): Float = item.width + (selfLoopExtraW[item.id] ?: 0f)

        val elBandSizes = elBands.map { band ->
            if (!isHorizontal) {
                val w = band.sumOf { allocW(it).toDouble() }.toFloat() + max(0, band.size - 1) * cardGapX
                val h = band.maxOfOrNull { it.height } ?: 0f
                DiagramSize(w, h)
            } else {
                val w = band.maxOfOrNull { allocW(it) } ?: 0f
                val h = band.sumOf { it.height.toDouble() }.toFloat() + max(0, band.size - 1) * cardGapY
                DiagramSize(w, h)
            }
        }
        val bndBandSizes = bndRows.map { band ->
            if (!isHorizontal) {
                val w = band.sumOf { allocW(it).toDouble() }.toFloat() + max(0, band.size - 1) * bndGapX
                val h = band.maxOfOrNull { it.height } ?: 0f
                DiagramSize(w, h)
            } else {
                val w = band.maxOfOrNull { allocW(it) } ?: 0f
                val h = band.sumOf { it.height.toDouble() }.toFloat() + max(0, band.size - 1) * bndGapY
                DiagramSize(w, h)
            }
        }

        val elTotalW = if (!isHorizontal) {
            elBandSizes.maxOfOrNull { it.width } ?: 0f
        } else {
            elBandSizes.sumOf { it.width.toDouble() }.toFloat() + max(0, elBandSizes.size - 1) * cardGapX
        }
        val elTotalH = if (!isHorizontal) {
            elBandSizes.sumOf { it.height.toDouble() }.toFloat() + max(0, elBandSizes.size - 1) * cardGapY
        } else {
            elBandSizes.maxOfOrNull { it.height } ?: 0f
        }

        val bndTotalW = if (!isHorizontal) {
            bndBandSizes.maxOfOrNull { it.width } ?: 0f
        } else {
            bndBandSizes.sumOf { it.width.toDouble() }.toFloat() + max(0, bndBandSizes.size - 1) * bndGapX
        }
        val bndTotalH = if (!isHorizontal) {
            bndBandSizes.sumOf { it.height.toDouble() }.toFloat() + max(0, bndBandSizes.size - 1) * bndGapY
        } else {
            bndBandSizes.maxOfOrNull { it.height } ?: 0f
        }

        val maxRowW = max(elTotalW, bndTotalW)
        val innerW = max(maxRowW, node.headerSize.width + 16f * d)
        val totalW = max(200f * d, innerW + bndPadX * 2f)

        val offsets = LinkedHashMap<String, DiagramPoint>()
        var curY = headerH

        if (elBands.isNotEmpty()) {
            if (!isHorizontal) {
                for ((rIdx, band) in elBands.withIndex()) {
                    val bSize = elBandSizes[rIdx]
                    var curX = bndPadX + (innerW - bSize.width) / 2f
                    for (el in band) {
                        val elY = curY + (bSize.height - el.height) / 2f
                        offsets[el.id] = DiagramPoint(curX, elY)
                        curX += allocW(el) + cardGapX
                    }
                    curY += bSize.height + cardGapY
                }
            } else {
                var curX = bndPadX + (innerW - elTotalW) / 2f
                for ((rIdx, band) in elBands.withIndex()) {
                    val bSize = elBandSizes[rIdx]
                    var itemY = curY + (elTotalH - bSize.height) / 2f
                    for (el in band) {
                        val elX = curX + (bSize.width - allocW(el)) / 2f
                        offsets[el.id] = DiagramPoint(elX, itemY)
                        itemY += el.height + cardGapY
                    }
                    curX += bSize.width + cardGapX
                }
                curY += elTotalH + cardGapY
            }
        }

        if (bndRows.isNotEmpty()) {
            if (!isHorizontal) {
                for ((rIdx, row) in bndRows.withIndex()) {
                    val rSize = bndBandSizes[rIdx]
                    var curX = bndPadX + (innerW - rSize.width) / 2f
                    for (childB in row) {
                        val bY = curY + (rSize.height - childB.height) / 2f
                        offsets[childB.id] = DiagramPoint(curX, bY)
                        curX += allocW(childB) + bndGapX
                    }
                    curY += rSize.height + bndGapY
                }
            } else {
                var curX = bndPadX + (innerW - bndTotalW) / 2f
                for ((rIdx, band) in bndRows.withIndex()) {
                    val bSize = bndBandSizes[rIdx]
                    var itemY = curY + (bndTotalH - bSize.height) / 2f
                    for (childB in band) {
                        val bX = curX + (bSize.width - allocW(childB)) / 2f
                        offsets[childB.id] = DiagramPoint(bX, itemY)
                        itemY += childB.height + bndGapY
                    }
                    curX += bSize.width + bndGapX
                }
                curY += bndTotalH + bndGapY
            }
        }

        val contentBottom = if (elBands.isEmpty() && bndRows.isEmpty()) {
            headerH + 40f * d
        } else {
            val lastGap = if (bndRows.isNotEmpty()) bndGapY else cardGapY
            curY - lastGap + bndPadBottom
        }

        node.width = totalW
        node.height = max(110f * d, contentBottom)
        node.localChildOffsets = offsets
    }

    private fun <T : LayoutTreeItem> rankItemsIntoRows(
        elements: List<T>,
        relationships: List<C4Relationship>,
        shapeInRow: Int,
        direction: Direction,
    ): List<List<T>> {
        if (elements.isEmpty()) return emptyList()
        if (elements.size <= shapeInRow && relationships.isEmpty()) return listOf(elements)

        val isHorizontal = direction == Direction.LR || direction == Direction.RL
        val byId = elements.associateBy { it.id }
        val inDegree = HashMap<String, Int>()
        val adj = HashMap<String, MutableList<String>>()
        val sameBandOrder = HashMap<String, Int>()
        for ((idx, el) in elements.withIndex()) {
            inDegree[el.id] = 0
            adj[el.id] = mutableListOf()
            sameBandOrder[el.id] = idx * 10
        }
        val explicitDirPairs = relationships
            .filter {
                it.from != it.to && (
                    it.kind == C4RelKind.REL_UP ||
                        it.kind == C4RelKind.REL_DOWN ||
                        it.kind == C4RelKind.REL_LEFT ||
                        it.kind == C4RelKind.REL_RIGHT ||
                        it.kind == C4RelKind.REL_BACK
                    )
            }
            .map { if (it.from <= it.to) it.from to it.to else it.to to it.from }
            .toSet()

        val seenDirectedEdges = HashSet<Pair<String, String>>()
        val sameBandPairs = mutableListOf<Triple<String, String, C4RelKind>>()
        for (rel in relationships) {
            if (rel.from == rel.to || rel.from !in byId || rel.to !in byId) continue
            val unordered = if (rel.from <= rel.to) rel.from to rel.to else rel.to to rel.from
            val isExplicitDir = rel.kind == C4RelKind.REL_UP ||
                rel.kind == C4RelKind.REL_DOWN ||
                rel.kind == C4RelKind.REL_LEFT ||
                rel.kind == C4RelKind.REL_RIGHT ||
                rel.kind == C4RelKind.REL_BACK
            if (!isExplicitDir && unordered in explicitDirPairs) continue

            val isCrossBandHint = if (!isHorizontal) {
                rel.kind == C4RelKind.REL_LEFT || rel.kind == C4RelKind.REL_RIGHT
            } else {
                rel.kind == C4RelKind.REL_UP || rel.kind == C4RelKind.REL_DOWN
            }
            if (isCrossBandHint) {
                sameBandPairs += Triple(rel.from, rel.to, rel.kind)
                continue
            }
            val reverseFlow = when (rel.kind) {
                C4RelKind.REL_BACK -> true
                C4RelKind.REL_UP -> !isHorizontal && direction != Direction.BT
                C4RelKind.REL_DOWN -> !isHorizontal && direction == Direction.BT
                C4RelKind.REL_LEFT -> isHorizontal && direction != Direction.RL
                C4RelKind.REL_RIGHT -> isHorizontal && direction == Direction.RL
                else -> false
            }
            val src = if (reverseFlow) rel.to else rel.from
            val dst = if (reverseFlow) rel.from else rel.to
            if ((dst to src) in seenDirectedEdges || (src to dst) in seenDirectedEdges) continue
            seenDirectedEdges += (src to dst)
            adj.getValue(src) += dst
            inDegree[dst] = (inDegree[dst] ?: 0) + 1
        }

        val rankOf = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        for (el in elements) {
            if ((inDegree[el.id] ?: 0) == 0) {
                rankOf[el.id] = 0
                queue.addLast(el.id)
            }
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val r = rankOf[u] ?: 0
            for (v in adj[u].orEmpty()) {
                val nextRank = max(rankOf[v] ?: 0, r + 1)
                if (rankOf[v] == null || nextRank > (rankOf[v] ?: 0)) {
                    rankOf[v] = nextRank.coerceAtMost(elements.size)
                }
                val deg = (inDegree[v] ?: 1) - 1
                inDegree[v] = deg
                if (deg == 0) queue.addLast(v)
            }
        }
        for (el in elements) {
            if (el.id !in rankOf) rankOf[el.id] = 0
        }

        // Apply same-band hints (`Rel_L`/`Rel_R` in TB/BT, `Rel_U`/`Rel_D` in LR/RL)
        val declIndex = elements.withIndex().associate { (i, el) -> el.id to i }
        val sameBandEdges = LinkedHashMap<String, MutableSet<String>>()
        for (el in elements) sameBandEdges[el.id] = LinkedHashSet()
        var rankChanged = true
        var pass = 0
        while (rankChanged && pass < elements.size * 2) {
            rankChanged = false
            pass++
            for ((fromId, toId, _) in sameBandPairs) {
                val rFrom = rankOf[fromId] ?: 0
                val rTo = rankOf[toId] ?: 0
                val targetRank = max(rFrom, rTo)
                if (rFrom != targetRank) {
                    rankOf[fromId] = targetRank
                    rankChanged = true
                }
                if (rTo != targetRank) {
                    rankOf[toId] = targetRank
                    rankChanged = true
                }
            }
            for ((u, vs) in adj) {
                val ru = rankOf[u] ?: 0
                for (v in vs) {
                    val rv = rankOf[v] ?: 0
                    val nextRank = (ru + 1).coerceAtMost(elements.size)
                    if (rv < nextRank) {
                        rankOf[v] = nextRank
                        rankChanged = true
                    }
                }
            }
        }
        val seenBandEdges = HashSet<Pair<String, String>>()
        for ((fromId, toId, kind) in sameBandPairs) {
            val placeBefore = kind == C4RelKind.REL_LEFT || kind == C4RelKind.REL_UP
            val beforeId = if (placeBefore) toId else fromId
            val afterId = if (placeBefore) fromId else toId
            if ((afterId to beforeId) !in seenBandEdges && (beforeId to afterId) !in seenBandEdges) {
                seenBandEdges += (beforeId to afterId)
                sameBandEdges.getValue(beforeId) += afterId
            }
        }

        val distinctRanks = rankOf.values.distinct().sorted()
        val rows = mutableListOf<List<T>>()
        for (r in distinctRanks) {
            val inRank = elements.filter { rankOf[it.id] == r }
            val rankSet = inRank.map { it.id }.toSet()
            val bandInDeg = inRank.associate { it.id to 0 }.toMutableMap()
            for (u in rankSet) {
                for (v in sameBandEdges[u].orEmpty()) {
                    if (v in rankSet) bandInDeg[v] = (bandInDeg[v] ?: 0) + 1
                }
            }
            val ready = inRank.filter { (bandInDeg[it.id] ?: 0) == 0 }
                .sortedBy { declIndex[it.id] ?: 0 }
                .toMutableList()
            val orderedInRank = mutableListOf<T>()
            while (ready.isNotEmpty()) {
                val nextEl = ready.removeAt(0)
                orderedInRank += nextEl
                for (v in sameBandEdges[nextEl.id].orEmpty()) {
                    if (v in rankSet) {
                        val deg = (bandInDeg[v] ?: 1) - 1
                        bandInDeg[v] = deg
                        if (deg == 0) {
                            ready += byId.getValue(v)
                            ready.sortBy { declIndex[it.id] ?: 0 }
                        }
                    }
                }
            }
            for (el in inRank) {
                if (el !in orderedInRank) orderedInRank += el
            }
            rows.addAll(orderedInRank.chunked(shapeInRow))
        }
        return rows
    }

    private fun clipLineFromCenter(rect: DiagramRect, target: DiagramPoint): DiagramPoint {
        val cx = rect.centerX
        val cy = rect.centerY
        val dx = target.x - cx
        val dy = target.y - cy
        if (abs(dx) < 0.01f && abs(dy) < 0.01f) return DiagramPoint(cx, cy)
        val hw = (rect.width / 2f).coerceAtLeast(1f)
        val hh = (rect.height / 2f).coerceAtLeast(1f)
        val tx = if (abs(dx) > 0.01f) hw / abs(dx) else Float.MAX_VALUE
        val ty = if (abs(dy) > 0.01f) hh / abs(dy) else Float.MAX_VALUE
        val t = min(tx, ty)
        return DiagramPoint(cx + dx * t, cy + dy * t)
    }

    private fun segmentIntersectsRect(a: DiagramPoint, b: DiagramPoint, rect: DiagramRect): Boolean {
        val minSegX = min(a.x, b.x)
        val maxSegX = max(a.x, b.x)
        val minSegY = min(a.y, b.y)
        val maxSegY = max(a.y, b.y)
        if (maxSegX < rect.x || minSegX > rect.right || maxSegY < rect.y || minSegY > rect.bottom) {
            return false
        }
        // Sample 9 interior points along segment `(a, b)` for fast, exact axis-aligned card hit detection
        for (step in 1..9) {
            val t = step / 10f
            val px = a.x + (b.x - a.x) * t
            val py = a.y + (b.y - a.y) * t
            if (px >= rect.x && px <= rect.right && py >= rect.y && py <= rect.bottom) {
                return true
            }
        }
        return false
    }
}
