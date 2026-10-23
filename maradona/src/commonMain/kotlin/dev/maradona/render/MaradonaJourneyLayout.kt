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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.JourneyDiagramLayout
import dev.maradona.layout.JourneyLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.model.JourneyDiagram
import dev.maradona.model.JourneyMood
import kotlin.math.roundToInt

private class JourneyLayoutHolder {
    var layout: JourneyDiagramLayout? = null
}

private data class JourneySectionPalette(
    val sectionFill: Color,
    val taskFill: Color,
    val border: Color,
)

private val JOURNEY_SECTION_PALETTE_LIGHT = listOf(
    JourneySectionPalette(Color(0xFFC7DFF2), Color(0xFFEDF4FA), Color(0xFF205EA6)),
    JourneySectionPalette(Color(0xFFC3E4DF), Color(0xFFECF6F4), Color(0xFF24837B)),
    JourneySectionPalette(Color(0xFFEFDFB4), Color(0xFFFAF4E4), Color(0xFFAD8301)),
    JourneySectionPalette(Color(0xFFE5CADB), Color(0xFFF7EEF4), Color(0xFFA02F6F)),
    JourneySectionPalette(Color(0xFFD9E6B8), Color(0xFFF3F7E6), Color(0xFF66800B)),
    JourneySectionPalette(Color(0xFFF2D2C2), Color(0xFFFBF0EA), Color(0xFFBC5215)),
)

private val JOURNEY_SECTION_PALETTE_DARK = listOf(
    JourneySectionPalette(Color(0xFF1F3D58), Color(0xFF162838), Color(0xFF4385BE)),
    JourneySectionPalette(Color(0xFF1C423E), Color(0xFF142C29), Color(0xFF3AA99F)),
    JourneySectionPalette(Color(0xFF473715), Color(0xFF2D2310), Color(0xFFD0A215)),
    JourneySectionPalette(Color(0xFF462237), Color(0xFF2C1723), Color(0xFFCE5D97)),
    JourneySectionPalette(Color(0xFF323E14), Color(0xFF20280E), Color(0xFF879A39)),
    JourneySectionPalette(Color(0xFF4A2817), Color(0xFF2E1A10), Color(0xFFDA702C)),
)

private val ACTOR_COLORS = listOf(
    Color(0xFF5DA5DA) to Color(0xFF1D4F7A),
    Color(0xFF60BD68) to Color(0xFF236329),
    Color(0xFFFAA43A) to Color(0xFF8C5108),
    Color(0xFFF17CB0) to Color(0xFF872652),
    Color(0xFFB276B2) to Color(0xFF5D2D5D),
    Color(0xFFDECF3F) to Color(0xFF756B0E),
)

/**
 * Custom Compose [Layout] for Mermaid `journey` (User Journey) diagrams.
 *
 * Actor legend labels, section headers, task labels, and the optional chart title are
 * real [BasicText] composables; actor circles, section banners, task cards, dashed
 * score tracks, and satisfaction mood faces are drawn on the underlay canvas.
 */
@Composable
fun MaradonaJourneyLayout(
    diagram: JourneyDiagram,
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (JourneyDiagramLayout) -> Unit = {},
) {
    val holder = remember { JourneyLayoutHolder() }
    val allActors = remember(diagram) { JourneyLayout.allActors(diagram) }
    val sectionRuns = remember(diagram) { JourneyLayout.buildSectionRuns(diagram) }

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
                    "User journey diagram. ${diagram.tasks.size} tasks."
                }
            }
            .drawBehind {
                holder.layout?.let { drawJourneyUnderlay(it, theme) }
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
            for ((aIdx, actor) in allActors.withIndex()) {
                BasicText(
                    text = annotatedLabel(actor),
                    modifier = Modifier.layoutId("actor:$aIdx"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for (t in diagram.tasks) {
                BasicText(
                    text = annotatedLabel(t.label),
                    modifier = Modifier.layoutId("tlbl:${t.id}"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
                Box(
                    modifier = Modifier
                        .layoutId("tbox:${t.id}")
                        .focusable()
                        .semantics {
                            val actorsDesc = if (t.actors.isNotEmpty()) " by ${t.actors.joinToString(", ")}" else ""
                            contentDescription = "Journey task ${t.label}, score ${t.score}$actorsDesc"
                        },
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()
        val taskWrapConstraint = Constraints(maxWidth = (180f * renderDensity).roundToInt().coerceAtLeast(1))
        var titlePlaceable: Placeable? = null
        val actorPlaceables = HashMap<Int, Placeable>()
        val actorSizeByName = HashMap<String, DiagramSize>()
        val secRunPlaceables = HashMap<Int, Placeable>()
        val secSizeByName = HashMap<String, DiagramSize>()
        val taskLblPlaceables = HashMap<String, Placeable>()
        val taskBoxMeasurables = HashMap<String, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("actor:") -> {
                    val aIdx = id.removePrefix("actor:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    actorPlaceables[aIdx] = pl
                    val name = allActors.getOrNull(aIdx)
                    if (name != null) {
                        actorSizeByName[name] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
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
                id.startsWith("tlbl:") -> taskLblPlaceables[id.removePrefix("tlbl:")] = m.measure(taskWrapConstraint)
                id.startsWith("tbox:") -> taskBoxMeasurables[id.removePrefix("tbox:")] = m
            }
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = JourneyLayout.layout(
            diagram = diagram,
            taskLabelSize = { t ->
                val pl = taskLblPlaceables[t.id]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else JourneyLayout.defaultTextSize(t.label, renderDensity)
            },
            sectionTitleSize = { s ->
                secSizeByName[s] ?: JourneyLayout.defaultTextSize(s, renderDensity)
            },
            actorLabelSize = { a ->
                actorSizeByName[a] ?: JourneyLayout.defaultTextSize(a, renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val taskBoxPlaceables = HashMap<String, Placeable>()
        for (pt in layout.tasks) {
            val m = taskBoxMeasurables[pt.task.id] ?: continue
            val w = pt.cardRect.width.roundToInt().coerceAtLeast(1)
            val h = pt.cardRect.height.roundToInt().coerceAtLeast(1)
            taskBoxPlaceables[pt.task.id] = m.measure(Constraints.fixed(w, h))
        }

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for (al in layout.actorLegend) {
                val pl = actorPlaceables[al.actorIndex] ?: continue
                pl.place(al.labelRect.x.roundToInt(), al.labelRect.y.roundToInt())
            }
            for (sec in layout.sections) {
                val pl = secRunPlaceables[sec.runIndex] ?: continue
                pl.place(
                    (sec.rect.centerX - pl.width / 2f).roundToInt(),
                    (sec.rect.centerY - pl.height / 2f).roundToInt(),
                )
            }
            for (pt in layout.tasks) {
                taskBoxPlaceables[pt.task.id]?.place(
                    pt.cardRect.x.roundToInt(),
                    pt.cardRect.y.roundToInt(),
                )
                val tPl = taskLblPlaceables[pt.task.id]
                if (tPl != null) {
                    tPl.place(
                        (pt.cardRect.centerX - tPl.width / 2f).roundToInt(),
                        (pt.cardRect.centerY - tPl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawJourneyUnderlay(
    layout: JourneyDiagramLayout,
    theme: MaradonaTheme,
) {
    val unit = density
    val isDark = (theme.background.red * 0.299f + theme.background.green * 0.587f + theme.background.blue * 0.114f) < 0.45f
    val sectionPalette = if (isDark) JOURNEY_SECTION_PALETTE_DARK else JOURNEY_SECTION_PALETTE_LIGHT
    val dash = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)

    // 1. Actor legend badges.
    for (al in layout.actorLegend) {
        val (fill, stroke) = ACTOR_COLORS[al.actorIndex.mod(ACTOR_COLORS.size)]
        val center = Offset(al.circleCenter.x, al.circleCenter.y)
        drawCircle(color = fill, radius = al.circleRadius, center = center)
        drawCircle(color = stroke, radius = al.circleRadius, center = center, style = Stroke(width = 1.4f * unit))
    }

    // 2. Section banners.
    for (sec in layout.sections) {
        val colors = sectionPalette[sec.sectionIndex.mod(sectionPalette.size)]
        val r = sec.rect
        val cr = CornerRadius(7f * unit, 7f * unit)
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
            style = Stroke(width = 1.5f * unit),
        )
    }

    // 3. Horizontal journey spine + arrowhead.
    val spineColor = theme.nodeBorder
    drawLine(
        color = spineColor,
        start = Offset(layout.spineStart.x, layout.spineStart.y),
        end = Offset(layout.spineEnd.x, layout.spineEnd.y),
        strokeWidth = 2.4f * unit,
        cap = StrokeCap.Round,
    )
    val arrowSize = 7f * unit
    val arrowPath = Path().apply {
        val ex = layout.spineEnd.x
        val ey = layout.spineEnd.y
        moveTo(ex, ey)
        lineTo(ex - arrowSize, ey - arrowSize * 0.7f)
        lineTo(ex - arrowSize, ey + arrowSize * 0.7f)
        close()
    }
    drawPath(path = arrowPath, color = spineColor)

    // 4. Task score lines, mood faces, task cards, and actor dots.
    val faceFill = if (isDark) Color(0xFF3E351B) else Color(0xFFFFF8DC)
    val faceStroke = if (isDark) Color(0xFFD0A215) else Color(0xFF878580)
    val featureColor = if (isDark) Color(0xFFCECDC3) else Color(0xFF575653)

    for (pt in layout.tasks) {
        val colors = sectionPalette[pt.sectionIndex.mod(sectionPalette.size)]

        // Vertical dashed score line.
        drawLine(
            color = theme.subgraphBorder.copy(alpha = 0.75f),
            start = Offset(pt.lineStart.x, pt.lineStart.y),
            end = Offset(pt.lineEnd.x, pt.lineEnd.y),
            strokeWidth = 1.3f * unit,
            pathEffect = dash,
        )

        // Task card.
        val cr = CornerRadius(6f * unit, 6f * unit)
        val r = pt.cardRect
        drawRoundRect(
            color = colors.taskFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
        )
        drawRoundRect(
            color = colors.border,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Stroke(width = 1.4f * unit),
        )

        // Actor dots along top of task card.
        for (dot in pt.actorDots) {
            val (aFill, aStroke) = ACTOR_COLORS[dot.actorIndex.mod(ACTOR_COLORS.size)]
            val dCenter = Offset(dot.center.x, dot.center.y)
            drawCircle(color = aFill, radius = dot.radius, center = dCenter)
            drawCircle(color = aStroke, radius = dot.radius, center = dCenter, style = Stroke(width = 1.2f * unit))
        }

        // Satisfaction mood face.
        val fc = Offset(pt.faceCenter.x, pt.faceCenter.y)
        val fr = pt.faceRadius
        drawCircle(color = faceFill, radius = fr, center = fc)
        drawCircle(color = faceStroke, radius = fr, center = fc, style = Stroke(width = 1.6f * unit))

        val eyeOffsetX = fr * 0.34f
        val eyeOffsetY = fr * 0.22f
        val eyeR = 1.6f * unit
        drawCircle(color = featureColor, radius = eyeR, center = Offset(fc.x - eyeOffsetX, fc.y - eyeOffsetY))
        drawCircle(color = featureColor, radius = eyeR, center = Offset(fc.x + eyeOffsetX, fc.y - eyeOffsetY))

        when (pt.mood) {
            JourneyMood.Happy -> {
                drawArc(
                    color = featureColor,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(fc.x - fr * 0.45f, fc.y - fr * 0.18f),
                    size = Size(fr * 0.90f, fr * 0.68f),
                    style = Stroke(width = 1.6f * unit, cap = StrokeCap.Round),
                )
            }
            JourneyMood.Sad -> {
                drawArc(
                    color = featureColor,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(fc.x - fr * 0.45f, fc.y + fr * 0.14f),
                    size = Size(fr * 0.90f, fr * 0.68f),
                    style = Stroke(width = 1.6f * unit, cap = StrokeCap.Round),
                )
            }
            JourneyMood.Neutral -> {
                drawLine(
                    color = featureColor,
                    start = Offset(fc.x - fr * 0.36f, fc.y + fr * 0.28f),
                    end = Offset(fc.x + fr * 0.36f, fc.y + fr * 0.28f),
                    strokeWidth = 1.6f * unit,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
