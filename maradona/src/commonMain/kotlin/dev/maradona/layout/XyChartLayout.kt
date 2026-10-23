package dev.maradona.layout

import dev.maradona.model.XyAxisSpec
import dev.maradona.model.XyChartDiagram
import dev.maradona.model.XyChartOrientation
import dev.maradona.model.XyPlotSeries
import dev.maradona.model.XyPlotType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

enum class XyAxisSide {
    Left,
    Bottom,
    Top,
}

data class PositionedXyTick(
    val id: String,
    val side: XyAxisSide,
    val label: String,
    val lineStart: DiagramPoint,
    val lineEnd: DiagramPoint,
    val gridStart: DiagramPoint,
    val gridEnd: DiagramPoint,
    val labelRect: DiagramRect,
)

data class PositionedXyAxisTitle(
    val id: String,
    val side: XyAxisSide,
    val text: String,
    val rect: DiagramRect,
    val unrotatedSize: DiagramSize,
    val rotationDeg: Float,
)

data class PositionedXyBar(
    val id: String,
    val seriesId: String,
    val seriesIndex: Int,
    val pointIndex: Int,
    val categoryOrX: String,
    val value: Double,
    val rect: DiagramRect,
    val dataLabel: String? = null,
    val dataLabelRect: DiagramRect? = null,
    val dataLabelOutside: Boolean = false,
)

data class PositionedXyLinePoint(
    val id: String,
    val seriesId: String,
    val seriesIndex: Int,
    val pointIndex: Int,
    val categoryOrX: String,
    val value: Double,
    val position: DiagramPoint,
    val label: String? = null,
    val labelRect: DiagramRect? = null,
)

data class PositionedXyLineSeries(
    val series: XyPlotSeries,
    val seriesIndex: Int,
    val points: List<PositionedXyLinePoint>,
)

data class PositionedXyLegendItem(
    val series: XyPlotSeries,
    val seriesIndex: Int,
    val markerRect: DiagramRect,
    val labelRect: DiagramRect,
)

data class XyChartDiagramLayout(
    val width: Float,
    val height: Float,
    val orientation: XyChartOrientation,
    val titleRect: DiagramRect?,
    val plotRect: DiagramRect,
    val xAxisLineStart: DiagramPoint,
    val xAxisLineEnd: DiagramPoint,
    val yAxisLineStart: DiagramPoint,
    val yAxisLineEnd: DiagramPoint,
    val xTicks: List<PositionedXyTick>,
    val yTicks: List<PositionedXyTick>,
    val axisTitles: List<PositionedXyAxisTitle>,
    val bars: List<PositionedXyBar>,
    val lines: List<PositionedXyLineSeries>,
    val legendItems: List<PositionedXyLegendItem>,
)

/**
 * Pure-Kotlin layout engine for Mermaid `xychart` / `xychart-beta` diagrams.
 *
 * Computes vertical and horizontal chart layouts, categorical band and numeric linear axes,
 * nice linear tick intervals, grouped bar rectangles, line polylines, data/point labels,
 * and legend items.
 */
object XyChartLayout {

    fun layout(
        diagram: XyChartDiagram,
        tickLabelSize: (String) -> DiagramSize = { s -> defaultTickLabelSize(s, 1f) },
        axisTitleSize: (String) -> DiagramSize = { s -> defaultAxisTitleSize(s, 1f) },
        legendLabelSize: (String) -> DiagramSize = { s -> defaultTickLabelSize(s, 1f) },
        pointLabelSize: (String) -> DiagramSize = { s -> defaultPointLabelSize(s, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): XyChartDiagramLayout {
        val d = options.density
        val outerPad = 20f * d
        val tickLen = 5f * d
        val tickLabelGap = 6f * d
        val axisTitleGap = 10f * d

        val resolvedTitleSize = if (diagram.showTitle && !diagram.title.isNullOrBlank()) {
            titleSize ?: DiagramSize(max(120f * d, diagram.title.length * 9.5f * d), 24f * d)
        } else {
            null
        }

        // Determine Y-axis linear domain and nice ticks.
        val (yMin, yMax) = effectiveYDomain(diagram.yAxis)
        val yTickNumbers = generateLinearTicks(yMin, yMax, targetCount = 6)
        val yTickLabels = yTickNumbers.map { formatNumber(it) }

        // Determine X-axis tick labels and normalized positions.
        val xTickLabels: List<String>
        val xLinearValues: List<Double>?
        val xDomainMin: Double
        val xDomainMax: Double
        when (val xa = diagram.xAxis) {
            is XyAxisSpec.BandAxis -> {
                xTickLabels = xa.categories
                xLinearValues = null
                xDomainMin = 0.0
                xDomainMax = max(1, xa.categories.size - 1).toDouble()
            }
            is XyAxisSpec.LinearAxis -> {
                val (xm0, xm1) = effectiveXDomain(xa)
                xDomainMin = xm0
                xDomainMax = xm1
                val ticks = generateLinearTicks(xm0, xm1, targetCount = 6)
                xLinearValues = ticks
                xTickLabels = ticks.map { formatNumber(it) }
            }
        }

        // Legend items (plots that have a non-blank title when showLegend is true).
        val titledPlots = if (diagram.showLegend) {
            diagram.plots.mapIndexedNotNull { idx, p ->
                if (!p.title.isNullOrBlank()) idx to p else null
            }
        } else {
            emptyList()
        }
        val markerSize = 11f * d
        val markerTextGap = 6f * d
        val legendRowGap = 8f * d
        val legendPadLeft = 16f * d
        val maxLegendTextW = titledPlots.maxOfOrNull { (_, p) -> legendLabelSize(p.title!!).width } ?: 0f
        val maxLegendTextH = titledPlots.maxOfOrNull { (_, p) -> legendLabelSize(p.title!!).height } ?: (16f * d)
        val legendRowH = max(markerSize, maxLegendTextH)
        val totalLegendH = if (titledPlots.isNotEmpty()) {
            titledPlots.size * legendRowH + (titledPlots.size - 1) * legendRowGap
        } else {
            0f
        }
        val legendWidth = if (titledPlots.isNotEmpty()) {
            legendPadLeft + markerSize + markerTextGap + maxLegendTextW
        } else {
            0f
        }

        val xAxisTitleText = diagram.xAxis.title?.takeIf { it.isNotBlank() }
        val yAxisTitleText = diagram.yAxis.title?.takeIf { it.isNotBlank() }
        val xAxisTitleSz = xAxisTitleText?.let(axisTitleSize)
        val yAxisTitleSz = yAxisTitleText?.let(axisTitleSize)

        val isHorizontal = diagram.orientation == XyChartOrientation.Horizontal

        // Left-axis labels and title depend on orientation:
        // Vertical: Left = Y-axis, Bottom = X-axis
        // Horizontal: Left = X-axis, Top = Y-axis
        val leftTickSizes = (if (isHorizontal) xTickLabels else yTickLabels).map(tickLabelSize)
        val horizTickSizes = (if (isHorizontal) yTickLabels else xTickLabels).map(tickLabelSize)

        val maxLeftTickW = leftTickSizes.maxOfOrNull { it.width } ?: (24f * d)
        val maxLeftTickH = leftTickSizes.maxOfOrNull { it.height } ?: (14f * d)
        val maxHorizTickW = horizTickSizes.maxOfOrNull { it.width } ?: (24f * d)
        val maxHorizTickH = horizTickSizes.maxOfOrNull { it.height } ?: (14f * d)

        val leftTitleSz = if (isHorizontal) xAxisTitleSz else yAxisTitleSz
        val horizTitleSz = if (isHorizontal) yAxisTitleSz else xAxisTitleSz

        // Rotated -90 deg title on the left uses its `height` as horizontal thickness.
        val leftTitleBandW = if (leftTitleSz != null) leftTitleSz.height + axisTitleGap else 0f
        val leftAxisBandW = leftTitleBandW + maxLeftTickW + tickLabelGap + tickLen

        val horizTitleBandH = if (horizTitleSz != null) horizTitleSz.height + axisTitleGap else 0f
        val horizAxisBandH = maxHorizTickH + tickLabelGap + tickLen + horizTitleBandH

        val baseChartW = max(320f, diagram.chartWidth) * d
        val baseChartH = max(240f, diagram.chartHeight) * d

        var contentTopY = outerPad
        val titleTopY = contentTopY
        if (resolvedTitleSize != null) {
            contentTopY += resolvedTitleSize.height + 14f * d
        }

        // Reserve inner headroom on the Y-axis when outside bar data labels are enabled so
        // labels at domain extremes remain strictly outside their bars and inside plotRect,
        // and grow plotRect so yPadAtMin + yPadAtMax never inverts the drawable Y span.
        val outsideBarLabelSizes = diagram.plots
            .filter { it.type == XyPlotType.Bar }
            .flatMap { s ->
                s.points.mapNotNull { pt ->
                    val txt = pt.label ?: if (diagram.showDataLabel && diagram.showDataLabelOutsideBar) {
                        formatNumber(pt.value)
                    } else {
                        null
                    }
                    txt?.let(pointLabelSize)
                }
            }
        val maxOutsideBarLabelW = outsideBarLabelSizes.maxOfOrNull { it.width } ?: 0f
        val maxOutsideBarLabelH = outsideBarLabelSizes.maxOfOrNull { it.height } ?: 0f
        val yLo = min(yMin, yMax)
        val yHi = max(yMin, yMax)
        val barBaseVal = 0.0.coerceIn(yLo, yHi)
        val barValues = diagram.plots.filter { it.type == XyPlotType.Bar }.flatMap { s -> s.points.map { it.value } }
        val hasBarTowardsMin = barValues.any { if (yMin <= yMax) it < barBaseVal else it > barBaseVal }
        val hasBarTowardsMax = barValues.any { if (yMin <= yMax) it >= barBaseVal else it <= barBaseVal }

        val yPadAtMin = if (outsideBarLabelSizes.isNotEmpty() && hasBarTowardsMin) {
            if (isHorizontal) maxOutsideBarLabelW + 10f * d else maxOutsideBarLabelH + 8f * d
        } else {
            0f
        }
        val yPadAtMax = if (outsideBarLabelSizes.isNotEmpty() && hasBarTowardsMax) {
            if (isHorizontal) maxOutsideBarLabelW + 10f * d else maxOutsideBarLabelH + 8f * d
        } else {
            0f
        }

        val hasBarPlot = diagram.plots.any { it.type == XyPlotType.Bar }
        val barSeriesCountForSizing = max(1, diagram.plots.count { it.type == XyPlotType.Bar })
        val maxBarPoints = diagram.plots
            .filter { it.type == XyPlotType.Bar }
            .maxOfOrNull { it.points.size } ?: 1
        val xTickCount = max(1, xTickLabels.size)
        val xSlotCount = when (val xa = diagram.xAxis) {
            is XyAxisSpec.BandAxis -> max(1, xa.categories.size)
            is XyAxisSpec.LinearAxis -> max(xTickCount, maxBarPoints)
        }
        val minSlotForBars = if (hasBarPlot) {
            (barSeriesCountForSizing * 4f * d + (barSeriesCountForSizing - 1) * 2f * d + 8f * d) / 0.8f
        } else {
            0f
        }

        val linePointLabelSizes = diagram.plots
            .filter { it.type == XyPlotType.Line }
            .flatMap { s -> s.points.mapNotNull { pt -> pt.label?.takeIf { it.isNotBlank() }?.let(pointLabelSize) } }
        val maxLinePointLabelW = linePointLabelSizes.maxOfOrNull { it.width } ?: 0f

        val leftReservedW = max(leftAxisBandW, maxHorizTickW / 2f)
        val rightReservedW = max(legendWidth, maxHorizTickW / 2f)
        val plotX = outerPad + leftReservedW
        val plotY = if (isHorizontal) contentTopY + horizAxisBandH else contentTopY
        val minPlotW = maxOf(
            220f * d,
            horizTitleSz?.width ?: 0f,
            maxOutsideBarLabelW + 16f * d,
            maxLinePointLabelW + 16f * d,
            if (!isHorizontal) {
                max(
                    xTickLabels.size * (maxHorizTickW + 10f * d),
                    xSlotCount * minSlotForBars,
                )
            } else {
                yPadAtMin + yPadAtMax + 180f * d
            },
        )
        val minPlotH = maxOf(
            160f * d,
            leftTitleSz?.width ?: 0f,
            totalLegendH,
            if (isHorizontal) {
                max(
                    xTickLabels.size * (maxLeftTickH + 10f * d),
                    xSlotCount * minSlotForBars,
                )
            } else {
                yPadAtMin + yPadAtMax + 140f * d
            },
        )

        val plotW = max(minPlotW, baseChartW - outerPad * 2f - leftReservedW - rightReservedW)
        val plotH = max(
            minPlotH,
            baseChartH - contentTopY - outerPad - horizAxisBandH,
        )
        val plotRect = DiagramRect(plotX, plotY, plotW, plotH)

        // Compute outer padding along the X-axis (band/category or linear) to match Mermaid's BaseAxis,
        // accounting for actual bar point counts on linear X-axes so dense bar series do not overlap.
        val xSpan = if (isHorizontal) plotH else plotW
        val xLabelHalf = if (isHorizontal) maxLeftTickH / 2f else maxHorizTickW / 2f
        var xOuterPad = min(xLabelHalf, 0.2f * xSpan)
        if (hasBarPlot) {
            val initialSlotDist = max(1f, (xSpan - 2f * xOuterPad) / xSlotCount)
            if (0.7f * initialSlotDist > xOuterPad * 2f) {
                xOuterPad = 0.35f * initialSlotDist
            }
        }
        val xRangeStart = (if (isHorizontal) plotRect.y else plotRect.x) + xOuterPad
        val xRangeEnd = (if (isHorizontal) plotRect.bottom else plotRect.right) - xOuterPad

        fun xCoordForBandIndex(index: Int, count: Int): Float {
            if (count <= 1) return (xRangeStart + xRangeEnd) / 2f
            val step = (xRangeEnd - xRangeStart) / (count - 1).toFloat()
            return xRangeStart + index * step
        }

        fun xCoordForLinearValue(v: Double): Float {
            val denom = xDomainMax - xDomainMin
            if (denom == 0.0 || !denom.isFinite()) return (xRangeStart + xRangeEnd) / 2f
            val t = ((v - xDomainMin) / denom).toFloat().coerceIn(0f, 1f)
            return xRangeStart + t * (xRangeEnd - xRangeStart)
        }

        val yRangeMinCoord = if (isHorizontal) plotRect.x + yPadAtMin else plotRect.bottom - yPadAtMin
        val yRangeMaxCoord = if (isHorizontal) plotRect.right - yPadAtMax else plotRect.y + yPadAtMax

        fun yCoordForValue(v: Double): Float {
            val denom = yMax - yMin
            val t = if (denom == 0.0 || !denom.isFinite()) 0.5f else ((v - yMin) / denom).toFloat().coerceIn(0f, 1f)
            return yRangeMinCoord + t * (yRangeMaxCoord - yRangeMinCoord)
        }

        // Build X-axis ticks.
        val xTicks = mutableListOf<PositionedXyTick>()
        for (i in xTickLabels.indices) {
            val label = xTickLabels[i]
            val lSz = if (isHorizontal) leftTickSizes[i] else horizTickSizes[i]
            val pos = if (xLinearValues != null) {
                xCoordForLinearValue(xLinearValues[i])
            } else {
                xCoordForBandIndex(i, xTickLabels.size)
            }
            if (isHorizontal) {
                xTicks += PositionedXyTick(
                    id = "xtick_$i",
                    side = XyAxisSide.Left,
                    label = label,
                    lineStart = DiagramPoint(plotRect.x, pos),
                    lineEnd = DiagramPoint(plotRect.x - tickLen, pos),
                    gridStart = DiagramPoint(plotRect.x, pos),
                    gridEnd = DiagramPoint(plotRect.right, pos),
                    labelRect = DiagramRect(
                        x = plotRect.x - tickLen - tickLabelGap - lSz.width,
                        y = pos - lSz.height / 2f,
                        width = lSz.width,
                        height = lSz.height,
                    ),
                )
            } else {
                xTicks += PositionedXyTick(
                    id = "xtick_$i",
                    side = XyAxisSide.Bottom,
                    label = label,
                    lineStart = DiagramPoint(pos, plotRect.bottom),
                    lineEnd = DiagramPoint(pos, plotRect.bottom + tickLen),
                    gridStart = DiagramPoint(pos, plotRect.y),
                    gridEnd = DiagramPoint(pos, plotRect.bottom),
                    labelRect = DiagramRect(
                        x = pos - lSz.width / 2f,
                        y = plotRect.bottom + tickLen + tickLabelGap,
                        width = lSz.width,
                        height = lSz.height,
                    ),
                )
            }
        }

        // Build Y-axis ticks.
        val yTicks = mutableListOf<PositionedXyTick>()
        for (i in yTickNumbers.indices) {
            val v = yTickNumbers[i]
            val label = yTickLabels[i]
            val lSz = if (isHorizontal) horizTickSizes[i] else leftTickSizes[i]
            val pos = yCoordForValue(v)
            if (isHorizontal) {
                yTicks += PositionedXyTick(
                    id = "ytick_$i",
                    side = XyAxisSide.Top,
                    label = label,
                    lineStart = DiagramPoint(pos, plotRect.y),
                    lineEnd = DiagramPoint(pos, plotRect.y - tickLen),
                    gridStart = DiagramPoint(pos, plotRect.y),
                    gridEnd = DiagramPoint(pos, plotRect.bottom),
                    labelRect = DiagramRect(
                        x = pos - lSz.width / 2f,
                        y = plotRect.y - tickLen - tickLabelGap - lSz.height,
                        width = lSz.width,
                        height = lSz.height,
                    ),
                )
            } else {
                yTicks += PositionedXyTick(
                    id = "ytick_$i",
                    side = XyAxisSide.Left,
                    label = label,
                    lineStart = DiagramPoint(plotRect.x, pos),
                    lineEnd = DiagramPoint(plotRect.x - tickLen, pos),
                    gridStart = DiagramPoint(plotRect.x, pos),
                    gridEnd = DiagramPoint(plotRect.right, pos),
                    labelRect = DiagramRect(
                        x = plotRect.x - tickLen - tickLabelGap - lSz.width,
                        y = pos - lSz.height / 2f,
                        width = lSz.width,
                        height = lSz.height,
                    ),
                )
            }
        }

        // Build Axis Titles.
        val axisTitles = mutableListOf<PositionedXyAxisTitle>()
        if (leftTitleSz != null) {
            val text = (if (isHorizontal) xAxisTitleText else yAxisTitleText)!!
            val boxW = leftTitleSz.height
            val boxH = leftTitleSz.width
            axisTitles += PositionedXyAxisTitle(
                id = "axtitle_left",
                side = XyAxisSide.Left,
                text = text,
                rect = DiagramRect(
                    x = outerPad,
                    y = plotRect.centerY - boxH / 2f,
                    width = boxW,
                    height = boxH,
                ),
                unrotatedSize = leftTitleSz,
                rotationDeg = -90f,
            )
        }
        if (horizTitleSz != null) {
            val text = (if (isHorizontal) yAxisTitleText else xAxisTitleText)!!
            if (isHorizontal) {
                axisTitles += PositionedXyAxisTitle(
                    id = "axtitle_top",
                    side = XyAxisSide.Top,
                    text = text,
                    rect = DiagramRect(
                        x = plotRect.centerX - horizTitleSz.width / 2f,
                        y = contentTopY,
                        width = horizTitleSz.width,
                        height = horizTitleSz.height,
                    ),
                    unrotatedSize = horizTitleSz,
                    rotationDeg = 0f,
                )
            } else {
                val yTop = plotRect.bottom + tickLen + tickLabelGap + maxHorizTickH + axisTitleGap
                axisTitles += PositionedXyAxisTitle(
                    id = "axtitle_bottom",
                    side = XyAxisSide.Bottom,
                    text = text,
                    rect = DiagramRect(
                        x = plotRect.centerX - horizTitleSz.width / 2f,
                        y = yTop,
                        width = horizTitleSz.width,
                        height = horizTitleSz.height,
                    ),
                    unrotatedSize = horizTitleSz,
                    rotationDeg = 0f,
                )
            }
        }

        // Position bars and lines.
        val barSeriesIndices = diagram.plots.mapIndexedNotNull { idx, s ->
            if (s.type == XyPlotType.Bar) idx else null
        }
        val barSeriesCount = max(1, barSeriesIndices.size)
        val tickDist = if (xSlotCount > 1) {
            abs(xRangeEnd - xRangeStart) / (xSlotCount - 1).toFloat()
        } else {
            abs(xRangeEnd - xRangeStart).coerceAtLeast(40f * d)
        }
        val clusterSpan = min(xOuterPad * 2f, tickDist) * 0.85f
        val barGap = if (barSeriesCount > 1) {
            min(2f * d, clusterSpan * 0.25f / (barSeriesCount - 1).toFloat())
        } else {
            0f
        }
        val singleBarThickness = max(
            1f * d,
            (clusterSpan - barGap * (barSeriesCount - 1)) / barSeriesCount,
        )
        val actualClusterSpan = singleBarThickness * barSeriesCount + barGap * (barSeriesCount - 1)

        val bars = mutableListOf<PositionedXyBar>()
        val lines = mutableListOf<PositionedXyLineSeries>()

        fun pointXCoordAndCategory(pointIndex: Int, totalPoints: Int): Pair<Float, String> {
            return when (val xa = diagram.xAxis) {
                is XyAxisSpec.BandAxis -> {
                    val cat = xa.categories.getOrElse(pointIndex) { "${pointIndex + 1}" }
                    xCoordForBandIndex(pointIndex, xa.categories.size) to cat
                }
                is XyAxisSpec.LinearAxis -> {
                    val xVal = if (totalPoints <= 1) {
                        xa.min
                    } else {
                        xa.min + pointIndex.toDouble() * (xa.max - xa.min) / (totalPoints - 1).toDouble()
                    }
                    xCoordForLinearValue(xVal) to formatNumber(xVal)
                }
            }
        }

        val barBaseCoord = yCoordForValue(barBaseVal)

        for ((seriesIdx, series) in diagram.plots.withIndex()) {
            when (series.type) {
                XyPlotType.Bar -> {
                    val barSlot = barSeriesIndices.indexOf(seriesIdx).coerceAtLeast(0)
                    val clusterStartOffset = -actualClusterSpan / 2f + barSlot * (singleBarThickness + barGap)
                    for ((ptIdx, pt) in series.points.withIndex()) {
                        val (bandCenter, catLabel) = pointXCoordAndCategory(ptIdx, series.points.size)
                        val valCoord = yCoordForValue(pt.value)
                        val barRect = if (isHorizontal) {
                            val left = min(barBaseCoord, valCoord)
                            val right = max(barBaseCoord, valCoord)
                            DiagramRect(
                                x = left,
                                y = bandCenter + clusterStartOffset,
                                width = max(1f * d, right - left),
                                height = singleBarThickness,
                            )
                        } else {
                            val top = min(valCoord, barBaseCoord)
                            val bottom = max(valCoord, barBaseCoord)
                            DiagramRect(
                                x = bandCenter + clusterStartOffset,
                                y = top,
                                width = singleBarThickness,
                                height = max(1f * d, bottom - top),
                            )
                        }

                        val dataLabelText = pt.label ?: if (diagram.showDataLabel) formatNumber(pt.value) else null
                        val dataLabelRect = if (dataLabelText != null) {
                            val dlSz = pointLabelSize(dataLabelText)
                            val outside = diagram.showDataLabelOutsideBar || pt.label != null
                            if (isHorizontal) {
                                val extendsLeft = valCoord < barBaseCoord
                                val lx = if (outside) {
                                    if (extendsLeft) {
                                        barRect.x - dlSz.width - 6f * d
                                    } else {
                                        barRect.right + 6f * d
                                    }
                                } else {
                                    if (extendsLeft) {
                                        min(barRect.right - dlSz.width, barRect.x + 6f * d)
                                    } else {
                                        max(barRect.x + 2f * d, barRect.right - dlSz.width - 6f * d)
                                    }
                                }
                                val ly = (barRect.centerY - dlSz.height / 2f)
                                    .coerceIn(plotRect.y, max(plotRect.y, plotRect.bottom - dlSz.height))
                                DiagramRect(
                                    x = lx,
                                    y = ly,
                                    width = dlSz.width,
                                    height = dlSz.height,
                                )
                            } else {
                                val extendsDown = valCoord > barBaseCoord
                                val ly = if (outside) {
                                    if (extendsDown) {
                                        barRect.bottom + 4f * d
                                    } else {
                                        barRect.y - dlSz.height - 4f * d
                                    }
                                } else {
                                    if (extendsDown) {
                                        max(barRect.y, barRect.bottom - dlSz.height - 4f * d)
                                    } else {
                                        min(barRect.bottom - dlSz.height, barRect.y + 4f * d)
                                    }
                                }
                                val lx = (barRect.centerX - dlSz.width / 2f)
                                    .coerceIn(plotRect.x, max(plotRect.x, plotRect.right - dlSz.width))
                                DiagramRect(
                                    x = lx,
                                    y = ly,
                                    width = dlSz.width,
                                    height = dlSz.height,
                                )
                            }
                        } else {
                            null
                        }

                        bars += PositionedXyBar(
                            id = "bar_${seriesIdx}_$ptIdx",
                            seriesId = series.id,
                            seriesIndex = seriesIdx,
                            pointIndex = ptIdx,
                            categoryOrX = catLabel,
                            value = pt.value,
                            rect = barRect,
                            dataLabel = dataLabelText,
                            dataLabelRect = dataLabelRect,
                            dataLabelOutside = diagram.showDataLabelOutsideBar || pt.label != null,
                        )
                    }
                }
                XyPlotType.Line -> {
                    val linePts = series.points.mapIndexed { ptIdx, pt ->
                        val (xCoord, catLabel) = pointXCoordAndCategory(ptIdx, series.points.size)
                        val yCoord = yCoordForValue(pt.value)
                        val pos = if (isHorizontal) {
                            DiagramPoint(yCoord, xCoord)
                        } else {
                            DiagramPoint(xCoord, yCoord)
                        }
                        val lbl = pt.label
                        val lblRect = if (!lbl.isNullOrBlank()) {
                            val lSz = pointLabelSize(lbl)
                            if (isHorizontal) {
                                val rawLx = pos.x + 8f * d
                                val lx = if (rawLx + lSz.width <= plotRect.right + 4f * d) {
                                    rawLx
                                } else {
                                    max(plotRect.x, pos.x - 8f * d - lSz.width)
                                }
                                val ly = (pos.y - lSz.height / 2f).coerceIn(plotRect.y, max(plotRect.y, plotRect.bottom - lSz.height))
                                DiagramRect(lx, ly, lSz.width, lSz.height)
                            } else {
                                val lx = (pos.x - lSz.width / 2f).coerceIn(plotRect.x, max(plotRect.x, plotRect.right - lSz.width))
                                val rawLy = pos.y - lSz.height - 6f * d
                                val ly = if (rawLy >= plotRect.y - 4f * d) {
                                    rawLy
                                } else {
                                    min(max(plotRect.y, plotRect.bottom - lSz.height), pos.y + 6f * d)
                                }
                                DiagramRect(lx, ly, lSz.width, lSz.height)
                            }
                        } else {
                            null
                        }
                        PositionedXyLinePoint(
                            id = "linept_${seriesIdx}_$ptIdx",
                            seriesId = series.id,
                            seriesIndex = seriesIdx,
                            pointIndex = ptIdx,
                            categoryOrX = catLabel,
                            value = pt.value,
                            position = pos,
                            label = lbl,
                            labelRect = lblRect,
                        )
                    }
                    lines += PositionedXyLineSeries(
                        series = series,
                        seriesIndex = seriesIdx,
                        points = linePts,
                    )
                }
            }
        }

        // Legend placement to the right of plotRect, vertically centered.
        val legendItems = mutableListOf<PositionedXyLegendItem>()
        if (titledPlots.isNotEmpty()) {
            val rowH = max(markerSize, maxLegendTextH)
            val totalLegendH = titledPlots.size * rowH + (titledPlots.size - 1) * legendRowGap
            val startX = plotRect.right + legendPadLeft
            val startY = plotRect.y + max(0f, (plotRect.height - totalLegendH) / 2f)
            for ((rowIdx, pair) in titledPlots.withIndex()) {
                val (seriesIdx, series) = pair
                val lSz = legendLabelSize(series.title!!)
                val rowTop = startY + rowIdx * (rowH + legendRowGap)
                val rowMidY = rowTop + rowH / 2f
                legendItems += PositionedXyLegendItem(
                    series = series,
                    seriesIndex = seriesIdx,
                    markerRect = DiagramRect(
                        x = startX,
                        y = rowMidY - markerSize / 2f,
                        width = markerSize,
                        height = markerSize,
                    ),
                    labelRect = DiagramRect(
                        x = startX + markerSize + markerTextGap,
                        y = rowMidY - lSz.height / 2f,
                        width = lSz.width,
                        height = lSz.height,
                    ),
                )
            }
        }

        val legendBottom = legendItems.maxOfOrNull { max(it.markerRect.bottom, it.labelRect.bottom) } ?: 0f
        val maxTickRight = max(
            xTicks.maxOfOrNull { it.labelRect.right } ?: 0f,
            yTicks.maxOfOrNull { it.labelRect.right } ?: 0f,
        )
        val maxTickBottom = max(
            xTicks.maxOfOrNull { it.labelRect.bottom } ?: 0f,
            yTicks.maxOfOrNull { it.labelRect.bottom } ?: 0f,
        )
        val maxAxisTitleRight = axisTitles.maxOfOrNull { it.rect.right } ?: 0f
        val maxAxisTitleBottom = axisTitles.maxOfOrNull { it.rect.bottom } ?: 0f
        val maxDataLabelRight = bars.mapNotNull { it.dataLabelRect }.maxOfOrNull { it.right } ?: 0f
        val maxDataLabelBottom = bars.mapNotNull { it.dataLabelRect }.maxOfOrNull { it.bottom } ?: 0f
        val lineLabelRects = lines.flatMap { ls -> ls.points.mapNotNull { it.labelRect } }
        val maxLineLabelRight = lineLabelRects.maxOfOrNull { it.right } ?: 0f
        val maxLineLabelBottom = lineLabelRects.maxOfOrNull { it.bottom } ?: 0f
        val totalW = maxOf(
            plotRect.right + legendWidth + outerPad,
            (resolvedTitleSize?.width ?: 0f) + outerPad * 2f,
            maxTickRight + outerPad,
            maxAxisTitleRight + outerPad,
            maxDataLabelRight + outerPad,
            maxLineLabelRight + outerPad,
        )
        val bottomExtent = maxOf(
            legendBottom + outerPad,
            maxTickBottom + outerPad,
            maxAxisTitleBottom + outerPad,
            maxDataLabelBottom + outerPad,
            maxLineLabelBottom + outerPad,
            if (isHorizontal) {
                plotRect.bottom + outerPad
            } else {
                plotRect.bottom + horizAxisBandH + outerPad
            },
        )
        val totalH = max(baseChartH, bottomExtent)

        val titleRect = resolvedTitleSize?.let {
            val cx = plotRect.centerX
            val tx = (cx - it.width / 2f).coerceIn(outerPad, max(outerPad, totalW - outerPad - it.width))
            DiagramRect(tx, titleTopY, it.width, it.height)
        }

        val xAxisLineStart: DiagramPoint
        val xAxisLineEnd: DiagramPoint
        val yAxisLineStart: DiagramPoint
        val yAxisLineEnd: DiagramPoint
        if (isHorizontal) {
            xAxisLineStart = DiagramPoint(plotRect.x, plotRect.y)
            xAxisLineEnd = DiagramPoint(plotRect.x, plotRect.bottom)
            yAxisLineStart = DiagramPoint(plotRect.x, plotRect.y)
            yAxisLineEnd = DiagramPoint(plotRect.right, plotRect.y)
        } else {
            xAxisLineStart = DiagramPoint(plotRect.x, plotRect.bottom)
            xAxisLineEnd = DiagramPoint(plotRect.right, plotRect.bottom)
            yAxisLineStart = DiagramPoint(plotRect.x, plotRect.y)
            yAxisLineEnd = DiagramPoint(plotRect.x, plotRect.bottom)
        }

        val initialLayout = XyChartDiagramLayout(
            width = totalW,
            height = totalH,
            orientation = diagram.orientation,
            titleRect = titleRect,
            plotRect = plotRect,
            xAxisLineStart = xAxisLineStart,
            xAxisLineEnd = xAxisLineEnd,
            yAxisLineStart = yAxisLineStart,
            yAxisLineEnd = yAxisLineEnd,
            xTicks = xTicks,
            yTicks = yTicks,
            axisTitles = axisTitles,
            bars = bars,
            lines = lines,
            legendItems = legendItems,
        )
        return normalizeLayoutBounds(initialLayout, outerPad, baseChartW, baseChartH)
    }

    private fun normalizeLayoutBounds(
        layout: XyChartDiagramLayout,
        outerPad: Float,
        baseChartW: Float,
        baseChartH: Float,
    ): XyChartDiagramLayout {
        val allRects = buildList {
            layout.titleRect?.let { add(it) }
            add(layout.plotRect)
            layout.xTicks.forEach { add(it.labelRect) }
            layout.yTicks.forEach { add(it.labelRect) }
            layout.axisTitles.forEach { add(it.rect) }
            layout.bars.forEach {
                add(it.rect)
                it.dataLabelRect?.let { r -> add(r) }
            }
            layout.lines.forEach { ls ->
                ls.points.forEach { pt -> pt.labelRect?.let { r -> add(r) } }
            }
            layout.legendItems.forEach {
                add(it.markerRect)
                add(it.labelRect)
            }
        }
        val minX = allRects.minOfOrNull { it.x } ?: outerPad
        val minY = allRects.minOfOrNull { it.y } ?: outerPad
        val dx = if (minX < outerPad) outerPad - minX else 0f
        val dy = if (minY < outerPad) outerPad - minY else 0f

        fun DiagramPoint.shift(): DiagramPoint = DiagramPoint(x + dx, y + dy)
        fun DiagramRect.shift(): DiagramRect = DiagramRect(x + dx, y + dy, width, height)

        val shiftedTitle = layout.titleRect?.shift()
        val shiftedPlot = layout.plotRect.shift()
        val shiftedXTicks = layout.xTicks.map {
            it.copy(
                lineStart = it.lineStart.shift(),
                lineEnd = it.lineEnd.shift(),
                gridStart = it.gridStart.shift(),
                gridEnd = it.gridEnd.shift(),
                labelRect = it.labelRect.shift(),
            )
        }
        val shiftedYTicks = layout.yTicks.map {
            it.copy(
                lineStart = it.lineStart.shift(),
                lineEnd = it.lineEnd.shift(),
                gridStart = it.gridStart.shift(),
                gridEnd = it.gridEnd.shift(),
                labelRect = it.labelRect.shift(),
            )
        }
        val shiftedAxisTitles = layout.axisTitles.map { it.copy(rect = it.rect.shift()) }
        val shiftedBars = layout.bars.map {
            it.copy(
                rect = it.rect.shift(),
                dataLabelRect = it.dataLabelRect?.shift(),
            )
        }
        val shiftedLines = layout.lines.map { ls ->
            ls.copy(
                points = ls.points.map { pt ->
                    pt.copy(
                        position = pt.position.shift(),
                        labelRect = pt.labelRect?.shift(),
                    )
                },
            )
        }
        val shiftedLegend = layout.legendItems.map {
            it.copy(
                markerRect = it.markerRect.shift(),
                labelRect = it.labelRect.shift(),
            )
        }

        val maxRight = (allRects.maxOfOrNull { it.right } ?: 0f) + dx
        val maxBottom = (allRects.maxOfOrNull { it.bottom } ?: 0f) + dy
        val finalW = maxOf(baseChartW, layout.width + dx, maxRight + outerPad)
        val finalH = maxOf(baseChartH, layout.height + dy, maxBottom + outerPad)

        return layout.copy(
            width = finalW,
            height = finalH,
            titleRect = shiftedTitle,
            plotRect = shiftedPlot,
            xAxisLineStart = layout.xAxisLineStart.shift(),
            xAxisLineEnd = layout.xAxisLineEnd.shift(),
            yAxisLineStart = layout.yAxisLineStart.shift(),
            yAxisLineEnd = layout.yAxisLineEnd.shift(),
            xTicks = shiftedXTicks,
            yTicks = shiftedYTicks,
            axisTitles = shiftedAxisTitles,
            bars = shiftedBars,
            lines = shiftedLines,
            legendItems = shiftedLegend,
        )
    }

    internal fun effectiveXDomain(xa: XyAxisSpec.LinearAxis): Pair<Double, Double> =
        if (xa.max == xa.min) {
            xa.min to (xa.min + 1.0)
        } else {
            xa.min to xa.max
        }

    internal fun effectiveYDomain(ya: XyAxisSpec.LinearAxis): Pair<Double, Double> =
        if (ya.max == ya.min) {
            when {
                ya.min > 0.0 -> 0.0 to ya.max
                ya.min < 0.0 -> ya.min to 0.0
                else -> 0.0 to 10.0
            }
        } else {
            ya.min to ya.max
        }

    internal fun generateLinearTicks(minVal: Double, maxVal: Double, targetCount: Int = 6): List<Double> {
        val lo = min(minVal, maxVal)
        val hi = max(minVal, maxVal)
        val span = hi - lo
        if (!span.isFinite() || span <= 0.0) return listOf(minVal)

        val rawStep = span / max(2, targetCount - 1).toDouble()
        val exp = floor(log10(rawStep)).toInt()
        val mag = 10.0.pow(exp)
        val norm = rawStep / mag
        val niceMult = when {
            norm <= 1.5 -> 1.0
            norm <= 3.0 -> 2.0
            norm <= 7.0 -> 5.0
            else -> 10.0
        }
        val step = niceMult * mag
        val eps = step * 1e-9
        val firstIdx = ceil((lo - eps) / step).toLong()
        val lastIdx = floor((hi + eps) / step).toLong()
        val ticks = mutableListOf<Double>()
        if (lastIdx >= firstIdx && (lastIdx - firstIdx) <= 20) {
            val invMag = if (exp in -12..-1) 10.0.pow(-exp) else null
            val multLong = niceMult.roundToLong()
            for (k in firstIdx..lastIdx) {
                val v = if (invMag != null) {
                    (k * multLong).toDouble() / invMag
                } else {
                    k * step
                }
                val cleaned = if (abs(v) < step * 1e-9) 0.0 else v
                ticks += cleaned
            }
        }
        if (ticks.isEmpty()) {
            ticks += lo
            ticks += hi
        }
        return if (minVal <= maxVal) ticks else ticks.asReversed()
    }

    internal fun formatNumber(value: Double): String {
        if (!value.isFinite() || value == 0.0) return "0"
        val absVal = abs(value)
        if (absVal >= 1e12 || absVal < 1e-4) {
            return value.toString()
        }
        val nearestLong = kotlin.math.round(value)
        if (abs(value - nearestLong) <= 1e-12 * max(1.0, absVal)) {
            return nearestLong.toLong().toString()
        }
        val decimals = (14 - 1 - floor(log10(absVal)).toInt()).coerceIn(1, 12)
        val factor = 10.0.pow(decimals)
        val scaled = value * factor
        val str = if (scaled.isFinite() && abs(scaled) < 9e15) {
            (kotlin.math.round(scaled) / factor).toString()
        } else {
            value.toString()
        }
        return if (str.contains('.') && !str.contains('E') && !str.contains('e')) {
            str.trimEnd('0').trimEnd('.')
        } else {
            str
        }
    }

    internal fun defaultTickLabelSize(text: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(14f * density, (text.length * 7.2f + 4f) * density),
            height = 16f * density,
        )

    internal fun defaultAxisTitleSize(text: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(24f * density, (text.length * 7.8f + 8f) * density),
            height = 18f * density,
        )

    internal fun defaultPointLabelSize(text: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(14f * density, (text.length * 6.8f + 4f) * density),
            height = 15f * density,
        )
}
