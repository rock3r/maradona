package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.PositionedParticipant
import dev.maradona.layout.PositionedSequenceMessage
import dev.maradona.layout.SequenceDiagramLayout
import dev.maradona.layout.SequenceLayout
import dev.maradona.layout.SequenceLayoutOptions
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.ParticipantKind
import dev.maradona.model.SequenceBlockKind
import dev.maradona.model.SequenceDiagram
import dev.maradona.model.SequenceHead
import dev.maradona.model.SequenceParticipant
import dev.maradona.model.SequenceStatement
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private class SequenceLayoutHolder {
    var layout: SequenceDiagramLayout? = null
}

/**
 * Custom Compose [Layout] for Mermaid `sequenceDiagram` diagrams.
 *
 * Participant boxes (header and mirrored footer), message labels, note labels,
 * and block labels are real Composables with semantics; lifelines, activation
 * bars, control-block frames, notes, and message arrows are drawn on the
 * underlay canvas.
 */
@Composable
fun MaradonaSequenceLayout(
    diagram: SequenceDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    options: SequenceLayoutOptions = SequenceLayoutOptions(),
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onLayout: (SequenceDiagramLayout) -> Unit = {},
) {
    val holder = remember { SequenceLayoutHolder() }
    val flatMessages = remember(diagram) { collectMessages(diagram.statements) }
    val flatNotes = remember(diagram) { collectNotes(diagram.statements) }
    val flatBlocks = remember(diagram) { collectBlocks(diagram.statements) }
    val participantSummaries = remember(diagram, flatMessages) {
        val out = HashMap<String, MutableList<String>>()
        for (p in diagram.participants) out[p.id] = mutableListOf()
        for (m in flatMessages) {
            out[m.from]?.add("sends ${plainTextLabel(m.text)} to ${m.to}")
            out[m.to]?.add("receives ${plainTextLabel(m.text)} from ${m.from}")
        }
        out.mapValues { it.value.joinToString("; ") }
    }

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                contentDescription =
                    "Sequence diagram. ${diagram.participants.size} participants, ${flatMessages.size} messages."
            }
            .drawBehind {
                holder.layout?.let { drawSequenceUnderlay(it, theme) }
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
            for ((pIdx, p) in diagram.participants.withIndex()) {
                BasicText(
                    text = annotatedLabel(p.label),
                    modifier = Modifier.layoutId("plabel:${p.id}"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.nodeFontSize,
                        textAlign = TextAlign.Center,
                    ),
                )
                SequenceParticipantBox(
                    participant = p,
                    theme = theme,
                    modifier = Modifier.layoutId("ptop:${p.id}"),
                    messageSummary = participantSummaries[p.id],
                    traversalOrder = pIdx,
                    onNodeClick = onNodeClick,
                )
                if (diagram.mirrorActors) {
                    SequenceParticipantBox(
                        participant = p,
                        theme = theme,
                        modifier = Modifier.layoutId("pbot:${p.id}"),
                        messageSummary = participantSummaries[p.id],
                        traversalOrder = diagram.participants.size + flatMessages.size + pIdx,
                        onNodeClick = onNodeClick,
                    )
                }
            }
            for ((index, msg) in flatMessages.withIndex()) {
                BasicText(
                    text = annotatedLabel(msg.text),
                    modifier = Modifier
                        .layoutId("msg:$index")
                        .semantics {
                            contentDescription = "Message from ${msg.from} to ${msg.to}: ${plainTextLabel(msg.text)}"
                        },
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.nodeFontSize,
                        textAlign = TextAlign.Center,
                    ),
                )
                if (diagram.autonumber) {
                    BasicText(
                        text = "${index + 1}",
                        modifier = Modifier.layoutId("seqnum:$index"),
                        style = TextStyle(
                            color = theme.background,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(10f),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
            for ((index, note) in flatNotes.withIndex()) {
                BasicText(
                    text = annotatedLabel(note.text),
                    modifier = Modifier
                        .layoutId("note:$index")
                        .semantics {
                            contentDescription = "Note: ${plainTextLabel(note.text)}"
                        },
                    style = TextStyle(
                        color = theme.noteText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.nodeFontSize,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for ((index, box) in diagram.boxes.withIndex()) {
                if (!box.label.isNullOrBlank()) {
                    BasicText(
                        text = annotatedLabel(box.label),
                        modifier = Modifier.layoutId("boxlabel:$index"),
                        style = TextStyle(
                            color = theme.subgraphText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.subgraphFontSize,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
            for ((index, block) in flatBlocks.withIndex()) {
                if (block.kind != SequenceBlockKind.Rect) {
                    BasicText(
                        text = block.kind.name.lowercase(),
                        modifier = Modifier.layoutId("btag:$index"),
                        style = TextStyle(
                            color = theme.nodeText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    if (!block.label.isNullOrBlank()) {
                        BasicText(
                            text = annotatedLabel("[${block.label}]"),
                            modifier = Modifier.layoutId("blabel:$index"),
                            style = TextStyle(
                                color = theme.nodeText,
                                fontFamily = theme.fontFamily,
                                fontSize = theme.edgeLabelFontSize,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                    for ((secIdx, sec) in block.sections.withIndex()) {
                        if (secIdx > 0 && !sec.label.isNullOrBlank()) {
                            BasicText(
                                text = annotatedLabel("[${sec.label}]"),
                                modifier = Modifier.layoutId("bdiv:$index:$secIdx"),
                                style = TextStyle(
                                    color = theme.nodeText,
                                    fontFamily = theme.fontFamily,
                                    fontSize = theme.edgeLabelFontSize,
                                    textAlign = TextAlign.Center,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { measurables, _ ->
        val renderDensity = density
        val unbounded = Constraints()
        val labelMeasurables = HashMap<String, Placeable>()
        val msgPlaceables = HashMap<Int, Placeable>()
        val seqNumPlaceables = HashMap<Int, Placeable>()
        val notePlaceables = HashMap<Int, Placeable>()
        val blockTagPlaceables = HashMap<Int, Placeable>()
        val blockLabelPlaceables = HashMap<Int, Placeable>()
        val blockDivPlaceables = HashMap<String, Placeable>()
        val boxLabelPlaceables = HashMap<Int, Placeable>()
        var titlePlaceable: Placeable? = null

        val topBoxMeasurables = HashMap<String, androidx.compose.ui.layout.Measurable>()
        val botBoxMeasurables = HashMap<String, androidx.compose.ui.layout.Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("plabel:") -> labelMeasurables[id.removePrefix("plabel:")] = m.measure(unbounded)
                id.startsWith("ptop:") -> topBoxMeasurables[id.removePrefix("ptop:")] = m
                id.startsWith("pbot:") -> botBoxMeasurables[id.removePrefix("pbot:")] = m
                id.startsWith("msg:") -> msgPlaceables[id.removePrefix("msg:").toInt()] = m.measure(unbounded)
                id.startsWith("seqnum:") -> seqNumPlaceables[id.removePrefix("seqnum:").toInt()] = m.measure(unbounded)
                id.startsWith("note:") -> notePlaceables[id.removePrefix("note:").toInt()] = m.measure(unbounded)
                id.startsWith("btag:") -> blockTagPlaceables[id.removePrefix("btag:").toInt()] = m.measure(unbounded)
                id.startsWith("blabel:") -> blockLabelPlaceables[id.removePrefix("blabel:").toInt()] = m.measure(unbounded)
                id.startsWith("bdiv:") -> blockDivPlaceables[id.removePrefix("bdiv:")] = m.measure(unbounded)
                id.startsWith("boxlabel:") -> boxLabelPlaceables[id.removePrefix("boxlabel:").toInt()] = m.measure(unbounded)
            }
        }

        val scaledOptions = with(options) {
            copy(
                actorWidth = actorWidth * renderDensity,
                actorHeight = actorHeight * renderDensity,
                actorMargin = actorMargin * renderDensity,
                activationWidth = activationWidth * renderDensity,
                boxMargin = boxMargin * renderDensity,
                boxTextMargin = boxTextMargin * renderDensity,
                noteMargin = noteMargin * renderDensity,
                wrapPadding = wrapPadding * renderDensity,
                labelBoxWidth = labelBoxWidth * renderDensity,
                labelBoxHeight = labelBoxHeight * renderDensity,
                paddingX = paddingX * renderDensity,
                paddingY = paddingY * renderDensity,
                density = renderDensity,
            )
        }

        val result = SequenceLayout.layout(
            diagram = diagram,
            participantLabelSize = { p ->
                val pl = labelMeasurables[p.id]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            messageLabelSize = { idx, _ ->
                val pl = msgPlaceables[idx]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            noteTextSize = { idx, _ ->
                val pl = notePlaceables[idx]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            blockLabelSize = { idx, secIdx, _ ->
                val pl = if (secIdx == 0) blockLabelPlaceables[idx] else blockDivPlaceables["$idx:$secIdx"]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            boxLabelSize = { idx, _ ->
                val pl = boxLabelPlaceables[idx]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = result
        onLayout(result)

        val topBoxPlaceables = HashMap<String, Placeable>()
        val botBoxPlaceables = HashMap<String, Placeable>()
        for (pp in result.participants) {
            val id = pp.participant.id
            val w = pp.topRect.width.roundToInt()
            val h = pp.topRect.height.roundToInt()
            topBoxMeasurables[id]?.let {
                topBoxPlaceables[id] = it.measure(Constraints.fixed(w, h))
            }
            if (pp.bottomRect != null) {
                botBoxMeasurables[id]?.let {
                    botBoxPlaceables[id] = it.measure(Constraints.fixed(w, h))
                }
            }
        }

        layout(result.width.roundToInt(), result.height.roundToInt()) {
            result.titleRect?.let { r ->
                titlePlaceable?.place(r.x.roundToInt(), r.y.roundToInt())
            }
            for (b in result.boxes) {
                b.labelRect?.let { r ->
                    boxLabelPlaceables[b.index]?.place(r.x.roundToInt(), r.y.roundToInt())
                }
            }
            for (pp in result.participants) {
                val id = pp.participant.id
                topBoxPlaceables[id]?.place(pp.topRect.x.roundToInt(), pp.topRect.y.roundToInt())
                pp.bottomRect?.let { br ->
                    botBoxPlaceables[id]?.place(br.x.roundToInt(), br.y.roundToInt())
                }
            }
            for (msg in result.messages) {
                msgPlaceables[msg.index]?.place(
                    msg.labelRect.x.roundToInt(),
                    msg.labelRect.y.roundToInt(),
                )
                if (msg.sequenceNumber != null) {
                    val pl = seqNumPlaceables[msg.index] ?: continue
                    val dir = if (msg.stopX >= msg.startX) 1f else -1f
                    val badgeX = msg.startX + dir * 8f * renderDensity
                    pl.place(
                        (badgeX - pl.width / 2f).roundToInt(),
                        (msg.lineY - pl.height / 2f).roundToInt(),
                    )
                }
            }
            for (note in result.notes) {
                val pl = notePlaceables[note.index] ?: continue
                pl.place(
                    (note.rect.centerX - pl.width / 2f).roundToInt(),
                    (note.rect.centerY - pl.height / 2f).roundToInt(),
                )
            }
            for (block in result.blocks) {
                if (block.kind == SequenceBlockKind.Rect) continue
                blockTagPlaceables[block.index]?.let { pl ->
                    pl.place(
                        (block.tagRect.centerX - pl.width / 2f).roundToInt(),
                        (block.tagRect.centerY - pl.height / 2f).roundToInt(),
                    )
                }
                block.labelRect?.let { r ->
                    blockLabelPlaceables[block.index]?.place(r.x.roundToInt(), r.y.roundToInt())
                }
                for ((divIdx, div) in block.dividers.withIndex()) {
                    val r = div.labelRect ?: continue
                    blockDivPlaceables["${block.index}:${divIdx + 1}"]?.place(
                        r.x.roundToInt(),
                        r.y.roundToInt(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SequenceParticipantBox(
    participant: SequenceParticipant,
    theme: MaradonaTheme,
    modifier: Modifier = Modifier,
    messageSummary: String? = null,
    traversalOrder: Int = 0,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val plainLabel = plainTextLabel(participant.label)
    val baseDesc = buildString {
        append("Participant $plainLabel")
        if (!messageSummary.isNullOrBlank()) append(". $messageSummary")
    }
    val firstLink = participant.links.entries.firstOrNull()
    val clickEvent = when {
        firstLink != null -> MaradonaNodeClickEvent(
            nodeId = participant.id,
            label = plainLabel,
            url = firstLink.value,
            tooltip = firstLink.key,
            links = participant.links,
        )
        onNodeClick != null -> MaradonaNodeClickEvent(
            nodeId = participant.id,
            label = plainLabel,
        )
        else -> null
    }
    Box(
        modifier = modifier
            .drawBehind {
                val unit = density
                val stroke = Stroke(width = 1f * unit)
                if (participant.kind == ParticipantKind.Actor) {
                    // Stick figure in the upper glyph band (44 units tall), label at bottom.
                    val cx = size.width / 2f
                    val topY = 6f * unit
                    val headR = 9f * unit
                    val headCy = topY + headR
                    drawCircle(theme.nodeFill, radius = headR, center = Offset(cx, headCy))
                    drawCircle(theme.nodeBorder, radius = headR, center = Offset(cx, headCy), style = Stroke(width = 1.5f * unit))
                    val neckY = headCy + headR
                    val hipY = neckY + 14f * unit
                    val armY = neckY + 5f * unit
                    val footY = hipY + 11f * unit
                    val span = 11f * unit
                    drawLine(theme.nodeBorder, Offset(cx, neckY), Offset(cx, hipY), strokeWidth = 1.5f * unit)
                    drawLine(theme.nodeBorder, Offset(cx - span, armY), Offset(cx + span, armY), strokeWidth = 1.5f * unit)
                    drawLine(theme.nodeBorder, Offset(cx, hipY), Offset(cx - span * 0.85f, footY), strokeWidth = 1.5f * unit)
                    drawLine(theme.nodeBorder, Offset(cx, hipY), Offset(cx + span * 0.85f, footY), strokeWidth = 1.5f * unit)
                } else {
                    val radius = CornerRadius(6f * unit, 6f * unit)
                    drawRoundRect(theme.nodeFill, cornerRadius = radius)
                    drawRoundRect(theme.nodeBorder, cornerRadius = radius, style = stroke)
                }
            }
            .diagramNodeInteraction(
                baseDescription = baseDesc,
                traversalOrder = traversalOrder,
                event = clickEvent,
                onNodeClick = onNodeClick,
                uriHandler = uriHandler,
                mergeDescendants = true,
            ),
        contentAlignment = if (participant.kind == ParticipantKind.Actor) Alignment.BottomCenter else Alignment.Center,
    ) {
        BasicText(
            text = annotatedLabel(participant.label),
            modifier = if (participant.kind == ParticipantKind.Actor) Modifier.padding(bottom = 4.dp) else Modifier,
            style = TextStyle(
                color = theme.nodeText,
                fontFamily = theme.fontFamily,
                fontSize = theme.nodeFontSize,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

private fun DrawScope.drawSequenceUnderlay(layout: SequenceDiagramLayout, theme: MaradonaTheme) {
    val unit = density

    // 0. Participant `box` groupings behind everything else (transparent when uncolored).
    for (b in layout.boxes) {
        val fill = b.box.color?.let(::parseCssColor) ?: Color.Transparent
        if (fill != Color.Transparent) {
            drawRect(
                color = fill,
                topLeft = Offset(b.rect.x, b.rect.y),
                size = Size(b.rect.width, b.rect.height),
            )
        }
        drawRect(
            color = theme.subgraphBorder,
            topLeft = Offset(b.rect.x, b.rect.y),
            size = Size(b.rect.width, b.rect.height),
            style = Stroke(width = 1f * unit),
        )
    }

    // 1. Background `rect` blocks.
    for (block in layout.blocks) {
        if (block.kind == SequenceBlockKind.Rect) {
            val fill = parseCssColor(block.label) ?: theme.subgraphFill.copy(alpha = 0.55f)
            drawRect(
                color = fill,
                topLeft = Offset(block.rect.x, block.rect.y),
                size = Size(block.rect.width, block.rect.height),
            )
        }
    }

    // 2. Lifelines behind activations, notes, and messages.
    for (pp in layout.participants) {
        drawLine(
            color = theme.nodeBorder,
            start = Offset(pp.lifelineX, pp.lifelineTopY),
            end = Offset(pp.lifelineX, pp.lifelineBottomY),
            strokeWidth = 1f * unit,
        )
    }

    // 3. Activation bars (drawn above lifelines and behind control-flow frame tabs).
    for (act in layout.activations) {
        val r = act.rect
        drawRect(
            color = theme.subgraphFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = theme.nodeBorder,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = 1f * unit),
        )
    }

    // 4. Control-flow frames (`loop`, `alt`, `opt`, `par`, `critical`, `break`).
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(3f * unit, 3f * unit), 0f)
    for (block in layout.blocks) {
        if (block.kind == SequenceBlockKind.Rect) continue
        val r = block.rect
        drawRect(
            color = theme.nodeBorder,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = 1.5f * unit, pathEffect = dashEffect),
        )
        // Pentagon tag tab in top-left corner (`labelBox`).
        val tr = block.tagRect
        val notch = 6f * unit
        val tabPath = Path().apply {
            moveTo(tr.x, tr.y)
            lineTo(tr.right, tr.y)
            lineTo(tr.right, tr.bottom - notch)
            lineTo(tr.right - notch, tr.bottom)
            lineTo(tr.x, tr.bottom)
            close()
        }
        drawPath(tabPath, color = theme.nodeFill)
        drawPath(tabPath, color = theme.nodeBorder, style = Stroke(width = 1f * unit))

        for (div in block.dividers) {
            drawLine(
                color = theme.nodeBorder,
                start = Offset(r.x, div.y),
                end = Offset(r.right, div.y),
                strokeWidth = 1.5f * unit,
                pathEffect = dashEffect,
            )
        }
    }

    // 5. Notes (sticky note fill and border from theme).
    val noteFill = theme.noteFill
    val noteBorder = theme.noteBorder
    for (note in layout.notes) {
        val r = note.rect
        drawRect(
            color = noteFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = noteBorder,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = 1f * unit),
        )
    }

    // 6. Messages and arrowheads.
    for (msg in layout.messages) {
        val arrow = msg.statement.arrow
        val strokeEffect = if (arrow.dashed) {
            PathEffect.dashPathEffect(floatArrayOf(3f * unit, 3f * unit), 0f)
        } else null
        val strokeWidth = 1.5f * unit

        if (msg.isSelf) {
            val x = msg.startX
            val y = msg.lineY
            val loopW = 42f * unit
            val loopH = 22f * unit
            val selfPath = Path().apply {
                moveTo(x, y)
                cubicTo(x + loopW, y - 6f * unit, x + loopW, y + loopH + 6f * unit, x, y + loopH)
            }
            drawPath(
                path = selfPath,
                color = theme.edge,
                style = Stroke(width = strokeWidth, pathEffect = strokeEffect),
            )
            drawSequenceHead(
                head = arrow.head,
                tip = DiagramPoint(x, y + loopH),
                from = DiagramPoint(x + 12f * unit, y + loopH),
                color = theme.edge,
                unit = unit,
            )
            if (arrow.bidirectional) {
                drawSequenceHead(
                    head = arrow.head,
                    tip = DiagramPoint(x, y),
                    from = DiagramPoint(x + 12f * unit, y),
                    color = theme.edge,
                    unit = unit,
                )
            }
        } else {
            val toRight = msg.stopX >= msg.startX
            val trimEnd = if (arrow.head == SequenceHead.Filled) 7f * unit else 0f
            val trimStart = if (arrow.bidirectional && arrow.head == SequenceHead.Filled) 7f * unit else 0f
            val x1 = msg.startX + (if (toRight) trimStart else -trimStart)
            val x2 = msg.stopX + (if (toRight) -trimEnd else trimEnd)
            drawLine(
                color = theme.edge,
                start = Offset(x1, msg.lineY),
                end = Offset(x2, msg.lineY),
                strokeWidth = strokeWidth,
                pathEffect = strokeEffect,
            )
            drawSequenceHead(
                head = arrow.head,
                tip = DiagramPoint(msg.stopX, msg.lineY),
                from = DiagramPoint(msg.startX, msg.lineY),
                color = theme.edge,
                unit = unit,
            )
            if (arrow.bidirectional) {
                drawSequenceHead(
                    head = arrow.head,
                    tip = DiagramPoint(msg.startX, msg.lineY),
                    from = DiagramPoint(msg.stopX, msg.lineY),
                    color = theme.edge,
                    unit = unit,
                )
            }
        }

        if (msg.sequenceNumber != null) {
            val dir = if (msg.stopX >= msg.startX) 1f else -1f
            val badgeX = msg.startX + dir * 8f * unit
            drawCircle(
                color = theme.edge,
                radius = 7f * unit,
                center = Offset(badgeX, msg.lineY),
            )
        }
    }
}

private fun DrawScope.drawSequenceHead(
    head: SequenceHead,
    tip: DiagramPoint,
    from: DiagramPoint,
    color: Color,
    unit: Float,
) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    when (head) {
        SequenceHead.Open -> Unit
        SequenceHead.Filled -> {
            val len = 10f * unit
            val halfW = 5f * unit
            val spread = atan2(halfW, len)
            val p1 = Offset(tip.x - len * cos(angle - spread), tip.y - len * sin(angle - spread))
            val p2 = Offset(tip.x - len * cos(angle + spread), tip.y - len * sin(angle + spread))
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                close()
            }
            drawPath(path, color = color)
        }
        SequenceHead.Async -> {
            val len = 9f * unit
            val halfW = 5f * unit
            val spread = atan2(halfW, len)
            val p1 = Offset(tip.x - len * cos(angle - spread), tip.y - len * sin(angle - spread))
            val p2 = Offset(tip.x - len * cos(angle + spread), tip.y - len * sin(angle + spread))
            drawLine(color, Offset(tip.x, tip.y), p1, strokeWidth = 1.5f * unit, cap = StrokeCap.Round)
            drawLine(color, Offset(tip.x, tip.y), p2, strokeWidth = 1.5f * unit, cap = StrokeCap.Round)
        }
        SequenceHead.Cross -> {
            val r = 4.5f * unit
            val ux = cos(angle); val uy = sin(angle)
            val px = -uy; val py = ux
            val center = Offset(tip.x - ux * r, tip.y - uy * r)
            drawLine(
                color,
                Offset(center.x + (ux + px) * r, center.y + (uy + py) * r),
                Offset(center.x - (ux + px) * r, center.y - (uy + py) * r),
                strokeWidth = 1.5f * unit,
                cap = StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(center.x + (ux - px) * r, center.y + (uy - py) * r),
                Offset(center.x - (ux - px) * r, center.y - (uy - py) * r),
                strokeWidth = 1.5f * unit,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun collectMessages(statements: List<SequenceStatement>): List<SequenceStatement.Message> {
    val out = mutableListOf<SequenceStatement.Message>()
    fun walk(list: List<SequenceStatement>) {
        for (s in list) {
            when (s) {
                is SequenceStatement.Message -> out += s
                is SequenceStatement.Block -> for (sec in s.sections) walk(sec.statements)
                else -> Unit
            }
        }
    }
    walk(statements)
    return out
}

private fun collectNotes(statements: List<SequenceStatement>): List<SequenceStatement.Note> {
    val out = mutableListOf<SequenceStatement.Note>()
    fun walk(list: List<SequenceStatement>) {
        for (s in list) {
            when (s) {
                is SequenceStatement.Note -> out += s
                is SequenceStatement.Block -> for (sec in s.sections) walk(sec.statements)
                else -> Unit
            }
        }
    }
    walk(statements)
    return out
}

private fun collectBlocks(statements: List<SequenceStatement>): List<SequenceStatement.Block> {
    val out = mutableListOf<SequenceStatement.Block>()
    fun walk(list: List<SequenceStatement>) {
        for (s in list) {
            if (s is SequenceStatement.Block) {
                out += s
                for (sec in s.sections) walk(sec.statements)
            }
        }
    }
    walk(statements)
    return out
}
