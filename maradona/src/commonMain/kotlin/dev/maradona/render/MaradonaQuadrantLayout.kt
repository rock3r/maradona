package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import dev.maradona.layout.QuadrantDiagramLayout
import dev.maradona.layout.QuadrantLayout
import dev.maradona.model.QuadrantDiagram
import kotlin.math.roundToInt

private class QuadrantLayoutHolder {
    var layout: QuadrantDiagramLayout? = null
}

/**
 * Custom Compose [Layout] for Mermaid `quadrantChart` diagrams.
 *
 * Quadrant labels, X/Y axis labels, point labels, and the optional chart title are
 * real [BasicText] composables; the four quadrant backgrounds, crosshair dividers,
 * outer border, and styled data points are drawn on the underlay canvas.
 */
@Composable
fun MaradonaQuadrantLayout(
    diagram: QuadrantDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (QuadrantDiagramLayout) -> Unit = {},
) {
    val holder = remember { QuadrantLayoutHolder() }
    val quadrantSpecs = remember(diagram) {
        listOf(
            1 to diagram.quadrant1Text,
            2 to diagram.quadrant2Text,
            3 to diagram.quadrant3Text,
            4 to diagram.quadrant4Text,
        ).mapNotNull { (qNum, txt) ->
            txt?.takeIf { it.isNotBlank() }?.let { qNum to it }
        }
    }
    val axisSpecs = remember(diagram) {
        listOf(
            0 to diagram.xAxisLeftText,
            1 to diagram.xAxisRightText,
            2 to diagram.yAxisBottomText,
            3 to diagram.yAxisTopText,
        ).mapNotNull { (slot, txt) ->
            txt?.takeIf { it.isNotBlank() }?.let { slot to it }
        }
    }

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
                    "Quadrant chart. ${diagram.points.size} points."
                }
            }
            .drawBehind {
                holder.layout?.let { drawQuadrantUnderlay(it, theme) }
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
            for ((qNum, txt) in quadrantSpecs) {
                BasicText(
                    text = annotatedLabel(txt),
                    modifier = Modifier.layoutId("q:$qNum"),
                    style = TextStyle(
                        color = theme.nodeText.copy(alpha = 0.85f),
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(13f),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for ((slot, axTxt) in axisSpecs) {
                BasicText(
                    text = annotatedLabel(axTxt),
                    modifier = Modifier.layoutId("ax:$slot"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(12.5f),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for (p in diagram.points) {
                BasicText(
                    text = annotatedLabel(p.label),
                    modifier = Modifier.layoutId("ptlbl:${p.id}"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(11.5f),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
                Box(
                    modifier = Modifier
                        .layoutId("ptbox:${p.id}")
                        .focusable()
                        .semantics {
                            contentDescription = "Quadrant point ${p.label} at (${p.x}, ${p.y})"
                        },
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()
        var titlePlaceable: Placeable? = null
        val qPlaceables = HashMap<Int, Placeable>()
        val axisPlaceables = HashMap<Int, Placeable>()
        val axisSizeByText = HashMap<String, DiagramSize>()
        val ptLblPlaceables = HashMap<String, Placeable>()
        val ptBoxMeasurables = HashMap<String, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("q:") -> {
                    val qNum = id.removePrefix("q:").toIntOrNull() ?: continue
                    qPlaceables[qNum] = m.measure(unbounded)
                }
                id.startsWith("ax:") -> {
                    val slot = id.removePrefix("ax:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    axisPlaceables[slot] = pl
                    val txt = axisSpecs.firstOrNull { it.first == slot }?.second
                    if (txt != null) {
                        axisSizeByText[txt] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                    }
                }
                id.startsWith("ptlbl:") -> ptLblPlaceables[id.removePrefix("ptlbl:")] = m.measure(unbounded)
                id.startsWith("ptbox:") -> ptBoxMeasurables[id.removePrefix("ptbox:")] = m
            }
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = QuadrantLayout.layout(
            diagram = diagram,
            pointLabelSize = { p ->
                val pl = ptLblPlaceables[p.id]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else QuadrantLayout.defaultTextSize(p.label, renderDensity)
            },
            quadrantLabelSize = { qNum, s ->
                val pl = qPlaceables[qNum]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else QuadrantLayout.defaultHeaderSize(s, renderDensity)
            },
            axisLabelSize = { s ->
                axisSizeByText[s] ?: QuadrantLayout.defaultHeaderSize(s, renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val ptBoxPlaceables = HashMap<String, Placeable>()
        for (pt in layout.points) {
            val m = ptBoxMeasurables[pt.point.id] ?: continue
            val dPx = ((pt.radius + pt.strokeWidth / 2f) * 2f).roundToInt().coerceAtLeast(1)
            ptBoxPlaceables[pt.point.id] = m.measure(Constraints.fixed(dPx, dPx))
        }

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for (q in layout.quadrants) {
                val lr = q.labelRect ?: continue
                val pl = qPlaceables[q.quadrantNumber] ?: continue
                pl.place(
                    (lr.centerX - pl.width / 2f).roundToInt(),
                    (lr.centerY - pl.height / 2f).roundToInt(),
                )
            }
            for (al in layout.axisLabels) {
                val pl = axisPlaceables[al.axisSlot] ?: continue
                val px = (al.rect.centerX - pl.width / 2f).roundToInt()
                val py = (al.rect.centerY - pl.height / 2f).roundToInt()
                if (al.rotationDeg != 0f) {
                    pl.placeWithLayer(px, py) {
                        rotationZ = al.rotationDeg
                    }
                } else {
                    pl.place(px, py)
                }
            }
            for (pt in layout.points) {
                val r = pt.radius + pt.strokeWidth / 2f
                ptBoxPlaceables[pt.point.id]?.place(
                    (pt.center.x - r).roundToInt(),
                    (pt.center.y - r).roundToInt(),
                )
                val pl = ptLblPlaceables[pt.point.id]
                if (pl != null) {
                    pl.place(
                        (pt.labelRect.centerX - pl.width / 2f).roundToInt(),
                        (pt.labelRect.centerY - pl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawQuadrantUnderlay(
    layout: QuadrantDiagramLayout,
    theme: MaradonaTheme,
) {
    val unit = density
    val fills = listOf(
        androidx.compose.ui.graphics.lerp(theme.seriesFill(0), theme.background, 0.42f),
        androidx.compose.ui.graphics.lerp(theme.seriesFill(2), theme.background, 0.42f),
        theme.mutedFill,
        androidx.compose.ui.graphics.lerp(theme.seriesFill(3), theme.background, 0.42f),
    )
    val defaultPointColor = theme.accentBorder

    // 1. Quadrant background fills.
    for (q in layout.quadrants) {
        val fill = fills[(q.quadrantNumber - 1).coerceIn(0, fills.lastIndex)]
        drawRect(
            color = fill,
            topLeft = Offset(q.rect.x, q.rect.y),
            size = Size(q.rect.width, q.rect.height),
        )
    }

    // 2. Inner crosshairs & outer frame.
    val g = layout.gridRect
    val midX = g.centerX
    val midY = g.centerY
    drawLine(
        color = theme.subgraphBorder,
        start = Offset(midX, g.y),
        end = Offset(midX, g.bottom),
        strokeWidth = 1.2f * unit,
    )
    drawLine(
        color = theme.subgraphBorder,
        start = Offset(g.x, midY),
        end = Offset(g.right, midY),
        strokeWidth = 1.2f * unit,
    )
    drawRect(
        color = theme.nodeBorder,
        topLeft = Offset(g.x, g.y),
        size = Size(g.width, g.height),
        style = Stroke(width = 2f * unit),
    )

    // 3. Data points.
    for (pt in layout.points) {
        val st = pt.point.style
        val fill = parseCssColor(st?.color) ?: defaultPointColor
        val stroke = parseCssColor(st?.strokeColor) ?: fill
        val center = Offset(pt.center.x, pt.center.y)
        drawCircle(
            color = fill,
            radius = pt.radius,
            center = center,
        )
        if (pt.strokeWidth > 0f) {
            drawCircle(
                color = stroke,
                radius = pt.radius,
                center = center,
                style = Stroke(width = pt.strokeWidth),
            )
        }
    }
}
