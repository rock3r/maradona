package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ClassLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.ErDiagramLayout
import dev.maradona.layout.ErEntityMetrics
import dev.maradona.layout.ErLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.model.Direction
import dev.maradona.model.ErCardinality
import dev.maradona.model.ErDiagram
import dev.maradona.model.ErEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class ErLayoutHolder {
    var layout: ErDiagramLayout? = null
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
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(nodeGap = 52f, layerGap = 58f, subgraphPaddingX = 28f),
    availableWidthPx: Int = Int.MAX_VALUE,
    onLayout: (ErDiagramLayout) -> Unit = {},
) {
    val holder = remember { ErLayoutHolder() }

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
                    "Entity relationship diagram. ${diagram.entities.size} entities, ${diagram.relationships.size} relationships."
                }
            }
            .drawBehind {
                holder.layout?.let { drawErUnderlay(it, theme) }
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
            for (e in diagram.entities) {
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
                            fontSize = 11.5.sp,
                        ),
                    )
                    BasicText(
                        text = attr.name,
                        modifier = Modifier.layoutId("eattr:${e.id}:$rIdx:1"),
                        style = TextStyle(
                            color = textColor,
                            fontFamily = theme.fontFamily,
                            fontSize = 11.5.sp,
                        ),
                    )
                    if (hasKeys) {
                        BasicText(
                            text = attr.keys.joinToString(", "),
                            modifier = Modifier.layoutId("eattr:${e.id}:$rIdx:2"),
                            style = TextStyle(
                                color = textColor,
                                fontFamily = theme.fontFamily,
                                fontSize = 11.sp,
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
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Italic,
                            ),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .layoutId("ebox:${e.id}")
                        .focusable()
                        .semantics {
                            contentDescription = "Entity ${e.label}"
                        },
                )
            }
            for (sg in diagram.subgraphs) {
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

        fun computeMetrics(e: ErEntity): ErEntityMetrics {
            val hdr = hdrPlaceables[e.id]
            val hdrW = (hdr?.width?.toFloat() ?: 0f) + hdrPadX * 2f
            val hdrH = max(32f * renderDensity, (hdr?.height?.toFloat() ?: 0f) + hdrPadY * 2f)
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
                    val pl = cellPlaceables["${e.id}:$rIdx:$cIdx"]
                    val w = (pl?.width?.toFloat() ?: 0f) + cellPadX * 2f
                    val h = (pl?.height?.toFloat() ?: 0f) + cellPadY * 2f
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
            relationshipLabelSize = { idx, _ ->
                relLabelPlaceables[idx]?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            },
            subgraphLabelSize = { sg ->
                val pl = sgPlaceables[sg.id]
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
        for (pe in result.entities) {
            boxMeasurables[pe.entity.id]?.let { m ->
                boxPlaceables[pe.entity.id] = m.measure(
                    Constraints.fixed(pe.rect.width.roundToInt(), pe.rect.height.roundToInt()),
                )
            }
        }

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

private fun DrawScope.drawErUnderlay(
    layout: ErDiagramLayout,
    theme: MaradonaTheme,
) {
    val unit = density
    val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)

    // 1. Subgraph cluster boxes (parent-first).
    for (psg in layout.subgraphs) {
        val r = psg.rect
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
