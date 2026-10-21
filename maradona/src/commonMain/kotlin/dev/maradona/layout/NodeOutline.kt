package dev.maradona.layout

import dev.maradona.model.NodeShape
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where an edge attaches to a node: the point on the node's *outline* (not its
 * bounding box) along the line from the node's centre towards [target].
 *
 * mermaid attaches every connection to the shape it draws, so a diamond's edges
 * leave from its slanted sides and a circle's from its circumference. Using the
 * bounding box instead (what dagre's own `intersectRect` does for every shape)
 * moves the attachment by up to 10% of the diagram's diagonal on a diamond.
 *
 * The point sets mirror [dev.maradona.render.nodeShape] so the geometry the
 * layout attaches to is the geometry the renderer draws.
 */
internal object NodeOutline {

    fun intersect(shape: NodeShape, rect: DiagramRect, target: DiagramPoint): DiagramPoint {
        val centre = DiagramPoint(rect.centerX, rect.centerY)
        val dx = target.x - centre.x
        val dy = target.y - centre.y
        if (dx == 0f && dy == 0f) return centre

        return when (shape) {
            NodeShape.Circle, NodeShape.DoubleCircle -> ellipse(centre, rect.width / 2f, rect.height / 2f, dx, dy)
            NodeShape.Cylinder -> cylinder(rect, dx, dy)
            NodeShape.Diamond,
            NodeShape.Hexagon,
            NodeShape.Parallelogram,
            NodeShape.ParallelogramAlt,
            NodeShape.Trapezoid,
            NodeShape.TrapezoidAlt,
            NodeShape.Asymmetric,
            -> polygon(rect, polygonPoints(shape, rect), dx, dy)
            else -> box(rect, dx, dy)
        }
    }

    private fun cylinder(rect: DiagramRect, dx: Float, dy: Float): DiagramPoint {
        val rx = rect.width / 2f
        val halfH = rect.height / 2f
        if (rx <= 0f || halfH <= 0f) return DiagramPoint(rect.centerX, rect.centerY)
        val ry = minOf(halfH * 0.5f, rx / 2.5f)
        val bodyHalf = halfH - ry
        if (dx != 0f) {
            val tx = rx / abs(dx)
            val yOff = tx * dy
            if (abs(yOff) <= bodyHalf) {
                return DiagramPoint(rect.centerX + tx * dx, rect.centerY + yOff)
            }
        }
        // Intersect ray (t*dx, t*dy) with top or bottom cap ellipse centered at (0, capCy)
        val capCy = if (dy < 0f) -bodyHalf else bodyHalf
        val a = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry)
        val b = -2f * dy * capCy / (ry * ry)
        val c = (capCy * capCy) / (ry * ry) - 1f
        val disc = b * b - 4f * a * c
        if (a <= 0f || disc < 0f) return box(rect, dx, dy)
        val t = (-b + sqrt(disc)) / (2f * a)
        if (t <= 0f) return box(rect, dx, dy)
        return DiagramPoint(rect.centerX + t * dx, rect.centerY + t * dy)
    }

    /** The outline's points in box space, matching the renderer's shapes. */
    private fun polygonPoints(shape: NodeShape, rect: DiagramRect): List<Pair<Float, Float>> {
        val w = rect.width
        val h = rect.height
        return when (shape) {
            NodeShape.Diamond -> listOf(
                w / 2f to 0f, w to h / 2f, w / 2f to h, 0f to h / 2f,
            )
            NodeShape.Hexagon -> {
                val m = h / 3.5f
                listOf(m to 0f, w - m to 0f, w to h / 2f, w - m to h, m to h, 0f to h / 2f)
            }
            NodeShape.Parallelogram -> {
                val m = h / 2f
                listOf(m to 0f, w to 0f, w - m to h, 0f to h)
            }
            NodeShape.ParallelogramAlt -> {
                val m = h / 2f
                listOf(0f to 0f, w - m to 0f, w to h, m to h)
            }
            NodeShape.Trapezoid -> {
                val m = h / 2f
                listOf(m to 0f, w - m to 0f, w to h, 0f to h)
            }
            NodeShape.TrapezoidAlt -> {
                val m = h / 2f
                listOf(0f to 0f, w to 0f, w - m to h, m to h)
            }
            NodeShape.Asymmetric -> {
                val m = h / 4f
                listOf(0f to 0f, w to 0f, w to h, 0f to h, m to h / 2f)
            }
            else -> listOf(0f to 0f, w to 0f, w to h, 0f to h)
        }
    }

    private fun polygon(rect: DiagramRect, points: List<Pair<Float, Float>>, dx: Float, dy: Float): DiagramPoint {
        val cx = rect.width / 2f
        val cy = rect.height / 2f
        var best: DiagramPoint? = null
        var bestDistance = Float.MAX_VALUE
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val hit = segmentIntersection(cx, cy, cx + dx, cy + dy, a.first, a.second, b.first, b.second)
            if (hit != null) {
                val distance = abs(hit.first - cx) + abs(hit.second - cy)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = DiagramPoint(rect.x + hit.first, rect.y + hit.second)
                }
            }
        }
        return best ?: box(rect, dx, dy)
    }

    /** Intersection of the ray from (cx,cy) along (dx,dy) with segment a-b. */
    private fun segmentIntersection(
        cx: Float, cy: Float, tx: Float, ty: Float,
        ax: Float, ay: Float, bx: Float, by: Float,
    ): Pair<Float, Float>? {
        val rX = tx - cx
        val rY = ty - cy
        val sX = bx - ax
        val sY = by - ay
        val denominator = rX * sY - rY * sX
        if (denominator == 0f) return null
        val t = ((ax - cx) * sY - (ay - cy) * sX) / denominator
        val u = ((ax - cx) * rY - (ay - cy) * rX) / denominator
        if (t < 0f || u < 0f || u > 1f) return null
        return (cx + t * rX) to (cy + t * rY)
    }

    private fun ellipse(centre: DiagramPoint, rx: Float, ry: Float, dx: Float, dy: Float): DiagramPoint {
        if (rx <= 0f || ry <= 0f) return centre
        val det = sqrt(dx * dx * ry * ry + dy * dy * rx * rx)
        if (det == 0f) return centre
        val t = (rx * ry) / det
        return DiagramPoint(centre.x + dx * t, centre.y + dy * t)
    }

    /** dagre's util.intersectRect. */
    private fun box(rect: DiagramRect, dx: Float, dy: Float): DiagramPoint {
        var w = rect.width / 2f
        var h = rect.height / 2f
        val sx: Float
        val sy: Float
        if (abs(dy) * w > abs(dx) * h) {
            if (dy < 0) h = -h
            sx = if (dy == 0f) 0f else h * dx / dy
            sy = h
        } else {
            if (dx < 0) w = -w
            sx = w
            sy = if (dx == 0f) 0f else w * dy / dx
        }
        return DiagramPoint(rect.centerX + sx, rect.centerY + sy)
    }
}
