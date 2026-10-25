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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.GitGraphDiagramLayout
import dev.maradona.layout.GitGraphLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.model.GitCommitType
import dev.maradona.model.GitGraphDiagram
import kotlin.math.roundToInt

private class GitGraphLayoutHolder {
    var layout: GitGraphDiagramLayout? = null
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
    onLayout: (GitGraphDiagramLayout) -> Unit = {},
) {
    val holder = remember { GitGraphLayoutHolder() }
    val allBranchNames = remember(diagram) {
        (diagram.branches.map { it.name } + diagram.commits.map { it.branch }).distinct()
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
                    "Git graph. ${allBranchNames.size} branches, ${diagram.commits.size} commits."
                }
            }
            .drawBehind {
                holder.layout?.let { drawGitGraphUnderlay(it, theme) }
            },
        content = {
            if (diagram.title != null) {
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
            if (diagram.showBranches) {
                for (bName in allBranchNames) {
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
            for (c in diagram.commits) {
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
                Box(
                    modifier = Modifier
                        .layoutId("cbox:${c.id}")
                        .focusable()
                        .semantics {
                            val tagSuffix = c.tag?.let { ", tag $it" }.orEmpty()
                            contentDescription = "Commit ${c.id} on ${c.branch}$tagSuffix"
                        },
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
                val pl = branchPlaceables[bName]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else GitGraphLayout.defaultBadgeSize(bName, renderDensity)
            },
            commitLabelSize = { c ->
                val pl = commitLblPlaceables[c.id]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else GitGraphLayout.defaultCommitLabelSize(c.id, renderDensity)
            },
            tagLabelSize = { c ->
                val pl = tagPlaceables[c.id]
                if (pl != null) DiagramSize(pl.width.toFloat(), pl.height.toFloat())
                else GitGraphLayout.defaultBadgeSize(c.tag.orEmpty(), renderDensity)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
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

private fun DrawScope.drawGitGraphUnderlay(
    layout: GitGraphDiagramLayout,
    theme: MaradonaTheme,
) {
    val unit = density
    val guideDash = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f)
    val cherryDash = PathEffect.dashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f)

    // 1. Branch lane guide lines & branch pills.
    for (pb in layout.branches) {
        val fill = theme.seriesFill(pb.laneIndex)
        val stroke = theme.seriesColor(pb.laneIndex)
        drawLine(
            color = stroke.copy(alpha = 0.28f),
            start = Offset(pb.lineStart.x, pb.lineStart.y),
            end = Offset(pb.lineEnd.x, pb.lineEnd.y),
            strokeWidth = 1.4f * unit,
            pathEffect = guideDash,
        )
        val r = pb.labelRect
        if (r != null) {
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
