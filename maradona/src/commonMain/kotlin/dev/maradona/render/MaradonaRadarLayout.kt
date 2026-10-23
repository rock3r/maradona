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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
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
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.RadarDiagramLayout
import dev.maradona.layout.RadarLayout
import dev.maradona.model.RadarDiagram
import dev.maradona.model.RadarGraticule
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class RadarLayoutHolder {
    var layout: RadarDiagramLayout? = null
}

private val RADAR_PALETTE_LIGHT = listOf(
    Color(0xFF205EA6),
    Color(0xFFDA702C),
    Color(0xFF66800B),
    Color(0xFFCE5D97),
    Color(0xFF24837B),
    Color(0xFF8B7EC8),
    Color(0xFFD14D41),
    Color(0xFFD0A215),
)

private val RADAR_PALETTE_DARK = listOf(
    Color(0xFF4385BE),
    Color(0xFFF29A4B),
    Color(0xFF879A39),
    Color(0xFFE479B2),
    Color(0xFF3AA99F),
    Color(0xFFA699E0),
    Color(0xFFEB6F62),
    Color(0xFFE6B830),
)

private fun resolveRadarCurveColor(
    curveIndex: Int,
    customPalette: Map<Int, String>,
    isDark: Boolean,
): Color {
    val palette = if (isDark) RADAR_PALETTE_DARK else RADAR_PALETTE_LIGHT
    parseCssColor(customPalette[curveIndex])?.let { return it }
    return palette[curveIndex % palette.size]
}

/**
 * Custom Compose [Layout] for Mermaid `radar` / `radar-beta` diagrams.
 *
 * The chart title, radial axis labels, and legend labels are real [BasicText]
 * composables; circular or polygonal graticules, radial spokes, closed curves,
 * and legend swatches are drawn on the underlay canvas.
 */
@Composable
fun MaradonaRadarLayout(
    diagram: RadarDiagram,
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (RadarDiagramLayout) -> Unit = {},
) {
    val holder = remember { RadarLayoutHolder() }
    val isDark = (theme.background.red * 0.299f + theme.background.green * 0.587f + theme.background.blue * 0.114f) < 0.45f

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
                    "Radar diagram. ${diagram.axes.size} axes, ${diagram.curves.size} curves."
                }
            }
            .drawBehind {
                holder.layout?.let { drawRadarUnderlay(it, diagram, theme, isDark) }
            },
        content = {
            if (!diagram.title.isNullOrBlank()) {
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

            for ((idx, axis) in diagram.axes.withIndex()) {
                BasicText(
                    text = annotatedLabel(axis.label),
                    modifier = Modifier.layoutId("axis_lbl:$idx"),
                    maxLines = 1,
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Box(
                    modifier = Modifier
                        .layoutId("axis_box:$idx")
                        .focusable()
                        .semantics {
                            contentDescription = "Radar axis ${axis.label}"
                        },
                )
            }

            if (diagram.config.showLegend) {
                for ((idx, curve) in diagram.curves.withIndex()) {
                    BasicText(
                        text = annotatedLabel(curve.label),
                        modifier = Modifier.layoutId("legend_lbl:$idx"),
                        maxLines = 1,
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .layoutId("legend_box:$idx")
                            .focusable()
                            .semantics {
                                contentDescription = "Radar curve ${curve.label}"
                            },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val axisLblPlaceables = HashMap<Int, Placeable>()
        val axisSizesByText = HashMap<String, DiagramSize>()
        val axisBoxMeasurables = HashMap<Int, Measurable>()

        val legendLblPlaceables = HashMap<Int, Placeable>()
        val legendSizesByText = HashMap<String, DiagramSize>()
        val legendBoxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("axis_lbl:") -> {
                    val idx = id.removePrefix("axis_lbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    axisLblPlaceables[idx] = pl
                    val txt = diagram.axes.getOrNull(idx)?.label
                    if (txt != null) {
                        val prev = axisSizesByText[txt]
                        axisSizesByText[txt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
                id.startsWith("axis_box:") -> {
                    val idx = id.removePrefix("axis_box:").toIntOrNull() ?: continue
                    axisBoxMeasurables[idx] = m
                }
                id.startsWith("legend_lbl:") -> {
                    val idx = id.removePrefix("legend_lbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    legendLblPlaceables[idx] = pl
                    val txt = diagram.curves.getOrNull(idx)?.label
                    if (txt != null) {
                        val prev = legendSizesByText[txt]
                        legendSizesByText[txt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
                id.startsWith("legend_box:") -> {
                    val idx = id.removePrefix("legend_box:").toIntOrNull() ?: continue
                    legendBoxMeasurables[idx] = m
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

        val layout = RadarLayout.layout(
            diagram = diagram,
            axisLabelSize = { s ->
                axisSizesByText[s] ?: RadarLayout.defaultLabelSize(s, renderDensity)
            },
            legendLabelSize = { s ->
                legendSizesByText[s] ?: RadarLayout.defaultLabelSize(s, renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val axisBoxPlaceables = HashMap<Int, Placeable>()
        for (pa in layout.axes) {
            val m = axisBoxMeasurables[pa.axisIndex] ?: continue
            axisBoxPlaceables[pa.axisIndex] = m.measure(
                Constraints.fixed(
                    pa.labelRect.width.roundToInt().coerceAtLeast(1),
                    pa.labelRect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }

        val legendBoxPlaceables = HashMap<Int, Placeable>()
        for (li in layout.legendItems) {
            val m = legendBoxMeasurables[li.curveIndex] ?: continue
            val totalW = (li.labelRect.right - li.swatchRect.x).roundToInt().coerceAtLeast(1)
            val totalH = max(li.swatchRect.height, li.labelRect.height).roundToInt().coerceAtLeast(1)
            legendBoxPlaceables[li.curveIndex] = m.measure(Constraints.fixed(totalW, totalH))
        }

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }

            for (pa in layout.axes) {
                axisBoxPlaceables[pa.axisIndex]?.place(
                    pa.labelRect.x.roundToInt(),
                    pa.labelRect.y.roundToInt(),
                )
                axisLblPlaceables[pa.axisIndex]?.place(
                    pa.labelRect.x.roundToInt(),
                    pa.labelRect.y.roundToInt(),
                )
            }

            for (li in layout.legendItems) {
                legendBoxPlaceables[li.curveIndex]?.place(
                    li.swatchRect.x.roundToInt(),
                    minOf(li.swatchRect.y, li.labelRect.y).roundToInt(),
                )
                legendLblPlaceables[li.curveIndex]?.place(
                    li.labelRect.x.roundToInt(),
                    li.labelRect.y.roundToInt(),
                )
            }
        }
    }
}

private fun DrawScope.drawRadarUnderlay(
    layout: RadarDiagramLayout,
    diagram: RadarDiagram,
    theme: MaradonaTheme,
    isDark: Boolean,
) {
    val cx = layout.center.x
    val cy = layout.center.y
    val graticuleStroke = Stroke(width = max(1f, 1.05f * density))
    val graticuleColor = theme.nodeBorder.copy(alpha = 0.45f)
    val graticuleFill = theme.nodeFill.copy(alpha = 0.35f)
    val spokeStrokeW = max(1f, 1.15f * density)
    val spokeColor = theme.nodeBorder.copy(alpha = 0.65f)

    // 1. Graticule rings (outermost first for subtle background tint)
    val numAxes = layout.axes.size
    for (t in layout.ticks downTo 1) {
        val r = layout.radius * t.toFloat() / layout.ticks.toFloat()
        if (layout.graticule == RadarGraticule.Polygon && numAxes >= 3) {
            val poly = Path()
            for (i in 0 until numAxes) {
                val angle = layout.axes[i].angleRad.toDouble()
                val px = cx + r * cos(angle).toFloat()
                val py = cy + r * sin(angle).toFloat()
                if (i == 0) poly.moveTo(px, py) else poly.lineTo(px, py)
            }
            poly.close()
            if (t == layout.ticks) {
                drawPath(path = poly, color = graticuleFill, style = Fill)
            }
            drawPath(path = poly, color = graticuleColor, style = graticuleStroke)
        } else {
            if (t == layout.ticks) {
                drawCircle(color = graticuleFill, radius = r, center = Offset(cx, cy), style = Fill)
            }
            drawCircle(color = graticuleColor, radius = r, center = Offset(cx, cy), style = graticuleStroke)
        }
    }

    // 2. Radial axis lines
    for (pa in layout.axes) {
        drawLine(
            color = spokeColor,
            start = Offset(cx, cy),
            end = Offset(pa.lineEnd.x, pa.lineEnd.y),
            strokeWidth = spokeStrokeW,
        )
    }

    // 3. Curves
    val rawStrokeWidth = diagram.config.curveStrokeWidth
    val curveStroke = if (rawStrokeWidth > 0f) {
        Stroke(width = rawStrokeWidth * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    } else {
        null
    }
    val fillAlpha = diagram.config.curveOpacity.coerceIn(0f, 1f)
    val dotRadius = 3.2f * density

    for (pc in layout.curves) {
        val pts = pc.points
        if (pts.isEmpty()) continue
        val curveColor = resolveRadarCurveColor(pc.curveIndex, diagram.config.customPalette, isDark)
        if (pts.size >= 2) {
            val path = buildRadarCurvePath(pts, pc.useSmoothClosedCurve, pc.tension)
            if (pts.size >= 3 && fillAlpha > 0f) {
                drawPath(path = path, color = curveColor.copy(alpha = fillAlpha), style = Fill)
            }
            if (curveStroke != null) {
                drawPath(path = path, color = curveColor, style = curveStroke)
            }
        }
        if (curveStroke != null) {
            for (pt in pts) {
                drawCircle(
                    color = curveColor,
                    radius = dotRadius,
                    center = Offset(pt.x, pt.y),
                )
            }
        }
    }

    // 4. Legend swatches
    val swatchStroke = Stroke(width = max(1f, 1.25f * density))
    val swatchAlpha = max(0.35f, fillAlpha)
    for (li in layout.legendItems) {
        val curveColor = resolveRadarCurveColor(li.curveIndex, diagram.config.customPalette, isDark)
        val topLeft = Offset(li.swatchRect.x, li.swatchRect.y)
        val sz = Size(li.swatchRect.width, li.swatchRect.height)
        drawRect(color = curveColor.copy(alpha = swatchAlpha), topLeft = topLeft, size = sz, style = Fill)
        drawRect(color = curveColor, topLeft = topLeft, size = sz, style = swatchStroke)
    }
}

private fun buildRadarCurvePath(
    points: List<DiagramPoint>,
    useSmoothClosedCurve: Boolean,
    tension: Float,
): Path {
    val path = Path()
    val n = points.size
    if (n == 0) return path
    path.moveTo(points[0].x, points[0].y)
    if (useSmoothClosedCurve && n >= 3 && tension > 0f) {
        for (i in 0 until n) {
            val p0 = points[(i - 1 + n) % n]
            val p1 = points[i]
            val p2 = points[(i + 1) % n]
            val p3 = points[(i + 2) % n]
            val cp1x = p1.x + (p2.x - p0.x) * tension
            val cp1y = p1.y + (p2.y - p0.y) * tension
            val cp2x = p2.x - (p3.x - p1.x) * tension
            val cp2y = p2.y - (p3.y - p1.y) * tension
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }
    } else {
        for (i in 1 until n) {
            path.lineTo(points[i].x, points[i].y)
        }
    }
    path.close()
    return path
}
