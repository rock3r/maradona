package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.ZenUmlDiagramLayout
import dev.maradona.layout.ZenUmlLayout
import dev.maradona.model.ZenUmlDiagram
import dev.maradona.model.ZenUmlMessageKind
import dev.maradona.model.ZenUmlParticipant
import dev.maradona.model.ZenUmlParticipantStereotype
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class ZenUmlLayoutHolder {
    var layout: ZenUmlDiagramLayout? = null
}

/**
 * Custom Compose [Layout] for Mermaid `zenuml` sequence diagrams.
 */
@Composable
fun MaradonaZenUmlLayout(
    diagram: ZenUmlDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onLayout: (ZenUmlDiagramLayout) -> Unit = {},
) {
    val holder = remember { ZenUmlLayoutHolder() }

    val specs = remember(diagram) { ZenUmlLayout.collectSpecs(diagram) }
    val msgSpecs = specs.first
    val blockSpecs = specs.second
    val effectiveParticipants = remember(diagram.participants) {
        diagram.participants.ifEmpty {
            listOf(ZenUmlParticipant(id = "_STARTER_", label = "Starter"))
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
                    "ZenUML sequence diagram. ${effectiveParticipants.size} participants, ${msgSpecs.size} messages."
                }
            }
            .drawBehind {
                holder.layout?.let { drawZenUmlUnderlay(it, theme) }
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

            for ((gIdx, grp) in diagram.groups.withIndex()) {
                BasicText(
                    text = annotatedLabel(grp.name),
                    modifier = Modifier
                        .layoutId("grp_hdr:$gIdx")
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.scaledSp(11.5f),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }

            for (p in effectiveParticipants) {
                ZenUmlParticipantCard(
                    participant = p,
                    theme = theme,
                    modifier = Modifier
                        .layoutId("p_top:${p.id}")
                        .focusable()
                        .semantics {
                            contentDescription = "Participant ${p.label}"
                        },
                )
                if (diagram.mirrorParticipants) {
                    ZenUmlParticipantCard(
                        participant = p,
                        theme = theme,
                        modifier = Modifier.layoutId("p_bot:${p.id}"),
                    )
                }
            }

            for (mSpec in msgSpecs) {
                if (mSpec.displayText.isNotBlank()) {
                    BasicText(
                        text = annotatedLabel(mSpec.displayText),
                        modifier = Modifier
                            .layoutId("msg_lbl:${mSpec.index}")
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.monospaceFontFamily,
                            fontSize = theme.scaledSp(11.5f),
                            fontWeight = if (mSpec.kind == ZenUmlMessageKind.RETURN) FontWeight.Normal else FontWeight.Medium,
                            fontStyle = if (mSpec.kind == ZenUmlMessageKind.RETURN) FontStyle.Italic else FontStyle.Normal,
                        ),
                    )
                }
                if (!mSpec.commentText.isNullOrBlank()) {
                    BasicText(
                        text = annotatedLabel(mSpec.commentText),
                        modifier = Modifier
                            .layoutId("msg_comm:${mSpec.index}")
                            .widthIn(max = 220.dp)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        style = TextStyle(
                            color = theme.noteText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(10.5f),
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                }
            }

            for (bSpec in blockSpecs) {
                BasicText(
                    text = bSpec.keyword,
                    modifier = Modifier
                        .layoutId("blk_tab:${bSpec.index}")
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.monospaceFontFamily,
                        fontSize = theme.captionFontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (!bSpec.condition.isNullOrBlank()) {
                    BasicText(
                        text = "[${bSpec.condition}]",
                        modifier = Modifier
                            .layoutId("blk_cond:${bSpec.index}")
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.monospaceFontFamily,
                            fontSize = theme.captionFontSize,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                for ((dIdx, divText) in bSpec.dividerLabels.withIndex()) {
                    BasicText(
                        text = "[$divText]",
                        modifier = Modifier
                            .layoutId("blk_div:${bSpec.index}:$dIdx")
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.monospaceFontFamily,
                            fontSize = theme.scaledSp(10.5f),
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val grpHdrMeasurables = HashMap<Int, Measurable>()
        val pTopMeasurables = HashMap<String, Measurable>()
        val pBotMeasurables = HashMap<String, Measurable>()
        val msgLblMeasurables = HashMap<Int, Measurable>()
        val msgCommMeasurables = HashMap<Int, Measurable>()
        val blkTabMeasurables = HashMap<Int, Measurable>()
        val blkCondMeasurables = HashMap<Int, Measurable>()
        val blkDivMeasurables = HashMap<Pair<Int, Int>, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("grp_hdr:") -> {
                    val idx = id.removePrefix("grp_hdr:").toIntOrNull() ?: continue
                    grpHdrMeasurables[idx] = m
                }
                id.startsWith("p_top:") -> pTopMeasurables[id.removePrefix("p_top:")] = m
                id.startsWith("p_bot:") -> pBotMeasurables[id.removePrefix("p_bot:")] = m
                id.startsWith("msg_lbl:") -> {
                    val idx = id.removePrefix("msg_lbl:").toIntOrNull() ?: continue
                    msgLblMeasurables[idx] = m
                }
                id.startsWith("msg_comm:") -> {
                    val idx = id.removePrefix("msg_comm:").toIntOrNull() ?: continue
                    msgCommMeasurables[idx] = m
                }
                id.startsWith("blk_tab:") -> {
                    val idx = id.removePrefix("blk_tab:").toIntOrNull() ?: continue
                    blkTabMeasurables[idx] = m
                }
                id.startsWith("blk_cond:") -> {
                    val idx = id.removePrefix("blk_cond:").toIntOrNull() ?: continue
                    blkCondMeasurables[idx] = m
                }
                id.startsWith("blk_div:") -> {
                    val parts = id.removePrefix("blk_div:").split(':')
                    if (parts.size == 2) {
                        val bIdx = parts[0].toIntOrNull() ?: continue
                        val dIdx = parts[1].toIntOrNull() ?: continue
                        blkDivMeasurables[bIdx to dIdx] = m
                    }
                }
            }
        }

        val grpHdrPlaceables = grpHdrMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val pTopPlaceables = pTopMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val pBotPlaceables = pBotMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val msgLblPlaceables = msgLblMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val msgCommPlaceables = msgCommMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val blkTabPlaceables = blkTabMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val blkCondPlaceables = blkCondMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val blkDivPlaceables = blkDivMeasurables.mapValues { (_, m) -> m.measure(unbounded) }

        val participantSizes = pTopPlaceables.mapValues { (id, p) ->
            val defaultSz = effectiveParticipants.firstOrNull { it.id == id }
                ?.let { ZenUmlLayout.defaultParticipantSize(it, renderDensity) }
            DiagramSize(
                width = max(p.width.toFloat(), defaultSz?.width ?: 0f),
                height = max(p.height.toFloat(), defaultSz?.height ?: 0f),
            )
        }
        val groupHeaderSizes = grpHdrPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }
        val messageLabelSizes = msgLblPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }
        val commentSizes = msgCommPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }
        val blockTabSizes = blkTabPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }
        val blockCondSizes = blkCondPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }
        val blockDividerSizes = blkDivPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = ZenUmlLayout.layout(
            diagram = diagram,
            participantSizes = participantSizes,
            groupHeaderSizes = groupHeaderSizes,
            messageLabelSizes = messageLabelSizes,
            commentSizes = commentSizes,
            blockTabSizes = blockTabSizes,
            blockCondSizes = blockCondSizes,
            blockDividerSizes = blockDividerSizes,
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val wPx = layout.width.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val hPx = layout.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }

            for ((gIdx, pg) in layout.groups.withIndex()) {
                val p = grpHdrPlaceables[gIdx] ?: continue
                p.place(
                    (pg.headerRect.centerX - p.width / 2f).roundToInt(),
                    (pg.headerRect.centerY - p.height / 2f).roundToInt(),
                )
            }

            for (pp in layout.participants) {
                pTopPlaceables[pp.participant.id]?.let { p ->
                    p.place(
                        (pp.topRect.centerX - p.width / 2f).roundToInt(),
                        (pp.topRect.centerY - p.height / 2f).roundToInt(),
                    )
                }
                val br = pp.bottomRect
                if (br != null) {
                    pBotPlaceables[pp.participant.id]?.let { p ->
                        p.place(
                            (br.centerX - p.width / 2f).roundToInt(),
                            (br.centerY - p.height / 2f).roundToInt(),
                        )
                    }
                }
            }

            for (pm in layout.messages) {
                msgLblPlaceables[pm.messageIndex]?.let { p ->
                    p.place(
                        (pm.labelRect.centerX - p.width / 2f).roundToInt(),
                        (pm.labelRect.centerY - p.height / 2f).roundToInt(),
                    )
                }
                val cr = pm.commentRect
                if (cr != null) {
                    msgCommPlaceables[pm.messageIndex]?.let { p ->
                        p.place(cr.x.roundToInt(), cr.y.roundToInt())
                    }
                }
            }

            for (pb in layout.blocks) {
                blkTabPlaceables[pb.blockIndex]?.let { p ->
                    p.place(
                        (pb.tabRect.centerX - p.width / 2f).roundToInt(),
                        (pb.tabRect.centerY - p.height / 2f).roundToInt(),
                    )
                }
                val condR = pb.conditionRect
                if (condR != null) {
                    blkCondPlaceables[pb.blockIndex]?.let { p ->
                        p.place(
                            condR.x.roundToInt(),
                            (condR.centerY - p.height / 2f).roundToInt(),
                        )
                    }
                }
                for ((dIdx, div) in pb.dividers.withIndex()) {
                    blkDivPlaceables[pb.blockIndex to dIdx]?.let { p ->
                        p.place(
                            div.labelRect.x.roundToInt(),
                            (div.labelRect.centerY - p.height / 2f).roundToInt(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZenUmlParticipantCard(
    participant: ZenUmlParticipant,
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
) {
    val stereoText = participant.rawAnnotation ?: when (participant.stereotype) {
        ZenUmlParticipantStereotype.DEFAULT -> null
        ZenUmlParticipantStereotype.ACTOR -> "Actor"
        ZenUmlParticipantStereotype.BOUNDARY -> "Boundary"
        ZenUmlParticipantStereotype.CONTROL -> "Control"
        ZenUmlParticipantStereotype.ENTITY -> "Entity"
        ZenUmlParticipantStereotype.DATABASE -> "Database"
        ZenUmlParticipantStereotype.COLLECTIONS -> "Collections"
        ZenUmlParticipantStereotype.QUEUE -> "Queue"
    }
    Column(
        modifier = modifier
            .widthIn(min = 104.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!stereoText.isNullOrBlank()) {
            BasicText(
                text = "«$stereoText»",
                style = TextStyle(
                    color = theme.nodeText.copy(alpha = 0.78f),
                    fontFamily = theme.fontFamily,
                    fontSize = theme.scaledSp(10f),
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        BasicText(
            text = annotatedLabel(participant.label),
            style = TextStyle(
                color = theme.nodeText,
                fontFamily = theme.fontFamily,
                fontSize = theme.scaledSp(13f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

private fun DrawScope.drawZenUmlUnderlay(
    layout: ZenUmlDiagramLayout,
    theme: MaradonaTheme,
) {
    val lifelineDash = PathEffect.dashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    val returnDash = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    val dividerDash = PathEffect.dashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)

    // 1. Groups
    for (pg in layout.groups) {
        val cr = CornerRadius(6f * density, 6f * density)
        drawRoundRect(
            color = theme.subgraphFill.copy(alpha = 0.55f),
            topLeft = Offset(pg.rect.x, pg.rect.y),
            size = Size(pg.rect.width, pg.rect.height),
            cornerRadius = cr,
            style = Fill,
        )
        drawRoundRect(
            color = theme.subgraphBorder,
            topLeft = Offset(pg.rect.x, pg.rect.y),
            size = Size(pg.rect.width, pg.rect.height),
            cornerRadius = cr,
            style = Stroke(width = 1.4f * density),
        )
    }

    // 2. Lifelines
    for (pp in layout.participants) {
        drawLine(
            color = theme.edge.copy(alpha = 0.65f),
            start = Offset(pp.lifelineX, pp.lifelineTopY),
            end = Offset(pp.lifelineX, pp.lifelineBottomY),
            strokeWidth = 1.4f * density,
            pathEffect = lifelineDash,
        )
    }

    // 3. Control Blocks (frames, tabs, dividers)
    val frameFill = theme.subgraphFill.copy(alpha = 0.45f)
    val frameStroke = theme.subgraphBorder
    for (pb in layout.blocks) {
        val r = pb.rect
        val cr = CornerRadius(4f * density, 4f * density)
        drawRoundRect(
            color = frameFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Fill,
        )
        drawRoundRect(
            color = frameStroke,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Stroke(width = 1.4f * density),
        )

        // Pentagon tab at top-left
        val tr = pb.tabRect
        val notch = 6f * density
        val tabPath = Path().apply {
            moveTo(tr.x, tr.y)
            lineTo(tr.right, tr.y)
            lineTo(tr.right, tr.bottom - notch)
            lineTo(tr.right - notch, tr.bottom)
            lineTo(tr.x, tr.bottom)
            close()
        }
        drawPath(tabPath, color = theme.nodeFill, style = Fill)
        drawPath(tabPath, color = frameStroke, style = Stroke(width = 1.3f * density))

        // Dividers for else if / else / catch / finally
        for (div in pb.dividers) {
            drawLine(
                color = frameStroke,
                start = Offset(r.x, div.y),
                end = Offset(r.right, div.y),
                strokeWidth = 1.2f * density,
                pathEffect = dividerDash,
            )
        }
    }

    // 4. Activation bars (shallowest first so nested self-call activations render on top)
    val actFill = theme.accentFill
    val actStroke = theme.accentBorder
    for (act in layout.activations.sortedBy { it.depth }) {
        val r = act.rect
        val cr = CornerRadius(2f * density, 2f * density)
        drawRoundRect(
            color = actFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Fill,
        )
        drawRoundRect(
            color = actStroke,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Stroke(width = 1.3f * density),
        )
    }

    // 5. Participant Header Cards (top and optional mirrored bottom)
    for (pp in layout.participants) {
        drawParticipantHeaderBox(pp.topRect, pp.participant, theme)
        pp.bottomRect?.let { drawParticipantHeaderBox(it, pp.participant, theme) }
    }

    // 6. Messages & Comments
    val commentFill = theme.noteFill
    val commentBorder = theme.noteBorder
    for (pm in layout.messages) {
        pm.commentRect?.let { cr ->
            val corner = CornerRadius(4f * density, 4f * density)
            drawRoundRect(
                color = commentFill,
                topLeft = Offset(cr.x, cr.y),
                size = Size(cr.width, cr.height),
                cornerRadius = corner,
                style = Fill,
            )
            drawRoundRect(
                color = commentBorder,
                topLeft = Offset(cr.x, cr.y),
                size = Size(cr.width, cr.height),
                cornerRadius = corner,
                style = Stroke(width = 1.1f * density),
            )
        }

        if (pm.displayText.isBlank() && pm.commentRect != null) continue

        // Label background pill so lifelines don't cut through text
        if (pm.labelRect.width > 0f && pm.labelRect.height > 0f) {
            drawRoundRect(
                color = theme.background.copy(alpha = 0.88f),
                topLeft = Offset(pm.labelRect.x, pm.labelRect.y),
                size = Size(pm.labelRect.width, pm.labelRect.height),
                cornerRadius = CornerRadius(3f * density, 3f * density),
                style = Fill,
            )
        }

        val strokeColor = theme.edge
        val isDashed = pm.kind == ZenUmlMessageKind.RETURN
        val isFilledHead = pm.kind == ZenUmlMessageKind.SYNC || pm.kind == ZenUmlMessageKind.CREATE

        if (pm.isSelf) {
            val loopW = 34f * density
            val loopH = max(18f * density, pm.selfLoopHeight)
            val x0 = pm.startX
            val x1 = pm.endX
            val rightX = max(x0, x1) + loopW
            val topY = pm.y
            val botY = pm.y + loopH
            val path = Path().apply {
                moveTo(x0, topY)
                lineTo(rightX, topY)
                lineTo(rightX, botY)
                lineTo(x1, botY)
            }
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(
                    width = 1.6f * density,
                    pathEffect = if (isDashed) returnDash else null,
                ),
            )
            drawMessageArrowhead(
                from = DiagramPoint(rightX, botY),
                to = DiagramPoint(x1, botY),
                color = strokeColor,
                filled = isFilledHead,
            )
        } else {
            drawLine(
                color = strokeColor,
                start = Offset(pm.startX, pm.y),
                end = Offset(pm.endX, pm.y),
                strokeWidth = 1.6f * density,
                pathEffect = if (isDashed) returnDash else null,
            )
            drawMessageArrowhead(
                from = DiagramPoint(pm.startX, pm.y),
                to = DiagramPoint(pm.endX, pm.y),
                color = strokeColor,
                filled = isFilledHead,
            )
        }
    }
}

private fun DrawScope.drawParticipantHeaderBox(
    rect: dev.maradona.layout.DiagramRect,
    participant: ZenUmlParticipant,
    theme: MaradonaTheme,
) {
    val cr = CornerRadius(6f * density, 6f * density)
    drawRoundRect(
        color = theme.nodeFill,
        topLeft = Offset(rect.x, rect.y),
        size = Size(rect.width, rect.height),
        cornerRadius = cr,
        style = Fill,
    )
    drawRoundRect(
        color = theme.nodeBorder,
        topLeft = Offset(rect.x, rect.y),
        size = Size(rect.width, rect.height),
        cornerRadius = cr,
        style = Stroke(width = 1.6f * density),
    )
    if (participant.stereotype != ZenUmlParticipantStereotype.DEFAULT) {
        // Subtle top accent bar for stereotyped participants
        drawLine(
            color = theme.nodeBorder,
            start = Offset(rect.x + 6f * density, rect.y + 3f * density),
            end = Offset(rect.right - 6f * density, rect.y + 3f * density),
            strokeWidth = 2f * density,
        )
    }
}

private fun DrawScope.drawMessageArrowhead(
    from: DiagramPoint,
    to: DiagramPoint,
    color: Color,
    filled: Boolean,
) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val len = 8.5f * density
    val spread = 0.45f
    val x1 = to.x - len * cos(angle - spread)
    val y1 = to.y - len * sin(angle - spread)
    val x2 = to.x - len * cos(angle + spread)
    val y2 = to.y - len * sin(angle + spread)
    if (filled) {
        val p = Path().apply {
            moveTo(to.x, to.y)
            lineTo(x1, y1)
            lineTo(x2, y2)
            close()
        }
        drawPath(p, color = color, style = Fill)
    } else {
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(to.x, to.y)
            lineTo(x2, y2)
        }
        drawPath(p, color = color, style = Stroke(width = 1.6f * density))
    }
}
