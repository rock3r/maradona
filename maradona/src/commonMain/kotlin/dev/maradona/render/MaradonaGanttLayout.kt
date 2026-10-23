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
import androidx.compose.ui.semantics.contentDescription
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
import dev.maradona.parse.GanttParser
import kotlin.math.roundToInt

private class GanttLayoutHolder {
    var layout: GanttDiagramLayout? = null
}

private val SECTION_BAND_FILLS_LIGHT = listOf(
    Color(0xFFF5F2E8),
    Color(0xFFEDEAE0),
)

private val SECTION_BAND_FILLS_DARK = listOf(
    Color(0xFF1C1B1A),
    Color(0xFF242321),
)

private val DEFAULT_BAR_FILLS_LIGHT = listOf(
    Color(0xFFD6E6F4) to Color(0xFF205EA6),
    Color(0xFFD5EBE6) to Color(0xFF24837B),
    Color(0xFFF3E7C4) to Color(0xFFAD8301),
    Color(0xFFEAD7E4) to Color(0xFFA02F6F),
)

private val DEFAULT_BAR_FILLS_DARK = listOf(
    Color(0xFF1B344B) to Color(0xFF4385BE),
    Color(0xFF183734) to Color(0xFF3AA99F),
    Color(0xFF3B2E12) to Color(0xFFD0A215),
    Color(0xFF3A1D2E) to Color(0xFFCE5D97),
)

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
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (GanttDiagramLayout) -> Unit = {},
) {
    val holder = remember { GanttLayoutHolder() }

    Layout(
        modifier = modifier
            .semantics {
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
                        fontSize = 16.sp,
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            for (t in diagram.tasks) {
                BasicText(
                    text = annotatedLabel(t.label),
                    modifier = Modifier.layoutId("tasklbl:${t.id}"),
                    style = TextStyle(
                        color = if (t.isVerticalMarker) Color(0xFFAF3029) else theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = 12.sp,
                        fontWeight = if (t.isCritical || t.isMilestone) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
                Box(
                    modifier = Modifier
                        .layoutId("taskbox:${t.id}")
                        .focusable()
                        .semantics {
                            contentDescription = "Gantt task ${t.label}"
                        },
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
                        fontSize = 11.sp,
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
                            fontSize = 11.sp,
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
    val isDark = (theme.background.red * 0.299f + theme.background.green * 0.587f + theme.background.blue * 0.114f) < 0.45f
    val sectionBands = if (isDark) SECTION_BAND_FILLS_DARK else SECTION_BAND_FILLS_LIGHT
    val defaultBars = if (isDark) DEFAULT_BAR_FILLS_DARK else DEFAULT_BAR_FILLS_LIGHT

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
                color = if (isDark) Color(0x14FFFFFF) else Color(0x0A000000),
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
            color = if (isDark) Color(0x24000000) else Color(0x166F6E69),
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
        val (defaultFill, defaultStroke) = defaultBars[pt.sectionIndex.coerceAtLeast(0).mod(defaultBars.size)]
        val (fill, border) = if (isDark) {
            when {
                t.isCritical && t.status == GanttTaskStatus.Done -> Color(0xFF3E2220) to Color(0xFFD14D41)
                t.isCritical && t.status == GanttTaskStatus.Active -> Color(0xFF5A2521) to Color(0xFFEB6F65)
                t.isCritical -> Color(0xFF471E1B) to Color(0xFFD14D41)
                t.status == GanttTaskStatus.Done -> Color(0xFF282726) to Color(0xFF6F6E69)
                t.status == GanttTaskStatus.Active -> Color(0xFF1F3E5A) to Color(0xFF5B9BD5)
                else -> defaultFill to defaultStroke
            }
        } else {
            when {
                t.isCritical && t.status == GanttTaskStatus.Done -> Color(0xFFF2D2CF) to Color(0xFFAF3029)
                t.isCritical && t.status == GanttTaskStatus.Active -> Color(0xFFF8B9B2) to Color(0xFFAF3029)
                t.isCritical -> Color(0xFFFCE6E4) to Color(0xFFD14D41)
                t.status == GanttTaskStatus.Done -> Color(0xFFE6E4D9) to Color(0xFF878580)
                t.status == GanttTaskStatus.Active -> Color(0xFFBFE0F8) to Color(0xFF205EA6)
                else -> defaultFill to defaultStroke
            }
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
            color = Color(0xFFAF3029),
            start = Offset(vt.x, vt.topY),
            end = Offset(vt.x, vt.bottomY),
            strokeWidth = 1.6f * unit,
            pathEffect = dash,
        )
    }
}
