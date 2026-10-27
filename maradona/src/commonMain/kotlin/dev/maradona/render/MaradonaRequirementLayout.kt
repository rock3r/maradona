package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.RequirementBoxMetrics
import dev.maradona.layout.RequirementDiagramLayout
import dev.maradona.layout.RequirementLayout
import dev.maradona.model.Direction
import dev.maradona.model.ElementItem
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.RequirementDiagram
import dev.maradona.model.RequirementDiagramItem
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class RequirementLayoutHolder {
    var layout: RequirementDiagramLayout? = null
    var naturalLayout: RequirementDiagramLayout? = null
    var adaptedTbLayout: RequirementDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var stereoSizes: Map<Int, DiagramSize> = emptyMap()
    var nameSizes: Map<Int, DiagramSize> = emptyMap()
    var bodySizes: Map<String, DiagramSize> = emptyMap()
    var relSizes: Map<Int, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

/**
 * Custom Compose [Layout] for Mermaid `requirementDiagram` diagrams.
 *
 * Stereotype headers (`<<Requirement>>`, `<<Element>>`, etc.), requirement/element names,
 * attribute rows (`ID`, `Text`, `Risk`, `Verification`, `Type`, `Doc Ref`), and relationship
 * labels (`<<satisfies>>`, `<<contains>>`, etc.) are real [BasicText] composables; the
 * compartmentalized boxes, dashed/solid relationship splines, `⊕` containment markers, and
 * open-V arrowheads are drawn on the underlay canvas.
 */
@Composable
fun MaradonaRequirementLayout(
    diagram: RequirementDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(nodeGap = 48f, layerGap = 56f),
    availableWidthPx: Int = Int.MAX_VALUE,
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (RequirementDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { RequirementLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val allItems = remember(diagram) { diagram.items }
    val idxByName = remember(allItems) {
        HashMap<String, Int>(allItems.size).apply {
            for ((i, it) in allItems.withIndex()) put(it.name, i)
        }
    }
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

    var observedBoundedWidthPx by remember(diagram, theme, layoutOptions) { mutableIntStateOf(Int.MAX_VALUE) }
    var boundedWidthObserved by remember(diagram, theme, layoutOptions) { mutableStateOf(false) }
    val effectiveAvailableWidthPx = minOf(availableWidthPx, observedBoundedWidthPx)

    val naturalLayout = run {
        val cachedNatural = holder.naturalLayout
        if (
            cachedNatural != null &&
            abs(holder.lastDensity - renderDensity) < 1e-4f &&
            abs(holder.lastFontScale - renderFontScale) < 1e-4f &&
            holder.lastFontResolutionToken == fontResolutionToken &&
            holder.lastMonospaceFontResolutionToken == monoFontResolutionToken
        ) {
            cachedNatural
        } else {
            computePremeasuredRequirementLayout(
                diagram = diagram,
                allItems = allItems,
                idxByName = idxByName,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.naturalLayout = pre.naturalLayout
                holder.adaptedTbLayout = null
                holder.titleSize = pre.titleSize
                holder.stereoSizes = pre.stereoSizes
                holder.nameSizes = pre.nameSizes
                holder.bodySizes = pre.bodySizes
                holder.relSizes = pre.relSizes
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastFontResolutionToken = fontResolutionToken
                holder.lastMonospaceFontResolutionToken = monoFontResolutionToken
            }.naturalLayout
        }
    }

    fun resolveAdaptedTbLayout(): RequirementDiagramLayout {
        holder.adaptedTbLayout?.let { return it }
        val baseOptions = layoutOptions.copy(density = renderDensity, directionOverride = Direction.TB)
        return RequirementLayout.layout(
            diagram = diagram,
            itemBoxSize = { item ->
                computeRequirementBoxMetrics(
                    item = item,
                    idxByName = idxByName,
                    stereoSizes = holder.stereoSizes,
                    nameSizes = holder.nameSizes,
                    bodySizes = holder.bodySizes,
                    renderDensity = renderDensity,
                )
            },
            relationLabelSize = { relIdx, rel ->
                val sz = holder.relSizes[relIdx]
                if (sz != null) {
                    DiagramSize(sz.width + 10f * renderDensity, sz.height + 4f * renderDensity)
                } else {
                    RequirementLayout.defaultRelationLabelSize(rel.label, renderDensity)
                }
            },
            titleSize = holder.titleSize,
            options = baseOptions,
        ).also { holder.adaptedTbLayout = it }
    }

    val breakpointPx = with(densityObj) { 640.dp.roundToPx() }
    val effDir = layoutOptions.directionOverride ?: diagram.direction
    val maxAllowedInComposition = if (availableWidthPx in 1 until Int.MAX_VALUE) {
        effectiveAvailableWidthPx
    } else if (effectiveAvailableWidthPx in 1 until breakpointPx) {
        effectiveAvailableWidthPx
    } else {
        Int.MAX_VALUE
    }
    val wantVerticalInComposition = autoDirection &&
        layoutOptions.directionOverride == null &&
        (effDir == Direction.LR || effDir == Direction.RL) &&
        maxAllowedInComposition in 1 until Int.MAX_VALUE &&
        naturalLayout.width > maxAllowedInComposition * 1.15f
    val precomputedLayout = if (wantVerticalInComposition) {
        resolveAdaptedTbLayout()
    } else {
        naturalLayout
    }
    holder.layout = precomputedLayout
    val effectiveViewport = visibleViewport
        ?: viewportResolver?.invoke(precomputedLayout.width, precomputedLayout.height)
    val needsParentWidthProbe = autoDirection &&
        layoutOptions.directionOverride == null &&
        (effDir == Direction.LR || effDir == Direction.RL) &&
        availableWidthPx == Int.MAX_VALUE &&
        !boundedWidthObserved
    val activeCullViewport = if (needsParentWidthProbe) null else effectiveViewport
    holder.visibleViewport = activeCullViewport

    val positionedItemByName = remember(precomputedLayout) {
        precomputedLayout.items.associateBy { it.item.name }
    }

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
                    "Requirement diagram. ${diagram.requirements.size} requirements, ${diagram.elements.size} elements, ${diagram.relations.size} relationships."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (pi in precomputedLayout.items) {
                            if (rectIntersectsViewport(pi.rect, vp, 8f * renderDensity)) continue
                            val item = pi.item
                            val docRefUrl = (item as? ElementItem)?.docRef?.takeIf { it.isNotBlank() }
                            if (docRefUrl != null || onNodeClick != null) {
                                add(
                                    MaradonaNodeClickEvent(
                                        nodeId = item.name,
                                        label = item.name,
                                        url = docRefUrl,
                                    ),
                                )
                            }
                        }
                    }
                    val actions = buildOffscreenCustomActions(offscreenEvents, onNodeClick, uriHandler)
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawRequirementUnderlay(it, theme, holder.visibleViewport) }
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
            for ((itemIdx, item) in allItems.withIndex()) {
                if (activeCullViewport != null) {
                    val pi = positionedItemByName[item.name]
                    if (pi != null && !rectIntersectsViewport(pi.rect, activeCullViewport, 8f * renderDensity)) {
                        continue
                    }
                }
                val textColor = parseCssColor(item.style?.textColor) ?: theme.nodeText
                val isBold = item.style?.fontWeightBold == true
                BasicText(
                    text = annotatedLabel(item.stereotype),
                    modifier = Modifier.layoutId("stereo:$itemIdx"),
                    style = TextStyle(
                        color = textColor.copy(alpha = 0.78f),
                        fontFamily = theme.fontFamily,
                        fontSize = theme.captionFontSize,
                        fontStyle = FontStyle.Italic,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    ),
                )
                BasicText(
                    text = annotatedLabel(item.name),
                    modifier = Modifier.layoutId("name:$itemIdx"),
                    style = TextStyle(
                        color = textColor,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(13.5f),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                for ((lineIdx, bodyLine) in item.bodyLines.withIndex()) {
                    BasicText(
                        text = annotatedLabel(bodyLine),
                        modifier = Modifier.layoutId("body:$itemIdx:$lineIdx"),
                        style = TextStyle(
                            color = textColor,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(11.5f),
                            fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
                val details = item.bodyLines.joinToString("; ")
                val baseDesc = buildString {
                    append("${item.stereotype} ${item.name}")
                    if (details.isNotEmpty()) append(": $details")
                }
                val docRefUrl = (item as? ElementItem)?.docRef?.takeIf { it.isNotBlank() }
                val clickEvent = if (docRefUrl != null || onNodeClick != null) {
                    MaradonaNodeClickEvent(
                        nodeId = item.name,
                        label = item.name,
                        url = docRefUrl,
                    )
                } else {
                    null
                }
                Box(
                    modifier = Modifier
                        .layoutId("box:$itemIdx")
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = itemIdx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }
            for ((relIdx, rel) in diagram.relations.withIndex()) {
                if (activeCullViewport != null) {
                    val lr = precomputedLayout.relations.getOrNull(relIdx)?.labelRect
                    if (lr != null && !rectIntersectsViewport(lr, activeCullViewport, 8f * renderDensity)) {
                        continue
                    }
                }
                BasicText(
                    text = annotatedLabel(rel.label),
                    modifier = Modifier.layoutId("rel:$relIdx"),
                    style = TextStyle(
                        color = theme.edgeLabelText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.captionFontSize,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val stereoPlaceables = HashMap<Int, Placeable>()
        val namePlaceables = HashMap<Int, Placeable>()
        val bodyPlaceables = HashMap<String, Placeable>()
        val relPlaceables = HashMap<Int, Placeable>()
        val boxMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("stereo:") -> {
                    val idx = id.removePrefix("stereo:").toIntOrNull() ?: continue
                    stereoPlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("name:") -> {
                    val idx = id.removePrefix("name:").toIntOrNull() ?: continue
                    namePlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("body:") -> {
                    val key = id.removePrefix("body:")
                    bodyPlaceables[key] = m.measure(unbounded)
                }
                id.startsWith("rel:") -> {
                    val idx = id.removePrefix("rel:").toIntOrNull() ?: continue
                    relPlaceables[idx] = m.measure(unbounded)
                }
                id.startsWith("box:") -> {
                    val idx = id.removePrefix("box:").toIntOrNull() ?: continue
                    boxMeasurables[idx] = m
                }
            }
        }

        val boundedWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        if (!boundedWidthObserved || observedBoundedWidthPx != boundedWidth) {
            observedBoundedWidthPx = boundedWidth
            boundedWidthObserved = true
        }
        val effectiveMaxWidth = minOf(boundedWidth, availableWidthPx)
        val breakpoint = 640.dp.roundToPx()
        val maxAllowedAtMeasure = if (availableWidthPx in 1 until Int.MAX_VALUE) {
            effectiveMaxWidth
        } else if (effectiveMaxWidth in 1 until breakpoint) {
            effectiveMaxWidth
        } else {
            Int.MAX_VALUE
        }

        val padX = 14f * renderDensity
        val padY = 10f * renderDensity
        val lineGap = 4f * renderDensity

        val mergedStereoSizes = HashMap<Int, DiagramSize>(holder.stereoSizes)
        for ((k, pl) in stereoPlaceables) mergedStereoSizes[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        val mergedNameSizes = HashMap<Int, DiagramSize>(holder.nameSizes)
        for ((k, pl) in namePlaceables) mergedNameSizes[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        val mergedBodySizes = HashMap<String, DiagramSize>(holder.bodySizes)
        for ((k, pl) in bodyPlaceables) mergedBodySizes[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        val mergedRelSizes = HashMap<Int, DiagramSize>(holder.relSizes)
        for ((k, pl) in relPlaceables) mergedRelSizes[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())

        val resolvedTitleSize = if (!diagram.title.isNullOrBlank()) {
            titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) } ?: holder.titleSize
        } else {
            null
        }

        fun computeMetrics(item: RequirementDiagramItem): RequirementBoxMetrics =
            computeRequirementBoxMetrics(
                item = item,
                idxByName = idxByName,
                stereoSizes = mergedStereoSizes,
                nameSizes = mergedNameSizes,
                bodySizes = mergedBodySizes,
                renderDensity = renderDensity,
            )

        val baseOptions = layoutOptions.copy(density = renderDensity)
        var layout = RequirementLayout.layout(
            diagram = diagram,
            itemBoxSize = ::computeMetrics,
            relationLabelSize = { relIdx, rel ->
                val sz = mergedRelSizes[relIdx]
                if (sz != null) {
                    DiagramSize(sz.width + 10f * renderDensity, sz.height + 4f * renderDensity)
                } else {
                    RequirementLayout.defaultRelationLabelSize(rel.label, renderDensity)
                }
            },
            titleSize = resolvedTitleSize,
            options = baseOptions,
        )
        if (activeCullViewport == null) {
            holder.naturalLayout = layout
            holder.titleSize = resolvedTitleSize
            holder.stereoSizes = mergedStereoSizes
            holder.nameSizes = mergedNameSizes
            holder.bodySizes = mergedBodySizes
            holder.relSizes = mergedRelSizes
        }

        val wantVerticalAtMeasure = autoDirection &&
            baseOptions.directionOverride == null &&
            (effDir == Direction.LR || effDir == Direction.RL) &&
            maxAllowedAtMeasure in 1 until Int.MAX_VALUE &&
            layout.width > maxAllowedAtMeasure * 1.15f
        if (activeCullViewport != null && wantVerticalAtMeasure != wantVerticalInComposition) {
            layout = precomputedLayout
        } else if (wantVerticalAtMeasure) {
            layout = RequirementLayout.layout(
                diagram = diagram,
                itemBoxSize = ::computeMetrics,
                relationLabelSize = { relIdx, rel ->
                    val sz = mergedRelSizes[relIdx]
                    if (sz != null) {
                        DiagramSize(sz.width + 10f * renderDensity, sz.height + 4f * renderDensity)
                    } else {
                        RequirementLayout.defaultRelationLabelSize(rel.label, renderDensity)
                    }
                },
                titleSize = resolvedTitleSize,
                options = baseOptions.copy(directionOverride = Direction.TB),
            ).also { holder.adaptedTbLayout = it }
        }

        holder.layout = layout
        onLayout(layout)

        val boxPlaceables = HashMap<Int, Placeable>()
        for (pi in layout.items) {
            val itemIdx = idxByName[pi.item.name] ?: continue
            val m = boxMeasurables[itemIdx] ?: continue
            boxPlaceables[itemIdx] = m.measure(
                Constraints.fixed(
                    pi.rect.width.roundToInt().coerceAtLeast(1),
                    pi.rect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }

        val edgeCullMargin = 20f * renderDensity
        val drawnEdges = if (activeCullViewport == null) {
            layout.relations.size
        } else {
            layout.relations.count { polylineIntersectsViewport(it.points, activeCullViewport, edgeCullMargin) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = boxPlaceables.size,
                totalNodes = allItems.size,
                composedEdgeLabels = relPlaceables.size,
                totalEdgeLabels = layout.relations.size,
                drawnEdges = drawnEdges,
                totalEdges = layout.relations.size,
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
            for (pi in layout.items) {
                val itemIdx = idxByName[pi.item.name] ?: continue
                val r = pi.rect
                boxPlaceables[itemIdx]?.place(r.x.roundToInt(), r.y.roundToInt())

                val sPl = stereoPlaceables[itemIdx]
                if (sPl != null) {
                    sPl.place(
                        (r.centerX - sPl.width / 2f).roundToInt(),
                        (r.y + padY).roundToInt(),
                    )
                }
                val nPl = namePlaceables[itemIdx]
                if (nPl != null) {
                    nPl.place(
                        (r.centerX - nPl.width / 2f).roundToInt(),
                        (r.y + padY + pi.stereotypeHeight + lineGap).roundToInt(),
                    )
                }
                var curBodyY = r.y + pi.headerHeight + padY
                for (lIdx in pi.bodyLineHeights.indices) {
                    val bPl = bodyPlaceables["$itemIdx:$lIdx"]
                    if (bPl != null) {
                        bPl.place(
                            (r.x + padX).roundToInt(),
                            curBodyY.roundToInt(),
                        )
                    }
                    curBodyY += pi.bodyLineHeights[lIdx] + lineGap
                }
            }
            for (pr in layout.relations) {
                val lr = pr.labelRect ?: continue
                val pl = relPlaceables[pr.index] ?: continue
                pl.place(
                    (lr.centerX - pl.width / 2f).roundToInt(),
                    (lr.centerY - pl.height / 2f).roundToInt(),
                )
            }
        }
    }
}

private fun computeRequirementBoxMetrics(
    item: RequirementDiagramItem,
    idxByName: Map<String, Int>,
    stereoSizes: Map<Int, DiagramSize>,
    nameSizes: Map<Int, DiagramSize>,
    bodySizes: Map<String, DiagramSize>,
    renderDensity: Float,
): RequirementBoxMetrics {
    val itemIdx = idxByName[item.name] ?: return RequirementLayout.defaultBoxMetrics(item, renderDensity)
    val padX = 14f * renderDensity
    val padY = 10f * renderDensity
    val lineGap = 4f * renderDensity
    val sSz = stereoSizes[itemIdx]
    val nSz = nameSizes[itemIdx]
    val stereoW = sSz?.width ?: (item.stereotype.length * 6.8f * renderDensity)
    val stereoH = sSz?.height ?: (15f * renderDensity)
    val titleW = nSz?.width ?: (item.name.length * 8f * renderDensity)
    val titleH = nSz?.height ?: (18f * renderDensity)
    val headerH = padY + stereoH + lineGap + titleH + padY

    val lines = item.bodyLines
    if (lines.isEmpty()) {
        val w = maxOf(130f * renderDensity, stereoW + padX * 2f, titleW + padX * 2f)
        return RequirementBoxMetrics(
            width = w,
            height = headerH,
            headerHeight = headerH,
            stereotypeHeight = stereoH,
            titleHeight = titleH,
            bodyLineHeights = emptyList(),
        )
    }
    var maxBodyW = 0f
    val bodyHeights = lines.indices.map { lIdx ->
        val bSz = bodySizes["$itemIdx:$lIdx"]
        val bw = bSz?.width ?: (lines[lIdx].length * 6.8f * renderDensity)
        val bh = bSz?.height ?: (16f * renderDensity)
        if (bw > maxBodyW) maxBodyW = bw
        bh
    }
    val bodyTotalH = padY + bodyHeights.sum() + (bodyHeights.size - 1) * lineGap + padY
    val w = maxOf(150f * renderDensity, stereoW + padX * 2f, titleW + padX * 2f, maxBodyW + padX * 2f)
    return RequirementBoxMetrics(
        width = w,
        height = headerH + bodyTotalH,
        headerHeight = headerH,
        stereotypeHeight = stereoH,
        titleHeight = titleH,
        bodyLineHeights = bodyHeights,
    )
}

private data class PremeasuredRequirementResult(
    val naturalLayout: RequirementDiagramLayout,
    val titleSize: DiagramSize?,
    val stereoSizes: Map<Int, DiagramSize>,
    val nameSizes: Map<Int, DiagramSize>,
    val bodySizes: Map<String, DiagramSize>,
    val relSizes: Map<Int, DiagramSize>,
)

private fun computePremeasuredRequirementLayout(
    diagram: RequirementDiagram,
    allItems: List<RequirementDiagramItem>,
    idxByName: Map<String, Int>,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredRequirementResult {
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
    val stereoSizes = HashMap<Int, DiagramSize>(allItems.size)
    val nameSizes = HashMap<Int, DiagramSize>(allItems.size)
    val bodySizes = HashMap<String, DiagramSize>()

    for ((itemIdx, item) in allItems.withIndex()) {
        val textColor = parseCssColor(item.style?.textColor) ?: theme.nodeText
        val isBold = item.style?.fontWeightBold == true
        stereoSizes[itemIdx] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(item.stereotype),
            style = TextStyle(
                color = textColor.copy(alpha = 0.78f),
                fontFamily = theme.fontFamily,
                fontSize = theme.captionFontSize,
                fontStyle = FontStyle.Italic,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
        nameSizes[itemIdx] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(item.name),
            style = TextStyle(
                color = textColor,
                fontFamily = theme.fontFamily,
                fontSize = theme.scaledSp(13.5f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        val bodyStyle = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = theme.scaledSp(11.5f),
            fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal,
        )
        for ((lineIdx, bodyLine) in item.bodyLines.withIndex()) {
            bodySizes["$itemIdx:$lineIdx"] = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(bodyLine),
                style = bodyStyle,
            )
        }
    }

    val relStyle = TextStyle(
        color = theme.edgeLabelText,
        fontFamily = theme.fontFamily,
        fontSize = theme.captionFontSize,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val relSizes = diagram.relations.mapIndexed { relIdx, rel ->
        relIdx to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(rel.label),
            style = relStyle,
        )
    }.toMap()

    val baseOptions = layoutOptions.copy(density = renderDensity)
    val naturalLayout = RequirementLayout.layout(
        diagram = diagram,
        itemBoxSize = { item ->
            computeRequirementBoxMetrics(
                item = item,
                idxByName = idxByName,
                stereoSizes = stereoSizes,
                nameSizes = nameSizes,
                bodySizes = bodySizes,
                renderDensity = renderDensity,
            )
        },
        relationLabelSize = { relIdx, rel ->
            val sz = relSizes[relIdx]
            if (sz != null) {
                DiagramSize(sz.width + 10f * renderDensity, sz.height + 4f * renderDensity)
            } else {
                RequirementLayout.defaultRelationLabelSize(rel.label, renderDensity)
            }
        },
        titleSize = titleSize,
        options = baseOptions,
    )
    return PremeasuredRequirementResult(
        naturalLayout = naturalLayout,
        titleSize = titleSize,
        stereoSizes = stereoSizes,
        nameSizes = nameSizes,
        bodySizes = bodySizes,
        relSizes = relSizes,
    )
}

private fun DrawScope.drawRequirementUnderlay(
    layout: RequirementDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val defaultReqHeaderFill = theme.accentFill
    val defaultElemHeaderFill = theme.secondaryFill
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(7f * unit, 5f * unit), 0f)

    // 1. Requirement & Element boxes.
    for (pi in layout.items) {
        val r = pi.rect
        if (!rectIntersectsViewport(r, viewport, 8f * unit)) continue
        val st = pi.item.style
        val customFill = parseCssColor(st?.fill)
        val bodyFill = customFill ?: theme.nodeFill
        val headerFill = customFill ?: if (pi.item is ElementItem) defaultElemHeaderFill else defaultReqHeaderFill
        val borderCol = parseCssColor(st?.stroke) ?: theme.nodeBorder
        val strokeW = (st?.strokeWidth ?: 1.4f) * unit

        // Body background + header tint.
        drawRect(
            color = bodyFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = headerFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, pi.headerHeight.coerceAtMost(r.height)),
        )
        if (pi.bodyLineHeights.isNotEmpty() && pi.headerHeight < r.height) {
            val divY = r.y + pi.headerHeight
            drawLine(
                color = borderCol,
                start = Offset(r.x, divY),
                end = Offset(r.right, divY),
                strokeWidth = 1.1f * unit,
            )
        }
        drawRect(
            color = borderCol,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = strokeW),
        )
    }

    // 2. Relationship edges, `⊕` containment markers, open-V arrowheads, and label pills.
    val edgeColor = theme.edge
    for (pr in layout.relations) {
        val pts = pr.points
        if (pts.size >= 2 && polylineIntersectsViewport(pts, viewport, 20f * unit)) {
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    lineTo(pts[i].x, pts[i].y)
                }
            }
            drawPath(
                path = path,
                color = edgeColor,
                style = Stroke(
                    width = 1.5f * unit,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (pr.relation.isContains) null else dashEffect,
                ),
            )

            if (pr.relation.isContains) {
                drawContainsMarker(pts[0], pts[1], edgeColor, theme.background, unit)
            } else {
                drawOpenArrow(pts[pts.lastIndex - 1], pts.last(), edgeColor, unit)
            }
        }

        val lr = pr.labelRect
        if (lr != null && rectIntersectsViewport(lr, viewport, 8f * unit)) {
            drawRoundRect(
                color = theme.edgeLabelBackground,
                topLeft = Offset(lr.x, lr.y),
                size = Size(lr.width, lr.height),
                cornerRadius = CornerRadius(4f * unit, 4f * unit),
            )
        }
    }
}

private fun DrawScope.drawContainsMarker(
    at: DiagramPoint,
    towards: DiagramPoint,
    color: Color,
    fill: Color,
    unit: Float,
) {
    val angle = atan2(towards.y - at.y, towards.x - at.x)
    val radius = 5.5f * unit
    val cx = at.x + cos(angle) * radius
    val cy = at.y + sin(angle) * radius
    val center = Offset(cx, cy)
    drawCircle(
        color = fill,
        radius = radius,
        center = center,
    )
    drawCircle(
        color = color,
        radius = radius,
        center = center,
        style = Stroke(width = 1.4f * unit),
    )
    val crossHalf = radius * 0.65f
    drawLine(
        color = color,
        start = Offset(cx - crossHalf, cy),
        end = Offset(cx + crossHalf, cy),
        strokeWidth = 1.3f * unit,
    )
    drawLine(
        color = color,
        start = Offset(cx, cy - crossHalf),
        end = Offset(cx, cy + crossHalf),
        strokeWidth = 1.3f * unit,
    )
}

private fun DrawScope.drawOpenArrow(
    from: DiagramPoint,
    tip: DiagramPoint,
    color: Color,
    unit: Float,
) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val wingLen = 8.5f * unit
    val spread = 0.48f
    val x1 = tip.x - wingLen * cos(angle - spread)
    val y1 = tip.y - wingLen * sin(angle - spread)
    val x2 = tip.x - wingLen * cos(angle + spread)
    val y2 = tip.y - wingLen * sin(angle + spread)
    val p = Path().apply {
        moveTo(x1, y1)
        lineTo(tip.x, tip.y)
        lineTo(x2, y2)
    }
    drawPath(
        path = p,
        color = color,
        style = Stroke(width = 1.6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

internal fun measureRequirementLayoutForExport(
    diagram: RequirementDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): RequirementDiagramLayout {
    val allItems = diagram.items
    val idxByName = HashMap<String, Int>(allItems.size).apply {
        for ((i, it) in allItems.withIndex()) put(it.name, i)
    }
    return computePremeasuredRequirementLayout(
        diagram = diagram,
        allItems = allItems,
        idxByName = idxByName,
        theme = theme,
        layoutOptions = layoutOptions,
        densityObj = densityObj,
        textMeasurer = textMeasurer,
    ).naturalLayout
}

