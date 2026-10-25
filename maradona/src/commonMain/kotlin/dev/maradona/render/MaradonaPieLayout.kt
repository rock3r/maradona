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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.PieDiagramLayout
import dev.maradona.layout.PieLayout
import dev.maradona.model.PieDiagram
import kotlin.math.roundToInt

private class PieLayoutHolder {
    var layout: PieDiagramLayout? = null
}

/**
 * Custom Compose [Layout] for Mermaid `pie` diagrams.
 *
 * Title, slice percentage labels, and legend labels are real [BasicText] composables
 * with semantics; pie/donut wedges and legend swatches are drawn on the underlay canvas.
 */
@Composable
fun MaradonaPieLayout(
    diagram: PieDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (PieDiagramLayout) -> Unit = {},
) {
    val holder = remember { PieLayoutHolder() }
    val validSlices = remember(diagram) { PieLayout.validSlices(diagram) }
    val fractions = remember(validSlices) { PieLayout.sliceFractions(validSlices) }

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
                    "Pie chart. ${validSlices.size} slices."
                }
            }
            .drawBehind {
                holder.layout?.let { drawPieUnderlay(it, theme) }
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
            for ((idx, s) in validSlices.withIndex()) {
                val pct = fractions[idx] * 100.0
                val pctText = PieLayout.formatPercentage(pct)
                BasicText(
                    text = pctText,
                    modifier = Modifier.layoutId("pct:$idx"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.edgeLabelFontSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                val legendText = PieLayout.legendDisplayText(s, diagram.showData)
                BasicText(
                    text = annotatedLabel(legendText),
                    modifier = Modifier.layoutId("leg:$idx"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(13f),
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Box(
                    modifier = Modifier
                        .layoutId("legbox:$idx")
                        .focusable()
                        .semantics {
                            contentDescription = "${s.label}: $pctText (${PieLayout.formatSliceValue(s.value)})"
                        },
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()
        var titlePlaceable: Placeable? = null
        val pctPlaceables = HashMap<Int, Placeable>()
        val legPlaceables = HashMap<Int, Placeable>()
        val legBoxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("pct:") -> {
                    val idx = id.removePrefix("pct:").toIntOrNull() ?: continue
                    pctPlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("leg:") -> {
                    val idx = id.removePrefix("leg:").toIntOrNull() ?: continue
                    legPlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("legbox:") -> {
                    val idx = id.removePrefix("legbox:").toIntOrNull() ?: continue
                    legBoxMeasurables[idx] = m
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

        val layout = PieLayout.layout(
            diagram = diagram,
            pctLabelSize = { idx, s ->
                val pl = pctPlaceables[idx]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else PieLayout.defaultLabelSize(s, renderDensity)
            },
            legendLabelSize = { idx, s ->
                val pl = legPlaceables[idx]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else PieLayout.defaultLabelSize(s, renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val legBoxPlaceables = HashMap<Int, Placeable>()
        for (item in layout.legendItems) {
            val m = legBoxMeasurables[item.index] ?: continue
            val w = (item.labelRect.right - item.swatchRect.x).roundToInt().coerceAtLeast(1)
            val h = maxOf(item.swatchRect.height, item.labelRect.height).roundToInt().coerceAtLeast(1)
            legBoxPlaceables[item.index] = m.measure(Constraints.fixed(w, h))
        }

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for (ps in layout.slices) {
                val pr = ps.pctLabelRect ?: continue
                pctPlaceables[ps.index]?.place(pr.x.roundToInt(), pr.y.roundToInt())
            }
            for (item in layout.legendItems) {
                legPlaceables[item.index]?.place(item.labelRect.x.roundToInt(), item.labelRect.y.roundToInt())
                legBoxPlaceables[item.index]?.place(item.swatchRect.x.roundToInt(), item.swatchRect.y.roundToInt())
            }
        }
    }
}

private fun DrawScope.drawPieUnderlay(
    layout: PieDiagramLayout,
    theme: MaradonaTheme,
) {
    val unit = density
    val outerR = layout.outerRadius
    val innerR = layout.innerRadius
    val strokeW = layout.outerStrokeWidth

    for (ps in layout.slices) {
        if (ps.sweepAngleDeg <= 0.01f) continue
        val border = theme.seriesColor(ps.index)
        val fill = androidx.compose.ui.graphics.lerp(theme.seriesFill(ps.index), border, 0.30f)
        val cx = layout.center.x + ps.explodeOffset.x
        val cy = layout.center.y + ps.explodeOffset.y
        val outerRect = Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR)

        if (ps.sweepAngleDeg >= 359.9f) {
            if (innerR <= 0.5f) {
                drawCircle(color = fill, radius = outerR, center = Offset(cx, cy))
                drawCircle(color = border, radius = outerR, center = Offset(cx, cy), style = Stroke(width = strokeW))
            } else {
                val ringWidth = outerR - innerR
                val midR = innerR + ringWidth / 2f
                drawCircle(
                    color = fill,
                    radius = midR,
                    center = Offset(cx, cy),
                    style = Stroke(width = ringWidth),
                )
                drawCircle(color = border, radius = outerR, center = Offset(cx, cy), style = Stroke(width = strokeW))
                drawCircle(color = border, radius = innerR, center = Offset(cx, cy), style = Stroke(width = strokeW))
            }
        } else if (innerR <= 0.5f) {
            drawArc(
                color = fill,
                startAngle = ps.startAngleDeg,
                sweepAngle = ps.sweepAngleDeg,
                useCenter = true,
                topLeft = outerRect.topLeft,
                size = outerRect.size,
            )
            drawArc(
                color = border,
                startAngle = ps.startAngleDeg,
                sweepAngle = ps.sweepAngleDeg,
                useCenter = true,
                topLeft = outerRect.topLeft,
                size = outerRect.size,
                style = Stroke(width = strokeW, join = StrokeJoin.Round),
            )
        } else {
            val innerRect = Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
            val path = Path().apply {
                arcTo(outerRect, ps.startAngleDeg, ps.sweepAngleDeg, false)
                arcTo(innerRect, ps.startAngleDeg + ps.sweepAngleDeg, -ps.sweepAngleDeg, false)
                close()
            }
            drawPath(path = path, color = fill)
            drawPath(path = path, color = border, style = Stroke(width = strokeW, join = StrokeJoin.Round))
        }
    }

    for (item in layout.legendItems) {
        val border = theme.seriesColor(item.index)
        val fill = androidx.compose.ui.graphics.lerp(theme.seriesFill(item.index), border, 0.30f)
        val r = item.swatchRect
        val cr = CornerRadius(3f * unit, 3f * unit)
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
            style = Stroke(width = 1.2f * unit),
        )
    }
}
