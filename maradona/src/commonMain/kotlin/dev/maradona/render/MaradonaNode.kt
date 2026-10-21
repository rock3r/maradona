package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.maradona.layout.NodeSizing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.maradona.model.FlowNode
import dev.maradona.model.NodeShape

/**
 * One flowchart node as a real composable: shaped box plus text.
 * Being composables (not pixels on a shared Canvas) gives each node
 * semantics, focus traversal, and platform text rendering.
 */
@Composable
fun MaradonaNode(
    node: FlowNode,
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    /** Plain-language summary of this node's connections, for accessibility. */
    connectionsDescription: String? = null,
) {
    val shape = nodeShape(node.shape)
    val fill = parseCssColor(node.style?.fill) ?: theme.nodeFill
    val borderColor = parseCssColor(node.style?.stroke) ?: theme.nodeBorder
    val textColor = parseCssColor(node.style?.textColor) ?: theme.nodeText
    // Mermaid sizes a node from its *wrapped* label: labels wrap at
    // `wrappingWidth` and the label box has a minimum width, then each shape
    // adds its own padding. Matching that is what makes our geometry comparable
    // to mermaid's SVG (see NodeSizing).
    val textStyle = TextStyle(
        color = textColor,
        fontFamily = theme.fontFamily,
        fontSize = theme.nodeFontSize,
        // Mermaid lays labels out with `line-height: 1.5`, so its label box is
        // 21 units per line for a 14px font; matching that keeps node heights
        // comparable (a single line is 15.5 without it).
        lineHeight = theme.nodeFontSize * 1.5f,
        textAlign = TextAlign.Center,
    )
    val measurer = rememberTextMeasurer()
    val wrappedLabel = annotatedLabel(node.label)
    val wrapPx = with(LocalDensity.current) { NodeSizing.WRAPPING_WIDTH.dp.roundToPx() }
    val measured = measurer.measure(wrappedLabel, textStyle, constraints = Constraints(maxWidth = wrapPx))
    // Mermaid's label box is `lines x (fontSize * 1.5)` tall (its SVG sets
    // line-height: 1.5), not the font's tight line height, so derive it from the
    // line count rather than from the measured text height.
    val labelBox = NodeSizing.labelBox(
        node.shape,
        measured.size.width / LocalDensity.current.density,
        measured.lineCount * theme.nodeFontSize.value * 1.5f,
    )
    val nodeBox = NodeSizing.nodeBox(node.shape, labelBox.width, labelBox.height)
    val plainLabel = node.label.replace("**", "").replace("*", "")
    val description = if (connectionsDescription.isNullOrBlank()) {
        plainLabel
    } else {
        "$plainLabel. $connectionsDescription"
    }

    Box(
        modifier = modifier
            .size(nodeBox.width.dp, nodeBox.height.dp)
            // Draw fill + border as plain vectors. Modifier.background/border
            // rasterize generic (non-rect) outlines into an offscreen texture
            // for their BlendMode.Clear cutout, which then gets bilinearly
            // sampled at fractional positions -> blurry borders when zoomed.
            .drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                // mermaid's node borders are 1 unit; a `style … stroke-width:Npx`
                // overrides it (1px in SVG user units = 1dp here).
                val strokeWidth = node.style?.strokeWidth
                    ?.removeSuffix("px")?.trim()?.toFloatOrNull()
                    ?.let { it.dp.toPx() } ?: 1.dp.toPx()
                val stroke = Stroke(width = strokeWidth)
                when (outline) {
                    is Outline.Rectangle -> {
                        drawRect(fill, topLeft = outline.rect.topLeft, size = outline.rect.size)
                        drawRect(borderColor, topLeft = outline.rect.topLeft, size = outline.rect.size, style = stroke)
                    }
                    is Outline.Rounded -> {
                        val r = outline.roundRect
                        val topLeft = Offset(r.left, r.top)
                        val outlineSize = Size(r.width, r.height)
                        drawRoundRect(fill, topLeft = topLeft, size = outlineSize, cornerRadius = r.topLeftCornerRadius)
                        drawRoundRect(borderColor, topLeft = topLeft, size = outlineSize, cornerRadius = r.topLeftCornerRadius, style = stroke)
                    }
                    is Outline.Generic -> {
                        drawPath(outline.path, fill)
                        drawPath(outline.path, borderColor, style = stroke)
                    }
                }
                // Details a Shape outline cannot express.
                when (node.shape) {
                    NodeShape.DoubleCircle -> {
                        val inset = 5.dp.toPx()
                        drawOval(
                            color = borderColor,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            style = Stroke(width = strokeWidth),
                        )
                    }
                    NodeShape.Subroutine -> {
                        val inset = 8.dp.toPx()
                        drawLine(borderColor, Offset(inset, 0f), Offset(inset, size.height), strokeWidth)
                        drawLine(borderColor, Offset(size.width - inset, 0f), Offset(size.width - inset, size.height), strokeWidth)
                    }
                    else -> Unit
                }
            }
            .focusable()
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val labelModifier = when (node.shape) {
            NodeShape.Cylinder -> {
                val rx = nodeBox.width / 2f
                val ry = rx / (2.5f + nodeBox.width / 50f)
                Modifier. offset(y = (ry * 0.75f).dp)
            }
            NodeShape.Asymmetric -> Modifier.offset(x = (nodeBox.height / 8f).dp)
            else -> Modifier
        }
        BasicText(text = wrappedLabel, style = textStyle, modifier = labelModifier)
    }
}

/**
 * Turns the parser's `**bold**` / `*italic*` markers (normalised from
 * `<b>`/`<i>` in the source) into [AnnotatedString] spans.
 * This is the extent of rich text in v1 — no HTML layout.
 */
fun annotatedLabel(label: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    var bold = false
    var italic = false
    val plain = StringBuilder()
    fun flush() {
        if (plain.isEmpty()) return
        val text = plain.toString()
        plain.clear()
        when {
            bold && italic -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(text) }
            bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }
            italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text) }
            else -> append(text)
        }
    }
    while (i < label.length) {
        when {
            label.startsWith("**", i) -> { flush(); bold = !bold; i += 2 }
            label[i] == '*' -> { flush(); italic = !italic; i += 1 }
            else -> { plain.append(label[i]); i += 1 }
        }
    }
    flush()
}
