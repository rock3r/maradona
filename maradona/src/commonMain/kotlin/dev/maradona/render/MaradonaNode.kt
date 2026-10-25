package dev.maradona.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.maradona.layout.NodeSizing
import dev.maradona.model.FlowNode
import dev.maradona.model.MaradonaNodeClickEvent
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
    traversalOrder: Int = 0,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
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
    val inlineImages = remember(node.label) { extractInlineImages(node.label) }
    val inlineContent = rememberInlineImageContent(inlineImages)
    val wrappedLabel = annotatedLabel(node.label, theme.monospaceFontFamily, inlineContent.keys)
    val placeholderRanges = remember(wrappedLabel, inlineContent) {
        if (inlineContent.isEmpty()) {
            emptyList()
        } else {
            wrappedLabel.getStringAnnotations(0, wrappedLabel.length).mapNotNull { range ->
                val ic = inlineContent[range.item] ?: return@mapNotNull null
                androidx.compose.ui.text.AnnotatedString.Range(
                    item = ic.placeholder,
                    start = range.start,
                    end = range.end,
                )
            }
        }
    }
    val wrapPx = with(LocalDensity.current) { NodeSizing.WRAPPING_WIDTH.dp.roundToPx() }
    val measured = measurer.measure(
        text = wrappedLabel,
        style = textStyle,
        constraints = Constraints(maxWidth = wrapPx),
        placeholders = placeholderRanges,
    )
    // Mermaid's label box is `lines x (fontSize * 1.5)` tall (its SVG sets
    // line-height: 1.5), not the font's tight line height, so derive it from the
    // line count unless inline image placeholders make the measured height taller.
    val densityVal = LocalDensity.current.density
    val labelBox = NodeSizing.labelBox(
        node.shape,
        measured.size.width / densityVal,
        maxOf(measured.lineCount * theme.nodeFontSize.value * 1.5f, measured.size.height / densityVal),
    )
    val nodeBox = NodeSizing.nodeBox(node.shape, labelBox.width, labelBox.height)
    val plainLabel = plainTextLabel(node.label)
    val description = if (connectionsDescription.isNullOrBlank()) {
        plainLabel
    } else {
        "$plainLabel. $connectionsDescription"
    }
    val clickEvent = node.action?.toClickEvent(node.id, plainLabel)
        ?: if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = node.id, label = plainLabel) else null

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
            .diagramNodeInteraction(
                baseDescription = description,
                traversalOrder = traversalOrder,
                event = clickEvent,
                onNodeClick = onNodeClick,
                uriHandler = uriHandler,
                mergeDescendants = true,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val labelModifier = when (node.shape) {
            NodeShape.Cylinder -> {
                val rx = nodeBox.width / 2f
                val ry = rx / (2.5f + nodeBox.width / 50f)
                Modifier.offset(y = (ry * 0.75f).dp)
            }
            NodeShape.Asymmetric -> Modifier.offset(x = (nodeBox.height / 8f).dp)
            else -> Modifier
        }
        BasicText(
            text = wrappedLabel,
            style = textStyle,
            modifier = labelModifier,
            inlineContent = inlineContent,
        )
    }
}
