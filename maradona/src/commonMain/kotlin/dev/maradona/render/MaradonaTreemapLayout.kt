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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.PositionedTreemapLeaf
import dev.maradona.layout.TreemapDiagramLayout
import dev.maradona.layout.TreemapLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.TreemapDiagram
import dev.maradona.model.TreemapNode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class TreemapLayoutHolder {
    var layout: TreemapDiagramLayout? = null
}

/**
 * Custom Compose [Layout] for Mermaid `treemap` / `treemap-beta` diagrams.
 *
 * Section headers, section totals, leaf labels, leaf values, and the chart title
 * are real [BasicText] composables; nested squarified section boxes and leaf tiles
 * are drawn on the underlay canvas.
 */
@Composable
fun MaradonaTreemapLayout(
    diagram: TreemapDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onLayout: (TreemapDiagramLayout) -> Unit = {},
) {
    val holder = remember { TreemapLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val isDark = theme.isDark

    // Precompute layout so we know exact section and leaf counts and adaptive font sizes.
    val baseSectionsAndLeaves = remember(diagram) {
        val preview = TreemapLayout.layout(diagram)
        preview.sections to preview.leaves
    }
    val previewSections = baseSectionsAndLeaves.first
    val previewLeaves = baseSectionsAndLeaves.second

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                val accParts = listOfNotNull(
                    diagram.accTitle?.takeIf { it.isNotBlank() },
                    diagram.accDescr?.takeIf { it.isNotBlank() },
                )
                contentDescription = if (accParts.isNotEmpty()) {
                    accParts.joinToString(". ")
                } else {
                    "Treemap diagram. ${previewSections.size} sections, ${previewLeaves.size} leaves."
                }
            }
            .drawBehind {
                holder.layout?.let { drawTreemapUnderlay(it, diagram, theme, isDark) }
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

            for ((idx, sec) in previewSections.withIndex()) {
                val customText = parseCssColor(sec.node.style?.textColor) ?: theme.nodeText
                BasicText(
                    text = annotatedLabel(sec.node.name),
                    modifier = Modifier.layoutId("sec_lbl:$idx"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = customText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.edgeLabelFontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (diagram.config.showValues && sec.totalValue > 0.0) {
                    BasicText(
                        text = sec.formattedValue,
                        modifier = Modifier.layoutId("sec_val:$idx"),
                        maxLines = 1,
                        style = TextStyle(
                            color = customText.copy(alpha = 0.8f),
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(10.5f),
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                }
            }

            for ((idx, leaf) in previewLeaves.withIndex()) {
                val customText = parseCssColor(leaf.node.style?.textColor) ?: theme.nodeText
                val fontWeight = when (leaf.node.style?.fontWeightBold) {
                    true -> FontWeight.Bold
                    false -> FontWeight.Normal
                    null -> FontWeight.SemiBold
                }
                val (lblFontSp, valFontSp) = computeLeafFontSizes(leaf, diagram.config.labelFontSize, diagram.config.valueFontSize)
                BasicText(
                    text = annotatedLabel(leaf.node.name),
                    modifier = Modifier.layoutId("leaf_lbl:$idx"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = customText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(lblFontSp),
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                    ),
                )
                if (diagram.config.showValues) {
                    BasicText(
                        text = leaf.formattedValue,
                        modifier = Modifier.layoutId("leaf_val:$idx"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = customText.copy(alpha = 0.85f),
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(valFontSp),
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                val baseDesc = "Treemap item ${leaf.node.name}: ${leaf.formattedValue}"
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = leaf.node.name, label = leaf.node.name) else null
                Box(
                    modifier = Modifier
                        .layoutId("leaf_box:$idx")
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = idx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val secLblMeasurables = HashMap<Int, Measurable>()
        val secValMeasurables = HashMap<Int, Measurable>()
        val leafLblMeasurables = HashMap<Int, Measurable>()
        val leafValMeasurables = HashMap<Int, Measurable>()
        val leafBoxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("sec_lbl:") -> {
                    val idx = id.removePrefix("sec_lbl:").toIntOrNull() ?: continue
                    secLblMeasurables[idx] = m
                }
                id.startsWith("sec_val:") -> {
                    val idx = id.removePrefix("sec_val:").toIntOrNull() ?: continue
                    secValMeasurables[idx] = m
                }
                id.startsWith("leaf_lbl:") -> {
                    val idx = id.removePrefix("leaf_lbl:").toIntOrNull() ?: continue
                    leafLblMeasurables[idx] = m
                }
                id.startsWith("leaf_val:") -> {
                    val idx = id.removePrefix("leaf_val:").toIntOrNull() ?: continue
                    leafValMeasurables[idx] = m
                }
                id.startsWith("leaf_box:") -> {
                    val idx = id.removePrefix("leaf_box:").toIntOrNull() ?: continue
                    leafBoxMeasurables[idx] = m
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

        val layout = TreemapLayout.layout(
            diagram = diagram,
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val secLblPlaceables = HashMap<Int, Placeable>()
        val secValPlaceables = HashMap<Int, Placeable>()
        for (sec in layout.sections) {
            val idx = sec.sectionIndex
            val valPl = secValMeasurables[idx]?.measure(unbounded)
            if (valPl != null) secValPlaceables[idx] = valPl
            val valW = valPl?.width ?: 0
            val maxSecLblW = max(1, (sec.headerRect.width - 16f * renderDensity - valW).roundToInt())
            secLblMeasurables[idx]?.let { m ->
                secLblPlaceables[idx] = m.measure(Constraints(maxWidth = maxSecLblW))
            }
        }

        val leafLblPlaceables = HashMap<Int, Placeable>()
        val leafValPlaceables = HashMap<Int, Placeable>()
        val leafBoxPlaceables = HashMap<Int, Placeable>()
        for (leaf in layout.leaves) {
            val idx = leaf.leafIndex
            val maxW = max(1, (leaf.rect.width - 8f * renderDensity).roundToInt())
            leafLblMeasurables[idx]?.let { m ->
                leafLblPlaceables[idx] = m.measure(Constraints(maxWidth = maxW))
            }
            leafValMeasurables[idx]?.let { m ->
                leafValPlaceables[idx] = m.measure(Constraints(maxWidth = maxW))
            }
            leafBoxMeasurables[idx]?.let { m ->
                leafBoxPlaceables[idx] = m.measure(
                    Constraints.fixed(
                        leaf.rect.width.roundToInt().coerceAtLeast(1),
                        leaf.rect.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
        }

        val wPx = layout.width.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val hPx = layout.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }

            val pad6 = (6f * renderDensity).roundToInt()
            for (sec in layout.sections) {
                val idx = sec.sectionIndex
                val hr = sec.headerRect
                val lblP = secLblPlaceables[idx]
                val valP = secValPlaceables[idx]
                if (lblP != null && hr.width >= 20f * renderDensity && hr.height >= lblP.height) {
                    val lx = hr.x.roundToInt() + pad6
                    val ly = (hr.centerY - lblP.height / 2f).roundToInt()
                    lblP.place(lx, ly)
                    if (valP != null && lx + lblP.width + pad6 + valP.width <= hr.right.roundToInt() - pad6) {
                        val vx = hr.right.roundToInt() - valP.width - pad6
                        val vy = (hr.centerY - valP.height / 2f).roundToInt()
                        valP.place(vx, vy)
                    }
                }
            }

            val gap2 = (2f * renderDensity).roundToInt()
            for (leaf in layout.leaves) {
                val idx = leaf.leafIndex
                val r = leaf.rect
                leafBoxPlaceables[idx]?.place(r.x.roundToInt(), r.y.roundToInt())
                val lblP = leafLblPlaceables[idx]
                val valP = leafValPlaceables[idx]
                if (lblP != null && lblP.height + (4f * renderDensity) <= r.height && lblP.width <= r.width) {
                    if (valP != null &&
                        lblP.height + gap2 + valP.height + (6f * renderDensity) <= r.height &&
                        valP.width <= r.width
                    ) {
                        val totalH = lblP.height + gap2 + valP.height
                        val topY = (r.centerY - totalH / 2f).roundToInt()
                        lblP.place((r.centerX - lblP.width / 2f).roundToInt(), topY)
                        valP.place((r.centerX - valP.width / 2f).roundToInt(), topY + lblP.height + gap2)
                    } else {
                        lblP.place(
                            (r.centerX - lblP.width / 2f).roundToInt(),
                            (r.centerY - lblP.height / 2f).roundToInt(),
                        )
                    }
                }
            }
        }
    }
}

private fun computeLeafFontSizes(
    leaf: PositionedTreemapLeaf,
    baseLabelSp: Float,
    baseValueSp: Float,
): Pair<Float, Float> {
    val w = leaf.rect.width
    val h = leaf.rect.height
    val nameLen = leaf.node.name.length.coerceAtLeast(1)
    val maxSpByW = ((w - 10f) / (nameLen * 0.62f)).coerceIn(8f, 20f)
    val maxSpByH = (h * 0.34f).coerceIn(8f, 20f)
    val lblSp = minOf(max(baseLabelSp, 15f), maxSpByW, maxSpByH).coerceIn(8f, 18f)
    val valSp = min(baseValueSp, lblSp * 0.82f).coerceIn(7.5f, 14f)
    return lblSp to valSp
}

private fun DrawScope.drawTreemapUnderlay(
    layout: TreemapDiagramLayout,
    diagram: TreemapDiagram,
    theme: MaradonaTheme,
    isDark: Boolean,
) {
    val baseBorderW = diagram.config.borderWidth.coerceAtLeast(0f)

    // 1. Sections
    for (sec in layout.sections) {
        val baseColor = theme.seriesColor(sec.colorIndex)
        val customFill = parseCssColor(sec.node.style?.fill)
        val customStroke = parseCssColor(sec.node.style?.stroke)
        val fillColor = customFill?.copy(alpha = 0.25f) ?: baseColor.copy(alpha = if (isDark) 0.22f else 0.16f)
        val strokeColor = customStroke ?: baseColor.copy(alpha = 0.65f)
        val strokeW = (sec.node.style?.strokeWidth ?: baseBorderW) * density
        val cr = CornerRadius(4f * density, 4f * density)

        drawRoundRect(
            color = fillColor,
            topLeft = Offset(sec.rect.x, sec.rect.y),
            size = Size(sec.rect.width, sec.rect.height),
            cornerRadius = cr,
            style = Fill,
        )
        if (strokeW > 0f) {
            drawRoundRect(
                color = strokeColor,
                topLeft = Offset(sec.rect.x, sec.rect.y),
                size = Size(sec.rect.width, sec.rect.height),
                cornerRadius = cr,
                style = Stroke(width = strokeW),
            )
        }
    }

    // 2. Leaves
    for (leaf in layout.leaves) {
        val baseColor = theme.seriesColor(leaf.colorIndex)
        val customFill = parseCssColor(leaf.node.style?.fill)
        val customStroke = parseCssColor(leaf.node.style?.stroke)
        val fillColor = customFill?.copy(alpha = 0.42f) ?: baseColor.copy(alpha = if (isDark) 0.42f else 0.32f)
        val strokeColor = customStroke ?: baseColor
        val defaultLeafBorderW = if (baseBorderW <= 0f) 0f else max(1f, baseBorderW * 1.2f)
        val strokeW = (leaf.node.style?.strokeWidth ?: defaultLeafBorderW) * density
        val cr = CornerRadius(3f * density, 3f * density)

        drawRoundRect(
            color = fillColor,
            topLeft = Offset(leaf.rect.x, leaf.rect.y),
            size = Size(leaf.rect.width, leaf.rect.height),
            cornerRadius = cr,
            style = Fill,
        )
        if (strokeW > 0f) {
            drawRoundRect(
                color = strokeColor,
                topLeft = Offset(leaf.rect.x, leaf.rect.y),
                size = Size(leaf.rect.width, leaf.rect.height),
                cornerRadius = cr,
                style = Stroke(width = strokeW),
            )
        }
    }
}
