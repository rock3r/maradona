package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import dev.maradona.layout.PieDiagramLayout
import dev.maradona.layout.PieLayout
import dev.maradona.layout.PositionedPieLegendItem
import dev.maradona.layout.PositionedPieSlice
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.PieDiagram
import dev.maradona.model.PieSlice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private class PieLayoutHolder {
    var layout: PieDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var pctSizes: Map<Int, DiagramSize> = emptyMap()
    var legendSizes: Map<Int, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun pieLegendItemBounds(item: PositionedPieLegendItem): DiagramRect {
    val minX = min(item.swatchRect.x, item.labelRect.x)
    val minY = min(item.swatchRect.y, item.labelRect.y)
    val maxX = max(item.swatchRect.right, item.labelRect.right)
    val maxY = max(item.swatchRect.bottom, item.labelRect.bottom)
    return DiagramRect(minX, minY, maxX - minX, maxY - minY)
}

private fun pieSliceWedgeBounds(
    layout: PieDiagramLayout,
    ps: PositionedPieSlice,
): DiagramRect {
    val cx = layout.center.x + ps.explodeOffset.x
    val cy = layout.center.y + ps.explodeOffset.y
    val r = layout.outerRadius + layout.outerStrokeWidth / 2f
    if (ps.sweepAngleDeg >= 359.9f) {
        return DiagramRect(cx - r, cy - r, r * 2f, r * 2f)
    }
    val startDeg = ps.startAngleDeg
    val endDeg = ps.startAngleDeg + ps.sweepAngleDeg
    val startRad = startDeg.toDouble() * PI / 180.0
    val endRad = endDeg.toDouble() * PI / 180.0
    val innerR = layout.innerRadius
    var minX = min(cx + (cos(startRad) * innerR).toFloat(), cx + (cos(endRad) * innerR).toFloat())
    var maxX = max(cx + (cos(startRad) * innerR).toFloat(), cx + (cos(endRad) * innerR).toFloat())
    var minY = min(cy + (sin(startRad) * innerR).toFloat(), cy + (sin(endRad) * innerR).toFloat())
    var maxY = max(cy + (sin(startRad) * innerR).toFloat(), cy + (sin(endRad) * innerR).toFloat())
    for (rad in doubleArrayOf(startRad, endRad)) {
        val px = cx + (cos(rad) * r).toFloat()
        val py = cy + (sin(rad) * r).toFloat()
        minX = min(minX, px)
        maxX = max(maxX, px)
        minY = min(minY, py)
        maxY = max(maxY, py)
    }
    for (cardinal in intArrayOf(-90, 0, 90, 180, 270, 360)) {
        val c = cardinal.toFloat()
        if (c in startDeg..endDeg) {
            val cRad = cardinal.toDouble() * PI / 180.0
            val px = cx + (cos(cRad) * r).toFloat()
            val py = cy + (sin(cRad) * r).toFloat()
            minX = min(minX, px)
            maxX = max(maxX, px)
            minY = min(minY, py)
            maxY = max(maxY, py)
        }
    }
    return DiagramRect(minX, minY, (maxX - minX).coerceAtLeast(1f), (maxY - minY).coerceAtLeast(1f))
}

/**
 * Custom Compose [Layout] for Mermaid `pie` diagrams.
 *
 * Title, slice percentage labels, and legend labels are real [BasicText] composables
 * with semantics; pie/donut wedges and legend swatches are drawn on the underlay canvas.
 */
@Composable
fun MaradonaPieLayout(
    diagram: PieDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (PieDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { PieLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()
    val densityObj = LocalDensity.current
    val renderDensity = densityObj.density
    val renderFontScale = densityObj.fontScale
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontResolutionToken = listOf(
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Medium).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.SemiBold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontStyle = FontStyle.Italic).value,
    )
    val monoFontResolutionToken = fontFamilyResolver.resolve(fontFamily = theme.monospaceFontFamily).value

    val validSlices = remember(diagram) { PieLayout.validSlices(diagram) }
    val fractions = remember(validSlices) { PieLayout.sliceFractions(validSlices) }

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
            computePremeasuredPieLayout(
                diagram = diagram,
                validSlices = validSlices,
                fractions = fractions,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.pctSizes = pre.pctSizes
                holder.legendSizes = pre.legendSizes
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastFontResolutionToken = fontResolutionToken
                holder.lastMonospaceFontResolutionToken = monoFontResolutionToken
            }.layout
        }
    }

    val activeCullViewport = visibleViewport
        ?: viewportResolver?.invoke(precomputedLayout.width, precomputedLayout.height)
    holder.visibleViewport = activeCullViewport
    val cullMargin = 8f * renderDensity

    val positionedSliceByIndex = remember(precomputedLayout) {
        precomputedLayout.slices.associateBy { it.index }
    }
    val positionedLegendByIndex = remember(precomputedLayout) {
        precomputedLayout.legendItems.associateBy { it.index }
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
                    "Pie chart. ${validSlices.size} slices."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for ((idx, s) in validSlices.withIndex()) {
                            val li = positionedLegendByIndex[idx] ?: continue
                            if (rectIntersectsViewport(pieLegendItemBounds(li), vp, cullMargin)) continue
                            if (onNodeClick != null) {
                                add(MaradonaNodeClickEvent(nodeId = s.label, label = s.label))
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
                holder.layout?.let { drawPieUnderlay(it, theme, holder.visibleViewport) }
            },
        content = {
            if (diagram.title != null) {
                val tr = precomputedLayout.titleRect
                if (activeCullViewport == null || tr == null || rectIntersectsViewport(tr, activeCullViewport, cullMargin)) {
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
            for ((idx, s) in validSlices.withIndex()) {
                val pct = fractions[idx] * 100.0
                val pctText = PieLayout.formatPercentage(pct)
                val ps = positionedSliceByIndex[idx]
                val emitPct = activeCullViewport == null ||
                    (ps?.pctLabelRect != null && rectIntersectsViewport(ps.pctLabelRect, activeCullViewport, cullMargin))
                if (emitPct) {
                    BasicText(
                        text = pctText,
                        modifier = Modifier.layoutId("pct:$idx"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                val li = positionedLegendByIndex[idx]
                val emitLegend = activeCullViewport == null ||
                    li == null ||
                    rectIntersectsViewport(pieLegendItemBounds(li), activeCullViewport, cullMargin)
                if (emitLegend) {
                    val legendText = PieLayout.legendDisplayText(s, diagram.showData)
                    BasicText(
                        text = annotatedLabel(legendText),
                        modifier = Modifier.layoutId("leg:$idx"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(13f),
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    val baseDesc = "${s.label}: $pctText (${PieLayout.formatSliceValue(s.value)})"
                    val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = s.label, label = s.label) else null
                    Box(
                        modifier = Modifier
                            .layoutId("legbox:$idx")
                            .diagramNodeInteraction(
                                baseDescription = baseDesc,
                                traversalOrder = idx,
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
        val pctPlaceables = HashMap<Int, Placeable>()
        val legPlaceables = HashMap<Int, Placeable>()
        val legBoxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("pct:") -> {
                    val idx = id.removePrefix("pct:").toIntOrNull() ?: continue
                    pctPlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("leg:") -> {
                    val idx = id.removePrefix("leg:").toIntOrNull() ?: continue
                    legPlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("legbox:") -> {
                    val idx = id.removePrefix("legbox:").toIntOrNull() ?: continue
                    legBoxMeasurables[idx] = m
                }
            }
        }

        val resolvedTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize
        val resolvedPctSizes = HashMap<Int, DiagramSize>(validSlices.size)
        val resolvedLegendSizes = HashMap<Int, DiagramSize>(validSlices.size)
        for (idx in validSlices.indices) {
            val pctPl = pctPlaceables[idx]
            if (pctPl != null) {
                resolvedPctSizes[idx] = DiagramSize(pctPl.width.toFloat(), pctPl.height.toFloat())
            } else {
                holder.pctSizes[idx]?.let { resolvedPctSizes[idx] = it }
            }
            val legPl = legPlaceables[idx]
            if (legPl != null) {
                resolvedLegendSizes[idx] = DiagramSize(legPl.width.toFloat(), legPl.height.toFloat())
            } else {
                holder.legendSizes[idx]?.let { resolvedLegendSizes[idx] = it }
            }
        }

        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.pctSizes = resolvedPctSizes
            holder.legendSizes = resolvedLegendSizes
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = PieLayout.layout(
            diagram = diagram,
            pctLabelSize = { idx, s ->
                resolvedPctSizes[idx] ?: PieLayout.defaultLabelSize(s, renderDensity)
            },
            legendLabelSize = { idx, s ->
                resolvedLegendSizes[idx] ?: PieLayout.defaultLabelSize(s, renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val legBoxPlaceables = HashMap<Int, Placeable>()
        for (item in layout.legendItems) {
            val m = legBoxMeasurables[item.index] ?: continue
            val w = (item.labelRect.right - item.swatchRect.x).roundToInt().coerceAtLeast(1)
            val h = maxOf(item.swatchRect.height, item.labelRect.height).roundToInt().coerceAtLeast(1)
            legBoxPlaceables[item.index] = m.measure(Constraints.fixed(w, h))
        }

        val drawnSlices = if (activeCullViewport == null) {
            layout.slices.size
        } else {
            layout.slices.count { ps ->
                rectIntersectsViewport(pieSliceWedgeBounds(layout, ps), activeCullViewport, cullMargin)
            }
        }
        val composedSliceCount = validSlices.indices.count { it in legBoxPlaceables || it in pctPlaceables }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = composedSliceCount,
                totalNodes = validSlices.size,
                composedEdgeLabels = pctPlaceables.size,
                totalEdgeLabels = layout.slices.count { it.pctLabelRect != null },
                drawnEdges = drawnSlices,
                totalEdges = layout.slices.size,
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
            for (ps in layout.slices) {
                val pr = ps.pctLabelRect ?: continue
                pctPlaceables[ps.index]?.place(pr.x.roundToInt(), pr.y.roundToInt())
            }
            for (item in layout.legendItems) {
                legPlaceables[item.index]?.place(item.labelRect.x.roundToInt(), item.labelRect.y.roundToInt())
                legBoxPlaceables[item.index]?.place(item.swatchRect.x.roundToInt(), item.swatchRect.y.roundToInt())
            }
        }
    }
}

private data class PremeasuredPieResult(
    val layout: PieDiagramLayout,
    val titleSize: DiagramSize?,
    val pctSizes: Map<Int, DiagramSize>,
    val legendSizes: Map<Int, DiagramSize>,
)

private fun computePremeasuredPieLayout(
    diagram: PieDiagram,
    validSlices: List<PieSlice>,
    fractions: List<Double>,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredPieResult {
    val renderDensity = densityObj.density
    val titleSize = diagram.title?.let { title ->
        measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(title),
            style = TextStyle(
                color = theme.nodeText,
                fontFamily = theme.fontFamily,
                fontSize = theme.titleFontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
        )
    }

    val pctStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    val legStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(13f),
        fontWeight = FontWeight.Medium,
    )
    val pctSizes = HashMap<Int, DiagramSize>(validSlices.size)
    val legendSizes = HashMap<Int, DiagramSize>(validSlices.size)
    for ((idx, s) in validSlices.withIndex()) {
        val pct = fractions[idx] * 100.0
        val pctText = PieLayout.formatPercentage(pct)
        pctSizes[idx] = measurePlainTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = pctText,
            style = pctStyle,
        )
        val legendText = PieLayout.legendDisplayText(s, diagram.showData)
        legendSizes[idx] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(legendText),
            style = legStyle,
        )
    }

    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            density = renderDensity,
        )
    }
    val layout = PieLayout.layout(
        diagram = diagram,
        pctLabelSize = { idx, s -> pctSizes[idx] ?: PieLayout.defaultLabelSize(s, renderDensity) },
        legendLabelSize = { idx, s -> legendSizes[idx] ?: PieLayout.defaultLabelSize(s, renderDensity) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredPieResult(
        layout = layout,
        titleSize = titleSize,
        pctSizes = pctSizes,
        legendSizes = legendSizes,
    )
}

private fun DrawScope.drawPieUnderlay(
    layout: PieDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val cullMargin = 8f * unit
    val outerR = layout.outerRadius
    val innerR = layout.innerRadius
    val strokeW = layout.outerStrokeWidth

    for (ps in layout.slices) {
        if (ps.sweepAngleDeg <= 0.01f) continue
        if (!rectIntersectsViewport(pieSliceWedgeBounds(layout, ps), viewport, cullMargin)) continue
        val border = theme.seriesColor(ps.index)
        val fill = androidx.compose.ui.graphics.lerp(theme.seriesFill(ps.index), border, 0.30f)
        val cx = layout.center.x + ps.explodeOffset.x
        val cy = layout.center.y + ps.explodeOffset.y
        val outerRect = Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR)

        if (ps.sweepAngleDeg >= 359.9f) {
            if (innerR <= 0.5f) {
                drawCircle(color = fill, radius = outerR, center = Offset(cx, cy))
                drawCircle(color = border, radius = outerR, center = Offset(cx, cy), style = Stroke(width = strokeW))
            } else {
                val ringWidth = outerR - innerR
                val midR = innerR + ringWidth / 2f
                drawCircle(
                    color = fill,
                    radius = midR,
                    center = Offset(cx, cy),
                    style = Stroke(width = ringWidth),
                )
                drawCircle(color = border, radius = outerR, center = Offset(cx, cy), style = Stroke(width = strokeW))
                drawCircle(color = border, radius = innerR, center = Offset(cx, cy), style = Stroke(width = strokeW))
            }
        } else if (innerR <= 0.5f) {
            drawArc(
                color = fill,
                startAngle = ps.startAngleDeg,
                sweepAngle = ps.sweepAngleDeg,
                useCenter = true,
                topLeft = outerRect.topLeft,
                size = outerRect.size,
            )
            drawArc(
                color = border,
                startAngle = ps.startAngleDeg,
                sweepAngle = ps.sweepAngleDeg,
                useCenter = true,
                topLeft = outerRect.topLeft,
                size = outerRect.size,
                style = Stroke(width = strokeW, join = StrokeJoin.Round),
            )
        } else {
            val innerRect = Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
            val path = Path().apply {
                arcTo(outerRect, ps.startAngleDeg, ps.sweepAngleDeg, false)
                arcTo(innerRect, ps.startAngleDeg + ps.sweepAngleDeg, -ps.sweepAngleDeg, false)
                close()
            }
            drawPath(path = path, color = fill)
            drawPath(path = path, color = border, style = Stroke(width = strokeW, join = StrokeJoin.Round))
        }
    }

    for (item in layout.legendItems) {
        if (!rectIntersectsViewport(item.swatchRect, viewport, cullMargin)) continue
        val border = theme.seriesColor(item.index)
        val fill = androidx.compose.ui.graphics.lerp(theme.seriesFill(item.index), border, 0.30f)
        val r = item.swatchRect
        val cr = CornerRadius(3f * unit, 3f * unit)
        drawRoundRect(
            color = fill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
        )
        drawRoundRect(
            color = border,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Stroke(width = 1.2f * unit),
        )
    }
}

internal fun measurePieLayoutForExport(
    diagram: PieDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PieDiagramLayout {
    val validSlices = PieLayout.validSlices(diagram)
    val fractions = PieLayout.sliceFractions(validSlices)
    return computePremeasuredPieLayout(
        diagram = diagram,
        validSlices = validSlices,
        fractions = fractions,
        theme = theme,
        layoutOptions = layoutOptions,
        densityObj = densityObj,
        textMeasurer = textMeasurer,
    ).layout
}

