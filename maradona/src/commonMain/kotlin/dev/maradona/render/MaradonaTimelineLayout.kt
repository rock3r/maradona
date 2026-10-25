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
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.TimelineDiagramLayout
import dev.maradona.layout.TimelineLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.TimelineDiagram
import dev.maradona.model.TimelineDirection
import kotlin.math.roundToInt

private class TimelineLayoutHolder {
    var layout: TimelineDiagramLayout? = null
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
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onLayout: (TimelineDiagramLayout) -> Unit = {},
) {
    val holder = remember { TimelineLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val sectionRuns = remember(diagram) { TimelineLayout.buildSectionRuns(diagram) }

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
            }
            .drawBehind {
                holder.layout?.let { drawTimelineUnderlay(it, theme) }
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
            for (run in sectionRuns) {
                val secName = run.name ?: continue
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
                for ((eIdx, evText) in p.events.withIndex()) {
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
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()
        val eventWrapConstraint = Constraints(maxWidth = (220f * renderDensity).roundToInt().coerceAtLeast(1))
        var titlePlaceable: Placeable? = null
        val secRunPlaceables = HashMap<Int, Placeable>()
        val secSizeByName = HashMap<String, DiagramSize>()
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
                val pl = periodLblPlaceables[p.id]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else TimelineLayout.defaultTextSize(p.title, renderDensity)
            },
            eventTextSize = { p, eIdx, s ->
                val pl = eventLblPlaceables["${p.id}:$eIdx"]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else TimelineLayout.defaultTextSize(s, renderDensity)
            },
            sectionTitleSize = { s ->
                secSizeByName[s] ?: TimelineLayout.defaultTextSize(s, renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
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

private fun DrawScope.drawTimelineUnderlay(
    layout: TimelineDiagramLayout,
    theme: MaradonaTheme,
) {
    val unit = density
    val dash = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)

    fun colorSetFor(index: Int): TimelineColorSet {
        val border = theme.seriesColor(index)
        val sectionFill = theme.seriesFill(index)
        val periodFill = androidx.compose.ui.graphics.lerp(sectionFill, theme.nodeFill, 0.35f)
        val eventFill = androidx.compose.ui.graphics.lerp(sectionFill, theme.background, 0.58f)
        return TimelineColorSet(sectionFill, periodFill, eventFill, border)
    }

    // 1. Continuous timeline spine with arrow tip.
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

    // 2. Section banners.
    for (sec in layout.sections) {
        val colorIdx = if (layout.disableMulticolor) 0 else sec.sectionIndex
        val colors = colorSetFor(colorIdx)
        val r = sec.rect
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
        if (cStart != null && cEnd != null) {
            drawLine(
                color = colors.border.copy(alpha = 0.75f),
                start = Offset(cStart.x, cStart.y),
                end = Offset(cEnd.x, cEnd.y),
                strokeWidth = 1.6f * unit,
                pathEffect = dash,
            )
        }

        val pr = pp.rect
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

        val eCr = CornerRadius(6f * unit, 6f * unit)
        for (ev in pp.events) {
            val er = ev.rect
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
