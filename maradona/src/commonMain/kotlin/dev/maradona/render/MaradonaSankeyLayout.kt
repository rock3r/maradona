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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.SankeyDiagramLayout
import dev.maradona.layout.SankeyLayout
import dev.maradona.model.SankeyDiagram
import dev.maradona.model.SankeyLabelStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private class SankeyLayoutHolder {
    var layout: SankeyDiagramLayout? = null
}

// Tableau-10 / Flexoki-harmonized categorical palette matching Mermaid's d3.schemeTableau10.
private val SANKEY_PALETTE_LIGHT = listOf(
    Color(0xFF4E79A7),
    Color(0xFFF28E2B),
    Color(0xFFE15759),
    Color(0xFF76B7B2),
    Color(0xFF59A14F),
    Color(0xFFEDC948),
    Color(0xFFB07AA1),
    Color(0xFFFF9DA7),
    Color(0xFF9C755F),
    Color(0xFFBAB0AC),
)

private val SANKEY_PALETTE_DARK = listOf(
    Color(0xFF5B8CC0),
    Color(0xFFF59D3D),
    Color(0xFFEB6B6D),
    Color(0xFF88C6C1),
    Color(0xFF6BB361),
    Color(0xFFF2D45C),
    Color(0xFFC18EB3),
    Color(0xFFFFB0B8),
    Color(0xFFB08973),
    Color(0xFFCBC2BE),
)

private fun resolveSankeyNodeColor(
    nodeIndex: Int,
    nodeId: String,
    explicitColor: String?,
    nodeColorsMap: Map<String, String>,
    isDark: Boolean,
): Color {
    parseCssColor(explicitColor)?.let { return it }
    parseCssColor(nodeColorsMap[nodeId])?.let { return it }
    val palette = if (isDark) SANKEY_PALETTE_DARK else SANKEY_PALETTE_LIGHT
    return palette[nodeIndex % palette.size]
}

/**
 * Custom Compose [Layout] for Mermaid `sankey` / `sankey-beta` diagrams.
 *
 * Node labels (with optional flow values) and the chart title are real [BasicText]
 * composables; the proportional cubic-Bezier flow ribbons and vertical node bars
 * are drawn on the underlay canvas.
 */
@Composable
fun MaradonaSankeyLayout(
    diagram: SankeyDiagram,
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (SankeyDiagramLayout) -> Unit = {},
) {
    val holder = remember { SankeyLayoutHolder() }
    val isDark = (theme.background.red * 0.299f + theme.background.green * 0.587f + theme.background.blue * 0.114f) < 0.45f

    // Precompute formatted label texts for each node in diagram.nodes order.
    val nodeLabelTexts = remember(diagram) {
        val inSums = DoubleArray(diagram.nodes.size)
        val outSums = DoubleArray(diagram.nodes.size)
        val idxById = HashMap<String, Int>(diagram.nodes.size)
        for ((i, n) in diagram.nodes.withIndex()) idxById[n.id] = i
        for (l in diagram.links) {
            val u = idxById[l.source] ?: continue
            val v = idxById[l.target] ?: continue
            outSums[u] += l.value
            inSums[v] += l.value
        }
        diagram.nodes.mapIndexed { idx, node ->
            val v = max(inSums[idx], outSums[idx])
            SankeyLayout.formatNodeLabel(node.id, v, diagram)
        }
    }

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
                    "Sankey diagram. ${diagram.nodes.size} nodes, ${diagram.links.size} links."
                }
            }
            .drawBehind {
                holder.layout?.let { drawSankeyUnderlay(it, diagram, theme, isDark) }
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
            for ((idx, lblText) in nodeLabelTexts.withIndex()) {
                BasicText(
                    text = annotatedLabel(lblText),
                    modifier = Modifier.layoutId("nodelbl:$idx"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                val node = diagram.nodes[idx]
                Box(
                    modifier = Modifier
                        .layoutId("nodebox:$idx")
                        .focusable()
                        .semantics {
                            contentDescription = "Sankey node ${lblText.replace('\n', ' ')}"
                        },
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val nodeLblPlaceables = HashMap<Int, Placeable>()
        val labelSizesByText = HashMap<String, DiagramSize>()
        val nodeBoxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("nodelbl:") -> {
                    val idx = id.removePrefix("nodelbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    nodeLblPlaceables[idx] = pl
                    val txt = nodeLabelTexts.getOrNull(idx)
                    if (txt != null) {
                        val prev = labelSizesByText[txt]
                        labelSizesByText[txt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
                id.startsWith("nodebox:") -> {
                    val idx = id.removePrefix("nodebox:").toIntOrNull() ?: continue
                    nodeBoxMeasurables[idx] = m
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

        val layout = SankeyLayout.layout(
            diagram = diagram,
            nodeLabelSize = { s ->
                labelSizesByText[s] ?: SankeyLayout.defaultLabelSize(s, renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val nodeBoxPlaceables = HashMap<Int, Placeable>()
        for (pn in layout.nodes) {
            val m = nodeBoxMeasurables[pn.nodeIndex] ?: continue
            nodeBoxPlaceables[pn.nodeIndex] = m.measure(
                Constraints.fixed(
                    pn.rect.width.roundToInt().coerceAtLeast(1),
                    pn.rect.height.roundToInt().coerceAtLeast(1),
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
            for (pn in layout.nodes) {
                nodeBoxPlaceables[pn.nodeIndex]?.place(
                    pn.rect.x.roundToInt(),
                    pn.rect.y.roundToInt(),
                )
                val pl = nodeLblPlaceables[pn.nodeIndex]
                if (pl != null) {
                    pl.place(
                        pn.labelRect.x.roundToInt(),
                        (pn.labelRect.centerY - pl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawSankeyUnderlay(
    layout: SankeyDiagramLayout,
    diagram: SankeyDiagram,
    theme: MaradonaTheme,
    isDark: Boolean,
) {
    val unit = density
    val nodeColors = layout.nodes.associate { pn ->
        pn.nodeIndex to resolveSankeyNodeColor(
            nodeIndex = pn.nodeIndex,
            nodeId = pn.node.id,
            explicitColor = pn.node.color,
            nodeColorsMap = diagram.nodeColors,
            isDark = isDark,
        )
    }

    // 1. Flow link ribbons.
    val linkMode = diagram.linkColor.trim().lowercase()
    val customLinkColor = if (linkMode != "gradient" && linkMode != "source" && linkMode != "target") {
        parseCssColor(diagram.linkColor)
    } else {
        null
    }

    for (pl in layout.links) {
        val srcCol = (nodeColors[pl.sourceNodeIndex] ?: SANKEY_PALETTE_LIGHT[0]).copy(alpha = 0.45f)
        val dstCol = (nodeColors[pl.targetNodeIndex] ?: SANKEY_PALETTE_LIGHT[1]).copy(alpha = 0.45f)
        val halfW = pl.thickness / 2f
        val path = Path().apply {
            moveTo(pl.start.x, pl.start.y - halfW)
            cubicTo(
                pl.control1.x,
                pl.control1.y - halfW,
                pl.control2.x,
                pl.control2.y - halfW,
                pl.end.x,
                pl.end.y - halfW,
            )
            lineTo(pl.end.x, pl.end.y + halfW)
            cubicTo(
                pl.control2.x,
                pl.control2.y + halfW,
                pl.control1.x,
                pl.control1.y + halfW,
                pl.start.x,
                pl.start.y + halfW,
            )
            close()
        }
        if (linkMode == "gradient" && abs(pl.end.x - pl.start.x) > 1f) {
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    colors = listOf(srcCol, dstCol),
                    start = Offset(pl.start.x, pl.start.y),
                    end = Offset(pl.end.x, pl.end.y),
                ),
            )
        } else {
            val solidColor = when (linkMode) {
                "target" -> dstCol
                "source", "gradient" -> srcCol
                else -> customLinkColor?.copy(alpha = 0.45f) ?: srcCol
            }
            drawPath(
                path = path,
                color = solidColor,
            )
        }
    }

    // 2. Node bars.
    for (pn in layout.nodes) {
        val fill = nodeColors[pn.nodeIndex] ?: SANKEY_PALETTE_LIGHT[0]
        drawRect(
            color = fill,
            topLeft = Offset(pn.rect.x, pn.rect.y),
            size = Size(pn.rect.width, pn.rect.height),
        )
        drawRect(
            color = theme.nodeBorder.copy(alpha = 0.55f),
            topLeft = Offset(pn.rect.x, pn.rect.y),
            size = Size(pn.rect.width, pn.rect.height),
            style = Stroke(width = 0.8f * unit),
        )
    }

    // 3. Optional outlined label halo for SankeyLabelStyle.Outlined.
    if (diagram.labelStyle == SankeyLabelStyle.Outlined) {
        val padX = 3f * unit
        val padY = 1.5f * unit
        val r = CornerRadius(3f * unit, 3f * unit)
        for (pn in layout.nodes) {
            val lr = pn.labelRect
            drawRoundRect(
                color = theme.background.copy(alpha = 0.85f),
                topLeft = Offset(lr.x - padX, lr.y - padY),
                size = Size(lr.width + padX * 2f, lr.height + padY * 2f),
                cornerRadius = r,
            )
        }
    }
}
