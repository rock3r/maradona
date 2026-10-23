package dev.maradona.layout

import dev.maradona.model.QuadrantDiagram
import dev.maradona.model.QuadrantPoint
import kotlin.math.max
import kotlin.math.min

data class PositionedQuadrantBox(
    val quadrantNumber: Int,
    val rect: DiagramRect,
    val text: String?,
    val labelRect: DiagramRect?,
)

data class PositionedQuadrantAxisLabel(
    val axisSlot: Int,
    val text: String,
    val rect: DiagramRect,
    val unrotatedSize: DiagramSize,
    val rotationDeg: Float,
)

data class PositionedQuadrantPoint(
    val point: QuadrantPoint,
    val center: DiagramPoint,
    val radius: Float,
    val strokeWidth: Float,
    val labelRect: DiagramRect,
)

data class QuadrantDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val gridRect: DiagramRect,
    val quadrants: List<PositionedQuadrantBox>,
    val axisLabels: List<PositionedQuadrantAxisLabel>,
    val points: List<PositionedQuadrantPoint>,
) {
    fun point(label: String): PositionedQuadrantPoint? = points.firstOrNull { it.point.label == label }
}

/**
 * Pure-Kotlin layout engine for Mermaid `quadrantChart` diagrams.
 *
 * Computes the four quadrant boxes (Q1 top-right, Q2 top-left, Q3 bottom-left, Q4 bottom-right),
 * X and Y axis labels (including `-90°` rotated Y-axis labels), point coordinates in `[0, 1] × [0, 1]`,
 * and deconflicted point labels with 4-direction bounds translation.
 */
object QuadrantLayout {

    fun layout(
        diagram: QuadrantDiagram,
        pointLabelSize: (QuadrantPoint) -> DiagramSize = { p -> defaultTextSize(p.label, 1f) },
        quadrantLabelSize: (Int, String) -> DiagramSize = { _, s -> defaultHeaderSize(s, 1f) },
        axisLabelSize: (String) -> DiagramSize = { s -> defaultHeaderSize(s, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): QuadrantDiagramLayout {
        val d = options.density
        val outerPad = 24f * d

        val resolvedTitleSize = titleSize ?: diagram.title?.let {
            DiagramSize(max(120f * d, it.length * 9f * d), 24f * d)
        }

        var contentTopY = outerPad
        val titleTopY = contentTopY
        if (resolvedTitleSize != null) {
            contentTopY += resolvedTitleSize.height + 16f * d
        }

        val hasXAxis = !diagram.xAxisLeftText.isNullOrBlank() || !diagram.xAxisRightText.isNullOrBlank()
        val hasYAxis = !diagram.yAxisBottomText.isNullOrBlank() || !diagram.yAxisTopText.isNullOrBlank()
        val xAxisAtBottom = diagram.points.isNotEmpty()

        val xLeftSz = diagram.xAxisLeftText?.takeIf { it.isNotBlank() }?.let(axisLabelSize)
        val xRightSz = diagram.xAxisRightText?.takeIf { it.isNotBlank() }?.let(axisLabelSize)
        val yBottomSz = diagram.yAxisBottomText?.takeIf { it.isNotBlank() }?.let(axisLabelSize)
        val yTopSz = diagram.yAxisTopText?.takeIf { it.isNotBlank() }?.let(axisLabelSize)

        val xAxisBandH = if (hasXAxis) {
            max(xLeftSz?.height ?: 0f, xRightSz?.height ?: 0f) + 14f * d
        } else {
            0f
        }
        // Y-axis labels are rotated -90 deg, so their horizontal thickness is `height` and vertical span is `width`.
        val yAxisBandW = if (hasYAxis) {
            max(yBottomSz?.height ?: 0f, yTopSz?.height ?: 0f) + 14f * d
        } else {
            0f
        }

        val qTexts = listOf(
            1 to diagram.quadrant1Text,
            2 to diagram.quadrant2Text,
            3 to diagram.quadrant3Text,
            4 to diagram.quadrant4Text,
        )
        val qSizes = qTexts.associate { (qNum, txt) ->
            qNum to txt?.takeIf { it.isNotBlank() }?.let { quadrantLabelSize(qNum, it) }
        }

        val minHalfWFromQuadrants = (qSizes.values.filterNotNull().maxOfOrNull { it.width } ?: 0f) + 28f * d
        val minHalfWFromXAxis = max(xLeftSz?.width ?: 0f, xRightSz?.width ?: 0f) + 24f * d
        val minHalfHFromQuadrants = (qSizes.values.filterNotNull().maxOfOrNull { it.height } ?: 0f) + 40f * d
        val minHalfHFromYAxis = max(yBottomSz?.width ?: 0f, yTopSz?.width ?: 0f) + 24f * d

        val baseGridW = max(320f * d, (diagram.chartWidth - 80f) * d)
        val baseGridH = max(320f * d, (diagram.chartHeight - 80f) * d)
        val halfW = max(baseGridW / 2f, max(minHalfWFromQuadrants, minHalfWFromXAxis))
        val halfH = max(baseGridH / 2f, max(minHalfHFromQuadrants, minHalfHFromYAxis))
        val gridW = halfW * 2f
        val gridH = halfH * 2f

        val gridLeft = outerPad + yAxisBandW
        val gridTop = contentTopY + (if (hasXAxis && !xAxisAtBottom) xAxisBandH else 0f)
        val rawGridRect = DiagramRect(gridLeft, gridTop, gridW, gridH)

        // Quadrant boxes: 1=top-right, 2=top-left, 3=bottom-left, 4=bottom-right.
        val rawQuadrants = listOf(
             Triple(1, DiagramRect(gridLeft + halfW, gridTop, halfW, halfH), diagram.quadrant1Text),
            Triple(2, DiagramRect(gridLeft, gridTop, halfW, halfH), diagram.quadrant2Text),
            Triple(3, DiagramRect(gridLeft, gridTop + halfH, halfW, halfH), diagram.quadrant3Text),
            Triple(4, DiagramRect(gridLeft + halfW, gridTop + halfH, halfW, halfH), diagram.quadrant4Text),
        ).map { (qNum, qRect, txt) ->
            val cleanTxt = txt?.takeIf { it.isNotBlank() }
            val sz = qSizes[qNum]
            val lblRect = if (cleanTxt != null && sz != null) {
                val ly = if (diagram.points.isEmpty()) {
                    qRect.centerY - sz.height / 2f
                } else {
                    qRect.y + 8f * d
                }
                DiagramRect(
                    x = qRect.centerX - sz.width / 2f,
                    y = ly,
                    width = sz.width,
                    height = sz.height,
                )
            } else {
                null
            }
            PositionedQuadrantBox(
                quadrantNumber = qNum,
                rect = qRect,
                text = cleanTxt,
                labelRect = lblRect,
            )
        }

        val rawAxisLabels = mutableListOf<PositionedQuadrantAxisLabel>()
        val xLabelY = if (xAxisAtBottom) {
            rawGridRect.bottom + 8f * d
        } else {
            contentTopY
        }
        val hasRightX = xRightSz != null && !diagram.xAxisRightText.isNullOrBlank()
        if (xLeftSz != null && !diagram.xAxisLeftText.isNullOrBlank()) {
            val lx = if (hasRightX) {
                gridLeft + halfW / 2f - xLeftSz.width / 2f
            } else {
                gridLeft
            }
            rawAxisLabels += PositionedQuadrantAxisLabel(
                axisSlot = 0,
                text = diagram.xAxisLeftText,
                rect = DiagramRect(lx, xLabelY, xLeftSz.width, xLeftSz.height),
                unrotatedSize = xLeftSz,
                rotationDeg = 0f,
            )
        }
        if (xRightSz != null && !diagram.xAxisRightText.isNullOrBlank()) {
            val rx = gridLeft + halfW + halfW / 2f - xRightSz.width / 2f
            rawAxisLabels += PositionedQuadrantAxisLabel(
                axisSlot = 1,
                text = diagram.xAxisRightText,
                rect = DiagramRect(rx, xLabelY, xRightSz.width, xRightSz.height),
                unrotatedSize = xRightSz,
                rotationDeg = 0f,
            )
        }

        val hasTopY = yTopSz != null && !diagram.yAxisTopText.isNullOrBlank()
        if (yBottomSz != null && !diagram.yAxisBottomText.isNullOrBlank()) {
            val rotW = yBottomSz.height
            val rotH = yBottomSz.width
            val cy = if (hasTopY) {
                gridTop + halfH + halfH / 2f
            } else {
                gridTop + gridH - rotH / 2f
            }
            val cx = outerPad + rotW / 2f
            rawAxisLabels += PositionedQuadrantAxisLabel(
                axisSlot = 2,
                text = diagram.yAxisBottomText,
                rect = DiagramRect(cx - rotW / 2f, cy - rotH / 2f, rotW, rotH),
                unrotatedSize = yBottomSz,
                rotationDeg = -90f,
            )
        }
        if (yTopSz != null && !diagram.yAxisTopText.isNullOrBlank()) {
            val rotW = yTopSz.height
            val rotH = yTopSz.width
            val cy = gridTop + halfH / 2f
            val cx = outerPad + rotW / 2f
            rawAxisLabels += PositionedQuadrantAxisLabel(
                axisSlot = 3,
                text = diagram.yAxisTopText,
                rect = DiagramRect(cx - rotW / 2f, cy - rotH / 2f, rotW, rotH),
                unrotatedSize = yTopSz,
                rotationDeg = -90f,
            )
        }

        val pointCenters = diagram.points.map { p ->
            val px = gridLeft + p.x.coerceIn(0.0, 1.0).toFloat() * gridW
            val py = gridTop + (1f - p.y.coerceIn(0.0, 1.0).toFloat()) * gridH
            DiagramPoint(px, py)
        }
        val pointRadii = diagram.points.map { p -> (p.style?.radius ?: 5f) * d }
        val pointStrokes = diagram.points.map { p -> (p.style?.strokeWidth ?: 0f) * d }

        val occupiedRects = mutableListOf<DiagramRect>()
        for (q in rawQuadrants) {
            q.labelRect?.let { occupiedRects += it }
        }
        for (al in rawAxisLabels) {
            occupiedRects += al.rect
        }
        for (i in diagram.points.indices) {
            val c = pointCenters[i]
            val r = pointRadii[i] + pointStrokes[i] / 2f
            occupiedRects += DiagramRect(c.x - r, c.y - r, r * 2f, r * 2f)
        }

        val rawPoints = ArrayList<PositionedQuadrantPoint>(diagram.points.size)
        for ((idx, p) in diagram.points.withIndex()) {
            val c = pointCenters[idx]
            val r = pointRadii[idx]
            val sw = pointStrokes[idx]
            val outerR = r + sw / 2f
            val lblSz = pointLabelSize(p)
            val gap = 5f * d
            val candidates = listOf(
                DiagramRect(c.x - lblSz.width / 2f, c.y + outerR + gap, lblSz.width, lblSz.height),
                DiagramRect(c.x - lblSz.width / 2f, c.y - outerR - gap - lblSz.height, lblSz.width, lblSz.height),
                DiagramRect(c.x + outerR + gap, c.y - lblSz.height / 2f, lblSz.width, lblSz.height),
                DiagramRect(c.x - outerR - gap - lblSz.width, c.y - lblSz.height / 2f, lblSz.width, lblSz.height),
            )
            var chosen = candidates.firstOrNull { cand ->
                occupiedRects.none { overlaps(it, cand, 2f * d) }
            }
            if (chosen == null) {
                var fallback = candidates.first()
                var guard = 0
                val maxSteps = max(32, occupiedRects.size + 4)
                while (guard < maxSteps) {
                    val blocker = occupiedRects.firstOrNull { overlaps(it, fallback, 2f * d) } ?: break
                    fallback = fallback.copy(y = blocker.bottom + 3f * d)
                    guard++
                }
                chosen = fallback
            }
            occupiedRects += chosen
            rawPoints += PositionedQuadrantPoint(
                point = p,
                center = c,
                radius = r,
                strokeWidth = sw,
                labelRect = chosen,
            )
        }

        val allBounds = buildList {
            add(rawGridRect)
            for (q in rawQuadrants) q.labelRect?.let { add(it) }
            for (al in rawAxisLabels) add(al.rect)
            for (pt in rawPoints) {
                val r = pt.radius + pt.strokeWidth / 2f
                add(DiagramRect(pt.center.x - r, pt.center.y - r, r * 2f, r * 2f))
                add(pt.labelRect)
            }
        }

        val minX = allBounds.minOfOrNull { it.x } ?: outerPad
        val minY = allBounds.minOfOrNull { it.y } ?: contentTopY
        val shiftX = if (minX < outerPad) outerPad - minX else 0f
        val shiftY = if (minY < contentTopY) contentTopY - minY else 0f

        fun DiagramPoint.shifted() = DiagramPoint(x + shiftX, y + shiftY)
        fun DiagramRect.shifted() = copy(x = x + shiftX, y = y + shiftY)

        val gridRect = rawGridRect.shifted()
        val quadrants = rawQuadrants.map { q ->
            q.copy(rect = q.rect.shifted(), labelRect = q.labelRect?.shifted())
        }
        val axisLabels = rawAxisLabels.map { al ->
            al.copy(rect = al.rect.shifted())
        }
        val points = rawPoints.map { pt ->
            pt.copy(center = pt.center.shifted(), labelRect = pt.labelRect.shifted())
        }

        val maxRight = allBounds.maxOfOrNull { it.right + shiftX } ?: (gridRect.right)
        val maxBottom = allBounds.maxOfOrNull { it.bottom + shiftY } ?: (gridRect.bottom)
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

        return QuadrantDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            titleRect = titleRect,
            gridRect = gridRect,
            quadrants = quadrants,
            axisLabels = axisLabels,
            points = points,
        )
    }

    private fun overlaps(a: DiagramRect, b: DiagramRect, pad: Float): Boolean =
        a.x - pad < b.right && b.x - pad < a.right && a.y - pad < b.bottom && b.y - pad < a.bottom

    internal fun defaultTextSize(text: String, density: Float): DiagramSize {
        val plain = text.replace("**", "").replace("*", "")
        val lines = plain.lines().ifEmpty { listOf("") }
        val maxLen = lines.maxOfOrNull { it.length } ?: 3
        return DiagramSize(
            width = max(28f * density, maxLen * 7.0f * density),
            height = lines.size * 15f * density,
        )
    }

    internal fun defaultHeaderSize(text: String, density: Float): DiagramSize {
        val plain = text.replace("**", "").replace("*", "")
        val lines = plain.lines().ifEmpty { listOf("") }
        val maxLen = lines.maxOfOrNull { it.length } ?: 3
        return DiagramSize(
            width = max(36f * density, maxLen * 7.8f * density),
            height = lines.size * 17f * density,
        )
    }
}
