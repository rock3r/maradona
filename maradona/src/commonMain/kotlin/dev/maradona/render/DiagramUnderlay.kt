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
        drawRect(
            color = theme.subgraphFill,
            topLeft = topLeft,
            size = size,
        )
        drawRect(
            color = theme.subgraphBorder,
            topLeft = topLeft,
            size = size,
            style = Stroke(width = 1f * unit),
        )
    }
    for (positioned in layout.edges) {
        val edge = positioned.edge
        if (edge.stroke == EdgeStroke.Invisible || positioned.points.size < 2) continue
        val pts = positioned.points
        val edgeColor = parseCssColor(edge.style?.stroke) ?: theme.edge
        // Stop the line where the head starts so the stroke end doesn't poke
        // out of the arrowhead's sloped sides (or through a hollow circle).
        val linePts = trimPath(pts, startBy = headTrim(edge.startHead), endBy = headTrim(edge.endHead))
        if (linePts.size >= 2) {
            val path = basisSpline(linePts)
            // mermaid's edge strokes are 1px and 3.5px in SVG user units, and a unit
            // is one pixel here: dp would double them at density 2.
            val customWidth = edge.style?.strokeWidth
                ?.removeSuffix("px")?.trim()?.toFloatOrNull()
            val width = (customWidth ?: if (edge.stroke == EdgeStroke.Thick) 3.5f else 1f) * unit
            val effect = if (edge.stroke == EdgeStroke.Dotted) {
                PathEffect.dashPathEffect(floatArrayOf(3f * unit, 3f * unit), 0f)
            } else null
            drawPath(path, color = edgeColor, style = Stroke(width = width, pathEffect = effect))
        }

        drawHead(edge.endHead, tip = pts.last(), from = pts[pts.size - 2], color = edgeColor)
        drawHead(edge.startHead, tip = pts.first(), from = pts[1], color = edgeColor)
    }
}

/**
 * Uniform cubic B-spline (`d3.curveBasis`) through [points], matching the exact
 * SVG path `d` commands mermaid emits for flowchart edges.
 */
internal fun basisSpline(points: List<DiagramPoint>): Path {
    val path = Path()
    val n = points.size
    if (n == 0) return path
    val p0 = points[0]
    path.moveTo(p0.x, p0.y)
    if (n == 1) return path
    if (n == 2) {
        val p1 = points[1]
        path.lineTo(p1.x, p1.y)
        return path
    }
    val p1 = points[1]
    path.lineTo((5f * p0.x + p1.x) / 6f, (5f * p0.y + p1.y) / 6f)
    for (i in 1 until n - 1) {
        val a = points[i - 1]
        val b = points[i]
        val c = points[i + 1]
        path.cubicTo(
            (2f * a.x + b.x) / 3f, (2f * a.y + b.y) / 3f,
            (a.x + 2f * b.x) / 3f, (a.y + 2f * b.y) / 3f,
            (a.x + 4f * b.x + c.x) / 6f, (a.y + 4f * b.y + c.y) / 6f,
        )
    }
    val prev = points[n - 2]
    val last = points[n - 1]
    path.cubicTo(
        (2f * prev.x + last.x) / 3f, (2f * prev.y + last.y) / 3f,
        (prev.x + 2f * last.x) / 3f, (prev.y + 2f * last.y) / 3f,
        (prev.x + 5f * last.x) / 6f, (prev.y + 5f * last.y) / 6f,
    )
    path.lineTo(last.x, last.y)
    return path
}

// Marker geometry from mermaid's own SVG: the arrow is an 11.5x14 viewBox drawn
// at markerWidth 10.5 (so 10.5 units long and 14 wide), the circle has r=5, and
// the cross spans 13 units with a 1-unit stroke.
//
// These are layout UNITS, and one unit is `density` pixels in the render (the
// layout is computed in px at the render density). Multiplying by `unit` is what
// keeps a marker the same physical size as the diagram: drawing 5f raw pixels
// made the circle half the size mermaid draws.
private const val ArrowLengthUnits = 10.5f
private const val ArrowHalfWidthUnits = 7f
private const val CrossRadiusUnits = 6f
private const val CrossStrokeUnits = 1f
private const val CircleRadiusUnits = 5f

/** Pixels per layout unit at the current render density. */
private val DrawScope.unit: Float get() = density

/** How far before the tip the line should stop to make room for this head. */
private fun DrawScope.headTrim(head: ArrowHead): Float {
    // Overshoot well into the head so the line always ends inside its butt: a
    // 1dp overshoot still showed a gap once the head was scaled by the density.
    val overlap = 3f * unit
    return when (head) {
        ArrowHead.Arrow -> ArrowLengthUnits * unit - overlap
        ArrowHead.Circle -> CircleRadiusUnits * 2 * unit - overlap
        ArrowHead.Cross -> CrossRadiusUnits * 2 * unit - overlap
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

internal fun trimPath(points: List<DiagramPoint>, startBy: Float, endBy: Float): List<DiagramPoint> {
    var pts = points
    if (endBy > 0f) pts = trimEnd(pts, endBy)
    if (startBy > 0f) pts = trimStart(pts, startBy)
    return pts
}

private fun DrawScope.drawHead(
    head: ArrowHead,
    tip: DiagramPoint,
    from: DiagramPoint,
    color: androidx.compose.ui.graphics.Color,
) {
    if (head == ArrowHead.None) return
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    when (head) {
        ArrowHead.Arrow -> {
            val length = ArrowLengthUnits * unit
            // Half-width from the marker's own geometry (14 wide for 10.5 long).
            val spread = atan2(ArrowHalfWidthUnits * unit, length)
            val p1 = Offset(tip.x - length * cos(angle - spread), tip.y - length * sin(angle - spread))
            val p2 = Offset(tip.x - length * cos(angle + spread), tip.y - length * sin(angle + spread))
            val triangle = Path().apply {
                moveTo(tip.x, tip.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close()
            }
            drawPath(triangle, color = color)
        }
        ArrowHead.Cross -> {
            val r = CrossRadiusUnits * unit
            val ux = cos(angle); val uy = sin(angle)
            val px = -uy; val py = ux
            val center = Offset(tip.x - ux * r, tip.y - uy * r)
            val stroke = Stroke(width = CrossStrokeUnits * unit, cap = StrokeCap.Round)
            val extent = 1f
            drawLine(color, Offset(center.x + (ux + px) * r * extent, center.y + (uy + py) * r * extent), Offset(center.x - (ux + px) * r * extent, center.y - (uy + py) * r * extent), stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(center.x + (ux - px) * r * extent, center.y + (uy - py) * r * extent), Offset(center.x - (ux - px) * r * extent, center.y - (uy - py) * r * extent), stroke.width, cap = StrokeCap.Round)
        }
        ArrowHead.Circle -> {
            // mermaid draws `--o` as a solid dot, not a ring.
            val r = CircleRadiusUnits * unit
            val center = Offset(tip.x - r * cos(angle), tip.y - r * sin(angle))
            drawCircle(color = color, radius = r, center = center)
        }
        ArrowHead.None -> Unit
    }
}
