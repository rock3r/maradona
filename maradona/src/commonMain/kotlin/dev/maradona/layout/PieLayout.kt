package dev.maradona.layout

import dev.maradona.model.PieDiagram
import dev.maradona.model.PieSlice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class PositionedPieSlice(
    val slice: PieSlice,
    val index: Int,
    val startAngleDeg: Float,
    val sweepAngleDeg: Float,
    val percentage: Double,
    val percentageText: String,
    val isHighlighted: Boolean,
    val explodeOffset: DiagramPoint,
    val pctLabelRect: DiagramRect?,
)

data class PositionedPieLegendItem(
    val slice: PieSlice,
    val index: Int,
    val displayText: String,
    val swatchRect: DiagramRect,
    val labelRect: DiagramRect,
)

data class PieDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val center: DiagramPoint,
    val outerRadius: Float,
    val innerRadius: Float,
    val outerStrokeWidth: Float,
    val slices: List<PositionedPieSlice>,
    val legendItems: List<PositionedPieLegendItem>,
)

/**
 * Pure-Kotlin layout engine for Mermaid `pie` diagrams.
 *
 * Computes slice angles, donut hole geometry, highlighted-slice explosion offsets,
 * deconflicted percentage label positions, and right-hand legend items.
 */
object PieLayout {

    internal fun validSlices(diagram: PieDiagram): List<PieSlice> =
        diagram.slices
            .filter { it.value.isFinite() && it.value >= 0.0 }
            .distinctBy { it.label }

    internal fun sliceFractions(slices: List<PieSlice>): List<Double> {
        val maxVal = slices.maxOfOrNull { it.value } ?: 0.0
        if (maxVal <= 0.0) return List(slices.size) { 0.0 }
        val scaled = DoubleArray(slices.size) { i -> slices[i].value / maxVal }
        val scaledSum = scaled.sum()
        if (scaledSum <= 0.0 || !scaledSum.isFinite()) return List(slices.size) { 0.0 }
        return List(slices.size) { i -> scaled[i] / scaledSum }
    }

    fun layout(
        diagram: PieDiagram,
        pctLabelSize: (Int, String) -> DiagramSize = { _, s -> defaultLabelSize(s, 1f) },
        legendLabelSize: (Int, String) -> DiagramSize = { _, s -> defaultLabelSize(s, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): PieDiagramLayout {
        val d = options.density
        val outerPad = 24f * d
        val outerRadius = 118f * d
        val holeFraction = diagram.donutHole.coerceIn(0f, 0.85f)
        val innerRadius = outerRadius * holeFraction
        val strokeW = diagram.outerStrokeWidth.coerceAtLeast(1f) * d

        val resolvedTitleSize = titleSize ?: diagram.title?.let {
            DiagramSize(max(120f * d, it.length * 9f * d), 24f * d)
        }

        val slices = validSlices(diagram)
        val fractions = sliceFractions(slices)
        val positiveCount = slices.count { it.value > 0.0 }

        var contentTopY = outerPad
        val titleTopY = contentTopY
        if (resolvedTitleSize != null) {
            contentTopY += resolvedTitleSize.height + 16f * d
        }

        val hasHighlight = positiveCount > 1 && diagram.highlightSlice != null &&
            slices.any { it.value > 0.0 && it.label.equals(diagram.highlightSlice, ignoreCase = true) }
        val explodeDist = if (hasHighlight) 10f * d else 0f
        val pieMargin = explodeDist + strokeW / 2f + 4f * d

        val swatchSize = 14f * d
        val swatchGap = 8f * d
        val legendRowGap = 8f * d

        val legendSpecs = slices.mapIndexed { idx, s ->
            val text = legendDisplayText(s, diagram.showData)
            val sz = legendLabelSize(idx, text)
            Triple(s, text, sz)
        }
        val totalLegendHeight = if (legendSpecs.isEmpty()) {
            0f
        } else {
            legendSpecs.sumOf { max(swatchSize, it.third.height).toDouble() }.toFloat() +
                (legendSpecs.size - 1) * legendRowGap
        }

        val pieDiameterWithMargin = (outerRadius + pieMargin) * 2f
        val bodyHeight = max(pieDiameterWithMargin, totalLegendHeight)
        var center = DiagramPoint(
            x = outerPad + pieMargin + outerRadius,
            y = contentTopY + bodyHeight / 2f,
        )

        val rawSlices = ArrayList<PositionedPieSlice>(slices.size)
        val occupiedPctRects = mutableListOf<DiagramRect>()
        val textPos = diagram.textPosition.coerceIn(0.15f, 0.88f)
        val baseLabelRadius = if (positiveCount == 1 && innerRadius == 0f) {
            0f
        } else {
            innerRadius + (outerRadius - innerRadius) * textPos
        }

        var curAngleDeg = -90f
        for ((idx, slice) in slices.withIndex()) {
            val frac = fractions[idx]
            val sweepDeg = (frac * 360.0).toFloat()
            val midDeg = curAngleDeg + sweepDeg / 2f
            val midRad = midDeg.toDouble() * PI / 180.0
            val pct = frac * 100.0
            val pctText = formatPercentage(pct)
            val isHighlighted = hasHighlight && slice.value > 0.0 && slice.label.equals(diagram.highlightSlice, ignoreCase = true)
            val explode = if (isHighlighted) {
                DiagramPoint(
                    x = (cos(midRad) * explodeDist).toFloat(),
                    y = (sin(midRad) * explodeDist).toFloat(),
                )
            } else {
                DiagramPoint(0f, 0f)
            }

            val lblSz = pctLabelSize(idx, pctText)
            val pctRect: DiagramRect? = if (frac <= 0.0 || (pct < 3.0 && positiveCount > 2)) {
                null
            } else {
                val r = if (sweepDeg < 22f && positiveCount > 1) {
                    max(baseLabelRadius, innerRadius + (outerRadius - innerRadius) * 0.80f)
                } else {
                    baseLabelRadius
                }
                val cx = center.x + explode.x + (cos(midRad) * r).toFloat()
                val cy = center.y + explode.y + (sin(midRad) * r).toFloat()
                var candidate = DiagramRect(
                    x = cx - lblSz.width / 2f,
                    y = cy - lblSz.height / 2f,
                    width = lblSz.width,
                    height = lblSz.height,
                )
                val pushDirY = if (sin(midRad) >= 0.0) 1f else -1f
                var guard = 0
                while (guard < 8 && occupiedPctRects.any { overlaps(it, candidate, 2f * d) }) {
                    candidate = candidate.copy(y = candidate.y + pushDirY * (lblSz.height * 0.65f))
                    guard++
                }
                occupiedPctRects += candidate
                candidate
            }

            rawSlices += PositionedPieSlice(
                slice = slice,
                index = idx,
                startAngleDeg = curAngleDeg,
                sweepAngleDeg = sweepDeg,
                percentage = pct,
                percentageText = pctText,
                isHighlighted = isHighlighted,
                explodeOffset = explode,
                pctLabelRect = pctRect,
            )
            curAngleDeg += sweepDeg
        }

        val maxPctRightFromPie = rawSlices.mapNotNull { it.pctLabelRect?.right }.maxOrNull() ?: (center.x + outerRadius + pieMargin)
        val legendLeftX = max(center.x + outerRadius + pieMargin, maxPctRightFromPie) + 32f * d
        var legendCurY = center.y - totalLegendHeight / 2f
        val rawLegendItems = ArrayList<PositionedPieLegendItem>(legendSpecs.size)
        for ((idx, spec) in legendSpecs.withIndex()) {
            val (slice, text, lblSz) = spec
            val rowH = max(swatchSize, lblSz.height)
            val swatchRect = DiagramRect(
                x = legendLeftX,
                y = legendCurY + (rowH - swatchSize) / 2f,
                width = swatchSize,
                height = swatchSize,
            )
            val labelRect = DiagramRect(
                x = swatchRect.right + swatchGap,
                y = legendCurY + (rowH - lblSz.height) / 2f,
                width = lblSz.width,
                height = lblSz.height,
            )
            rawLegendItems += PositionedPieLegendItem(
                slice = slice,
                index = idx,
                displayText = text,
                swatchRect = swatchRect,
                labelRect = labelRect,
            )
            legendCurY += rowH + legendRowGap
        }

        // Ensure no percentage label or pie outline overflows the left (`outerPad`) or top (`contentTopY`) bounds.
        val minContentX = min(
            center.x - outerRadius - pieMargin,
            rawSlices.mapNotNull { it.pctLabelRect?.x }.minOrNull() ?: (center.x - outerRadius - pieMargin),
        )
        val minContentY = min(
            center.y - outerRadius - pieMargin,
            min(
                rawSlices.mapNotNull { it.pctLabelRect?.y }.minOrNull() ?: (center.y - outerRadius - pieMargin),
                rawLegendItems.minOfOrNull { min(it.swatchRect.y, it.labelRect.y) } ?: (center.y - outerRadius - pieMargin),
            ),
        )
        val shiftX = if (minContentX < outerPad) outerPad - minContentX else 0f
        val shiftY = if (minContentY < contentTopY) contentTopY - minContentY else 0f

        center = DiagramPoint(center.x + shiftX, center.y + shiftY)
        val positionedSlices = rawSlices.map { s ->
            s.copy(pctLabelRect = s.pctLabelRect?.let { it.copy(x = it.x + shiftX, y = it.y + shiftY) })
        }
        val legendItems = rawLegendItems.map { item ->
            item.copy(
                swatchRect = item.swatchRect.copy(x = item.swatchRect.x + shiftX, y = item.swatchRect.y + shiftY),
                labelRect = item.labelRect.copy(x = item.labelRect.x + shiftX, y = item.labelRect.y + shiftY),
            )
        }

        val pieRightX = center.x + outerRadius + pieMargin
        val maxLegendRightX = legendItems.maxOfOrNull { it.labelRect.right } ?: pieRightX
        val maxPctRightX = positionedSlices.mapNotNull { it.pctLabelRect?.right }.maxOrNull() ?: pieRightX
        val titleMinWidth = resolvedTitleSize?.let { it.width + outerPad * 2f } ?: 0f

        val finalWidth = max(titleMinWidth, max(pieRightX, max(maxLegendRightX, maxPctRightX)) + outerPad)
        val maxBottomY = max(
            center.y + outerRadius + pieMargin,
            max(
                legendItems.maxOfOrNull { max(it.swatchRect.bottom, it.labelRect.bottom) } ?: 0f,
                positionedSlices.mapNotNull { it.pctLabelRect?.bottom }.maxOrNull() ?: 0f,
            ),
        )
        val finalHeight = maxBottomY + outerPad

        val titleRect = resolvedTitleSize?.let {
            DiagramRect(
                x = max(outerPad, (finalWidth - it.width) / 2f),
                y = titleTopY,
                width = it.width,
                height = it.height,
            )
        }

        return PieDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            titleRect = titleRect,
            center = center,
            outerRadius = outerRadius,
            innerRadius = innerRadius,
            outerStrokeWidth = strokeW,
            slices = positionedSlices,
            legendItems = legendItems,
        )
    }

    internal fun legendDisplayText(slice: PieSlice, showData: Boolean): String {
        if (!showData) return slice.label
        val formattedVal = formatSliceValue(slice.value)
        return "${slice.label} [$formattedVal]"
    }

    internal fun formatSliceValue(value: Double): String {
        if (value == 0.0) return "0"
        val raw = value.toString()
        val sign = if (raw.startsWith("-")) "-" else ""
        val unsigned = raw.removePrefix("-").removePrefix("+")
        val eIdx = unsigned.indexOfFirst { it == 'e' || it == 'E' }
        val mantissa = if (eIdx >= 0) unsigned.substring(0, eIdx) else unsigned
        val exp = if (eIdx >= 0) unsigned.substring(eIdx + 1).toIntOrNull() ?: 0 else 0

        val dotIdx = mantissa.indexOf('.')
        val intDigits = if (dotIdx >= 0) mantissa.substring(0, dotIdx) else mantissa
        val fracDigits = (if (dotIdx >= 0) mantissa.substring(dotIdx + 1) else "").trimEnd('0')

        return when {
            exp == 0 -> if (fracDigits.isEmpty()) "$sign$intDigits" else "$sign$intDigits.$fracDigits"
            exp in 1..15 -> {
                if (exp >= fracDigits.length) {
                    "$sign$intDigits$fracDigits${"0".repeat(exp - fracDigits.length)}"
                } else {
                    val left = intDigits + fracDigits.substring(0, exp)
                    val right = fracDigits.substring(exp).trimEnd('0')
                    if (right.isEmpty()) "$sign$left" else "$sign$left.$right"
                }
            }
            exp in -12..-1 -> {
                val shift = -exp
                val allDigits = (intDigits + fracDigits).trimEnd('0')
                if (shift >= intDigits.length) {
                    val leadingZeros = "0".repeat(shift - intDigits.length)
                    "$sign" + "0.$leadingZeros$allDigits"
                } else {
                    val splitAt = intDigits.length - shift
                    val left = allDigits.substring(0, splitAt)
                    val right = allDigits.substring(splitAt).trimEnd('0')
                    if (right.isEmpty()) "$sign$left" else "$sign$left.$right"
                }
            }
            else -> raw
        }
    }

    internal fun formatPercentage(pct: Double): String {
        if (pct > 0.0 && pct < 0.5) return "<1%"
        return "${pct.roundToInt()}%"
    }

    private fun overlaps(a: DiagramRect, b: DiagramRect, pad: Float): Boolean =
        a.x - pad < b.right && b.x - pad < a.right && a.y - pad < b.bottom && b.y - pad < a.bottom

    internal fun defaultLabelSize(label: String, density: Float): DiagramSize {
        val lines = label.lines().ifEmpty { listOf("") }
        val maxLen = lines.maxOfOrNull { it.length } ?: 3
        return DiagramSize(
            width = max(20f * density, maxLen * 7.4f * density),
            height = lines.size * 16f * density,
        )
    }
}
