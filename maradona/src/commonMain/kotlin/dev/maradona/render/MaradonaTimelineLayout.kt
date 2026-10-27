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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import dev.maradona.layout.TimelineDiagramLayout
import dev.maradona.layout.TimelineLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.TimelineDiagram
import dev.maradona.model.TimelineDirection
import kotlin.math.abs
import kotlin.math.roundToInt

private class TimelineLayoutHolder {
    var layout: TimelineDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var secSizesByName: Map<String, DiagramSize> = emptyMap()
    var periodSizes: Map<String, DiagramSize> = emptyMap()
    var eventSizes: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private data class TimelineColorSet(
    val sectionFill: Color,
    val periodFill: Color,
    val eventFill: Color,
    val border: Color,
)

/**
 * Custom Compose [Layout] for Mermaid `timeline` diagrams.
 *
 * Section headers, period titles, event descriptions, and the optional chart title are
 * real [BasicText] composables; the timeline spine, dashed event connectors, and
 * section/period/event cards are drawn on the underlay canvas.
 */
@Composable
fun MaradonaTimelineLayout(
    diagram: TimelineDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (TimelineDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { TimelineLayoutHolder() }
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

    val sectionRuns = remember(diagram) { TimelineLayout.buildSectionRuns(diagram) }

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
            computePremeasuredTimelineLayout(
                diagram = diagram,
                sectionRuns = sectionRuns,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.secSizesByName = pre.secSizesByName
                holder.periodSizes = pre.periodSizes
                holder.eventSizes = pre.eventSizes
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

    val positionedSectionByRunIndex = remember(precomputedLayout) {
        precomputedLayout.sections.associateBy { it.runIndex }
    }
    val positionedPeriodById = remember(precomputedLayout) {
        precomputedLayout.periods.associateBy { it.period.id }
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
                    "Timeline diagram. ${diagram.periods.size} periods."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (p in diagram.periods) {
                            val pp = positionedPeriodById[p.id] ?: continue
                            if (rectIntersectsViewport(pp.rect, vp, cullMargin)) continue
                            if (onNodeClick != null) {
                                add(MaradonaNodeClickEvent(nodeId = p.id, label = p.title))
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
                holder.layout?.let { drawTimelineUnderlay(it, theme, holder.visibleViewport) }
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
            for (run in sectionRuns) {
                val secName = run.name ?: continue
                if (activeCullViewport != null) {
                    val pSec = positionedSectionByRunIndex[run.runIndex]
                    if (pSec != null && !rectIntersectsViewport(pSec.rect, activeCullViewport, cullMargin)) continue
                }
                BasicText(
                    text = annotatedLabel(secName),
                    modifier = Modifier.layoutId("secrun:${run.runIndex}"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(13f),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for ((pIdx, p) in diagram.periods.withIndex()) {
                val pp = positionedPeriodById[p.id]
                val emitPeriod = activeCullViewport == null ||
                    pp == null ||
                    rectIntersectsViewport(pp.rect, activeCullViewport, cullMargin)
                if (emitPeriod) {
                    BasicText(
                        text = annotatedLabel(p.title),
                        modifier = Modifier.layoutId("plbl:${p.id}"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(13f),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    val evSummary = if (p.events.isNotEmpty()) ": ${p.events.joinToString(", ")}" else ""
                    val baseDesc = "Timeline period ${p.title}$evSummary"
                    val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = p.id, label = p.title) else null
                    Box(
                        modifier = Modifier
                            .layoutId("pbox:${p.id}")
                            .diagramNodeInteraction(
                                baseDescription = baseDesc,
                                traversalOrder = pIdx,
                                event = clickEvent,
                                onNodeClick = onNodeClick,
                                uriHandler = uriHandler,
                            ),
                    )
                }
                for ((eIdx, evText) in p.events.withIndex()) {
                    val evRect = pp?.events?.getOrNull(eIdx)?.rect
                    val emitEvent = activeCullViewport == null ||
                        evRect == null ||
                        rectIntersectsViewport(evRect, activeCullViewport, cullMargin)
                    if (emitEvent) {
                        BasicText(
                            text = annotatedLabel(evText),
                            modifier = Modifier.layoutId("elbl:${p.id}:$eIdx"),
                            style = TextStyle(
                                color = theme.nodeText,
                                fontFamily = theme.fontFamily,
                                fontSize = theme.edgeLabelFontSize,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()
        val eventWrapConstraint = Constraints(maxWidth = (220f * renderDensity).roundToInt().coerceAtLeast(1))
        var titlePlaceable: Placeable? = null
        val secRunPlaceables = HashMap<Int, Placeable>()
        val secSizeByName = HashMap<String, DiagramSize>(holder.secSizesByName)
        if (activeCullViewport == null) {
            secSizeByName.clear()
        }
        val periodLblPlaceables = HashMap<String, Placeable>()
        val periodBoxMeasurables = HashMap<String, Measurable>()
        val eventLblPlaceables = HashMap<String, Placeable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("secrun:") -> {
                    val runIdx = id.removePrefix("secrun:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    secRunPlaceables[runIdx] = pl
                    val name = sectionRuns.firstOrNull { it.runIndex == runIdx }?.name
                    if (name != null) {
                        val sz = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                        val prev = secSizeByName[name]
                        secSizeByName[name] = if (prev == null) sz else DiagramSize(maxOf(prev.width, sz.width), maxOf(prev.height, sz.height))
                    }
                }
                id.startsWith("plbl:") -> periodLblPlaceables[id.removePrefix("plbl:")] = m.measure(unbounded)
                id.startsWith("pbox:") -> periodBoxMeasurables[id.removePrefix("pbox:")] = m
                id.startsWith("elbl:") -> eventLblPlaceables[id.removePrefix("elbl:")] = m.measure(eventWrapConstraint)
            }
        }

        val resolvedTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize
        val resolvedPeriodSizes = HashMap<String, DiagramSize>(diagram.periods.size)
        val resolvedEventSizes = HashMap<String, DiagramSize>()
        for (p in diagram.periods) {
            val pPl = periodLblPlaceables[p.id]
            if (pPl != null) {
                resolvedPeriodSizes[p.id] = DiagramSize(pPl.width.toFloat(), pPl.height.toFloat())
            } else {
                holder.periodSizes[p.id]?.let { resolvedPeriodSizes[p.id] = it }
            }
            for (eIdx in p.events.indices) {
                val key = "${p.id}:$eIdx"
                val ePl = eventLblPlaceables[key]
                if (ePl != null) {
                    resolvedEventSizes[key] = DiagramSize(ePl.width.toFloat(), ePl.height.toFloat())
                } else {
                    holder.eventSizes[key]?.let { resolvedEventSizes[key] = it }
                }
            }
        }

        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.secSizesByName = secSizeByName
            holder.periodSizes = resolvedPeriodSizes
            holder.eventSizes = resolvedEventSizes
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = TimelineLayout.layout(
            diagram = diagram,
            periodTitleSize = { p ->
                resolvedPeriodSizes[p.id] ?: TimelineLayout.defaultTextSize(p.title, renderDensity)
            },
            eventTextSize = { p, eIdx, s ->
                resolvedEventSizes["${p.id}:$eIdx"] ?: TimelineLayout.defaultTextSize(s, renderDensity)
            },
            sectionTitleSize = { s ->
                secSizeByName[s] ?: TimelineLayout.defaultTextSize(s, renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val periodBoxPlaceables = HashMap<String, Placeable>()
        for (pp in layout.periods) {
            val m = periodBoxMeasurables[pp.period.id] ?: continue
            val w = pp.rect.width.roundToInt().coerceAtLeast(1)
            val h = pp.rect.height.roundToInt().coerceAtLeast(1)
            periodBoxPlaceables[pp.period.id] = m.measure(Constraints.fixed(w, h))
        }

        val totalEvents = layout.periods.sumOf { it.events.size }
        val drawnEvents = if (activeCullViewport == null) {
            totalEvents
        } else {
            layout.periods.sumOf { pp ->
                pp.events.count { ev -> rectIntersectsViewport(ev.rect, activeCullViewport, cullMargin) }
            }
        }
        val drawnSections = if (activeCullViewport == null) {
            layout.sections.size
        } else {
            layout.sections.count { sec -> rectIntersectsViewport(sec.rect, activeCullViewport, cullMargin) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = periodBoxPlaceables.size,
                totalNodes = layout.periods.size,
                composedEdgeLabels = eventLblPlaceables.size,
                totalEdgeLabels = totalEvents,
                drawnEdges = drawnEvents,
                totalEdges = totalEvents,
                drawnSubgraphs = drawnSections,
                totalSubgraphs = layout.sections.size,
            ),
        )

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for (sec in layout.sections) {
                val pl = secRunPlaceables[sec.runIndex] ?: continue
                pl.place(
                    (sec.rect.centerX - pl.width / 2f).roundToInt(),
                    (sec.rect.centerY - pl.height / 2f).roundToInt(),
                )
            }
            for (pp in layout.periods) {
                periodBoxPlaceables[pp.period.id]?.place(
                    pp.rect.x.roundToInt(),
                    pp.rect.y.roundToInt(),
                )
                val pPl = periodLblPlaceables[pp.period.id]
                if (pPl != null) {
                    pPl.place(
                        (pp.rect.centerX - pPl.width / 2f).roundToInt(),
                        (pp.rect.centerY - pPl.height / 2f).roundToInt(),
                    )
                }
                for (ev in pp.events) {
                    val ePl = eventLblPlaceables["${pp.period.id}:${ev.eventIndex}"] ?: continue
                    ePl.place(
                        (ev.rect.centerX - ePl.width / 2f).roundToInt(),
                        (ev.rect.centerY - ePl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private data class PremeasuredTimelineResult(
    val layout: TimelineDiagramLayout,
    val titleSize: DiagramSize?,
    val secSizesByName: Map<String, DiagramSize>,
    val periodSizes: Map<String, DiagramSize>,
    val eventSizes: Map<String, DiagramSize>,
)

private fun computePremeasuredTimelineLayout(
    diagram: TimelineDiagram,
    sectionRuns: List<TimelineLayout.SectionRun>,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredTimelineResult {
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

    val secStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(13f),
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    val secSizesByName = HashMap<String, DiagramSize>()
    for (run in sectionRuns) {
        val secName = run.name ?: continue
        val sz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(secName),
            style = secStyle,
        )
        val prev = secSizesByName[secName]
        secSizesByName[secName] = if (prev == null) sz else DiagramSize(maxOf(prev.width, sz.width), maxOf(prev.height, sz.height))
    }

    val periodStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(13f),
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val eventStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
    val maxEventWidthPx = (220f * renderDensity).roundToInt().coerceAtLeast(1)
    val periodSizes = HashMap<String, DiagramSize>(diagram.periods.size)
    val eventSizes = HashMap<String, DiagramSize>()
    for (p in diagram.periods) {
        periodSizes[p.id] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(p.title),
            style = periodStyle,
        )
        for ((eIdx, evText) in p.events.withIndex()) {
            eventSizes["${p.id}:$eIdx"] = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(evText),
                style = eventStyle,
                maxWidthPx = maxEventWidthPx,
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
    val layout = TimelineLayout.layout(
        diagram = diagram,
        periodTitleSize = { p -> periodSizes[p.id] ?: TimelineLayout.defaultTextSize(p.title, renderDensity) },
        eventTextSize = { p, eIdx, s -> eventSizes["${p.id}:$eIdx"] ?: TimelineLayout.defaultTextSize(s, renderDensity) },
        sectionTitleSize = { s -> secSizesByName[s] ?: TimelineLayout.defaultTextSize(s, renderDensity) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredTimelineResult(
        layout = layout,
        titleSize = titleSize,
        secSizesByName = secSizesByName,
        periodSizes = periodSizes,
        eventSizes = eventSizes,
    )
}

private fun DrawScope.drawTimelineUnderlay(
    layout: TimelineDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val edgeMargin = 20f * unit
    val nodeMargin = 8f * unit
    val dash = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)

    fun colorSetFor(index: Int): TimelineColorSet {
        val border = theme.seriesColor(index)
        val sectionFill = theme.seriesFill(index)
        val periodFill = androidx.compose.ui.graphics.lerp(sectionFill, theme.nodeFill, 0.35f)
        val eventFill = androidx.compose.ui.graphics.lerp(sectionFill, theme.background, 0.58f)
        return TimelineColorSet(sectionFill, periodFill, eventFill, border)
    }

    // 1. Continuous timeline spine with arrow tip.
    if (segmentIntersectsViewport(layout.spineStart.x, layout.spineStart.y, layout.spineEnd.x, layout.spineEnd.y, viewport, edgeMargin)) {
        val spineColor = theme.nodeBorder
        drawLine(
            color = spineColor,
            start = Offset(layout.spineStart.x, layout.spineStart.y),
            end = Offset(layout.spineEnd.x, layout.spineEnd.y),
            strokeWidth = 2.6f * unit,
            cap = StrokeCap.Round,
        )
        val arrowSize = 7f * unit
        val arrowPath = Path().apply {
            if (layout.direction == TimelineDirection.TD) {
                val ex = layout.spineEnd.x
                val ey = layout.spineEnd.y
                moveTo(ex, ey)
                lineTo(ex - arrowSize * 0.7f, ey - arrowSize)
                lineTo(ex + arrowSize * 0.7f, ey - arrowSize)
                close()
            } else {
                val ex = layout.spineEnd.x
                val ey = layout.spineEnd.y
                moveTo(ex, ey)
                lineTo(ex - arrowSize, ey - arrowSize * 0.7f)
                lineTo(ex - arrowSize, ey + arrowSize * 0.7f)
                close()
            }
        }
        drawPath(path = arrowPath, color = spineColor)
    }

    // 2. Section banners.
    for (sec in layout.sections) {
        val r = sec.rect
        if (!rectIntersectsViewport(r, viewport, nodeMargin)) continue
        val colorIdx = if (layout.disableMulticolor) 0 else sec.sectionIndex
        val colors = colorSetFor(colorIdx)
        val cr = CornerRadius(8f * unit, 8f * unit)
        drawRoundRect(
            color = colors.sectionFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
        )
        drawRoundRect(
            color = colors.border,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Stroke(width = 1.6f * unit),
        )
    }

    // 3. Period cards, dashed event connectors, and event cards.
    for (pp in layout.periods) {
        val colors = colorSetFor(pp.colorIndex)
        val cStart = pp.connectorStart
        val cEnd = pp.connectorEnd
        if (cStart != null && cEnd != null && segmentIntersectsViewport(cStart.x, cStart.y, cEnd.x, cEnd.y, viewport, edgeMargin)) {
            drawLine(
                color = colors.border.copy(alpha = 0.75f),
                start = Offset(cStart.x, cStart.y),
                end = Offset(cEnd.x, cEnd.y),
                strokeWidth = 1.6f * unit,
                pathEffect = dash,
            )
        }

        val pr = pp.rect
        if (rectIntersectsViewport(pr, viewport, nodeMargin)) {
            val pCr = CornerRadius(7f * unit, 7f * unit)
            drawRoundRect(
                color = colors.periodFill,
                topLeft = Offset(pr.x, pr.y),
                size = Size(pr.width, pr.height),
                cornerRadius = pCr,
            )
            drawRoundRect(
                color = colors.border,
                topLeft = Offset(pr.x, pr.y),
                size = Size(pr.width, pr.height),
                cornerRadius = pCr,
                style = Stroke(width = 1.6f * unit),
            )
        }

        val eCr = CornerRadius(6f * unit, 6f * unit)
        for (ev in pp.events) {
            val er = ev.rect
            if (!rectIntersectsViewport(er, viewport, nodeMargin)) continue
            drawRoundRect(
                color = colors.eventFill,
                topLeft = Offset(er.x, er.y),
                size = Size(er.width, er.height),
                cornerRadius = eCr,
            )
            drawRoundRect(
                color = colors.border.copy(alpha = 0.85f),
                topLeft = Offset(er.x, er.y),
                size = Size(er.width, er.height),
                cornerRadius = eCr,
                style = Stroke(width = 1.2f * unit),
            )
        }
    }
}
