package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ClassLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.ErDiagramLayout
import dev.maradona.layout.ErEntityMetrics
import dev.maradona.layout.ErLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.model.Direction
import dev.maradona.model.ErCardinality
import dev.maradona.model.ErDiagram
import dev.maradona.model.ErEntity
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class ErLayoutHolder {
    var layout: ErDiagramLayout? = null
    var naturalLayout: ErDiagramLayout? = null
    var adaptedTbLayout: ErDiagramLayout? = null
    var hdrSizes: Map<String, DiagramSize> = emptyMap()
    var cellSizes: Map<String, DiagramSize> = emptyMap()
    var metricsById: Map<String, ErEntityMetrics> = emptyMap()
    var relLabelSizes: Map<Int, DiagramSize> = emptyMap()
    var sgLabelSizes: Map<String, DiagramSize> = emptyMap()
    var titleSize: DiagramSize? = null
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

/**
 * Custom Compose [Layout] for Mermaid `erDiagram` diagrams.
 *
 * Entity headers, attribute table cells (`type`, `name`, `PK/FK/UK`, `comment`),
 * subgraph titles, and relationship labels are real Composables with semantics;
 * subgraph cluster boxes, entity table grids, relationship splines, and crow's-foot
 * cardinality markers (`||`, `|o`, `|{`, `o{`) are drawn on the underlay canvas.
 */
@Composable
fun MaradonaErLayout(
    diagram: ErDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(nodeGap = 52f, layerGap = 58f, subgraphPaddingX = 28f),
    availableWidthPx: Int = Int.MAX_VALUE,
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (ErDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { ErLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val relDescriptions = remember(diagram) { erRelationshipDescriptions(diagram) }
    val textMeasurer = rememberTextMeasurer()
    val densityObj = LocalDensity.current
    val renderDensity = densityObj.density
    val renderFontScale = densityObj.fontScale
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontResolutionToken = listOf(
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.SemiBold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontStyle = FontStyle.Italic).value,
    )
    val monoFontResolutionToken = fontFamilyResolver.resolve(fontFamily = theme.monospaceFontFamily).value

    var observedBoundedWidthPx by remember(diagram, theme, layoutOptions) { mutableIntStateOf(Int.MAX_VALUE) }
    var boundedWidthObserved by remember(diagram, theme, layoutOptions) { mutableStateOf(false) }
    val effectiveAvailableWidthPx = minOf(availableWidthPx, observedBoundedWidthPx)

    val naturalLayout = run {
        val cachedNatural = holder.naturalLayout
        if (
            cachedNatural != null &&
            abs(holder.lastDensity - renderDensity) < 1e-4f &&
            abs(holder.lastFontScale - renderFontScale) < 1e-4f &&
            holder.lastFontResolutionToken == fontResolutionToken &&
            holder.lastMonospaceFontResolutionToken == monoFontResolutionToken
        ) {
            cachedNatural
        } else {
            computePremeasuredErLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.naturalLayout = pre.naturalLayout
                holder.adaptedTbLayout = null
                holder.hdrSizes = pre.hdrSizes
                holder.cellSizes = pre.cellSizes
                holder.metricsById = pre.metricsById
                holder.relLabelSizes = pre.relLabelSizes
                holder.sgLabelSizes = pre.sgLabelSizes
                holder.titleSize = pre.titleSize
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastFontResolutionToken = fontResolutionToken
                holder.lastMonospaceFontResolutionToken = monoFontResolutionToken
            }.naturalLayout
        }
    }

    fun resolveAdaptedTbLayout(): ErDiagramLayout {
        holder.adaptedTbLayout?.let { return it }
        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                padding = padding * renderDensity,
                subgraphPaddingX = subgraphPaddingX * renderDensity,
                subgraphPaddingY = subgraphPaddingY * renderDensity,
                density = renderDensity,
                directionOverride = Direction.TB,
            )
        }
        return ErLayout.layout(
            diagram = diagram,
            entityBoxSize = { holder.metricsById.getValue(it.id) },
            relationshipLabelSize = { idx, _ -> holder.relLabelSizes[idx] },
            subgraphLabelSize = { sg -> holder.sgLabelSizes[sg.id] ?: DiagramSize(0f, 0f) },
            titleSize = holder.titleSize,
            options = scaledOptions,
        ).also { holder.adaptedTbLayout = it }
    }

    val breakpointPx = with(densityObj) { 640.dp.roundToPx() }
    val wantVerticalInComposition = autoDirection &&
        (diagram.direction == Direction.LR || diagram.direction == Direction.RL) &&
        layoutOptions.directionOverride == null &&
        effectiveAvailableWidthPx in 1 until breakpointPx &&
        naturalLayout.width > effectiveAvailableWidthPx
    val precomputedLayout = if (wantVerticalInComposition) {
        resolveAdaptedTbLayout()
    } else {
        naturalLayout
    }
    holder.layout = precomputedLayout
    val effectiveViewport = visibleViewport
        ?: viewportResolver?.invoke(precomputedLayout.width, precomputedLayout.height)
    val needsParentWidthProbe = autoDirection &&
        (diagram.direction == Direction.LR || diagram.direction == Direction.RL) &&
        layoutOptions.directionOverride == null &&
        availableWidthPx == Int.MAX_VALUE &&
        !boundedWidthObserved
    val activeCullViewport = if (needsParentWidthProbe) null else effectiveViewport
    holder.visibleViewport = activeCullViewport

    val positionedEntityById = remember(precomputedLayout) {
        precomputedLayout.entities.associateBy { it.entity.id }
    }
    val positionedSubgraphById = remember(precomputedLayout) {
        precomputedLayout.subgraphs.associateBy { it.subgraph.id }
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
                    "Entity relationship diagram. ${diagram.entities.size} entities, ${diagram.relationships.size} relationships."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (e in diagram.entities) {
                            val pe = positionedEntityById[e.id] ?: continue
                            if (rectIntersectsViewport(pe.rect, vp, margin = 8f)) continue
                            if (onNodeClick != null) {
                                add(MaradonaNodeClickEvent(nodeId = e.id, label = e.label))
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
                holder.layout?.let { drawErUnderlay(it, theme, holder.visibleViewport) }
            },
        content = {
            if (diagram.title != null) {
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
            for ((eIdx, e) in diagram.entities.withIndex()) {
                if (activeCullViewport != null) {
                    val pe = positionedEntityById[e.id]
                    if (pe != null && !rectIntersectsViewport(pe.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
                }
                val textColor = parseCssColor(e.style?.textColor) ?: theme.nodeText
                val hasKeys = e.attributes.any { it.keys.isNotEmpty() }
                val hasComments = e.attributes.any { !it.comment.isNullOrEmpty() }
                BasicText(
                    text = annotatedLabel(e.label),
                    modifier = Modifier.layoutId("ehdr:${e.id}"),
                    style = TextStyle(
                        color = textColor,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.nodeFontSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                for ((rIdx, attr) in e.attributes.withIndex()) {
                    BasicText(
                        text = attr.type,
                        modifier = Modifier.layoutId("eattr:${e.id}:$rIdx:0"),
                        style = TextStyle(
                            color = textColor,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(11.5f),
                        ),
                    )
                    BasicText(
                        text = attr.name,
                        modifier = Modifier.layoutId("eattr:${e.id}:$rIdx:1"),
                        style = TextStyle(
                            color = textColor,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(11.5f),
                        ),
                    )
                    if (hasKeys) {
                        BasicText(
                            text = attr.keys.joinToString(", "),
                            modifier = Modifier.layoutId("eattr:${e.id}:$rIdx:2"),
                            style = TextStyle(
                                color = textColor,
                                fontFamily = theme.fontFamily,
                                fontSize = theme.captionFontSize,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                    if (hasComments) {
                        val colIdx = if (hasKeys) 3 else 2
                        BasicText(
                            text = annotatedLabel(attr.comment.orEmpty()),
                            modifier = Modifier.layoutId("eattr:${e.id}:$rIdx:$colIdx"),
                            style = TextStyle(
                                color = textColor,
                                fontFamily = theme.fontFamily,
                                fontSize = theme.captionFontSize,
                                fontStyle = FontStyle.Italic,
                            ),
                        )
                    }
                }
                val relDesc = relDescriptions[e.id]
                val fullDesc = if (relDesc.isNullOrBlank()) "Entity ${e.label}" else "Entity ${e.label}. $relDesc"
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = e.id, label = e.label) else null
                Box(
                    modifier = Modifier
                        .layoutId("ebox:${e.id}")
                        .diagramNodeInteraction(
                            baseDescription = fullDesc,
                            traversalOrder = eIdx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }
            for (sg in diagram.subgraphs) {
                if (activeCullViewport != null) {
                    val psg = positionedSubgraphById[sg.id]
                    if (psg != null && !rectIntersectsViewport(psg.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
                }
                val sgTextColor = parseCssColor(sg.style?.textColor) ?: theme.subgraphText
                BasicText(
                    text = annotatedLabel(sg.label),
                    modifier = Modifier.layoutId("sg:${sg.id}"),
                    style = TextStyle(
                        color = sgTextColor,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.subgraphFontSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            for ((idx, rel) in diagram.relationships.withIndex()) {
                if (!rel.label.isNullOrBlank()) {
                    if (activeCullViewport != null) {
                        val lr = precomputedLayout.relationships.getOrNull(idx)?.labelRect
                        if (lr != null && !rectIntersectsViewport(lr, activeCullViewport, margin = 8f)) {
                            continue
                        }
                    }
                    BasicText(
                        text = annotatedLabel(rel.label),
                        modifier = Modifier
                            .layoutId("rellabel:$idx")
                            .background(theme.edgeLabelBackground.copy(alpha = 0.85f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = TextStyle(
                            color = theme.edgeLabelText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()
        var titlePlaceable: Placeable? = null
        val hdrPlaceables = HashMap<String, Placeable>()
        val cellPlaceables = HashMap<String, Placeable>()
        val boxMeasurables = HashMap<String, Measurable>()
        val sgPlaceables = HashMap<String, Placeable>()
        val relLabelPlaceables = HashMap<Int, Placeable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("ehdr:") -> hdrPlaceables[id.removePrefix("ehdr:")] = m.measure(unbounded)
                id.startsWith("eattr:") -> cellPlaceables[id.removePrefix("eattr:")] = m.measure(unbounded)
                id.startsWith("ebox:") -> boxMeasurables[id.removePrefix("ebox:")] = m
                id.startsWith("sg:") -> sgPlaceables[id.removePrefix("sg:")] = m.measure(unbounded)
                id.startsWith("rellabel:") -> relLabelPlaceables[id.removePrefix("rellabel:").toInt()] = m.measure(unbounded)
            }
        }

        val cellPadX = 10f * renderDensity
        val cellPadY = 5f * renderDensity
        val hdrPadX = 14f * renderDensity
        val hdrPadY = 8f * renderDensity

        val effectiveHdrSizes = HashMap<String, DiagramSize>(holder.hdrSizes).apply {
            for ((k, pl) in hdrPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveCellSizes = HashMap<String, DiagramSize>(holder.cellSizes).apply {
            for ((k, pl) in cellPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveRelLabelSizes = HashMap<Int, DiagramSize>(holder.relLabelSizes).apply {
            for ((k, pl) in relLabelPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveSgLabelSizes = HashMap<String, DiagramSize>(holder.sgLabelSizes).apply {
            for ((k, pl) in sgPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize

        fun computeMetrics(e: ErEntity): ErEntityMetrics {
            val hdr = effectiveHdrSizes[e.id]
            val hdrW = (hdr?.width ?: 0f) + hdrPadX * 2f
            val hdrH = max(32f * renderDensity, (hdr?.height ?: 0f) + hdrPadY * 2f)
            if (e.attributes.isEmpty()) {
                return ErEntityMetrics(
                    width = max(96f * renderDensity, hdrW),
                    height = hdrH,
                    headerHeight = hdrH,
                )
            }
            val hasKeys = e.attributes.any { it.keys.isNotEmpty() }
            val hasComments = e.attributes.any { !it.comment.isNullOrEmpty() }
            val colCount = 2 + (if (hasKeys) 1 else 0) + (if (hasComments) 1 else 0)
            val maxColW = FloatArray(colCount)
            val rowHeights = FloatArray(e.attributes.size)
            for (rIdx in e.attributes.indices) {
                var rH = 22f * renderDensity
                for (cIdx in 0 until colCount) {
                    val sz = effectiveCellSizes["${e.id}:$rIdx:$cIdx"]
                    val w = (sz?.width ?: 0f) + cellPadX * 2f
                    val h = (sz?.height ?: 0f) + cellPadY * 2f
                    if (w > maxColW[cIdx]) maxColW[cIdx] = w
                    if (h > rH) rH = h
                }
                rowHeights[rIdx] = rH
            }
            val sumCols = maxColW.sum()
            val totalW = maxOf(108f * renderDensity, hdrW, sumCols)
            val extra = if (totalW > sumCols) (totalW - sumCols) / colCount else 0f
            val finalCols = maxColW.map { it + extra }
            val totalH = hdrH + rowHeights.sum()
            return ErEntityMetrics(
                width = totalW,
                height = totalH,
                headerHeight = hdrH,
                rowHeights = rowHeights.toList(),
                colWidths = finalCols,
            )
        }

        val metricsById = diagram.entities.associate { it.id to computeMetrics(it) }
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

        fun runLayout(opts: LayoutOptions): ErDiagramLayout = ErLayout.layout(
            diagram = diagram,
            entityBoxSize = { metricsById.getValue(it.id) },
            relationshipLabelSize = { idx, _ -> effectiveRelLabelSizes[idx] },
            subgraphLabelSize = { sg -> effectiveSgLabelSizes[sg.id] ?: DiagramSize(0f, 0f) },
            titleSize = effectiveTitleSize,
            options = opts,
        )

        val boundedWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        if (!boundedWidthObserved || observedBoundedWidthPx != boundedWidth) {
            observedBoundedWidthPx = boundedWidth
            boundedWidthObserved = true
        }
        val effectiveMaxWidth = minOf(boundedWidth, availableWidthPx)
        val breakpoint = 640.dp.roundToPx()
        val measuredNatural = if (activeCullViewport == null) runLayout(scaledOptions) else naturalLayout
        val wantVerticalAtMeasure = autoDirection &&
            (diagram.direction == Direction.LR || diagram.direction == Direction.RL) &&
            layoutOptions.directionOverride == null &&
            effectiveMaxWidth in 1 until breakpoint &&
            measuredNatural.width > effectiveMaxWidth

        val result = if (activeCullViewport != null && wantVerticalAtMeasure != wantVerticalInComposition) {
            precomputedLayout
        } else if (wantVerticalAtMeasure) {
            if (activeCullViewport == null) {
                runLayout(scaledOptions.copy(directionOverride = Direction.TB)).also {
                    holder.adaptedTbLayout = it
                }
            } else {
                resolveAdaptedTbLayout()
            }
        } else {
            measuredNatural
        }
        if (activeCullViewport == null) {
            holder.hdrSizes = effectiveHdrSizes
            holder.cellSizes = effectiveCellSizes
            holder.metricsById = metricsById
            holder.relLabelSizes = effectiveRelLabelSizes
            holder.sgLabelSizes = effectiveSgLabelSizes
            holder.titleSize = effectiveTitleSize
            holder.naturalLayout = measuredNatural
        }
        holder.layout = result
        onLayout(result)

        val boxPlaceables = HashMap<String, Placeable>()
        for (pe in result.entities) {
            boxMeasurables[pe.entity.id]?.let { m ->
                boxPlaceables[pe.entity.id] = m.measure(
                    Constraints.fixed(pe.rect.width.roundToInt(), pe.rect.height.roundToInt()),
                )
            }
        }

        val edgeMargin = 20f * renderDensity
        val drawnEdges = if (activeCullViewport == null) {
            result.relationships.size
        } else {
            result.relationships.count { rel ->
                polylineIntersectsViewport(rel.points, activeCullViewport, margin = edgeMargin)
            }
        }
        val drawnSubgraphs = if (activeCullViewport == null) {
            result.subgraphs.size
        } else {
            result.subgraphs.count { rectIntersectsViewport(it.rect, activeCullViewport, margin = 8f) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = boxPlaceables.size,
                totalNodes = diagram.entities.size,
                composedEdgeLabels = relLabelPlaceables.size,
                totalEdgeLabels = diagram.relationships.count { !it.label.isNullOrBlank() },
                drawnEdges = drawnEdges,
                totalEdges = result.relationships.size,
                drawnSubgraphs = drawnSubgraphs,
                totalSubgraphs = result.subgraphs.size,
            ),
        )

        layout(result.width.roundToInt(), result.height.roundToInt()) {
            result.titleRect?.let { r ->
                titlePlaceable?.place(r.x.roundToInt(), r.y.roundToInt())
            }
            for (psg in result.subgraphs) {
                val pl = sgPlaceables[psg.subgraph.id] ?: continue
                pl.place(
                    (psg.rect.centerX - pl.width / 2f).roundToInt(),
                    (psg.rect.y + (psg.headerHeight - pl.height) / 2f).roundToInt(),
                )
            }
            for (pe in result.entities) {
                val id = pe.entity.id
                val r = pe.rect
                boxPlaceables[id]?.place(r.x.roundToInt(), r.y.roundToInt())
                hdrPlaceables[id]?.let { pl ->
                    pl.place(
                        (r.centerX - pl.width / 2f).roundToInt(),
                        (r.y + (pe.headerHeight - pl.height) / 2f).roundToInt(),
                    )
                }
                var rowTop = r.y + pe.headerHeight
                for (rIdx in pe.rowHeights.indices) {
                    val rH = pe.rowHeights[rIdx]
                    var colLeft = r.x
                    for (cIdx in pe.colWidths.indices) {
                        val cW = pe.colWidths[cIdx]
                        cellPlaceables["$id:$rIdx:$cIdx"]?.let { pl ->
                            pl.place(
                                (colLeft + cellPadX).roundToInt(),
                                (rowTop + (rH - pl.height) / 2f).roundToInt(),
                            )
                        }
                        colLeft += cW
                    }
                    rowTop += rH
                }
            }
            for (rel in result.relationships) {
                rel.labelRect?.let { r ->
                    relLabelPlaceables[rel.index]?.place(r.x.roundToInt(), r.y.roundToInt())
                }
            }
        }
    }
}

private data class PremeasuredErResult(
    val naturalLayout: ErDiagramLayout,
    val hdrSizes: Map<String, DiagramSize>,
    val cellSizes: Map<String, DiagramSize>,
    val metricsById: Map<String, ErEntityMetrics>,
    val relLabelSizes: Map<Int, DiagramSize>,
    val sgLabelSizes: Map<String, DiagramSize>,
    val titleSize: DiagramSize?,
)

private fun computePremeasuredErLayout(
    diagram: ErDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredErResult {
    val renderDensity = densityObj.density
    val cellPadX = 10f * renderDensity
    val cellPadY = 5f * renderDensity
    val hdrPadX = 14f * renderDensity
    val hdrPadY = 8f * renderDensity

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

    val hdrSizes = HashMap<String, DiagramSize>(diagram.entities.size)
    val cellSizes = HashMap<String, DiagramSize>()
    val metricsById = HashMap<String, ErEntityMetrics>(diagram.entities.size)

    for (e in diagram.entities) {
        val textColor = parseCssColor(e.style?.textColor) ?: theme.nodeText
        val hasKeys = e.attributes.any { it.keys.isNotEmpty() }
        val hasComments = e.attributes.any { !it.comment.isNullOrEmpty() }
        val hdrStyle = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = theme.nodeFontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        val hdrSz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(e.label),
            style = hdrStyle,
        )
        hdrSizes[e.id] = hdrSz
        val hdrW = hdrSz.width + hdrPadX * 2f
        val hdrH = max(32f * renderDensity, hdrSz.height + hdrPadY * 2f)
        if (e.attributes.isEmpty()) {
            metricsById[e.id] = ErEntityMetrics(
                width = max(96f * renderDensity, hdrW),
                height = hdrH,
                headerHeight = hdrH,
            )
            continue
        }
        val cellRegularStyle = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = theme.scaledSp(11.5f),
        )
        val cellKeyStyle = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = theme.captionFontSize,
            fontWeight = FontWeight.SemiBold,
        )
        val cellCommentStyle = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = theme.captionFontSize,
            fontStyle = FontStyle.Italic,
        )
        val colCount = 2 + (if (hasKeys) 1 else 0) + (if (hasComments) 1 else 0)
        val maxColW = FloatArray(colCount)
        val rowHeights = FloatArray(e.attributes.size)
        for ((rIdx, attr) in e.attributes.withIndex()) {
            val sz0 = measurePlainTextSize(textMeasurer, densityObj, attr.type, cellRegularStyle)
            val sz1 = measurePlainTextSize(textMeasurer, densityObj, attr.name, cellRegularStyle)
            cellSizes["${e.id}:$rIdx:0"] = sz0
            cellSizes["${e.id}:$rIdx:1"] = sz1
            if (hasKeys) {
                val sz2 = measurePlainTextSize(textMeasurer, densityObj, attr.keys.joinToString(", "), cellKeyStyle)
                cellSizes["${e.id}:$rIdx:2"] = sz2
            }
            if (hasComments) {
                val colIdx = if (hasKeys) 3 else 2
                val szC = measureAnnotatedTextSize(textMeasurer, densityObj, annotatedLabel(attr.comment.orEmpty()), cellCommentStyle)
                cellSizes["${e.id}:$rIdx:$colIdx"] = szC
            }
            var rH = 22f * renderDensity
            for (cIdx in 0 until colCount) {
                val sz = cellSizes["${e.id}:$rIdx:$cIdx"]
                val w = (sz?.width ?: 0f) + cellPadX * 2f
                val h = (sz?.height ?: 0f) + cellPadY * 2f
                if (w > maxColW[cIdx]) maxColW[cIdx] = w
                if (h > rH) rH = h
            }
            rowHeights[rIdx] = rH
        }
        val sumCols = maxColW.sum()
        val totalW = maxOf(108f * renderDensity, hdrW, sumCols)
        val extra = if (totalW > sumCols) (totalW - sumCols) / colCount else 0f
        val finalCols = maxColW.map { it + extra }
        val totalH = hdrH + rowHeights.sum()
        metricsById[e.id] = ErEntityMetrics(
            width = totalW,
            height = totalH,
            headerHeight = hdrH,
            rowHeights = rowHeights.toList(),
            colWidths = finalCols,
        )
    }

    val sgLabelSizes = diagram.subgraphs.associate { sg ->
        val sgTextColor = parseCssColor(sg.style?.textColor) ?: theme.subgraphText
        sg.id to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(sg.label),
            style = TextStyle(
                color = sgTextColor,
                fontFamily = theme.fontFamily,
                fontSize = theme.subgraphFontSize,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }

    val relPadH = with(densityObj) { 4.dp.roundToPx() * 2 }
    val relPadV = with(densityObj) { 2.dp.roundToPx() * 2 }
    val relStyle = TextStyle(
        color = theme.edgeLabelText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        textAlign = TextAlign.Center,
    )
    val relLabelSizes = HashMap<Int, DiagramSize>()
    for ((idx, rel) in diagram.relationships.withIndex()) {
        if (!rel.label.isNullOrBlank()) {
            val sz = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(rel.label),
                style = relStyle,
            )
            relLabelSizes[idx] = DiagramSize(sz.width + relPadH, sz.height + relPadV)
        }
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
    val naturalLayout = ErLayout.layout(
        diagram = diagram,
        entityBoxSize = { metricsById.getValue(it.id) },
        relationshipLabelSize = { idx, _ -> relLabelSizes[idx] },
        subgraphLabelSize = { sg -> sgLabelSizes[sg.id] ?: DiagramSize(0f, 0f) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredErResult(
        naturalLayout = naturalLayout,
        hdrSizes = hdrSizes,
        cellSizes = cellSizes,
        metricsById = metricsById,
        relLabelSizes = relLabelSizes,
        sgLabelSizes = sgLabelSizes,
        titleSize = titleSize,
    )
}

private fun erRelationshipDescriptions(diagram: ErDiagram): Map<String, String> {
    val labelById = diagram.entities.associate { it.id to it.label }
    val out = HashMap<String, MutableList<String>>()
    for (e in diagram.entities) out[e.id] = mutableListOf()
    for (rel in diagram.relationships) {
        val lbl = rel.label?.let { " ($it)" } ?: ""
        val toName = labelById[rel.to] ?: rel.to
        val fromName = labelById[rel.from] ?: rel.from
        out[rel.from]?.add("relates to $toName$lbl")
        out[rel.to]?.add("related from $fromName$lbl")
    }
    return out.mapValues { it.value.joinToString("; ") }
}

private fun DrawScope.drawErUnderlay(
    layout: ErDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val cullMargin = 24f * unit
    val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)

    // 1. Subgraph cluster boxes (parent-first).
    for (psg in layout.subgraphs) {
        val r = psg.rect
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
        val fill = parseCssColor(psg.subgraph.style?.fill) ?: theme.subgraphFill
        val border = parseCssColor(psg.subgraph.style?.stroke) ?: theme.subgraphBorder
        val strokeW = (psg.subgraph.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1f) * unit
        drawRect(
            color = fill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = border,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = strokeW),
        )
    }

    // 2. Relationships and crow's-foot cardinality markers.
    for (pr in layout.relationships) {
        val pts = pr.points
        if (pts.size < 2) continue
        if (!polylineIntersectsViewport(pts, viewport, margin = cullMargin)) continue
        val rel = pr.relationship
        val samples = ClassLayout.basisSplineSamples(pts)
        val splineHitsObstacle = (0 until samples.lastIndex).any { i ->
            val a = samples[i]
            val b = samples[i + 1]
            layout.entities.any { pe ->
                pe.entity.id != rel.from && pe.entity.id != rel.to &&
                    ClassLayout.segmentIntersectsRect(a, b, pe.rect)
            } || layout.subgraphs.any { psg ->
                psg.subgraph.id != rel.from && psg.subgraph.id != rel.to &&
                    psg.headerRect.width > 0f && psg.headerRect.height > 0f &&
                    ClassLayout.segmentIntersectsRect(a, b, psg.headerRect)
            }
        }
        val path = if (!splineHitsObstacle) {
            basisSpline(pts)
        } else {
            Path().apply {
                moveTo(pts.first().x, pts.first().y)
                for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y)
            }
        }
        drawPath(
            path = path,
            color = theme.edge,
            style = Stroke(
                width = 1.25f * unit,
                pathEffect = if (rel.identifying) null else dashedEffect,
            ),
        )
        val startToward = pts.drop(1).firstOrNull { it != pts.first() } ?: pts[1]
        val endToward = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
        drawErCardinalityMarker(
            cardinality = rel.fromCardinality,
            tip = pts.first(),
            toward = startToward,
            layout = layout,
            theme = theme,
            unit = unit,
        )
        drawErCardinalityMarker(
            cardinality = rel.toCardinality,
            tip = pts.last(),
            toward = endToward,
            layout = layout,
            theme = theme,
            unit = unit,
        )
    }

    // 3. Entity boxes and attribute table grid lines.
    for (pe in layout.entities) {
        val r = pe.rect
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
        val e = pe.entity
        val fill = parseCssColor(e.style?.fill) ?: theme.nodeFill
        val border = parseCssColor(e.style?.stroke) ?: theme.nodeBorder
        val strokeW = (e.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1.25f) * unit
        drawRect(
            color = fill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        if (pe.rowHeights.isNotEmpty()) {
            // Subtle header shading
            drawRect(
                color = theme.subgraphFill.copy(alpha = 0.55f),
                topLeft = Offset(r.x, r.y),
                size = Size(r.width, pe.headerHeight),
            )
            // Alternating row shading and horizontal row dividers
            var rowY = r.y + pe.headerHeight
            drawLine(
                color = border,
                start = Offset(r.x, rowY),
                end = Offset(r.right, rowY),
                strokeWidth = 1f * unit,
            )
            for ((rIdx, rH) in pe.rowHeights.withIndex()) {
                if (rIdx % 2 == 1) {
                    drawRect(
                        color = theme.subgraphFill.copy(alpha = 0.25f),
                        topLeft = Offset(r.x, rowY),
                        size = Size(r.width, rH),
                    )
                }
                rowY += rH
                if (rIdx < pe.rowHeights.lastIndex) {
                    drawLine(
                        color = border.copy(alpha = 0.65f),
                        start = Offset(r.x, rowY),
                        end = Offset(r.right, rowY),
                        strokeWidth = 0.75f * unit,
                    )
                }
            }
            // Vertical column dividers inside the attribute section
            var colX = r.x
            for (cIdx in 0 until pe.colWidths.lastIndex) {
                colX += pe.colWidths[cIdx]
                drawLine(
                    color = border.copy(alpha = 0.65f),
                    start = Offset(colX, r.y + pe.headerHeight),
                    end = Offset(colX, r.bottom),
                    strokeWidth = 0.75f * unit,
                )
            }
        }
        drawRect(
            color = border,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = strokeW),
        )
    }
}

private fun DrawScope.drawErCardinalityMarker(
    cardinality: ErCardinality,
    tip: DiagramPoint,
    toward: DiagramPoint,
    layout: ErDiagramLayout,
    theme: MaradonaTheme,
    unit: Float,
) {
    val angle = atan2(toward.y - tip.y, toward.x - tip.x)
    val ux = cos(angle)
    val uy = sin(angle)
    val px = -uy
    val py = ux
    val halfTick = 5.5f * unit
    val strokeW = 1.35f * unit

    fun drawTick(dist: Float) {
        val cx = tip.x + ux * dist
        val cy = tip.y + uy * dist
        drawLine(
            color = theme.edge,
            start = Offset(cx + px * halfTick, cy + py * halfTick),
            end = Offset(cx - px * halfTick, cy - py * halfTick),
            strokeWidth = strokeW,
        )
    }

    fun drawCrowsFoot(forkDist: Float) {
        val fx = tip.x + ux * forkDist
        val fy = tip.y + uy * forkDist
        drawLine(
            color = theme.edge,
            start = Offset(fx, fy),
            end = Offset(tip.x + px * halfTick, tip.y + py * halfTick),
            strokeWidth = strokeW,
        )
        drawLine(
            color = theme.edge,
            start = Offset(fx, fy),
            end = Offset(tip.x - px * halfTick, tip.y - py * halfTick),
            strokeWidth = strokeW,
        )
    }

    fun drawZeroCircle(centerDist: Float) {
        val r = 4f * unit
        val cx = tip.x + ux * centerDist
        val cy = tip.y + uy * centerDist
        val center = Offset(cx, cy)
        val enclosingSg = layout.subgraphs.lastOrNull {
            cx in it.rect.x..it.rect.right && cy in it.rect.y..it.rect.bottom
        }
        val bgColor = if (enclosingSg != null) {
            parseCssColor(enclosingSg.subgraph.style?.fill) ?: theme.subgraphFill
        } else {
            theme.background
        }
        drawCircle(color = bgColor, radius = r, center = center)
        drawCircle(color = theme.edge, radius = r, center = center, style = Stroke(width = strokeW))
    }

    when (cardinality) {
        ErCardinality.ExactlyOne -> {
            drawTick(5f * unit)
            drawTick(10f * unit)
        }
        ErCardinality.ZeroOrOne -> {
            drawTick(5f * unit)
            drawZeroCircle(13f * unit)
        }
        ErCardinality.OneOrMore -> {
            drawCrowsFoot(11f * unit)
            drawTick(12f * unit)
        }
        ErCardinality.ZeroOrMore -> {
            drawCrowsFoot(11f * unit)
            drawZeroCircle(16f * unit)
        }
    }
}

internal fun measureErLayoutForExport(
    diagram: ErDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): ErDiagramLayout = computePremeasuredErLayout(
    diagram = diagram,
    theme = theme,
    layoutOptions = layoutOptions,
    densityObj = densityObj,
    textMeasurer = textMeasurer,
).naturalLayout

