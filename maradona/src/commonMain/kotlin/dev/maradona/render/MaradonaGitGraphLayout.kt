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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.GitGraphDiagramLayout
import dev.maradona.layout.GitGraphLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.layout.PositionedGitCommit
import dev.maradona.model.GitCommitType
import dev.maradona.model.GitGraphDiagram
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class GitGraphLayoutHolder {
    var layout: GitGraphDiagramLayout? = null
    var titleSize: DiagramSize? = null
    var branchSizes: Map<String, DiagramSize> = emptyMap()
    var commitLabelSizes: Map<String, DiagramSize> = emptyMap()
    var tagSizes: Map<String, DiagramSize> = emptyMap()
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
}

private fun gitCommitBounds(pc: PositionedGitCommit): DiagramRect {
    var minX = pc.nodeRect.x
    var minY = pc.nodeRect.y
    var maxX = pc.nodeRect.right
    var maxY = pc.nodeRect.bottom
    pc.commitLabelRect?.let { r ->
        minX = min(minX, r.x)
        minY = min(minY, r.y)
        maxX = max(maxX, r.right)
        maxY = max(maxY, r.bottom)
    }
    pc.tagRect?.let { r ->
        minX = min(minX, r.x)
        minY = min(minY, r.y)
        maxX = max(maxX, r.right)
        maxY = max(maxY, r.bottom)
    }
    return DiagramRect(minX, minY, maxX - minX, maxY - minY)
}

/**
 * Custom Compose [Layout] for Mermaid `gitGraph` diagrams.
 *
 * Branch pills, commit labels, tag badges, and the optional chart title are real
 * [BasicText] composables; branch tracks, commit curves, and commit markers
 * (`NORMAL`, `REVERSE`, `HIGHLIGHT`, merge rings, cherry-picks) are drawn on the underlay canvas.
 */
@Composable
fun MaradonaGitGraphLayout(
    diagram: GitGraphDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (GitGraphDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { GitGraphLayoutHolder() }
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

    val allBranchNames = remember(diagram) {
        (diagram.branches.map { it.name } + diagram.commits.map { it.branch }).distinct()
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
            computePremeasuredGitGraphLayout(
                diagram = diagram,
                allBranchNames = allBranchNames,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.titleSize = pre.titleSize
                holder.branchSizes = pre.branchSizes
                holder.commitLabelSizes = pre.commitLabelSizes
                holder.tagSizes = pre.tagSizes
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

    val positionedBranchByName = remember(precomputedLayout) {
        precomputedLayout.branches.associateBy { it.branch.name }
    }
    val positionedCommitById = remember(precomputedLayout) {
        precomputedLayout.commits.associateBy { it.commit.id }
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
                    "Git graph. ${allBranchNames.size} branches, ${diagram.commits.size} commits."
                }
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (c in diagram.commits) {
                            val pc = positionedCommitById[c.id] ?: continue
                            if (rectIntersectsViewport(gitCommitBounds(pc), vp, cullMargin)) continue
                            if (onNodeClick != null) {
                                add(MaradonaNodeClickEvent(nodeId = c.id, label = c.id))
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
                holder.layout?.let { drawGitGraphUnderlay(it, theme, holder.visibleViewport) }
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
            if (diagram.showBranches) {
                for (bName in allBranchNames) {
                    if (activeCullViewport != null) {
                        val lr = positionedBranchByName[bName]?.labelRect
                        if (lr != null && !rectIntersectsViewport(lr, activeCullViewport, cullMargin)) continue
                    }
                    BasicText(
                        text = bName,
                        modifier = Modifier.layoutId("branch:$bName"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
            for ((cIdx, c) in diagram.commits.withIndex()) {
                val pc = positionedCommitById[c.id]
                if (activeCullViewport != null && pc != null) {
                    if (!rectIntersectsViewport(gitCommitBounds(pc), activeCullViewport, cullMargin)) continue
                }
                if (GitGraphLayout.shouldShowCommitLabel(diagram, c)) {
                    BasicText(
                        text = c.id,
                        modifier = Modifier.layoutId("clbl:${c.id}"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(10.5f),
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                if (!c.tag.isNullOrBlank()) {
                    BasicText(
                        text = c.tag,
                        modifier = Modifier.layoutId("ctag:${c.id}"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(10.5f),
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                val tagSuffix = c.tag?.let { ", tag $it" }.orEmpty()
                val baseDesc = "Commit ${c.id} on ${c.branch}$tagSuffix"
                val clickEvent = if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = c.id, label = c.id) else null
                Box(
                    modifier = Modifier
                        .layoutId("cbox:${c.id}")
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = cIdx,
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
        val branchPlaceables = HashMap<String, Placeable>()
        val commitLblPlaceables = HashMap<String, Placeable>()
        val tagPlaceables = HashMap<String, Placeable>()
        val commitBoxMeasurables = HashMap<String, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("branch:") -> branchPlaceables[id.removePrefix("branch:")] = m.measure(unbounded)
                id.startsWith("clbl:") -> commitLblPlaceables[id.removePrefix("clbl:")] = m.measure(unbounded)
                id.startsWith("ctag:") -> tagPlaceables[id.removePrefix("ctag:")] = m.measure(unbounded)
                id.startsWith("cbox:") -> commitBoxMeasurables[id.removePrefix("cbox:")] = m
            }
        }

        val resolvedTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize
        val resolvedBranchSizes = HashMap<String, DiagramSize>(allBranchNames.size)
        for (bName in allBranchNames) {
            val pl = branchPlaceables[bName]
            if (pl != null) {
                resolvedBranchSizes[bName] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
            } else {
                holder.branchSizes[bName]?.let { resolvedBranchSizes[bName] = it }
            }
        }
        val resolvedCommitLblSizes = HashMap<String, DiagramSize>(diagram.commits.size)
        val resolvedTagSizes = HashMap<String, DiagramSize>(diagram.commits.size)
        for (c in diagram.commits) {
            val cPl = commitLblPlaceables[c.id]
            if (cPl != null) {
                resolvedCommitLblSizes[c.id] = DiagramSize(cPl.width.toFloat(), cPl.height.toFloat())
            } else {
                holder.commitLabelSizes[c.id]?.let { resolvedCommitLblSizes[c.id] = it }
            }
            val tPl = tagPlaceables[c.id]
            if (tPl != null) {
                resolvedTagSizes[c.id] = DiagramSize(tPl.width.toFloat(), tPl.height.toFloat())
            } else {
                holder.tagSizes[c.id]?.let { resolvedTagSizes[c.id] = it }
            }
        }

        if (activeCullViewport == null) {
            holder.titleSize = resolvedTitleSize
            holder.branchSizes = resolvedBranchSizes
            holder.commitLabelSizes = resolvedCommitLblSizes
            holder.tagSizes = resolvedTagSizes
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = GitGraphLayout.layout(
            diagram = diagram,
            branchLabelSize = { bName ->
                resolvedBranchSizes[bName] ?: GitGraphLayout.defaultBadgeSize(bName, renderDensity)
            },
            commitLabelSize = { c ->
                resolvedCommitLblSizes[c.id] ?: GitGraphLayout.defaultCommitLabelSize(c.id, renderDensity)
            },
            tagLabelSize = { c ->
                resolvedTagSizes[c.id] ?: GitGraphLayout.defaultBadgeSize(c.tag.orEmpty(), renderDensity)
            },
            titleSize = resolvedTitleSize,
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val commitBoxPlaceables = HashMap<String, Placeable>()
        for (pc in layout.commits) {
            val m = commitBoxMeasurables[pc.commit.id] ?: continue
            val w = pc.nodeRect.width.roundToInt().coerceAtLeast(1)
            val h = pc.nodeRect.height.roundToInt().coerceAtLeast(1)
            commitBoxPlaceables[pc.commit.id] = m.measure(Constraints.fixed(w, h))
        }

        val edgeMargin = 20f * renderDensity
        val drawnEdges = if (activeCullViewport == null) {
            layout.edges.size
        } else {
            layout.edges.count { polylineIntersectsViewport(it.points, activeCullViewport, edgeMargin) }
        }
        val drawnBranches = if (activeCullViewport == null) {
            layout.branches.size
        } else {
            layout.branches.count { pb ->
                segmentIntersectsViewport(
                    pb.lineStart.x,
                    pb.lineStart.y,
                    pb.lineEnd.x,
                    pb.lineEnd.y,
                    activeCullViewport,
                    edgeMargin,
                ) || (pb.labelRect != null && rectIntersectsViewport(pb.labelRect, activeCullViewport, cullMargin))
            }
        }
        val totalLabels = layout.commits.count { it.commitLabelRect != null } +
            layout.commits.count { it.tagRect != null }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = commitBoxPlaceables.size,
                totalNodes = layout.commits.size,
                composedEdgeLabels = commitLblPlaceables.size + tagPlaceables.size,
                totalEdgeLabels = totalLabels,
                drawnEdges = drawnEdges,
                totalEdges = layout.edges.size,
                drawnSubgraphs = drawnBranches,
                totalSubgraphs = layout.branches.size,
            ),
        )

        val wPx = layout.width.roundToInt().coerceAtLeast(constraints.minWidth)
        val hPx = layout.height.roundToInt().coerceAtLeast(constraints.minHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }
            for (pb in layout.branches) {
                val r = pb.labelRect ?: continue
                val pl = branchPlaceables[pb.branch.name] ?: continue
                pl.place(
                    (r.centerX - pl.width / 2f).roundToInt(),
                    (r.centerY - pl.height / 2f).roundToInt(),
                )
            }
            for (pc in layout.commits) {
                commitBoxPlaceables[pc.commit.id]?.place(
                    pc.nodeRect.x.roundToInt(),
                    pc.nodeRect.y.roundToInt(),
                )
                val clRect = pc.commitLabelRect
                val clPl = commitLblPlaceables[pc.commit.id]
                if (clRect != null && clPl != null) {
                    val px = (clRect.centerX - clPl.width / 2f).roundToInt()
                    val py = (clRect.centerY - clPl.height / 2f).roundToInt()
                    if (pc.commitLabelAngleDeg != 0f) {
                        clPl.placeWithLayer(px, py) {
                            rotationZ = pc.commitLabelAngleDeg
                        }
                    } else {
                        clPl.place(px, py)
                    }
                }
                val tgRect = pc.tagRect
                val tgPl = tagPlaceables[pc.commit.id]
                if (tgRect != null && tgPl != null) {
                    tgPl.place(
                        (tgRect.centerX - tgPl.width / 2f).roundToInt(),
                        (tgRect.centerY - tgPl.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

private data class PremeasuredGitGraphResult(
    val layout: GitGraphDiagramLayout,
    val titleSize: DiagramSize?,
    val branchSizes: Map<String, DiagramSize>,
    val commitLabelSizes: Map<String, DiagramSize>,
    val tagSizes: Map<String, DiagramSize>,
)

private fun computePremeasuredGitGraphLayout(
    diagram: GitGraphDiagram,
    allBranchNames: List<String>,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredGitGraphResult {
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

    val branchStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        fontWeight = FontWeight.SemiBold,
    )
    val branchSizes = HashMap<String, DiagramSize>(allBranchNames.size)
    if (diagram.showBranches) {
        for (bName in allBranchNames) {
            branchSizes[bName] = measurePlainTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = bName,
                style = branchStyle,
            )
        }
    }

    val commitLblStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(10.5f),
        fontWeight = FontWeight.Medium,
    )
    val tagStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.scaledSp(10.5f),
        fontWeight = FontWeight.SemiBold,
    )
    val commitLabelSizes = HashMap<String, DiagramSize>(diagram.commits.size)
    val tagSizes = HashMap<String, DiagramSize>(diagram.commits.size)
    for (c in diagram.commits) {
        if (GitGraphLayout.shouldShowCommitLabel(diagram, c)) {
            commitLabelSizes[c.id] = measurePlainTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = c.id,
                style = commitLblStyle,
            )
        }
        val tag = c.tag
        if (!tag.isNullOrBlank()) {
            tagSizes[c.id] = measurePlainTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = tag,
                style = tagStyle,
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
    val layout = GitGraphLayout.layout(
        diagram = diagram,
        branchLabelSize = { bName -> branchSizes[bName] ?: GitGraphLayout.defaultBadgeSize(bName, renderDensity) },
        commitLabelSize = { c -> commitLabelSizes[c.id] ?: GitGraphLayout.defaultCommitLabelSize(c.id, renderDensity) },
        tagLabelSize = { c -> tagSizes[c.id] ?: GitGraphLayout.defaultBadgeSize(c.tag.orEmpty(), renderDensity) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredGitGraphResult(
        layout = layout,
        titleSize = titleSize,
        branchSizes = branchSizes,
        commitLabelSizes = commitLabelSizes,
        tagSizes = tagSizes,
    )
}

private fun DrawScope.drawGitGraphUnderlay(
    layout: GitGraphDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val edgeMargin = 20f * unit
    val nodeMargin = 8f * unit
    val guideDash = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)
    val cherryDash = PathEffect.dashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f)

    // 1. Branch lane guide lines & branch pills.
    for (pb in layout.branches) {
        val fill = theme.seriesFill(pb.laneIndex)
        val stroke = theme.seriesColor(pb.laneIndex)
        if (segmentIntersectsViewport(pb.lineStart.x, pb.lineStart.y, pb.lineEnd.x, pb.lineEnd.y, viewport, edgeMargin)) {
            drawLine(
                color = stroke.copy(alpha = 0.28f),
                start = Offset(pb.lineStart.x, pb.lineStart.y),
                end = Offset(pb.lineEnd.x, pb.lineEnd.y),
                strokeWidth = 1.4f * unit,
                pathEffect = guideDash,
            )
        }
        val r = pb.labelRect
        if (r != null && rectIntersectsViewport(r, viewport, nodeMargin)) {
            val cr = CornerRadius(6f * unit, 6f * unit)
            drawRoundRect(
                color = fill,
                topLeft = Offset(r.x, r.y),
                size = Size(r.width, r.height),
                cornerRadius = cr,
            )
            drawRoundRect(
                color = stroke,
                topLeft = Offset(r.x, r.y),
                size = Size(r.width, r.height),
                cornerRadius = cr,
                style = Stroke(width = 1.4f * unit),
            )
        }
    }

    // 2. Commit edges (same-branch lines and cross-branch S-curves).
    for (edge in layout.edges) {
        val stroke = theme.seriesColor(edge.branchLaneIndex)
        val pts = edge.points
        if (pts.size < 2) continue
        if (!polylineIntersectsViewport(pts, viewport, edgeMargin)) continue
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            if (pts.size == 4) {
                cubicTo(pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
            } else {
                for (i in 1 until pts.size) {
                    lineTo(pts[i].x, pts[i].y)
                }
            }
        }
        drawPath(
            path = path,
            color = stroke,
            style = Stroke(
                width = 2.6f * unit,
                cap = StrokeCap.Round,
                pathEffect = if (edge.isCherryPick) cherryDash else null,
            ),
        )
    }

    // 3. Commit markers, commit label pills, and tag badges.
    val tagFill = theme.warningFill
    val tagBorder = theme.warningBorder
    val lblFill = theme.nodeFill

    for (pc in layout.commits) {
        if (!rectIntersectsViewport(gitCommitBounds(pc), viewport, nodeMargin)) continue
        val branchFill = theme.seriesFill(pc.laneIndex)
        val branchStroke = theme.seriesColor(pc.laneIndex)
        val nr = pc.nodeRect
        val center = Offset(pc.center.x, pc.center.y)
        val radius = nr.width / 2f

        when (pc.commit.type) {
            GitCommitType.Highlight -> {
                drawRect(
                    color = branchFill,
                    topLeft = Offset(nr.x, nr.y),
                    size = Size(nr.width, nr.height),
                )
                drawRect(
                    color = branchStroke,
                    topLeft = Offset(nr.x, nr.y),
                    size = Size(nr.width, nr.height),
                    style = Stroke(width = 2.2f * unit),
                )
                val inset = 3.2f * unit
                drawRect(
                    color = branchStroke,
                    topLeft = Offset(nr.x + inset, nr.y + inset),
                    size = Size(nr.width - inset * 2f, nr.height - inset * 2f),
                )
            }
            GitCommitType.Reverse -> {
                drawCircle(color = theme.background, radius = radius, center = center)
                drawCircle(
                    color = branchStroke,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.2f * unit),
                )
                val d = radius * 0.52f
                drawLine(
                    color = branchStroke,
                    start = Offset(center.x - d, center.y - d),
                    end = Offset(center.x + d, center.y + d),
                    strokeWidth = 2f * unit,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = branchStroke,
                    start = Offset(center.x - d, center.y + d),
                    end = Offset(center.x + d, center.y - d),
                    strokeWidth = 2f * unit,
                    cap = StrokeCap.Round,
                )
            }
            GitCommitType.Normal -> {
                if (pc.commit.isMerge) {
                    drawCircle(color = theme.background, radius = radius, center = center)
                    drawCircle(
                        color = branchStroke,
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.2f * unit),
                    )
                    drawCircle(color = branchStroke, radius = radius * 0.48f, center = center)
                } else {
                    drawCircle(color = branchStroke, radius = radius, center = center)
                    drawCircle(
                        color = theme.background,
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.6f * unit),
                    )
                }
            }
        }

        val clRect = pc.commitLabelRect
        if (clRect != null) {
            val pillW = pc.commitLabelPillSize?.width ?: clRect.width
            val pillH = pc.commitLabelPillSize?.height ?: clRect.height
            val cr = CornerRadius(4f * unit, 4f * unit)
            withTransform({
                if (pc.commitLabelAngleDeg != 0f) {
                    rotate(degrees = pc.commitLabelAngleDeg, pivot = Offset(clRect.centerX, clRect.centerY))
                }
            }) {
                val topLeft = Offset(clRect.centerX - pillW / 2f, clRect.centerY - pillH / 2f)
                val sz = Size(pillW, pillH)
                drawRoundRect(
                    color = lblFill.copy(alpha = 0.92f),
                    topLeft = topLeft,
                    size = sz,
                    cornerRadius = cr,
                )
                drawRoundRect(
                    color = theme.subgraphBorder.copy(alpha = 0.65f),
                    topLeft = topLeft,
                    size = sz,
                    cornerRadius = cr,
                    style = Stroke(width = 1f * unit),
                )
            }
        }

        val tgRect = pc.tagRect
        if (tgRect != null) {
            val cr = CornerRadius(4f * unit, 4f * unit)
            drawRoundRect(
                color = tagFill,
                topLeft = Offset(tgRect.x, tgRect.y),
                size = Size(tgRect.width, tgRect.height),
                cornerRadius = cr,
            )
            drawRoundRect(
                color = tagBorder,
                topLeft = Offset(tgRect.x, tgRect.y),
                size = Size(tgRect.width, tgRect.height),
                cornerRadius = cr,
                style = Stroke(width = 1.3f * unit),
            )
        }
    }
}

internal fun measureGitGraphLayoutForExport(
    diagram: GitGraphDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): GitGraphDiagramLayout {
    val allBranchNames = (diagram.branches.map { it.name } + diagram.commits.map { it.branch }).distinct()
    return computePremeasuredGitGraphLayout(
        diagram = diagram,
        allBranchNames = allBranchNames,
        theme = theme,
        layoutOptions = layoutOptions,
        densityObj = densityObj,
        textMeasurer = textMeasurer,
    ).layout
}

