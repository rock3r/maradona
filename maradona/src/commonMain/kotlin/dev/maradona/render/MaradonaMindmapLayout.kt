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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.MindmapDiagramLayout
import dev.maradona.layout.MindmapLayout
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.MindmapDiagram
import dev.maradona.model.MindmapNode
import dev.maradona.model.MindmapShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private class MindmapLayoutHolder {
    var layout: MindmapDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var nodeSizes: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun computeMindmapNodeSize(
    n: MindmapNode,
    labelSize: DiagramSize,
    renderDensity: Float,
): DiagramSize {
    val padX = when (n.shape) {
        MindmapShape.Circle, MindmapShape.Bang -> 18f * renderDensity
        MindmapShape.Hexagon, MindmapShape.Cloud -> 20f * renderDensity
        else -> 14f * renderDensity
    }
    val padY = when (n.shape) {
        MindmapShape.Circle, MindmapShape.Bang -> 14f * renderDensity
        else -> 9f * renderDensity
    }
    val textW = labelSize.width
    val textH = labelSize.height
    val w = max(64f * renderDensity, textW + padX * 2f)
    val h = max(30f * renderDensity, textH + padY * 2f)
    return when (n.shape) {
        MindmapShape.Circle -> {
            val d = max(max(w, h), kotlin.math.hypot(textW, textH) + padY * 2f)
            DiagramSize(d, d)
        }
        MindmapShape.Bang, MindmapShape.Cloud -> {
            val rw = textW + 20f * renderDensity
            val rh = textH + 12f * renderDensity
            DiagramSize(
                width = max(w * 1.22f, rw * 1.78f),
                height = max(h * 1.25f, rh * 1.82f),
            )
        }
        else -> DiagramSize(w, h)
    }
}

/**
 * Custom Compose [Layout] for Mermaid `mindmap` diagrams.
 *
 * Node labels are real Composables with semantics; branch S-curves and node shapes
 * (`Default`, `Square`, `Rounded`, `Circle`, `Bang`, `Cloud`, `Hexagon`) are drawn
 * on the underlay canvas with branch-coordinated colors.
 */
@Composable
fun MaradonaMindmapLayout(
    diagram: MindmapDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(nodeGap = 18f, layerGap = 54f),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (MindmapDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { MindmapLayoutHolder() }
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

    val childSummaries = remember(diagram) {
        val labelById = diagram.nodes.associate { it.id to it.label }
        val childrenByParent = diagram.nodes.groupBy { it.parentId }
        diagram.nodes.associate { n ->
            val parentLabel = n.parentId?.let { labelById[it] }
            val kids = childrenByParent[n.id].orEmpty().map { it.label }
            n.id to buildList {
                if (parentLabel != null) add("under $parentLabel")
                if (kids.isNotEmpty()) add("branches to ${kids.joinToString(", ")}")
            }.joinToString("; ")
        }
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
            computePremeasuredMindmapLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.nodeSizes = pre.nodeSizes
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

    val positionedNodeById = remember(precomputedLayout) {
        precomputedLayout.nodes.associateBy { it.node.id }
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
                    "Mindmap diagram. ${diagram.nodes.size} nodes."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (n in diagram.nodes) {
                            val pn = positionedNodeById[n.id] ?: continue
                            if (rectIntersectsViewport(pn.rect, vp, cullMargin)) continue
                            if (onNodeClick != null) {
                                add(MaradonaNodeClickEvent(nodeId = n.id, label = n.label))
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
                holder.layout?.let { drawMindmapUnderlay(it, theme, holder.visibleViewport) }
            },
        content = {
            if (diagram.title != null) {
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
            for ((nIdx, n) in diagram.nodes.withIndex()) {
                if (activeCullViewport != null) {
                    val pn = positionedNodeById[n.id]
                    if (pn != null && !rectIntersectsViewport(pn.rect, activeCullViewport, cullMargin)) continue
                }
                val isRoot = n.parentId == null
                val textColor = parseCssColor(n.style?.textColor) ?: theme.nodeText
                val isLarge = "large" in n.classes
                val baseFontSize = when {
                    isRoot -> theme.scaledSp(15f)
                    isLarge -> theme.scaledSp(14.5f)
                    else -> theme.nodeFontSize
                }
                val displayAnnotated = if (!n.icon.isNullOrBlank()) {
                    AnnotatedString("${resolveMermaidIconGlyph(n.icon)} ") + annotatedLabel(n.label, theme.monospaceFontFamily)
                } else {
                    annotatedLabel(n.label, theme.monospaceFontFamily)
                }
                BasicText(
                    text = displayAnnotated,
                    modifier = Modifier.layoutId("mmlbl:${n.id}"),
                    style = TextStyle(
                        color = textColor,
                        fontFamily = theme.fontFamily,
                        fontSize = baseFontSize,
                        fontWeight = if (isRoot || "urgent" in n.classes) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
                val summary = childSummaries[n.id]
                val baseDesc = if (summary.isNullOrBlank()) "Mindmap node ${n.label}" else "Mindmap node ${n.label}. $summary"
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = n.id, label = n.label) else null
                Box(
                    modifier = Modifier
                        .layoutId("mmbox:${n.id}")
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = nIdx,
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
        val lblPlaceables = HashMap<String, Placeable>()
        val boxMeasurables = HashMap<String, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("mmlbl:") -> lblPlaceables[id.removePrefix("mmlbl:")] = m.measure(unbounded)
                id.startsWith("mmbox:") -> boxMeasurables[id.removePrefix("mmbox:")] = m
            }
        }

        val sizesById = diagram.nodes.associate { n ->
            val pl = lblPlaceables[n.id]
            val sz = if (pl != null) {
                computeMindmapNodeSize(
                    n = n,
                    labelSize = DiagramSize(pl.width.toFloat(), pl.height.toFloat()),
                    renderDensity = renderDensity,
                )
            } else {
                holder.nodeSizes[n.id] ?: MindmapLayout.defaultNodeSize(n, renderDensity)
            }
            n.id to sz
        }
        val resolvedTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize

        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.nodeSizes = sizesById
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                padding = padding * renderDensity,
                subgraphPaddingX = subgraphPaddingX * renderDensity,
                subgraphPaddingY = subgraphPaddingY * renderDensity,
                density = renderDensity,
            )
        }

        val result = MindmapLayout.layout(
            diagram = diagram,
            nodeSize = { sizesById.getValue(it.id) },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        holder.layout = result
        onLayout(result)

        val boxPlaceables = HashMap<String, Placeable>()
        for (pn in result.nodes) {
            boxMeasurables[pn.node.id]?.let { m ->
                boxPlaceables[pn.node.id] = m.measure(
                    Constraints.fixed(pn.rect.width.roundToInt(), pn.rect.height.roundToInt()),
                )
            }
        }

        val edgeMargin = 20f * renderDensity
        val drawnEdges = if (activeCullViewport == null) {
            result.edges.size
        } else {
            result.edges.count { polylineIntersectsViewport(it.points, activeCullViewport, edgeMargin) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = boxPlaceables.size,
                totalNodes = diagram.nodes.size,
                composedEdgeLabels = 0,
                totalEdgeLabels = 0,
                drawnEdges = drawnEdges,
                totalEdges = result.edges.size,
                drawnSubgraphs = 0,
                totalSubgraphs = 0,
            ),
        )

        layout(result.width.roundToInt(), result.height.roundToInt()) {
            result.titleRect?.let { r ->
                titlePlaceable?.place(r.x.roundToInt(), r.y.roundToInt())
            }
            for (pn in result.nodes) {
                val id = pn.node.id
                val r = pn.rect
                boxPlaceables[id]?.place(r.x.roundToInt(), r.y.roundToInt())
                lblPlaceables[id]?.let { pl ->
                    pl.place(
                        (r.centerX - pl.width / 2f).roundToInt(),
                        (r.centerY - pl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private data class PremeasuredMindmapResult(
    val layout: MindmapDiagramLayout,
    val titleSize: DiagramSize?,
    val nodeSizes: Map<String, DiagramSize>,
)

private fun computePremeasuredMindmapLayout(
    diagram: MindmapDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredMindmapResult {
    val renderDensity = densityObj.density
    val titleSize = diagram.title?.let { title ->
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

    val nodeSizes = HashMap<String, DiagramSize>(diagram.nodes.size)
    for (n in diagram.nodes) {
        val isRoot = n.parentId == null
        val textColor = parseCssColor(n.style?.textColor) ?: theme.nodeText
        val isLarge = "large" in n.classes
        val baseFontSize = when {
            isRoot -> theme.scaledSp(15f)
            isLarge -> theme.scaledSp(14.5f)
            else -> theme.nodeFontSize
        }
        val displayAnnotated = if (!n.icon.isNullOrBlank()) {
            AnnotatedString("${resolveMermaidIconGlyph(n.icon)} ") + annotatedLabel(n.label, theme.monospaceFontFamily)
        } else {
            annotatedLabel(n.label, theme.monospaceFontFamily)
        }
        val lblSize = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = displayAnnotated,
            style = TextStyle(
                color = textColor,
                fontFamily = theme.fontFamily,
                fontSize = baseFontSize,
                fontWeight = if (isRoot || "urgent" in n.classes) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
        nodeSizes[n.id] = computeMindmapNodeSize(n, lblSize, renderDensity)
    }

    val scaledOptions = with(layoutOptions) {
        copy(
            nodeGap = nodeGap * renderDensity,
            layerGap = layerGap * renderDensity,
            padding = padding * renderDensity,
            subgraphPaddingX = subgraphPaddingX * renderDensity,
            subgraphPaddingY = subgraphPaddingY * renderDensity,
            density = renderDensity,
        )
    }
    val layout = MindmapLayout.layout(
        diagram = diagram,
        nodeSize = { nodeSizes.getValue(it.id) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredMindmapResult(
        layout = layout,
        titleSize = titleSize,
        nodeSizes = nodeSizes,
    )
}

private fun DrawScope.drawMindmapUnderlay(
    layout: MindmapDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val edgeMargin = 20f * unit
    val nodeMargin = 8f * unit

    fun branchColor(branchIndex: Int): Color {
        if (branchIndex < 0) return theme.nodeBorder
        return theme.seriesColor(branchIndex)
    }

    // 1. Branch S-curves behind nodes.
    for (edge in layout.edges) {
        val pts = edge.points
        if (pts.size < 2) continue
        if (!polylineIntersectsViewport(pts, viewport, edgeMargin)) continue
        val accent = branchColor(edge.branchIndex)
        val strokeW = max(1.5f, 3.2f - edge.depth * 0.45f) * unit
        val path = basisSpline(pts)
        drawPath(
            path = path,
            color = accent,
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
        )
    }

    // 2. Mindmap node shapes.
    for (pn in layout.nodes) {
        val r = pn.rect
        if (!rectIntersectsViewport(r, viewport, nodeMargin)) continue
        val n = pn.node
        val accent = branchColor(pn.branchIndex)
        val isUrgent = "urgent" in n.classes
        val border = parseCssColor(n.style?.stroke)
            ?: if (isUrgent) theme.criticalBorder else accent
        val fill = parseCssColor(n.style?.fill)
            ?: when {
                isUrgent -> theme.criticalFill
                pn.depth == 0 -> theme.subgraphFill
                else -> theme.nodeFill
            }
        val strokeW = (n.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull()
            ?: if (pn.depth == 0 || isUrgent) 2f else 1.35f) * unit

        when (n.shape) {
            MindmapShape.Square -> {
                drawRect(color = fill, topLeft = Offset(r.x, r.y), size = Size(r.width, r.height))
                drawRect(color = border, topLeft = Offset(r.x, r.y), size = Size(r.width, r.height), style = Stroke(width = strokeW))
            }
            MindmapShape.Rounded, MindmapShape.Default -> {
                val radius = if (n.shape == MindmapShape.Default) 10f * unit else 7f * unit
                val cr = CornerRadius(radius, radius)
                drawRoundRect(color = fill, topLeft = Offset(r.x, r.y), size = Size(r.width, r.height), cornerRadius = cr)
                drawRoundRect(color = border, topLeft = Offset(r.x, r.y), size = Size(r.width, r.height), cornerRadius = cr, style = Stroke(width = strokeW))
            }
            MindmapShape.Circle -> {
                val rad = min(r.width, r.height) / 2f
                val center = Offset(r.centerX, r.centerY)
                drawCircle(color = fill, radius = rad, center = center)
                drawCircle(color = border, radius = rad, center = center, style = Stroke(width = strokeW))
            }
            MindmapShape.Hexagon -> {
                val inset = min(16f * unit, r.width * 0.22f)
                val path = Path().apply {
                    moveTo(r.x + inset, r.y)
                    lineTo(r.right - inset, r.y)
                    lineTo(r.right, r.centerY)
                    lineTo(r.right - inset, r.bottom)
                    lineTo(r.x + inset, r.bottom)
                    lineTo(r.x, r.centerY)
                    close()
                }
                drawPath(path = path, color = fill)
                drawPath(path = path, color = border, style = Stroke(width = strokeW))
            }
            MindmapShape.Cloud -> {
                val lobes = 10
                val cx = r.centerX
                val cy = r.centerY
                val outerRx = r.width / 2f
                val outerRy = r.height / 2f
                val innerRx = outerRx * 0.85f
                val innerRy = outerRy * 0.82f
                val halfStepCos = cos(PI / lobes).toFloat()
                val ctrlRx = 2f * outerRx - innerRx * halfStepCos
                val ctrlRy = 2f * outerRy - innerRy * halfStepCos
                val path = Path().apply {
                    val startAngle = -PI / lobes
                    moveTo(
                        cx + (innerRx * cos(startAngle)).toFloat(),
                        cy + (innerRy * sin(startAngle)).toFloat(),
                    )
                    for (i in 0 until lobes) {
                        val aMid = (2.0 * PI * i) / lobes
                        val aEnd = (2.0 * PI * (i + 0.5)) / lobes
                        val ctrlX = cx + (ctrlRx * cos(aMid)).toFloat()
                        val ctrlY = cy + (ctrlRy * sin(aMid)).toFloat()
                        val endX = cx + (innerRx * cos(aEnd)).toFloat()
                        val endY = cy + (innerRy * sin(aEnd)).toFloat()
                        quadraticTo(ctrlX, ctrlY, endX, endY)
                    }
                    close()
                }
                drawPath(path = path, color = fill)
                drawPath(path = path, color = border, style = Stroke(width = strokeW))
            }
            MindmapShape.Bang -> {
                val spikes = 12
                val outerRx = r.width / 2f
                val outerRy = r.height / 2f
                val innerRx = outerRx * 0.84f
                val innerRy = outerRy * 0.84f
                val path = Path().apply {
                    val totalPts = spikes * 2
                    for (i in 0 until totalPts) {
                        val angle = (i.toDouble() / totalPts) * 2.0 * PI
                        val rx = if (i % 2 == 0) outerRx else innerRx
                        val ry = if (i % 2 == 0) outerRy else innerRy
                        val px = r.centerX + (cos(angle) * rx).toFloat()
                        val py = r.centerY + (sin(angle) * ry).toFloat()
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
                drawPath(path = path, color = fill)
                drawPath(path = path, color = border, style = Stroke(width = strokeW))
            }
        }
    }
}

internal fun measureMindmapLayoutForExport(
    diagram: MindmapDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): MindmapDiagramLayout = computePremeasuredMindmapLayout(
    diagram = diagram,
    theme = theme,
    layoutOptions = layoutOptions,
    densityObj = densityObj,
    textMeasurer = textMeasurer,
).layout

