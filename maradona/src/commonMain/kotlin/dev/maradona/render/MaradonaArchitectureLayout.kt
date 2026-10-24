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
import androidx.compose.ui.graphics.PathEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ArchitectureDiagramLayout
import dev.maradona.layout.ArchitectureLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.model.ArchitectureDiagram
import dev.maradona.model.ArchitecturePortDir
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class ArchitectureLayoutHolder {
    var layout: ArchitectureDiagramLayout? = null
}

private val ARCH_ICON_BG_LIGHT = Color(0xFF087EBF)
private val ARCH_ICON_BG_DARK = Color(0xFF0A8FD8)

/**
 * Custom Compose [Layout] for Mermaid `architecture` / `architecture-beta` diagrams.
 *
 * Group titles, service labels, service `iconText`, edge labels, and the chart title
 * are real [BasicText] composables; dashed group enclosures, `#087EBF` service icon
 * tiles (`database`, `server`, `disk`, `internet`, `cloud`), junctions, and port-to-port
 * orthogonal edges are drawn on the underlay canvas.
 */
@Composable
fun MaradonaArchitectureLayout(
    diagram: ArchitectureDiagram,
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (ArchitectureDiagramLayout) -> Unit = {},
) {
    val holder = remember { ArchitectureLayoutHolder() }
    val isDark = (theme.background.red * 0.299f + theme.background.green * 0.587f + theme.background.blue * 0.114f) < 0.45f
    val labelFontSp = diagram.config.fontSize.coerceAtLeast(1f)

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
                    "Architecture diagram. ${diagram.services.size} services, ${diagram.groups.size} groups, ${diagram.edges.size} edges."
                }
            }
            .drawBehind {
                holder.layout?.let { drawArchitectureUnderlay(it, theme, isDark) }
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

            for (grp in diagram.groups) {
                val grpLabel = grp.title?.takeIf { it.isNotBlank() } ?: grp.id
                BasicText(
                    text = annotatedLabel(grpLabel),
                    modifier = Modifier.layoutId("grplbl:${grp.id}"),
                    maxLines = 1,
                    style = TextStyle(
                        color = theme.subgraphText,
                        fontFamily = theme.fontFamily,
                        fontSize = labelFontSp.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }

            for (svc in diagram.services) {
                val svcTitle = svc.title?.takeIf { it.isNotBlank() }
                if (svcTitle != null) {
                    BasicText(
                        text = annotatedLabel(svcTitle),
                        modifier = Modifier.layoutId("svclbl:${svc.id}"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = labelFontSp.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                if (!svc.iconText.isNullOrBlank()) {
                    BasicText(
                        text = annotatedLabel(svc.iconText),
                        modifier = Modifier.layoutId("svcicontxt:${svc.id}"),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = Color.White,
                            fontFamily = theme.fontFamily,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .layoutId("svcbox:${svc.id}")
                        .focusable()
                        .semantics {
                            val desc = svc.title?.takeIf { it.isNotBlank() } ?: svc.id
                            contentDescription = "Architecture service $desc"
                        },
                )
            }

            for ((edgeIdx, edge) in diagram.edges.withIndex()) {
                if (!edge.title.isNullOrBlank()) {
                    BasicText(
                        text = annotatedLabel(edge.title),
                        modifier = Modifier.layoutId("edgelbl:$edgeIdx"),
                        style = TextStyle(
                            color = theme.edgeLabelText,
                            fontFamily = theme.fontFamily,
                            fontSize = 11.5.sp,
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
        val grpLblPlaceables = HashMap<String, Placeable>()
        val grpLblSizes = HashMap<String, DiagramSize>()

        val svcLblPlaceables = HashMap<String, Placeable>()
        val svcLblSizes = HashMap<String, DiagramSize>()
        val svcIconTxtMeasurables = HashMap<String, Measurable>()
        val svcBoxMeasurables = HashMap<String, Measurable>()

        val edgeLblPlaceables = HashMap<Int, Placeable>()
        val edgeLblSizesByText = HashMap<String, DiagramSize>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("grplbl:") -> {
                    val grpId = id.removePrefix("grplbl:")
                    val pl = m.measure(unbounded)
                    grpLblPlaceables[grpId] = pl
                    grpLblSizes[grpId] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                }
                id.startsWith("svclbl:") -> {
                    val svcId = id.removePrefix("svclbl:")
                    val pl = m.measure(unbounded)
                    svcLblPlaceables[svcId] = pl
                    svcLblSizes[svcId] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                }
                id.startsWith("svcicontxt:") -> {
                    val svcId = id.removePrefix("svcicontxt:")
                    svcIconTxtMeasurables[svcId] = m
                }
                id.startsWith("svcbox:") -> {
                    val svcId = id.removePrefix("svcbox:")
                    svcBoxMeasurables[svcId] = m
                }
                id.startsWith("edgelbl:") -> {
                    val idx = id.removePrefix("edgelbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    edgeLblPlaceables[idx] = pl
                    val txt = diagram.edges.getOrNull(idx)?.title
                    if (txt != null) {
                        val prev = edgeLblSizesByText[txt]
                        edgeLblSizesByText[txt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
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

        val layout = ArchitectureLayout.layout(
            diagram = diagram,
            serviceLabelSize = { svcId, lbl ->
                svcLblSizes[svcId] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
            },
            groupLabelSize = { grpId, lbl ->
                grpLblSizes[grpId] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
            },
            edgeLabelSize = { lbl ->
                edgeLblSizesByText[lbl] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val svcIconTxtPlaceables = HashMap<String, Placeable>()
        val svcBoxPlaceables = HashMap<String, Placeable>()
        for (ps in layout.services) {
            val svcId = ps.service.id
            svcIconTxtMeasurables[svcId]?.let { m ->
                val maxW = max(1, (ps.iconRect.width - 8f * renderDensity).roundToInt())
                val maxH = max(1, (ps.iconRect.height - 8f * renderDensity).roundToInt())
                svcIconTxtPlaceables[svcId] = m.measure(Constraints(maxWidth = maxW, maxHeight = maxH))
            }
            svcBoxMeasurables[svcId]?.let { m ->
                svcBoxPlaceables[svcId] = m.measure(
                    Constraints.fixed(
                        ps.bounds.width.roundToInt().coerceAtLeast(1),
                        ps.bounds.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
        }

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }

            for (pg in layout.groups) {
                val pl = grpLblPlaceables[pg.group.id]
                val lr = pg.labelRect
                if (pl != null && lr != null) {
                    pl.place(lr.x.roundToInt(), lr.y.roundToInt())
                }
            }

            for (ps in layout.services) {
                val svcId = ps.service.id
                svcBoxPlaceables[svcId]?.place(ps.bounds.x.roundToInt(), ps.bounds.y.roundToInt())
                val iconTxtP = svcIconTxtPlaceables[svcId]
                if (iconTxtP != null) {
                    iconTxtP.place(
                        (ps.iconRect.centerX - iconTxtP.width / 2f).roundToInt(),
                        (ps.iconRect.centerY - iconTxtP.height / 2f).roundToInt(),
                    )
                }
                val lblP = svcLblPlaceables[svcId]
                val lr = ps.labelRect
                if (lblP != null && lr != null) {
                    lblP.place(
                        (lr.centerX - lblP.width / 2f).roundToInt(),
                        lr.y.roundToInt(),
                    )
                }
            }

            for ((edgeIdx, pe) in layout.edges.withIndex()) {
                val pl = edgeLblPlaceables[edgeIdx]
                val lr = pe.labelRect
                if (pl != null && lr != null) {
                    pl.place(
                        (lr.centerX - pl.width / 2f).roundToInt(),
                        (lr.centerY - pl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawArchitectureUnderlay(
    layout: ArchitectureDiagramLayout,
    theme: MaradonaTheme,
    isDark: Boolean,
) {
    val iconTileBg = if (isDark) ARCH_ICON_BG_DARK else ARCH_ICON_BG_LIGHT
    val groupDash = PathEffect.dashPathEffect(floatArrayOf(7f * density, 4.5f * density), 0f)
    val groupStroke = Stroke(width = max(1f, 1.4f * density), pathEffect = groupDash)

    // 1. Groups
    for (pg in layout.groups) {
        val r = pg.rect
        val cr = CornerRadius(6f * density, 6f * density)
        drawRoundRect(
            color = theme.subgraphFill.copy(alpha = 0.42f),
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Fill,
        )
        drawRoundRect(
            color = theme.subgraphBorder,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = groupStroke,
        )
        val ir = pg.iconRect
        if (ir != null) {
            drawArchitectureIconTile(ir, pg.group.icon, iconTileBg)
        }
    }

    // 2. Edges (drawn behind service tiles so port attachments meet the tile border cleanly)
    val edgeStrokeW = max(1.2f, 2f * density)
    val edgeStroke = Stroke(width = edgeStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (pe in layout.edges) {
        val pts = pe.points
        if (pts.size < 2) continue
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                lineTo(pts[i].x, pts[i].y)
            }
        }
        drawPath(path = path, color = theme.edge, style = edgeStroke)

        if (pe.edge.lhsInto) {
            drawPortArrowHead(tip = pts.first(), portDir = pe.edge.lhsDir, color = theme.edge)
        }
        if (pe.edge.rhsInto) {
            drawPortArrowHead(tip = pts.last(), portDir = pe.edge.rhsDir, color = theme.edge)
        }

        val lr = pe.labelRect
        if (lr != null) {
            drawRoundRect(
                color = theme.edgeLabelBackground.copy(alpha = 0.92f),
                topLeft = Offset(lr.x, lr.y),
                size = Size(lr.width, lr.height),
                cornerRadius = CornerRadius(3f * density, 3f * density),
                style = Fill,
            )
        }
    }

    // 3. Junctions
    for (pj in layout.junctions) {
        drawCircle(
            color = theme.edge,
            radius = 3.6f * density,
            center = Offset(pj.center.x, pj.center.y),
        )
    }

    // 4. Services
    for (ps in layout.services) {
        val svc = ps.service
        val ir = ps.iconRect
        if (svc.icon != null || !svc.iconText.isNullOrBlank()) {
            drawArchitectureIconTile(ir, svc.icon, iconTileBg)
        } else {
            val cr = CornerRadius(5f * density, 5f * density)
            drawRoundRect(
                color = theme.nodeFill,
                topLeft = Offset(ir.x, ir.y),
                size = Size(ir.width, ir.height),
                cornerRadius = cr,
                style = Fill,
            )
            drawRoundRect(
                color = theme.nodeBorder,
                topLeft = Offset(ir.x, ir.y),
                size = Size(ir.width, ir.height),
                cornerRadius = cr,
                style = groupStroke,
            )
        }
    }
}

private fun DrawScope.drawArchitectureIconTile(
    rect: DiagramRect,
    iconName: String?,
    tileBg: Color,
) {
    val cr = CornerRadius(min(6f * density, rect.width * 0.12f), min(6f * density, rect.height * 0.12f))
    drawRoundRect(
        color = tileBg,
        topLeft = Offset(rect.x, rect.y),
        size = Size(rect.width, rect.height),
        cornerRadius = cr,
        style = Fill,
    )
    if (iconName.isNullOrBlank()) return

    val w = rect.width
    val h = rect.height
    val x0 = rect.x
    val y0 = rect.y
    val strokeW = max(1.1f, w * 0.032f)
    val iconStroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val norm = iconName.lowercase()

    when {
        norm.contains("database") || norm.contains("aurora") || norm.contains("db") -> {
            val cx = rect.centerX
            val rx = w * 0.25f
            val ry = h * 0.09f
            val topY = y0 + h * 0.28f
            val botY = y0 + h * 0.72f
            drawOval(
                color = Color.White,
                topLeft = Offset(cx - rx, topY - ry),
                size = Size(rx * 2f, ry * 2f),
                style = iconStroke,
            )
            drawLine(color = Color.White, start = Offset(cx - rx, topY), end = Offset(cx - rx, botY), strokeWidth = strokeW)
            drawLine(color = Color.White, start = Offset(cx + rx, topY), end = Offset(cx + rx, botY), strokeWidth = strokeW)
            for (frac in listOf(0.43f, 0.58f, 0.72f)) {
                val ay = y0 + h * frac
                drawArc(
                    color = Color.White,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - rx, ay - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = iconStroke,
                )
            }
        }
        norm.contains("disk") || norm.contains("glacier") || norm.contains("s3") || norm.contains("storage") -> {
            val bx = x0 + w * 0.25f
            val by = y0 + h * 0.19f
            val bw = w * 0.50f
            val bh = h * 0.62f
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(bx, by),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(strokeW, strokeW),
                style = iconStroke,
            )
            val platterCenter = Offset(rect.centerX, y0 + h * 0.43f)
            drawCircle(color = Color.White, radius = w * 0.17f, center = platterCenter, style = iconStroke)
            drawCircle(color = Color.White, radius = w * 0.045f, center = platterCenter, style = Fill)
            drawLine(
                color = Color.White,
                start = Offset(rect.centerX - w * 0.03f, y0 + h * 0.52f),
                end = Offset(rect.centerX - w * 0.12f, y0 + h * 0.70f),
                strokeWidth = strokeW * 1.3f,
                cap = StrokeCap.Round,
            )
        }
        norm.contains("internet") || norm.contains("globe") || norm.contains("gateway") -> {
            val center = Offset(rect.centerX, rect.centerY)
            val r = min(w, h) * 0.28f
            drawCircle(color = Color.White, radius = r, center = center, style = iconStroke)
            drawLine(color = Color.White, start = Offset(center.x, center.y - r), end = Offset(center.x, center.y + r), strokeWidth = strokeW)
            drawLine(color = Color.White, start = Offset(center.x - r, center.y), end = Offset(center.x + r, center.y), strokeWidth = strokeW)
            drawOval(
                color = Color.White,
                topLeft = Offset(center.x - r * 0.52f, center.y - r),
                size = Size(r * 1.04f, r * 2f),
                style = iconStroke,
            )
        }
        norm.contains("cloud") || norm.contains("lambda") -> {
            val path = Path().apply {
                val left = x0 + w * 0.20f
                val right = x0 + w * 0.80f
                val base = y0 + h * 0.66f
                moveTo(left + w * 0.08f, base)
                lineTo(right - w * 0.08f, base)
                cubicTo(right + w * 0.02f, base, right + w * 0.02f, base - h * 0.18f, right - w * 0.08f, base - h * 0.20f)
                cubicTo(right - w * 0.10f, y0 + h * 0.28f, x0 + w * 0.44f, y0 + h * 0.26f, x0 + w * 0.38f, y0 + h * 0.42f)
                cubicTo(left, y0 + h * 0.42f, left - w * 0.02f, base, left + w * 0.08f, base)
                close()
            }
            drawPath(path = path, color = Color.White, style = iconStroke)
        }
        else -> {
            // Default `server` / compute rack icon
            val bx = x0 + w * 0.22f
            val by = y0 + h * 0.22f
            val bw = w * 0.56f
            val bh = h * 0.56f
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(bx, by),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(strokeW, strokeW),
                style = iconStroke,
            )
            val y1 = by + bh / 3f
            val y2 = by + bh * 2f / 3f
            drawLine(color = Color.White, start = Offset(bx, y1), end = Offset(bx + bw, y1), strokeWidth = strokeW)
            drawLine(color = Color.White, start = Offset(bx, y2), end = Offset(bx + bw, y2), strokeWidth = strokeW)
            for (slotY in listOf(by + bh / 6f, by + bh / 2f, by + bh * 5f / 6f)) {
                drawCircle(color = Color.White, radius = strokeW * 0.8f, center = Offset(bx + bw * 0.16f, slotY))
                drawCircle(color = Color.White, radius = strokeW * 0.8f, center = Offset(bx + bw * 0.28f, slotY))
                drawLine(
                    color = Color.White,
                    start = Offset(bx + bw * 0.55f, slotY),
                    end = Offset(bx + bw * 0.84f, slotY),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun DrawScope.drawPortArrowHead(
    tip: DiagramPoint,
    portDir: ArchitecturePortDir,
    color: Color,
) {
    val sz = 9f * density
    val half = sz * 0.55f
    val path = Path().apply {
        when (portDir) {
            // Arrow pointing INTO port `L` (pointing right -> into left side of node)
            ArchitecturePortDir.L -> {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - sz, tip.y - half)
                lineTo(tip.x - sz, tip.y + half)
                close()
            }
            // Arrow pointing INTO port `R` (pointing left <- into right side of node)
            ArchitecturePortDir.R -> {
                moveTo(tip.x, tip.y)
                lineTo(tip.x + sz, tip.y - half)
                lineTo(tip.x + sz, tip.y + half)
                close()
            }
            // Arrow pointing INTO port `T` (pointing down v into top side of node)
            ArchitecturePortDir.T -> {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - half, tip.y - sz)
                lineTo(tip.x + half, tip.y - sz)
                close()
            }
            // Arrow pointing INTO port `B` (pointing up ^ into bottom side of node)
            ArchitecturePortDir.B -> {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - half, tip.y + sz)
                lineTo(tip.x + half, tip.y + sz)
                close()
            }
        }
    }
    drawPath(path = path, color = color, style = Fill)
}
