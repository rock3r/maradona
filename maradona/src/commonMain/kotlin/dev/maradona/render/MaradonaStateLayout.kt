package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ClassLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.StateBoxMetrics
import dev.maradona.layout.StateDiagramLayout
import dev.maradona.layout.StateLayout
import dev.maradona.model.Direction
import dev.maradona.model.StateDiagram
import dev.maradona.model.StateKind
import dev.maradona.model.StateNode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class StateLayoutHolder {
    var layout: StateDiagramLayout? = null
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
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(nodeGap = 44f, layerGap = 48f),
    availableWidthPx: Int = Int.MAX_VALUE,
    onLayout: (StateDiagramLayout) -> Unit = {},
) {
    val holder = remember { StateLayoutHolder() }
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

    Layout(
        modifier = modifier
            .semantics {
                contentDescription =
                    "State diagram. ${diagram.states.size} states, ${diagram.transitions.size} transitions."
            }
            .drawBehind {
                holder.layout?.let { drawStateUnderlay(it, compositeIds, theme) }
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
            for (s in diagram.states) {
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
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Start,
                                    ),
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .layoutId("sbox:${s.id}")
                        .focusable()
                        .semantics {
                            contentDescription = when {
                                s.kind == StateKind.Start -> "Start state"
                                s.kind == StateKind.End -> "End state"
                                s.kind == StateKind.Choice -> "Choice state ${s.id}"
                                s.kind == StateKind.Fork -> "Fork state ${s.id}"
                                s.kind == StateKind.Join -> "Join state ${s.id}"
                                isComp -> "Composite state ${s.label}"
                                else -> "State ${s.label}"
                            }
                        },
                )
            }
            for ((idx, note) in diagram.notes.withIndex()) {
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
                            color = Color(0xFF333333),
                            fontFamily = theme.fontFamily,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Start,
                        ),
                    )
                }
            }
            for ((idx, tr) in diagram.transitions.withIndex()) {
                if (!tr.label.isNullOrBlank()) {
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

        fun computeMetrics(s: StateNode): StateBoxMetrics {
            val isComp = s.id in compositeIds
            if (!isComp && s.kind != StateKind.Normal) {
                return StateLayout.defaultStateMetrics(s, renderDensity)
            }
            val hdr = hdrPlaceables[s.id]
            val desc = descPlaceables[s.id]
            val hdrW = hdr?.width?.toFloat() ?: 0f
            val hdrH = hdr?.height?.toFloat() ?: 0f
            val descW = desc?.width?.toFloat() ?: 0f
            val descH = desc?.height?.toFloat() ?: 0f
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
            transitionLabelSize = { idx, _ ->
                trLabelPlaceables[idx]?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            },
            noteBoxSize = { idx, _ ->
                val pl = notePlaceables[idx]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = opts,
        )

        var result = runLayout(scaledOptions)
        val breakpoint = 640.dp.roundToPx()
        val effectiveMaxWidth = minOf(
            if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE,
            availableWidthPx,
        )
        if (
            autoDirection &&
            (diagram.direction == Direction.LR || diagram.direction == Direction.RL) &&
            layoutOptions.directionOverride == null &&
            effectiveMaxWidth in 1 until breakpoint &&
            result.width > effectiveMaxWidth
        ) {
            result = runLayout(scaledOptions.copy(directionOverride = Direction.TB))
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

private fun DrawScope.drawStateUnderlay(
    layout: StateDiagramLayout,
    compositeIds: Set<String>,
    theme: MaradonaTheme,
) {
    val unit = density
    val cornerRadius = CornerRadius(8f * unit, 8f * unit)
    val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)
    val dottedEffect = PathEffect.dashPathEffect(floatArrayOf(3f * unit, 3f * unit), 0f)

    // 1. Composite state containers (ordered parent-first in layout.states).
    for (ps in layout.states) {
        if (ps.state.id !in compositeIds) continue
        val r = ps.rect
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
    val noteFill = Color(0xFFFFF5AD)
    val noteBorder = Color(0xFFAAAA33)
    for (note in layout.notes) {
        val r = note.rect
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
