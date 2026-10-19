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

/**
 * Compose [Shape] for each flowchart node geometry.
 *
 * The point sets are mermaid's, drawn into the node box [NodeSizing] computed:
 * mermaid's shapes derive their slants from the box height, so the drawn shape
 * and the layout box agree (e.g. a hexagon's ends are h/3.5 wide).
 */
fun nodeShape(shape: NodeShape, cornerRadius: Dp = 5.dp): Shape = when (shape) {
    // 5 is mermaid's `themeVariables.radius`, which its roundedRect uses.
    NodeShape.Rect, NodeShape.Subroutine -> RectangleShape
    NodeShape.RoundedRect -> RoundedCornerShape(cornerRadius)
    NodeShape.Stadium -> RoundedCornerShape(percent = 50)
    NodeShape.Circle, NodeShape.DoubleCircle -> CircleShape
    // side = labelW + labelH + 2*padding, i.e. a square diamond.
    NodeShape.Diamond -> PolygonShape { w, h ->
        moveTo(w / 2f, 0f); lineTo(w, h / 2f); lineTo(w / 2f, h); lineTo(0f, h / 2f); close()
    }
    // ends are h/3.5 wide, top/bottom edges inset by that much.
    NodeShape.Hexagon -> PolygonShape { w, h ->
        val m = h / 3.5f
        moveTo(m, 0f); lineTo(w - m, 0f); lineTo(w, h / 2f)
        lineTo(w - m, h); lineTo(m, h); lineTo(0f, h / 2f); close()
    }
    // ends are h/2 wide.
    NodeShape.Parallelogram -> PolygonShape { w, h ->
        val m = h / 2f
        moveTo(m, 0f); lineTo(w, 0f); lineTo(w - m, h); lineTo(0f, h); close()
    }
    NodeShape.ParallelogramAlt -> PolygonShape { w, h ->
        val m = h / 2f
        moveTo(0f, 0f); lineTo(w - m, 0f); lineTo(w, h); lineTo(m, h); close()
    }
    NodeShape.Trapezoid -> PolygonShape { w, h ->
        val m = h / 2f
        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w - m, h); lineTo(m, h); close()
    }
    NodeShape.TrapezoidAlt -> PolygonShape { w, h ->
        val m = h / 2f
        moveTo(m, 0f); lineTo(w - m, 0f); lineTo(w, h); lineTo(0f, h); close()
    }
    // a notch of h/4, which pokes out past the left edge (mermaid draws the
    // notch point outside the label box, so the drawn shape is wider than it).
    NodeShape.Asymmetric -> PolygonShape { w, h ->
        val m = h / 4f
        moveTo(m, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(m, h); lineTo(0f, h / 2f); close()
    }
    NodeShape.Cylinder -> CylinderShape
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
        // mermaid: rx = w/2, ry = rx / (2.5 + w/50)
        val ry = (w / 2f) / (2.5f + w / 50f)
        // mermaid's path: a cap above the body (from -ry to ry), the body, then
        // a bulge below it, so the outline spans the full height.
        val body = h - ry * 2f
        val path = Path().apply {
            moveTo(0f, ry)
            arcTo(Rect(0f, 0f, w, ry * 2), 180f, -180f, false)
            arcTo(Rect(0f, 0f, w, ry * 2), 180f, -180f, false)
            lineTo(w, body + ry)
            arcTo(Rect(0f, body, w, ry * 2), 0f, 180f, false)
            close()
        }
        return Outline.Generic(path)
    }
}
