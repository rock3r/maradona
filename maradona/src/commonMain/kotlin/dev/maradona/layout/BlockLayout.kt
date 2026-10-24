package dev.maradona.layout

import dev.maradona.model.BlockDiagram
import dev.maradona.model.BlockEdge
import dev.maradona.model.BlockEdgePattern
import dev.maradona.model.BlockItem
import dev.maradona.model.BlockShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Positioned block (leaf or composite container) in a laid-out [BlockDiagram].
 */
data class PositionedBlockItem(
    val item: BlockItem,
    val rect: DiagramRect,
    val labelRect: DiagramRect?,
    val isCompositeContainer: Boolean,
)

/**
 * Positioned edge in a laid-out [BlockDiagram].
 */
data class PositionedBlockEdge(
    val edge: BlockEdge,
    val points: List<DiagramPoint>,
    val labelRect: DiagramRect?,
)

/**
 * Complete layout geometry for a [BlockDiagram].
 */
data class BlockDiagramLayout(
    val width: Float,
    val height: Float,
    val blocks: List<PositionedBlockItem>,
    val edges: List<PositionedBlockEdge>,
    val titleRect: DiagramRect?,
)

/**
 * Computes hierarchical column-grid geometry and edge attachments for Mermaid `block` / `block-beta` diagrams.
 */
object BlockLayout {

    private const val OUTER_MARGIN = 16f
    private const val TITLE_GAP = 14f
    private const val MIN_CELL_WIDTH = 68f
    private const val MIN_CELL_HEIGHT = 38f

    fun defaultLabelSize(text: String, density: Float = 1f): DiagramSize {
        if (text.isEmpty()) return DiagramSize(0f, 0f)
        val lines = text.lines()
        val maxCols = lines.maxOfOrNull { it.length } ?: 0
        return DiagramSize(
            width = (maxCols * 7.4f + 6f) * density,
            height = (lines.size * 16f + 2f) * density,
        )
    }

    fun defaultTitleSize(text: String, density: Float = 1f): DiagramSize =
        DiagramSize((text.length * 9.5f + 8f) * density, 22f * density)

    private class ContainerMetrics(
        val intrinsicSize: DiagramSize,
        val xCols: Int,
        val numRows: Int,
        val baseCellWidth: Float,
        val baseCellHeight: Float,
        val headerHeight: Float,
        val childMetrics: Map<String, ContainerMetrics>,
    )

    fun layout(
        diagram: BlockDiagram,
        nodeLabelSize: (String, String) -> DiagramSize = { _, lbl -> defaultLabelSize(lbl, 1f) },
        edgeLabelSize: (String) -> DiagramSize = { lbl -> defaultLabelSize(lbl, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): BlockDiagramLayout {
        val d = options.density.coerceAtLeast(0.01f)
        val pad = diagram.config.padding.coerceAtLeast(2f) * d
        val margin = OUTER_MARGIN * d

        val titleText = diagram.title?.takeIf { it.isNotBlank() }
        val resolvedTitleSize = if (titleText != null) {
            titleSize ?: defaultTitleSize(titleText, d)
        } else {
            null
        }
        val titleTopSpace = if (resolvedTitleSize != null) resolvedTitleSize.height + TITLE_GAP * d else 0f

        val rootContainer = BlockItem(
            id = "root",
            label = "",
            shape = BlockShape.Composite,
            columns = diagram.rootColumns,
            children = diagram.children,
        )

        val rootMetrics = measureBlock(rootContainer, isRoot = true, pad = pad, d = d, nodeLabelSize = nodeLabelSize)

        val gridWidth = rootMetrics.intrinsicSize.width
        val gridHeight = rootMetrics.intrinsicSize.height
        val minTitleW = if (resolvedTitleSize != null) resolvedTitleSize.width + margin * 2f else 0f
        val totalWidth = max(gridWidth + margin * 2f, minTitleW)
        val totalHeight = margin + titleTopSpace + gridHeight + margin

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

        val rootRect = DiagramRect(
            x = (totalWidth - gridWidth) / 2f,
            y = margin + titleTopSpace,
            width = gridWidth,
            height = gridHeight,
        )

        val positionedBlocks = mutableListOf<PositionedBlockItem>()
        val byId = LinkedHashMap<String, PositionedBlockItem>()

        fun placeChildren(container: BlockItem, containerRect: DiagramRect, metrics: ContainerMetrics, isRoot: Boolean) {
            if (container.children.isEmpty()) return
            val innerPad = if (isRoot) 0f else pad
            val availW = (containerRect.width - innerPad * 2f).coerceAtLeast(1f)
            val availH = (containerRect.height - metrics.headerHeight - innerPad * 2f).coerceAtLeast(1f)

            val actualCellW = max(
                metrics.baseCellWidth,
                (availW - (metrics.xCols - 1) * pad) / metrics.xCols.coerceAtLeast(1).toFloat(),
            )
            val actualCellH = max(
                metrics.baseCellHeight,
                (availH - (metrics.numRows - 1) * pad) / metrics.numRows.coerceAtLeast(1).toFloat(),
            )

            var colCursor = 0
            for (child in container.children) {
                val colIdx = colCursor % metrics.xCols
                val rowIdx = colCursor / metrics.xCols
                val span = if (container.columns > 0) {
                    min(child.widthInColumns.coerceAtLeast(1), metrics.xCols - colIdx)
                } else {
                    child.widthInColumns.coerceAtLeast(1)
                }
                val cx = containerRect.x + innerPad + colIdx * (actualCellW + pad)
                val cy = containerRect.y + metrics.headerHeight + innerPad + rowIdx * (actualCellH + pad)
                val cw = span * actualCellW + (span - 1) * pad
                val ch = actualCellH
                colCursor += span

                if (child.shape == BlockShape.Space) {
                    continue
                }

                val childRect = DiagramRect(cx, cy, cw, ch)
                val isCompWithChildren = child.shape == BlockShape.Composite && child.children.isNotEmpty()
                val lblSize = if (child.label.isNotEmpty()) nodeLabelSize(child.id, child.label) else null
                val lblRect = if (lblSize != null) {
                    if (isCompWithChildren) {
                        DiagramRect(
                            x = childRect.centerX - lblSize.width / 2f,
                            y = childRect.y + 4f * d,
                            width = lblSize.width,
                            height = lblSize.height,
                        )
                    } else {
                        DiagramRect(
                            x = childRect.centerX - lblSize.width / 2f,
                            y = childRect.centerY - lblSize.height / 2f,
                            width = lblSize.width,
                            height = lblSize.height,
                        )
                    }
                } else {
                    null
                }

                val positioned = PositionedBlockItem(
                    item = child,
                    rect = childRect,
                    labelRect = lblRect,
                    isCompositeContainer = child.shape == BlockShape.Composite,
                )
                positionedBlocks += positioned
                byId[child.id] = positioned

                if (isCompWithChildren) {
                    val childM = metrics.childMetrics[child.id] ?: continue
                    placeChildren(child, childRect, childM, isRoot = false)
                }
            }
        }

        placeChildren(rootContainer, rootRect, rootMetrics, isRoot = true)

        val positionedEdges = mutableListOf<PositionedBlockEdge>()
        for (edge in diagram.edges) {
            if (edge.pattern == BlockEdgePattern.Invisible) continue
            val src = byId[edge.from] ?: continue
            val dst = byId[edge.to] ?: continue
            val srcCenter = DiagramPoint(src.rect.centerX, src.rect.centerY)
            val dstCenter = DiagramPoint(dst.rect.centerX, dst.rect.centerY)
            val startPt = clipToBlockOutline(src.item.shape, src.rect, dstCenter, d)
            val endPt = clipToBlockOutline(dst.item.shape, dst.rect, srcCenter, d)
            val midPt = DiagramPoint((startPt.x + endPt.x) / 2f, (startPt.y + endPt.y) / 2f)
            val lblRect = edge.label?.takeIf { it.isNotEmpty() }?.let { lbl ->
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
            positionedEdges += PositionedBlockEdge(
                edge = edge,
                points = listOf(startPt, midPt, endPt),
                labelRect = lblRect,
            )
        }

        return BlockDiagramLayout(
            width = totalWidth,
            height = totalHeight,
            blocks = positionedBlocks,
            edges = positionedEdges,
            titleRect = titleRect,
        )
    }

    private fun measureBlock(
        block: BlockItem,
        isRoot: Boolean,
        pad: Float,
        d: Float,
        nodeLabelSize: (String, String) -> DiagramSize,
    ): ContainerMetrics {
        if (block.shape == BlockShape.Space) {
            return ContainerMetrics(
                intrinsicSize = DiagramSize(0f, 0f),
                xCols = 1,
                numRows = 1,
                baseCellWidth = 0f,
                baseCellHeight = 0f,
                headerHeight = 0f,
                childMetrics = emptyMap(),
            )
        }

        if (block.shape != BlockShape.Composite || block.children.isEmpty()) {
            val sz = computeLeafSize(block, d, nodeLabelSize)
            return ContainerMetrics(
                intrinsicSize = sz,
                xCols = 1,
                numRows = 1,
                baseCellWidth = sz.width,
                baseCellHeight = sz.height,
                headerHeight = 0f,
                childMetrics = emptyMap(),
            )
        }

        val childMap = LinkedHashMap<String, ContainerMetrics>()
        for (child in block.children) {
            childMap[child.id] = measureBlock(child, isRoot = false, pad = pad, d = d, nodeLabelSize = nodeLabelSize)
        }

        val totalRequestedSlots = block.children.sumOf { it.widthInColumns.coerceAtLeast(1) }.coerceAtLeast(1)
        val xCols = if (block.columns > 0) {
            min(block.columns, totalRequestedSlots).coerceAtLeast(1)
        } else {
            totalRequestedSlots
        }

        var maxNormCellW = MIN_CELL_WIDTH * d
        var maxCellH = MIN_CELL_HEIGHT * d
        var colCursor = 0
        for (child in block.children) {
            val colIdx = colCursor % xCols
            val effectiveSpan = if (block.columns > 0) {
                min(child.widthInColumns.coerceAtLeast(1), xCols - colIdx)
            } else {
                child.widthInColumns.coerceAtLeast(1)
            }
            colCursor += effectiveSpan
            if (child.shape == BlockShape.Space) continue
            val cm = childMap[child.id] ?: continue
            val normW = (cm.intrinsicSize.width - (effectiveSpan - 1) * pad) / effectiveSpan.toFloat()
            if (normW > maxNormCellW) maxNormCellW = normW
            if (cm.intrinsicSize.height > maxCellH) maxCellH = cm.intrinsicSize.height
        }
        val numRows = ceil(colCursor.toDouble() / xCols.toDouble()).toInt().coerceAtLeast(1)

        val lblSz = if (!isRoot && block.label.isNotEmpty()) nodeLabelSize(block.id, block.label) else null
        val headerH = if (lblSz != null) lblSz.height + 8f * d else 0f

        val innerPad = if (isRoot) 0f else pad
        val gridW = xCols * maxNormCellW + (xCols - 1) * pad + innerPad * 2f
        val minLblW = if (lblSz != null) lblSz.width + 24f * d else 0f
        val totalW = max(gridW, minLblW)
        val totalH = headerH + numRows * maxCellH + (numRows - 1) * pad + innerPad * 2f

        return ContainerMetrics(
            intrinsicSize = DiagramSize(totalW, totalH),
            xCols = xCols,
            numRows = numRows,
            baseCellWidth = maxNormCellW,
            baseCellHeight = maxCellH,
            headerHeight = headerH,
            childMetrics = childMap,
        )
    }

    private fun computeLeafSize(
        block: BlockItem,
        d: Float,
        nodeLabelSize: (String, String) -> DiagramSize,
    ): DiagramSize {
        val lbl = if (block.label.isNotEmpty()) nodeLabelSize(block.id, block.label) else DiagramSize(0f, 0f)
        val minW = MIN_CELL_WIDTH * d
        val minH = MIN_CELL_HEIGHT * d
        return when (block.shape) {
            BlockShape.Circle -> {
                val side = maxOf(lbl.width + 22f * d, lbl.height + 22f * d, 48f * d)
                DiagramSize(side, side)
            }
            BlockShape.DoubleCircle -> {
                val side = maxOf(lbl.width + 30f * d, lbl.height + 30f * d, 54f * d)
                DiagramSize(side, side)
            }
            BlockShape.Diamond -> {
                val w = max(lbl.width * 1.45f + 28f * d, minW)
                val h = max(lbl.height * 1.45f + 22f * d, minH + 8f * d)
                DiagramSize(w, h)
            }
            BlockShape.Hexagon,
            BlockShape.LeanRight,
            BlockShape.LeanLeft,
            BlockShape.Trapezoid,
            BlockShape.InvTrapezoid,
            BlockShape.Asymmetric,
            -> {
                DiagramSize(
                    width = max(lbl.width + 36f * d, minW),
                    height = max(lbl.height + 18f * d, minH),
                )
            }
            BlockShape.Subroutine -> {
                DiagramSize(
                    width = max(lbl.width + 34f * d, minW),
                    height = max(lbl.height + 18f * d, minH),
                )
            }
            BlockShape.Cylinder -> {
                DiagramSize(
                    width = max(lbl.width + 26f * d, minW),
                    height = max(lbl.height + 26f * d, minH + 6f * d),
                )
            }
            BlockShape.BlockArrow -> {
                DiagramSize(
                    width = max(lbl.width + 32f * d, 56f * d),
                    height = max(lbl.height + 20f * d, minH),
                )
            }
            else -> {
                DiagramSize(
                    width = max(lbl.width + 24f * d, minW),
                    height = max(lbl.height + 18f * d, minH),
                )
            }
        }
    }

    private fun clipToBlockOutline(
        shape: BlockShape,
        rect: DiagramRect,
        towards: DiagramPoint,
        d: Float = 1f,
    ): DiagramPoint {
        val cx = rect.centerX
        val cy = rect.centerY
        val dx = towards.x - cx
        val dy = towards.y - cy
        if (abs(dx) < 0.001f && abs(dy) < 0.001f) {
            return DiagramPoint(cx, cy)
        }
        val hw = (rect.width / 2f).coerceAtLeast(1f)
        val hh = (rect.height / 2f).coerceAtLeast(1f)

        val vertices: List<DiagramPoint>? = when (shape) {
            BlockShape.Circle, BlockShape.DoubleCircle -> {
                val r = min(hw, hh)
                val dist = hypot(dx, dy).coerceAtLeast(0.001f)
                return DiagramPoint(cx + dx * (r / dist), cy + dy * (r / dist))
            }
            BlockShape.Diamond -> listOf(
                DiagramPoint(cx, rect.y),
                DiagramPoint(rect.right, cy),
                DiagramPoint(cx, rect.bottom),
                DiagramPoint(rect.x, cy),
            )
            BlockShape.Hexagon -> {
                val inset = min(14f * d, rect.width * 0.22f)
                listOf(
                    DiagramPoint(rect.x + inset, rect.y),
                    DiagramPoint(rect.right - inset, rect.y),
                    DiagramPoint(rect.right, cy),
                    DiagramPoint(rect.right - inset, rect.bottom),
                    DiagramPoint(rect.x + inset, rect.bottom),
                    DiagramPoint(rect.x, cy),
                )
            }
            BlockShape.LeanRight -> {
                val slant = min(12f * d, rect.width * 0.2f)
                listOf(
                    DiagramPoint(rect.x + slant, rect.y),
                    DiagramPoint(rect.right, rect.y),
                    DiagramPoint(rect.right - slant, rect.bottom),
                    DiagramPoint(rect.x, rect.bottom),
                )
            }
            BlockShape.LeanLeft -> {
                val slant = min(12f * d, rect.width * 0.2f)
                listOf(
                    DiagramPoint(rect.x, rect.y),
                    DiagramPoint(rect.right - slant, rect.y),
                    DiagramPoint(rect.right, rect.bottom),
                    DiagramPoint(rect.x + slant, rect.bottom),
                )
            }
            BlockShape.Trapezoid -> {
                val slant = min(12f * d, rect.width * 0.2f)
                listOf(
                    DiagramPoint(rect.x + slant, rect.y),
                    DiagramPoint(rect.right - slant, rect.y),
                    DiagramPoint(rect.right, rect.bottom),
                    DiagramPoint(rect.x, rect.bottom),
                )
            }
            BlockShape.InvTrapezoid -> {
                val slant = min(12f * d, rect.width * 0.2f)
                listOf(
                    DiagramPoint(rect.x, rect.y),
                    DiagramPoint(rect.right, rect.y),
                    DiagramPoint(rect.right - slant, rect.bottom),
                    DiagramPoint(rect.x + slant, rect.bottom),
                )
            }
            BlockShape.Asymmetric -> {
                val notch = min(12f * d, rect.width * 0.2f)
                listOf(
                    DiagramPoint(rect.x, rect.y),
                    DiagramPoint(rect.right, rect.y),
                    DiagramPoint(rect.right, rect.bottom),
                    DiagramPoint(rect.x, rect.bottom),
                    DiagramPoint(rect.x + notch, cy),
                )
            }
            else -> null
        }

        if (vertices != null) {
            intersectRayWithPolygon(cx, cy, dx, dy, vertices)?.let { return it }
        }

        val tx = if (abs(dx) > 0.001f) hw / abs(dx) else Float.MAX_VALUE
        val ty = if (abs(dy) > 0.001f) hh / abs(dy) else Float.MAX_VALUE
        val t = min(tx, ty)
        return DiagramPoint(cx + dx * t, cy + dy * t)
    }

    private fun intersectRayWithPolygon(
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
        poly: List<DiagramPoint>,
    ): DiagramPoint? {
        var bestT = Float.MAX_VALUE
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[i]
            val b = poly[(i + 1) % n]
            val sx = b.x - a.x
            val sy = b.y - a.y
            val denom = dx * sy - dy * sx
            if (abs(denom) < 1e-6f) continue
            val ax = a.x - cx
            val ay = a.y - cy
            val t = (ax * sy - ay * sx) / denom
            val u = (ax * dy - ay * dx) / denom
            if (t > 0f && u in -1e-4f..1.0001f && t < bestT) {
                bestT = t
            }
        }
        return if (bestT < Float.MAX_VALUE) DiagramPoint(cx + dx * bestT, cy + dy * bestT) else null
    }
}
