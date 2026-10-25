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
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.RequirementBoxMetrics
import dev.maradona.layout.RequirementDiagramLayout
import dev.maradona.layout.RequirementLayout
import dev.maradona.model.Direction
import dev.maradona.model.ElementItem
import dev.maradona.model.RequirementDiagram
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class RequirementLayoutHolder {
    var layout: RequirementDiagramLayout? = null
}

/**
 * Custom Compose [Layout] for Mermaid `requirementDiagram` diagrams.
 *
 * Stereotype headers (`<<Requirement>>`, `<<Element>>`, etc.), requirement/element names,
 * attribute rows (`ID`, `Text`, `Risk`, `Verification`, `Type`, `Doc Ref`), and relationship
 * labels (`<<satisfies>>`, `<<contains>>`, etc.) are real [BasicText] composables; the
 * compartmentalized boxes, dashed/solid relationship splines, `⊕` containment markers, and
 * open-V arrowheads are drawn on the underlay canvas.
 */
@Composable
fun MaradonaRequirementLayout(
    diagram: RequirementDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(nodeGap = 48f, layerGap = 56f),
    availableWidthPx: Int = Int.MAX_VALUE,
    onLayout: (RequirementDiagramLayout) -> Unit = {},
) {
    val holder = remember { RequirementLayoutHolder() }
    val allItems = remember(diagram) { diagram.items }

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
                    "Requirement diagram. ${diagram.requirements.size} requirements, ${diagram.elements.size} elements, ${diagram.relations.size} relationships."
                }
            }
            .drawBehind {
                holder.layout?.let { drawRequirementUnderlay(it, theme) }
            },
        content = {
            if (!diagram.title.isNullOrBlank()) {
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
            for ((itemIdx, item) in allItems.withIndex()) {
                val textColor = parseCssColor(item.style?.textColor) ?: theme.nodeText
                val isBold = item.style?.fontWeightBold == true
                BasicText(
                    text = annotatedLabel(item.stereotype),
                    modifier = Modifier.layoutId("stereo:$itemIdx"),
                    style = TextStyle(
                        color = textColor.copy(alpha = 0.78f),
                        fontFamily = theme.fontFamily,
                        fontSize = theme.captionFontSize,
                        fontStyle = FontStyle.Italic,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    ),
                )
                BasicText(
                    text = annotatedLabel(item.name),
                    modifier = Modifier.layoutId("name:$itemIdx"),
                    style = TextStyle(
                        color = textColor,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(13.5f),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                for ((lineIdx, bodyLine) in item.bodyLines.withIndex()) {
                    BasicText(
                        text = annotatedLabel(bodyLine),
                        modifier = Modifier.layoutId("body:$itemIdx:$lineIdx"),
                        style = TextStyle(
                            color = textColor,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(11.5f),
                            fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .layoutId("box:$itemIdx")
                        .focusable()
                        .semantics {
                            val details = item.bodyLines.joinToString("; ")
                            contentDescription = buildString {
                                append("${item.stereotype} ${item.name}")
                                if (details.isNotEmpty()) append(": $details")
                            }
                        },
                )
            }
            for ((relIdx, rel) in diagram.relations.withIndex()) {
                BasicText(
                    text = annotatedLabel(rel.label),
                    modifier = Modifier.layoutId("rel:$relIdx"),
                    style = TextStyle(
                        color = theme.edgeLabelText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.captionFontSize,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val stereoPlaceables = HashMap<Int, Placeable>()
        val namePlaceables = HashMap<Int, Placeable>()
        val bodyPlaceables = HashMap<String, Placeable>()
        val relPlaceables = HashMap<Int, Placeable>()
        val boxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("stereo:") -> {
                    val idx = id.removePrefix("stereo:").toIntOrNull() ?: continue
                    stereoPlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("name:") -> {
                    val idx = id.removePrefix("name:").toIntOrNull() ?: continue
                    namePlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("body:") -> {
                    val key = id.removePrefix("body:")
                    bodyPlaceables[key] = m.measure(unbounded)
                }
                id.startsWith("rel:") -> {
                    val idx = id.removePrefix("rel:").toIntOrNull() ?: continue
                    relPlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("box:") -> {
                    val idx = id.removePrefix("box:").toIntOrNull() ?: continue
                    boxMeasurables[idx] = m
                }
            }
        }

        val idxByName = HashMap<String, Int>(allItems.size)
        for ((i, it) in allItems.withIndex()) idxByName[it.name] = i

        val padX = 14f * renderDensity
        val padY = 10f * renderDensity
        val lineGap = 4f * renderDensity

        fun computeMetrics(item: dev.maradona.model.RequirementDiagramItem): RequirementBoxMetrics {
            val itemIdx = idxByName[item.name] ?: return RequirementLayout.defaultBoxMetrics(item, renderDensity)
            val sPl = stereoPlaceables[itemIdx]
            val nPl = namePlaceables[itemIdx]
            val stereoW = sPl?.width?.toFloat() ?: (item.stereotype.length * 6.8f * renderDensity)
            val stereoH = sPl?.height?.toFloat() ?: (15f * renderDensity)
            val titleW = nPl?.width?.toFloat() ?: (item.name.length * 8f * renderDensity)
            val titleH = nPl?.height?.toFloat() ?: (18f * renderDensity)
            val headerH = padY + stereoH + lineGap + titleH + padY

            val lines = item.bodyLines
            if (lines.isEmpty()) {
                val w = maxOf(130f * renderDensity, stereoW + padX * 2f, titleW + padX * 2f)
                return RequirementBoxMetrics(
                    width = w,
                    height = headerH,
                    headerHeight = headerH,
                    stereotypeHeight = stereoH,
                    titleHeight = titleH,
                    bodyLineHeights = emptyList(),
                )
            }
            var maxBodyW = 0f
            val bodyHeights = lines.indices.map { lIdx ->
                val bPl = bodyPlaceables["$itemIdx:$lIdx"]
                val bw = bPl?.width?.toFloat() ?: (lines[lIdx].length * 6.8f * renderDensity)
                val bh = bPl?.height?.toFloat() ?: (16f * renderDensity)
                if (bw > maxBodyW) maxBodyW = bw
                bh
            }
            val bodyTotalH = padY + bodyHeights.sum() + (bodyHeights.size - 1) * lineGap + padY
            val w = maxOf(150f * renderDensity, stereoW + padX * 2f, titleW + padX * 2f, maxBodyW + padX * 2f)
            return RequirementBoxMetrics(
                width = w,
                height = headerH + bodyTotalH,
                headerHeight = headerH,
                stereotypeHeight = stereoH,
                titleHeight = titleH,
                bodyLineHeights = bodyHeights,
            )
        }

        val baseOptions = layoutOptions.copy(density = renderDensity)
        var layout = RequirementLayout.layout(
            diagram = diagram,
            itemBoxSize = ::computeMetrics,
            relationLabelSize = { relIdx, rel ->
                val pl = relPlaceables[relIdx]
                if (pl != null) {
                    DiagramSize(pl.width.toFloat() + 10f * renderDensity, pl.height.toFloat() + 4f * renderDensity)
                } else {
                    RequirementLayout.defaultRelationLabelSize(rel.label, renderDensity)
                }
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = baseOptions,
        )
        val effDir = baseOptions.directionOverride ?: diagram.direction
        if (
            autoDirection &&
            baseOptions.directionOverride == null &&
            (effDir == Direction.LR || effDir == Direction.RL) &&
            availableWidthPx in 1 until Int.MAX_VALUE &&
            layout.width > availableWidthPx * 1.15f
        ) {
            layout = RequirementLayout.layout(
                diagram = diagram,
                itemBoxSize = ::computeMetrics,
                relationLabelSize = { relIdx, rel ->
                    val pl = relPlaceables[relIdx]
                    if (pl != null) {
                        DiagramSize(pl.width.toFloat() + 10f * renderDensity, pl.height.toFloat() + 4f * renderDensity)
                    } else {
                        RequirementLayout.defaultRelationLabelSize(rel.label, renderDensity)
                    }
                },
                titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
                options = baseOptions.copy(directionOverride = Direction.TB),
            )
        }

        holder.layout = layout
        onLayout(layout)

        val boxPlaceables = HashMap<Int, Placeable>()
        for (pi in layout.items) {
            val itemIdx = idxByName[pi.item.name] ?: continue
            val m = boxMeasurables[itemIdx] ?: continue
            boxPlaceables[itemIdx] = m.measure(
                Constraints.fixed(
                    pi.rect.width.roundToInt().coerceAtLeast(1),
                    pi.rect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for (pi in layout.items) {
                val itemIdx = idxByName[pi.item.name] ?: continue
                val r = pi.rect
                boxPlaceables[itemIdx]?.place(r.x.roundToInt(), r.y.roundToInt())

                val sPl = stereoPlaceables[itemIdx]
                if (sPl != null) {
                    sPl.place(
                        (r.centerX - sPl.width / 2f).roundToInt(),
                        (r.y + padY).roundToInt(),
                    )
                }
                val nPl = namePlaceables[itemIdx]
                if (nPl != null) {
                    nPl.place(
                        (r.centerX - nPl.width / 2f).roundToInt(),
                        (r.y + padY + pi.stereotypeHeight + lineGap).roundToInt(),
                    )
                }
                var curBodyY = r.y + pi.headerHeight + padY
                for (lIdx in pi.bodyLineHeights.indices) {
                    val bPl = bodyPlaceables["$itemIdx:$lIdx"]
                    if (bPl != null) {
                        bPl.place(
                            (r.x + padX).roundToInt(),
                            curBodyY.roundToInt(),
                        )
                    }
                    curBodyY += pi.bodyLineHeights[lIdx] + lineGap
                }
            }
            for (pr in layout.relations) {
                val lr = pr.labelRect ?: continue
                val pl = relPlaceables[pr.index] ?: continue
                pl.place(
                    (lr.centerX - pl.width / 2f).roundToInt(),
                    (lr.centerY - pl.height / 2f).roundToInt(),
                )
            }
        }
    }
}

private fun DrawScope.drawRequirementUnderlay(
    layout: RequirementDiagramLayout,
    theme: MaradonaTheme,
) {
    val unit = density
    val defaultReqHeaderFill = theme.accentFill
    val defaultElemHeaderFill = theme.secondaryFill
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(7f * unit, 5f * unit), 0f)

    // 1. Requirement & Element boxes.
    for (pi in layout.items) {
        val r = pi.rect
        val st = pi.item.style
        val customFill = parseCssColor(st?.fill)
        val bodyFill = customFill ?: theme.nodeFill
        val headerFill = customFill ?: if (pi.item is ElementItem) defaultElemHeaderFill else defaultReqHeaderFill
        val borderCol = parseCssColor(st?.stroke) ?: theme.nodeBorder
        val strokeW = (st?.strokeWidth ?: 1.4f) * unit

        // Body background + header tint.
        drawRect(
            color = bodyFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = headerFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, pi.headerHeight.coerceAtMost(r.height)),
        )
        if (pi.bodyLineHeights.isNotEmpty() && pi.headerHeight < r.height) {
            val divY = r.y + pi.headerHeight
            drawLine(
                color = borderCol,
                start = Offset(r.x, divY),
                end = Offset(r.right, divY),
                strokeWidth = 1.1f * unit,
            )
        }
        drawRect(
            color = borderCol,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = strokeW),
        )
    }

    // 2. Relationship edges, `⊕` containment markers, open-V arrowheads, and label pills.
    val edgeColor = theme.edge
    for (pr in layout.relations) {
        val pts = pr.points
        if (pts.size < 2) continue
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                lineTo(pts[i].x, pts[i].y)
            }
        }
        drawPath(
            path = path,
            color = edgeColor,
            style = Stroke(
                width = 1.5f * unit,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = if (pr.relation.isContains) null else dashEffect,
            ),
        )

        if (pr.relation.isContains) {
            drawContainsMarker(pts[0], pts[1], edgeColor, theme.background, unit)
        } else {
            drawOpenArrow(pts[pts.lastIndex - 1], pts.last(), edgeColor, unit)
        }

        val lr = pr.labelRect
        if (lr != null) {
            drawRoundRect(
                color = theme.edgeLabelBackground,
                topLeft = Offset(lr.x, lr.y),
                size = Size(lr.width, lr.height),
                cornerRadius = CornerRadius(4f * unit, 4f * unit),
            )
        }
    }
}

private fun DrawScope.drawContainsMarker(
    at: DiagramPoint,
    towards: DiagramPoint,
    color: Color,
    fill: Color,
    unit: Float,
) {
    val angle = atan2(towards.y - at.y, towards.x - at.x)
    val radius = 5.5f * unit
    val cx = at.x + cos(angle) * radius
    val cy = at.y + sin(angle) * radius
    val center = Offset(cx, cy)
    drawCircle(
        color = fill,
        radius = radius,
        center = center,
    )
    drawCircle(
        color = color,
        radius = radius,
        center = center,
        style = Stroke(width = 1.4f * unit),
    )
    val crossHalf = radius * 0.65f
    drawLine(
        color = color,
        start = Offset(cx - crossHalf, cy),
        end = Offset(cx + crossHalf, cy),
        strokeWidth = 1.3f * unit,
    )
    drawLine(
        color = color,
        start = Offset(cx, cy - crossHalf),
        end = Offset(cx, cy + crossHalf),
        strokeWidth = 1.3f * unit,
    )
}

private fun DrawScope.drawOpenArrow(
    from: DiagramPoint,
    tip: DiagramPoint,
    color: Color,
    unit: Float,
) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val wingLen = 8.5f * unit
    val spread = 0.48f
    val x1 = tip.x - wingLen * cos(angle - spread)
    val y1 = tip.y - wingLen * sin(angle - spread)
    val x2 = tip.x - wingLen * cos(angle + spread)
    val y2 = tip.y - wingLen * sin(angle + spread)
    val p = Path().apply {
        moveTo(x1, y1)
        lineTo(tip.x, tip.y)
        lineTo(x2, y2)
    }
    drawPath(
        path = p,
        color = color,
        style = Stroke(width = 1.6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
