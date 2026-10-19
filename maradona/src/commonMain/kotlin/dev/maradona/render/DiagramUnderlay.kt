package dev.maradona.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.model.ArrowHead
import dev.maradona.model.EdgeStroke
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Edges and subgraph boxes are pure geometry, drawn on a single underlay
 * behind the node composables. Nodes and labels stay real composables;
 * only this non-textual layer is canvas-drawn.
 */
fun DrawScope.drawDiagramUnderlay(layout: DiagramLayout, theme: MaradonaTheme) {
    for (sg in layout.subgraphs) {
        val r = sg.rect
        val topLeft = Offset(r.x, r.y)
        val size = androidx.compose.ui.geometry.Size(r.width, r.height)
        drawRoundRect(
            color = theme.subgraphFill,
            topLeft = topLeft,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
        )
        drawRoundRect(
            color = theme.subgraphBorder,
            topLeft = topLeft,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    for (positioned in layout.edges) {
        val edge = positioned.edge
        if (edge.stroke == EdgeStroke.Invisible || positioned.points.size < 2) continue
        val pts = positioned.points
        // Stop the line where the head starts so the stroke end doesn't poke
        // out of the arrowhead's sloped sides (or through a hollow circle).
        val linePts = trimPath(pts, startBy = headTrim(edge.startHead), endBy = headTrim(edge.endHead))
        if (linePts.size >= 2) {
            val path = roundedPolyline(linePts, radius = 6.dp.toPx())
            val width = if (edge.stroke == EdgeStroke.Thick) 2.5.dp.toPx() else 1.25.dp.toPx()
            val effect = if (edge.stroke == EdgeStroke.Dotted) {
                PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
            } else null
            drawPath(path, color = theme.edge, style = Stroke(width = width, pathEffect = effect))
        }

        drawHead(edge.endHead, tip = pts.last(), from = pts[pts.size - 2], theme = theme)
        drawHead(edge.startHead, tip = pts.first(), from = pts[1], theme = theme)
    }
}

/**
 * The polyline with its corners rounded, which is what mermaid draws: its edges
 * are `curve: basis` splines, so a right-angle turn is a smooth bend rather than
 * a hard corner. The radius is clamped to half of each adjacent segment so short
 * hops cannot overshoot.
 */
private fun roundedPolyline(points: List<DiagramPoint>, radius: Float): Path {
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.lastIndex) {
        val previous = points[i - 1]
        val corner = points[i]
        val next = points[i + 1]
        val inLength = distance(previous, corner)
        val outLength = distance(corner, next)
        val r = minOf(radius, inLength / 2f, outLength / 2f)
        if (r <= 0.01f) {
            path.lineTo(corner.x, corner.y)
            continue
        }
        val start = lerp(corner, previous, r / inLength)
        val end = lerp(corner, next, r / outLength)
        path.lineTo(start.x, start.y)
        // A quadratic through the corner is the cheapest smooth join.
        path.quadraticTo(corner.x, corner.y, end.x, end.y)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

private fun distance(a: DiagramPoint, b: DiagramPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun lerp(from: DiagramPoint, to: DiagramPoint, t: Float) =
    DiagramPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)

private val ArrowLength = 10.dp
private val CrossRadius = 5.dp
private val CircleRadius = 4.5.dp

/** How far before the tip the line should stop to make room for this head. */
private fun DrawScope.headTrim(head: ArrowHead): Float {
    // Overshoot 1dp into the head so rounding/AA can't leave a hairline gap.
    val overlap = 1.dp.toPx()
    return when (head) {
        ArrowHead.Arrow -> ArrowLength.toPx() - overlap
        ArrowHead.Circle -> CircleRadius.toPx() * 2 - overlap
        ArrowHead.Cross -> CrossRadius.toPx() * 1.7f - overlap
        ArrowHead.None -> 0f
    }
}

/** Walks [distance] back from the path end, returning the shortened polyline. */
private fun trimEnd(points: List<DiagramPoint>, distance: Float): List<DiagramPoint> {
    val result = points.toMutableList()
    var remaining = distance
    while (result.size >= 2 && remaining > 0f) {
        val last = result.last()
        val prev = result[result.size - 2]
        val dx = last.x - prev.x
        val dy = last.y - prev.y
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 0f) {
            result.removeAt(result.size - 1)
            continue
        }
        if (len > remaining) {
            result[result.size - 1] = DiagramPoint(last.x - dx / len * remaining, last.y - dy / len * remaining)
            break
        }
        remaining -= len
        result.removeAt(result.size - 1)
    }
    return result
}

/** Walks [distance] forward from the path start, returning the shortened polyline. */
private fun trimStart(points: List<DiagramPoint>, distance: Float): List<DiagramPoint> {
    val result = points.toMutableList()
    var remaining = distance
    while (result.size >= 2 && remaining > 0f) {
        val first = result.first()
        val next = result[1]
        val dx = next.x - first.x
        val dy = next.y - first.y
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 0f) {
            result.removeAt(0)
            continue
        }
        if (len > remaining) {
            result[0] = DiagramPoint(first.x + dx / len * remaining, first.y + dy / len * remaining)
            break
        }
        remaining -= len
        result.removeAt(0)
    }
    return result
}

private fun trimPath(points: List<DiagramPoint>, startBy: Float, endBy: Float): List<DiagramPoint> {
    var pts = points
    if (endBy > 0f) pts = trimEnd(pts, endBy)
    if (startBy > 0f) pts = trimStart(pts, startBy)
    return pts
}

private fun DrawScope.drawHead(
    head: ArrowHead,
    tip: DiagramPoint,
    from: DiagramPoint,
    theme: MaradonaTheme,
) {
    if (head == ArrowHead.None) return
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val tipOffset = Offset(tip.x, tip.y)
    when (head) {
        ArrowHead.Arrow -> {
            val length = ArrowLength.toPx()
            val spread = 0.42f // ~24 degrees
            val p1 = Offset(tip.x - length * cos(angle - spread), tip.y - length * sin(angle - spread))
            val p2 = Offset(tip.x - length * cos(angle + spread), tip.y - length * sin(angle + spread))
            val triangle = Path().apply {
                moveTo(tip.x, tip.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close()
            }
            drawPath(triangle, color = theme.edge)
        }
        ArrowHead.Cross -> {
            val r = CrossRadius.toPx()
            val ux = cos(angle); val uy = sin(angle)
            val px = -uy; val py = ux
            val center = Offset(tip.x - ux * r, tip.y - uy * r)
            // mermaid's cross is a bold X about as wide as the circle head.
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            val extent = 1f
            drawLine(theme.edge, Offset(center.x + (ux + px) * r * extent, center.y + (uy + py) * r * extent), Offset(center.x - (ux + px) * r * extent, center.y - (uy + py) * r * extent), stroke.width, cap = StrokeCap.Round)
            drawLine(theme.edge, Offset(center.x + (ux - px) * r * extent, center.y + (uy - py) * r * extent), Offset(center.x - (ux - px) * r * extent, center.y - (uy - py) * r * extent), stroke.width, cap = StrokeCap.Round)
        }
        ArrowHead.Circle -> {
            // mermaid draws `--o` as a solid dot, not a ring.
            val r = CircleRadius.toPx()
            val center = Offset(tip.x - r * cos(angle), tip.y - r * sin(angle))
            drawCircle(color = theme.edge, radius = r, center = center)
        }
        ArrowHead.None -> Unit
    }
}
