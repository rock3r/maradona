package dev.maradona.render

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.KanbanDiagramLayout
import dev.maradona.layout.KanbanLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.model.KanbanDiagram
import dev.maradona.model.KanbanPriority
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.abs
import kotlin.math.roundToInt

private class KanbanLayoutHolder {
    var layout: KanbanDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var colHdrSizes: Map<Int, DiagramSize> = emptyMap()
    var taskLblSizes: Map<String, DiagramSize> = emptyMap()
    var metaSizes: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun priorityStripeColor(priority: KanbanPriority?, theme: MaradonaTheme): Color? = when (priority) {
    KanbanPriority.VeryHigh -> theme.criticalBorder
    KanbanPriority.High -> theme.warningBorder
    KanbanPriority.Medium -> null
    KanbanPriority.Low -> theme.accentBorder
    KanbanPriority.VeryLow -> theme.seriesColor(1)
    null -> null
}

/**
 * Custom Compose [Layout] for Mermaid `kanban` diagrams.
 *
 * Column titles, wrapped task descriptions, `ticket` badges, `assigned` labels, and
 * the optional board title are real [BasicText] composables; the tinted column containers,
 * task card backgrounds/borders, and priority stripes are drawn on the underlay canvas.
 */
@Composable
fun MaradonaKanbanLayout(
    diagram: KanbanDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (KanbanDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { KanbanLayoutHolder() }
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
            computePremeasuredKanbanLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.colHdrSizes = pre.colHdrSizes
                holder.taskLblSizes = pre.taskLblSizes
                holder.metaSizes = pre.metaSizes
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

    val positionedColByIndex = remember(precomputedLayout) {
        precomputedLayout.columns.associateBy { it.columnIndex }
    }
    val positionedTaskByKey = remember(precomputedLayout) {
        precomputedLayout.columns
            .flatMap { col -> col.tasks.map { pt -> "${col.columnIndex}:${pt.taskIndex}" to pt } }
            .toMap()
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
                    val totalTasks = diagram.columns.sumOf { it.tasks.size }
                    "Kanban board. ${diagram.columns.size} columns, $totalTasks tasks."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (col in precomputedLayout.columns) {
                            for (pt in col.tasks) {
                                if (rectIntersectsViewport(pt.cardRect, vp, 8f * renderDensity)) continue
                                val task = pt.task
                                val ticketUrl = task.ticket?.takeIf { it.isNotBlank() }?.let { diagram.resolveTicketUrl(it) }
                                if (ticketUrl != null || onNodeClick != null) {
                                    add(
                                        MaradonaNodeClickEvent(
                                            nodeId = task.id,
                                            label = task.label,
                                            url = ticketUrl,
                                            tooltip = task.ticket,
                                        ),
                                    )
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
                holder.layout?.let { drawKanbanUnderlay(it, theme, holder.visibleViewport) }
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
            var flatTaskIndex = 0
            for ((colIdx, col) in diagram.columns.withIndex()) {
                val pCol = positionedColByIndex[colIdx]
                val colVisible = activeCullViewport == null || pCol == null ||
                    rectIntersectsViewport(pCol.rect, activeCullViewport, 8f * renderDensity)
                if (colVisible) {
                    val colHeaderLabel = if (!col.icon.isNullOrBlank()) {
                        "${resolveMermaidIconGlyph(col.icon)} ${col.label}"
                    } else {
                        col.label
                    }
                    BasicText(
                        text = annotatedLabel(colHeaderLabel, theme.monospaceFontFamily),
                        modifier = Modifier.layoutId("colhdr:$colIdx"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(13.5f),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                for ((taskIdx, task) in col.tasks.withIndex()) {
                    val order = flatTaskIndex++
                    val key = "$colIdx:$taskIdx"
                    if (activeCullViewport != null) {
                        val pt = positionedTaskByKey[key]
                        if (pt != null && !rectIntersectsViewport(pt.cardRect, activeCullViewport, 8f * renderDensity)) {
                            continue
                        }
                    }
                    val ticketUrl = task.ticket?.takeIf { it.isNotBlank() }?.let { diagram.resolveTicketUrl(it) }
                    val clickEvent = if (ticketUrl != null || onNodeClick != null) {
                        MaradonaNodeClickEvent(
                            nodeId = task.id,
                            label = task.label,
                            url = ticketUrl,
                            tooltip = task.ticket,
                        )
                    } else {
                        null
                    }
                    val taskDisplayLabel = if (!task.icon.isNullOrBlank()) {
                        "${resolveMermaidIconGlyph(task.icon)} ${task.label}"
                    } else {
                        task.label
                    }
                    BasicText(
                        text = annotatedLabel(taskDisplayLabel, theme.monospaceFontFamily),
                        modifier = Modifier.layoutId("tasklbl:$key"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            fontWeight = FontWeight.Normal,
                        ),
                    )
                    task.ticket?.takeIf { it.isNotBlank() }?.let { tkt ->
                        val tktModifier = if (ticketUrl != null || onNodeClick != null) {
                            Modifier
                                .layoutId("tasktkt:$key")
                                .clickable {
                                    if (onNodeClick != null && clickEvent != null) {
                                        onNodeClick(clickEvent)
                                    } else if (ticketUrl != null) {
                                        runCatching { uriHandler.openUri(ticketUrl) }
                                    }
                                }
                        } else {
                            Modifier.layoutId("tasktkt:$key")
                        }
                        BasicText(
                            text = annotatedLabel(tkt),
                            modifier = tktModifier,
                            style = TextStyle(
                                color = theme.nodeText.copy(alpha = 0.78f),
                                fontFamily = theme.fontFamily,
                                fontSize = theme.scaledSp(10.5f),
                                fontWeight = FontWeight.Medium,
                                textDecoration = if (ticketUrl != null) TextDecoration.Underline else TextDecoration.None,
                            ),
                        )
                    }
                    task.assigned?.takeIf { it.isNotBlank() }?.let { asg ->
                        BasicText(
                            text = annotatedLabel(asg),
                            modifier = Modifier.layoutId("taskasg:$key"),
                            style = TextStyle(
                                color = theme.nodeText.copy(alpha = 0.75f),
                                fontFamily = theme.fontFamily,
                                fontSize = theme.scaledSp(10.5f),
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    }
                    val meta = listOfNotNull(
                        task.ticket?.let { "ticket $it" },
                        task.assigned?.let { "assigned to $it" },
                        task.rawPriority?.let { "priority $it" },
                    ).joinToString(", ")
                    val baseDesc = buildString {
                        append("${col.label}: ${task.label}")
                        if (meta.isNotEmpty()) append(" ($meta)")
                    }
                    Box(
                        modifier = Modifier
                            .layoutId("taskbox:$key")
                            .diagramNodeInteraction(
                                baseDescription = baseDesc,
                                traversalOrder = order,
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
        val colHdrPlaceables = HashMap<Int, Placeable>()
        val taskLblMeasurables = HashMap<String, Measurable>()
        val taskLblPlaceables = HashMap<String, Placeable>()
        val measuredTaskLblSizes = HashMap<String, DiagramSize>(holder.taskLblSizes)
        val taskTktPlaceables = HashMap<String, Placeable>()
        val taskAsgPlaceables = HashMap<String, Placeable>()
        val metaSizesByText = HashMap<String, DiagramSize>(holder.metaSizes)
        val taskBoxMeasurables = HashMap<String, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("colhdr:") -> {
                    val cIdx = id.removePrefix("colhdr:").toIntOrNull() ?: continue
                    colHdrPlaceables[cIdx] = m.measure(unbounded)
                }
                id.startsWith("tasklbl:") -> {
                    taskLblMeasurables[id.removePrefix("tasklbl:")] = m
                }
                id.startsWith("tasktkt:") -> {
                    val key = id.removePrefix("tasktkt:")
                    val pl = m.measure(unbounded)
                    taskTktPlaceables[key] = pl
                    val parts = key.split(':')
                    val tkt = diagram.columns.getOrNull(parts[0].toInt())
                        ?.tasks?.getOrNull(parts[1].toInt())?.ticket
                    if (tkt != null) {
                        val prev = metaSizesByText[tkt]
                        metaSizesByText[tkt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
                id.startsWith("taskasg:") -> {
                    val key = id.removePrefix("taskasg:")
                    val pl = m.measure(unbounded)
                    taskAsgPlaceables[key] = pl
                    val parts = key.split(':')
                    val asg = diagram.columns.getOrNull(parts[0].toInt())
                        ?.tasks?.getOrNull(parts[1].toInt())?.assigned
                    if (asg != null) {
                        val prev = metaSizesByText[asg]
                        metaSizesByText[asg] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
                id.startsWith("taskbox:") -> {
                    taskBoxMeasurables[id.removePrefix("taskbox:")] = m
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

        val layout = KanbanLayout.layout(
            diagram = diagram,
            columnHeaderSize = { colIdx, col ->
                val pl = colHdrPlaceables[colIdx]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else holder.colHdrSizes[colIdx] ?: KanbanLayout.defaultHeaderSize(col.label, renderDensity)
            },
            taskLabelSize = { colIdx, taskIdx, task, maxW ->
                val key = "$colIdx:$taskIdx"
                val m = taskLblMeasurables[key]
                if (m != null) {
                    val pl = m.measure(Constraints(maxWidth = maxW.roundToInt().coerceAtLeast(1)))
                    taskLblPlaceables[key] = pl
                    val sz = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    measuredTaskLblSizes[key] = sz
                    sz
                } else {
                    measuredTaskLblSizes[key] ?: KanbanLayout.defaultWrappedTaskSize(task.label, maxW, renderDensity)
                }
            },
            metaBadgeSize = { s ->
                metaSizesByText[s] ?: KanbanLayout.defaultBadgeSize(s, renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.colHdrSizes = colHdrPlaceables.mapValues { DiagramSize(it.value.width.toFloat(), it.value.height.toFloat()) }
            holder.taskLblSizes = measuredTaskLblSizes
            holder.metaSizes = metaSizesByText
        }
        holder.layout = layout
        onLayout(layout)

        val taskBoxPlaceables = HashMap<String, Placeable>()
        for (col in layout.columns) {
            for (pt in col.tasks) {
                val key = "${col.columnIndex}:${pt.taskIndex}"
                val m = taskBoxMeasurables[key] ?: continue
                taskBoxPlaceables[key] = m.measure(
                    Constraints.fixed(
                        pt.cardRect.width.roundToInt().coerceAtLeast(1),
                        pt.cardRect.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
        }

        val totalTasks = layout.columns.sumOf { it.tasks.size }
        val drawnTasks = if (activeCullViewport == null) {
            totalTasks
        } else {
            layout.columns.sumOf { col ->
                col.tasks.count { rectIntersectsViewport(it.cardRect, activeCullViewport, 8f * renderDensity) }
            }
        }
        val drawnColumns = if (activeCullViewport == null) {
            layout.columns.size
        } else {
            layout.columns.count { rectIntersectsViewport(it.rect, activeCullViewport, 8f * renderDensity) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = taskBoxPlaceables.size,
                totalNodes = totalTasks,
                composedEdgeLabels = 0,
                totalEdgeLabels = 0,
                drawnEdges = drawnTasks,
                totalEdges = totalTasks,
                drawnSubgraphs = drawnColumns,
                totalSubgraphs = layout.columns.size,
            ),
        )

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for (col in layout.columns) {
                colHdrPlaceables[col.columnIndex]?.place(
                    col.headerRect.x.roundToInt(),
                    col.headerRect.y.roundToInt(),
                )
                for (pt in col.tasks) {
                    val key = "${col.columnIndex}:${pt.taskIndex}"
                    taskBoxPlaceables[key]?.place(
                        pt.cardRect.x.roundToInt(),
                        pt.cardRect.y.roundToInt(),
                    )
                    taskLblPlaceables[key]?.place(
                        pt.labelRect.x.roundToInt(),
                        pt.labelRect.y.roundToInt(),
                    )
                    val tktRect = pt.ticketRect
                    if (tktRect != null) {
                        taskTktPlaceables[key]?.place(
                            tktRect.x.roundToInt(),
                            tktRect.y.roundToInt(),
                        )
                    }
                    val asgRect = pt.assignedRect
                    if (asgRect != null) {
                        taskAsgPlaceables[key]?.place(
                            asgRect.x.roundToInt(),
                            asgRect.y.roundToInt(),
                        )
                    }
                }
            }
        }
    }
}

private data class PremeasuredKanbanResult(
    val layout: KanbanDiagramLayout,
    val titleSize: DiagramSize?,
    val colHdrSizes: Map<Int, DiagramSize>,
    val taskLblSizes: Map<String, DiagramSize>,
    val metaSizes: Map<String, DiagramSize>,
)

private fun computePremeasuredKanbanLayout(
    diagram: KanbanDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredKanbanResult {
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
    val colHdrStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(13.5f),
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val colHdrSizes = HashMap<Int, DiagramSize>(diagram.columns.size)
    val metaSizes = HashMap<String, DiagramSize>()
    val asgStyle = TextStyle(
        color = theme.nodeText.copy(alpha = 0.75f),
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(10.5f),
        fontWeight = FontWeight.Normal,
    )
    val taskLblStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        fontWeight = FontWeight.Normal,
    )

    for ((colIdx, col) in diagram.columns.withIndex()) {
        val colHeaderLabel = if (!col.icon.isNullOrBlank()) {
            "${resolveMermaidIconGlyph(col.icon)} ${col.label}"
        } else {
            col.label
        }
        colHdrSizes[colIdx] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(colHeaderLabel, theme.monospaceFontFamily),
            style = colHdrStyle,
        )
        for (task in col.tasks) {
            task.ticket?.takeIf { it.isNotBlank() }?.let { tkt ->
                val ticketUrl = diagram.resolveTicketUrl(tkt)
                val tktStyle = TextStyle(
                    color = theme.nodeText.copy(alpha = 0.78f),
                    fontFamily = theme.fontFamily,
                    fontSize = theme.scaledSp(10.5f),
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (ticketUrl != null) TextDecoration.Underline else TextDecoration.None,
                )
                val sz = measureAnnotatedTextSize(
                    textMeasurer = textMeasurer,
                    densityObj = densityObj,
                    text = annotatedLabel(tkt),
                    style = tktStyle,
                )
                val prev = metaSizes[tkt]
                metaSizes[tkt] = DiagramSize(
                    maxOf(prev?.width ?: 0f, sz.width),
                    maxOf(prev?.height ?: 0f, sz.height),
                )
            }
            task.assigned?.takeIf { it.isNotBlank() }?.let { asg ->
                val sz = measureAnnotatedTextSize(
                    textMeasurer = textMeasurer,
                    densityObj = densityObj,
                    text = annotatedLabel(asg),
                    style = asgStyle,
                )
                val prev = metaSizes[asg]
                metaSizes[asg] = DiagramSize(
                    maxOf(prev?.width ?: 0f, sz.width),
                    maxOf(prev?.height ?: 0f, sz.height),
                )
            }
        }
    }

    val taskLblSizes = HashMap<String, DiagramSize>()
    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            density = renderDensity,
        )
    }
    val layout = KanbanLayout.layout(
        diagram = diagram,
        columnHeaderSize = { colIdx, col ->
            colHdrSizes[colIdx] ?: KanbanLayout.defaultHeaderSize(col.label, renderDensity)
        },
        taskLabelSize = { colIdx, taskIdx, task, maxW ->
            val key = "$colIdx:$taskIdx"
            val taskDisplayLabel = if (!task.icon.isNullOrBlank()) {
                "${resolveMermaidIconGlyph(task.icon)} ${task.label}"
            } else {
                task.label
            }
            val sz = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(taskDisplayLabel, theme.monospaceFontFamily),
                style = taskLblStyle,
                maxWidthPx = maxW.roundToInt().coerceAtLeast(1),
            )
            taskLblSizes[key] = sz
            sz
        },
        metaBadgeSize = { s ->
            metaSizes[s] ?: KanbanLayout.defaultBadgeSize(s, renderDensity)
        },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredKanbanResult(
        layout = layout,
        titleSize = titleSize,
        colHdrSizes = colHdrSizes,
        taskLblSizes = taskLblSizes,
        metaSizes = metaSizes,
    )
}

private fun DrawScope.drawKanbanUnderlay(
    layout: KanbanDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val colCorner = CornerRadius(6f * unit, 6f * unit)
    val cardCorner = CornerRadius(5f * unit, 5f * unit)

    for (col in layout.columns) {
        if (!rectIntersectsViewport(col.rect, viewport, 8f * unit)) continue
        val colFill = androidx.compose.ui.graphics.lerp(theme.seriesFill(col.columnIndex), theme.background, 0.45f)
        drawRoundRect(
            color = colFill,
            topLeft = Offset(col.rect.x, col.rect.y),
            size = Size(col.rect.width, col.rect.height),
            cornerRadius = colCorner,
        )
        drawRoundRect(
            color = theme.subgraphBorder,
            topLeft = Offset(col.rect.x, col.rect.y),
            size = Size(col.rect.width, col.rect.height),
            cornerRadius = colCorner,
            style = Stroke(width = 1.2f * unit),
        )

        for (pt in col.tasks) {
            val cr = pt.cardRect
            if (!rectIntersectsViewport(cr, viewport, 8f * unit)) continue
            drawRoundRect(
                color = theme.background,
                topLeft = Offset(cr.x, cr.y),
                size = Size(cr.width, cr.height),
                cornerRadius = cardCorner,
            )
            drawRoundRect(
                color = theme.nodeBorder,
                topLeft = Offset(cr.x, cr.y),
                size = Size(cr.width, cr.height),
                cornerRadius = cardCorner,
                style = Stroke(width = 1.1f * unit),
            )
            val stripeCol = priorityStripeColor(pt.task.priority, theme)
            if (stripeCol != null) {
                val insetY = 3.5f * unit
                val lineX = cr.x + 3f * unit
                drawLine(
                    color = stripeCol,
                    start = Offset(lineX, cr.y + insetY),
                    end = Offset(lineX, cr.bottom - insetY),
                    strokeWidth = 3.6f * unit,
                )
            }
        }
    }
}
