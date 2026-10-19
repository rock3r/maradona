package dev.maradona.layout

import dev.maradona.model.NodeShape
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mermaid's flowchart node metrics, so our layout produces the same geometry as
 * mermaid's SVG (which is what the oracle compares against).
 *
 * Values are mermaid's own defaults (flowchart config: `padding: 15`,
 * `wrappingWidth: 120`, `minNodeWidth: 120`) and its per-shape formulas from
 * mermaid's `rendering-elements/shapes` sources, for the `neo` look it renders with.
 * Units are mermaid SVG units, which for us are density-independent pixels:
 * a node measured here is the same size mermaid would draw it.
 */
object NodeSizing {
    /** mermaid `flowchart.padding`. */
    const val PADDING = 15f

    /** mermaid `flowchart.wrappingWidth`: labels wrap at this width. */
    const val WRAPPING_WIDTH = 120f

    /** mermaid `flowchart.minNodeWidth`: the label box is at least this wide. */
    const val MIN_NODE_WIDTH = 120f

    /** The label box mermaid measures: wrapped text, clamped to the minimum width. */
    fun labelBox(shape: NodeShape, textWidth: Float, textHeight: Float): DiagramSize = when (shape) {
        // mermaid calls labelHelper with ignoreMinWidth for these.
        NodeShape.Circle, NodeShape.DoubleCircle -> DiagramSize(textWidth, textHeight)
        else -> DiagramSize(max(textWidth, MIN_NODE_WIDTH), textHeight)
    }

    /**
     * The node box mermaid draws around a label box.
     *
     * `rect`:            label + 16/12 padding each side
     * `rounded`:         label + 15 padding each side
     * `stadium`:         semicircular caps of radius h/2, so it needs h more width
     * `subroutine`:      the two frame bars (8 units inside each edge) plus padding
     * `cylinder`:        an elliptical cap whose ry depends on the width
     * `circle`:          radius = hypot(label)/2 + 16
     * `doublecircle`:    ... + 12 more for the outer ring
     * `diamond`:         square of side (label width + 15) + (label height + 15)
     * `hexagon`:         slanted ends of h/3.5 plus 70/32 padding
     * `parallelogram`, `trapezoid`:        slanted ends of h/2
     * `trapezoidAlt`:    ... with twice the vertical padding
     * `asymmetric`:      a notch of h/4 plus 21/12 padding
     */
    fun nodeBox(shape: NodeShape, labelW: Float, labelH: Float): DiagramSize = when (shape) {
        NodeShape.Rect -> DiagramSize(labelW + 32f, labelH + 24f)
        NodeShape.RoundedRect -> DiagramSize(labelW + PADDING * 2, labelH + PADDING * 2)
        NodeShape.Stadium -> stadium(labelW, labelH)
        NodeShape.Subroutine -> DiagramSize(labelW + 44f, labelH + 12f)
        NodeShape.Cylinder -> cylinder(labelW, labelH)
        NodeShape.Circle -> {
            val d = hypot(labelW, labelH) + 32f
            DiagramSize(d, d)
        }
        NodeShape.DoubleCircle -> {
            val d = hypot(labelW, labelH) + 56f
            DiagramSize(d, d)
        }
        NodeShape.Diamond -> {
            val side = labelW + labelH + PADDING * 2
            DiagramSize(side, side)
        }
        NodeShape.Hexagon -> {
            val h = labelH + 70f
            val slant = h / 3.5f
            DiagramSize(labelW + 2f * slant + 32f, h)
        }
        NodeShape.Parallelogram, NodeShape.ParallelogramAlt, NodeShape.Trapezoid -> {
            val h = labelH + PADDING
            DiagramSize(labelW + PADDING * 2 + h, h)
        }
        NodeShape.TrapezoidAlt -> {
            val h = labelH + PADDING * 2
            DiagramSize(labelW + PADDING * 4 + h, h)
        }
        NodeShape.Asymmetric -> DiagramSize(labelW + 42f, labelH + 24f)
    }

    /** The height the shape's slanted end eats on each side (for drawing paths). */
    fun slantInset(shape: NodeShape, boxHeight: Float): Float = when (shape) {
        NodeShape.Hexagon -> boxHeight / 3.5f
        NodeShape.Parallelogram, NodeShape.ParallelogramAlt,
        NodeShape.Trapezoid, NodeShape.TrapezoidAlt,
        -> boxHeight / 2f
        NodeShape.Asymmetric -> boxHeight / 4f
        else -> 0f
    }

    private fun stadium(labelW: Float, labelH: Float): DiagramSize {
        // mermaid: h = labelH + 24; caps are arcs of radius h/2 with 50 points,
        // so the straight part must leave room for the label.
        val h = labelH + 24f
        val inscribed = h * cos(PI / (2f * 49f))
        val capAtLabel = sqrt(max(0f, inscribed * inscribed - labelH * labelH))
        val w = maxOf(
            labelW + h / 4f + 40f,
            1.5f * h + (h - inscribed),
            labelW + 40f + h - capAtLabel,
        )
        return DiagramSize(w, h)
    }

    private fun cylinder(labelW: Float, labelH: Float): DiagramSize {
        val w = labelW + 24f
        val rx = w / 2f
        val ry = rx / (2.5f + w / 50f)
        return DiagramSize(w, labelH + 24f + ry * 2f)
    }

    private fun hypot(a: Float, b: Float): Float = sqrt(a * a + b * b)

    private const val PI = 3.1415927f
}
