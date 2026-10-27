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
import dev.maradona.layout.GanttDiagramLayout
import dev.maradona.layout.GanttLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.PositionedGanttTask
import dev.maradona.model.GanttDiagram
import dev.maradona.model.GanttTask
import dev.maradona.model.GanttTaskStatus
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.parse.GanttParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class GanttLayoutHolder {
    var layout: GanttDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var sectionSizes: Map<String, DiagramSize> = emptyMap()
    var taskLabelSizes: Map<String, DiagramSize> = emptyMap()
    var tickSizeByLabel: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun ganttTaskBounds(pt: PositionedGanttTask): DiagramRect {
    val minX = min(pt.barRect.x, pt.labelRect.x)
    val minY = min(pt.barRect.y, pt.labelRect.y)
    val maxX = max(pt.barRect.right, pt.labelRect.right)
    val maxY = max(pt.barRect.bottom, pt.labelRect.bottom)
    return DiagramRect(minX, minY, maxX - minX, maxY - minY)
}

/**
 * Custom Compose [Layout] for Mermaid `gantt` diagrams.
 *
 * Section headers, task labels, vertical marker labels, and axis tick labels are real
 * [BasicText] composables; section rows, excluded-day shading, grid lines, task bars,
 * milestone diamonds, and `vert` lines are drawn on the underlay canvas.
 */
@Composable
fun MaradonaGanttLayout(
    diagram: GanttDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (GanttDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { GanttLayoutHolder() }
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

    val allSectionNames = remember(diagram) {
        (diagram.sections + diagram.tasks.mapNotNull { it.section }).distinct()
    }
    val candidateTicks = remember(diagram) { GanttLayout.candidateTicks(diagram) }

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
            computePremeasuredGanttLayout(
                diagram = diagram,
                allSectionNames = allSectionNames,
                candidateTicks = candidateTicks,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.sectionSizes = pre.sectionSizes
                holder.taskLabelSizes = pre.taskLabelSizes
                holder.tickSizeByLabel = pre.tickSizeByLabel
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

    val positionedSectionByName = remember(precomputedLayout) {
        precomputedLayout.sections.associateBy { it.name }
    }
    val positionedTaskById = remember(precomputedLayout) {
        precomputedLayout.tasks.associateBy { it.task.id }
    }
    val positionedVertById = remember(precomputedLayout) {
        precomputedLayout.verticalMarkers.associateBy { it.task.id }
    }
    val positionedTickByMs = remember(precomputedLayout) {
        precomputedLayout.ticks.associateBy { it.timeMs }
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
                    "Gantt diagram. ${diagram.tasks.size} tasks."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (t in diagram.tasks) {
                            val pt = positionedTaskById[t.id]
                            val vt = positionedVertById[t.id]
                            val bounds = when {
                                pt != null -> ganttTaskBounds(pt)
                                vt != null -> vt.labelRect
                                else -> null
                            } ?: continue
                            if (rectIntersectsViewport(bounds, vp, cullMargin)) continue
                            val clickEvent = t.action?.toClickEvent(t.id, t.label)
                                ?: if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = t.id, label = t.label) else null
                            if (clickEvent != null) add(clickEvent)
                        }
                    }
                    val actions = buildOffscreenCustomActions(offscreenEvents, onNodeClick, uriHandler)
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawGanttUnderlay(it, theme, holder.visibleViewport) }
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
            for (sec in allSectionNames) {
                if (activeCullViewport != null) {
                    val pSec = positionedSectionByName[sec]
                    if (pSec != null && !rectIntersectsViewport(pSec.rect, activeCullViewport, cullMargin)) continue
                }
                BasicText(
                    text = annotatedLabel(sec),
                    modifier = Modifier.layoutId("sec:$sec"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(13f),
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            for ((tIdx, t) in diagram.tasks.withIndex()) {
                if (activeCullViewport != null) {
                    val pt = positionedTaskById[t.id]
                    val vt = positionedVertById[t.id]
                    val bounds = when {
                        pt != null -> ganttTaskBounds(pt)
                        vt != null -> vt.labelRect
                        else -> null
                    }
                    if (bounds != null && !rectIntersectsViewport(bounds, activeCullViewport, cullMargin)) continue
                }
                BasicText(
                    text = annotatedLabel(t.label),
                    modifier = Modifier.layoutId("tasklbl:${t.id}"),
                    style = TextStyle(
                        color = if (t.isVerticalMarker) theme.criticalBorder else theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.edgeLabelFontSize,
                        fontWeight = if (t.isCritical || t.isMilestone) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
                val clickEvent = t.action?.toClickEvent(t.id, t.label)
                    ?: if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = t.id, label = t.label) else null
                Box(
                    modifier = Modifier
                        .layoutId("taskbox:${t.id}")
                        .diagramNodeInteraction(
                            baseDescription = "Gantt task ${t.label}",
                            traversalOrder = tIdx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }
            for ((ms, lbl) in candidateTicks) {
                val pTick = positionedTickByMs[ms]
                val emitBottom = activeCullViewport == null ||
                    (pTick != null && rectIntersectsViewport(pTick.bottomLabelRect, activeCullViewport, cullMargin))
                if (emitBottom) {
                    BasicText(
                        text = lbl,
                        modifier = Modifier.layoutId("tickB:$ms"),
                        style = TextStyle(
                            color = theme.subgraphText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.captionFontSize,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                if (diagram.topAxis) {
                    val emitTop = activeCullViewport == null ||
                        (pTick?.topLabelRect != null && rectIntersectsViewport(pTick.topLabelRect, activeCullViewport, cullMargin))
                    if (emitTop) {
                        BasicText(
                            text = lbl,
                            modifier = Modifier.layoutId("tickT:$ms"),
                            style = TextStyle(
                                color = theme.subgraphText,
                                fontFamily = theme.fontFamily,
                                fontSize = theme.captionFontSize,
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
        var titlePlaceable: Placeable? = null
        val secPlaceables = HashMap<String, Placeable>()
        val taskLblPlaceables = HashMap<String, Placeable>()
        val taskBoxMeasurables = HashMap<String, Measurable>()
        val tickBottomPlaceables = HashMap<Long, Placeable>()
        val tickTopPlaceables = HashMap<Long, Placeable>()
        val tickSizeByLabel = HashMap<String, DiagramSize>(holder.tickSizeByLabel)
        if (activeCullViewport == null) {
            tickSizeByLabel.clear()
        }

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("sec:") -> secPlaceables[id.removePrefix("sec:")] = m.measure(unbounded)
                id.startsWith("tasklbl:") -> taskLblPlaceables[id.removePrefix("tasklbl:")] = m.measure(unbounded)
                id.startsWith("taskbox:") -> taskBoxMeasurables[id.removePrefix("taskbox:")] = m
                id.startsWith("tickB:") -> {
                    val ms = id.removePrefix("tickB:").toLongOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    tickBottomPlaceables[ms] = pl
                    val lbl = GanttParser.formatTickLabel(ms, diagram.dateFormat, diagram.axisFormat)
                    tickSizeByLabel[lbl] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                }
                id.startsWith("tickT:") -> {
                    val ms = id.removePrefix("tickT:").toLongOrNull() ?: continue
                    tickTopPlaceables[ms] = m.measure(unbounded)
                }
            }
        }

        val resolvedTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize
        val resolvedSectionSizes = allSectionNames.associateWith { s ->
            val pl = secPlaceables[s]
            if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
            else holder.sectionSizes[s] ?: GanttLayout.defaultLabelSize(s, renderDensity)
        }
        val resolvedTaskLabelSizes = diagram.tasks.associate { t ->
            val pl = taskLblPlaceables[t.id]
            val sz = if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
            else holder.taskLabelSizes[t.id] ?: GanttLayout.defaultLabelSize(t.label, renderDensity)
            t.id to sz
        }

        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.sectionSizes = resolvedSectionSizes
            holder.taskLabelSizes = resolvedTaskLabelSizes
            holder.tickSizeByLabel = tickSizeByLabel
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = GanttLayout.layout(
            diagram = diagram,
            taskLabelSize = { t ->
                resolvedTaskLabelSizes[t.id] ?: GanttLayout.defaultLabelSize(t.label, renderDensity)
            },
            sectionLabelSize = { s ->
                resolvedSectionSizes[s] ?: GanttLayout.defaultLabelSize(s, renderDensity)
            },
            tickLabelSize = { s -> tickSizeByLabel[s] ?: GanttLayout.defaultTickSize(s, renderDensity) },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val boxPlaceables = HashMap<String, Placeable>()
        for (pt in layout.tasks) {
            val m = taskBoxMeasurables[pt.task.id] ?: continue
            val w = pt.barRect.width.roundToInt().coerceAtLeast(1)
            val h = pt.barRect.height.roundToInt().coerceAtLeast(1)
            boxPlaceables[pt.task.id] = m.measure(Constraints.fixed(w, h))
        }

        val drawnTasks = if (activeCullViewport == null) {
            layout.tasks.size
        } else {
            layout.tasks.count { rectIntersectsViewport(it.barRect, activeCullViewport, cullMargin) }
        }
        val drawnTicks = if (activeCullViewport == null) {
            layout.ticks.size
        } else {
            layout.ticks.count {
                segmentIntersectsViewport(
                    it.x,
                    layout.plotRect.y,
                    it.x,
                    layout.plotRect.bottom + 4f * renderDensity,
                    activeCullViewport,
                    cullMargin,
                )
            }
        }
        val drawnSections = if (activeCullViewport == null) {
            layout.sections.size
        } else {
            layout.sections.count { rectIntersectsViewport(it.rect, activeCullViewport, cullMargin) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = boxPlaceables.size,
                totalNodes = diagram.tasks.size,
                composedEdgeLabels = taskLblPlaceables.size,
                totalEdgeLabels = diagram.tasks.size,
                drawnEdges = drawnTasks + drawnTicks,
                totalEdges = layout.tasks.size + layout.ticks.size,
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
                val pl = secPlaceables[sec.name] ?: continue
                pl.place(sec.labelRect.x.roundToInt(), sec.labelRect.y.roundToInt())
            }
            for (pt in layout.tasks) {
                boxPlaceables[pt.task.id]?.place(pt.barRect.x.roundToInt(), pt.barRect.y.roundToInt())
                taskLblPlaceables[pt.task.id]?.place(pt.labelRect.x.roundToInt(), pt.labelRect.y.roundToInt())
            }
            for (vt in layout.verticalMarkers) {
                taskLblPlaceables[vt.task.id]?.place(vt.labelRect.x.roundToInt(), vt.labelRect.y.roundToInt())
            }
            for (tick in layout.ticks) {
                tickBottomPlaceables[tick.timeMs]?.place(
                    tick.bottomLabelRect.x.roundToInt(),
                    tick.bottomLabelRect.y.roundToInt(),
                )
                val tRect = tick.topLabelRect
                if (tRect != null) {
                    tickTopPlaceables[tick.timeMs]?.place(
                        tRect.x.roundToInt(),
                        tRect.y.roundToInt(),
                    )
                }
            }
        }
    }
}

private data class PremeasuredGanttResult(
    val layout: GanttDiagramLayout,
    val titleSize: DiagramSize?,
    val sectionSizes: Map<String, DiagramSize>,
    val taskLabelSizes: Map<String, DiagramSize>,
    val tickSizeByLabel: Map<String, DiagramSize>,
)

private fun computePremeasuredGanttLayout(
    diagram: GanttDiagram,
    allSectionNames: List<String>,
    candidateTicks: List<Pair<Long, String>>,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredGanttResult {
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
        fontWeight = FontWeight.SemiBold,
    )
    val sectionSizes = allSectionNames.associateWith { sec ->
        measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(sec),
            style = secStyle,
        )
    }

    val taskLabelSizes = diagram.tasks.associate { t ->
        val sz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(t.label),
            style = TextStyle(
                color = if (t.isVerticalMarker) theme.criticalBorder else theme.nodeText,
                fontFamily = theme.fontFamily,
                fontSize = theme.edgeLabelFontSize,
                fontWeight = if (t.isCritical || t.isMilestone) FontWeight.SemiBold else FontWeight.Medium,
            ),
        )
        t.id to sz
    }

    val tickStyle = TextStyle(
        color = theme.subgraphText,
        fontFamily = theme.fontFamily,
        fontSize = theme.captionFontSize,
        textAlign = TextAlign.Center,
    )
    val tickSizeByLabel = HashMap<String, DiagramSize>()
    for ((_, lbl) in candidateTicks) {
        if (lbl !in tickSizeByLabel) {
            tickSizeByLabel[lbl] = measurePlainTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = lbl,
                style = tickStyle,
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
    val layout = GanttLayout.layout(
        diagram = diagram,
        taskLabelSize = { t -> taskLabelSizes[t.id] ?: GanttLayout.defaultLabelSize(t.label, renderDensity) },
        sectionLabelSize = { s -> sectionSizes[s] ?: GanttLayout.defaultLabelSize(s, renderDensity) },
        tickLabelSize = { s -> tickSizeByLabel[s] ?: GanttLayout.defaultTickSize(s, renderDensity) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredGanttResult(
        layout = layout,
        titleSize = titleSize,
        sectionSizes = sectionSizes,
        taskLabelSizes = taskLabelSizes,
        tickSizeByLabel = tickSizeByLabel,
    )
}

private fun DrawScope.drawGanttUnderlay(
    layout: GanttDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val cullMargin = 8f * unit
    val plot = layout.plotRect
    val sectionBands = listOf(
        theme.subgraphFill.copy(alpha = 0.72f),
        theme.mutedFill.copy(alpha = 0.85f),
    )

    // 1. Section and row background bands.
    for (sec in layout.sections) {
        val bandRect = DiagramRect(sec.rect.x, sec.rect.y, sec.rect.width + plot.width, sec.rect.height)
        if (!rectIntersectsViewport(bandRect, viewport, cullMargin)) continue
        val bandFill = sectionBands[sec.sectionIndex.mod(sectionBands.size)]
        drawRect(
            color = bandFill,
            topLeft = Offset(sec.rect.x, sec.rect.y),
            size = Size(sec.rect.width + plot.width, sec.rect.height),
        )
    }
    for ((idx, rRect) in layout.rowRects.withIndex()) {
        if (!rectIntersectsViewport(rRect, viewport, cullMargin)) continue
        if (idx % 2 == 1) {
            drawRect(
                color = theme.mutedFill.copy(alpha = 0.35f),
                topLeft = Offset(rRect.x, rRect.y),
                size = Size(rRect.width, rRect.height),
            )
        }
        drawLine(
            color = theme.subgraphBorder.copy(alpha = 0.45f),
            start = Offset(rRect.x, rRect.bottom),
            end = Offset(rRect.right, rRect.bottom),
            strokeWidth = 1f * unit,
        )
    }

    // 2. Excluded day shading (weekends / holidays).
    for (ex in layout.excludedBands) {
        if (!rectIntersectsViewport(ex, viewport, cullMargin)) continue
        drawRect(
            color = theme.mutedBorder.copy(alpha = 0.16f),
            topLeft = Offset(ex.x, ex.y),
            size = Size(ex.width, ex.height),
        )
    }

    // 3. Plot frame & vertical tick grid lines.
    for (tick in layout.ticks) {
        if (!segmentIntersectsViewport(tick.x, plot.y, tick.x, plot.bottom + 4f * unit, viewport, cullMargin)) continue
        drawLine(
            color = theme.subgraphBorder.copy(alpha = 0.55f),
            start = Offset(tick.x, plot.y),
            end = Offset(tick.x, plot.bottom + 4f * unit),
            strokeWidth = 1f * unit,
        )
    }
    if (rectIntersectsViewport(plot, viewport, cullMargin)) {
        drawRect(
            color = theme.subgraphBorder,
            topLeft = Offset(plot.x, plot.y),
            size = Size(plot.width, plot.height),
            style = Stroke(width = 1.2f * unit),
        )
    }

    // 4. Task bars & milestone diamonds.
    for (pt in layout.tasks) {
        val t = pt.task
        val r = pt.barRect
        if (!rectIntersectsViewport(r, viewport, cullMargin)) continue
        val secIdx = pt.sectionIndex.coerceAtLeast(0)
        val defaultFill = theme.seriesFill(secIdx)
        val defaultStroke = theme.seriesColor(secIdx)
        val (fill, border) = when {
            t.isCritical && t.status == GanttTaskStatus.Done -> theme.criticalFill.copy(alpha = 0.78f) to theme.criticalBorder
            t.isCritical && t.status == GanttTaskStatus.Active -> androidx.compose.ui.graphics.lerp(theme.criticalFill, theme.criticalBorder, 0.24f) to theme.criticalBorder
            t.isCritical -> theme.criticalFill to theme.criticalBorder
            t.status == GanttTaskStatus.Done -> theme.mutedFill to theme.mutedBorder
            t.status == GanttTaskStatus.Active -> theme.accentFill to theme.accentBorder
            else -> defaultFill to defaultStroke
        }
        val strokeW = if (t.isCritical || t.status == GanttTaskStatus.Active) 1.8f * unit else 1.3f * unit

        if (t.isMilestone) {
            val diamond = Path().apply {
                moveTo(r.centerX, r.y)
                lineTo(r.right, r.centerY)
                lineTo(r.centerX, r.bottom)
                lineTo(r.x, r.centerY)
                close()
            }
            drawPath(path = diamond, color = fill)
            drawPath(path = diamond, color = border, style = Stroke(width = strokeW))
        } else {
            val cr = CornerRadius(4f * unit, 4f * unit)
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
                style = Stroke(width = strokeW),
            )
        }
    }

    // 5. Vertical markers (`vert`).
    val dash = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)
    for (vt in layout.verticalMarkers) {
        if (!segmentIntersectsViewport(vt.x, vt.topY, vt.x, vt.bottomY, viewport, cullMargin)) continue
        drawLine(
            color = theme.criticalBorder,
            start = Offset(vt.x, vt.topY),
            end = Offset(vt.x, vt.bottomY),
            strokeWidth = 1.6f * unit,
            pathEffect = dash,
        )
    }
}
