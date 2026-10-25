package dev.maradona.export

import androidx.compose.ui.graphics.Color
import dev.maradona.layout.ArchitectureDiagramLayout
import dev.maradona.layout.ArchitectureLayout
import dev.maradona.layout.C4DiagramLayout
import dev.maradona.layout.C4Layout
import dev.maradona.layout.ClassBoxMetrics
import dev.maradona.layout.ClassDiagramLayout
import dev.maradona.layout.ClassLayout
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.ErDiagramLayout
import dev.maradona.layout.ErLayout
import dev.maradona.layout.FlowchartLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.NodeOutline
import dev.maradona.layout.NodeSizing
import dev.maradona.layout.RequirementDiagramLayout
import dev.maradona.layout.RequirementLayout
import dev.maradona.layout.SequenceDiagramLayout
import dev.maradona.layout.SequenceLayout
import dev.maradona.layout.StateDiagramLayout
import dev.maradona.layout.StateLayout
import dev.maradona.layout.ZenUmlDiagramLayout
import dev.maradona.layout.ZenUmlLayout
import dev.maradona.model.ArchitectureDiagram
import dev.maradona.model.ArrowHead
import dev.maradona.model.C4Diagram
import dev.maradona.model.C4ElementKind
import dev.maradona.model.ClassClassifier
import dev.maradona.model.ClassDiagram
import dev.maradona.model.ClassRelationEnd
import dev.maradona.model.EdgeStroke
import dev.maradona.model.ElementItem
import dev.maradona.model.ErCardinality
import dev.maradona.model.ErDiagram
import dev.maradona.model.FlowNode
import dev.maradona.model.Flowchart
import dev.maradona.model.NodeShape
import dev.maradona.model.ParticipantKind
import dev.maradona.model.RequirementDiagram
import dev.maradona.model.SequenceBlockKind
import dev.maradona.model.SequenceDiagram
import dev.maradona.model.SequenceHead
import dev.maradona.model.StateDiagram
import dev.maradona.model.StateKind
import dev.maradona.model.ZenUmlDiagram
import dev.maradona.model.ZenUmlMessageKind
import dev.maradona.model.ZenUmlParticipant
import dev.maradona.model.ZenUmlParticipantStereotype
import dev.maradona.parse.MaradonaDiagramModel
import dev.maradona.parse.MaradonaParser
import dev.maradona.render.MaradonaTheme
import dev.maradona.render.parseCssColor
import dev.maradona.render.plainTextLabel
import dev.maradona.render.resolveMermaidIconGlyph
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure Kotlin multiplatform SVG exporter for Maradona diagrams.
 *
 * Generates standalone, standards-compliant `<svg>` documents styled with [MaradonaTheme]
 * tokens without requiring an active display window or platform-specific graphics context.
 */
object MaradonaSvgExporter {

    /**
     * Parses [source] and exports a supported Mermaid diagram to a standalone SVG string.
     */
    fun exportToSvg(
        source: String,
        theme: MaradonaTheme = MaradonaTheme.light(),
        options: LayoutOptions = LayoutOptions(),
    ): String {
        return when (val model = MaradonaParser.parse(source)) {
            is MaradonaDiagramModel.FlowchartModel -> {
                val layout = FlowchartLayout.layout(
                    chart = model.chart,
                    nodeSize = { defaultFlowNodeSize(it, theme) },
                    edgeLabelSize = { e -> e.label?.let { defaultTextSize(it, theme.edgeLabelFontSize.value) } },
                    options = options,
                )
                exportFlowchartToSvg(model.chart, layout, theme)
            }
            is MaradonaDiagramModel.SequenceModel -> {
                val d = model.diagram
                val seqOpts = with(dev.maradona.layout.SequenceLayoutOptions(density = options.density)) {
                    val dens = options.density.coerceAtLeast(0.01f)
                    copy(
                        actorWidth = actorWidth * dens,
                        actorHeight = actorHeight * dens,
                        actorMargin = actorMargin * dens,
                        activationWidth = activationWidth * dens,
                        boxMargin = boxMargin * dens,
                        boxTextMargin = boxTextMargin * dens,
                        noteMargin = noteMargin * dens,
                        wrapPadding = wrapPadding * dens,
                        labelBoxWidth = labelBoxWidth * dens,
                        labelBoxHeight = labelBoxHeight * dens,
                        paddingX = paddingX * dens,
                        paddingY = paddingY * dens,
                    )
                }
                val layout = SequenceLayout.layout(
                    diagram = d,
                    participantLabelSize = { p -> defaultTextSize(p.label, theme.nodeFontSize.value) },
                    messageLabelSize = { _, m -> defaultTextSize(m.text, theme.nodeFontSize.value) },
                    noteTextSize = { _, n -> defaultTextSize(n.text, theme.nodeFontSize.value) },
                    blockLabelSize = { _, _, b -> defaultTextSize(b, theme.edgeLabelFontSize.value) },
                    boxLabelSize = { _, bx -> bx.label?.let { defaultTextSize(it, theme.subgraphFontSize.value) } ?: DiagramSize(0f, 0f) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = seqOpts,
                )
                exportSequenceToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.ClassModel -> {
                val d = model.diagram
                val memberLineH = theme.edgeLabelFontSize.value * 1.4f
                val layout = ClassLayout.layout(
                    diagram = d,
                    classBoxSize = { c ->
                        val stereoLines = c.annotations.size
                        val titleSize = defaultTextSize(c.displayTitle, theme.nodeFontSize.value)
                        val memberWidths = (c.attributes + c.methods).map {
                            defaultTextSize(it.displayText, theme.edgeLabelFontSize.value).width
                        }
                        val maxMemberW = memberWidths.maxOrNull() ?: 0f
                        val showCompartments = !d.hideEmptyMembersBox || c.attributes.isNotEmpty() || c.methods.isNotEmpty()
                        val attrH = if (!showCompartments) 0f else if (c.attributes.isEmpty()) 8f else c.attributes.size * memberLineH + 12f
                        val methH = if (!showCompartments) 0f else if (c.methods.isEmpty()) 8f else c.methods.size * memberLineH + 12f
                        val hdrH = max(32f, titleSize.height + stereoLines * (theme.nodeFontSize.value * 1.35f) + 16f)
                        val totalW = max(110f, max(titleSize.width, maxMemberW) + 24f)
                        ClassBoxMetrics(totalW, hdrH + attrH + methH, hdrH, attrH, methH)
                    },
                    relationLabelSize = { _, r -> r.label?.let { defaultTextSize(it, theme.edgeLabelFontSize.value) } },
                    cardinalitySize = { _, _, text -> defaultTextSize(text, theme.captionFontSize.value) },
                    noteBoxSize = { _, note ->
                        val sz = defaultTextSize(note.text, theme.edgeLabelFontSize.value)
                        DiagramSize(max(80f, sz.width + 16f), max(32f, sz.height + 12f))
                    },
                    namespaceLabelSize = { ns -> defaultTextSize(ns.label, theme.subgraphFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportClassToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.StateModel -> {
                val d = model.diagram
                val layout = StateLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportStateToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.ErModel -> {
                val d = model.diagram
                val layout = ErLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportErToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.RequirementModel -> {
                val d = model.diagram
                val layout = RequirementLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportRequirementToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.C4Model -> {
                val d = model.diagram
                val layout = C4Layout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportC4ToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.ArchitectureModel -> {
                val d = model.diagram
                val layout = ArchitectureLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportArchitectureToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.ZenUmlModel -> {
                val d = model.diagram
                val layout = ZenUmlLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportZenUmlToSvg(d, layout, theme)
            }
            else -> throw UnsupportedOperationException(
                "Standalone SVG export does not yet support ${model::class.simpleName}; render it through MaradonaDiagram.",
            )
        }
    }

    /**
     * Exports a pre-laid-out [Flowchart] and [DiagramLayout] to a standalone SVG string.
     */
    fun exportFlowchartToSvg(
        chart: Flowchart,
        layout: DiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val w = ceil(layout.width).toInt().coerceAtLeast(1)
        val h = ceil(layout.height).toInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h">""").append('\n')
        sb.append("""  <rect width="100%" height="100%" fill="${theme.background.toSvgHex()}"/>""").append('\n')

        // 1. Subgraph containers
        for (psg in layout.subgraphs) {
            val r = psg.rect
            sb.append("""  <g class="subgraph" data-id="${escapeXml(psg.subgraph.id)}">""").append('\n')
            sb.append(
                """    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.subgraphFill.toSvgHex()}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
            val sgLines = plainTextLabel(psg.subgraph.label).split('\n')
            val sgFont = theme.subgraphFontSize.value
            val sgLineH = sgFont * 1.3f
            for ((idx, line) in sgLines.withIndex()) {
                val ly = r.y + 18f + idx * sgLineH
                sb.append(
                    """    <text x="${fmt(r.centerX)}" y="${fmt(ly)}" text-anchor="middle" fill="${theme.subgraphText.toSvgHex()}" font-family="sans-serif" font-size="${fmt(sgFont)}" font-weight="600">${escapeXml(line)}</text>""",
                ).append('\n')
            }
            sb.append("""  </g>""").append('\n')
        }

        // 2. Edges and arrowheads
        for (pe in layout.edges) {
            if (pe.edge.stroke == EdgeStroke.Invisible || pe.points.size < 2) continue
            val edgeColor = (parseCssColor(pe.edge.style?.stroke) ?: theme.edge).toSvgHex()
            val strokeW = pe.edge.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull()
                ?: if (pe.edge.stroke == EdgeStroke.Thick) 3.5f else 1.5f
            val dashAttr = if (pe.edge.stroke == EdgeStroke.Dotted) """ stroke-dasharray="4,4"""" else ""
            val d = buildSvgEdgePath(pe.points)
            sb.append("""  <path class="edge" d="$d" fill="none" stroke="$edgeColor" stroke-width="${fmt(strokeW)}"$dashAttr/>""").append('\n')

            if (pe.edge.endHead != ArrowHead.None) {
                val tip = pe.points.last()
                val prev = pe.points.dropLast(1).lastOrNull { it.x != tip.x || it.y != tip.y } ?: pe.points[pe.points.size - 2]
                appendSvgArrowHead(sb, tip, prev, pe.edge.endHead, edgeColor, theme.background.toSvgHex())
            }
            if (pe.edge.startHead != ArrowHead.None) {
                val tip = pe.points.first()
                val next = pe.points.drop(1).firstOrNull { it.x != tip.x || it.y != tip.y } ?: pe.points[1]
                appendSvgArrowHead(sb, tip, next, pe.edge.startHead, edgeColor, theme.background.toSvgHex())
            }

            val lr = pe.labelRect
            val lbl = pe.edge.label
            if (lr != null && !lbl.isNullOrBlank()) {
                sb.append(
                    """  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""",
                ).append('\n')
                appendMultilineText(
                    sb = sb,
                    indent = "  ",
                    centerX = lr.centerX,
                    centerY = lr.centerY,
                    lines = plainTextLabel(lbl).split('\n'),
                    colorHex = theme.edgeLabelText.toSvgHex(),
                    fontSize = theme.edgeLabelFontSize.value,
                )
            }
        }

        // 3. Nodes (with optional <a> links and <title> tooltips)
        for (pn in layout.nodes) {
            val node = pn.node
            val r = pn.rect
            val fill = (parseCssColor(node.style?.fill) ?: theme.nodeFill).toSvgHex()
            val stroke = (parseCssColor(node.style?.stroke) ?: theme.nodeBorder).toSvgHex()
            val textColor = (parseCssColor(node.style?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = node.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1f
            val url = node.action?.url?.takeIf { it.isNotBlank() }
            val tooltip = node.action?.tooltip?.takeIf { it.isNotBlank() }

            if (url != null) {
                val targetAttr = node.action.target?.let { """ target="${escapeXml(it)}"""" } ?: ""
                sb.append("""  <a href="${escapeXml(url)}"$targetAttr>""").append('\n')
            }
            sb.append("""  <g class="node" data-id="${escapeXml(node.id)}">""").append('\n')
            if (tooltip != null) {
                sb.append("""    <title>${escapeXml(tooltip)}</title>""").append('\n')
            }
            appendSvgNodeShape(sb, node.shape, r, fill, stroke, strokeW)
            val fontSize = theme.nodeFontSize.value
            val lines = wrapSvgLines(plainTextLabel(node.label), fontSize, NodeSizing.WRAPPING_WIDTH)
            appendMultilineText(
                sb = sb,
                indent = "    ",
                centerX = r.centerX,
                centerY = r.centerY,
                lines = lines,
                colorHex = textColor,
                fontSize = fontSize,
            )
            sb.append("""  </g>""").append('\n')
            if (url != null) {
                sb.append("""  </a>""").append('\n')
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [SequenceDiagram] and [SequenceDiagramLayout] to a standalone SVG string.
     */
    fun exportSequenceToSvg(
        diagram: SequenceDiagram,
        layout: SequenceDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()

        // 0. Participant `box` groupings
        for (b in layout.boxes) {
            val r = b.rect
            val fill = (b.box.color?.let(::parseCssColor) ?: Color.Transparent).toSvgHex()
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fill" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
            val lbl = b.box.label
            if (!lbl.isNullOrBlank()) {
                val lr = b.labelRect ?: DiagramRect(r.x, r.y, r.width, 22f)
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, plainTextLabel(lbl).split('\n'), theme.subgraphText.toSvgHex(), theme.subgraphFontSize.value)
            }
        }

        // 1. Background `rect` highlight blocks
        for (blk in layout.blocks) {
            if (blk.kind == SequenceBlockKind.Rect) {
                val r = blk.rect
                val fill = (parseCssColor(blk.label) ?: theme.subgraphFill.copy(alpha = 0.55f)).toSvgHex()
                sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fill"/>""").append('\n')
            }
        }

        // 2. Lifelines
        for (pp in layout.participants) {
            sb.append(
                """  <line x1="${fmt(pp.lifelineX)}" y1="${fmt(pp.lifelineTopY)}" x2="${fmt(pp.lifelineX)}" y2="${fmt(pp.lifelineBottomY)}" stroke="${theme.nodeBorder.toSvgHex()}" stroke-width="1" stroke-dasharray="4,3"/>""",
            ).append('\n')
        }

        // 3. Activation bars
        for (act in layout.activations) {
            val r = act.rect
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="${theme.subgraphFill.toSvgHex()}" stroke="${theme.nodeBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
        }

        // 4. Control-flow frames (`loop`, `alt`, `opt`, `par`, `critical`, `break`)
        for (blk in layout.blocks) {
            if (blk.kind == SequenceBlockKind.Rect) continue
            val r = blk.rect
            val borderHex = theme.nodeBorder.toSvgHex()
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="none" stroke="$borderHex" stroke-width="1.5" stroke-dasharray="4,3"/>""",
            ).append('\n')
            val tr = blk.tagRect
            val notch = 6f
            val tabPts = "${fmt(tr.x)},${fmt(tr.y)} ${fmt(tr.right)},${fmt(tr.y)} ${fmt(tr.right)},${fmt(tr.bottom - notch)} ${fmt(tr.right - notch)},${fmt(tr.bottom)} ${fmt(tr.x)},${fmt(tr.bottom)}"
            sb.append("""  <polygon points="$tabPts" fill="${theme.nodeFill.toSvgHex()}" stroke="$borderHex" stroke-width="1"/>""").append('\n')
            appendMultilineText(sb, "  ", tr.centerX, tr.centerY, listOf(plainTextLabel(blk.tag)), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            if (!blk.label.isNullOrBlank() && blk.labelRect != null) {
                appendMultilineText(sb, "  ", blk.labelRect.centerX, blk.labelRect.centerY, plainTextLabel("[${blk.label}]").split('\n'), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
            for (div in blk.dividers) {
                sb.append(
                    """  <line x1="${fmt(r.x)}" y1="${fmt(div.y)}" x2="${fmt(r.right)}" y2="${fmt(div.y)}" stroke="$borderHex" stroke-width="1.5" stroke-dasharray="4,3"/>""",
                ).append('\n')
                if (!div.label.isNullOrBlank() && div.labelRect != null) {
                    appendMultilineText(sb, "  ", div.labelRect.centerX, div.labelRect.centerY, plainTextLabel("[${div.label}]").split('\n'), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
                }
            }
        }

        // 5. Participant boxes / Actor stick figures (top & bottom)
        fun emitParticipantCard(pp: dev.maradona.layout.PositionedParticipant, r: DiagramRect) {
            val p = pp.participant
            val linkUrl = p.links.values.firstOrNull()?.takeIf { it.isNotBlank() }
            if (linkUrl != null) sb.append("""  <a href="${escapeXml(linkUrl)}">""").append('\n')
            sb.append("""  <g class="participant" data-id="${escapeXml(p.id)}">""").append('\n')
            val fillHex = theme.nodeFill.toSvgHex()
            val strokeHex = theme.nodeBorder.toSvgHex()
            val textHex = theme.nodeText.toSvgHex()
            if (p.kind == ParticipantKind.Actor) {
                val cx = r.centerX
                val headR = 9f
                val headCy = r.y + 6f + headR
                val neckY = headCy + headR
                val hipY = neckY + 14f
                val armY = neckY + 5f
                val footY = hipY + 11f
                val span = 11f
                sb.append("""    <circle cx="${fmt(cx)}" cy="${fmt(headCy)}" r="${fmt(headR)}" fill="$fillHex" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx)}" y1="${fmt(neckY)}" x2="${fmt(cx)}" y2="${fmt(hipY)}" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx - span)}" y1="${fmt(armY)}" x2="${fmt(cx + span)}" y2="${fmt(armY)}" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx)}" y1="${fmt(hipY)}" x2="${fmt(cx - span * 0.85f)}" y2="${fmt(footY)}" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx)}" y1="${fmt(hipY)}" x2="${fmt(cx + span * 0.85f)}" y2="${fmt(footY)}" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                appendMultilineText(sb, "    ", r.centerX, r.bottom - 8f, plainTextLabel(p.label).split('\n'), textHex, theme.nodeFontSize.value)
            } else {
                sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="$fillHex" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
                appendMultilineText(sb, "    ", r.centerX, r.centerY, plainTextLabel(p.label).split('\n'), textHex, theme.nodeFontSize.value)
            }
            sb.append("""  </g>""").append('\n')
            if (linkUrl != null) sb.append("""  </a>""").append('\n')
        }
        for (pp in layout.participants) {
            emitParticipantCard(pp, pp.topRect)
            pp.bottomRect?.let { emitParticipantCard(pp, it) }
        }

        // 6. Sticky notes
        for (n in layout.notes) {
            val r = n.rect
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="${theme.noteFill.toSvgHex()}" stroke="${theme.noteBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
            appendMultilineText(sb, "  ", r.centerX, r.centerY, plainTextLabel(n.note.text).split('\n'), theme.noteText.toSvgHex(), theme.edgeLabelFontSize.value)
        }

        // 7. Messages and arrowheads
        val edgeHex = theme.edge.toSvgHex()
        for (m in layout.messages) {
            val arrow = m.statement.arrow
            val endMarker = when (arrow.head) {
                SequenceHead.Filled -> SvgMarker.FilledArrow
                SequenceHead.Open -> SvgMarker.None
                SequenceHead.Cross -> SvgMarker.Cross
                SequenceHead.Async -> SvgMarker.OpenArrow
            }
            val startMarker = if (arrow.bidirectional) endMarker else SvgMarker.None
            val pts = if (m.isSelf) {
                val loopW = 36f
                val loopH = 22f
                listOf(
                    DiagramPoint(m.startX, m.lineY),
                    DiagramPoint(m.startX + loopW, m.lineY),
                    DiagramPoint(m.startX + loopW, m.lineY + loopH),
                    DiagramPoint(m.startX, m.lineY + loopH),
                )
            } else {
                listOf(DiagramPoint(m.startX, m.lineY), DiagramPoint(m.stopX, m.lineY))
            }
            val dashAttr = if (arrow.dashed) """ stroke-dasharray="4,3"""" else ""
            sb.append("""  <path class="message" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$edgeHex" stroke-width="1.5"$dashAttr/>""").append('\n')
            if (startMarker != SvgMarker.None) {
                appendSvgMarker(sb, pts.first(), pts[1], startMarker, edgeHex, bgHex)
            }
            if (endMarker != SvgMarker.None) {
                appendSvgMarker(sb, pts.last(), pts[pts.size - 2], endMarker, edgeHex, bgHex)
            }
            if (m.sequenceNumber != null) {
                val dir = if (m.stopX >= m.startX) 1f else -1f
                val badgeX = m.startX + dir * 8f
                sb.append("""  <circle cx="${fmt(badgeX)}" cy="${fmt(m.lineY)}" r="7" fill="$edgeHex"/>""").append('\n')
                sb.append("""  <text x="${fmt(badgeX)}" y="${fmt(m.lineY + 3.5f)}" text-anchor="middle" fill="$bgHex" font-family="sans-serif" font-size="9" font-weight="600">${m.sequenceNumber}</text>""").append('\n')
            }
            if (m.statement.text.isNotBlank()) {
                val msgLabel = if (m.sequenceNumber != null) "${m.sequenceNumber}. ${m.statement.text}" else m.statement.text
                appendMultilineText(sb, "  ", m.labelRect.centerX, m.labelRect.centerY, plainTextLabel(msgLabel).split('\n'), theme.nodeText.toSvgHex(), theme.nodeFontSize.value)
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [ClassDiagram] and [ClassDiagramLayout] to a standalone SVG string.
     */
    fun exportClassToSvg(
        diagram: ClassDiagram,
        layout: ClassDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val edgeHex = theme.edge.toSvgHex()

        // 1. Namespace cluster boxes
        for (ns in layout.namespaces) {
            val r = ns.rect
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.subgraphFill.toSvgHex()}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
            if (ns.namespace.label.isNotBlank()) {
                appendMultilineText(sb, "  ", r.centerX, r.y + 16f, plainTextLabel(ns.namespace.label).split('\n'), theme.subgraphText.toSvgHex(), theme.subgraphFontSize.value)
            }
        }

        // 2. Dotted note connectors
        for (n in layout.notes) {
            if (n.connectorPoints.size >= 2) {
                sb.append(
                    """  <path d="${buildSvgEdgePath(n.connectorPoints)}" fill="none" stroke="$edgeHex" stroke-width="1" stroke-dasharray="3,3"/>""",
                ).append('\n')
            }
        }

        // 3. Relations and UML markers
        for (r in layout.relations) {
            val pts = r.points
            if (pts.size < 2) continue
            val dashAttr = if (r.relation.dashed) """ stroke-dasharray="4,3"""" else ""
            sb.append("""  <path class="relation" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$edgeHex" stroke-width="1.3"$dashAttr/>""").append('\n')
            val startFrom = pts.drop(1).firstOrNull { it != pts.first() } ?: pts[1]
            val endFrom = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
            appendSvgMarker(sb, pts.first(), startFrom, mapClassEndMarker(r.relation.startEnd), edgeHex, bgHex)
            appendSvgMarker(sb, pts.last(), endFrom, mapClassEndMarker(r.relation.endEnd), edgeHex, bgHex)

            val lr = r.labelRect
            val lbl = r.relation.label
            if (lr != null && !lbl.isNullOrBlank()) {
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""").append('\n')
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, plainTextLabel(lbl).split('\n'), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
            val fc = r.relation.fromCardinality
            val fcr = r.fromCardinalityRect
            if (!fc.isNullOrBlank() && fcr != null) {
                appendMultilineText(sb, "  ", fcr.centerX, fcr.centerY, plainTextLabel(fc).split('\n'), theme.edgeLabelText.toSvgHex(), theme.captionFontSize.value)
            }
            val tc = r.relation.toCardinality
            val tcr = r.toCardinalityRect
            if (!tc.isNullOrBlank() && tcr != null) {
                appendMultilineText(sb, "  ", tcr.centerX, tcr.centerY, plainTextLabel(tc).split('\n'), theme.edgeLabelText.toSvgHex(), theme.captionFontSize.value)
            }
        }

        // 4. Class boxes, compartment dividers, and member rows
        for (pc in layout.classes) {
            val c = pc.node
            val r = pc.rect
            val fillHex = (parseCssColor(c.style?.fill) ?: theme.nodeFill).toSvgHex()
            val strokeHex = (parseCssColor(c.style?.stroke) ?: theme.nodeBorder).toSvgHex()
            val textHex = (parseCssColor(c.style?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = c.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1f
            val url = c.action?.url?.takeIf { it.isNotBlank() }
            val tooltip = c.action?.tooltip?.takeIf { it.isNotBlank() }

            if (url != null) {
                val targetAttr = c.action.target?.let { """ target="${escapeXml(it)}"""" } ?: ""
                sb.append("""  <a href="${escapeXml(url)}"$targetAttr>""").append('\n')
            }
            sb.append("""  <g class="class-node" data-id="${escapeXml(c.id)}">""").append('\n')
            if (tooltip != null) {
                sb.append("""    <title>${escapeXml(tooltip)}</title>""").append('\n')
            }
            sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(strokeW)}"/>""").append('\n')

            val hdrLines = buildList {
                for (ann in c.annotations) {
                    if (ann.isNotBlank()) add("«${plainTextLabel(ann)}»")
                }
                addAll(plainTextLabel(c.displayTitle).split('\n'))
            }
            appendMultilineText(sb, "    ", r.centerX, r.y + pc.headerHeight / 2f, hdrLines, textHex, theme.nodeFontSize.value)

            val showCompartments = !diagram.hideEmptyMembersBox || c.attributes.isNotEmpty() || c.methods.isNotEmpty()
            if (showCompartments) {
                val div1Y = r.y + pc.headerHeight
                val div2Y = div1Y + pc.attributesHeight
                sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(div1Y)}" x2="${fmt(r.right)}" y2="${fmt(div1Y)}" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
                sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(div2Y)}" x2="${fmt(r.right)}" y2="${fmt(div2Y)}" stroke="$strokeHex" stroke-width="1"/>""").append('\n')

                val memberFont = theme.edgeLabelFontSize.value
                val lineH = memberFont * 1.4f
                for ((idx, attr) in c.attributes.withIndex()) {
                    val ay = div1Y + 14f + idx * lineH
                    val styleAttr = when (attr.classifier) {
                        ClassClassifier.Abstract -> """ font-style="italic""""
                        ClassClassifier.Static -> """ text-decoration="underline""""
                        else -> ""
                    }
                    sb.append("""    <text x="${fmt(r.x + 10f)}" y="${fmt(ay)}" text-anchor="start" fill="$textHex" font-family="sans-serif" font-size="${fmt(memberFont)}"$styleAttr>${escapeXml(attr.displayText)}</text>""").append('\n')
                }
                for ((idx, meth) in c.methods.withIndex()) {
                    val my = div2Y + 14f + idx * lineH
                    val styleAttr = when (meth.classifier) {
                        ClassClassifier.Abstract -> """ font-style="italic""""
                        ClassClassifier.Static -> """ text-decoration="underline""""
                        else -> ""
                    }
                    sb.append("""    <text x="${fmt(r.x + 10f)}" y="${fmt(my)}" text-anchor="start" fill="$textHex" font-family="sans-serif" font-size="${fmt(memberFont)}"$styleAttr>${escapeXml(meth.displayText)}</text>""").append('\n')
                }
            }
            sb.append("""  </g>""").append('\n')
            if (url != null) {
                sb.append("""  </a>""").append('\n')
            }
        }

        // 5. Sticky notes
        for (n in layout.notes) {
            val r = n.rect
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="${theme.noteFill.toSvgHex()}" stroke="${theme.noteBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
            appendMultilineText(sb, "  ", r.centerX, r.centerY, plainTextLabel(n.note.text).split('\n'), theme.noteText.toSvgHex(), theme.edgeLabelFontSize.value)
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [StateDiagram] and [StateDiagramLayout] to a standalone SVG string.
     */
    fun exportStateToSvg(
        diagram: StateDiagram,
        layout: StateDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val edgeHex = theme.edge.toSvgHex()
        val compositeIds = diagram.states
            .filter { s ->
                s.kind == StateKind.Composite ||
                    diagram.states.any { it.parentId == s.id } ||
                    diagram.notes.any { it.parentId == s.id }
            }
            .map { it.id }
            .toSet()

        // 1. Composite state containers & concurrent region dividers
        for (ps in layout.states) {
            if (ps.state.id !in compositeIds) continue
            val r = ps.rect
            val fillHex = (parseCssColor(ps.state.style?.fill) ?: theme.subgraphFill).toSvgHex()
            val strokeHex = (parseCssColor(ps.state.style?.stroke) ?: theme.subgraphBorder).toSvgHex()
            val textHex = (parseCssColor(ps.state.style?.textColor) ?: theme.subgraphText).toSvgHex()
            val strokeW = ps.state.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1.25f
            sb.append("""  <g class="composite-state" data-id="${escapeXml(ps.state.id)}">""").append('\n')
            sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="8" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(strokeW)}"/>""").append('\n')
            sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(r.y + ps.headerHeight)}" x2="${fmt(r.right)}" y2="${fmt(r.y + ps.headerHeight)}" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
            appendMultilineText(sb, "    ", r.centerX, r.y + ps.headerHeight / 2f, plainTextLabel(ps.state.label).split('\n'), textHex, theme.subgraphFontSize.value)
            for ((a, b) in ps.regionDividers) {
                sb.append("""    <line class="region-divider" x1="${fmt(a.x)}" y1="${fmt(a.y)}" x2="${fmt(b.x)}" y2="${fmt(b.y)}" stroke="$strokeHex" stroke-width="1" stroke-dasharray="5,4"/>""").append('\n')
            }
            sb.append("""  </g>""").append('\n')
        }

        // 2. Dotted note connectors
        for (n in layout.notes) {
            if (n.connectorPoints.size >= 2) {
                sb.append("""  <path d="${buildSvgEdgePath(n.connectorPoints)}" fill="none" stroke="$edgeHex" stroke-width="1" stroke-dasharray="3,3"/>""").append('\n')
            }
        }

        // 3. Transitions
        for (tr in layout.transitions) {
            val pts = tr.points
            if (pts.size < 2) continue
            val trEdgeHex = (parseCssColor(tr.transition.style?.stroke) ?: theme.edge).toSvgHex()
            val trStrokeW = tr.transition.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1.25f
            sb.append("""  <path class="transition" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$trEdgeHex" stroke-width="${fmt(trStrokeW)}"/>""").append('\n')
            val endFrom = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
            appendSvgMarker(sb, pts.last(), endFrom, SvgMarker.FilledArrow, trEdgeHex, bgHex)
            val lr = tr.labelRect
            val lbl = tr.transition.label
            if (lr != null && !lbl.isNullOrBlank()) {
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""").append('\n')
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, plainTextLabel(lbl).split('\n'), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        // 4. Leaf state nodes (Start, End, Choice, Fork, Join, Normal)
        for (ps in layout.states) {
            if (ps.state.id in compositeIds) continue
            val s = ps.state
            val r = ps.rect
            val fillHex = (parseCssColor(s.style?.fill) ?: theme.nodeFill).toSvgHex()
            val strokeHex = (parseCssColor(s.style?.stroke) ?: theme.nodeBorder).toSvgHex()
            val textHex = (parseCssColor(s.style?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = s.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1.25f

            sb.append("""  <g class="state-node" data-id="${escapeXml(s.id)}" data-kind="${s.kind.name.lowercase()}">""").append('\n')
            when (s.kind) {
                StateKind.Start -> {
                    val rad = min(r.width, r.height) / 2f
                    sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="${fmt(rad)}" fill="$strokeHex" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
                }
                StateKind.End -> {
                    val rad = min(r.width, r.height) / 2f
                    sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="${fmt(rad)}" fill="$bgHex" stroke="$strokeHex" stroke-width="1.75"/>""").append('\n')
                    sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="${fmt(rad * 0.52f)}" fill="$strokeHex"/>""").append('\n')
                }
                StateKind.Choice -> {
                    appendSvgNodeShape(sb, NodeShape.Diamond, r, fillHex, strokeHex, strokeW)
                }
                StateKind.Fork, StateKind.Join -> {
                    val barFill = (parseCssColor(s.style?.fill) ?: theme.nodeBorder).toSvgHex()
                    sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="4" fill="$barFill" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
                }
                StateKind.Normal, StateKind.Composite -> {
                    sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="8" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(strokeW)}"/>""").append('\n')
                    if (s.descriptions.isNotEmpty()) {
                        val divY = r.y + ps.headerHeight
                        sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(divY)}" x2="${fmt(r.right)}" y2="${fmt(divY)}" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
                        appendMultilineText(sb, "    ", r.centerX, r.y + ps.headerHeight / 2f, plainTextLabel(s.label).split('\n'), textHex, theme.nodeFontSize.value)
                        val descLines = s.descriptions.flatMap { plainTextLabel(it).split('\n') }
                        appendMultilineText(sb, "    ", r.centerX, divY + (r.height - ps.headerHeight) / 2f, descLines, textHex, theme.edgeLabelFontSize.value)
                    } else {
                        appendMultilineText(sb, "    ", r.centerX, r.centerY, plainTextLabel(s.label).split('\n'), textHex, theme.nodeFontSize.value)
                    }
                }
            }
            sb.append("""  </g>""").append('\n')
        }

        // 5. Sticky notes
        for (n in layout.notes) {
            val r = n.rect
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="${theme.noteFill.toSvgHex()}" stroke="${theme.noteBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
            appendMultilineText(sb, "  ", r.centerX, r.centerY, plainTextLabel(n.note.text).split('\n'), theme.noteText.toSvgHex(), theme.edgeLabelFontSize.value)
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [ErDiagram] and [ErDiagramLayout] to a standalone SVG string.
     */
    fun exportErToSvg(
        diagram: ErDiagram,
        layout: ErDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val edgeHex = theme.edge.toSvgHex()

        // 1. Subgraph containers
        for (sg in layout.subgraphs) {
            val r = sg.rect
            val fillHex = (parseCssColor(sg.subgraph.style?.fill) ?: theme.subgraphFill).toSvgHex()
            val strokeHex = (parseCssColor(sg.subgraph.style?.stroke) ?: theme.subgraphBorder).toSvgHex()
            val textHex = (parseCssColor(sg.subgraph.style?.textColor) ?: theme.subgraphText).toSvgHex()
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="$fillHex" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
            if (sg.subgraph.label.isNotBlank()) {
                appendMultilineText(sb, "  ", r.centerX, r.y + sg.headerHeight / 2f, plainTextLabel(sg.subgraph.label).split('\n'), textHex, theme.subgraphFontSize.value)
            }
        }

        // 2. Relationships and crow's-foot cardinality markers
        for (pr in layout.relationships) {
            val pts = pr.points
            if (pts.size < 2) continue
            val dashAttr = if (!pr.relationship.identifying) """ stroke-dasharray="5,4"""" else ""
            sb.append("""  <path class="er-relationship" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$edgeHex" stroke-width="1.35"$dashAttr/>""").append('\n')
            val startToward = pts.drop(1).firstOrNull { it != pts.first() } ?: pts[1]
            val endToward = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
            appendSvgMarker(sb, pts.first(), startToward, mapErCardinalityMarker(pr.relationship.fromCardinality), edgeHex, bgHex)
            appendSvgMarker(sb, pts.last(), endToward, mapErCardinalityMarker(pr.relationship.toCardinality), edgeHex, bgHex)
            val lr = pr.labelRect
            val lbl = pr.relationship.label
            if (lr != null && !lbl.isNullOrBlank()) {
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""").append('\n')
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, plainTextLabel(lbl).split('\n'), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        // 3. Entities and attribute tables
        for (pe in layout.entities) {
            val e = pe.entity
            val r = pe.rect
            val fillHex = (parseCssColor(e.style?.fill) ?: theme.nodeFill).toSvgHex()
            val strokeHex = (parseCssColor(e.style?.stroke) ?: theme.nodeBorder).toSvgHex()
            val textHex = (parseCssColor(e.style?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = e.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1.25f

            sb.append("""  <g class="er-entity" data-id="${escapeXml(e.id)}">""").append('\n')
            sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(strokeW)}"/>""").append('\n')
            if (e.attributes.isNotEmpty()) {
                val hdrH = pe.headerHeight
                sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(hdrH)}" fill="${theme.subgraphFill.copy(alpha = 0.55f).toSvgHex()}"/>""").append('\n')
                sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(r.y + hdrH)}" x2="${fmt(r.right)}" y2="${fmt(r.y + hdrH)}" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
                appendMultilineText(sb, "    ", r.centerX, r.y + hdrH / 2f, plainTextLabel(e.label).split('\n'), textHex, theme.nodeFontSize.value)

                var rowY = r.y + hdrH
                val attrFont = theme.edgeLabelFontSize.value
                for ((rIdx, attr) in e.attributes.withIndex()) {
                    val rH = pe.rowHeights.getOrElse(rIdx) { 22f }
                    if (rIdx % 2 == 1) {
                        sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(rowY)}" width="${fmt(r.width)}" height="${fmt(rH)}" fill="${theme.subgraphFill.copy(alpha = 0.25f).toSvgHex()}"/>""").append('\n')
                    }
                    val rowText = listOfNotNull(
                        attr.type,
                        attr.name,
                        attr.keys.takeIf { it.isNotEmpty() }?.joinToString(","),
                        attr.comment?.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
                    ).joinToString(" ")
                    sb.append(
                        """    <text x="${fmt(r.x + 8f)}" y="${fmt(rowY + rH / 2f + attrFont * 0.35f)}" text-anchor="start" fill="$textHex" font-family="sans-serif" font-size="${fmt(attrFont)}">${escapeXml(plainTextLabel(rowText))}</text>""",
                    ).append('\n')
                    rowY += rH
                    if (rIdx < e.attributes.lastIndex) {
                        sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(rowY)}" x2="${fmt(r.right)}" y2="${fmt(rowY)}" stroke="$strokeHex" stroke-width="0.75" stroke-opacity="0.65"/>""").append('\n')
                    }
                }
            } else {
                appendMultilineText(sb, "    ", r.centerX, r.centerY, plainTextLabel(e.label).split('\n'), textHex, theme.nodeFontSize.value)
            }
            sb.append("""  </g>""").append('\n')
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [RequirementDiagram] and [RequirementDiagramLayout] to a standalone SVG string.
     */
    fun exportRequirementToSvg(
        diagram: RequirementDiagram,
        layout: RequirementDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val edgeHex = theme.edge.toSvgHex()

        // 1. Requirement & Element boxes
        for (pi in layout.items) {
            val r = pi.rect
            val st = pi.item.style
            val customFill = parseCssColor(st?.fill)
            val bodyFillHex = (customFill ?: theme.nodeFill).toSvgHex()
            val headerFillHex = (customFill ?: if (pi.item is ElementItem) theme.seriesFill(2) else theme.accentFill).toSvgHex()
            val strokeHex = (parseCssColor(st?.stroke) ?: theme.nodeBorder).toSvgHex()
            val textHex = (parseCssColor(st?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = st?.strokeWidth ?: 1.4f
            val docRefUrl = (pi.item as? ElementItem)?.docRef?.takeIf {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            }

            if (docRefUrl != null) sb.append("""  <a href="${escapeXml(docRefUrl)}">""").append('\n')
            sb.append("""  <g class="requirement-item" data-id="${escapeXml(pi.item.name)}">""").append('\n')
            sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$bodyFillHex" stroke="$strokeHex" stroke-width="${fmt(strokeW)}"/>""").append('\n')
            val hdrH = pi.headerHeight.coerceAtMost(r.height)
            sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(hdrH)}" fill="$headerFillHex"/>""").append('\n')
            val hdrLines = listOf("<<${pi.item.stereotype}>>") + plainTextLabel(pi.item.name).split('\n')
            if (pi.bodyLineHeights.isNotEmpty() && pi.headerHeight < r.height) {
                val divY = r.y + pi.headerHeight
                sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(divY)}" x2="${fmt(r.right)}" y2="${fmt(divY)}" stroke="$strokeHex" stroke-width="1.1"/>""").append('\n')
                appendMultilineText(sb, "    ", r.centerX, r.y + hdrH / 2f, hdrLines, textHex, theme.nodeFontSize.value)
                val bodyLines = pi.item.bodyLines.flatMap { plainTextLabel(it).split('\n') }
                appendMultilineText(sb, "    ", r.centerX, divY + (r.height - hdrH) / 2f, bodyLines, textHex, theme.edgeLabelFontSize.value)
            } else {
                appendMultilineText(sb, "    ", r.centerX, r.centerY, hdrLines, textHex, theme.nodeFontSize.value)
            }
            sb.append("""  </g>""").append('\n')
            if (docRefUrl != null) sb.append("""  </a>""").append('\n')
        }

        // 2. Relationship edges, `⊕` containment markers at source, and open arrowheads at target
        for (pr in layout.relations) {
            val pts = pr.points
            if (pts.size < 2) continue
            val dashAttr = if (pr.relation.isContains) "" else """ stroke-dasharray="5,4""""
            sb.append("""  <path class="requirement-rel" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$edgeHex" stroke-width="1.5"$dashAttr/>""").append('\n')
            if (pr.relation.isContains) {
                val startToward = pts.drop(1).firstOrNull { it != pts.first() } ?: pts[1]
                appendSvgMarker(sb, pts.first(), startToward, SvgMarker.ContainsCircleCross, edgeHex, bgHex)
            } else {
                val endFrom = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
                appendSvgMarker(sb, pts.last(), endFrom, SvgMarker.OpenArrow, edgeHex, bgHex)
            }
            val lr = pr.labelRect
            if (lr != null && pr.relation.label.isNotBlank()) {
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="4" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""").append('\n')
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, plainTextLabel(pr.relation.label).split('\n'), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [C4Diagram] and [C4DiagramLayout] to a standalone SVG string.
     */
    fun exportC4ToSvg(
        diagram: C4Diagram,
        layout: C4DiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val p = theme.c4Palette

        // 1. Boundaries
        for (pb in layout.boundaries) {
            val r = pb.rect
            val bFillHex = (parseCssColor(pb.boundary.style?.bgColor) ?: theme.subgraphFill).toSvgHex()
            val bStrokeHex = (parseCssColor(pb.boundary.style?.borderColor) ?: theme.subgraphBorder).toSvgHex()
            val bTextHex = (parseCssColor(pb.boundary.style?.fontColor) ?: theme.subgraphText).toSvgHex()
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="$bFillHex" stroke="$bStrokeHex" stroke-width="1.3" stroke-dasharray="6,4"/>""").append('\n')
            val hdr = listOfNotNull(
                plainTextLabel(pb.boundary.label),
                pb.boundary.type.takeIf { it.isNotBlank() }?.let { "[$it]" },
            ).joinToString(" ")
            appendMultilineText(sb, "  ", pb.headerRect.centerX, pb.headerRect.centerY, listOf(hdr), bTextHex, theme.subgraphFontSize.value)
        }

        // 2. Elements (Person, Database, Queue, System, Container, Component, External)
        for (pe in layout.elements) {
            val el = pe.element
            val r = pe.rect
            val (defFill, defBorder, defText) = when {
                el.kind.isExternal -> Triple(p.externalFill, p.externalBorder, p.externalText)
                el.kind.isPerson -> Triple(p.personFill, p.personBorder, p.personText)
                el.kind == C4ElementKind.CONTAINER ||
                    el.kind == C4ElementKind.CONTAINER_DB ||
                    el.kind == C4ElementKind.CONTAINER_QUEUE ->
                    Triple(p.containerFill, p.containerBorder, p.containerText)
                el.kind == C4ElementKind.COMPONENT ||
                    el.kind == C4ElementKind.COMPONENT_DB ||
                    el.kind == C4ElementKind.COMPONENT_QUEUE ->
                    Triple(p.componentFill, p.componentBorder, p.componentText)
                else -> Triple(p.systemFill, p.systemBorder, p.systemText)
            }
            val fillHex = (parseCssColor(el.style?.bgColor) ?: defFill).toSvgHex()
            val strokeHex = (parseCssColor(el.style?.borderColor) ?: defBorder).toSvgHex()
            val textHex = (parseCssColor(el.style?.fontColor) ?: defText).toSvgHex()
            val url = el.link?.takeIf { it.isNotBlank() }
            val tooltip = el.description?.takeIf { it.isNotBlank() }

            if (url != null) sb.append("""  <a href="${escapeXml(url)}">""").append('\n')
            sb.append("""  <g class="c4-element" data-id="${escapeXml(el.id)}" data-kind="${el.kind.name.lowercase()}">""").append('\n')
            if (tooltip != null) sb.append("""    <title>${escapeXml(tooltip)}</title>""").append('\n')

            when {
                el.kind.isPerson -> {
                    val headR = 11f
                    val bodyTop = r.y + headR * 1.55f
                    val bodyH = max(20f, r.bottom - bodyTop)
                    sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(bodyTop)}" width="${fmt(r.width)}" height="${fmt(bodyH)}" rx="12" fill="$fillHex" stroke="$strokeHex" stroke-width="1.6"/>""").append('\n')
                    sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.y + headR + 2f)}" r="${fmt(headR)}" fill="$fillHex" stroke="$strokeHex" stroke-width="1.6"/>""").append('\n')
                }
                el.kind.isDatabase -> {
                    appendSvgNodeShape(sb, NodeShape.Cylinder, r, fillHex, strokeHex, 1.6f)
                }
                el.kind.isQueue -> {
                    val capRx = (r.height * 0.22f).coerceIn(8f, 18f)
                    appendSvgNodeShape(sb, NodeShape.Stadium, r, fillHex, strokeHex, 1.6f)
                    sb.append("""    <ellipse cx="${fmt(r.right - capRx)}" cy="${fmt(r.centerY)}" rx="${fmt(capRx)}" ry="${fmt(r.height / 2f)}" fill="none" stroke="$strokeHex" stroke-width="1.3"/>""").append('\n')
                }
                else -> {
                    appendSvgNodeShape(sb, NodeShape.RoundedRect, r, fillHex, strokeHex, 1.6f)
                }
            }

            val displayLabel = if (!el.sprite.isNullOrBlank()) {
                "${resolveMermaidIconGlyph(el.sprite)} ${el.label}"
            } else {
                el.label
            }
            val cardLines = buildList {
                add(el.typeHeaderText)
                addAll(plainTextLabel(displayLabel).split('\n'))
                if (!el.description.isNullOrBlank()) {
                    addAll(plainTextLabel(el.description).split('\n'))
                }
            }
            appendMultilineText(sb, "    ", r.centerX, r.centerY, cardLines, textHex, theme.nodeFontSize.value)
            sb.append("""  </g>""").append('\n')
            if (url != null) sb.append("""  </a>""").append('\n')
        }

        // 3. Relationships (including RelIndex prefixes)
        for (pr in layout.relationships) {
            val pts = pr.points
            if (pts.size < 2) continue
            val lineHex = (parseCssColor(pr.relationship.style?.lineColor) ?: theme.edge).toSvgHex()
            val textHex = (parseCssColor(pr.relationship.style?.textColor) ?: theme.edgeLabelText).toSvgHex()
            sb.append("""  <path class="c4-rel" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$lineHex" stroke-width="1.5" stroke-dasharray="5,4"/>""").append('\n')
            if (pr.startArrow) {
                val startToward = pts.drop(1).firstOrNull { it != pts.first() } ?: pts[1]
                appendSvgMarker(sb, pts.first(), startToward, SvgMarker.FilledArrow, lineHex, bgHex)
            }
            if (pr.endArrow) {
                val endFrom = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
                appendSvgMarker(sb, pts.last(), endFrom, SvgMarker.FilledArrow, lineHex, bgHex)
            }
            val lr = pr.labelRect
            val indexedLabel = if (!pr.relationship.index.isNullOrBlank()) {
                "${pr.relationship.index}: ${pr.relationship.label}"
            } else {
                pr.relationship.label
            }
            val relLines = buildList {
                if (indexedLabel.isNotBlank()) addAll(plainTextLabel(indexedLabel).split('\n'))
                if (!pr.relationship.technology.isNullOrBlank()) add("[${plainTextLabel(pr.relationship.technology)}]")
            }
            if (lr != null && relLines.isNotEmpty()) {
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="4" fill="${theme.background.copy(alpha = 0.92f).toSvgHex()}"/>""").append('\n')
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, relLines, textHex, theme.edgeLabelFontSize.value)
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [ArchitectureDiagram] and [ArchitectureDiagramLayout] to a standalone SVG string.
     */
    fun exportArchitectureToSvg(
        diagram: ArchitectureDiagram,
        layout: ArchitectureDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val edgeHex = theme.edge.toSvgHex()

        // 1. Groups
        for (pg in layout.groups) {
            val r = pg.rect
            sb.append(
                """  <rect class="arch-group" x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="8" fill="${theme.subgraphFill.toSvgHex()}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1.4" stroke-dasharray="6,4"/>""",
            ).append('\n')
            pg.iconRect?.let { ir ->
                sb.append("""  <rect x="${fmt(ir.x)}" y="${fmt(ir.y)}" width="${fmt(ir.width)}" height="${fmt(ir.height)}" rx="4" fill="${theme.accentBorder.toSvgHex()}"/>""").append('\n')
            }
            val grpTitle = pg.group.title ?: pg.group.id
            val lr = pg.labelRect
            if (lr != null) {
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, plainTextLabel(grpTitle).split('\n'), theme.subgraphText.toSvgHex(), theme.subgraphFontSize.value)
            } else {
                appendMultilineText(sb, "  ", r.centerX, r.y + 16f, plainTextLabel(grpTitle).split('\n'), theme.subgraphText.toSvgHex(), theme.subgraphFontSize.value)
            }
        }

        // 2. Edges
        for (pe in layout.edges) {
            val pts = pe.points
            if (pts.size < 2) continue
            sb.append("""  <path class="arch-edge" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$edgeHex" stroke-width="1.8"/>""").append('\n')
            if (pe.edge.lhsInto) {
                val startToward = pts.drop(1).firstOrNull { it != pts.first() } ?: pts[1]
                appendSvgMarker(sb, pts.first(), startToward, SvgMarker.FilledArrow, edgeHex, bgHex)
            }
            if (pe.edge.rhsInto) {
                val endFrom = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
                appendSvgMarker(sb, pts.last(), endFrom, SvgMarker.FilledArrow, edgeHex, bgHex)
            }
            val lr = pe.labelRect
            val lbl = pe.edge.title
            if (lr != null && !lbl.isNullOrBlank()) {
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""").append('\n')
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, plainTextLabel(lbl).split('\n'), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        // 3. Services
        for (ps in layout.services) {
            val ir = ps.iconRect
            val titleText = ps.service.title ?: ps.service.id
            val iconGlyph = ps.service.iconText?.takeIf { it.isNotBlank() }
                ?: ps.service.icon?.let(::resolveMermaidIconGlyph).orEmpty()
            sb.append("""  <g class="arch-service" data-id="${escapeXml(ps.service.id)}">""").append('\n')
            sb.append("""    <rect x="${fmt(ir.x)}" y="${fmt(ir.y)}" width="${fmt(ir.width)}" height="${fmt(ir.height)}" rx="8" fill="${theme.accentBorder.toSvgHex()}" stroke="${theme.nodeBorder.toSvgHex()}" stroke-width="1.2"/>""").append('\n')
            if (iconGlyph.isNotBlank()) {
                appendMultilineText(sb, "    ", ir.centerX, ir.centerY, plainTextLabel(iconGlyph).split('\n'), theme.accentText.toSvgHex(), theme.nodeFontSize.value)
            }
            val lr = ps.labelRect
            if (lr != null) {
                appendMultilineText(sb, "    ", lr.centerX, lr.centerY, plainTextLabel(titleText).split('\n'), theme.nodeText.toSvgHex(), theme.nodeFontSize.value)
            } else if (iconGlyph.isBlank()) {
                appendMultilineText(sb, "    ", ir.centerX, ir.centerY, plainTextLabel(titleText).split('\n'), theme.accentText.toSvgHex(), theme.nodeFontSize.value)
            }
            sb.append("""  </g>""").append('\n')
        }

        // 4. Junctions
        for (pj in layout.junctions) {
            val r = min(pj.rect.width, pj.rect.height) / 2f
            sb.append("""  <circle class="arch-junction" cx="${fmt(pj.center.x)}" cy="${fmt(pj.center.y)}" r="${fmt(r)}" fill="$edgeHex"/>""").append('\n')
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [ZenUmlDiagram] and [ZenUmlDiagramLayout] to a standalone SVG string.
     */
    fun exportZenUmlToSvg(
        diagram: ZenUmlDiagram,
        layout: ZenUmlDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val edgeHex = theme.edge.toSvgHex()

        // 1. Participant groups
        for (pg in layout.groups) {
            val r = pg.rect
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.subgraphFill.copy(alpha = 0.55f).toSvgHex()}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1.2"/>""",
            ).append('\n')
            appendMultilineText(sb, "  ", pg.headerRect.centerX, pg.headerRect.centerY, plainTextLabel(pg.group.name).split('\n'), theme.subgraphText.toSvgHex(), theme.subgraphFontSize.value)
        }

        // 2. Lifelines
        for (pp in layout.participants) {
            sb.append(
                """  <line x1="${fmt(pp.lifelineX)}" y1="${fmt(pp.lifelineTopY)}" x2="${fmt(pp.lifelineX)}" y2="${fmt(pp.lifelineBottomY)}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1.2" stroke-dasharray="6,5"/>""",
            ).append('\n')
        }

        // 3. Control-flow frames (`if`/`else`, `while`/`loop`, `opt`, `par`, `try`/`catch`/`finally`)
        for (blk in layout.blocks) {
            val r = blk.rect
            val borderHex = theme.nodeBorder.toSvgHex()
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="4" fill="none" stroke="$borderHex" stroke-width="1.3"/>""").append('\n')
            val tr = blk.tabRect
            val notch = 6f
            val tabPts = "${fmt(tr.x)},${fmt(tr.y)} ${fmt(tr.right)},${fmt(tr.y)} ${fmt(tr.right)},${fmt(tr.bottom - notch)} ${fmt(tr.right - notch)},${fmt(tr.bottom)} ${fmt(tr.x)},${fmt(tr.bottom)}"
            sb.append("""  <polygon points="$tabPts" fill="${theme.subgraphFill.toSvgHex()}" stroke="$borderHex" stroke-width="1"/>""").append('\n')
            appendMultilineText(sb, "  ", tr.centerX, tr.centerY, listOf(plainTextLabel(blk.keyword)), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            if (!blk.condition.isNullOrBlank() && blk.conditionRect != null) {
                appendMultilineText(sb, "  ", blk.conditionRect.centerX, blk.conditionRect.centerY, plainTextLabel("[${blk.condition}]").split('\n'), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
            for (div in blk.dividers) {
                sb.append("""  <line x1="${fmt(r.x)}" y1="${fmt(div.y)}" x2="${fmt(r.right)}" y2="${fmt(div.y)}" stroke="$borderHex" stroke-width="1.2" stroke-dasharray="4,4"/>""").append('\n')
                appendMultilineText(sb, "  ", div.labelRect.centerX, div.labelRect.centerY, plainTextLabel(div.label).split('\n'), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        // 4. Activation bars
        for (act in layout.activations) {
            val r = act.rect
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="${theme.accentFill.toSvgHex()}" stroke="${theme.accentBorder.toSvgHex()}" stroke-width="1"/>""").append('\n')
        }

        // 5. Participant headers (top and optional mirrored bottom)
        fun zenUmlParticipantLines(p: ZenUmlParticipant): List<String> {
            val stereo = p.rawAnnotation ?: when (p.stereotype) {
                ZenUmlParticipantStereotype.DEFAULT -> null
                ZenUmlParticipantStereotype.ACTOR -> "Actor"
                ZenUmlParticipantStereotype.BOUNDARY -> "Boundary"
                ZenUmlParticipantStereotype.CONTROL -> "Control"
                ZenUmlParticipantStereotype.ENTITY -> "Entity"
                ZenUmlParticipantStereotype.DATABASE -> "Database"
                ZenUmlParticipantStereotype.COLLECTIONS -> "Collections"
                ZenUmlParticipantStereotype.QUEUE -> "Queue"
            }
            return buildList {
                if (!stereo.isNullOrBlank()) add("«$stereo»")
                addAll(plainTextLabel(p.label).split('\n'))
            }
        }
        for (pp in layout.participants) {
            val lines = zenUmlParticipantLines(pp.participant)
            for (r in listOfNotNull(pp.topRect, pp.bottomRect)) {
                sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.nodeFill.toSvgHex()}" stroke="${theme.nodeBorder.toSvgHex()}" stroke-width="1.6"/>""").append('\n')
                appendMultilineText(sb, "  ", r.centerX, r.centerY, lines, theme.nodeText.toSvgHex(), theme.nodeFontSize.value)
            }
        }

        // 6. Messages and inline comments
        for (pm in layout.messages) {
            val ct = pm.commentText
            val cr = pm.commentRect
            if (!ct.isNullOrBlank() && cr != null) {
                sb.append("""  <rect x="${fmt(cr.x)}" y="${fmt(cr.y)}" width="${fmt(cr.width)}" height="${fmt(cr.height)}" rx="4" fill="${theme.noteFill.toSvgHex()}" stroke="${theme.noteBorder.toSvgHex()}" stroke-width="1"/>""").append('\n')
                appendMultilineText(sb, "  ", cr.centerX, cr.centerY, plainTextLabel(ct).split('\n'), theme.noteText.toSvgHex(), theme.captionFontSize.value)
            }
            if (pm.displayText.isBlank() && cr != null) continue

            val pts = if (pm.isSelf) {
                val loopW = 34f
                val loopH = max(18f, pm.selfLoopHeight)
                val rightX = max(pm.startX, pm.endX) + loopW
                listOf(
                    DiagramPoint(pm.startX, pm.y),
                    DiagramPoint(rightX, pm.y),
                    DiagramPoint(rightX, pm.y + loopH),
                    DiagramPoint(pm.endX, pm.y + loopH),
                )
            } else {
                listOf(DiagramPoint(pm.startX, pm.y), DiagramPoint(pm.endX, pm.y))
            }
            val dashAttr = if (pm.kind == ZenUmlMessageKind.RETURN) """ stroke-dasharray="5,4"""" else ""
            sb.append("""  <path class="zenuml-msg" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$edgeHex" stroke-width="1.6"$dashAttr/>""").append('\n')
            val endMarker = if (pm.kind == ZenUmlMessageKind.SYNC || pm.kind == ZenUmlMessageKind.CREATE) {
                SvgMarker.FilledArrow
            } else {
                SvgMarker.OpenArrow
            }
            appendSvgMarker(sb, pts.last(), pts[pts.size - 2], endMarker, edgeHex, bgHex)
            if (pm.displayText.isNotBlank()) {
                val lr = pm.labelRect
                if (lr.width > 0f && lr.height > 0f) {
                    sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.background.copy(alpha = 0.88f).toSvgHex()}"/>""").append('\n')
                }
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, plainTextLabel(pm.displayText).split('\n'), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun startSvgDocument(
        width: Float,
        height: Float,
        theme: MaradonaTheme,
        titleText: String?,
        titleRect: DiagramRect?,
    ): StringBuilder {
        val w = ceil(width).toInt().coerceAtLeast(1)
        val h = ceil(height).toInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h">""").append('\n')
        sb.append("""  <rect width="100%" height="100%" fill="${theme.background.toSvgHex()}"/>""").append('\n')
        if (!titleText.isNullOrBlank() && titleRect != null) {
            appendMultilineText(
                sb = sb,
                indent = "  ",
                centerX = titleRect.centerX,
                centerY = titleRect.centerY,
                lines = plainTextLabel(titleText).split('\n'),
                colorHex = theme.nodeText.toSvgHex(),
                fontSize = theme.titleFontSize.value,
            )
        }
        return sb
    }

    private enum class SvgMarker {
        None,
        FilledArrow,
        OpenArrow,
        HollowTriangle,
        FilledDiamond,
        HollowDiamond,
        Cross,
        Circle,
        Lollipop,
        ContainsCircleCross,
        ErExactlyOne,
        ErZeroOrOne,
        ErOneOrMore,
        ErZeroOrMore,
    }

    private fun mapClassEndMarker(end: ClassRelationEnd): SvgMarker = when (end) {
        ClassRelationEnd.None -> SvgMarker.None
        ClassRelationEnd.Extension -> SvgMarker.HollowTriangle
        ClassRelationEnd.Composition -> SvgMarker.FilledDiamond
        ClassRelationEnd.Aggregation -> SvgMarker.HollowDiamond
        ClassRelationEnd.Dependency -> SvgMarker.OpenArrow
        ClassRelationEnd.Lollipop -> SvgMarker.Lollipop
    }

    private fun mapErCardinalityMarker(card: ErCardinality): SvgMarker = when (card) {
        ErCardinality.ExactlyOne -> SvgMarker.ErExactlyOne
        ErCardinality.ZeroOrOne -> SvgMarker.ErZeroOrOne
        ErCardinality.OneOrMore -> SvgMarker.ErOneOrMore
        ErCardinality.ZeroOrMore -> SvgMarker.ErZeroOrMore
    }

    private fun appendSvgMarker(
        sb: StringBuilder,
        tip: DiagramPoint,
        from: DiagramPoint,
        marker: SvgMarker,
        color: String,
        bgColor: String,
    ) {
        if (marker == SvgMarker.None) return
        val angle = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
        when (marker) {
            SvgMarker.FilledArrow -> {
                val len = 9.0
                val spread = 0.45
                val x1 = tip.x - (len * cos(angle - spread)).toFloat()
                val y1 = tip.y - (len * sin(angle - spread)).toFloat()
                val x2 = tip.x - (len * cos(angle + spread)).toFloat()
                val y2 = tip.y - (len * sin(angle + spread)).toFloat()
                sb.append("""  <polygon class="edge-marker" data-marker="filled-arrow" points="${fmt(tip.x)},${fmt(tip.y)} ${fmt(x1)},${fmt(y1)} ${fmt(x2)},${fmt(y2)}" fill="$color"/>""").append('\n')
            }
            SvgMarker.OpenArrow -> {
                val len = 9.0
                val spread = 0.45
                val x1 = tip.x - (len * cos(angle - spread)).toFloat()
                val y1 = tip.y - (len * sin(angle - spread)).toFloat()
                val x2 = tip.x - (len * cos(angle + spread)).toFloat()
                val y2 = tip.y - (len * sin(angle + spread)).toFloat()
                sb.append("""  <polyline class="edge-marker" data-marker="open-arrow" points="${fmt(x1)},${fmt(y1)} ${fmt(tip.x)},${fmt(tip.y)} ${fmt(x2)},${fmt(y2)}" fill="none" stroke="$color" stroke-width="1.6"/>""").append('\n')
            }
            SvgMarker.HollowTriangle -> {
                val len = 11.0
                val spread = 0.48
                val x1 = tip.x - (len * cos(angle - spread)).toFloat()
                val y1 = tip.y - (len * sin(angle - spread)).toFloat()
                val x2 = tip.x - (len * cos(angle + spread)).toFloat()
                val y2 = tip.y - (len * sin(angle + spread)).toFloat()
                sb.append("""  <polygon class="edge-marker" data-marker="hollow-triangle" points="${fmt(tip.x)},${fmt(tip.y)} ${fmt(x1)},${fmt(y1)} ${fmt(x2)},${fmt(y2)}" fill="$bgColor" stroke="$color" stroke-width="1.6"/>""").append('\n')
            }
            SvgMarker.FilledDiamond, SvgMarker.HollowDiamond -> {
                val len = 12.0
                val halfLen = 6.0
                val spread = 0.48
                val x1 = tip.x - (halfLen * cos(angle - spread)).toFloat()
                val y1 = tip.y - (halfLen * sin(angle - spread)).toFloat()
                val xBack = tip.x - (len * cos(angle)).toFloat()
                val yBack = tip.y - (len * sin(angle)).toFloat()
                val x2 = tip.x - (halfLen * cos(angle + spread)).toFloat()
                val y2 = tip.y - (halfLen * sin(angle + spread)).toFloat()
                val fill = if (marker == SvgMarker.FilledDiamond) color else bgColor
                val tag = if (marker == SvgMarker.FilledDiamond) "filled-diamond" else "hollow-diamond"
                sb.append("""  <polygon class="edge-marker" data-marker="$tag" points="${fmt(tip.x)},${fmt(tip.y)} ${fmt(x1)},${fmt(y1)} ${fmt(xBack)},${fmt(yBack)} ${fmt(x2)},${fmt(y2)}" fill="$fill" stroke="$color" stroke-width="1.6"/>""").append('\n')
            }
            SvgMarker.Circle, SvgMarker.Lollipop -> {
                val tag = if (marker == SvgMarker.Lollipop) "lollipop" else "circle"
                sb.append("""  <circle class="edge-marker" data-marker="$tag" cx="${fmt(tip.x)}" cy="${fmt(tip.y)}" r="4.5" fill="$bgColor" stroke="$color" stroke-width="1.5"/>""").append('\n')
            }
            SvgMarker.ContainsCircleCross -> {
                val ux = -cos(angle).toFloat()
                val uy = -sin(angle).toFloat()
                val rad = 5.5f
                val cx = tip.x + ux * rad
                val cy = tip.y + uy * rad
                sb.append("""  <g class="edge-marker" data-marker="contains-circle-cross">""").append('\n')
                sb.append("""    <circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(rad)}" fill="$bgColor" stroke="$color" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx - rad)}" y1="${fmt(cy)}" x2="${fmt(cx + rad)}" y2="${fmt(cy)}" stroke="$color" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx)}" y1="${fmt(cy - rad)}" x2="${fmt(cx)}" y2="${fmt(cy + rad)}" stroke="$color" stroke-width="1.5"/>""").append('\n')
                sb.append("""  </g>""").append('\n')
            }
            SvgMarker.Cross -> {
                val d = 4f
                sb.append("""  <g class="edge-marker" data-marker="cross">""").append('\n')
                sb.append("""    <line x1="${fmt(tip.x - d)}" y1="${fmt(tip.y - d)}" x2="${fmt(tip.x + d)}" y2="${fmt(tip.y + d)}" stroke="$color" stroke-width="1.8"/>""").append('\n')
                sb.append("""    <line x1="${fmt(tip.x - d)}" y1="${fmt(tip.y + d)}" x2="${fmt(tip.x + d)}" y2="${fmt(tip.y - d)}" stroke="$color" stroke-width="1.8"/>""").append('\n')
                sb.append("""  </g>""").append('\n')
            }
            SvgMarker.ErExactlyOne,
            SvgMarker.ErZeroOrOne,
            SvgMarker.ErOneOrMore,
            SvgMarker.ErZeroOrMore,
            -> {
                val ux = -cos(angle).toFloat()
                val uy = -sin(angle).toFloat()
                val px = -uy
                val py = ux
                val halfTick = 5.5f
                val tag = when (marker) {
                    SvgMarker.ErExactlyOne -> "er-exactly-one"
                    SvgMarker.ErZeroOrOne -> "er-zero-or-one"
                    SvgMarker.ErOneOrMore -> "er-one-or-more"
                    else -> "er-zero-or-more"
                }
                sb.append("""  <g class="edge-marker" data-marker="$tag">""").append('\n')
                fun emitTick(offsetAlong: Float) {
                    val cx = tip.x + ux * offsetAlong
                    val cy = tip.y + uy * offsetAlong
                    sb.append("""    <line x1="${fmt(cx - px * halfTick)}" y1="${fmt(cy - py * halfTick)}" x2="${fmt(cx + px * halfTick)}" y2="${fmt(cy + py * halfTick)}" stroke="$color" stroke-width="1.6"/>""").append('\n')
                }
                fun emitCrowFoot() {
                    val base = 11f
                    val bx = tip.x + ux * base
                    val by = tip.y + uy * base
                    sb.append("""    <line x1="${fmt(bx)}" y1="${fmt(by)}" x2="${fmt(tip.x + px * halfTick)}" y2="${fmt(tip.y + py * halfTick)}" stroke="$color" stroke-width="1.6"/>""").append('\n')
                    sb.append("""    <line x1="${fmt(bx)}" y1="${fmt(by)}" x2="${fmt(tip.x - px * halfTick)}" y2="${fmt(tip.y - py * halfTick)}" stroke="$color" stroke-width="1.6"/>""").append('\n')
                }
                fun emitZeroCircle(offsetAlong: Float) {
                    val cx = tip.x + ux * offsetAlong
                    val cy = tip.y + uy * offsetAlong
                    sb.append("""    <circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="4" fill="$bgColor" stroke="$color" stroke-width="1.5"/>""").append('\n')
                }
                when (marker) {
                    SvgMarker.ErExactlyOne -> {
                        emitTick(5f)
                        emitTick(10f)
                    }
                    SvgMarker.ErZeroOrOne -> {
                        emitTick(5f)
                        emitZeroCircle(13f)
                    }
                    SvgMarker.ErOneOrMore -> {
                        emitCrowFoot()
                        emitTick(12f)
                    }
                    else -> {
                        emitCrowFoot()
                        emitZeroCircle(15f)
                    }
                }
                sb.append("""  </g>""").append('\n')
            }
            SvgMarker.None -> Unit
        }
    }

    private fun wrapSvgLines(text: String, fontSp: Float, maxWidth: Float): List<String> {
        val maxChars = max(8, (maxWidth / (fontSp * 0.62f)).toInt())
        val out = mutableListOf<String>()
        for (rawLine in text.split('\n')) {
            if (rawLine.length <= maxChars) {
                out += rawLine
                continue
            }
            val words = rawLine.split(' ')
            val cur = StringBuilder()
            for (word in words) {
                if (cur.isNotEmpty() && cur.length + 1 + word.length <= maxChars) {
                    cur.append(' ').append(word)
                    continue
                }
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur.setLength(0)
                }
                if (word.length <= maxChars) {
                    cur.append(word)
                } else {
                    var pos = 0
                    while (pos < word.length) {
                        val end = min(word.length, pos + maxChars)
                        val chunk = word.substring(pos, end)
                        if (end < word.length) out += chunk else cur.append(chunk)
                        pos = end
                    }
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return if (out.isEmpty()) listOf("") else out
    }

    private fun defaultFlowNodeSize(node: FlowNode, theme: MaradonaTheme): DiagramSize {
        val fontSize = theme.nodeFontSize.value
        val lines = wrapSvgLines(plainTextLabel(node.label), fontSize, NodeSizing.WRAPPING_WIDTH)
        val maxCols = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val textW = min(NodeSizing.WRAPPING_WIDTH, maxCols * fontSize * 0.62f)
        val textH = lines.size * fontSize * 1.5f
        val labelBox = NodeSizing.labelBox(node.shape, textW, textH)
        return NodeSizing.nodeBox(node.shape, labelBox.width, labelBox.height)
    }

    private fun defaultTextSize(text: String, fontSp: Float): DiagramSize {
        val plain = plainTextLabel(text)
        val lines = plain.split('\n')
        val maxCols = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        return DiagramSize(
            width = max(24f, maxCols * fontSp * 0.62f + 8f),
            height = max(18f, lines.size * fontSp * 1.4f + 4f),
        )
    }

    private fun appendSvgNodeShape(
        sb: StringBuilder,
        shape: NodeShape,
        r: DiagramRect,
        fill: String,
        stroke: String,
        strokeW: Float,
    ) {
        val x = fmt(r.x)
        val y = fmt(r.y)
        val w = fmt(r.width)
        val h = fmt(r.height)
        val sw = fmt(strokeW)
        when (shape) {
            NodeShape.Rect ->
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            NodeShape.RoundedRect ->
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" rx="6" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            NodeShape.Stadium -> {
                val rx = fmt(r.height / 2f)
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" rx="$rx" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Subroutine -> {
                val inset = min(10f, r.width * 0.15f)
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""    <line x1="${fmt(r.x + inset)}" y1="$y" x2="${fmt(r.x + inset)}" y2="${fmt(r.bottom)}" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""    <line x1="${fmt(r.right - inset)}" y1="$y" x2="${fmt(r.right - inset)}" y2="${fmt(r.bottom)}" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Circle -> {
                val rad = fmt(min(r.width, r.height) / 2f)
                sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="$rad" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.DoubleCircle -> {
                val outerR = min(r.width, r.height) / 2f
                val innerR = max(2f, outerR - 5f)
                sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="${fmt(outerR)}" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="${fmt(innerR)}" fill="none" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Cylinder -> {
                val rx = r.width / 2f
                val ry = (rx / (2.5f + r.width / 50f)).coerceAtMost(r.height * 0.25f)
                val topY = r.y + ry
                val botY = r.bottom - ry
                val bodyD = "M ${fmt(r.x)} ${fmt(topY)} L ${fmt(r.x)} ${fmt(botY)} A ${fmt(rx)} ${fmt(ry)} 0 0 0 ${fmt(r.right)} ${fmt(botY)} L ${fmt(r.right)} ${fmt(topY)} A ${fmt(rx)} ${fmt(ry)} 0 0 0 ${fmt(r.x)} ${fmt(topY)} Z"
                sb.append("""    <path d="$bodyD" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""    <ellipse cx="${fmt(r.centerX)}" cy="${fmt(topY)}" rx="${fmt(rx)}" ry="${fmt(ry)}" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Diamond,
            NodeShape.Hexagon,
            NodeShape.Parallelogram,
            NodeShape.ParallelogramAlt,
            NodeShape.Trapezoid,
            NodeShape.TrapezoidAlt,
            NodeShape.Asymmetric,
            -> {
                val pts = NodeOutline.polygonPoints(shape, r).joinToString(" ") { (px, py) ->
                    "${fmt(r.x + px)},${fmt(r.y + py)}"
                }
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
        }
    }

    private fun buildSvgEdgePath(points: List<DiagramPoint>): String {
        val sb = StringBuilder()
        sb.append("M ${fmt(points[0].x)} ${fmt(points[0].y)}")
        for (i in 1 until points.size) {
            sb.append(" L ${fmt(points[i].x)} ${fmt(points[i].y)}")
        }
        return sb.toString()
    }

    private fun appendSvgArrowHead(
        sb: StringBuilder,
        tip: DiagramPoint,
        from: DiagramPoint,
        head: ArrowHead,
        color: String,
        bgColor: String,
    ) {
        val angle = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
        when (head) {
            ArrowHead.Arrow -> {
                val len = 9.0
                val spread = 0.45
                val x1 = tip.x - (len * cos(angle - spread)).toFloat()
                val y1 = tip.y - (len * sin(angle - spread)).toFloat()
                val x2 = tip.x - (len * cos(angle + spread)).toFloat()
                val y2 = tip.y - (len * sin(angle + spread)).toFloat()
                sb.append("""  <polygon points="${fmt(tip.x)},${fmt(tip.y)} ${fmt(x1)},${fmt(y1)} ${fmt(x2)},${fmt(y2)}" fill="$color"/>""").append('\n')
            }
            ArrowHead.Circle -> {
                sb.append("""  <circle cx="${fmt(tip.x)}" cy="${fmt(tip.y)}" r="4" fill="$bgColor" stroke="$color" stroke-width="1.5"/>""").append('\n')
            }
            ArrowHead.Cross -> {
                val d = 4f
                sb.append("""  <line x1="${fmt(tip.x - d)}" y1="${fmt(tip.y - d)}" x2="${fmt(tip.x + d)}" y2="${fmt(tip.y + d)}" stroke="$color" stroke-width="1.8"/>""").append('\n')
                sb.append("""  <line x1="${fmt(tip.x - d)}" y1="${fmt(tip.y + d)}" x2="${fmt(tip.x + d)}" y2="${fmt(tip.y - d)}" stroke="$color" stroke-width="1.8"/>""").append('\n')
            }
            ArrowHead.None -> Unit
        }
    }

    private fun appendMultilineText(
        sb: StringBuilder,
        indent: String,
        centerX: Float,
        centerY: Float,
        lines: List<String>,
        colorHex: String,
        fontSize: Float,
    ) {
        val lineH = fontSize * 1.35f
        val startY = centerY - ((lines.size - 1) * lineH) / 2f + fontSize * 0.35f
        for ((idx, textLine) in lines.withIndex()) {
            val ly = startY + idx * lineH
            sb.append(
                """$indent<text x="${fmt(centerX)}" y="${fmt(ly)}" text-anchor="middle" fill="$colorHex" font-family="sans-serif" font-size="${fmt(fontSize)}">${escapeXml(textLine)}</text>""",
            ).append('\n')
        }
    }

    private fun Color.toSvgHex(): String {
        if (alpha <= 0.004f) return "none"
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
        if (alpha < 0.996f) {
            return "rgba($r,$g,$b,${fmt(alpha)})"
        }
        return "#" +
            r.toString(16).padStart(2, '0').uppercase() +
            g.toString(16).padStart(2, '0').uppercase() +
            b.toString(16).padStart(2, '0').uppercase()
    }

    private fun fmt(v: Float): String {
        val rounded = (v * 100f).roundToInt() / 100f
        val intPart = rounded.toInt()
        return if (rounded == intPart.toFloat()) intPart.toString() else rounded.toString()
    }

    private fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val code = ch.code
            if (ch.isHighSurrogate()) {
                if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    sb.append(ch).append(s[i + 1])
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (ch.isLowSurrogate()) {
                i++
                continue
            }
            val validXml10 = code == 0x09 || code == 0x0A || code == 0x0D ||
                (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
            if (!validXml10) {
                i++
                continue
            }
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
