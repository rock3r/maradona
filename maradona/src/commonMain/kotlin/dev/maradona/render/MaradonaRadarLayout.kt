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
import androidx.compose.ui.graphics.drawscope.Fill
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
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.PositionedRadarCurve
import dev.maradona.layout.PositionedRadarLegendItem
import dev.maradona.layout.RadarDiagramLayout
import dev.maradona.layout.RadarLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.RadarDiagram
import dev.maradona.model.RadarGraticule
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private class RadarLayoutHolder {
    var layout: RadarDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var axisSizesByText: Map<String, DiagramSize> = emptyMap()
    var legendSizesByText: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun legendItemRect(li: PositionedRadarLegendItem): DiagramRect {
    val minX = min(li.swatchRect.x, li.labelRect.x)
    val minY = min(li.swatchRect.y, li.labelRect.y)
    val maxX = max(li.swatchRect.right, li.labelRect.right)
    val maxY = max(li.swatchRect.bottom, li.labelRect.bottom)
    return DiagramRect(minX, minY, maxX - minX, maxY - minY)
}

private fun resolveRadarCurveColor(
    curveIndex: Int,
    customPalette: Map<Int, String>,
    theme: MaradonaTheme,
): Color {
    parseCssColor(customPalette[curveIndex])?.let { return it }
    return theme.seriesColor(curveIndex)
}

/**
 * Custom Compose [Layout] for Mermaid `radar` / `radar-beta` diagrams.
 *
 * The chart title, radial axis labels, and legend labels are real [BasicText]
 * composables; circular or polygonal graticules, radial spokes, closed curves,
 * and legend swatches are drawn on the underlay canvas.
 */
@Composable
fun MaradonaRadarLayout(
    diagram: RadarDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (RadarDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { RadarLayoutHolder() }
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
            computePremeasuredRadarLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.axisSizesByText = pre.axisSizesByText
                holder.legendSizesByText = pre.legendSizesByText
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

    val positionedAxisByIndex = remember(precomputedLayout) {
        precomputedLayout.axes.associateBy { it.axisIndex }
    }
    val positionedLegendByCurveIndex = remember(precomputedLayout) {
        precomputedLayout.legendItems.associateBy { it.curveIndex }
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
                    "Radar diagram. ${diagram.axes.size} axes, ${diagram.curves.size} curves."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for ((idx, axis) in diagram.axes.withIndex()) {
                            val pa = positionedAxisByIndex[idx] ?: continue
                            if (rectIntersectsViewport(pa.labelRect, vp, cullMargin)) continue
                            if (onNodeClick != null) {
                                add(MaradonaNodeClickEvent(nodeId = axis.id, label = axis.label))
                            }
                        }
                        if (diagram.config.showLegend) {
                            for ((idx, curve) in diagram.curves.withIndex()) {
                                val li = positionedLegendByCurveIndex[idx] ?: continue
                                if (rectIntersectsViewport(legendItemRect(li), vp, cullMargin)) continue
                                if (onNodeClick != null) {
                                    add(MaradonaNodeClickEvent(nodeId = curve.id, label = curve.label))
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
                holder.layout?.let { drawRadarUnderlay(it, diagram, theme, holder.visibleViewport) }
            },
        content = {
            if (!diagram.title.isNullOrBlank()) {
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

            for ((idx, axis) in diagram.axes.withIndex()) {
                if (activeCullViewport != null) {
                    val pa = positionedAxisByIndex[idx]
                    if (pa != null && !rectIntersectsViewport(pa.labelRect, activeCullViewport, cullMargin)) continue
                }
                BasicText(
                    text = annotatedLabel(axis.label),
                    modifier = Modifier.layoutId("axis_lbl:$idx"),
                    maxLines = 1,
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.edgeLabelFontSize,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                val axisClickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = axis.id, label = axis.label) else null
                Box(
                    modifier = Modifier
                        .layoutId("axis_box:$idx")
                        .diagramNodeInteraction(
                            baseDescription = "Radar axis ${axis.label}",
                            traversalOrder = idx,
                            event = axisClickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }

            if (diagram.config.showLegend) {
                for ((idx, curve) in diagram.curves.withIndex()) {
                    if (activeCullViewport != null) {
                        val li = positionedLegendByCurveIndex[idx]
                        if (li != null && !rectIntersectsViewport(legendItemRect(li), activeCullViewport, cullMargin)) continue
                    }
                    BasicText(
                        text = annotatedLabel(curve.label),
                        modifier = Modifier.layoutId("legend_lbl:$idx"),
                        maxLines = 1,
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            fontWeight = FontWeight.Normal,
                        ),
                    )
                    val curveClickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = curve.id, label = curve.label) else null
                    Box(
                        modifier = Modifier
                            .layoutId("legend_box:$idx")
                            .diagramNodeInteraction(
                                baseDescription = "Radar curve ${curve.label}",
                                traversalOrder = diagram.axes.size + idx,
                                event = curveClickEvent,
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
        val axisLblPlaceables = HashMap<Int, Placeable>()
        val axisSizesByText = HashMap<String, DiagramSize>(holder.axisSizesByText)
        val axisBoxMeasurables = HashMap<Int, Measurable>()

        val legendLblPlaceables = HashMap<Int, Placeable>()
        val legendSizesByText = HashMap<String, DiagramSize>(holder.legendSizesByText)
        val legendBoxMeasurables = HashMap<Int, Measurable>()

        if (activeCullViewport == null) {
            axisSizesByText.clear()
            legendSizesByText.clear()
        }

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("axis_lbl:") -> {
                    val idx = id.removePrefix("axis_lbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    axisLblPlaceables[idx] = pl
                    val txt = diagram.axes.getOrNull(idx)?.label
                    if (txt != null) {
                        val prev = axisSizesByText[txt]
                        axisSizesByText[txt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
                id.startsWith("axis_box:") -> {
                    val idx = id.removePrefix("axis_box:").toIntOrNull() ?: continue
                    axisBoxMeasurables[idx] = m
                }
                id.startsWith("legend_lbl:") -> {
                    val idx = id.removePrefix("legend_lbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    legendLblPlaceables[idx] = pl
                    val txt = diagram.curves.getOrNull(idx)?.label
                    if (txt != null) {
                        val prev = legendSizesByText[txt]
                        legendSizesByText[txt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
                id.startsWith("legend_box:") -> {
                    val idx = id.removePrefix("legend_box:").toIntOrNull() ?: continue
                    legendBoxMeasurables[idx] = m
                }
            }
        }

        val resolvedTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize

        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.axisSizesByText = axisSizesByText
            holder.legendSizesByText = legendSizesByText
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = RadarLayout.layout(
            diagram = diagram,
            axisLabelSize = { s ->
                axisSizesByText[s] ?: RadarLayout.defaultLabelSize(s, renderDensity)
            },
            legendLabelSize = { s ->
                legendSizesByText[s] ?: RadarLayout.defaultLabelSize(s, renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val axisBoxPlaceables = HashMap<Int, Placeable>()
        for (pa in layout.axes) {
            val m = axisBoxMeasurables[pa.axisIndex] ?: continue
            axisBoxPlaceables[pa.axisIndex] = m.measure(
                Constraints.fixed(
                    pa.labelRect.width.roundToInt().coerceAtLeast(1),
                    pa.labelRect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }

        val legendBoxPlaceables = HashMap<Int, Placeable>()
        for (li in layout.legendItems) {
            val m = legendBoxMeasurables[li.curveIndex] ?: continue
            val totalW = (li.labelRect.right - li.swatchRect.x).roundToInt().coerceAtLeast(1)
            val totalH = max(li.swatchRect.height, li.labelRect.height).roundToInt().coerceAtLeast(1)
            legendBoxPlaceables[li.curveIndex] = m.measure(Constraints.fixed(totalW, totalH))
        }

        val edgeMargin = 20f * renderDensity
        val totalEdges = layout.axes.size + layout.curves.size
        val drawnEdges = if (activeCullViewport == null) {
            totalEdges
        } else {
            val visibleSpokes = layout.axes.count { pa ->
                segmentIntersectsViewport(
                    layout.center.x,
                    layout.center.y,
                    pa.lineEnd.x,
                    pa.lineEnd.y,
                    activeCullViewport,
                    edgeMargin,
                )
            }
            val visibleCurves = layout.curves.count { pc ->
                radarCurveIntersectsViewport(pc, activeCullViewport, edgeMargin)
            }
            visibleSpokes + visibleCurves
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = axisBoxPlaceables.size + legendBoxPlaceables.size,
                totalNodes = layout.axes.size + (if (diagram.config.showLegend) layout.curves.size else 0),
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

            for (pa in layout.axes) {
                axisBoxPlaceables[pa.axisIndex]?.place(
                    pa.labelRect.x.roundToInt(),
                    pa.labelRect.y.roundToInt(),
                )
                axisLblPlaceables[pa.axisIndex]?.place(
                    pa.labelRect.x.roundToInt(),
                    pa.labelRect.y.roundToInt(),
                )
            }

            for (li in layout.legendItems) {
                legendBoxPlaceables[li.curveIndex]?.place(
                    li.swatchRect.x.roundToInt(),
                    minOf(li.swatchRect.y, li.labelRect.y).roundToInt(),
                )
                legendLblPlaceables[li.curveIndex]?.place(
                    li.labelRect.x.roundToInt(),
                    li.labelRect.y.roundToInt(),
                )
            }
        }
    }
}

private data class PremeasuredRadarResult(
    val layout: RadarDiagramLayout,
    val titleSize: DiagramSize?,
    val axisSizesByText: Map<String, DiagramSize>,
    val legendSizesByText: Map<String, DiagramSize>,
)

private fun computePremeasuredRadarLayout(
    diagram: RadarDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredRadarResult {
    val renderDensity = densityObj.density
    val titleSize = diagram.title?.takeIf { it.isNotBlank() }?.let { title ->
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

    val axisStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        fontWeight = FontWeight.Medium,
    )
    val axisSizesByText = HashMap<String, DiagramSize>()
    for (axis in diagram.axes) {
        val sz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(axis.label),
            style = axisStyle,
        )
        val prev = axisSizesByText[axis.label]
        axisSizesByText[axis.label] = DiagramSize(
            maxOf(prev?.width ?: 0f, sz.width),
            maxOf(prev?.height ?: 0f, sz.height),
        )
    }

    val legendStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        fontWeight = FontWeight.Normal,
    )
    val legendSizesByText = HashMap<String, DiagramSize>()
    if (diagram.config.showLegend) {
        for (curve in diagram.curves) {
            val sz = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(curve.label),
                style = legendStyle,
            )
            val prev = legendSizesByText[curve.label]
            legendSizesByText[curve.label] = DiagramSize(
                maxOf(prev?.width ?: 0f, sz.width),
                maxOf(prev?.height ?: 0f, sz.height),
            )
        }
    }

    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            density = renderDensity,
        )
    }
    val layout = RadarLayout.layout(
        diagram = diagram,
        axisLabelSize = { s -> axisSizesByText[s] ?: RadarLayout.defaultLabelSize(s, renderDensity) },
        legendLabelSize = { s -> legendSizesByText[s] ?: RadarLayout.defaultLabelSize(s, renderDensity) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredRadarResult(
        layout = layout,
        titleSize = titleSize,
        axisSizesByText = axisSizesByText,
        legendSizesByText = legendSizesByText,
    )
}

private fun DrawScope.drawRadarUnderlay(
    layout: RadarDiagramLayout,
    diagram: RadarDiagram,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val cx = layout.center.x
    val cy = layout.center.y
    val graticuleStroke = Stroke(width = max(1f, 1.05f * density))
    val graticuleColor = theme.nodeBorder.copy(alpha = 0.45f)
    val graticuleFill = theme.nodeFill.copy(alpha = 0.35f)
    val spokeStrokeW = max(1f, 1.15f * density)
    val spokeColor = theme.nodeBorder.copy(alpha = 0.65f)
    val edgeMargin = 20f * density

    // 1. Graticule rings (outermost first for subtle background tint)
    val numAxes = layout.axes.size
    for (t in layout.ticks downTo 1) {
        val r = layout.radius * t.toFloat() / layout.ticks.toFloat()
        val ringRect = DiagramRect(cx - r, cy - r, r * 2f, r * 2f)
        if (!rectIntersectsViewport(ringRect, viewport, 4f * density)) continue
        if (layout.graticule == RadarGraticule.Polygon && numAxes >= 3) {
            val poly = Path()
            for (i in 0 until numAxes) {
                val angle = layout.axes[i].angleRad.toDouble()
                val px = cx + r * cos(angle).toFloat()
                val py = cy + r * sin(angle).toFloat()
                if (i == 0) poly.moveTo(px, py) else poly.lineTo(px, py)
            }
            poly.close()
            if (t == layout.ticks) {
                drawPath(path = poly, color = graticuleFill, style = Fill)
            }
            drawPath(path = poly, color = graticuleColor, style = graticuleStroke)
        } else {
            if (t == layout.ticks) {
                drawCircle(color = graticuleFill, radius = r, center = Offset(cx, cy), style = Fill)
            }
            drawCircle(color = graticuleColor, radius = r, center = Offset(cx, cy), style = graticuleStroke)
        }
    }

    // 2. Radial axis lines
    for (pa in layout.axes) {
        if (!segmentIntersectsViewport(cx, cy, pa.lineEnd.x, pa.lineEnd.y, viewport, edgeMargin)) continue
        drawLine(
            color = spokeColor,
            start = Offset(cx, cy),
            end = Offset(pa.lineEnd.x, pa.lineEnd.y),
            strokeWidth = spokeStrokeW,
        )
    }

    // 3. Curves
    val rawStrokeWidth = diagram.config.curveStrokeWidth
    val curveStroke = if (rawStrokeWidth > 0f) {
        Stroke(width = rawStrokeWidth * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    } else {
        null
    }
    val fillAlpha = diagram.config.curveOpacity.coerceIn(0f, 1f)
    val dotRadius = 3.2f * density

    for (pc in layout.curves) {
        val pts = pc.points
        if (pts.isEmpty()) continue
        if (!radarCurveIntersectsViewport(pc, viewport, edgeMargin)) continue
        val curveColor = resolveRadarCurveColor(pc.curveIndex, diagram.config.customPalette, theme)
        if (pts.size >= 2) {
            val path = buildRadarCurvePath(pts, pc.useSmoothClosedCurve, pc.tension)
            if (pts.size >= 3 && fillAlpha > 0f) {
                drawPath(path = path, color = curveColor.copy(alpha = fillAlpha), style = Fill)
            }
            if (curveStroke != null) {
                drawPath(path = path, color = curveColor, style = curveStroke)
            }
        }
        if (curveStroke != null) {
            for (pt in pts) {
                drawCircle(
                    color = curveColor,
                    radius = dotRadius,
                    center = Offset(pt.x, pt.y),
                )
            }
        }
    }

    // 4. Legend swatches
    val swatchStroke = Stroke(width = max(1f, 1.25f * density))
    val swatchAlpha = max(0.35f, fillAlpha)
    for (li in layout.legendItems) {
        if (!rectIntersectsViewport(li.swatchRect, viewport, 4f * density)) continue
        val curveColor = resolveRadarCurveColor(li.curveIndex, diagram.config.customPalette, theme)
        val topLeft = Offset(li.swatchRect.x, li.swatchRect.y)
        val sz = Size(li.swatchRect.width, li.swatchRect.height)
        drawRect(color = curveColor.copy(alpha = swatchAlpha), topLeft = topLeft, size = sz, style = Fill)
        drawRect(color = curveColor, topLeft = topLeft, size = sz, style = swatchStroke)
    }
}

private fun radarCurveIntersectsViewport(
    pc: PositionedRadarCurve,
    viewport: DiagramRect?,
    margin: Float,
): Boolean {
    if (viewport == null) return true
    val pts = pc.points
    if (pts.isEmpty()) return false
    if (pts.size < 2 || !pc.useSmoothClosedCurve) {
        return polylineIntersectsViewport(pts, viewport, margin)
    }
    val bounds = buildRadarCurvePath(pts, pc.useSmoothClosedCurve, pc.tension).getBounds()
    return DiagramRect(
        x = bounds.left - margin,
        y = bounds.top - margin,
        width = bounds.width + margin * 2f,
        height = bounds.height + margin * 2f,
    ).intersects(viewport)
}

private fun buildRadarCurvePath(
    points: List<DiagramPoint>,
    useSmoothClosedCurve: Boolean,
    tension: Float,
): Path {
    val path = Path()
    val n = points.size
    if (n == 0) return path
    path.moveTo(points[0].x, points[0].y)
    if (useSmoothClosedCurve && n >= 3 && tension > 0f) {
        for (i in 0 until n) {
            val p0 = points[(i - 1 + n) % n]
            val p1 = points[i]
            val p2 = points[(i + 1) % n]
            val p3 = points[(i + 2) % n]
            val cp1x = p1.x + (p2.x - p0.x) * tension
            val cp1y = p1.y + (p2.y - p0.y) * tension
            val cp2x = p2.x - (p3.x - p1.x) * tension
            val cp2y = p2.y - (p3.y - p1.y) * tension
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }
    } else {
        for (i in 1 until n) {
            path.lineTo(points[i].x, points[i].y)
        }
    }
    path.close()
    return path
}
