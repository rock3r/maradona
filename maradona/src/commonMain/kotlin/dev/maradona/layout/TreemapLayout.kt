package dev.maradona.layout

import dev.maradona.model.TreemapDiagram
import dev.maradona.model.TreemapNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Positioned branch/section container in a laid-out [TreemapDiagram].
 */
data class PositionedTreemapSection(
    val sectionIndex: Int,
    val node: TreemapNode,
    val depth: Int,
    val colorIndex: Int,
    val totalValue: Double,
    val formattedValue: String,
    val rect: DiagramRect,
    val headerRect: DiagramRect,
)

/**
 * Positioned leaf tile in a laid-out [TreemapDiagram].
 */
data class PositionedTreemapLeaf(
    val leafIndex: Int,
    val node: TreemapNode,
    val colorIndex: Int,
    val value: Double,
    val formattedValue: String,
    val rect: DiagramRect,
)

/**
 * Complete layout geometry for a [TreemapDiagram].
 */
data class TreemapDiagramLayout(
    val width: Float,
    val height: Float,
    val sections: List<PositionedTreemapSection>,
    val leaves: List<PositionedTreemapLeaf>,
    val showValues: Boolean,
    val titleRect: DiagramRect?,
)

/**
 * Computes squarified treemap section and leaf rectangles for Mermaid `treemap` / `treemap-beta` diagrams.
 */
object TreemapLayout {

    private const val SECTION_HEADER_HEIGHT = 26f
    private const val SECTION_INNER_PAD = 8f
    private const val TITLE_GAP = 14f
    private val GOLDEN_RATIO = ((1.0 + sqrt(5.0)) / 2.0).toFloat()

    fun defaultTitleSize(text: String, density: Float = 1f): DiagramSize =
        DiagramSize((text.length * 9.5f + 8f) * density, 22f * density)

    private class WeightedNode(
        val node: TreemapNode,
        val totalValue: Double,
        val weight: Double,
        val children: List<WeightedNode>,
    )

    fun layout(
        diagram: TreemapDiagram,
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): TreemapDiagramLayout {
        val d = options.density.coerceAtLeast(0.01f)
        val cfg = diagram.config
        val outerPad = cfg.diagramPadding.coerceAtLeast(4f) * d
        val innerGap = (cfg.padding.coerceAtLeast(0f) * 0.6f) * d
        val headerH = SECTION_HEADER_HEIGHT * d
        val sectionPad = SECTION_INNER_PAD * d

        val titleText = diagram.title?.takeIf { it.isNotBlank() }
        val resolvedTitleSize = if (titleText != null) {
            titleSize ?: defaultTitleSize(titleText, d)
        } else {
            null
        }
        val titleTopSpace = if (resolvedTitleSize != null) resolvedTitleSize.height + TITLE_GAP * d else 0f

        val plotW = cfg.width.coerceAtLeast(120f) * d
        val plotH = cfg.height.coerceAtLeast(80f) * d
        val minTitleW = if (resolvedTitleSize != null) resolvedTitleSize.width + outerPad * 2f else 0f
        val totalWidth = max(plotW + outerPad * 2f, minTitleW)
        val totalHeight = outerPad + titleTopSpace + plotH + outerPad

        val titleRect = if (resolvedTitleSize != null) {
            DiagramRect(
                x = (totalWidth - resolvedTitleSize.width) / 2f,
                y = outerPad,
                width = resolvedTitleSize.width,
                height = resolvedTitleSize.height,
            )
        } else {
            null
        }

        val plotRect = DiagramRect(
            x = (totalWidth - plotW) / 2f,
            y = outerPad + titleTopSpace,
            width = plotW,
            height = plotH,
        )

        val weightedRoots = diagram.roots
            .map { buildWeightedTree(it) }
            .sortedByDescending { it.weight }

        val sections = mutableListOf<PositionedTreemapSection>()
        val leaves = mutableListOf<PositionedTreemapLeaf>()
        var nextSectionColor = 0

        fun subdivideChildren(
            items: List<WeightedNode>,
            bounds: DiagramRect,
            depth: Int,
            inheritedColorIdx: Int?,
        ) {
            if (items.isEmpty() || bounds.width <= 0f || bounds.height <= 0f) return
            val rects = squarify(items.map { it.weight }, bounds, innerGap)
            for (i in items.indices) {
                val item = items[i]
                val r = rects[i]
                if (r.width <= 0f || r.height <= 0f) continue
                if (item.children.isEmpty()) {
                    val colorIdx = inheritedColorIdx ?: nextSectionColor++
                    leaves += PositionedTreemapLeaf(
                        leafIndex = leaves.size,
                        node = item.node,
                        colorIndex = colorIdx,
                        value = item.totalValue,
                        formattedValue = formatValue(item.totalValue, cfg.valueFormat),
                        rect = r,
                    )
                } else {
                    val colorIdx = if (depth <= 1 || inheritedColorIdx == null) nextSectionColor++ else inheritedColorIdx
                    val hHeight = min(headerH, r.height * 0.38f)
                    val hRect = DiagramRect(
                        x = r.x,
                        y = r.y,
                        width = r.width,
                        height = hHeight,
                    )
                    sections += PositionedTreemapSection(
                        sectionIndex = sections.size,
                        node = item.node,
                        depth = depth,
                        colorIndex = colorIdx,
                        totalValue = item.totalValue,
                        formattedValue = formatValue(item.totalValue, cfg.valueFormat),
                        rect = r,
                        headerRect = hRect,
                    )
                    val remH = (r.height - hHeight).coerceAtLeast(0f)
                    val padX = min(sectionPad, r.width * 0.12f)
                    val padTop = min(sectionPad * 0.5f, remH * 0.12f)
                    val padBottom = min(sectionPad, remH * 0.12f)
                    val innerX = r.x + padX
                    val innerY = hRect.bottom + padTop
                    val innerW = max(0f, r.right - padX - innerX)
                    val innerH = max(0f, r.bottom - padBottom - innerY)
                    if (innerW > 0f && innerH > 0f) {
                        subdivideChildren(
                            items = item.children,
                            bounds = DiagramRect(innerX, innerY, innerW, innerH),
                            depth = depth + 1,
                            inheritedColorIdx = colorIdx,
                        )
                    }
                }
            }
        }

        subdivideChildren(weightedRoots, plotRect, depth = 1, inheritedColorIdx = null)

        return TreemapDiagramLayout(
            width = totalWidth,
            height = totalHeight,
            sections = sections,
            leaves = leaves,
            showValues = cfg.showValues,
            titleRect = titleRect,
        )
    }

    private fun buildWeightedTree(node: TreemapNode): WeightedNode {
        if (node.children.isEmpty()) {
            val v = (node.value ?: 0.0).coerceAtLeast(0.0)
            return WeightedNode(node = node, totalValue = v, weight = v, children = emptyList())
        }
        val childWeighted = node.children
            .map { buildWeightedTree(it) }
            .sortedByDescending { it.weight }
        val maxChildVal = childWeighted.maxOfOrNull { it.totalValue } ?: 0.0
        val sumVal = if (maxChildVal > 1e300) {
            val scaledSum = childWeighted.sumOf { it.totalValue / maxChildVal }
            (scaledSum * maxChildVal).coerceAtMost(Double.MAX_VALUE)
        } else {
            childWeighted.sumOf { it.totalValue }.coerceAtMost(Double.MAX_VALUE)
        }
        return WeightedNode(
            node = node,
            totalValue = sumVal,
            weight = sumVal,
            children = childWeighted,
        )
    }

    private fun squarify(
        weights: List<Double>,
        bounds: DiagramRect,
        gap: Float,
    ): List<DiagramRect> {
        val n = weights.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(bounds)

        val rawRects = Array(n) { DiagramRect(0f, 0f, 0f, 0f) }
        val maxWeight = weights.maxOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val normWeights = DoubleArray(n) { i ->
            val w = weights[i]
            if (maxWeight == null) {
                1.0
            } else if (w.isFinite() && w > 0.0) {
                (w / maxWeight).coerceAtLeast(1e-9)
            } else {
                1e-9
            }
        }
        val totalWeight = normWeights.sum().coerceAtLeast(1e-9)
        val totalArea = (bounds.width * bounds.height).toDouble()
        val areas = DoubleArray(n) { i -> normWeights[i] / totalWeight * totalArea }

        var x0 = bounds.x
        var y0 = bounds.y
        val x1 = bounds.right
        val y1 = bounds.bottom
        var i = 0

        while (i < n) {
            val dx = (x1 - x0).coerceAtLeast(0f)
            val dy = (y1 - y0).coerceAtLeast(0f)
            if (dx <= 0f || dy <= 0f) {
                for (k in i until n) {
                    rawRects[k] = DiagramRect(x0, y0, 0f, 0f)
                }
                break
            }
            val shortSide = min(dx, dy).toDouble()
            var rowEnd = i + 1
            var rowSum = areas[i]
            var minArea = areas[i]
            var maxArea = areas[i]
            var bestScore = worstAspect(rowSum, minArea, maxArea, shortSide)

            while (rowEnd < n) {
                val nextArea = areas[rowEnd]
                val nextSum = rowSum + nextArea
                val nextMin = min(minArea, nextArea)
                val nextMax = max(maxArea, nextArea)
                val nextScore = worstAspect(nextSum, nextMin, nextMax, shortSide)
                if (nextScore <= bestScore) {
                    rowSum = nextSum
                    minArea = nextMin
                    maxArea = nextMax
                    bestScore = nextScore
                    rowEnd++
                } else {
                    break
                }
            }

            if (dx < dy) {
                // Lay out row horizontally across width `dx`
                val rowHeight = if (dx > 0f) (rowSum / dx.toDouble()).toFloat().coerceIn(0f, dy) else dy
                var curX = x0
                for (k in i until rowEnd) {
                    val itemW = if (k == rowEnd - 1) {
                        (x1 - curX).coerceAtLeast(0f)
                    } else if (rowSum > 0.0) {
                        (dx * (areas[k] / rowSum)).toFloat().coerceIn(0f, (x1 - curX).coerceAtLeast(0f))
                    } else {
                        dx / (rowEnd - i).toFloat()
                    }
                    rawRects[k] = DiagramRect(curX, y0, max(0f, itemW), max(0f, rowHeight))
                    curX += itemW
                }
                y0 += rowHeight
            } else {
                // Lay out column vertically across height `dy`
                val colWidth = if (dy > 0f) (rowSum / dy.toDouble()).toFloat().coerceIn(0f, dx) else dx
                var curY = y0
                for (k in i until rowEnd) {
                    val itemH = if (k == rowEnd - 1) {
                        (y1 - curY).coerceAtLeast(0f)
                    } else if (rowSum > 0.0) {
                        (dy * (areas[k] / rowSum)).toFloat().coerceIn(0f, (y1 - curY).coerceAtLeast(0f))
                    } else {
                        dy / (rowEnd - i).toFloat()
                    }
                    rawRects[k] = DiagramRect(x0, curY, max(0f, colWidth), max(0f, itemH))
                    curY += itemH
                }
                x0 += colWidth
            }
            i = rowEnd
        }

        if (gap <= 0f) return rawRects.toList()

        // Apply inner gap between adjacent sibling tiles (without shrinking outer boundary edges).
        val halfGap = gap / 2f
        val eps = max(bounds.width, bounds.height) * 1e-4f
        return rawRects.map { r ->
            val padLeft = if (r.x > bounds.x + eps) min(halfGap, r.width * 0.2f) else 0f
            val padRight = if (r.right < bounds.right - eps) min(halfGap, r.width * 0.2f) else 0f
            val padTop = if (r.y > bounds.y + eps) min(halfGap, r.height * 0.2f) else 0f
            val padBottom = if (r.bottom < bounds.bottom - eps) min(halfGap, r.height * 0.2f) else 0f
            DiagramRect(
                x = r.x + padLeft,
                y = r.y + padTop,
                width = max(0f, r.width - padLeft - padRight),
                height = max(0f, r.height - padTop - padBottom),
            )
        }
    }

    private fun worstAspect(sum: Double, minA: Double, maxA: Double, w: Double): Double {
        if (sum <= 0.0 || minA <= 0.0 || w <= 0.0) return Double.MAX_VALUE
        val s2 = sum * sum
        val w2 = w * w
        val r1 = (w2 * maxA) / s2
        val r2 = s2 / (w2 * minA)
        return max(r1 / GOLDEN_RATIO, r2 * GOLDEN_RATIO)
    }

    fun formatValue(value: Double, formatSpec: String): String {
        if (!value.isFinite()) return value.toString()
        val spec = formatSpec.trim().ifEmpty { "," }
        val hasDollar = spec.startsWith("$")
        val body = if (hasDollar) spec.substring(1) else spec
        val isPercent = body.endsWith("%")
        val isSiPrefix = body.endsWith("s") || body.endsWith("S")
        val precisionMatch = Regex("""\.(\d+)""").find(body)
        val decimals = precisionMatch?.groupValues?.get(1)?.toIntOrNull()
        val useGrouping = body.contains(",") || spec == "$0,0"

        val prefix = if (hasDollar) "$" else ""
        if (isSiPrefix) {
            return prefix + formatSiValue(value, decimals ?: 2)
        }

        val scaled = if (isPercent) value * 100.0 else value
        if (abs(scaled) >= 1e12) {
            val suffix = if (isPercent) "%" else ""
            return "$prefix${formatLargeDouble(scaled)}$suffix"
        }

        val numStr = if (decimals != null) {
            formatFixedDecimals(scaled, decimals, useGrouping)
        } else {
            val rounded = scaled.roundToLong()
            if (abs(scaled - rounded.toDouble()) < 1e-6) {
                if (useGrouping) groupInteger(rounded) else rounded.toString()
            } else {
                formatTrimmedDecimals(scaled, useGrouping)
            }
        }

        val suffix = if (isPercent) "%" else ""
        return "$prefix$numStr$suffix"
    }

    private fun formatSiValue(v: Double, sigDigits: Int): String {
        val absV = abs(v)
        val (divisor, unit) = when {
            absV >= 1e12 -> 1e12 to "T"
            absV >= 1e9 -> 1e9 to "G"
            absV >= 1e6 -> 1e6 to "M"
            absV >= 1e3 -> 1e3 to "k"
            else -> 1.0 to ""
        }
        val scaled = v / divisor
        if (abs(scaled) >= 1e12) return "${formatLargeDouble(scaled)}$unit"
        val sig = sigDigits.coerceIn(1, 6)
        val absScaled = abs(scaled)
        val intDigits = when {
            absScaled >= 100.0 -> 3
            absScaled >= 10.0 -> 2
            absScaled >= 1.0 -> 1
            else -> 0
        }
        val formatted = if (sig < intDigits) {
            val step = 10.0.pow(intDigits - sig)
            val rounded = (scaled / step).roundToLong() * step.roundToLong()
            rounded.toString()
        } else {
            val dec = (sig - intDigits).coerceIn(0, 6)
            val raw = formatFixedDecimals(scaled, dec, false)
            if (raw.contains('.')) raw.trimEnd('0').trimEnd('.') else raw
        }
        return "$formatted$unit"
    }

    private fun formatLargeDouble(v: Double): String {
        val s = v.toString()
        if (!s.contains('e', ignoreCase = true) && !s.contains('.')) {
            return s
        }
        return s
    }

    private fun formatFixedDecimals(v: Double, decimals: Int, useGrouping: Boolean): String {
        val d = decimals.coerceIn(0, 6)
        val factor = 10.0.pow(d)
        val scaledInt = (v * factor).roundToLong()
        val sign = if (scaledInt < 0) "-" else ""
        val absScaled = abs(scaledInt)
        val intFactor = factor.roundToLong().coerceAtLeast(1L)
        val intPart = absScaled / intFactor
        val fracPart = absScaled % intFactor
        val intStr = if (useGrouping) groupInteger(intPart) else intPart.toString()
        if (d == 0) return "$sign$intStr"
        val fracStr = fracPart.toString().padStart(d, '0')
        return "$sign$intStr.$fracStr"
    }

    private fun formatTrimmedDecimals(v: Double, useGrouping: Boolean): String {
        val raw = formatFixedDecimals(v, 4, useGrouping)
        if (!raw.contains('.')) return raw
        return raw.trimEnd('0').trimEnd('.')
    }

    private fun groupInteger(n: Long): String {
        val sign = if (n < 0) "-" else ""
        val digits = abs(n).toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) {
                sb.append(',')
            }
            sb.append(ch)
        }
        return "$sign$sb"
    }
}
