package dev.maradona.render

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.maradona.model.NodeShape

/** Compose [Shape] for each flowchart node geometry. */
fun nodeShape(shape: NodeShape, cornerRadius: Dp = 4.dp): Shape = when (shape) {
    NodeShape.Rect, NodeShape.Subroutine -> RectangleShape
    NodeShape.RoundedRect -> RoundedCornerShape(cornerRadius)
    NodeShape.Stadium -> RoundedCornerShape(percent = 50)
    NodeShape.Circle, NodeShape.DoubleCircle -> CircleShape
    NodeShape.Diamond -> PolygonShape { w, h ->
        moveTo(w / 2f, 0f); lineTo(w, h / 2f); lineTo(w / 2f, h); lineTo(0f, h / 2f); close()
    }
    NodeShape.Hexagon -> PolygonShape { w, h ->
        val inset = minOf(w * 0.18f, h * 0.5f)
        moveTo(inset, 0f); lineTo(w - inset, 0f); lineTo(w, h / 2f)
        lineTo(w - inset, h); lineTo(inset, h); lineTo(0f, h / 2f); close()
    }
    NodeShape.Parallelogram -> PolygonShape { w, h ->
        val slant = minOf(16f, w * 0.2f)
        moveTo(slant, 0f); lineTo(w, 0f); lineTo(w - slant, h); lineTo(0f, h); close()
    }
    NodeShape.ParallelogramAlt -> PolygonShape { w, h ->
        val slant = minOf(16f, w * 0.2f)
        moveTo(0f, 0f); lineTo(w - slant, 0f); lineTo(w, h); lineTo(slant, h); close()
    }
    NodeShape.Trapezoid -> PolygonShape { w, h ->
        val inset = minOf(18f, w * 0.22f)
        moveTo(inset, 0f); lineTo(w - inset, 0f); lineTo(w, h); lineTo(0f, h); close()
    }
    NodeShape.TrapezoidAlt -> PolygonShape { w, h ->
        val inset = minOf(18f, w * 0.22f)
        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w - inset, h); lineTo(inset, h); close()
    }
    NodeShape.Asymmetric -> PolygonShape { w, h ->
        val point = minOf(14f, w * 0.18f)
        moveTo(point, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(point, h); lineTo(0f, h / 2f); close()
    }
    NodeShape.Cylinder -> CylinderShape
}

/** Extra inner padding so text stays inside non-rectangular shapes. */
fun nodeContentPadding(shape: NodeShape): Pair<Dp, Dp> = when (shape) {
    // horizontal to vertical
    NodeShape.Diamond -> 28.dp to 16.dp
    NodeShape.Hexagon -> 22.dp to 10.dp
    NodeShape.Circle, NodeShape.DoubleCircle -> 20.dp to 14.dp
    NodeShape.Parallelogram, NodeShape.ParallelogramAlt,
    NodeShape.Trapezoid, NodeShape.TrapezoidAlt,
    NodeShape.Asymmetric -> 20.dp to 10.dp
    NodeShape.Stadium -> 18.dp to 8.dp
    NodeShape.Cylinder -> 14.dp to 14.dp
    NodeShape.Subroutine -> 18.dp to 8.dp
    else -> 12.dp to 8.dp
}

private class PolygonShape(private val build: Path.(Float, Float) -> Unit) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path().apply { build(size.width, size.height) }
        return Outline.Generic(path)
    }
}

private object CylinderShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val ry = minOf(h * 0.22f, 14f)
        val path = Path().apply {
            moveTo(0f, ry)
            arcTo(Rect(0f, 0f, w, ry * 2), 180f, 180f, false)
            lineTo(w, h - ry)
            arcTo(Rect(0f, h - ry * 2, w, h), 0f, 180f, false)
            close()
        }
        return Outline.Generic(path)
    }
}
