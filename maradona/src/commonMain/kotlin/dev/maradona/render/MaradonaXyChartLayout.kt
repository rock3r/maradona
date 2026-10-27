package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.PositionedXyBar
import dev.maradona.layout.PositionedXyLinePoint
import dev.maradona.layout.XyChartDiagramLayout
import dev.maradona.layout.XyChartLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.XyAxisSpec
import dev.maradona.model.XyChartDiagram
import dev.maradona.model.XyPlotSeries
import dev.maradona.model.XyPlotType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class XyChartLayoutHolder {
    var layout: XyChartDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var tickSizes: Map<String, DiagramSize> = emptyMap()
    var axisTitleSizes: Map<String, DiagramSize> = emptyMap()
    var legendSizes: Map<String, DiagramSize> = emptyMap()
    var pointSizes: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun PositionedXyBar.barAndLabelBounds(): DiagramRect {
    val dlr = dataLabelRect ?: return rect
    val minX = min(rect.x, dlr.x)
    val minY = min(rect.y, dlr.y)
    val maxX = max(rect.right, dlr.right)
    val maxY = max(rect.bottom, dlr.bottom)
    return DiagramRect(minX, minY, maxX - minX, maxY - minY)
}

private fun PositionedXyLinePoint.pointAndLabelBounds(halfExtent: Float): DiagramRect {
    var minX = position.x - halfExtent
    var minY = position.y - halfExtent
    var maxX = position.x + halfExtent
    var maxY = position.y + halfExtent
    labelRect?.let { lr ->
        minX = min(minX, lr.x)
        minY = min(minY, lr.y)
        maxX = max(maxX, lr.right)
        maxY = max(maxY, lr.bottom)
    }
    return DiagramRect(minX, minY, maxX - minX, maxY - minY)
}

private fun resolveSeriesColor(
    seriesIndex: Int,
    explicitColor: String?,
    paletteStrings: List<String>,
    theme: MaradonaTheme,
): Color {
    parseCssColor(explicitColor)?.let { return it }
    if (paletteStrings.isNotEmpty()) {
        val raw = paletteStrings[seriesIndex % paletteStrings.size]
        parseCssColor(raw)?.let { return it }
    }
    return theme.seriesColor(seriesIndex)
}

/**
 * Custom Compose [Layout] for Mermaid `xychart` / `xychart-beta` diagrams.
 *
 * Tick labels, axis titles, chart title, data/point labels, and legend labels are real
 * [BasicText] composables; the plot background, grid lines, bar rectangles, line polylines,
 * axis lines, tick marks, and legend swatches are drawn on the underlay canvas.
 */
@Composable
fun MaradonaXyChartLayout(
    diagram: XyChartDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (XyChartDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { XyChartLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()
    val densityObj = LocalDensity.current
    val renderDensity = densityObj.density
    val renderFontScale = densityObj.fontScale
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontResolutionToken = listOf(
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontStyle = FontStyle.Italic).value,
    )
    val monoFontResolutionToken = fontFamilyResolver.resolve(fontFamily = theme.monospaceFontFamily).value

    val xAxisPreviewTicks = remember(diagram) {
        when (val xa = diagram.xAxis) {
            is XyAxisSpec.BandAxis -> xa.categories
            is XyAxisSpec.LinearAxis -> {
                val (xm0, xm1) = XyChartLayout.effectiveXDomain(xa)
                XyChartLayout.generateLinearTicks(xm0, xm1, 6).map { XyChartLayout.formatNumber(it) }
            }
        }
    }

    val yAxisPreviewTicks = remember(diagram) {
        val (ym0, ym1) = XyChartLayout.effectiveYDomain(diagram.yAxis)
        XyChartLayout.generateLinearTicks(ym0, ym1, 6).map { XyChartLayout.formatNumber(it) }
    }

    val legendSeries = remember(diagram) {
        if (diagram.showLegend) {
            diagram.plots.mapIndexedNotNull { idx, s ->
                if (!s.title.isNullOrBlank()) idx to s else null
            }
        } else {
            emptyList()
        }
    }

    val pointAndDataLabels = remember(diagram) {
        val list = mutableListOf<Triple<String, Int, String>>() // key, seriesIdx, text
        for ((sIdx, s) in diagram.plots.withIndex()) {
            for ((pIdx, pt) in s.points.withIndex()) {
                when (s.type) {
                    XyPlotType.Bar -> {
                        val txt = pt.label ?: if (diagram.showDataLabel) XyChartLayout.formatNumber(pt.value) else null
                        if (!txt.isNullOrBlank()) {
                            list += Triple("dlbl:bar_${sIdx}_$pIdx", sIdx, txt)
                        }
                    }
                    XyPlotType.Line -> {
                        val txt = pt.label
                        if (!txt.isNullOrBlank()) {
                            list += Triple("plbl:linept_${sIdx}_$pIdx", sIdx, txt)
                        }
                    }
                }
            }
        }
        list
    }

    val precomputedLayout = run {
        val cached = holder.layout
        if (
            cached != null &&
            abs(holder.lastDensity - renderDensity) < 1e-4f &&
            abs(holder.lastFontScale - renderFontScale) < 1e-4f &&
            holder.lastFontResolutionToken == fontResolutionToken &&
            holder.lastMonospaceFontResolutionToken == monoFontResolutionToken
        ) {
            cached
        } else {
            computePremeasuredXyChartLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                xAxisPreviewTicks = xAxisPreviewTicks,
                yAxisPreviewTicks = yAxisPreviewTicks,
                legendSeries = legendSeries,
                pointAndDataLabels = pointAndDataLabels,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.tickSizes = pre.tickSizes
                holder.axisTitleSizes = pre.axisTitleSizes
                holder.legendSizes = pre.legendSizes
                holder.pointSizes = pre.pointSizes
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastFontResolutionToken = fontResolutionToken
                holder.lastMonospaceFontResolutionToken = monoFontResolutionToken
            }.layout
        }
    }
    val effectiveViewport = visibleViewport
        ?: viewportResolver?.invoke(precomputedLayout.width, precomputedLayout.height)
    val activeCullViewport = effectiveViewport
    holder.visibleViewport = activeCullViewport

    val positionedBarById = remember(precomputedLayout) {
        precomputedLayout.bars.associateBy { it.id }
    }
    val positionedLinePointById = remember(precomputedLayout) {
        precomputedLayout.lines.flatMap { it.points }.associateBy { it.id }
    }

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                val accParts = listOfNotNull(
                    diagram.accTitle?.takeIf { it.isNotBlank() },
                    diagram.accDescr?.takeIf { it.isNotBlank() },
                )
                contentDescription = if (accParts.isNotEmpty()) {
                    accParts.joinToString(". ")
                } else {
                    "XY chart. ${diagram.plots.size} series."
                }
                val vp = holder.visibleViewport
                if (vp != null && onNodeClick != null) {
                    val offscreenEvents = buildList {
                        for ((sIdx, s) in diagram.plots.withIndex()) {
                            val seriesName = s.title ?: "${s.type.name} ${sIdx + 1}"
                            for ((pIdx, pt) in s.points.withIndex()) {
                                val isVisible = if (s.type == XyPlotType.Bar) {
                                    val bar = positionedBarById["bar_${sIdx}_$pIdx"]
                                    bar == null || rectIntersectsViewport(bar.barAndLabelBounds(), vp, 8f * renderDensity)
                                } else {
                                    val linePt = positionedLinePointById["linept_${sIdx}_$pIdx"]
                                    linePt == null || rectIntersectsViewport(
                                        linePt.pointAndLabelBounds(16f * renderDensity),
                                        vp,
                                        16f * renderDensity,
                                    )
                                }
                                if (!isVisible) {
                                    add(MaradonaNodeClickEvent(nodeId = "${s.id}_$pIdx", label = "$seriesName: ${pt.value}"))
                                }
                            }
                        }
                    }
                    val actions = buildOffscreenCustomActions(offscreenEvents, onNodeClick, uriHandler)
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawXyChartUnderlay(it, diagram, theme, holder.visibleViewport) }
            },
        content = {
            if (diagram.showTitle && !diagram.title.isNullOrBlank()) {
                val tr = precomputedLayout.titleRect
                if (activeCullViewport == null || tr == null || rectIntersectsViewport(tr, activeCullViewport, 8f * renderDensity)) {
                    BasicText(
                        text = annotatedLabel(diagram.title),
                        modifier = Modifier.layoutId("title"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.titleFontSize,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
            for ((sIdx, series) in legendSeries) {
                BasicText(
                    text = annotatedLabel(series.title!!),
                    modifier = Modifier.layoutId("legend:$sIdx"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.edgeLabelFontSize,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            for ((key, sIdx, txt) in pointAndDataLabels) {
                if (activeCullViewport != null) {
                    if (key.startsWith("dlbl:")) {
                        val bar = positionedBarById[key.removePrefix("dlbl:")]
                        if (bar != null && !rectIntersectsViewport(bar.barAndLabelBounds(), activeCullViewport, 8f * renderDensity)) {
                            continue
                        }
                    } else if (key.startsWith("plbl:")) {
                        val linePt = positionedLinePointById[key.removePrefix("plbl:")]
                        if (linePt != null && !rectIntersectsViewport(
                                linePt.pointAndLabelBounds(16f * renderDensity),
                                activeCullViewport,
                                16f * renderDensity,
                            )
                        ) {
                            continue
                        }
                    }
                }
                val series = diagram.plots[sIdx]
                val seriesCol = resolveSeriesColor(sIdx, series.color, diagram.plotColorPalette, theme)
                val labelColor = if (key.startsWith("plbl:")) {
                    seriesCol
                } else if (diagram.showDataLabelOutsideBar || series.points.any { it.label == txt }) {
                    theme.nodeText
                } else {
                    contrastingTextColor(seriesCol)
                }
                BasicText(
                    text = annotatedLabel(txt),
                    modifier = Modifier.layoutId(key),
                    style = TextStyle(
                        color = labelColor,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.captionFontSize,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for ((i, lbl) in xAxisPreviewTicks.withIndex()) {
                if (activeCullViewport != null) {
                    val xt = precomputedLayout.xTicks.getOrNull(i)
                    if (xt != null && !rectIntersectsViewport(xt.labelRect, activeCullViewport, 8f * renderDensity)) continue
                }
                BasicText(
                    text = annotatedLabel(lbl),
                    modifier = Modifier.layoutId("xtick:$i"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.captionFontSize,
                        fontWeight = FontWeight.Normal,
                    ),
                )
            }
            for ((i, lbl) in yAxisPreviewTicks.withIndex()) {
                if (activeCullViewport != null) {
                    val yt = precomputedLayout.yTicks.getOrNull(i)
                    if (yt != null && !rectIntersectsViewport(yt.labelRect, activeCullViewport, 8f * renderDensity)) continue
                }
                BasicText(
                    text = annotatedLabel(lbl),
                    modifier = Modifier.layoutId("ytick:$i"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.captionFontSize,
                        fontWeight = FontWeight.Normal,
                    ),
                )
            }
            diagram.xAxis.title?.takeIf { it.isNotBlank() }?.let { txt ->
                BasicText(
                    text = annotatedLabel(txt),
                    modifier = Modifier.layoutId("xaxtitle"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(12.5f),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            diagram.yAxis.title?.takeIf { it.isNotBlank() }?.let { txt ->
                BasicText(
                    text = annotatedLabel(txt),
                    modifier = Modifier.layoutId("yaxtitle"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(12.5f),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for ((sIdx, s) in diagram.plots.withIndex()) {
                for ((pIdx, pt) in s.points.withIndex()) {
                    if (activeCullViewport != null) {
                        if (s.type == XyPlotType.Bar) {
                            val bar = positionedBarById["bar_${sIdx}_$pIdx"]
                            if (bar != null && !rectIntersectsViewport(bar.barAndLabelBounds(), activeCullViewport, 8f * renderDensity)) {
                                continue
                            }
                        } else {
                            val linePt = positionedLinePointById["linept_${sIdx}_$pIdx"]
                            if (linePt != null && !rectIntersectsViewport(
                                    linePt.pointAndLabelBounds(16f * renderDensity),
                                    activeCullViewport,
                                    16f * renderDensity,
                                )
                            ) {
                                continue
                            }
                        }
                    }
                    val itemId = if (s.type == XyPlotType.Bar) "barbox:bar_${sIdx}_$pIdx" else "ptbox:linept_${sIdx}_$pIdx"
                    val seriesName = s.title ?: "${s.type.name} ${sIdx + 1}"
                    val baseDesc = "$seriesName point ${pIdx + 1}: ${pt.value}${pt.label?.let { " ($it)" } ?: ""}"
                    val nodeId = "${s.id}_$pIdx"
                    val clickEvent = if (onNodeClick != null) {
                        MaradonaNodeClickEvent(nodeId = nodeId, label = "$seriesName: ${pt.value}")
                    } else {
                        null
                    }
                    Box(
                        modifier = Modifier
                            .layoutId(itemId)
                            .diagramNodeInteraction(
                                baseDescription = baseDesc,
                                traversalOrder = sIdx * 100 + pIdx,
                                event = clickEvent,
                                onNodeClick = onNodeClick,
                                uriHandler = uriHandler,
                            ),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val tickMeasSizes = HashMap<String, DiagramSize>(holder.tickSizes)
        val axTitleMeasSizes = HashMap<String, DiagramSize>(holder.axisTitleSizes)
        val legendPlaceables = HashMap<Int, Placeable>()
        val legendSizesByTitle = HashMap<String, DiagramSize>(holder.legendSizes)
        val dataAndPointPlaceables = HashMap<String, Placeable>()
        val pointSizesByText = HashMap<String, DiagramSize>(holder.pointSizes)
        val xTickPlaceables = HashMap<Int, Placeable>()
        val yTickPlaceables = HashMap<Int, Placeable>()
        var xAxTitlePlaceable: Placeable? = null
        var yAxTitlePlaceable: Placeable? = null
        val barBoxMeasurables = HashMap<String, Measurable>()
        val ptBoxMeasurables = HashMap<String, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("legend:") -> {
                    val sIdx = id.removePrefix("legend:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    legendPlaceables[sIdx] = pl
                    val t = diagram.plots.getOrNull(sIdx)?.title
                    if (t != null) {
                        legendSizesByTitle[t] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
                id.startsWith("dlbl:") || id.startsWith("plbl:") -> {
                    val pl = m.measure(unbounded)
                    dataAndPointPlaceables[id] = pl
                    val txt = pointAndDataLabels.firstOrNull { it.first == id }?.third
                    if (txt != null) {
                        pointSizesByText[txt] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
                id.startsWith("xtick:") -> {
                    val idx = id.removePrefix("xtick:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    xTickPlaceables[idx] = pl
                    val lbl = xAxisPreviewTicks.getOrNull(idx)
                    if (lbl != null) {
                        tickMeasSizes[lbl] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
                id.startsWith("ytick:") -> {
                    val idx = id.removePrefix("ytick:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    yTickPlaceables[idx] = pl
                    val lbl = yAxisPreviewTicks.getOrNull(idx)
                    if (lbl != null) {
                        tickMeasSizes[lbl] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
                id == "xaxtitle" -> {
                    val pl = m.measure(unbounded)
                    xAxTitlePlaceable = pl
                    diagram.xAxis.title?.takeIf { it.isNotBlank() }?.let {
                        axTitleMeasSizes[it] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
                id == "yaxtitle" -> {
                    val pl = m.measure(unbounded)
                    yAxTitlePlaceable = pl
                    diagram.yAxis.title?.takeIf { it.isNotBlank() }?.let {
                        axTitleMeasSizes[it] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
                id.startsWith("barbox:") -> barBoxMeasurables[id.removePrefix("barbox:")] = m
                id.startsWith("ptbox:") -> ptBoxMeasurables[id.removePrefix("ptbox:")] = m
            }
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val resolvedTitleSize = if (diagram.showTitle && !diagram.title.isNullOrBlank()) {
            titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) } ?: holder.titleSize
        } else {
            null
        }

        val layout = XyChartLayout.layout(
            diagram = diagram,
            tickLabelSize = { s ->
                tickMeasSizes[s] ?: XyChartLayout.defaultTickLabelSize(s, renderDensity)
            },
            axisTitleSize = { s ->
                axTitleMeasSizes[s] ?: XyChartLayout.defaultAxisTitleSize(s, renderDensity)
            },
            legendLabelSize = { s ->
                legendSizesByTitle[s] ?: XyChartLayout.defaultTickLabelSize(s, renderDensity)
            },
            pointLabelSize = { s ->
                pointSizesByText[s] ?: XyChartLayout.defaultPointLabelSize(s, renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.tickSizes = tickMeasSizes
            holder.axisTitleSizes = axTitleMeasSizes
            holder.legendSizes = legendSizesByTitle
            holder.pointSizes = pointSizesByText
        }
        holder.layout = layout
        onLayout(layout)

        val barBoxPlaceables = HashMap<String, Placeable>()
        for (bar in layout.bars) {
            val m = barBoxMeasurables[bar.id] ?: continue
            barBoxPlaceables[bar.id] = m.measure(
                Constraints.fixed(
                    bar.rect.width.roundToInt().coerceAtLeast(1),
                    bar.rect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }
        val ptBoxPlaceables = HashMap<String, Placeable>()
        val ptBoxSizePx = (8f * renderDensity).roundToInt().coerceAtLeast(1)
        for (lineSeries in layout.lines) {
            for (pt in lineSeries.points) {
                val m = ptBoxMeasurables[pt.id] ?: continue
                ptBoxPlaceables[pt.id] = m.measure(Constraints.fixed(ptBoxSizePx, ptBoxSizePx))
            }
        }

        val totalNodes = layout.bars.size + layout.lines.sumOf { it.points.size }
        val totalEdges = layout.bars.size + layout.lines.sumOf { (it.points.size - 1).coerceAtLeast(0) }
        val drawnEdges = if (activeCullViewport == null) {
            totalEdges
        } else {
            val drawnBars = layout.bars.count { rectIntersectsViewport(it.rect, activeCullViewport, 8f * renderDensity) }
            val drawnSegments = layout.lines.sumOf { ls ->
                var cnt = 0
                for (i in 1 until ls.points.size) {
                    val p0 = ls.points[i - 1].position
                    val p1 = ls.points[i].position
                    if (segmentIntersectsViewport(p0.x, p0.y, p1.x, p1.y, activeCullViewport, 12f * renderDensity)) {
                        cnt++
                    }
                }
                cnt
            }
            drawnBars + drawnSegments
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = barBoxPlaceables.size + ptBoxPlaceables.size,
                totalNodes = totalNodes,
                composedEdgeLabels = 0,
                totalEdgeLabels = 0,
                drawnEdges = drawnEdges,
                totalEdges = totalEdges,
                drawnSubgraphs = 0,
                totalSubgraphs = 0,
            ),
        )

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for ((i, xt) in layout.xTicks.withIndex()) {
                val pl = xTickPlaceables[i] ?: continue
                pl.place(
                    (xt.labelRect.centerX - pl.width / 2f).roundToInt(),
                    (xt.labelRect.centerY - pl.height / 2f).roundToInt(),
                )
            }
            for ((i, yt) in layout.yTicks.withIndex()) {
                val pl = yTickPlaceables[i] ?: continue
                pl.place(
                    (yt.labelRect.centerX - pl.width / 2f).roundToInt(),
                    (yt.labelRect.centerY - pl.height / 2f).roundToInt(),
                )
            }
            val isHoriz = diagram.orientation == dev.maradona.model.XyChartOrientation.Horizontal
            for (at in layout.axisTitles) {
                val pl = when (at.id) {
                    "axtitle_left" -> if (isHoriz) xAxTitlePlaceable else yAxTitlePlaceable
                    else -> if (isHoriz) yAxTitlePlaceable else xAxTitlePlaceable
                } ?: continue
                val px = (at.rect.centerX - pl.width / 2f).roundToInt()
                val py = (at.rect.centerY - pl.height / 2f).roundToInt()
                if (at.rotationDeg != 0f) {
                    pl.placeWithLayer(px, py) {
                        rotationZ = at.rotationDeg
                    }
                } else {
                    pl.place(px, py)
                }
            }
            for (bar in layout.bars) {
                barBoxPlaceables[bar.id]?.place(bar.rect.x.roundToInt(), bar.rect.y.roundToInt())
                val dlRect = bar.dataLabelRect
                val dlPl = dataAndPointPlaceables["dlbl:${bar.id}"]
                if (dlRect != null && dlPl != null) {
                    dlPl.place(
                        (dlRect.centerX - dlPl.width / 2f).roundToInt(),
                        (dlRect.centerY - dlPl.height / 2f).roundToInt(),
                    )
                }
            }
            for (ls in layout.lines) {
                for (pt in ls.points) {
                    ptBoxPlaceables[pt.id]?.place(
                        (pt.position.x - ptBoxSizePx / 2f).roundToInt(),
                        (pt.position.y - ptBoxSizePx / 2f).roundToInt(),
                    )
                    val lRect = pt.labelRect
                    val lPl = dataAndPointPlaceables["plbl:${pt.id}"]
                    if (lRect != null && lPl != null) {
                        lPl.place(
                            (lRect.centerX - lPl.width / 2f).roundToInt(),
                            (lRect.centerY - lPl.height / 2f).roundToInt(),
                        )
                    }
                }
            }
            for (li in layout.legendItems) {
                val pl = legendPlaceables[li.seriesIndex] ?: continue
                pl.place(
                    li.labelRect.x.roundToInt(),
                    (li.labelRect.centerY - pl.height / 2f).roundToInt(),
                )
            }
        }
    }
}

private data class PremeasuredXyChartResult(
    val layout: XyChartDiagramLayout,
    val titleSize: DiagramSize?,
    val tickSizes: Map<String, DiagramSize>,
    val axisTitleSizes: Map<String, DiagramSize>,
    val legendSizes: Map<String, DiagramSize>,
    val pointSizes: Map<String, DiagramSize>,
)

private fun computePremeasuredXyChartLayout(
    diagram: XyChartDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    xAxisPreviewTicks: List<String>,
    yAxisPreviewTicks: List<String>,
    legendSeries: List<Pair<Int, XyPlotSeries>>,
    pointAndDataLabels: List<Triple<String, Int, String>>,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredXyChartResult {
    val renderDensity = densityObj.density
    val titleSize = if (diagram.showTitle && !diagram.title.isNullOrBlank()) {
        measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(diagram.title),
            style = TextStyle(
                color = theme.nodeText,
                fontFamily = theme.fontFamily,
                fontSize = theme.titleFontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
        )
    } else {
        null
    }
    val legendStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        fontWeight = FontWeight.Medium,
    )
    val legendSizes = HashMap<String, DiagramSize>()
    for ((_, series) in legendSeries) {
        val t = series.title ?: continue
        legendSizes[t] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(t),
            style = legendStyle,
        )
    }
    val pointStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.captionFontSize,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val pointSizes = HashMap<String, DiagramSize>()
    for ((_, _, txt) in pointAndDataLabels) {
        if (txt !in pointSizes) {
            pointSizes[txt] = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(txt),
                style = pointStyle,
            )
        }
    }
    val tickStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.captionFontSize,
        fontWeight = FontWeight.Normal,
    )
    val tickSizes = HashMap<String, DiagramSize>()
    for (lbl in xAxisPreviewTicks) {
        if (lbl !in tickSizes) {
            tickSizes[lbl] = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(lbl),
                style = tickStyle,
            )
        }
    }
    for (lbl in yAxisPreviewTicks) {
        if (lbl !in tickSizes) {
            tickSizes[lbl] = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(lbl),
                style = tickStyle,
            )
        }
    }
    val axTitleStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(12.5f),
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val axisTitleSizes = HashMap<String, DiagramSize>()
    diagram.xAxis.title?.takeIf { it.isNotBlank() }?.let { txt ->
        axisTitleSizes[txt] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(txt),
            style = axTitleStyle,
        )
    }
    diagram.yAxis.title?.takeIf { it.isNotBlank() }?.let { txt ->
        axisTitleSizes[txt] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(txt),
            style = axTitleStyle,
        )
    }
    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            density = renderDensity,
        )
    }
    val layout = XyChartLayout.layout(
        diagram = diagram,
        tickLabelSize = { s -> tickSizes[s] ?: XyChartLayout.defaultTickLabelSize(s, renderDensity) },
        axisTitleSize = { s -> axisTitleSizes[s] ?: XyChartLayout.defaultAxisTitleSize(s, renderDensity) },
        legendLabelSize = { s -> legendSizes[s] ?: XyChartLayout.defaultTickLabelSize(s, renderDensity) },
        pointLabelSize = { s -> pointSizes[s] ?: XyChartLayout.defaultPointLabelSize(s, renderDensity) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredXyChartResult(
        layout = layout,
        titleSize = titleSize,
        tickSizes = tickSizes,
        axisTitleSizes = axisTitleSizes,
        legendSizes = legendSizes,
        pointSizes = pointSizes,
    )
}

private fun DrawScope.drawXyChartUnderlay(
    layout: XyChartDiagramLayout,
    diagram: XyChartDiagram,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val pr = layout.plotRect

    // 1. Subtle plot background and Y-tick gridlines.
    if (rectIntersectsViewport(pr, viewport, 8f * unit)) {
        drawRect(
            color = theme.subgraphFill.copy(alpha = 0.35f),
            topLeft = Offset(pr.x, pr.y),
            size = Size(pr.width, pr.height),
        )
    }
    for (yt in layout.yTicks) {
        if (!segmentIntersectsViewport(yt.gridStart.x, yt.gridStart.y, yt.gridEnd.x, yt.gridEnd.y, viewport, 8f * unit)) {
            continue
        }
        drawLine(
            color = theme.subgraphBorder.copy(alpha = 0.5f),
            start = Offset(yt.gridStart.x, yt.gridStart.y),
            end = Offset(yt.gridEnd.x, yt.gridEnd.y),
            strokeWidth = 1f * unit,
        )
    }

    // 2. Bar plots.
    for (bar in layout.bars) {
        if (!rectIntersectsViewport(bar.rect, viewport, 8f * unit)) continue
        val series = diagram.plots.getOrNull(bar.seriesIndex)
        val fill = resolveSeriesColor(bar.seriesIndex, series?.color, diagram.plotColorPalette, theme)
        drawRect(
            color = fill,
            topLeft = Offset(bar.rect.x, bar.rect.y),
            size = Size(bar.rect.width, bar.rect.height),
        )
    }

    // 3. Line plots.
    for (ls in layout.lines) {
        val strokeColor = resolveSeriesColor(ls.seriesIndex, ls.series.color, diagram.plotColorPalette, theme)
        if (ls.points.size >= 2) {
            if (viewport == null) {
                val path = Path().apply {
                    val first = ls.points.first().position
                    moveTo(first.x, first.y)
                    for (i in 1 until ls.points.size) {
                        val p = ls.points[i].position
                        lineTo(p.x, p.y)
                    }
                }
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(
                        width = 2.4f * unit,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            } else {
                var anySegment = false
                val path = Path()
                var penAtIndex = -1
                for (i in 1 until ls.points.size) {
                    val p0 = ls.points[i - 1].position
                    val p1 = ls.points[i].position
                    if (segmentIntersectsViewport(p0.x, p0.y, p1.x, p1.y, viewport, 12f * unit)) {
                        if (penAtIndex != i - 1) {
                            path.moveTo(p0.x, p0.y)
                        }
                        path.lineTo(p1.x, p1.y)
                        penAtIndex = i
                        anySegment = true
                    }
                }
                if (anySegment) {
                    drawPath(
                        path = path,
                        color = strokeColor,
                        style = Stroke(
                            width = 2.4f * unit,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
        for (pt in ls.points) {
            if (!rectIntersectsViewport(pt.pointAndLabelBounds(16f * unit), viewport, 16f * unit)) continue
            val center = Offset(pt.position.x, pt.position.y)
            drawCircle(
                color = theme.background,
                radius = 4.6f * unit,
                center = center,
            )
            drawCircle(
                color = strokeColor,
                radius = 3.2f * unit,
                center = center,
            )
        }
    }

    // 4. Axis lines and tick marks.
    val axisStroke = 1.6f * unit
    if (segmentIntersectsViewport(layout.xAxisLineStart.x, layout.xAxisLineStart.y, layout.xAxisLineEnd.x, layout.xAxisLineEnd.y, viewport, 8f * unit)) {
        drawLine(
            color = theme.nodeBorder,
            start = Offset(layout.xAxisLineStart.x, layout.xAxisLineStart.y),
            end = Offset(layout.xAxisLineEnd.x, layout.xAxisLineEnd.y),
            strokeWidth = axisStroke,
        )
    }
    if (segmentIntersectsViewport(layout.yAxisLineStart.x, layout.yAxisLineStart.y, layout.yAxisLineEnd.x, layout.yAxisLineEnd.y, viewport, 8f * unit)) {
        drawLine(
            color = theme.nodeBorder,
            start = Offset(layout.yAxisLineStart.x, layout.yAxisLineStart.y),
            end = Offset(layout.yAxisLineEnd.x, layout.yAxisLineEnd.y),
            strokeWidth = axisStroke,
        )
    }
    for (xt in layout.xTicks) {
        if (!segmentIntersectsViewport(xt.lineStart.x, xt.lineStart.y, xt.lineEnd.x, xt.lineEnd.y, viewport, 8f * unit)) continue
        drawLine(
            color = theme.nodeBorder,
            start = Offset(xt.lineStart.x, xt.lineStart.y),
            end = Offset(xt.lineEnd.x, xt.lineEnd.y),
            strokeWidth = 1.2f * unit,
        )
    }
    for (yt in layout.yTicks) {
        if (!segmentIntersectsViewport(yt.lineStart.x, yt.lineStart.y, yt.lineEnd.x, yt.lineEnd.y, viewport, 8f * unit)) continue
        drawLine(
            color = theme.nodeBorder,
            start = Offset(yt.lineStart.x, yt.lineStart.y),
            end = Offset(yt.lineEnd.x, yt.lineEnd.y),
            strokeWidth = 1.2f * unit,
        )
    }

    // 5. Legend markers.
    for (li in layout.legendItems) {
        val mr = li.markerRect
        if (!rectIntersectsViewport(mr, viewport, 8f * unit)) continue
        val col = resolveSeriesColor(li.seriesIndex, li.series.color, diagram.plotColorPalette, theme)
        if (li.series.type == XyPlotType.Bar) {
            drawRect(
                color = col,
                topLeft = Offset(mr.x, mr.y),
                size = Size(mr.width, mr.height),
            )
        } else {
            val midY = mr.centerY
            drawLine(
                color = col,
                start = Offset(mr.x, midY),
                end = Offset(mr.right, midY),
                strokeWidth = 2.4f * unit,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = col,
                radius = 2.6f * unit,
                center = Offset(mr.centerX, midY),
            )
        }
    }
}

internal fun measureXyChartLayoutForExport(
    diagram: XyChartDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): XyChartDiagramLayout {
    val xAxisPreviewTicks = when (val xa = diagram.xAxis) {
        is XyAxisSpec.BandAxis -> xa.categories
        is XyAxisSpec.LinearAxis -> {
            val (xm0, xm1) = XyChartLayout.effectiveXDomain(xa)
            XyChartLayout.generateLinearTicks(xm0, xm1, 6).map { XyChartLayout.formatNumber(it) }
        }
    }
    val (ym0, ym1) = XyChartLayout.effectiveYDomain(diagram.yAxis)
    val yAxisPreviewTicks = XyChartLayout.generateLinearTicks(ym0, ym1, 6).map { XyChartLayout.formatNumber(it) }
    val legendSeries = if (diagram.showLegend) {
        diagram.plots.mapIndexedNotNull { idx, s ->
            if (!s.title.isNullOrBlank()) idx to s else null
        }
    } else {
        emptyList()
    }
    val pointAndDataLabels = buildList {
        for ((sIdx, s) in diagram.plots.withIndex()) {
            for ((pIdx, pt) in s.points.withIndex()) {
                when (s.type) {
                    XyPlotType.Bar -> {
                        val txt = pt.label ?: if (diagram.showDataLabel) XyChartLayout.formatNumber(pt.value) else null
                        if (!txt.isNullOrBlank()) add(Triple("dlbl:bar_${sIdx}_$pIdx", sIdx, txt))
                    }
                    XyPlotType.Line -> {
                        val txt = pt.label
                        if (!txt.isNullOrBlank()) add(Triple("plbl:linept_${sIdx}_$pIdx", sIdx, txt))
                    }
                }
            }
        }
    }
    return computePremeasuredXyChartLayout(
        diagram = diagram,
        theme = theme,
        layoutOptions = layoutOptions,
        xAxisPreviewTicks = xAxisPreviewTicks,
        yAxisPreviewTicks = yAxisPreviewTicks,
        legendSeries = legendSeries,
        pointAndDataLabels = pointAndDataLabels,
        densityObj = densityObj,
        textMeasurer = textMeasurer,
    ).layout
}

