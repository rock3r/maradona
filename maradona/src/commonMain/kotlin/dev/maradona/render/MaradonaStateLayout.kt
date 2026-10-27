package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.StateBoxMetrics
import dev.maradona.layout.StateDiagramLayout
import dev.maradona.layout.StateLayout
import dev.maradona.model.Direction
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.StateDiagram
import dev.maradona.model.StateKind
import dev.maradona.model.StateNode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class StateLayoutHolder {
    var layout: StateDiagramLayout? = null
    var naturalLayout: StateDiagramLayout? = null
    var adaptedTbLayout: StateDiagramLayout? = null
    var hdrSizes: Map<String, DiagramSize> = emptyMap()
    var descSizes: Map<String, DiagramSize> = emptyMap()
    var metricsById: Map<String, StateBoxMetrics> = emptyMap()
    var trLabelSizes: Map<Int, DiagramSize> = emptyMap()
    var noteSizes: Map<Int, DiagramSize> = emptyMap()
    var titleSize: DiagramSize? = null
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

/**
 * Custom Compose [Layout] for Mermaid `stateDiagram` and `stateDiagram-v2` diagrams.
 *
 * State headers, state description compartments, transition labels, and notes are
 * real Composables with semantics; composite state containers, region dividers,
 * start/end/choice/fork/join pseudostates, rounded state boxes, note boxes, and
 * transition splines are drawn on the underlay canvas.
 */
@Composable
fun MaradonaStateLayout(
    diagram: StateDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(nodeGap = 44f, layerGap = 48f),
    availableWidthPx: Int = Int.MAX_VALUE,
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (StateDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { StateLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val transitionDescriptions = remember(diagram) { stateTransitionDescriptions(diagram) }
    val compositeIds = remember(diagram) {
        diagram.states
            .filter { s ->
                s.kind == StateKind.Composite ||
                    diagram.states.any { it.parentId == s.id } ||
                    diagram.notes.any { it.parentId == s.id }
            }
            .map { it.id }
            .toSet()
    }
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
            computePremeasuredStateLayout(
                diagram = diagram,
                compositeIds = compositeIds,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.naturalLayout = pre.naturalLayout
                holder.adaptedTbLayout = null
                holder.hdrSizes = pre.hdrSizes
                holder.descSizes = pre.descSizes
                holder.metricsById = pre.metricsById
                holder.trLabelSizes = pre.trLabelSizes
                holder.noteSizes = pre.noteSizes
                holder.titleSize = pre.titleSize
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastFontResolutionToken = fontResolutionToken
                holder.lastMonospaceFontResolutionToken = monoFontResolutionToken
            }.naturalLayout
        }
    }

    fun resolveAdaptedTbLayout(): StateDiagramLayout {
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
        return StateLayout.layout(
            diagram = diagram,
            stateBoxSize = { holder.metricsById.getValue(it.id) },
            transitionLabelSize = { idx, _ -> holder.trLabelSizes[idx] },
            noteBoxSize = { idx, _ -> holder.noteSizes[idx] ?: DiagramSize(0f, 0f) },
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

    val positionedStateById = remember(precomputedLayout) {
        precomputedLayout.states.associateBy { it.state.id }
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
                    "State diagram. ${diagram.states.size} states, ${diagram.transitions.size} transitions."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (s in diagram.states) {
                            val ps = positionedStateById[s.id] ?: continue
                            if (rectIntersectsViewport(ps.rect, vp, margin = 8f)) continue
                            if (onNodeClick != null) {
                                add(MaradonaNodeClickEvent(nodeId = s.id, label = s.label))
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
                holder.layout?.let { drawStateUnderlay(it, compositeIds, theme, holder.visibleViewport) }
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
            for ((sIdx, s) in diagram.states.withIndex()) {
                if (activeCullViewport != null) {
                    val ps = positionedStateById[s.id]
                    if (ps != null && !rectIntersectsViewport(ps.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
                }
                val isComp = s.id in compositeIds
                val textColor = parseCssColor(s.style?.textColor)
                    ?: if (isComp) theme.subgraphText else theme.nodeText
                if (s.kind == StateKind.Normal || isComp) {
                    BasicText(
                        text = annotatedLabel(s.label),
                        modifier = Modifier.layoutId("shdr:${s.id}"),
                        style = TextStyle(
                            color = textColor,
                            fontFamily = theme.fontFamily,
                            fontSize = if (isComp) theme.subgraphFontSize else theme.nodeFontSize,
                            fontWeight = if (isComp || s.descriptions.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    if (!isComp && s.descriptions.isNotEmpty()) {
                        Column(
                            modifier = Modifier.layoutId("sdesc:${s.id}"),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            for (desc in s.descriptions) {
                                BasicText(
                                    text = annotatedLabel(desc),
                                    style = TextStyle(
                                        color = textColor,
                                        fontFamily = theme.fontFamily,
                                        fontSize = theme.edgeLabelFontSize,
                                        textAlign = TextAlign.Start,
                                    ),
                                )
                            }
                        }
                    }
                }
                val baseTitle = when {
                    s.kind == StateKind.Start -> "Start state"
                    s.kind == StateKind.End -> "End state"
                    s.kind == StateKind.Choice -> "Choice state ${s.id}"
                    s.kind == StateKind.Fork -> "Fork state ${s.id}"
                    s.kind == StateKind.Join -> "Join state ${s.id}"
                    isComp -> "Composite state ${s.label}"
                    else -> "State ${s.label}"
                }
                val trDesc = transitionDescriptions[s.id]
                val fullDesc = if (trDesc.isNullOrBlank()) baseTitle else "$baseTitle. $trDesc"
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = s.id, label = s.label) else null
                Box(
                    modifier = Modifier
                        .layoutId("sbox:${s.id}")
                        .diagramNodeInteraction(
                            baseDescription = fullDesc,
                            traversalOrder = sIdx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }
            for ((idx, note) in diagram.notes.withIndex()) {
                if (activeCullViewport != null) {
                    val pn = precomputedLayout.notes.getOrNull(idx)
                    if (pn != null && !rectIntersectsViewport(pn.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
                }
                Box(
                    modifier = Modifier
                        .layoutId("note:$idx")
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = "Note: ${note.text}"
                        },
                ) {
                    BasicText(
                        text = annotatedLabel(note.text),
                        style = TextStyle(
                            color = theme.noteText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            textAlign = TextAlign.Start,
                        ),
                    )
                }
            }
            for ((idx, tr) in diagram.transitions.withIndex()) {
                if (!tr.label.isNullOrBlank()) {
                    if (activeCullViewport != null) {
                        val lr = precomputedLayout.transitions.getOrNull(idx)?.labelRect
                        if (lr != null && !rectIntersectsViewport(lr, activeCullViewport, margin = 8f)) {
                            continue
                        }
                    }
                    BasicText(
                        text = annotatedLabel(tr.label),
                        modifier = Modifier
                            .layoutId("trlabel:$idx")
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
        val descPlaceables = HashMap<String, Placeable>()
        val boxMeasurables = HashMap<String, Measurable>()
        val notePlaceables = HashMap<Int, Placeable>()
        val trLabelPlaceables = HashMap<Int, Placeable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("shdr:") -> hdrPlaceables[id.removePrefix("shdr:")] = m.measure(unbounded)
                id.startsWith("sdesc:") -> descPlaceables[id.removePrefix("sdesc:")] = m.measure(unbounded)
                id.startsWith("sbox:") -> boxMeasurables[id.removePrefix("sbox:")] = m
                id.startsWith("note:") -> notePlaceables[id.removePrefix("note:").toInt()] = m.measure(unbounded)
                id.startsWith("trlabel:") -> trLabelPlaceables[id.removePrefix("trlabel:").toInt()] = m.measure(unbounded)
            }
        }

        val padX = 14f * renderDensity
        val padY = 8f * renderDensity

        val effectiveHdrSizes = HashMap<String, DiagramSize>(holder.hdrSizes).apply {
            for ((k, pl) in hdrPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveDescSizes = HashMap<String, DiagramSize>(holder.descSizes).apply {
            for ((k, pl) in descPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveTrLabelSizes = HashMap<Int, DiagramSize>(holder.trLabelSizes).apply {
            for ((k, pl) in trLabelPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveNoteSizes = HashMap<Int, DiagramSize>(holder.noteSizes).apply {
            for ((k, pl) in notePlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize

        fun computeMetrics(s: StateNode): StateBoxMetrics {
            val isComp = s.id in compositeIds
            if (!isComp && s.kind != StateKind.Normal) {
                return StateLayout.defaultStateMetrics(s, renderDensity)
            }
            val hdr = effectiveHdrSizes[s.id]
            val desc = effectiveDescSizes[s.id]
            val hdrW = hdr?.width ?: 0f
            val hdrH = hdr?.height ?: 0f
            val descW = desc?.width ?: 0f
            val descH = desc?.height ?: 0f
            val headerHeight = max(28f * renderDensity, hdrH + padY * 2f)
            if (isComp || desc == null) {
                val width = max(84f * renderDensity, hdrW + padX * 2f)
                return StateBoxMetrics(width = width, height = headerHeight, headerHeight = headerHeight, descriptionHeight = 0f)
            }
            val descBoxH = descH + padY * 1.5f
            val width = maxOf(84f * renderDensity, hdrW + padX * 2f, descW + padX * 2f)
            return StateBoxMetrics(
                width = width,
                height = headerHeight + descBoxH,
                headerHeight = headerHeight,
                descriptionHeight = descBoxH,
            )
        }

        val metricsById = diagram.states.associate { it.id to computeMetrics(it) }
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

        fun runLayout(opts: LayoutOptions): StateDiagramLayout = StateLayout.layout(
            diagram = diagram,
            stateBoxSize = { metricsById.getValue(it.id) },
            transitionLabelSize = { idx, _ -> effectiveTrLabelSizes[idx] },
            noteBoxSize = { idx, _ -> effectiveNoteSizes[idx] ?: DiagramSize(0f, 0f) },
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
            holder.descSizes = effectiveDescSizes
            holder.metricsById = metricsById
            holder.trLabelSizes = effectiveTrLabelSizes
            holder.noteSizes = effectiveNoteSizes
            holder.titleSize = effectiveTitleSize
            holder.naturalLayout = measuredNatural
        }
        holder.layout = result
        onLayout(result)

        val boxPlaceables = HashMap<String, Placeable>()
        for (ps in result.states) {
            boxMeasurables[ps.state.id]?.let { m ->
                boxPlaceables[ps.state.id] = m.measure(
                    Constraints.fixed(ps.rect.width.roundToInt(), ps.rect.height.roundToInt()),
                )
            }
        }

        val edgeMargin = 20f * renderDensity
        val drawnEdges = if (activeCullViewport == null) {
            result.transitions.size
        } else {
            result.transitions.count { tr ->
                polylineIntersectsViewport(tr.points, activeCullViewport, margin = edgeMargin)
            }
        }
        val compositeStates = result.states.filter { it.state.id in compositeIds }
        val drawnSubgraphs = if (activeCullViewport == null) {
            compositeStates.size
        } else {
            compositeStates.count { rectIntersectsViewport(it.rect, activeCullViewport, margin = 8f) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = boxPlaceables.size,
                totalNodes = result.states.size,
                composedEdgeLabels = trLabelPlaceables.size,
                totalEdgeLabels = diagram.transitions.count { !it.label.isNullOrBlank() },
                drawnEdges = drawnEdges,
                totalEdges = result.transitions.size,
                drawnSubgraphs = drawnSubgraphs,
                totalSubgraphs = compositeStates.size,
            ),
        )

        layout(result.width.roundToInt(), result.height.roundToInt()) {
            result.titleRect?.let { r ->
                titlePlaceable?.place(r.x.roundToInt(), r.y.roundToInt())
            }
            for (ps in result.states) {
                val id = ps.state.id
                val r = ps.rect
                boxPlaceables[id]?.place(r.x.roundToInt(), r.y.roundToInt())
                hdrPlaceables[id]?.let { pl ->
                    pl.place(
                        (r.centerX - pl.width / 2f).roundToInt(),
                        (r.y + (ps.headerHeight - pl.height) / 2f).roundToInt(),
                    )
                }
                descPlaceables[id]?.let { pl ->
                    pl.place(
                        (r.x + padX).roundToInt(),
                        (r.y + ps.headerHeight + padY * 0.75f).roundToInt(),
                    )
                }
            }
            for (note in result.notes) {
                notePlaceables[note.index]?.place(
                    note.rect.x.roundToInt(),
                    note.rect.y.roundToInt(),
                )
            }
            for (tr in result.transitions) {
                tr.labelRect?.let { r ->
                    trLabelPlaceables[tr.index]?.place(r.x.roundToInt(), r.y.roundToInt())
                }
            }
        }
    }
}

private data class PremeasuredStateResult(
    val naturalLayout: StateDiagramLayout,
    val hdrSizes: Map<String, DiagramSize>,
    val descSizes: Map<String, DiagramSize>,
    val metricsById: Map<String, StateBoxMetrics>,
    val trLabelSizes: Map<Int, DiagramSize>,
    val noteSizes: Map<Int, DiagramSize>,
    val titleSize: DiagramSize?,
)

private fun computePremeasuredStateLayout(
    diagram: StateDiagram,
    compositeIds: Set<String>,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredStateResult {
    val renderDensity = densityObj.density
    val padX = 14f * renderDensity
    val padY = 8f * renderDensity
    val descGap = with(densityObj) { 3.dp.roundToPx().toFloat() }

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

    val hdrSizes = HashMap<String, DiagramSize>(diagram.states.size)
    val descSizes = HashMap<String, DiagramSize>(diagram.states.size)
    val metricsById = HashMap<String, StateBoxMetrics>(diagram.states.size)

    for (s in diagram.states) {
        val isComp = s.id in compositeIds
        if (!isComp && s.kind != StateKind.Normal) {
            metricsById[s.id] = StateLayout.defaultStateMetrics(s, renderDensity)
            continue
        }
        val textColor = parseCssColor(s.style?.textColor)
            ?: if (isComp) theme.subgraphText else theme.nodeText
        val hdrStyle = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = if (isComp) theme.subgraphFontSize else theme.nodeFontSize,
            fontWeight = if (isComp || s.descriptions.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
        val hdrSz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(s.label),
            style = hdrStyle,
        )
        hdrSizes[s.id] = hdrSz

        val descSz = if (!isComp && s.descriptions.isNotEmpty()) {
            val descStyle = TextStyle(
                color = textColor,
                fontFamily = theme.fontFamily,
                fontSize = theme.edgeLabelFontSize,
                textAlign = TextAlign.Start,
            )
            var maxW = 0f
            var sumH = 0f
            for ((i, desc) in s.descriptions.withIndex()) {
                val sz = measureAnnotatedTextSize(
                    textMeasurer = textMeasurer,
                    densityObj = densityObj,
                    text = annotatedLabel(desc),
                    style = descStyle,
                )
                if (sz.width > maxW) maxW = sz.width
                sumH += sz.height
                if (i > 0) sumH += descGap
            }
            DiagramSize(maxW, sumH).also { descSizes[s.id] = it }
        } else null

        val headerHeight = max(28f * renderDensity, hdrSz.height + padY * 2f)
        metricsById[s.id] = if (isComp || descSz == null) {
            val width = max(84f * renderDensity, hdrSz.width + padX * 2f)
            StateBoxMetrics(width = width, height = headerHeight, headerHeight = headerHeight, descriptionHeight = 0f)
        } else {
            val descBoxH = descSz.height + padY * 1.5f
            val width = maxOf(84f * renderDensity, hdrSz.width + padX * 2f, descSz.width + padX * 2f)
            StateBoxMetrics(
                width = width,
                height = headerHeight + descBoxH,
                headerHeight = headerHeight,
                descriptionHeight = descBoxH,
            )
        }
    }

    val notePadH = with(densityObj) { 10.dp.roundToPx() * 2 }
    val notePadV = with(densityObj) { 6.dp.roundToPx() * 2 }
    val noteStyle = TextStyle(
        color = theme.noteText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        textAlign = TextAlign.Start,
    )
    val noteSizes = diagram.notes.mapIndexed { idx, note ->
        val sz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(note.text),
            style = noteStyle,
        )
        idx to DiagramSize(sz.width + notePadH, sz.height + notePadV)
    }.toMap()

    val trPadH = with(densityObj) { 4.dp.roundToPx() * 2 }
    val trPadV = with(densityObj) { 2.dp.roundToPx() * 2 }
    val trStyle = TextStyle(
        color = theme.edgeLabelText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        textAlign = TextAlign.Center,
    )
    val trLabelSizes = HashMap<Int, DiagramSize>()
    for ((idx, tr) in diagram.transitions.withIndex()) {
        if (!tr.label.isNullOrBlank()) {
            val sz = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(tr.label),
                style = trStyle,
            )
            trLabelSizes[idx] = DiagramSize(sz.width + trPadH, sz.height + trPadV)
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
    val naturalLayout = StateLayout.layout(
        diagram = diagram,
        stateBoxSize = { metricsById.getValue(it.id) },
        transitionLabelSize = { idx, _ -> trLabelSizes[idx] },
        noteBoxSize = { idx, _ -> noteSizes[idx] ?: DiagramSize(0f, 0f) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredStateResult(
        naturalLayout = naturalLayout,
        hdrSizes = hdrSizes,
        descSizes = descSizes,
        metricsById = metricsById,
        trLabelSizes = trLabelSizes,
        noteSizes = noteSizes,
        titleSize = titleSize,
    )
}

private fun stateTransitionDescriptions(diagram: StateDiagram): Map<String, String> {
    val labelById = diagram.states.associate { s ->
        s.id to when (s.kind) {
            StateKind.Start -> "Start"
            StateKind.End -> "End"
            else -> s.label
        }
    }
    val out = HashMap<String, MutableList<String>>()
    for (s in diagram.states) out[s.id] = mutableListOf()
    for (tr in diagram.transitions) {
        val lbl = tr.label?.let { " ($it)" } ?: ""
        val toName = labelById[tr.to] ?: tr.to
        val fromName = labelById[tr.from] ?: tr.from
        out[tr.from]?.add("transitions to $toName$lbl")
        out[tr.to]?.add("transition from $fromName$lbl")
    }
    return out.mapValues { it.value.joinToString("; ") }
}

private fun DrawScope.drawStateUnderlay(
    layout: StateDiagramLayout,
    compositeIds: Set<String>,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val cullMargin = 24f * unit
    val cornerRadius = CornerRadius(8f * unit, 8f * unit)
    val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)
    val dottedEffect = PathEffect.dashPathEffect(floatArrayOf(3f * unit, 3f * unit), 0f)

    // 1. Composite state containers (ordered parent-first in layout.states).
    for (ps in layout.states) {
        if (ps.state.id !in compositeIds) continue
        val r = ps.rect
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
        val fill = parseCssColor(ps.state.style?.fill) ?: theme.subgraphFill
        val border = parseCssColor(ps.state.style?.stroke) ?: theme.subgraphBorder
        val strokeW = (ps.state.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1.25f) * unit
        drawRoundRect(
            color = fill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cornerRadius,
        )
        // Divider line under composite header band
        drawLine(
            color = border,
            start = Offset(r.x, r.y + ps.headerHeight),
            end = Offset(r.right, r.y + ps.headerHeight),
            strokeWidth = 1f * unit,
        )
        // Concurrent region dividers
        for ((a, b) in ps.regionDividers) {
            drawLine(
                color = border,
                start = Offset(a.x, a.y),
                end = Offset(b.x, b.y),
                strokeWidth = 1f * unit,
                pathEffect = dashedEffect,
            )
        }
        drawRoundRect(
            color = border,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cornerRadius,
            style = Stroke(width = strokeW),
        )
    }

    // 2. Dotted note connectors.
    for (note in layout.notes) {
        val nPts = note.connectorPoints
        if (nPts.size >= 2) {
            if (!polylineIntersectsViewport(nPts, viewport, margin = cullMargin)) continue
            val samples = ClassLayout.basisSplineSamples(nPts)
            val splineHitsObstacle = (0 until samples.lastIndex).any { i ->
                val a = samples[i]
                val b = samples[i + 1]
                layout.states.any { ps ->
                    if (ps.state.id == note.note.forStateId) false
                    else if (ps.state.id in compositeIds) {
                        ps.headerRect.width > 0f && ps.headerRect.height > 0f &&
                            ClassLayout.segmentIntersectsRect(a, b, ps.headerRect)
                    } else {
                        ClassLayout.segmentIntersectsRect(a, b, ps.rect)
                    }
                }
            }
            val path = if (!splineHitsObstacle) {
                basisSpline(nPts)
            } else {
                Path().apply {
                    moveTo(nPts.first().x, nPts.first().y)
                    for (k in 1 until nPts.size) lineTo(nPts[k].x, nPts[k].y)
                }
            }
            drawPath(
                path = path,
                color = theme.edge,
                style = Stroke(width = 1f * unit, pathEffect = dottedEffect),
            )
        }
    }

    // 3. Transitions and arrowheads.
    for (tr in layout.transitions) {
        val pts = tr.points
        if (pts.size < 2) continue
        if (!polylineIntersectsViewport(pts, viewport, margin = cullMargin)) continue
        val endTrim = 7.5f * unit
        val trimmed = trimPath(pts, startBy = 0f, endBy = endTrim)
        if (trimmed.size >= 2) {
            val samples = ClassLayout.basisSplineSamples(trimmed)
            val splineHitsObstacle = (0 until samples.lastIndex).any { i ->
                val a = samples[i]
                val b = samples[i + 1]
                layout.states.any { ps ->
                    if (ps.state.id == tr.transition.from || ps.state.id == tr.transition.to) false
                    else if (ps.state.id in compositeIds) {
                        ps.headerRect.width > 0f && ps.headerRect.height > 0f &&
                            ClassLayout.segmentIntersectsRect(a, b, ps.headerRect)
                    } else {
                        ClassLayout.segmentIntersectsRect(a, b, ps.rect)
                    }
                } || layout.notes.any { pn ->
                    ClassLayout.segmentIntersectsRect(a, b, pn.rect)
                }
            }
            val path = if (!splineHitsObstacle) {
                basisSpline(trimmed)
            } else {
                Path().apply {
                    moveTo(trimmed.first().x, trimmed.first().y)
                    for (k in 1 until trimmed.size) lineTo(trimmed[k].x, trimmed[k].y)
                }
            }
            drawPath(
                path = path,
                color = theme.edge,
                style = Stroke(width = 1.25f * unit),
            )
        }
        val endFrom = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
        drawStateArrowHead(tip = pts.last(), from = endFrom, color = theme.edge, unit = unit)
    }

    // 4. Leaf state nodes (Start, End, Choice, Fork, Join, Normal).
    for (ps in layout.states) {
        if (ps.state.id in compositeIds) continue
        val r = ps.rect
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
        val s = ps.state
        val fill = parseCssColor(s.style?.fill) ?: theme.nodeFill
        val border = parseCssColor(s.style?.stroke) ?: theme.nodeBorder
        val strokeW = (s.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1.25f) * unit
        when (s.kind) {
            StateKind.Start -> {
                val radius = minOf(r.width, r.height) / 2f
                drawCircle(
                    color = border,
                    radius = radius,
                    center = Offset(r.centerX, r.centerY),
                )
            }
            StateKind.End -> {
                val radius = minOf(r.width, r.height) / 2f
                drawCircle(
                    color = theme.background,
                    radius = radius,
                    center = Offset(r.centerX, r.centerY),
                )
                drawCircle(
                    color = border,
                    radius = radius,
                    center = Offset(r.centerX, r.centerY),
                    style = Stroke(width = 1.75f * unit),
                )
                drawCircle(
                    color = border,
                    radius = radius * 0.52f,
                    center = Offset(r.centerX, r.centerY),
                )
            }
            StateKind.Choice -> {
                val path = Path().apply {
                    moveTo(r.centerX, r.y)
                    lineTo(r.right, r.centerY)
                    lineTo(r.centerX, r.bottom)
                    lineTo(r.x, r.centerY)
                    close()
                }
                drawPath(path = path, color = fill)
                drawPath(path = path, color = border, style = Stroke(width = strokeW))
            }
            StateKind.Fork, StateKind.Join -> {
                val barCorner = CornerRadius(4f * unit, 4f * unit)
                val barColor = parseCssColor(s.style?.fill) ?: border
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(r.x, r.y),
                    size = Size(r.width, r.height),
                    cornerRadius = barCorner,
                )
            }
            StateKind.Normal, StateKind.Composite -> {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(r.x, r.y),
                    size = Size(r.width, r.height),
                    cornerRadius = cornerRadius,
                )
                if (s.descriptions.isNotEmpty()) {
                    drawLine(
                        color = border,
                        start = Offset(r.x, r.y + ps.headerHeight),
                        end = Offset(r.right, r.y + ps.headerHeight),
                        strokeWidth = 1f * unit,
                    )
                }
                drawRoundRect(
                    color = border,
                    topLeft = Offset(r.x, r.y),
                    size = Size(r.width, r.height),
                    cornerRadius = cornerRadius,
                    style = Stroke(width = strokeW),
                )
            }
        }
    }

    // 5. Sticky note backgrounds and borders.
    val noteFill = theme.noteFill
    val noteBorder = theme.noteBorder
    for (note in layout.notes) {
        val r = note.rect
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
        drawRect(
            color = noteFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = noteBorder,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = 1f * unit),
        )
    }
}

private fun DrawScope.drawStateArrowHead(
    tip: DiagramPoint,
    from: DiagramPoint,
    color: Color,
    unit: Float,
) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val ux = cos(angle)
    val uy = sin(angle)
    val px = -uy
    val py = ux
    val len = 10f * unit
    val halfW = 5f * unit
    val baseX = tip.x - ux * len
    val baseY = tip.y - uy * len
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseX + px * halfW, baseY + py * halfW)
        lineTo(baseX - px * halfW, baseY - py * halfW)
        close()
    }
    drawPath(path = path, color = color)
}

internal fun measureStateLayoutForExport(
    diagram: StateDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): StateDiagramLayout {
    val compositeIds = diagram.states
        .filter { s ->
            s.kind == StateKind.Composite ||
                diagram.states.any { it.parentId == s.id } ||
                diagram.notes.any { it.parentId == s.id }
        }
        .map { it.id }
        .toSet()
    return computePremeasuredStateLayout(
        diagram = diagram,
        compositeIds = compositeIds,
        theme = theme,
        layoutOptions = layoutOptions,
        densityObj = densityObj,
        textMeasurer = textMeasurer,
    ).naturalLayout
}

