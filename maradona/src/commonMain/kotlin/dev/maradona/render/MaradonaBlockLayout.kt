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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.maradona.layout.BlockDiagramLayout
import dev.maradona.layout.BlockLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.PositionedBlockEdge
import dev.maradona.layout.PositionedBlockItem
import dev.maradona.model.BlockArrowDir
import dev.maradona.model.BlockArrowHead
import dev.maradona.model.BlockDiagram
import dev.maradona.model.BlockEdgePattern
import dev.maradona.model.BlockItem
import dev.maradona.model.BlockShape
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private class BlockLayoutHolder {
    var layout: BlockDiagramLayout? = null
}

/**
 * Custom Compose [Layout] for Mermaid `block` / `block-beta` diagrams.
 *
 * Block labels, edge labels, and the chart title are real [BasicText] composables;
 * composite containers, all 15 block shapes (including directional block arrows),
 * and connecting edges are drawn on the underlay canvas.
 */
@Composable
fun MaradonaBlockLayout(
    diagram: BlockDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (BlockDiagramLayout) -> Unit = {},
) {
    val holder = remember { BlockLayoutHolder() }
    val allNonSpaceBlocks = remember(diagram) { collectNonSpaceBlocks(diagram.children) }

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
                    "Block diagram. ${allNonSpaceBlocks.size} blocks, ${diagram.edges.size} edges."
                }
            }
            .drawBehind {
                holder.layout?.let { drawBlockUnderlay(it, theme) }
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

            for (item in allNonSpaceBlocks) {
                val customTextColor = parseCssColor(item.style?.textColor) ?: theme.nodeText
                val fontWeight = when (item.style?.fontWeightBold) {
                    true -> FontWeight.Bold
                    false -> FontWeight.Normal
                    null -> if (item.shape == BlockShape.Composite) FontWeight.SemiBold else FontWeight.Medium
                }
                if (item.label.isNotEmpty()) {
                    BasicText(
                        text = annotatedLabel(item.label),
                        modifier = Modifier.layoutId("nodelbl:${item.id}"),
                        style = TextStyle(
                            color = customTextColor,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(13f),
                            fontWeight = fontWeight,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .layoutId("nodebox:${item.id}")
                        .focusable()
                        .semantics {
                            val desc = item.label.ifEmpty { item.id }
                            contentDescription = "Block $desc"
                        },
                )
            }

            for ((edgeIdx, edge) in diagram.edges.withIndex()) {
                if (edge.pattern != BlockEdgePattern.Invisible && !edge.label.isNullOrEmpty()) {
                    BasicText(
                        text = annotatedLabel(edge.label),
                        modifier = Modifier.layoutId("edgelbl:$edgeIdx"),
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
        val nodeLblPlaceables = HashMap<String, Placeable>()
        val nodeLblSizes = HashMap<String, DiagramSize>()
        val nodeBoxMeasurables = HashMap<String, Measurable>()

        val edgeLblPlaceables = HashMap<Int, Placeable>()
        val edgeLblSizesByText = HashMap<String, DiagramSize>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("nodelbl:") -> {
                    val blockId = id.removePrefix("nodelbl:")
                    val pl = m.measure(unbounded)
                    nodeLblPlaceables[blockId] = pl
                    nodeLblSizes[blockId] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                }
                id.startsWith("nodebox:") -> {
                    val blockId = id.removePrefix("nodebox:")
                    nodeBoxMeasurables[blockId] = m
                }
                id.startsWith("edgelbl:") -> {
                    val idx = id.removePrefix("edgelbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    edgeLblPlaceables[idx] = pl
                    val txt = diagram.edges.getOrNull(idx)?.label
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

        val layout = BlockLayout.layout(
            diagram = diagram,
            nodeLabelSize = { blockId, lbl ->
                nodeLblSizes[blockId] ?: BlockLayout.defaultLabelSize(lbl, renderDensity)
            },
            edgeLabelSize = { lbl ->
                edgeLblSizesByText[lbl] ?: BlockLayout.defaultLabelSize(lbl, renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val nodeBoxPlaceables = HashMap<String, Placeable>()
        for (pb in layout.blocks) {
            val m = nodeBoxMeasurables[pb.item.id] ?: continue
            nodeBoxPlaceables[pb.item.id] = m.measure(
                Constraints.fixed(
                    pb.rect.width.roundToInt().coerceAtLeast(1),
                    pb.rect.height.roundToInt().coerceAtLeast(1),
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

            for (pb in layout.blocks) {
                nodeBoxPlaceables[pb.item.id]?.place(
                    pb.rect.x.roundToInt(),
                    pb.rect.y.roundToInt(),
                )
                val lblP = nodeLblPlaceables[pb.item.id]
                val lr = pb.labelRect
                if (lblP != null && lr != null) {
                    lblP.place(
                        (lr.centerX - lblP.width / 2f).roundToInt(),
                        (lr.centerY - lblP.height / 2f).roundToInt(),
                    )
                }
            }

            var visibleEdgeIdx = 0
            for ((rawEdgeIdx, edge) in diagram.edges.withIndex()) {
                if (edge.pattern == BlockEdgePattern.Invisible) continue
                val pe = layout.edges.getOrNull(visibleEdgeIdx++) ?: continue
                val pl = edgeLblPlaceables[rawEdgeIdx]
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

private fun collectNonSpaceBlocks(items: List<BlockItem>): List<BlockItem> {
    val out = LinkedHashMap<String, BlockItem>()
    fun visit(list: List<BlockItem>) {
        for (item in list) {
            if (item.shape != BlockShape.Space) {
                out[item.id] = item
            }
            if (item.children.isNotEmpty()) {
                visit(item.children)
            }
        }
    }
    visit(items)
    return out.values.toList()
}

private fun DrawScope.drawBlockUnderlay(
    layout: BlockDiagramLayout,
    theme: MaradonaTheme,
) {
    // 1. Draw composite containers first, then leaf blocks so nested children sit on top of their container.
    for (pb in layout.blocks) {
        if (pb.isCompositeContainer) {
            drawSingleBlock(pb, theme)
        }
    }
    for (pb in layout.blocks) {
        if (!pb.isCompositeContainer) {
            drawSingleBlock(pb, theme)
        }
    }

    // 2. Draw connecting edges and arrowheads.
    for (pe in layout.edges) {
        drawBlockEdge(pe, theme)
    }
}

private fun DrawScope.drawSingleBlock(
    pb: PositionedBlockItem,
    theme: MaradonaTheme,
) {
    val item = pb.item
    val rect = pb.rect
    val style = item.style

    val defaultFill = when {
        pb.isCompositeContainer -> theme.subgraphFill
        item.shape == BlockShape.BlockArrow -> theme.subgraphFill
        else -> theme.nodeFill
    }
    val defaultBorder = if (pb.isCompositeContainer) theme.subgraphBorder else theme.nodeBorder

    val fillColor = parseCssColor(style?.fill) ?: defaultFill
    val borderColor = parseCssColor(style?.stroke) ?: defaultBorder
    val rawStrokeWidth = style?.strokeWidth ?: 1.35f
    val pathEffect = style?.strokeDashArray?.takeIf { it.size >= 2 }?.let { dashes ->
        PathEffect.dashPathEffect(dashes.map { it * density }.toFloatArray(), 0f)
    }
    val strokeStyle = if (rawStrokeWidth > 0f) {
        Stroke(
            width = rawStrokeWidth * density,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = pathEffect,
        )
    } else {
        null
    }

    when (item.shape) {
        BlockShape.Rect, BlockShape.Composite -> {
            val topLeft = Offset(rect.x, rect.y)
            val sz = Size(rect.width, rect.height)
            val cr = if (item.shape == BlockShape.Composite) CornerRadius(4f * density, 4f * density) else CornerRadius.Zero
            drawRoundRect(color = fillColor, topLeft = topLeft, size = sz, cornerRadius = cr, style = Fill)
            if (strokeStyle != null) {
                drawRoundRect(color = borderColor, topLeft = topLeft, size = sz, cornerRadius = cr, style = strokeStyle)
            }
        }
        BlockShape.Round -> {
            val cr = CornerRadius(6f * density, 6f * density)
            val topLeft = Offset(rect.x, rect.y)
            val sz = Size(rect.width, rect.height)
            drawRoundRect(color = fillColor, topLeft = topLeft, size = sz, cornerRadius = cr, style = Fill)
            if (strokeStyle != null) {
                drawRoundRect(color = borderColor, topLeft = topLeft, size = sz, cornerRadius = cr, style = strokeStyle)
            }
        }
        BlockShape.Stadium -> {
            val r = min(rect.width, rect.height) / 2f
            val cr = CornerRadius(r, r)
            val topLeft = Offset(rect.x, rect.y)
            val sz = Size(rect.width, rect.height)
            drawRoundRect(color = fillColor, topLeft = topLeft, size = sz, cornerRadius = cr, style = Fill)
            if (strokeStyle != null) {
                drawRoundRect(color = borderColor, topLeft = topLeft, size = sz, cornerRadius = cr, style = strokeStyle)
            }
        }
        BlockShape.Subroutine -> {
            val topLeft = Offset(rect.x, rect.y)
            val sz = Size(rect.width, rect.height)
            drawRect(color = fillColor, topLeft = topLeft, size = sz, style = Fill)
            if (strokeStyle != null) {
                drawRect(color = borderColor, topLeft = topLeft, size = sz, style = strokeStyle)
                val inset = min(8f * density, rect.width * 0.18f)
                drawLine(
                    color = borderColor,
                    start = Offset(rect.x + inset, rect.y),
                    end = Offset(rect.x + inset, rect.bottom),
                    strokeWidth = strokeStyle.width,
                )
                drawLine(
                    color = borderColor,
                    start = Offset(rect.right - inset, rect.y),
                    end = Offset(rect.right - inset, rect.bottom),
                    strokeWidth = strokeStyle.width,
                )
            }
        }
        BlockShape.Cylinder -> {
            val capRy = min(7f * density, rect.height * 0.2f)
            val bodyTop = rect.y + capRy
            val bodyBottom = rect.bottom - capRy
            val path = Path().apply {
                moveTo(rect.x, bodyTop)
                arcTo(
                    rect = Rect(rect.x, rect.y, rect.right, rect.y + capRy * 2f),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                lineTo(rect.right, bodyBottom)
                arcTo(
                    rect = Rect(rect.x, rect.bottom - capRy * 2f, rect.right, rect.bottom),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                close()
            }
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
                val topFrontArc = Path().apply {
                    addArc(
                        oval = Rect(rect.x, rect.y, rect.right, rect.y + capRy * 2f),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 180f,
                    )
                }
                drawPath(path = topFrontArc, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.Circle -> {
            val r = min(rect.width, rect.height) / 2f
            val center = Offset(rect.centerX, rect.centerY)
            drawCircle(color = fillColor, radius = r, center = center, style = Fill)
            if (strokeStyle != null) {
                drawCircle(color = borderColor, radius = r, center = center, style = strokeStyle)
            }
        }
        BlockShape.DoubleCircle -> {
            val r = min(rect.width, rect.height) / 2f
            val innerR = max(2f, r - 4.5f * density)
            val center = Offset(rect.centerX, rect.centerY)
            drawCircle(color = fillColor, radius = r, center = center, style = Fill)
            if (strokeStyle != null) {
                drawCircle(color = borderColor, radius = r, center = center, style = strokeStyle)
                drawCircle(color = borderColor, radius = innerR, center = center, style = strokeStyle)
            }
        }
        BlockShape.Diamond -> {
            val path = Path().apply {
                moveTo(rect.centerX, rect.y)
                lineTo(rect.right, rect.centerY)
                lineTo(rect.centerX, rect.bottom)
                lineTo(rect.x, rect.centerY)
                close()
            }
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.Hexagon -> {
            val inset = min(14f * density, rect.width * 0.22f)
            val path = Path().apply {
                moveTo(rect.x + inset, rect.y)
                lineTo(rect.right - inset, rect.y)
                lineTo(rect.right, rect.centerY)
                lineTo(rect.right - inset, rect.bottom)
                lineTo(rect.x + inset, rect.bottom)
                lineTo(rect.x, rect.centerY)
                close()
            }
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.Asymmetric -> {
            val notch = min(12f * density, rect.width * 0.2f)
            val path = Path().apply {
                moveTo(rect.x, rect.y)
                lineTo(rect.right, rect.y)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.x, rect.bottom)
                lineTo(rect.x + notch, rect.centerY)
                close()
            }
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.LeanRight -> {
            val slant = min(12f * density, rect.width * 0.2f)
            val path = Path().apply {
                moveTo(rect.x + slant, rect.y)
                lineTo(rect.right, rect.y)
                lineTo(rect.right - slant, rect.bottom)
                lineTo(rect.x, rect.bottom)
                close()
            }
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.LeanLeft -> {
            val slant = min(12f * density, rect.width * 0.2f)
            val path = Path().apply {
                moveTo(rect.x, rect.y)
                lineTo(rect.right - slant, rect.y)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.x + slant, rect.bottom)
                close()
            }
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.Trapezoid -> {
            val slant = min(12f * density, rect.width * 0.2f)
            val path = Path().apply {
                moveTo(rect.x + slant, rect.y)
                lineTo(rect.right - slant, rect.y)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.x, rect.bottom)
                close()
            }
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.InvTrapezoid -> {
            val slant = min(12f * density, rect.width * 0.2f)
            val path = Path().apply {
                moveTo(rect.x, rect.y)
                lineTo(rect.right, rect.y)
                lineTo(rect.right - slant, rect.bottom)
                lineTo(rect.x + slant, rect.bottom)
                close()
            }
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.BlockArrow -> {
            val path = buildBlockArrowPath(rect, item.arrowDirections, density)
            drawPath(path = path, color = fillColor, style = Fill)
            if (strokeStyle != null) {
                drawPath(path = path, color = borderColor, style = strokeStyle)
            }
        }
        BlockShape.Space -> Unit
    }
}

private fun buildBlockArrowPath(
    rect: DiagramRect,
    dirs: List<BlockArrowDir>,
    density: Float,
): Path {
    val pts = BlockLayout.computeBlockArrowVertices(rect, dirs, density)
    return Path().apply {
        if (pts.isNotEmpty()) {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                lineTo(pts[i].x, pts[i].y)
            }
            close()
        }
    }
}

private fun DrawScope.drawBlockEdge(
    pe: PositionedBlockEdge,
    theme: MaradonaTheme,
) {
    val pts = pe.points
    if (pts.size < 2) return
    val edge = pe.edge
    val strokeW = when (edge.pattern) {
        BlockEdgePattern.Thick -> 2.8f * density
        else -> 1.6f * density
    }
    val dash = if (edge.pattern == BlockEdgePattern.Dotted) {
        PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    } else {
        null
    }

    val startPt = pts.first()
    val endPt = pts.last()
    val path = Path().apply {
        moveTo(startPt.x, startPt.y)
        for (i in 1 until pts.size) {
            lineTo(pts[i].x, pts[i].y)
        }
    }
    drawPath(
        path = path,
        color = theme.edge,
        style = Stroke(
            width = strokeW,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = dash,
        ),
    )

    if (edge.endHead != BlockArrowHead.None) {
        drawBlockEdgeHead(
            tip = endPt,
            from = pts[pts.size - 2],
            head = edge.endHead,
            color = theme.edge,
        )
    }
    if (edge.startHead != BlockArrowHead.None) {
        drawBlockEdgeHead(
            tip = startPt,
            from = pts[1],
            head = edge.startHead,
            color = theme.edge,
        )
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

private fun DrawScope.drawBlockEdgeHead(
    tip: DiagramPoint,
    from: DiagramPoint,
    head: BlockArrowHead,
    color: Color,
) {
    val angle = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
    when (head) {
        BlockArrowHead.Point -> {
            val len = 8.5f * density
            val spread = 0.45
            val x1 = tip.x - len * cos(angle - spread).toFloat()
            val y1 = tip.y - len * sin(angle - spread).toFloat()
            val x2 = tip.x - len * cos(angle + spread).toFloat()
            val y2 = tip.y - len * sin(angle + spread).toFloat()
            val p = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(x1, y1)
                lineTo(x2, y2)
                close()
            }
            drawPath(path = p, color = color, style = Fill)
        }
        BlockArrowHead.Circle -> {
            val r = 4f * density
            val cx = tip.x - r * cos(angle).toFloat()
            val cy = tip.y - r * sin(angle).toFloat()
            drawCircle(color = color, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.5f * density))
        }
        BlockArrowHead.Cross -> {
            val arm = 4.5f * density
            val cx = tip.x - arm * cos(angle).toFloat()
            val cy = tip.y - arm * sin(angle).toFloat()
            drawLine(
                color = color,
                start = Offset(cx - arm, cy - arm),
                end = Offset(cx + arm, cy + arm),
                strokeWidth = 1.7f * density,
            )
            drawLine(
                color = color,
                start = Offset(cx - arm, cy + arm),
                end = Offset(cx + arm, cy - arm),
                strokeWidth = 1.7f * density,
            )
        }
        BlockArrowHead.None -> Unit
    }
}
