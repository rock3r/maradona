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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import dev.maradona.layout.ArchitectureDiagramLayout
import dev.maradona.layout.ArchitectureLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.model.ArchitectureDiagram
import dev.maradona.model.ArchitecturePortDir
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class ArchitectureLayoutHolder {
    var layout: ArchitectureDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var grpLblSizes: Map<String, DiagramSize> = emptyMap()
    var svcLblSizes: Map<String, DiagramSize> = emptyMap()
    var edgeLblSizesByText: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

/**
 * Custom Compose [Layout] for Mermaid `architecture` / `architecture-beta` diagrams.
 *
 * Group titles, service labels, service `iconText`, edge labels, and the chart title
 * are real [BasicText] composables; dashed group enclosures, `#087EBF` service icon
 * tiles (`database`, `server`, `disk`, `internet`, `cloud`), junctions, and port-to-port
 * orthogonal edges are drawn on the underlay canvas.
 */
@Composable
fun MaradonaArchitectureLayout(
    diagram: ArchitectureDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (ArchitectureDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { ArchitectureLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()
    val densityObj = LocalDensity.current
    val renderDensity = densityObj.density
    val renderFontScale = densityObj.fontScale
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontResolutionToken = listOf(
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Medium).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.SemiBold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontStyle = FontStyle.Italic).value,
    )
    val monoFontResolutionToken = fontFamilyResolver.resolve(fontFamily = theme.monospaceFontFamily).value
    val labelFontSp = diagram.config.fontSize.coerceAtLeast(1f)
    val edgeSummaries = remember(diagram) {
        val labelById = diagram.services.associate { it.id to (it.title?.takeIf { t -> t.isNotBlank() } ?: it.id) }
        val out = HashMap<String, MutableList<String>>()
        for (svc in diagram.services) out[svc.id] = mutableListOf()
        for (e in diagram.edges) {
            val lhsName = labelById[e.lhsId] ?: e.lhsId
            val rhsName = labelById[e.rhsId] ?: e.rhsId
            val lbl = e.title?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            out[e.lhsId]?.add("connects to $rhsName$lbl")
            out[e.rhsId]?.add("connected from $lhsName$lbl")
        }
        out.mapValues { it.value.joinToString("; ") }
    }

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
            computePremeasuredArchitectureLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.grpLblSizes = pre.grpLblSizes
                holder.svcLblSizes = pre.svcLblSizes
                holder.edgeLblSizesByText = pre.edgeLblSizesByText
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastFontResolutionToken = fontResolutionToken
                holder.lastMonospaceFontResolutionToken = monoFontResolutionToken
            }.layout
        }
    }

    val activeCullViewport = visibleViewport
        ?: viewportResolver?.invoke(precomputedLayout.width, precomputedLayout.height)
    holder.visibleViewport = activeCullViewport
    val cullMargin = 8f * renderDensity

    val positionedGroupById = remember(precomputedLayout) {
        precomputedLayout.groups.associateBy { it.group.id }
    }
    val positionedServiceById = remember(precomputedLayout) {
        precomputedLayout.services.associateBy { it.service.id }
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
                    "Architecture diagram. ${diagram.services.size} services, ${diagram.groups.size} groups, ${diagram.edges.size} edges."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (svc in diagram.services) {
                            val ps = positionedServiceById[svc.id] ?: continue
                            if (rectIntersectsViewport(ps.bounds, vp, cullMargin)) continue
                            if (onNodeClick != null) {
                                val desc = svc.title?.takeIf { it.isNotBlank() } ?: svc.id
                                add(MaradonaNodeClickEvent(nodeId = svc.id, label = desc))
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
                holder.layout?.let { drawArchitectureUnderlay(it, theme, holder.visibleViewport) }
            },
        content = {
            if (!diagram.title.isNullOrBlank()) {
                val tr = precomputedLayout.titleRect
                if (activeCullViewport == null || tr == null || rectIntersectsViewport(tr, activeCullViewport, cullMargin)) {
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

            for (grp in diagram.groups) {
                if (activeCullViewport != null) {
                    val pg = positionedGroupById[grp.id]
                    if (pg != null && !rectIntersectsViewport(pg.rect, activeCullViewport, cullMargin)) continue
                }
                val grpLabel = grp.title?.takeIf { it.isNotBlank() } ?: grp.id
                val sgScale = if (theme.subgraphFontSize.value > 0f) theme.subgraphFontSize.value / 14f else 1f
                BasicText(
                    text = annotatedLabel(grpLabel),
                    modifier = Modifier.layoutId("grplbl:${grp.id}"),
                    maxLines = 1,
                    style = TextStyle(
                        color = theme.subgraphText,
                        fontFamily = theme.fontFamily,
                        fontSize = (labelFontSp * sgScale).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }

            for ((svcIdx, svc) in diagram.services.withIndex()) {
                if (activeCullViewport != null) {
                    val ps = positionedServiceById[svc.id]
                    if (ps != null && !rectIntersectsViewport(ps.bounds, activeCullViewport, cullMargin)) continue
                }
                val svcTitle = svc.title?.takeIf { it.isNotBlank() }
                if (svcTitle != null) {
                    BasicText(
                        text = annotatedLabel(svcTitle),
                        modifier = Modifier.layoutId("svclbl:${svc.id}"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(labelFontSp),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                if (!svc.iconText.isNullOrBlank()) {
                    BasicText(
                        text = annotatedLabel(svc.iconText),
                        modifier = Modifier.layoutId("svcicontxt:${svc.id}"),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = theme.accentText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(11.5f),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                val desc = svc.title?.takeIf { it.isNotBlank() } ?: svc.id
                val edgeDesc = edgeSummaries[svc.id]
                val baseDesc = if (edgeDesc.isNullOrBlank()) "Architecture service $desc" else "Architecture service $desc. $edgeDesc"
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = svc.id, label = desc) else null
                Box(
                    modifier = Modifier
                        .layoutId("svcbox:${svc.id}")
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = svcIdx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }

            for ((edgeIdx, edge) in diagram.edges.withIndex()) {
                if (!edge.title.isNullOrBlank()) {
                    if (activeCullViewport != null) {
                        val lr = precomputedLayout.edges.getOrNull(edgeIdx)?.labelRect
                        if (lr != null && !rectIntersectsViewport(lr, activeCullViewport, cullMargin)) continue
                    }
                    BasicText(
                        text = annotatedLabel(edge.title),
                        modifier = Modifier.layoutId("edgelbl:$edgeIdx"),
                        style = TextStyle(
                            color = theme.edgeLabelText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val grpLblPlaceables = HashMap<String, Placeable>()
        val grpLblSizes = HashMap<String, DiagramSize>(holder.grpLblSizes)

        val svcLblPlaceables = HashMap<String, Placeable>()
        val svcLblSizes = HashMap<String, DiagramSize>(holder.svcLblSizes)
        val svcIconTxtMeasurables = HashMap<String, Measurable>()
        val svcBoxMeasurables = HashMap<String, Measurable>()

        val edgeLblPlaceables = HashMap<Int, Placeable>()
        val edgeLblSizesByText = HashMap<String, DiagramSize>(holder.edgeLblSizesByText)

        if (activeCullViewport == null) {
            grpLblSizes.clear()
            svcLblSizes.clear()
            edgeLblSizesByText.clear()
        }

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("grplbl:") -> {
                    val grpId = id.removePrefix("grplbl:")
                    val pl = m.measure(unbounded)
                    grpLblPlaceables[grpId] = pl
                    grpLblSizes[grpId] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                }
                id.startsWith("svclbl:") -> {
                    val svcId = id.removePrefix("svclbl:")
                    val pl = m.measure(unbounded)
                    svcLblPlaceables[svcId] = pl
                    svcLblSizes[svcId] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                }
                id.startsWith("svcicontxt:") -> {
                    val svcId = id.removePrefix("svcicontxt:")
                    svcIconTxtMeasurables[svcId] = m
                }
                id.startsWith("svcbox:") -> {
                    val svcId = id.removePrefix("svcbox:")
                    svcBoxMeasurables[svcId] = m
                }
                id.startsWith("edgelbl:") -> {
                    val idx = id.removePrefix("edgelbl:").toIntOrNull() ?: continue
                    val pl = m.measure(unbounded)
                    edgeLblPlaceables[idx] = pl
                    val txt = diagram.edges.getOrNull(idx)?.title
                    if (txt != null) {
                        val prev = edgeLblSizesByText[txt]
                        edgeLblSizesByText[txt] = DiagramSize(
                            maxOf(prev?.width ?: 0f, pl.width.toFloat()),
                            maxOf(prev?.height ?: 0f, pl.height.toFloat()),
                        )
                    }
                }
            }
        }

        val resolvedTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize

        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.grpLblSizes = grpLblSizes
            holder.svcLblSizes = svcLblSizes
            holder.edgeLblSizesByText = edgeLblSizesByText
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = ArchitectureLayout.layout(
            diagram = diagram,
            serviceLabelSize = { svcId, lbl ->
                svcLblSizes[svcId] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
            },
            groupLabelSize = { grpId, lbl ->
                grpLblSizes[grpId] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
            },
            edgeLabelSize = { lbl ->
                edgeLblSizesByText[lbl] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val svcIconTxtPlaceables = HashMap<String, Placeable>()
        val svcBoxPlaceables = HashMap<String, Placeable>()
        for (ps in layout.services) {
            val svcId = ps.service.id
            svcIconTxtMeasurables[svcId]?.let { m ->
                val maxW = max(1, (ps.iconRect.width - 8f * renderDensity).roundToInt())
                val maxH = max(1, (ps.iconRect.height - 8f * renderDensity).roundToInt())
                svcIconTxtPlaceables[svcId] = m.measure(Constraints(maxWidth = maxW, maxHeight = maxH))
            }
            svcBoxMeasurables[svcId]?.let { m ->
                svcBoxPlaceables[svcId] = m.measure(
                    Constraints.fixed(
                        ps.bounds.width.roundToInt().coerceAtLeast(1),
                        ps.bounds.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
        }

        val edgeMargin = 20f * renderDensity
        val drawnEdges = if (activeCullViewport == null) {
            layout.edges.size
        } else {
            layout.edges.count { polylineIntersectsViewport(it.points, activeCullViewport, edgeMargin) }
        }
        val drawnSubgraphs = if (activeCullViewport == null) {
            layout.groups.size
        } else {
            layout.groups.count { rectIntersectsViewport(it.rect, activeCullViewport) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = svcBoxPlaceables.size,
                totalNodes = layout.services.size,
                composedEdgeLabels = edgeLblPlaceables.size,
                totalEdgeLabels = layout.edges.count { it.labelRect != null },
                drawnEdges = drawnEdges,
                totalEdges = layout.edges.size,
                drawnSubgraphs = drawnSubgraphs,
                totalSubgraphs = layout.groups.size,
            ),
        )

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }

            for (pg in layout.groups) {
                val pl = grpLblPlaceables[pg.group.id]
                val lr = pg.labelRect
                if (pl != null && lr != null) {
                    pl.place(lr.x.roundToInt(), lr.y.roundToInt())
                }
            }

            for (ps in layout.services) {
                val svcId = ps.service.id
                svcBoxPlaceables[svcId]?.place(ps.bounds.x.roundToInt(), ps.bounds.y.roundToInt())
                val iconTxtP = svcIconTxtPlaceables[svcId]
                if (iconTxtP != null) {
                    iconTxtP.place(
                        (ps.iconRect.centerX - iconTxtP.width / 2f).roundToInt(),
                        (ps.iconRect.centerY - iconTxtP.height / 2f).roundToInt(),
                    )
                }
                val lblP = svcLblPlaceables[svcId]
                val lr = ps.labelRect
                if (lblP != null && lr != null) {
                    lblP.place(
                        (lr.centerX - lblP.width / 2f).roundToInt(),
                        lr.y.roundToInt(),
                    )
                }
            }

            for ((edgeIdx, pe) in layout.edges.withIndex()) {
                val pl = edgeLblPlaceables[edgeIdx]
                val lr = pe.labelRect
                if (pl != null && lr != null) {
                    pl.place(
                        (lr.centerX - pl.width / 2f).roundToInt(),
                        (lr.centerY - pl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private data class PremeasuredArchitectureResult(
    val layout: ArchitectureDiagramLayout,
    val titleSize: DiagramSize?,
    val grpLblSizes: Map<String, DiagramSize>,
    val svcLblSizes: Map<String, DiagramSize>,
    val edgeLblSizesByText: Map<String, DiagramSize>,
)

private fun computePremeasuredArchitectureLayout(
    diagram: ArchitectureDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredArchitectureResult {
    val renderDensity = densityObj.density
    val labelFontSp = diagram.config.fontSize.coerceAtLeast(1f)
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

    val sgScale = if (theme.subgraphFontSize.value > 0f) theme.subgraphFontSize.value / 14f else 1f
    val grpStyle = TextStyle(
        color = theme.subgraphText,
        fontFamily = theme.fontFamily,
        fontSize = (labelFontSp * sgScale).sp,
        fontWeight = FontWeight.SemiBold,
    )
    val grpLblSizes = HashMap<String, DiagramSize>(diagram.groups.size)
    for (grp in diagram.groups) {
        val grpLabel = grp.title?.takeIf { it.isNotBlank() } ?: grp.id
        grpLblSizes[grp.id] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(grpLabel),
            style = grpStyle,
        )
    }

    val svcStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(labelFontSp),
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val svcLblSizes = HashMap<String, DiagramSize>(diagram.services.size)
    for (svc in diagram.services) {
        val svcTitle = svc.title?.takeIf { it.isNotBlank() } ?: continue
        svcLblSizes[svc.id] = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(svcTitle),
            style = svcStyle,
        )
    }

    val edgeStyle = TextStyle(
        color = theme.edgeLabelText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        textAlign = TextAlign.Center,
    )
    val edgeLblSizesByText = HashMap<String, DiagramSize>()
    for (edge in diagram.edges) {
        val title = edge.title
        if (!title.isNullOrBlank()) {
            val sz = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(title),
                style = edgeStyle,
            )
            val prev = edgeLblSizesByText[title]
            edgeLblSizesByText[title] = DiagramSize(
                maxOf(prev?.width ?: 0f, sz.width),
                maxOf(prev?.height ?: 0f, sz.height),
            )
        }
    }

    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            density = renderDensity,
        )
    }
    val layout = ArchitectureLayout.layout(
        diagram = diagram,
        serviceLabelSize = { svcId, lbl ->
            svcLblSizes[svcId] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
        },
        groupLabelSize = { grpId, lbl ->
            grpLblSizes[grpId] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
        },
        edgeLabelSize = { lbl ->
            edgeLblSizesByText[lbl] ?: ArchitectureLayout.defaultLabelSize(lbl, renderDensity)
        },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredArchitectureResult(
        layout = layout,
        titleSize = titleSize,
        grpLblSizes = grpLblSizes,
        svcLblSizes = svcLblSizes,
        edgeLblSizesByText = edgeLblSizesByText,
    )
}

private fun DrawScope.drawArchitectureUnderlay(
    layout: ArchitectureDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val iconTileBg = theme.accentBorder
    val iconFg = theme.accentText
    val groupDash = PathEffect.dashPathEffect(floatArrayOf(7f * density, 4.5f * density), 0f)
    val groupStroke = Stroke(width = max(1f, 1.4f * density), pathEffect = groupDash)

    // 1. Groups
    for (pg in layout.groups) {
        val r = pg.rect
        if (!rectIntersectsViewport(r, viewport, 8f * density)) continue
        val cr = CornerRadius(6f * density, 6f * density)
        drawRoundRect(
            color = theme.subgraphFill.copy(alpha = 0.42f),
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Fill,
        )
        drawRoundRect(
            color = theme.subgraphBorder,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = groupStroke,
        )
        val ir = pg.iconRect
        if (ir != null) {
            drawArchitectureIconTile(ir, pg.group.icon, iconTileBg, iconFg)
        }
    }

    // 2. Edges (drawn behind service tiles so port attachments meet the tile border cleanly)
    val edgeStrokeW = max(1.2f, 2f * density)
    val edgeStroke = Stroke(width = edgeStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val edgeMargin = 20f * density
    for (pe in layout.edges) {
        val pts = pe.points
        if (pts.size < 2) continue
        if (!polylineIntersectsViewport(pts, viewport, edgeMargin)) continue
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                lineTo(pts[i].x, pts[i].y)
            }
        }
        drawPath(path = path, color = theme.edge, style = edgeStroke)

        if (pe.edge.lhsInto) {
            drawPortArrowHead(tip = pts.first(), portDir = pe.edge.lhsDir, color = theme.edge)
        }
        if (pe.edge.rhsInto) {
            drawPortArrowHead(tip = pts.last(), portDir = pe.edge.rhsDir, color = theme.edge)
        }

        val lr = pe.labelRect
        if (lr != null) {
            drawRoundRect(
                color = theme.edgeLabelBackground.copy(alpha = 0.92f),
                topLeft = Offset(lr.x, lr.y),
                size = Size(lr.width, lr.height),
                cornerRadius = CornerRadius(3f * density, 3f * density),
                style = Fill,
            )
        }
    }

    // 3. Junctions
    for (pj in layout.junctions) {
        if (!rectIntersectsViewport(pj.rect, viewport, 8f * density)) continue
        drawCircle(
            color = theme.edge,
            radius = 3.6f * density,
            center = Offset(pj.center.x, pj.center.y),
        )
    }

    // 4. Services
    for (ps in layout.services) {
        if (!rectIntersectsViewport(ps.bounds, viewport, 8f * density)) continue
        val svc = ps.service
        val ir = ps.iconRect
        if (svc.icon != null || !svc.iconText.isNullOrBlank()) {
            drawArchitectureIconTile(ir, svc.icon, iconTileBg, iconFg)
        } else {
            val cr = CornerRadius(5f * density, 5f * density)
            drawRoundRect(
                color = theme.nodeFill,
                topLeft = Offset(ir.x, ir.y),
                size = Size(ir.width, ir.height),
                cornerRadius = cr,
                style = Fill,
            )
            drawRoundRect(
                color = theme.nodeBorder,
                topLeft = Offset(ir.x, ir.y),
                size = Size(ir.width, ir.height),
                cornerRadius = cr,
                style = groupStroke,
            )
        }
    }
}

private fun DrawScope.drawArchitectureIconTile(
    rect: DiagramRect,
    iconName: String?,
    tileBg: Color,
    iconFg: Color = Color.White,
) {
    val cr = CornerRadius(min(6f * density, rect.width * 0.12f), min(6f * density, rect.height * 0.12f))
    drawRoundRect(
        color = tileBg,
        topLeft = Offset(rect.x, rect.y),
        size = Size(rect.width, rect.height),
        cornerRadius = cr,
        style = Fill,
    )
    if (iconName.isNullOrBlank()) return

    val w = rect.width
    val h = rect.height
    val x0 = rect.x
    val y0 = rect.y
    val strokeW = max(1.1f, w * 0.032f)
    val iconStroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val norm = iconName.lowercase()

    when {
        norm.contains("database") || norm.contains("aurora") || norm.contains("db") -> {
            val cx = rect.centerX
            val rx = w * 0.25f
            val ry = h * 0.09f
            val topY = y0 + h * 0.28f
            val botY = y0 + h * 0.72f
            drawOval(
                color = iconFg,
                topLeft = Offset(cx - rx, topY - ry),
                size = Size(rx * 2f, ry * 2f),
                style = iconStroke,
            )
            drawLine(color = iconFg, start = Offset(cx - rx, topY), end = Offset(cx - rx, botY), strokeWidth = strokeW)
            drawLine(color = iconFg, start = Offset(cx + rx, topY), end = Offset(cx + rx, botY), strokeWidth = strokeW)
            for (frac in listOf(0.43f, 0.58f, 0.72f)) {
                val ay = y0 + h * frac
                drawArc(
                    color = iconFg,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - rx, ay - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = iconStroke,
                )
            }
        }
        norm.contains("disk") || norm.contains("glacier") || norm.contains("s3") || norm.contains("storage") -> {
            val bx = x0 + w * 0.25f
            val by = y0 + h * 0.19f
            val bw = w * 0.50f
            val bh = h * 0.62f
            drawRoundRect(
                color = iconFg,
                topLeft = Offset(bx, by),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(strokeW, strokeW),
                style = iconStroke,
            )
            val platterCenter = Offset(rect.centerX, y0 + h * 0.43f)
            drawCircle(color = iconFg, radius = w * 0.17f, center = platterCenter, style = iconStroke)
            drawCircle(color = iconFg, radius = w * 0.045f, center = platterCenter, style = Fill)
            drawLine(
                color = iconFg,
                start = Offset(rect.centerX - w * 0.03f, y0 + h * 0.52f),
                end = Offset(rect.centerX - w * 0.12f, y0 + h * 0.70f),
                strokeWidth = strokeW * 1.3f,
                cap = StrokeCap.Round,
            )
        }
        norm.contains("internet") || norm.contains("globe") || norm.contains("gateway") -> {
            val center = Offset(rect.centerX, rect.centerY)
            val r = min(w, h) * 0.28f
            drawCircle(color = iconFg, radius = r, center = center, style = iconStroke)
            drawLine(color = iconFg, start = Offset(center.x, center.y - r), end = Offset(center.x, center.y + r), strokeWidth = strokeW)
            drawLine(color = iconFg, start = Offset(center.x - r, center.y), end = Offset(center.x + r, center.y), strokeWidth = strokeW)
            drawOval(
                color = iconFg,
                topLeft = Offset(center.x - r * 0.52f, center.y - r),
                size = Size(r * 1.04f, r * 2f),
                style = iconStroke,
            )
        }
        norm.contains("cloud") || norm.contains("lambda") -> {
            val path = Path().apply {
                val left = x0 + w * 0.20f
                val right = x0 + w * 0.80f
                val base = y0 + h * 0.66f
                moveTo(left + w * 0.08f, base)
                lineTo(right - w * 0.08f, base)
                cubicTo(right + w * 0.02f, base, right + w * 0.02f, base - h * 0.18f, right - w * 0.08f, base - h * 0.20f)
                cubicTo(right - w * 0.10f, y0 + h * 0.28f, x0 + w * 0.44f, y0 + h * 0.26f, x0 + w * 0.38f, y0 + h * 0.42f)
                cubicTo(left, y0 + h * 0.42f, left - w * 0.02f, base, left + w * 0.08f, base)
                close()
            }
            drawPath(path = path, color = iconFg, style = iconStroke)
        }
        else -> {
            // Default `server` / compute rack icon
            val bx = x0 + w * 0.22f
            val by = y0 + h * 0.22f
            val bw = w * 0.56f
            val bh = h * 0.56f
            drawRoundRect(
                color = iconFg,
                topLeft = Offset(bx, by),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(strokeW, strokeW),
                style = iconStroke,
            )
            val y1 = by + bh / 3f
            val y2 = by + bh * 2f / 3f
            drawLine(color = iconFg, start = Offset(bx, y1), end = Offset(bx + bw, y1), strokeWidth = strokeW)
            drawLine(color = iconFg, start = Offset(bx, y2), end = Offset(bx + bw, y2), strokeWidth = strokeW)
            for (slotY in listOf(by + bh / 6f, by + bh / 2f, by + bh * 5f / 6f)) {
                drawCircle(color = iconFg, radius = strokeW * 0.8f, center = Offset(bx + bw * 0.16f, slotY))
                drawCircle(color = iconFg, radius = strokeW * 0.8f, center = Offset(bx + bw * 0.28f, slotY))
                drawLine(
                    color = iconFg,
                    start = Offset(bx + bw * 0.55f, slotY),
                    end = Offset(bx + bw * 0.84f, slotY),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun DrawScope.drawPortArrowHead(
    tip: DiagramPoint,
    portDir: ArchitecturePortDir,
    color: Color,
) {
    val sz = 9f * density
    val half = sz * 0.55f
    val path = Path().apply {
        when (portDir) {
            // Arrow pointing INTO port `L` (pointing right -> into left side of node)
            ArchitecturePortDir.L -> {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - sz, tip.y - half)
                lineTo(tip.x - sz, tip.y + half)
                close()
            }
            // Arrow pointing INTO port `R` (pointing left <- into right side of node)
            ArchitecturePortDir.R -> {
                moveTo(tip.x, tip.y)
                lineTo(tip.x + sz, tip.y - half)
                lineTo(tip.x + sz, tip.y + half)
                close()
            }
            // Arrow pointing INTO port `T` (pointing down v into top side of node)
            ArchitecturePortDir.T -> {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - half, tip.y - sz)
                lineTo(tip.x + half, tip.y - sz)
                close()
            }
            // Arrow pointing INTO port `B` (pointing up ^ into bottom side of node)
            ArchitecturePortDir.B -> {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - half, tip.y + sz)
                lineTo(tip.x + half, tip.y + sz)
                close()
            }
        }
    }
    drawPath(path = path, color = color, style = Fill)
}
