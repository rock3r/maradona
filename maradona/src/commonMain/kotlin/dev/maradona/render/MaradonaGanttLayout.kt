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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.GanttDiagramLayout
import dev.maradona.layout.GanttLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.model.GanttDiagram
import dev.maradona.model.GanttTask
import dev.maradona.model.GanttTaskStatus
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.parse.GanttParser
import kotlin.math.roundToInt

private class GanttLayoutHolder {
    var layout: GanttDiagramLayout? = null
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
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onLayout: (GanttDiagramLayout) -> Unit = {},
) {
    val holder = remember { GanttLayoutHolder() }
    val uriHandler = LocalUriHandler.current

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
            }
            .drawBehind {
                holder.layout?.let { drawGanttUnderlay(it, theme) }
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
            val allSectionNames = (diagram.sections + diagram.tasks.mapNotNull { it.section }).distinct()
            for (sec in allSectionNames) {
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
            val candidateTicks = remember(diagram) { GanttLayout.candidateTicks(diagram) }
            for ((ms, lbl) in candidateTicks) {
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
                if (diagram.topAxis) {
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
        val tickSizeByLabel = HashMap<String, DiagramSize>()

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
                val pl = taskLblPlaceables[t.id]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else GanttLayout.defaultLabelSize(t.label, renderDensity)
            },
            sectionLabelSize = { s ->
                val pl = secPlaceables[s]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else GanttLayout.defaultLabelSize(s, renderDensity)
            },
            tickLabelSize = { s -> tickSizeByLabel[s] ?: GanttLayout.defaultTickSize(s, renderDensity) },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
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

private fun DrawScope.drawGanttUnderlay(
    layout: GanttDiagramLayout,
    theme: MaradonaTheme,
) {
    val unit = density
    val plot = layout.plotRect
    val isDark = theme.isDark
    val sectionBands = listOf(
        theme.subgraphFill.copy(alpha = 0.72f),
        theme.mutedFill.copy(alpha = 0.85f),
    )

    // 1. Section and row background bands.
    for (sec in layout.sections) {
        val bandFill = sectionBands[sec.sectionIndex.mod(sectionBands.size)]
        drawRect(
            color = bandFill,
            topLeft = Offset(sec.rect.x, sec.rect.y),
            size = Size(sec.rect.width + plot.width, sec.rect.height),
        )
    }
    for ((idx, rRect) in layout.rowRects.withIndex()) {
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
        drawRect(
            color = theme.mutedBorder.copy(alpha = 0.16f),
            topLeft = Offset(ex.x, ex.y),
            size = Size(ex.width, ex.height),
        )
    }

    // 3. Plot frame & vertical tick grid lines.
    for (tick in layout.ticks) {
        drawLine(
            color = theme.subgraphBorder.copy(alpha = 0.55f),
            start = Offset(tick.x, plot.y),
            end = Offset(tick.x, plot.bottom + 4f * unit),
            strokeWidth = 1f * unit,
        )
    }
    drawRect(
        color = theme.subgraphBorder,
        topLeft = Offset(plot.x, plot.y),
        size = Size(plot.width, plot.height),
        style = Stroke(width = 1.2f * unit),
    )

    // 4. Task bars & milestone diamonds.
    for (pt in layout.tasks) {
        val t = pt.task
        val r = pt.barRect
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
        drawLine(
            color = theme.criticalBorder,
            start = Offset(vt.x, vt.topY),
            end = Offset(vt.x, vt.bottomY),
            strokeWidth = 1.6f * unit,
            pathEffect = dash,
        )
    }
}
