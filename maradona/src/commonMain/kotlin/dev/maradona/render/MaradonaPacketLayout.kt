package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.PacketDiagramLayout
import dev.maradona.layout.PacketLayout
import dev.maradona.layout.PositionedPacketBlock
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.PacketBitOrder
import dev.maradona.model.PacketBlock
import dev.maradona.model.PacketDiagram
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private class PacketLayoutHolder {
    var layout: PacketDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun PositionedPacketBlock.blockAndBitsBounds(showBits: Boolean, density: Float): DiagramRect {
    if (!showBits) return rect
    val bitH = 16f * density
    return DiagramRect(
        x = rect.x,
        y = rect.y - bitH,
        width = rect.width,
        height = rect.height + bitH,
    )
}

/**
 * Custom Compose [Layout] for Mermaid `packet` / `packet-beta` diagrams.
 *
 * The chart title, bit numbers, and field labels are real [BasicText] composables;
 * word-row block rectangles and borders are drawn on the underlay canvas.
 */
@Composable
fun MaradonaPacketLayout(
    diagram: PacketDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (PacketDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { PacketLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val allBlocks = remember(diagram) { diagram.words.flatten() }
    val textMeasurer = rememberTextMeasurer()
    val densityObj = LocalDensity.current
    val renderDensity = densityObj.density
    val renderFontScale = densityObj.fontScale
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontResolutionToken = listOf(
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontStyle = FontStyle.Italic).value,
    )
    val monoFontResolutionToken = fontFamilyResolver.resolve(fontFamily = theme.monospaceFontFamily).value

    val precomputedLayout = run {
        val cached = holder.layout
        if (
            cached != null &&
            abs(holder.lastDensity - renderDensity) < 1e-4f &&
            abs(holder.lastFontScale - renderFontScale) < 1e-4f &&
            holder.lastFontResolutionToken == fontResolutionToken &&
            holder.lastMonospaceFontResolutionToken == monoFontResolutionToken
        ) {
            cached
        } else {
            computePremeasuredPacketLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastFontResolutionToken = fontResolutionToken
                holder.lastMonospaceFontResolutionToken = monoFontResolutionToken
            }.layout
        }
    }
    val effectiveViewport = visibleViewport
        ?: viewportResolver?.invoke(precomputedLayout.width, precomputedLayout.height)
    val activeCullViewport = effectiveViewport
    holder.visibleViewport = activeCullViewport

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
                    "Packet diagram. ${diagram.fields.size} fields, ${diagram.words.size} rows."
                }
                val vp = holder.visibleViewport
                if (vp != null && onNodeClick != null) {
                    val offscreenEvents = buildList {
                        for (pb in precomputedLayout.blocks) {
                            if (rectIntersectsViewport(
                                    pb.blockAndBitsBounds(diagram.config.showBits, renderDensity),
                                    vp,
                                    8f * renderDensity,
                                )
                            ) {
                                continue
                            }
                            val block = pb.block
                            add(MaradonaNodeClickEvent(nodeId = "${block.start}-${block.end}", label = block.label))
                        }
                    }
                    val actions = buildOffscreenCustomActions(offscreenEvents, onNodeClick, uriHandler)
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawPacketUnderlay(it, theme, holder.visibleViewport) }
            },
        content = {
            if (!diagram.title.isNullOrBlank()) {
                val tr = precomputedLayout.titleRect
                if (activeCullViewport == null || tr == null || rectIntersectsViewport(tr, activeCullViewport, 8f * renderDensity)) {
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
            }

            for ((idx, block) in allBlocks.withIndex()) {
                if (activeCullViewport != null) {
                    val pb = precomputedLayout.blocks.getOrNull(idx)
                    if (pb != null && !rectIntersectsViewport(
                            pb.blockAndBitsBounds(diagram.config.showBits, renderDensity),
                            activeCullViewport,
                            8f * renderDensity,
                        )
                    ) {
                        continue
                    }
                }
                val isSingleBit = block.bits == 1
                if (diagram.config.showBits) {
                    val leadingBit = when (diagram.config.bitOrder) {
                        PacketBitOrder.Ascending -> block.start.toString()
                        PacketBitOrder.Descending -> block.end.toString()
                    }
                    val trailingBit = when (diagram.config.bitOrder) {
                        PacketBitOrder.Ascending -> block.end.toString()
                        PacketBitOrder.Descending -> block.start.toString()
                    }
                    val bitFontSp = if (!isSingleBit && block.bits <= 2 &&
                        (leadingBit.length >= 3 || trailingBit.length >= 3)
                    ) {
                        8.5f
                    } else {
                        10f
                    }
                    val resolvedBitFontSize = minOf(theme.scaledSp(bitFontSp).value, 11f).sp
                    BasicText(
                        text = leadingBit,
                        modifier = Modifier.layoutId("bit_lead:$idx"),
                        maxLines = 1,
                        style = TextStyle(
                            color = theme.subgraphText,
                            fontFamily = theme.monospaceFontFamily,
                            fontSize = resolvedBitFontSize,
                            textAlign = if (isSingleBit) TextAlign.Center else TextAlign.Start,
                        ),
                    )
                    if (!isSingleBit) {
                        BasicText(
                            text = trailingBit,
                            modifier = Modifier.layoutId("bit_trail:$idx"),
                            maxLines = 1,
                            style = TextStyle(
                                color = theme.subgraphText,
                                fontFamily = theme.monospaceFontFamily,
                                fontSize = resolvedBitFontSize,
                                textAlign = TextAlign.End,
                            ),
                        )
                    }
                }

                if (block.label.isNotEmpty()) {
                    val labelFontSp = computeBlockLabelFontSize(block, diagram.config.bitWidth)
                    val maxRowLabelSp = (diagram.config.rowHeight * 0.44f).coerceAtLeast(9f)
                    val resolvedLabelFontSize = minOf(theme.scaledSp(labelFontSp).value, maxRowLabelSp).sp
                    BasicText(
                        text = annotatedLabel(block.label),
                        modifier = Modifier.layoutId("label:$idx"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = resolvedLabelFontSize,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }

                val rangeText = if (block.start == block.end) {
                    "bit ${block.start}"
                } else {
                    "bits ${block.start}..${block.end}"
                }
                val baseDesc = "Packet field ${block.label} ($rangeText)"
                val nodeId = "${block.start}-${block.end}"
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = nodeId, label = block.label) else null
                Box(
                    modifier = Modifier
                        .layoutId("blockbox:$idx")
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
        val leadMeasurables = HashMap<Int, Measurable>()
        val trailMeasurables = HashMap<Int, Measurable>()
        val labelMeasurables = HashMap<Int, Measurable>()
        val boxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("bit_lead:") -> {
                    val idx = id.removePrefix("bit_lead:").toIntOrNull() ?: continue
                    leadMeasurables[idx] = m
                }
                id.startsWith("bit_trail:") -> {
                    val idx = id.removePrefix("bit_trail:").toIntOrNull() ?: continue
                    trailMeasurables[idx] = m
                }
                id.startsWith("label:") -> {
                    val idx = id.removePrefix("label:").toIntOrNull() ?: continue
                    labelMeasurables[idx] = m
                }
                id.startsWith("blockbox:") -> {
                    val idx = id.removePrefix("blockbox:").toIntOrNull() ?: continue
                    boxMeasurables[idx] = m
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

        val resolvedTitleSize = if (!diagram.title.isNullOrBlank()) {
            titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) } ?: holder.titleSize
        } else {
            null
        }

        val layout = PacketLayout.layout(
            diagram = diagram,
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
        }
        holder.layout = layout
        onLayout(layout)

        val leadPlaceables = HashMap<Int, Placeable>()
        val trailPlaceables = HashMap<Int, Placeable>()
        val labelPlaceables = HashMap<Int, Placeable>()
        val boxPlaceables = HashMap<Int, Placeable>()

        for (pb in layout.blocks) {
            val idx = pb.blockIndex
            leadMeasurables[idx]?.let { leadPlaceables[idx] = it.measure(unbounded) }
            trailMeasurables[idx]?.let { trailPlaceables[idx] = it.measure(unbounded) }
            labelMeasurables[idx]?.let { m ->
                val maxLabelW = max(1, (pb.rect.width - 4f * renderDensity).roundToInt())
                labelPlaceables[idx] = m.measure(Constraints(maxWidth = maxLabelW))
            }
            boxMeasurables[idx]?.let { m ->
                boxPlaceables[idx] = m.measure(
                    Constraints.fixed(
                        pb.rect.width.roundToInt().coerceAtLeast(1),
                        pb.rect.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
        }

        val drawnBlocks = if (activeCullViewport == null) {
            layout.blocks.size
        } else {
            layout.blocks.count { rectIntersectsViewport(it.rect, activeCullViewport, 8f * renderDensity) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = boxPlaceables.size,
                totalNodes = layout.blocks.size,
                composedEdgeLabels = 0,
                totalEdgeLabels = 0,
                drawnEdges = drawnBlocks,
                totalEdges = layout.blocks.size,
                drawnSubgraphs = 0,
                totalSubgraphs = 0,
            ),
        )

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }

            val bitPadX = (2f * renderDensity).roundToInt()
            val bitPadY = (2f * renderDensity).roundToInt()

            for (pb in layout.blocks) {
                val idx = pb.blockIndex
                boxPlaceables[idx]?.place(pb.rect.x.roundToInt(), pb.rect.y.roundToInt())

                val leadP = leadPlaceables[idx]
                val trailP = trailPlaceables[idx]
                if (leadP != null) {
                    val bitY = (pb.rect.y.roundToInt() - leadP.height - bitPadY).coerceAtLeast(0)
                    if (pb.isSingleBit) {
                        val cx = (pb.rect.centerX - leadP.width / 2f).roundToInt()
                        leadP.place(cx, bitY)
                    } else {
                        val lx = pb.rect.x.roundToInt() + bitPadX
                        leadP.place(lx, bitY)
                        if (trailP != null) {
                            val ty = (pb.rect.y.roundToInt() - trailP.height - bitPadY).coerceAtLeast(0)
                            val rx = max(
                                lx + leadP.width + bitPadX,
                                pb.rect.right.roundToInt() - trailP.width - bitPadX,
                            )
                            trailP.place(rx, ty)
                        }
                    }
                }

                val labelP = labelPlaceables[idx]
                if (labelP != null) {
                    val lx = (pb.rect.centerX - labelP.width / 2f).roundToInt()
                    val ly = (pb.rect.centerY - labelP.height / 2f).roundToInt()
                    labelP.place(lx, ly)
                }
            }
        }
    }
}

private data class PremeasuredPacketResult(
    val layout: PacketDiagramLayout,
    val titleSize: DiagramSize?,
)

private fun computePremeasuredPacketLayout(
    diagram: PacketDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredPacketResult {
    val renderDensity = densityObj.density
    val titleSize = diagram.title?.takeIf { it.isNotBlank() }?.let { title ->
        measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(title),
            style = TextStyle(
                color = theme.nodeText,
                fontFamily = theme.fontFamily,
                fontSize = theme.titleFontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
        )
    }
    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            density = renderDensity,
        )
    }
    val layout = PacketLayout.layout(
        diagram = diagram,
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredPacketResult(
        layout = layout,
        titleSize = titleSize,
    )
}

private fun DrawScope.drawPacketUnderlay(
    layout: PacketDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val strokeW = max(1f, 1.35f * unit)
    for (pb in layout.blocks) {
        if (!rectIntersectsViewport(pb.rect, viewport, 8f * unit)) continue
        val topLeft = Offset(pb.rect.x, pb.rect.y)
        val rectSize = Size(pb.rect.width, pb.rect.height)
        drawRect(
            color = if (pb.isSingleBit) theme.subgraphFill else theme.nodeFill,
            topLeft = topLeft,
            size = rectSize,
            style = Fill,
        )
        drawRect(
            color = theme.nodeBorder,
            topLeft = topLeft,
            size = rectSize,
            style = Stroke(width = strokeW),
        )
    }
}

private fun computeBlockLabelFontSize(block: PacketBlock, bitWidth: Float): Float {
    val availW = (block.bits * bitWidth - 6f).coerceAtLeast(10f)
    val len = block.label.length.coerceAtLeast(1)
    return when {
        block.bits == 1 && len >= 3 -> 9.5f
        len * 7.2f <= availW -> 12.5f
        len * 6.2f <= availW -> 11f
        len * 5.4f <= availW -> 9.5f
        else -> 9f
    }
}
