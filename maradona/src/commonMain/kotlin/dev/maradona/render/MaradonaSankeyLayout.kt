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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import dev.maradona.layout.PositionedSankeyLink
import dev.maradona.layout.PositionedSankeyNode
import dev.maradona.layout.SankeyDiagramLayout
import dev.maradona.layout.SankeyLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.SankeyDiagram
import dev.maradona.model.SankeyLabelStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class SankeyLayoutHolder {
    var layout: SankeyDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var labelSizes: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun PositionedSankeyNode.nodeAndLabelBounds(): DiagramRect {
    val minX = min(rect.x, labelRect.x)
    val minY = min(rect.y, labelRect.y)
    val maxX = max(rect.right, labelRect.right)
    val maxY = max(rect.bottom, labelRect.bottom)
    return DiagramRect(minX, minY, maxX - minX, maxY - minY)
}

private fun PositionedSankeyLink.ribbonBounds(): DiagramRect {
    val halfW = thickness / 2f
    val minX = min(start.x, end.x)
    val maxX = max(start.x, end.x)
    val minY = min(start.y - halfW, end.y - halfW)
    val maxY = max(start.y + halfW, end.y + halfW)
    return DiagramRect(minX, minY, max(1f, maxX - minX), max(1f, maxY - minY))
}

private fun resolveSankeyNodeColor(
    nodeIndex: Int,
    nodeId: String,
    explicitColor: String?,
    nodeColorsMap: Map<String, String>,
    theme: MaradonaTheme,
): Color {
    parseCssColor(explicitColor)?.let { return it }
    parseCssColor(nodeColorsMap[nodeId])?.let { return it }
    return theme.seriesColor(nodeIndex)
}

/**
 * Custom Compose [Layout] for Mermaid `sankey` / `sankey-beta` diagrams.
 *
 * Node labels (with optional flow values) and the chart title are real [BasicText]
 * composables; the proportional cubic-Bezier flow ribbons and vertical node bars
 * are drawn on the underlay canvas.
 */
@Composable
fun MaradonaSankeyLayout(
    diagram: SankeyDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (SankeyDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { SankeyLayoutHolder() }
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

    // Precompute formatted label texts and flow summaries for each node in diagram.nodes order.
    val nodeLabelTexts = remember(diagram) {
        val inSums = DoubleArray(diagram.nodes.size)
        val outSums = DoubleArray(diagram.nodes.size)
        val idxById = HashMap<String, Int>(diagram.nodes.size)
        for ((i, n) in diagram.nodes.withIndex()) idxById[n.id] = i
        for (l in diagram.links) {
            val u = idxById[l.source] ?: continue
            val v = idxById[l.target] ?: continue
            outSums[u] += l.value
            inSums[v] += l.value
        }
        diagram.nodes.mapIndexed { idx, node ->
            val v = max(inSums[idx], outSums[idx])
            SankeyLayout.formatNodeLabel(node.id, v, diagram)
        }
    }
    val nodeFlowSummaries = remember(diagram) {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (l in diagram.links) {
            out.getOrPut(l.source) { mutableListOf() }.add("flows to ${l.target} (${l.value})")
            out.getOrPut(l.target) { mutableListOf() }.add("receives from ${l.source} (${l.value})")
        }
        out.mapValues { it.value.joinToString("; ") }
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
            computePremeasuredSankeyLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                nodeLabelTexts = nodeLabelTexts,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.labelSizes = pre.labelSizes
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

    val positionedNodeByIndex = remember(precomputedLayout) {
        precomputedLayout.nodes.associateBy { it.nodeIndex }
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
                    "Sankey diagram. ${diagram.nodes.size} nodes, ${diagram.links.size} links."
                }
                val vp = holder.visibleViewport
                if (vp != null && onNodeClick != null) {
                    val offscreenEvents = buildList {
                        for (pn in precomputedLayout.nodes) {
                            if (rectIntersectsViewport(pn.nodeAndLabelBounds(), vp, 16f * renderDensity)) continue
                            add(MaradonaNodeClickEvent(nodeId = pn.node.id, label = pn.node.id))
                        }
                    }
                    val actions = buildOffscreenCustomActions(offscreenEvents, onNodeClick, uriHandler)
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawSankeyUnderlay(it, diagram, theme, holder.visibleViewport) }
            },
        content = {
            if (!diagram.title.isNullOrBlank()) {
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
            for ((idx, lblText) in nodeLabelTexts.withIndex()) {
                if (activeCullViewport != null) {
                    val pn = positionedNodeByIndex[idx]
                    if (pn != null && !rectIntersectsViewport(pn.nodeAndLabelBounds(), activeCullViewport, 16f * renderDensity)) {
                        continue
                    }
                }
                BasicText(
                    text = annotatedLabel(lblText),
                    modifier = Modifier.layoutId("nodelbl:$idx"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(11.5f),
                        fontWeight = FontWeight.Medium,
                    ),
                )
                val node = diagram.nodes[idx]
                val flowDesc = nodeFlowSummaries[node.id]
                val baseDesc = if (flowDesc.isNullOrBlank()) {
                    "Sankey node ${lblText.replace('\n', ' ')}"
                } else {
                    "Sankey node ${lblText.replace('\n', ' ')}. $flowDesc"
                }
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = node.id, label = node.id) else null
                Box(
                    modifier = Modifier
                        .layoutId("nodebox:$idx")
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = idx,
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
        val nodeLblPlaceables = HashMap<Int, Placeable>()
        val labelSizesByText = HashMap<String, DiagramSize>(holder.labelSizes)
        val nodeBoxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("nodelbl:") -> {
                    val idx = id.removePrefix("nodelbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    nodeLblPlaceables[idx] = pl
                    val txt = nodeLabelTexts.getOrNull(idx)
                    if (txt != null) {
                        val prev = labelSizesByText[txt]
                        labelSizesByText[txt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
                id.startsWith("nodebox:") -> {
                    val idx = id.removePrefix("nodebox:").toIntOrNull() ?: continue
                    nodeBoxMeasurables[idx] = m
                }
            }
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val resolvedTitleSize = if (!diagram.title.isNullOrBlank()) {
            titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) } ?: holder.titleSize
        } else {
            null
        }

        val layout = SankeyLayout.layout(
            diagram = diagram,
            nodeLabelSize = { s ->
                labelSizesByText[s] ?: SankeyLayout.defaultLabelSize(s, renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.labelSizes = labelSizesByText
        }
        holder.layout = layout
        onLayout(layout)

        val nodeBoxPlaceables = HashMap<Int, Placeable>()
        for (pn in layout.nodes) {
            val m = nodeBoxMeasurables[pn.nodeIndex] ?: continue
            nodeBoxPlaceables[pn.nodeIndex] = m.measure(
                Constraints.fixed(
                    pn.rect.width.roundToInt().coerceAtLeast(1),
                    pn.rect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }

        val drawnLinks = if (activeCullViewport == null) {
            layout.links.size
        } else {
            layout.links.count { rectIntersectsViewport(it.ribbonBounds(), activeCullViewport, 16f * renderDensity) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = nodeBoxPlaceables.size,
                totalNodes = layout.nodes.size,
                composedEdgeLabels = 0,
                totalEdgeLabels = 0,
                drawnEdges = drawnLinks,
                totalEdges = layout.links.size,
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
            for (pn in layout.nodes) {
                nodeBoxPlaceables[pn.nodeIndex]?.place(
                    pn.rect.x.roundToInt(),
                    pn.rect.y.roundToInt(),
                )
                val pl = nodeLblPlaceables[pn.nodeIndex]
                if (pl != null) {
                    pl.place(
                        pn.labelRect.x.roundToInt(),
                        (pn.labelRect.centerY - pl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private data class PremeasuredSankeyResult(
    val layout: SankeyDiagramLayout,
    val titleSize: DiagramSize?,
    val labelSizes: Map<String, DiagramSize>,
)

private fun computePremeasuredSankeyLayout(
    diagram: SankeyDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    nodeLabelTexts: List<String>,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredSankeyResult {
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
    val nodeLblStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(11.5f),
        fontWeight = FontWeight.Medium,
    )
    val labelSizes = HashMap<String, DiagramSize>(nodeLabelTexts.size)
    for (txt in nodeLabelTexts) {
        val sz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(txt),
            style = nodeLblStyle,
        )
        val prev = labelSizes[txt]
        labelSizes[txt] = DiagramSize(
            maxOf(prev?.width ?: 0f, sz.width),
            maxOf(prev?.height ?: 0f, sz.height),
        )
    }
    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            density = renderDensity,
        )
    }
    val layout = SankeyLayout.layout(
        diagram = diagram,
        nodeLabelSize = { s -> labelSizes[s] ?: SankeyLayout.defaultLabelSize(s, renderDensity) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredSankeyResult(
        layout = layout,
        titleSize = titleSize,
        labelSizes = labelSizes,
    )
}

private fun DrawScope.drawSankeyUnderlay(
    layout: SankeyDiagramLayout,
    diagram: SankeyDiagram,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val nodeColors = layout.nodes.associate { pn ->
        pn.nodeIndex to resolveSankeyNodeColor(
            nodeIndex = pn.nodeIndex,
            nodeId = pn.node.id,
            explicitColor = pn.node.color,
            nodeColorsMap = diagram.nodeColors,
            theme = theme,
        )
    }

    // 1. Flow link ribbons.
    val linkMode = diagram.linkColor.trim().lowercase()
    val customLinkColor = if (linkMode != "gradient" && linkMode != "source" && linkMode != "target") {
        parseCssColor(diagram.linkColor)
    } else {
        null
    }

    for (pl in layout.links) {
        if (!rectIntersectsViewport(pl.ribbonBounds(), viewport, 16f * unit)) continue
        val srcCol = (nodeColors[pl.sourceNodeIndex] ?: theme.seriesColor(0)).copy(alpha = 0.45f)
        val dstCol = (nodeColors[pl.targetNodeIndex] ?: theme.seriesColor(1)).copy(alpha = 0.45f)
        val halfW = pl.thickness / 2f
        val path = Path().apply {
            moveTo(pl.start.x, pl.start.y - halfW)
            cubicTo(
                pl.control1.x,
                pl.control1.y - halfW,
                pl.control2.x,
                pl.control2.y - halfW,
                pl.end.x,
                pl.end.y - halfW,
            )
            lineTo(pl.end.x, pl.end.y + halfW)
            cubicTo(
                pl.control2.x,
                pl.control2.y + halfW,
                pl.control1.x,
                pl.control1.y + halfW,
                pl.start.x,
                pl.start.y + halfW,
            )
            close()
        }
        if (linkMode == "gradient" && abs(pl.end.x - pl.start.x) > 1f) {
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    colors = listOf(srcCol, dstCol),
                    start = Offset(pl.start.x, pl.start.y),
                    end = Offset(pl.end.x, pl.end.y),
                ),
            )
        } else {
            val solidColor = when (linkMode) {
                "target" -> dstCol
                "source", "gradient" -> srcCol
                else -> customLinkColor?.copy(alpha = 0.45f) ?: srcCol
            }
            drawPath(
                path = path,
                color = solidColor,
            )
        }
    }

    // 2. Node bars.
    for (pn in layout.nodes) {
        if (!rectIntersectsViewport(pn.nodeAndLabelBounds(), viewport, 16f * unit)) continue
        val fill = nodeColors[pn.nodeIndex] ?: theme.seriesColor(0)
        drawRect(
            color = fill,
            topLeft = Offset(pn.rect.x, pn.rect.y),
            size = Size(pn.rect.width, pn.rect.height),
        )
        drawRect(
            color = theme.nodeBorder.copy(alpha = 0.55f),
            topLeft = Offset(pn.rect.x, pn.rect.y),
            size = Size(pn.rect.width, pn.rect.height),
            style = Stroke(width = 0.8f * unit),
        )
    }

    // 3. Optional outlined label halo for SankeyLabelStyle.Outlined.
    if (diagram.labelStyle == SankeyLabelStyle.Outlined) {
        val padX = 3f * unit
        val padY = 1.5f * unit
        val r = CornerRadius(3f * unit, 3f * unit)
        for (pn in layout.nodes) {
            if (!rectIntersectsViewport(pn.nodeAndLabelBounds(), viewport, 16f * unit)) continue
            val lr = pn.labelRect
            drawRoundRect(
                color = theme.background.copy(alpha = 0.85f),
                topLeft = Offset(lr.x - padX, lr.y - padY),
                size = Size(lr.width + padX * 2f, lr.height + padY * 2f),
                cornerRadius = r,
            )
        }
    }
}

internal fun measureSankeyLayoutForExport(
    diagram: SankeyDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): SankeyDiagramLayout {
    val inSums = DoubleArray(diagram.nodes.size)
    val outSums = DoubleArray(diagram.nodes.size)
    val idxById = HashMap<String, Int>(diagram.nodes.size)
    for ((i, n) in diagram.nodes.withIndex()) idxById[n.id] = i
    for (l in diagram.links) {
        val u = idxById[l.source] ?: continue
        val v = idxById[l.target] ?: continue
        outSums[u] += l.value
        inSums[v] += l.value
    }
    val nodeLabelTexts = diagram.nodes.mapIndexed { idx, node ->
        val v = max(inSums[idx], outSums[idx])
        SankeyLayout.formatNodeLabel(node.id, v, diagram)
    }
    return computePremeasuredSankeyLayout(
        diagram = diagram,
        theme = theme,
        layoutOptions = layoutOptions,
        nodeLabelTexts = nodeLabelTexts,
        densityObj = densityObj,
        textMeasurer = textMeasurer,
    ).layout
}

