package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.FlowchartLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.NodeSizing
import dev.maradona.model.ArrowHead
import dev.maradona.model.Direction
import dev.maradona.model.EdgeStroke
import dev.maradona.model.Flowchart
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.abs
import kotlin.math.roundToInt

private class LayoutHolder {
    var layout: DiagramLayout? = null
    var nodeSizes: Map<String, DiagramSize> = emptyMap()
    var edgeLabelSizes: List<DiagramSize?> = emptyList()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastResolvedImageUrls: Set<String> = emptySet()
    var lastAdaptedVertical: Boolean = false
    var lastBoundedWidthPx: Int = Int.MAX_VALUE
    var boundedWidthObserved: Boolean = false
    var visibleViewport: DiagramRect? = null
}

internal data class FlowchartViewportParams(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val gestureZoom: Float,
    val panPx: Offset,
    val gesturePanPx: Offset,
    val initialPan: Offset?,
    val initialViewApplied: Boolean,
    val overscanPx: Float,
    val scaleKnownOnFirstFrame: Boolean,
) {
    fun resolve(layoutWidth: Float, layoutHeight: Float): DiagramRect? {
        if (!scaleKnownOnFirstFrame) return null
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f || layoutWidth <= 0f || layoutHeight <= 0f) return null
        val effectivePan = if (!initialViewApplied && initialPan != null) {
            val rangeX = layoutWidth - viewportWidthPx
            val rangeY = layoutHeight - viewportHeightPx
            Offset(
                if (rangeX > 0f) rangeX / 2f - initialPan.x * rangeX else 0f,
                if (rangeY > 0f) rangeY / 2f - initialPan.y * rangeY else 0f,
            )
        } else {
            panPx
        }
        val centerX = (viewportWidthPx - layoutWidth) / 2f
        val centerY = (viewportHeightPx - layoutHeight) / 2f
        val transX = centerX + effectivePan.x + gesturePanPx.x
        val transY = centerY + effectivePan.y + gesturePanPx.y
        val gZoom = gestureZoom.coerceAtLeast(0.01f)
        return DiagramRect(
            x = (-transX - overscanPx) / gZoom,
            y = (-transY - overscanPx) / gZoom,
            width = (viewportWidthPx + overscanPx * 2f) / gZoom,
            height = (viewportHeightPx + overscanPx * 2f) / gZoom,
        )
    }
}

/**
 * Custom layout that measures node composables, runs the Sugiyama layout
 * with those measured sizes, then places nodes, edge labels, and subgraph
 * labels. Edge paths are drawn in an underlay behind the nodes.
 *
 * When [visibleViewport] is non-null (in layout pixel coordinates at the
 * current render density), only nodes, edge labels, and subgraph headers
 * whose bounding boxes intersect [visibleViewport] are composed into the
 * Compose layout tree, and [drawDiagramUnderlay] skips off-screen edges and
 * subgraphs while preserving edges that cross the viewport between off-screen
 * endpoints.
 */
@Composable
fun MaradonaLayout(
    chart: Flowchart,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(),
    /** Width available in the parent, in px at the current density, for
     * [autoDirection]. Callers that measure this layout unbounded (for
     * fit-to-size zooming) must pass it explicitly. */
    availableWidthPx: Int = Int.MAX_VALUE,
    /**
     * Optional visible viewport rectangle in this layout's local pixel
     * coordinates (at the current [LocalDensity]). When non-null, off-screen
     * nodes, edge labels, subgraph headers, and underlay edges/subgraphs
     * outside this rectangle are lazily skipped without a separate renderer.
     */
    visibleViewport: DiagramRect? = null,
    /**
     * Optional resolver that computes the visible viewport rectangle in local
     * layout pixel coordinates once the layout's `width` and `height` are known.
     */
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    /** Optional callback invoked when a flowchart node is clicked/activated. */
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    /** Reports diagnostic counts for viewport-lazy composition and underlay culling. */
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    /** Reports the computed layout (the intermediate representation the oracle
     * compares against mermaid's SVG geometry, and the renderer against its own
     * pixels). Called whenever the layout is recomputed. */
    onLayout: (DiagramLayout) -> Unit = {},
) {
    val holder = remember(chart, theme, layoutOptions) { LayoutHolder() }
    val connections = remember(chart) { connectionDescriptions(chart) }
    val textMeasurer = rememberTextMeasurer()
    val densityObj = LocalDensity.current
    val renderDensity = densityObj.density
    val renderFontScale = densityObj.fontScale
    val imageResolver = LocalMaradonaImageResolver.current
    val uriHandler = LocalUriHandler.current
    val distinctImageUrls = remember(chart) {
        chart.nodes.asSequence()
            .flatMap { extractInlineImages(it.label).asSequence() }
            .map { it.url }
            .distinct()
            .toList()
    }
    val resolvedImageUrls = if (imageResolver == null || distinctImageUrls.isEmpty()) {
        emptySet()
    } else {
        buildSet {
            for (url in distinctImageUrls) {
                if (imageResolver.resolve(url) != null) add(url)
            }
        }
    }
    val effectiveAvailableWidthPx = minOf(availableWidthPx, holder.lastBoundedWidthPx)

    // Pre-compute or reuse the layout at the current renderDensity/fontScale so viewport-lazy
    // composition knows every node, edge label, and subgraph bounding box before
    // emitting child composables, and panning across a zoomed diagram does not
    // re-run Sugiyama/Dagre layout.
    val precomputedLayout = run {
        val breakpointPx = with(densityObj) { 640.dp.roundToPx() }
        val cached = holder.layout
        if (
            cached != null &&
            abs(holder.lastDensity - renderDensity) < 1e-4f &&
            abs(holder.lastFontScale - renderFontScale) < 1e-4f &&
            holder.lastResolvedImageUrls == resolvedImageUrls
        ) {
            val wantVertical = autoDirection &&
                (chart.direction == Direction.LR || chart.direction == Direction.RL) &&
                layoutOptions.directionOverride == null &&
                effectiveAvailableWidthPx in 1 until breakpointPx &&
                cached.width > effectiveAvailableWidthPx
            if (wantVertical == holder.lastAdaptedVertical) {
                cached
            } else {
                computePremeasuredFlowchartLayout(
                    chart = chart,
                    theme = theme,
                    layoutOptions = layoutOptions,
                    autoDirection = autoDirection,
                    availableWidthPx = effectiveAvailableWidthPx,
                    densityObj = densityObj,
                    textMeasurer = textMeasurer,
                    resolvedImageUrls = resolvedImageUrls,
                ).also { pre ->
                    holder.layout = pre.layout
                    holder.nodeSizes = pre.nodeSizes
                    holder.edgeLabelSizes = pre.edgeLabelSizes
                    holder.lastDensity = renderDensity
                    holder.lastFontScale = renderFontScale
                    holder.lastResolvedImageUrls = resolvedImageUrls
                    holder.lastAdaptedVertical = pre.adaptedVertical
                }.layout
            }
        } else {
            computePremeasuredFlowchartLayout(
                chart = chart,
                theme = theme,
                layoutOptions = layoutOptions,
                autoDirection = autoDirection,
                availableWidthPx = effectiveAvailableWidthPx,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
                resolvedImageUrls = resolvedImageUrls,
            ).also { pre ->
                holder.layout = pre.layout
                holder.nodeSizes = pre.nodeSizes
                holder.edgeLabelSizes = pre.edgeLabelSizes
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastResolvedImageUrls = resolvedImageUrls
                holder.lastAdaptedVertical = pre.adaptedVertical
            }.layout
        }
    }
    val effectiveViewport = visibleViewport
        ?: viewportResolver?.invoke(precomputedLayout.width, precomputedLayout.height)
    val needsParentWidthProbe = autoDirection &&
        (chart.direction == Direction.LR || chart.direction == Direction.RL) &&
        layoutOptions.directionOverride == null &&
        availableWidthPx == Int.MAX_VALUE &&
        !holder.boundedWidthObserved
    val activeCullViewport = if (needsParentWidthProbe) null else effectiveViewport
    holder.visibleViewport = activeCullViewport

    val positionedNodeById = remember(precomputedLayout) {
        precomputedLayout.nodes.associateBy { it.node.id }
    }
    val positionedSubgraphById = remember(precomputedLayout) {
        precomputedLayout.subgraphs.associateBy { it.subgraph.id }
    }

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                contentDescription =
                    "Flowchart. ${chart.nodes.size} nodes, ${chart.edges.size} connections."
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenActions = buildList {
                        for (node in chart.nodes) {
                            val pNode = positionedNodeById[node.id] ?: continue
                            if (pNode.rect.intersects(vp)) continue
                            val plain = plainTextLabel(node.label)
                            val clickEvent = node.action?.toClickEvent(node.id, plain)
                                ?: if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = node.id, label = plain) else null
                            if (clickEvent != null && (onNodeClick != null || !clickEvent.url.isNullOrBlank())) {
                                add(
                                    CustomAccessibilityAction(label = "Activate off-screen node $plain") {
                                        if (onNodeClick != null) {
                                            onNodeClick(clickEvent)
                                        } else if (!clickEvent.url.isNullOrBlank()) {
                                            runCatching { uriHandler.openUri(clickEvent.url) }
                                        }
                                        true
                                    },
                                )
                            }
                        }
                    }
                    if (offscreenActions.isNotEmpty()) {
                        customActions = offscreenActions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawDiagramUnderlay(it, theme, holder.visibleViewport) }
            },
        content = {
            for ((index, node) in chart.nodes.withIndex()) {
                if (activeCullViewport != null) {
                    val pNode = positionedNodeById[node.id]
                    if (pNode != null && !pNode.rect.intersects(activeCullViewport)) continue
                }
                MaradonaNode(
                    node = node,
                    theme = theme,
                    modifier = Modifier.layoutId("node:${node.id}"),
                    connectionsDescription = connections[node.id],
                    traversalOrder = index,
                    onNodeClick = onNodeClick,
                )
            }
            for ((index, edge) in chart.edges.withIndex()) {
                val label = edge.label ?: continue
                if (activeCullViewport != null) {
                    val lr = precomputedLayout.edges.getOrNull(index)?.labelRect
                    if (lr != null && !lr.intersects(activeCullViewport)) continue
                }
                BasicText(
                    text = annotatedLabel(label, theme.monospaceFontFamily),
                    modifier = Modifier
                        .layoutId("elabel:$index")
                        .background(theme.edgeLabelBackground)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .semantics {
                            contentDescription =
                                "Connection from ${plainLabel(chart, edge.from)} to ${plainLabel(chart, edge.to)}: ${plainTextLabel(label)}"
                        },
                    style = TextStyle(
                        color = theme.edgeLabelText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.edgeLabelFontSize,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for (sg in chart.subgraphs) {
                if (activeCullViewport != null) {
                    val psg = positionedSubgraphById[sg.id]
                    if (psg != null) {
                        val hdrRect = DiagramRect(
                            x = psg.rect.x,
                            y = psg.rect.y,
                            width = psg.rect.width,
                            height = 36f * renderDensity,
                        )
                        if (!hdrRect.intersects(activeCullViewport)) continue
                    }
                }
                BasicText(
                    text = annotatedLabel(sg.label, theme.monospaceFontFamily),
                    modifier = Modifier.layoutId("sg:${sg.id}"),
                    style = TextStyle(
                        color = theme.subgraphText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.subgraphFontSize,
                    ),
                )
            }
        },
    ) { measurables, constraints ->
        val maxNodeWidth = 240.dp.roundToPx()
        val nodeConstraints = Constraints(maxWidth = maxNodeWidth)
        val nodePlaceables = HashMap<String, androidx.compose.ui.layout.Placeable>()
        val edgeLabelPlaceables = HashMap<Int, androidx.compose.ui.layout.Placeable>()
        val subgraphPlaceables = HashMap<String, androidx.compose.ui.layout.Placeable>()

        for (measurable in measurables) {
            val id = measurable.layoutId as? String ?: continue
            when {
                id.startsWith("node:") ->
                    nodePlaceables[id.removePrefix("node:")] = measurable.measure(nodeConstraints)
                id.startsWith("elabel:") ->
                    edgeLabelPlaceables[id.removePrefix("elabel:").toInt()] =
                        measurable.measure(Constraints(maxWidth = 180.dp.roundToPx()))
                id.startsWith("sg:") ->
                    subgraphPlaceables[id.removePrefix("sg:")] = measurable.measure(Constraints())
            }
        }

        val boundedWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        holder.lastBoundedWidthPx = boundedWidth
        holder.boundedWidthObserved = true
        val effectiveMaxWidth = minOf(boundedWidth, availableWidthPx)
        val breakpoint = 640.dp.roundToPx()
        var result = precomputedLayout
        if (
            autoDirection &&
            (chart.direction == Direction.LR || chart.direction == Direction.RL) &&
            layoutOptions.directionOverride == null &&
            !holder.lastAdaptedVertical &&
            effectiveMaxWidth in 1 until breakpoint &&
            result.width > effectiveMaxWidth
        ) {
            val scaledOptions = with(layoutOptions) {
                copy(
                    nodeGap = nodeGap * density,
                    layerGap = layerGap * density,
                    padding = padding * density,
                    subgraphPaddingX = subgraphPaddingX * density,
                    subgraphPaddingY = subgraphPaddingY * density,
                    density = density,
                    directionOverride = Direction.TB,
                )
            }
            result = FlowchartLayout.layout(
                chart = chart,
                nodeSize = { holder.nodeSizes[it.id] ?: DiagramSize(0f, 0f) },
                edgeLabelSize = { edge -> holder.edgeLabelSizes[chart.edges.indexOf(edge)] },
                options = scaledOptions,
            )
            holder.lastAdaptedVertical = true
        }
        holder.layout = result
        onLayout(result)

        val edgeCullMargin = 20f * density
        val totalVisibleEdges = result.edges.count { it.edge.stroke != EdgeStroke.Invisible && it.points.size >= 2 }
        val drawnEdges = if (activeCullViewport == null) {
            totalVisibleEdges
        } else {
            result.edges.count {
                it.edge.stroke != EdgeStroke.Invisible &&
                    it.points.size >= 2 &&
                    it.bounds(edgeCullMargin).intersects(activeCullViewport)
            }
        }
        val drawnSubgraphs = if (activeCullViewport == null) {
            result.subgraphs.size
        } else {
            result.subgraphs.count { it.rect.intersects(activeCullViewport) }
        }
        val totalEdgeLabels = chart.edges.count { it.label != null }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = nodePlaceables.size,
                totalNodes = chart.nodes.size,
                composedEdgeLabels = edgeLabelPlaceables.size,
                totalEdgeLabels = totalEdgeLabels,
                drawnEdges = drawnEdges,
                totalEdges = totalVisibleEdges,
                drawnSubgraphs = drawnSubgraphs,
                totalSubgraphs = result.subgraphs.size,
            ),
        )

        layout(result.width.roundToInt(), result.height.roundToInt()) {
            for (positioned in result.nodes) {
                nodePlaceables[positioned.node.id]?.place(
                    positioned.rect.x.roundToInt(),
                    positioned.rect.y.roundToInt(),
                )
            }
            for ((index, positioned) in result.edges.withIndex()) {
                val rect = positioned.labelRect ?: continue
                // The layout engine preserves chart edge order.
                edgeLabelPlaceables[index]?.place(
                    rect.x.roundToInt(),
                    rect.y.roundToInt(),
                )
            }
            for (sg in result.subgraphs) {
                val placeable = subgraphPlaceables[sg.subgraph.id] ?: continue
                placeable.place(
                    (sg.rect.centerX - placeable.width / 2f).roundToInt(),
                    (sg.rect.y + 2.dp.roundToPx()).roundToInt(),
                )
            }
        }
    }
}

private data class PremeasuredFlowchartResult(
    val layout: DiagramLayout,
    val nodeSizes: Map<String, DiagramSize>,
    val edgeLabelSizes: List<DiagramSize?>,
    val adaptedVertical: Boolean,
)

private fun computePremeasuredFlowchartLayout(
    chart: Flowchart,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    autoDirection: Boolean,
    availableWidthPx: Int,
    densityObj: Density,
    textMeasurer: TextMeasurer,
    resolvedImageUrls: Set<String>,
): PremeasuredFlowchartResult {
    val renderDensity = densityObj.density
    val wrapPx = with(densityObj) { NodeSizing.WRAPPING_WIDTH.dp.roundToPx() }
    val maxNodeWidthPx = with(densityObj) { 240.dp.roundToPx() }
    val nodeTextStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.nodeFontSize,
        lineHeight = theme.nodeFontSize * 1.5f,
        textAlign = TextAlign.Center,
    )
    val nodeSizes = HashMap<String, DiagramSize>(chart.nodes.size)
    for (node in chart.nodes) {
        val inlineImages = if (resolvedImageUrls.isNotEmpty()) {
            extractInlineImages(node.label).filter { it.url in resolvedImageUrls }
        } else {
            emptyList()
        }
        val inlineIds = if (inlineImages.isEmpty()) emptySet() else inlineImages.map { it.inlineId }.toSet()
        val wrappedLabel = annotatedLabel(node.label, theme.monospaceFontFamily, inlineIds)
        val placeholderRanges = if (inlineImages.isEmpty()) {
            emptyList()
        } else {
            val byId = inlineImages.associateBy { it.inlineId }
            wrappedLabel.getStringAnnotations(0, wrappedLabel.length).mapNotNull { range ->
                val spec = byId[range.item] ?: return@mapNotNull null
                AnnotatedString.Range(
                    item = Placeholder(
                        width = spec.widthPx.sp,
                        height = spec.heightPx.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                    ),
                    start = range.start,
                    end = range.end,
                )
            }
        }
        val measured = textMeasurer.measure(
            text = wrappedLabel,
            style = nodeTextStyle,
            constraints = Constraints(maxWidth = wrapPx),
            placeholders = placeholderRanges,
            density = densityObj,
        )
        val labelBox = NodeSizing.labelBox(
            node.shape,
            measured.size.width / renderDensity,
            maxOf(measured.lineCount * theme.nodeFontSize.value * 1.5f, measured.size.height / renderDensity),
        )
        val nodeBox = NodeSizing.nodeBox(node.shape, labelBox.width, labelBox.height)
        val wPx = with(densityObj) { nodeBox.width.dp.roundToPx().coerceAtMost(maxNodeWidthPx) }
        val hPx = with(densityObj) { nodeBox.height.dp.roundToPx() }
        nodeSizes[node.id] = DiagramSize(wPx.toFloat(), hPx.toFloat())
    }

    val edgePadH = with(densityObj) { 4.dp.roundToPx() }
    val edgePadV = with(densityObj) { 2.dp.roundToPx() }
    val maxEdgeLabelW = with(densityObj) { 180.dp.roundToPx() }
    val edgeTextStyle = TextStyle(
        color = theme.edgeLabelText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        textAlign = TextAlign.Center,
    )
    val edgeLabelSizes = chart.edges.map { edge ->
        val label = edge.label ?: return@map null
        val m = textMeasurer.measure(
            text = annotatedLabel(label, theme.monospaceFontFamily),
            style = edgeTextStyle,
            constraints = Constraints(maxWidth = (maxEdgeLabelW - edgePadH * 2).coerceAtLeast(1)),
            density = densityObj,
        )
        DiagramSize(
            width = (m.size.width + edgePadH * 2).coerceAtMost(maxEdgeLabelW).toFloat(),
            height = (m.size.height + edgePadV * 2).toFloat(),
        )
    }

    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            padding = padding * renderDensity,
            subgraphPaddingX = subgraphPaddingX * renderDensity,
            subgraphPaddingY = subgraphPaddingY * renderDensity,
            density = renderDensity,
        )
    }
    var result = FlowchartLayout.layout(
        chart = chart,
        nodeSize = { nodeSizes[it.id] ?: DiagramSize(0f, 0f) },
        edgeLabelSize = { edge -> edgeLabelSizes[chart.edges.indexOf(edge)] },
        options = scaledOptions,
    )
    val breakpointPx = with(densityObj) { 640.dp.roundToPx() }
    var adaptedVertical = false
    if (
        autoDirection &&
        (chart.direction == Direction.LR || chart.direction == Direction.RL) &&
        layoutOptions.directionOverride == null &&
        availableWidthPx in 1 until breakpointPx &&
        result.width > availableWidthPx
    ) {
        result = FlowchartLayout.layout(
            chart = chart,
            nodeSize = { nodeSizes[it.id] ?: DiagramSize(0f, 0f) },
            edgeLabelSize = { edge -> edgeLabelSizes[chart.edges.indexOf(edge)] },
            options = scaledOptions.copy(directionOverride = Direction.TB),
        )
        adaptedVertical = true
    }
    return PremeasuredFlowchartResult(
        layout = result,
        nodeSizes = nodeSizes,
        edgeLabelSizes = edgeLabelSizes,
        adaptedVertical = adaptedVertical,
    )
}

private fun plainLabel(chart: Flowchart, id: String): String =
    plainTextLabel(chart.nodes.firstOrNull { it.id == id }?.label ?: id)

private fun connectionDescriptions(chart: Flowchart): Map<String, String> {
    val out = HashMap<String, MutableList<String>>()
    for (node in chart.nodes) out[node.id] = mutableListOf()
    for (edge in chart.edges) {
        if (edge.stroke == EdgeStroke.Invisible) continue
        val labelPart = edge.label?.let { " (${plainTextLabel(it)})" } ?: ""
        val fromLabel = plainLabel(chart, edge.from)
        val toLabel = plainLabel(chart, edge.to)
        when {
            edge.startHead != ArrowHead.None && edge.endHead != ArrowHead.None -> {
                out[edge.from]?.add("connects bidirectionally with $toLabel$labelPart")
                out[edge.to]?.add("connects bidirectionally with $fromLabel$labelPart")
            }
            edge.startHead == ArrowHead.None && edge.endHead == ArrowHead.None -> {
                out[edge.from]?.add("connected with $toLabel$labelPart")
                out[edge.to]?.add("connected with $fromLabel$labelPart")
            }
            edge.startHead != ArrowHead.None && edge.endHead == ArrowHead.None -> {
                out[edge.to]?.add("goes to $fromLabel$labelPart")
                out[edge.from]?.add("comes from $toLabel$labelPart")
            }
            else -> {
                out[edge.from]?.add("goes to $toLabel$labelPart")
                out[edge.to]?.add("comes from $fromLabel$labelPart")
            }
        }
    }
    return out.mapValues { it.value.joinToString("; ") }
}
