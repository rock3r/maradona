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
import dev.maradona.layout.MaradonaViewportStats
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private class SequenceLayoutHolder {
    var layout: SequenceDiagramLayout? = null
    var participantLabelSizes: Map<String, DiagramSize> = emptyMap()
    var messageLabelSizes: Map<Int, DiagramSize> = emptyMap()
    var noteTextSizes: Map<Int, DiagramSize> = emptyMap()
    var blockLabelSizes: Map<Int, DiagramSize> = emptyMap()
    var blockDivSizes: Map<String, DiagramSize> = emptyMap()
    var boxLabelSizes: Map<Int, DiagramSize> = emptyMap()
    var titleSize: DiagramSize? = null
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
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
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (SequenceDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, options) { SequenceLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()
    val densityObj = LocalDensity.current
    val renderDensity = densityObj.density
    val renderFontScale = densityObj.fontScale
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontResolutionToken = listOf(
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.SemiBold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontStyle = FontStyle.Italic).value,
    )
    val monoFontResolutionToken = fontFamilyResolver.resolve(fontFamily = theme.monospaceFontFamily).value

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
            computePremeasuredSequenceLayout(
                diagram = diagram,
                flatMessages = flatMessages,
                flatNotes = flatNotes,
                flatBlocks = flatBlocks,
                theme = theme,
                options = options,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.layout = pre.layout
                holder.participantLabelSizes = pre.participantLabelSizes
                holder.messageLabelSizes = pre.messageLabelSizes
                holder.noteTextSizes = pre.noteTextSizes
                holder.blockLabelSizes = pre.blockLabelSizes
                holder.blockDivSizes = pre.blockDivSizes
                holder.boxLabelSizes = pre.boxLabelSizes
                holder.titleSize = pre.titleSize
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

    val positionedParticipantById = remember(precomputedLayout) {
        precomputedLayout.participants.associateBy { it.participant.id }
    }
    val positionedBoxByIndex = remember(precomputedLayout) {
        precomputedLayout.boxes.associateBy { it.index }
    }

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                contentDescription =
                    "Sequence diagram. ${diagram.participants.size} participants, ${flatMessages.size} messages."
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (p in diagram.participants) {
                            val pp = positionedParticipantById[p.id] ?: continue
                            val topVis = rectIntersectsViewport(pp.topRect, vp, margin = 8f)
                            val botVis = diagram.mirrorActors &&
                                pp.bottomRect != null &&
                                rectIntersectsViewport(pp.bottomRect, vp, margin = 8f)
                            if (topVis || botVis) continue
                            val plainLabel = plainTextLabel(p.label)
                            val firstLink = p.links.entries.firstOrNull()
                            val clickEvent = when {
                                firstLink != null -> MaradonaNodeClickEvent(
                                    nodeId = p.id,
                                    label = plainLabel,
                                    url = firstLink.value,
                                    tooltip = firstLink.key,
                                    links = p.links,
                                )
                                onNodeClick != null -> MaradonaNodeClickEvent(
                                    nodeId = p.id,
                                    label = plainLabel,
                                )
                                else -> null
                            }
                            if (clickEvent != null) add(clickEvent)
                        }
                    }
                    val actions = buildOffscreenCustomActions(offscreenEvents, onNodeClick, uriHandler)
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawSequenceUnderlay(it, theme, holder.visibleViewport) }
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
                val pp = positionedParticipantById[p.id]
                val topVisible = activeCullViewport == null ||
                    pp == null ||
                    rectIntersectsViewport(pp.topRect, activeCullViewport, margin = 8f)
                if (topVisible) {
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
                }
                if (diagram.mirrorActors) {
                    val botRect = pp?.bottomRect
                    val botVisible = activeCullViewport == null ||
                        botRect == null ||
                        rectIntersectsViewport(botRect, activeCullViewport, margin = 8f)
                    if (botVisible) {
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
            }
            for ((index, msg) in flatMessages.withIndex()) {
                val pm = precomputedLayout.messages.getOrNull(index)
                val labelVisible = activeCullViewport == null ||
                    pm == null ||
                    (if (pm.labelRect.width > 0f && pm.labelRect.height > 0f) {
                        rectIntersectsViewport(pm.labelRect, activeCullViewport, margin = 8f)
                    } else {
                        segmentIntersectsViewport(pm.startX, pm.lineY, pm.stopX, pm.lineY, activeCullViewport, margin = 24f)
                    })
                if (labelVisible) {
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
                }
                if (diagram.autonumber) {
                    val seqVisible = labelVisible ||
                        segmentIntersectsViewport(pm.startX, pm.lineY, pm.stopX, pm.lineY, activeCullViewport, margin = 24f)
                    if (seqVisible) {
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
            }
            for ((index, note) in flatNotes.withIndex()) {
                val pn = precomputedLayout.notes.getOrNull(index)
                if (activeCullViewport != null && pn != null && !rectIntersectsViewport(pn.rect, activeCullViewport, margin = 8f)) {
                    continue
                }
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
                    val pb = positionedBoxByIndex[index]
                    if (activeCullViewport != null && pb != null && !rectIntersectsViewport(pb.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
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
                    val pblk = precomputedLayout.blocks.getOrNull(index)
                    if (activeCullViewport != null && pblk != null && !rectIntersectsViewport(pblk.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
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

        val effectiveParticipantLabelSizes = diagram.participants.associate { p ->
            val pl = labelMeasurables[p.id]
            p.id to (pl?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
                ?: holder.participantLabelSizes[p.id]
                ?: DiagramSize(0f, 0f))
        }
        val effectiveMessageLabelSizes = flatMessages.indices.associateWith { idx ->
            val pl = msgPlaceables[idx]
            pl?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
                ?: holder.messageLabelSizes[idx]
                ?: DiagramSize(0f, 0f)
        }
        val effectiveNoteTextSizes = flatNotes.indices.associateWith { idx ->
            val pl = notePlaceables[idx]
            pl?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
                ?: holder.noteTextSizes[idx]
                ?: DiagramSize(0f, 0f)
        }
        val effectiveBlockLabelSizes = flatBlocks.indices.associateWith { idx ->
            val pl = blockLabelPlaceables[idx]
            pl?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
                ?: holder.blockLabelSizes[idx]
                ?: DiagramSize(0f, 0f)
        }
        val effectiveBlockDivSizes = HashMap<String, DiagramSize>(holder.blockDivSizes).apply {
            for ((k, pl) in blockDivPlaceables) {
                this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
            }
        }
        val effectiveBoxLabelSizes = diagram.boxes.indices.associateWith { idx ->
            val pl = boxLabelPlaceables[idx]
            pl?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
                ?: holder.boxLabelSizes[idx]
                ?: DiagramSize(0f, 0f)
        }
        val effectiveTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize

        val result = SequenceLayout.layout(
            diagram = diagram,
            participantLabelSize = { p -> effectiveParticipantLabelSizes[p.id] ?: DiagramSize(0f, 0f) },
            messageLabelSize = { idx, _ -> effectiveMessageLabelSizes[idx] ?: DiagramSize(0f, 0f) },
            noteTextSize = { idx, _ -> effectiveNoteTextSizes[idx] ?: DiagramSize(0f, 0f) },
            blockLabelSize = { idx, secIdx, _ ->
                if (secIdx == 0) {
                    effectiveBlockLabelSizes[idx] ?: DiagramSize(0f, 0f)
                } else {
                    effectiveBlockDivSizes["$idx:$secIdx"] ?: DiagramSize(0f, 0f)
                }
            },
            boxLabelSize = { idx, _ -> effectiveBoxLabelSizes[idx] ?: DiagramSize(0f, 0f) },
            titleSize = effectiveTitleSize,
            options = scaledOptions,
        )
        if (activeCullViewport == null) {
            holder.participantLabelSizes = effectiveParticipantLabelSizes
            holder.messageLabelSizes = effectiveMessageLabelSizes
            holder.noteTextSizes = effectiveNoteTextSizes
            holder.blockLabelSizes = effectiveBlockLabelSizes
            holder.blockDivSizes = effectiveBlockDivSizes
            holder.boxLabelSizes = effectiveBoxLabelSizes
            holder.titleSize = effectiveTitleSize
        }
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

        val composedNodes = topBoxPlaceables.size + (if (diagram.mirrorActors) botBoxPlaceables.size else 0)
        val totalNodes = diagram.participants.size * (if (diagram.mirrorActors) 2 else 1)
        val edgeMargin = 30f * renderDensity
        val drawnEdges = if (activeCullViewport == null) {
            result.messages.size
        } else {
            result.messages.count { msg ->
                val endX = if (msg.isSelf) msg.startX + 42f * renderDensity else msg.stopX
                val endY = if (msg.isSelf) msg.lineY + 22f * renderDensity else msg.lineY
                segmentIntersectsViewport(msg.startX, msg.lineY, endX, endY, activeCullViewport, margin = edgeMargin)
            }
        }
        val drawnSubgraphs = if (activeCullViewport == null) {
            result.blocks.size + result.boxes.size
        } else {
            result.blocks.count { rectIntersectsViewport(it.rect, activeCullViewport, margin = 8f) } +
                result.boxes.count { rectIntersectsViewport(it.rect, activeCullViewport, margin = 8f) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = composedNodes,
                totalNodes = totalNodes,
                composedEdgeLabels = msgPlaceables.size,
                totalEdgeLabels = flatMessages.size,
                drawnEdges = drawnEdges,
                totalEdges = result.messages.size,
                drawnSubgraphs = drawnSubgraphs,
                totalSubgraphs = result.blocks.size + result.boxes.size,
            ),
        )

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

private data class PremeasuredSequenceResult(
    val layout: SequenceDiagramLayout,
    val participantLabelSizes: Map<String, DiagramSize>,
    val messageLabelSizes: Map<Int, DiagramSize>,
    val noteTextSizes: Map<Int, DiagramSize>,
    val blockLabelSizes: Map<Int, DiagramSize>,
    val blockDivSizes: Map<String, DiagramSize>,
    val boxLabelSizes: Map<Int, DiagramSize>,
    val titleSize: DiagramSize?,
)

private fun computePremeasuredSequenceLayout(
    diagram: SequenceDiagram,
    flatMessages: List<SequenceStatement.Message>,
    flatNotes: List<SequenceStatement.Note>,
    flatBlocks: List<SequenceStatement.Block>,
    theme: MaradonaTheme,
    options: SequenceLayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredSequenceResult {
    val renderDensity = densityObj.density
    val nodeStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.nodeFontSize,
        textAlign = TextAlign.Center,
    )
    val noteStyle = TextStyle(
        color = theme.noteText,
        fontFamily = theme.fontFamily,
        fontSize = theme.nodeFontSize,
        textAlign = TextAlign.Center,
    )
    val boxLabelStyle = TextStyle(
        color = theme.subgraphText,
        fontFamily = theme.fontFamily,
        fontSize = theme.subgraphFontSize,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val blockLabelStyle = TextStyle(
        color = theme.nodeText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        textAlign = TextAlign.Center,
    )
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
    val participantLabelSizes = diagram.participants.associate { p ->
        p.id to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(p.label),
            style = nodeStyle,
        )
    }
    val messageLabelSizes = flatMessages.mapIndexed { idx, msg ->
        idx to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(msg.text),
            style = nodeStyle,
        )
    }.toMap()
    val noteTextSizes = flatNotes.mapIndexed { idx, note ->
        idx to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(note.text),
            style = noteStyle,
        )
    }.toMap()
    val blockLabelSizes = HashMap<Int, DiagramSize>()
    val blockDivSizes = HashMap<String, DiagramSize>()
    for ((index, block) in flatBlocks.withIndex()) {
        if (block.kind == SequenceBlockKind.Rect) continue
        if (!block.label.isNullOrBlank()) {
            blockLabelSizes[index] = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel("[${block.label}]"),
                style = blockLabelStyle,
            )
        }
        for ((secIdx, sec) in block.sections.withIndex()) {
            if (secIdx > 0 && !sec.label.isNullOrBlank()) {
                blockDivSizes["$index:$secIdx"] = measureAnnotatedTextSize(
                    textMeasurer = textMeasurer,
                    densityObj = densityObj,
                    text = annotatedLabel("[${sec.label}]"),
                    style = blockLabelStyle,
                )
            }
        }
    }
    val boxLabelSizes = HashMap<Int, DiagramSize>()
    for ((index, box) in diagram.boxes.withIndex()) {
        if (!box.label.isNullOrBlank()) {
            boxLabelSizes[index] = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(box.label),
                style = boxLabelStyle,
            )
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
    val layout = SequenceLayout.layout(
        diagram = diagram,
        participantLabelSize = { p -> participantLabelSizes[p.id] ?: DiagramSize(0f, 0f) },
        messageLabelSize = { idx, _ -> messageLabelSizes[idx] ?: DiagramSize(0f, 0f) },
        noteTextSize = { idx, _ -> noteTextSizes[idx] ?: DiagramSize(0f, 0f) },
        blockLabelSize = { idx, secIdx, _ ->
            if (secIdx == 0) {
                blockLabelSizes[idx] ?: DiagramSize(0f, 0f)
            } else {
                blockDivSizes["$idx:$secIdx"] ?: DiagramSize(0f, 0f)
            }
        },
        boxLabelSize = { idx, _ -> boxLabelSizes[idx] ?: DiagramSize(0f, 0f) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredSequenceResult(
        layout = layout,
        participantLabelSizes = participantLabelSizes,
        messageLabelSizes = messageLabelSizes,
        noteTextSizes = noteTextSizes,
        blockLabelSizes = blockLabelSizes,
        blockDivSizes = blockDivSizes,
        boxLabelSizes = boxLabelSizes,
        titleSize = titleSize,
    )
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

private fun DrawScope.drawSequenceUnderlay(
    layout: SequenceDiagramLayout,
    theme: MaradonaTheme,
    viewport: DiagramRect? = null,
) {
    val unit = density
    val cullMargin = 24f * unit

    // 0. Participant `box` groupings behind everything else (transparent when uncolored).
    for (b in layout.boxes) {
        if (!rectIntersectsViewport(b.rect, viewport, margin = cullMargin)) continue
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
            if (!rectIntersectsViewport(block.rect, viewport, margin = cullMargin)) continue
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
        if (!segmentIntersectsViewport(pp.lifelineX, pp.lifelineTopY, pp.lifelineX, pp.lifelineBottomY, viewport, margin = cullMargin)) {
            continue
        }
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
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
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
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
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
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
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
        val endX = if (msg.isSelf) msg.startX + 42f * unit else msg.stopX
        val endY = if (msg.isSelf) msg.lineY + 22f * unit else msg.lineY
        if (!segmentIntersectsViewport(msg.startX, msg.lineY, endX, endY, viewport, margin = 30f * unit)) {
            continue
        }
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

internal fun measureSequenceLayoutForExport(
    diagram: SequenceDiagram,
    theme: MaradonaTheme,
    options: SequenceLayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): SequenceDiagramLayout = computePremeasuredSequenceLayout(
    diagram = diagram,
    flatMessages = collectMessages(diagram.statements),
    flatNotes = collectNotes(diagram.statements),
    flatBlocks = collectBlocks(diagram.statements),
    theme = theme,
    options = options,
    densityObj = densityObj,
    textMeasurer = textMeasurer,
).layout

