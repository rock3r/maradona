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
import dev.maradona.layout.PositionedQuadrantPoint
import dev.maradona.layout.QuadrantDiagramLayout
import dev.maradona.layout.QuadrantLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.QuadrantDiagram
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class QuadrantLayoutHolder {
    var layout: QuadrantDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var qSizes: Map<Int, DiagramSize> = emptyMap()
    var axisSizes: Map<String, DiagramSize> = emptyMap()
    var ptLblSizes: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun PositionedQuadrantPoint.pointAndLabelBounds(): DiagramRect {
    val r = radius + strokeWidth / 2f
    val minX = min(labelRect.x, center.x - r)
    val minY = min(labelRect.y, center.y - r)
    val maxX = max(labelRect.right, center.x + r)
    val maxY = max(labelRect.bottom, center.y + r)
    return DiagramRect(minX, minY, maxX - minX, maxY - minY)
}

/**
 * Custom Compose [Layout] for Mermaid `quadrantChart` diagrams.
 *
 * Quadrant labels, X/Y axis labels, point labels, and the optional chart title are
 * real [BasicText] composables; the four quadrant backgrounds, crosshair dividers,
 * outer border, and styled data points are drawn on the underlay canvas.
 */
@Composable
fun MaradonaQuadrantLayout(
    diagram: QuadrantDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (QuadrantDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { QuadrantLayoutHolder() }
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

    val quadrantSpecs = remember(diagram) {
        listOf(
            1 to diagram.quadrant1Text,
            2 to diagram.quadrant2Text,
            3 to diagram.quadrant3Text,
            4 to diagram.quadrant4Text,
        ).mapNotNull { (qNum, txt) ->
            txt?.takeIf { it.isNotBlank() }?.let { qNum to it }
        }
    }
    val axisSpecs = remember(diagram) {
        listOf(
            0 to diagram.xAxisLeftText,
            1 to diagram.xAxisRightText,
            2 to diagram.yAxisBottomText,
            3 to diagram.yAxisTopText,
        ).mapNotNull { (slot, txt) ->
            txt?.takeIf { it.isNotBlank() }?.let { slot to it }
        }
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
            computePremeasuredQuadrantLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                quadrantSpecs = quadrantSpecs,
                axisSpecs = axisSpecs,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.qSizes = pre.qSizes
                holder.axisSizes = pre.axisSizes
                holder.ptLblSizes = pre.ptLblSizes
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

    val positionedQuadrantByNum = remember(precomputedLayout) {
        precomputedLayout.quadrants.associateBy { it.quadrantNumber }
    }
    val positionedPointById = remember(precomputedLayout) {
        precomputedLayout.points.associateBy { it.point.id }
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
                    "Quadrant chart. ${diagram.points.size} points."
                }
                val vp = holder.visibleViewport
                if (vp != null && onNodeClick != null) {
                    val offscreenEvents = buildList {
                        for (pt in precomputedLayout.points) {
                            if (rectIntersectsViewport(pt.pointAndLabelBounds(), vp, 12f * renderDensity)) continue
                            add(MaradonaNodeClickEvent(nodeId = pt.point.id, label = pt.point.label))
                        }
                    }
                    val actions = buildOffscreenCustomActions(offscreenEvents, onNodeClick, uriHandler)
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawQuadrantUnderlay(it, theme, holder.visibleViewport) }
            },
        content = {
            if (diagram.title != null) {
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
            for ((qNum, txt) in quadrantSpecs) {
                if (activeCullViewport != null) {
                    val qBox = positionedQuadrantByNum[qNum]
                    if (qBox != null && !rectIntersectsViewport(qBox.rect, activeCullViewport, 8f * renderDensity)) continue
                }
                BasicText(
                    text = annotatedLabel(txt),
                    modifier = Modifier.layoutId("q:$qNum"),
                    style = TextStyle(
                        color = theme.nodeText.copy(alpha = 0.85f),
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(13f),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for ((slot, axTxt) in axisSpecs) {
                BasicText(
                    text = annotatedLabel(axTxt),
                    modifier = Modifier.layoutId("ax:$slot"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(12.5f),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for ((pIdx, p) in diagram.points.withIndex()) {
                if (activeCullViewport != null) {
                    val ptPos = positionedPointById[p.id]
                    if (ptPos != null && !rectIntersectsViewport(ptPos.pointAndLabelBounds(), activeCullViewport, 12f * renderDensity)) continue
                }
                BasicText(
                    text = annotatedLabel(p.label),
                    modifier = Modifier.layoutId("ptlbl:${p.id}"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(11.5f),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
                val baseDesc = "Quadrant point ${p.label} at (${p.x}, ${p.y})"
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = p.id, label = p.label) else null
                Box(
                    modifier = Modifier
                        .layoutId("ptbox:${p.id}")
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = pIdx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()
        var titlePlaceable: Placeable? = null
        val qPlaceables = HashMap<Int, Placeable>()
        val axisPlaceables = HashMap<Int, Placeable>()
        val axisSizeByText = HashMap<String, DiagramSize>()
        val ptLblPlaceables = HashMap<String, Placeable>()
        val ptBoxMeasurables = HashMap<String, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("q:") -> {
                    val qNum = id.removePrefix("q:").toIntOrNull() ?: continue
                    qPlaceables[qNum] = m.measure(unbounded)
                }
                id.startsWith("ax:") -> {
                    val slot = id.removePrefix("ax:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    axisPlaceables[slot] = pl
                    val txt = axisSpecs.firstOrNull { it.first == slot }?.second
                    if (txt != null) {
                        axisSizeByText[txt] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
                id.startsWith("ptlbl:") -> ptLblPlaceables[id.removePrefix("ptlbl:")] = m.measure(unbounded)
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

        val resolvedTitleSize = if (diagram.title != null) {
            titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) } ?: holder.titleSize
        } else {
            null
        }

        val layout = QuadrantLayout.layout(
            diagram = diagram,
            pointLabelSize = { p ->
                val pl = ptLblPlaceables[p.id]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else holder.ptLblSizes[p.id] ?: QuadrantLayout.defaultTextSize(p.label, renderDensity)
            },
            quadrantLabelSize = { qNum, s ->
                val pl = qPlaceables[qNum]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else holder.qSizes[qNum] ?: QuadrantLayout.defaultHeaderSize(s, renderDensity)
            },
            axisLabelSize = { s ->
                axisSizeByText[s] ?: holder.axisSizes[s] ?: QuadrantLayout.defaultHeaderSize(s, renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.qSizes = qPlaceables.mapValues { DiagramSize(it.value.width.toFloat(), it.value.height.toFloat()) }
            holder.axisSizes = axisSizeByText
            holder.ptLblSizes = ptLblPlaceables.mapValues { DiagramSize(it.value.width.toFloat(), it.value.height.toFloat()) }
        }
        holder.layout = layout
        onLayout(layout)

        val ptBoxPlaceables = HashMap<String, Placeable>()
        for (pt in layout.points) {
            val m = ptBoxMeasurables[pt.point.id] ?: continue
            val dPx = ((pt.radius + pt.strokeWidth / 2f) * 2f).roundToInt().coerceAtLeast(1)
            ptBoxPlaceables[pt.point.id] = m.measure(Constraints.fixed(dPx, dPx))
        }

        val drawnPoints = if (activeCullViewport == null) {
            layout.points.size
        } else {
            layout.points.count { rectIntersectsViewport(it.pointAndLabelBounds(), activeCullViewport, 12f * renderDensity) }
        }
        val drawnQuadrants = if (activeCullViewport == null) {
            layout.quadrants.size
        } else {
            layout.quadrants.count { rectIntersectsViewport(it.rect, activeCullViewport, 8f * renderDensity) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = ptBoxPlaceables.size,
                totalNodes = diagram.points.size,
                composedEdgeLabels = 0,
                totalEdgeLabels = 0,
                drawnEdges = drawnPoints,
                totalEdges = layout.points.size,
                drawnSubgraphs = drawnQuadrants,
                totalSubgraphs = layout.quadrants.size,
            ),
        )

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for (q in layout.quadrants) {
                val lr = q.labelRect ?: continue
                val pl = qPlaceables[q.quadrantNumber] ?: continue
                pl.place(
                    (lr.centerX - pl.width / 2f).roundToInt(),
                    (lr.centerY - pl.height / 2f).roundToInt(),
                )
            }
            for (al in layout.axisLabels) {
                val pl = axisPlaceables[al.axisSlot] ?: continue
                val px = (al.rect.centerX - pl.width / 2f).roundToInt()
                val py = (al.rect.centerY - pl.height / 2f).roundToInt()
                if (al.rotationDeg != 0f) {
                    pl.placeWithLayer(px, py) {
                        rotationZ = al.rotationDeg
                    }
                } else {
                    pl.place(px, py)
                }
            }
            for (pt in layout.points) {
                val r = pt.radius + pt.strokeWidth / 2f
                ptBoxPlaceables[pt.point.id]?.place(
                    (pt.center.x - r).roundToInt(),
                    (pt.center.y - r).roundToInt(),
                )
                val pl = ptLblPlaceables[pt.point.id]
                if (pl != null) {
                    pl.place(
                        (pt.labelRect.centerX - pl.width / 2f).roundToInt(),
                        (pt.labelRect.centerY - pl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private data class PremeasuredQuadrantResult(
    val layout: QuadrantDiagramLayout,
    val titleSize: DiagramSize?,
    val qSizes: Map<Int, DiagramSize>,
    val axisSizes: Map<String, DiagramSize>,
    val ptLblSizes: Map<String, DiagramSize>,
)

private fun computePremeasuredQuadrantLayout(
    diagram: QuadrantDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    quadrantSpecs: List<Pair<Int, String>>,
    axisSpecs: List<Pair<Int, String>>,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredQuadrantResult {
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
    val qStyle = TextStyle(
        color = theme.nodeText.copy(alpha = 0.85f),
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(13f),
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val qSizes = quadrantSpecs.associate { (qNum, txt) ->
        qNum to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(txt),
            style = qStyle,
        )
    }
    val axStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(12.5f),
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val axisSizes = axisSpecs.associate { (_, axTxt) ->
        axTxt to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(axTxt),
            style = axStyle,
        )
    }
    val ptLblStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(11.5f),
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val ptLblSizes = diagram.points.associate { p ->
        p.id to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(p.label),
            style = ptLblStyle,
        )
    }
    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            density = renderDensity,
        )
    }
    val layout = QuadrantLayout.layout(
        diagram = diagram,
        pointLabelSize = { p -> ptLblSizes[p.id] ?: QuadrantLayout.defaultTextSize(p.label, renderDensity) },
        quadrantLabelSize = { qNum, s -> qSizes[qNum] ?: QuadrantLayout.defaultHeaderSize(s, renderDensity) },
        axisLabelSize = { s -> axisSizes[s] ?: QuadrantLayout.defaultHeaderSize(s, renderDensity) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredQuadrantResult(
        layout = layout,
        titleSize = titleSize,
        qSizes = qSizes,
        axisSizes = axisSizes,
        ptLblSizes = ptLblSizes,
    )
}

private fun DrawScope.drawQuadrantUnderlay(
    layout: QuadrantDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val fills = listOf(
        androidx.compose.ui.graphics.lerp(theme.seriesFill(0), theme.background, 0.42f),
        androidx.compose.ui.graphics.lerp(theme.seriesFill(2), theme.background, 0.42f),
        theme.mutedFill,
        androidx.compose.ui.graphics.lerp(theme.seriesFill(3), theme.background, 0.42f),
    )
    val defaultPointColor = theme.accentBorder

    // 1. Quadrant background fills.
    for (q in layout.quadrants) {
        if (!rectIntersectsViewport(q.rect, viewport, 8f * unit)) continue
        val fill = fills[(q.quadrantNumber - 1).coerceIn(0, fills.lastIndex)]
        drawRect(
            color = fill,
            topLeft = Offset(q.rect.x, q.rect.y),
            size = Size(q.rect.width, q.rect.height),
        )
    }

    // 2. Inner crosshairs & outer frame.
    val g = layout.gridRect
    if (rectIntersectsViewport(g, viewport, 8f * unit)) {
        val midX = g.centerX
        val midY = g.centerY
        drawLine(
            color = theme.subgraphBorder,
            start = Offset(midX, g.y),
            end = Offset(midX, g.bottom),
            strokeWidth = 1.2f * unit,
        )
        drawLine(
            color = theme.subgraphBorder,
            start = Offset(g.x, midY),
            end = Offset(g.right, midY),
            strokeWidth = 1.2f * unit,
        )
        drawRect(
            color = theme.nodeBorder,
            topLeft = Offset(g.x, g.y),
            size = Size(g.width, g.height),
            style = Stroke(width = 2f * unit),
        )
    }

    // 3. Data points.
    for (pt in layout.points) {
        if (!rectIntersectsViewport(pt.pointAndLabelBounds(), viewport, 12f * unit)) continue
        val st = pt.point.style
        val fill = parseCssColor(st?.color) ?: defaultPointColor
        val stroke = parseCssColor(st?.strokeColor) ?: fill
        val center = Offset(pt.center.x, pt.center.y)
        drawCircle(
            color = fill,
            radius = pt.radius,
            center = center,
        )
        if (pt.strokeWidth > 0f) {
            drawCircle(
                color = stroke,
                radius = pt.radius,
                center = center,
                style = Stroke(width = pt.strokeWidth),
            )
        }
    }
}

internal fun measureQuadrantLayoutForExport(
    diagram: QuadrantDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): QuadrantDiagramLayout {
    val quadrantSpecs = listOf(1, 2, 3, 4).mapNotNull { qNum ->
        val txt = when (qNum) {
            1 -> diagram.quadrant1Text
            2 -> diagram.quadrant2Text
            3 -> diagram.quadrant3Text
            else -> diagram.quadrant4Text
        }
        txt?.takeIf { it.isNotBlank() }?.let { qNum to it }
    }
    val axisSpecs = listOf(
        0 to diagram.xAxisLeftText,
        1 to diagram.xAxisRightText,
        2 to diagram.yAxisBottomText,
        3 to diagram.yAxisTopText,
    ).mapNotNull { (slot, txt) ->
        txt?.takeIf { it.isNotBlank() }?.let { slot to it }
    }
    return computePremeasuredQuadrantLayout(
        diagram = diagram,
        theme = theme,
        layoutOptions = layoutOptions,
        quadrantSpecs = quadrantSpecs,
        axisSpecs = axisSpecs,
        densityObj = densityObj,
        textMeasurer = textMeasurer,
    ).layout
}

