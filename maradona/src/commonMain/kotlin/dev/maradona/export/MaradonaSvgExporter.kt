package dev.maradona.export

import androidx.compose.ui.graphics.Color
import dev.maradona.layout.ArchitectureLayout
import dev.maradona.layout.BlockLayout
import dev.maradona.layout.C4Layout
import dev.maradona.layout.ClassLayout
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.ErLayout
import dev.maradona.layout.FlowchartLayout
import dev.maradona.layout.GanttLayout
import dev.maradona.layout.GitGraphLayout
import dev.maradona.layout.JourneyLayout
import dev.maradona.layout.KanbanLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MindmapLayout
import dev.maradona.layout.NodeSizing
import dev.maradona.layout.PacketLayout
import dev.maradona.layout.PieLayout
import dev.maradona.layout.QuadrantLayout
import dev.maradona.layout.RadarLayout
import dev.maradona.layout.RequirementLayout
import dev.maradona.layout.SankeyLayout
import dev.maradona.layout.SequenceLayout
import dev.maradona.layout.StateLayout
import dev.maradona.layout.TimelineLayout
import dev.maradona.layout.TreemapLayout
import dev.maradona.layout.XyChartLayout
import dev.maradona.layout.ZenUmlLayout
import dev.maradona.model.ArrowHead
import dev.maradona.model.EdgeStroke
import dev.maradona.model.FlowNode
import dev.maradona.model.Flowchart
import dev.maradona.model.NodeShape
import dev.maradona.parse.MaradonaDiagramModel
import dev.maradona.parse.MaradonaParser
import dev.maradona.render.MaradonaTheme
import dev.maradona.render.parseCssColor
import dev.maradona.render.plainTextLabel
import kotlin.math.atan2
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
     * Parses [source] and exports the resulting Mermaid diagram to an SVG string.
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
                val layout = SequenceLayout.layout(
                    diagram = d,
                    participantLabelSize = { p -> defaultTextSize(p.label, theme.nodeFontSize.value) },
                    messageLabelSize = { _, m -> defaultTextSize(m.text, theme.nodeFontSize.value) },
                    noteTextSize = { _, n -> defaultTextSize(n.text, theme.nodeFontSize.value) },
                    blockLabelSize = { _, _, b -> defaultTextSize(b, theme.edgeLabelFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                )
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.boxes.map { b ->
                        SvgBox(b.rect, b.box.label.orEmpty(), theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    } + layout.blocks.map { blk ->
                        val header = listOfNotNull(blk.tag, blk.label?.takeIf { it.isNotBlank() }?.let { "[$it]" }).joinToString(" ")
                        SvgBox(blk.rect, header, Color.Transparent, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.participants.flatMap { pp ->
                        listOfNotNull(
                            SvgBox(pp.topRect, pp.participant.label, theme.nodeFill, theme.nodeBorder, theme.nodeText),
                            pp.bottomRect?.let { SvgBox(it, pp.participant.label, theme.nodeFill, theme.nodeBorder, theme.nodeText) },
                        )
                    } + layout.activations.map { act ->
                        SvgBox(act.rect, "", theme.subgraphFill, theme.nodeBorder, theme.nodeText)
                    } + layout.notes.map { n ->
                        SvgBox(n.rect, n.note.text, theme.noteFill, theme.noteBorder, theme.noteText)
                    },
                    lines = layout.participants.map { pp ->
                        SvgLine(
                            points = listOf(DiagramPoint(pp.lifelineX, pp.lifelineTopY), DiagramPoint(pp.lifelineX, pp.lifelineBottomY)),
                            color = theme.subgraphBorder,
                            dashed = true,
                            label = null to null,
                        )
                    } + layout.blocks.flatMap { blk ->
                        blk.dividers.map { div ->
                            SvgLine(
                                points = listOf(DiagramPoint(blk.rect.x, div.y), DiagramPoint(blk.rect.right, div.y)),
                                color = theme.subgraphBorder,
                                dashed = true,
                                label = div.label?.let { "[$it]" } to div.labelRect,
                            )
                        }
                    } + layout.messages.map { m ->
                        val endMarker = when (m.statement.arrow.head) {
                            dev.maradona.model.SequenceHead.Filled -> SvgMarker.FilledArrow
                            dev.maradona.model.SequenceHead.Open -> SvgMarker.None
                            dev.maradona.model.SequenceHead.Cross -> SvgMarker.Cross
                            dev.maradona.model.SequenceHead.Async -> SvgMarker.OpenArrow
                        }
                        val startMarker = if (m.statement.arrow.bidirectional) endMarker else SvgMarker.None
                        val msgLabel = if (m.sequenceNumber != null) "${m.sequenceNumber}. ${m.statement.text}" else m.statement.text
                        val pts = if (m.isSelf) {
                            val loopW = 32f
                            val loopH = 18f
                            listOf(
                                DiagramPoint(m.startX, m.lineY),
                                DiagramPoint(m.startX + loopW, m.lineY),
                                DiagramPoint(m.startX + loopW, m.lineY + loopH),
                                DiagramPoint(m.startX, m.lineY + loopH),
                            )
                        } else {
                            listOf(DiagramPoint(m.startX, m.lineY), DiagramPoint(m.stopX, m.lineY))
                        }
                        SvgLine(
                            points = pts,
                            color = theme.edge,
                            dashed = m.statement.arrow.dashed,
                            label = msgLabel to m.labelRect,
                            startMarker = startMarker,
                            endMarker = endMarker,
                        )
                    },
                )
            }
            is MaradonaDiagramModel.ClassModel -> {
                val d = model.diagram
                val layout = ClassLayout.layout(
                    diagram = d,
                    classBoxSize = { c ->
                        val titleSize = defaultTextSize(c.displayTitle, theme.nodeFontSize.value)
                        val memberWidths = (c.attributes + c.methods).map { defaultTextSize(it.displayText, theme.edgeLabelFontSize.value).width }
                        val maxMemberW = memberWidths.maxOrNull() ?: 0f
                        val attrH = c.attributes.size * 18f
                        val methH = c.methods.size * 18f
                        val hdrH = max(32f, titleSize.height + 12f)
                        val totalW = max(110f, max(titleSize.width, maxMemberW) + 24f)
                        val totalH = hdrH + attrH + methH + if (attrH + methH > 0f) 12f else 0f
                        dev.maradona.layout.ClassBoxMetrics(totalW, totalH, hdrH, attrH, methH)
                    },
                    relationLabelSize = { _, r -> r.label?.let { defaultTextSize(it, theme.edgeLabelFontSize.value) } },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.namespaces.map { ns ->
                        SvgBox(ns.rect, ns.namespace.label, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.classes.map { pc ->
                        val fullClassText = buildList {
                            add(pc.node.displayTitle)
                            addAll(pc.node.attributes.map { it.displayText })
                            addAll(pc.node.methods.map { it.displayText })
                        }.joinToString("\n")
                        SvgBox(
                            rect = pc.rect,
                            label = fullClassText,
                            fill = parseCssColor(pc.node.style?.fill) ?: theme.nodeFill,
                            stroke = parseCssColor(pc.node.style?.stroke) ?: theme.nodeBorder,
                            textColor = parseCssColor(pc.node.style?.textColor) ?: theme.nodeText,
                            url = pc.node.action?.url,
                            tooltip = pc.node.action?.tooltip,
                        )
                    } + layout.notes.map { n ->
                        SvgBox(n.rect, n.note.text, theme.noteFill, theme.noteBorder, theme.noteText)
                    },
                    lines = layout.relations.map { r ->
                        SvgLine(
                            points = r.points,
                            color = theme.edge,
                            dashed = r.relation.dashed,
                            label = r.relation.label to r.labelRect,
                            startMarker = mapClassEndMarker(r.relation.startEnd),
                            endMarker = mapClassEndMarker(r.relation.endEnd),
                            startLabel = r.relation.fromCardinality to r.fromCardinalityRect,
                            endLabel = r.relation.toCardinality to r.toCardinalityRect,
                        )
                    },
                )
            }
            is MaradonaDiagramModel.StateModel -> {
                val d = model.diagram
                val layout = StateLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.states.filter { it.state.kind == dev.maradona.model.StateKind.Composite }.map { cs ->
                        SvgBox(cs.rect, cs.state.label, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.states.filter { it.state.kind != dev.maradona.model.StateKind.Composite }.map { ps ->
                        val kind = ps.state.kind
                        val isPseudo = kind != dev.maradona.model.StateKind.Normal
                        val fullStateText = if (isPseudo) {
                            ""
                        } else {
                            (listOf(ps.state.label) + ps.state.descriptions).filter { it.isNotBlank() }.joinToString("\n")
                        }
                        val shape = when (kind) {
                            dev.maradona.model.StateKind.Start -> NodeShape.Circle
                            dev.maradona.model.StateKind.End -> NodeShape.DoubleCircle
                            dev.maradona.model.StateKind.Choice -> NodeShape.Diamond
                            dev.maradona.model.StateKind.Fork, dev.maradona.model.StateKind.Join -> NodeShape.Rect
                            dev.maradona.model.StateKind.Normal, dev.maradona.model.StateKind.Composite -> NodeShape.RoundedRect
                        }
                        val defaultFill = when (kind) {
                            dev.maradona.model.StateKind.Start,
                            dev.maradona.model.StateKind.End,
                            dev.maradona.model.StateKind.Fork,
                            dev.maradona.model.StateKind.Join,
                            -> theme.nodeBorder
                            else -> theme.nodeFill
                        }
                        SvgBox(
                            rect = ps.rect,
                            label = fullStateText,
                            fill = parseCssColor(ps.state.style?.fill) ?: defaultFill,
                            stroke = parseCssColor(ps.state.style?.stroke) ?: theme.nodeBorder,
                            textColor = parseCssColor(ps.state.style?.textColor) ?: theme.nodeText,
                            shape = shape,
                        )
                    } + layout.notes.map { n ->
                        SvgBox(n.rect, n.note.text, theme.noteFill, theme.noteBorder, theme.noteText)
                    },
                    lines = layout.transitions.map { tr ->
                        SvgLine(
                            points = tr.points,
                            color = theme.edge,
                            dashed = false,
                            label = tr.transition.label to tr.labelRect,
                            endMarker = SvgMarker.FilledArrow,
                        )
                    },
                )
            }
            is MaradonaDiagramModel.ErModel -> {
                val d = model.diagram
                val layout = ErLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.subgraphs.map { sg ->
                        SvgBox(sg.rect, sg.subgraph.label, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.entities.map { pe ->
                        val attrLines = pe.entity.attributes.map { attr ->
                            listOfNotNull(
                                attr.type,
                                attr.name,
                                attr.keys.takeIf { it.isNotEmpty() }?.joinToString(","),
                                attr.comment?.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
                            ).joinToString(" ")
                        }
                        val fullEntityText = (listOf(pe.entity.label) + attrLines).joinToString("\n")
                        SvgBox(
                            rect = pe.rect,
                            label = fullEntityText,
                            fill = parseCssColor(pe.entity.style?.fill) ?: theme.nodeFill,
                            stroke = parseCssColor(pe.entity.style?.stroke) ?: theme.nodeBorder,
                            textColor = parseCssColor(pe.entity.style?.textColor) ?: theme.nodeText,
                        )
                    },
                    lines = layout.relationships.map { pr ->
                        SvgLine(
                            points = pr.points,
                            color = theme.edge,
                            dashed = !pr.relationship.identifying,
                            label = pr.relationship.label to pr.labelRect,
                            startMarker = mapErCardinalityMarker(pr.relationship.fromCardinality),
                            endMarker = mapErCardinalityMarker(pr.relationship.toCardinality),
                        )
                    },
                )
            }
            is MaradonaDiagramModel.MindmapModel -> {
                val d = model.diagram
                val layout = MindmapLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.nodes.map { pn ->
                        val stroke = if (pn.branchIndex >= 0) theme.seriesColor(pn.branchIndex) else theme.nodeBorder
                        val shape = when (pn.node.shape) {
                            dev.maradona.model.MindmapShape.Square -> NodeShape.Rect
                            dev.maradona.model.MindmapShape.Circle -> NodeShape.Circle
                            dev.maradona.model.MindmapShape.Hexagon -> NodeShape.Hexagon
                            else -> NodeShape.RoundedRect
                        }
                        SvgBox(pn.rect, pn.node.label, theme.nodeFill, stroke, theme.nodeText, shape = shape)
                    },
                    lines = layout.edges.map { e ->
                        val stroke = if (e.branchIndex >= 0) theme.seriesColor(e.branchIndex) else theme.edge
                        SvgLine(e.points, stroke, false, null to null)
                    },
                )
            }
            is MaradonaDiagramModel.GanttModel -> {
                val d = model.diagram
                val layout = GanttLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.sections.map { s ->
                        SvgBox(s.rect, "", theme.seriesFill(s.sectionIndex.coerceAtLeast(0)), theme.subgraphBorder, theme.nodeText)
                    },
                    boxes = layout.sections.map { s ->
                        SvgBox(s.labelRect, s.name, Color.Transparent, Color.Transparent, theme.nodeText)
                    } + layout.tasks.flatMap { pt ->
                        val idx = pt.sectionIndex.coerceAtLeast(0)
                        val barBox = SvgBox(
                            rect = pt.barRect,
                            label = "",
                            fill = theme.seriesFill(idx),
                            stroke = theme.seriesColor(idx),
                            textColor = theme.nodeText,
                            url = pt.task.action?.url,
                            tooltip = pt.task.action?.tooltip,
                            shape = if (pt.task.isMilestone) NodeShape.Diamond else NodeShape.RoundedRect,
                        )
                        val lblBox = SvgBox(
                            rect = pt.labelRect,
                            label = pt.task.label,
                            fill = Color.Transparent,
                            stroke = Color.Transparent,
                            textColor = theme.nodeText,
                            url = pt.task.action?.url,
                            tooltip = pt.task.action?.tooltip,
                        )
                        listOf(barBox, lblBox)
                    } + layout.ticks.map { t ->
                        SvgBox(t.bottomLabelRect, t.label, Color.Transparent, Color.Transparent, theme.edgeLabelText)
                    } + layout.verticalMarkers.map { vm ->
                        SvgBox(vm.labelRect, vm.task.label, theme.accentFill, theme.accentBorder, theme.nodeText)
                    },
                    lines = layout.verticalMarkers.map { vm ->
                        SvgLine(listOf(DiagramPoint(vm.x, vm.topY), DiagramPoint(vm.x, vm.bottomY)), theme.accentBorder, true, null to null)
                    },
                )
            }
            is MaradonaDiagramModel.PieModel -> {
                val d = model.diagram
                val layout = PieLayout.layout(diagram = d, options = options)
                val customElements = buildList {
                    for (s in layout.slices) {
                        val cx = layout.center.x + s.explodeOffset.x
                        val cy = layout.center.y + s.explodeOffset.y
                        val fillHex = theme.seriesColor(s.index).toSvgHex()
                        val strokeHex = theme.background.toSvgHex()
                        if (s.sweepAngleDeg >= 359.9f) {
                            val rOut = layout.outerRadius
                            if (layout.innerRadius > 0.5f) {
                                val rIn = layout.innerRadius
                                val dPath = "M ${fmt(cx)} ${fmt(cy - rOut)} A ${fmt(rOut)} ${fmt(rOut)} 0 1 1 ${fmt(cx)} ${fmt(cy + rOut)} A ${fmt(rOut)} ${fmt(rOut)} 0 1 1 ${fmt(cx)} ${fmt(cy - rOut)} Z " +
                                    "M ${fmt(cx)} ${fmt(cy - rIn)} A ${fmt(rIn)} ${fmt(rIn)} 0 1 0 ${fmt(cx)} ${fmt(cy + rIn)} A ${fmt(rIn)} ${fmt(rIn)} 0 1 0 ${fmt(cx)} ${fmt(cy - rIn)} Z"
                                add("""  <path class="pie-slice" fill-rule="evenodd" d="$dPath" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(layout.outerStrokeWidth)}"/>""")
                            } else {
                                add("""  <circle class="pie-slice" cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(rOut)}" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(layout.outerStrokeWidth)}"/>""")
                            }
                        } else if (s.sweepAngleDeg > 0.01f) {
                            val startRad = s.startAngleDeg * kotlin.math.PI / 180.0
                            val endRad = (s.startAngleDeg + s.sweepAngleDeg) * kotlin.math.PI / 180.0
                            val x1 = cx + (layout.outerRadius * cos(startRad)).toFloat()
                            val y1 = cy + (layout.outerRadius * sin(startRad)).toFloat()
                            val x2 = cx + (layout.outerRadius * cos(endRad)).toFloat()
                            val y2 = cy + (layout.outerRadius * sin(endRad)).toFloat()
                            val largeArc = if (s.sweepAngleDeg > 180f) 1 else 0
                            val pathData = if (layout.innerRadius > 0.5f) {
                                val ix2 = cx + (layout.innerRadius * cos(endRad)).toFloat()
                                val iy2 = cy + (layout.innerRadius * sin(endRad)).toFloat()
                                val ix1 = cx + (layout.innerRadius * cos(startRad)).toFloat()
                                val iy1 = cy + (layout.innerRadius * sin(startRad)).toFloat()
                                "M ${fmt(x1)} ${fmt(y1)} A ${fmt(layout.outerRadius)} ${fmt(layout.outerRadius)} 0 $largeArc 1 ${fmt(x2)} ${fmt(y2)} L ${fmt(ix2)} ${fmt(iy2)} A ${fmt(layout.innerRadius)} ${fmt(layout.innerRadius)} 0 $largeArc 0 ${fmt(ix1)} ${fmt(iy1)} Z"
                            } else {
                                "M ${fmt(cx)} ${fmt(cy)} L ${fmt(x1)} ${fmt(y1)} A ${fmt(layout.outerRadius)} ${fmt(layout.outerRadius)} 0 $largeArc 1 ${fmt(x2)} ${fmt(y2)} Z"
                            }
                            add("""  <path class="pie-slice" d="$pathData" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(layout.outerStrokeWidth)}"/>""")
                        }
                        s.pctLabelRect?.let { pr ->
                            add("""  <text x="${fmt(pr.centerX)}" y="${fmt(pr.centerY + 4f)}" text-anchor="middle" fill="${theme.nodeText.toSvgHex()}" font-family="sans-serif" font-size="${fmt(theme.edgeLabelFontSize.value)}">${escapeXml(s.percentageText)}</text>""")
                        }
                    }
                }
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.legendItems.flatMap { li ->
                        listOf(
                            SvgBox(li.swatchRect, "", theme.seriesColor(li.index), theme.seriesColor(li.index), theme.nodeText),
                            SvgBox(li.labelRect, li.displayText, Color.Transparent, Color.Transparent, theme.nodeText),
                        )
                    },
                    extraSvgElements = customElements,
                )
            }
            is MaradonaDiagramModel.GitGraphModel -> {
                val d = model.diagram
                val layout = GitGraphLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.branches.mapNotNull { b ->
                        b.labelRect?.let { SvgBox(it, b.branch.name, theme.seriesFill(b.laneIndex), theme.seriesColor(b.laneIndex), theme.nodeText) }
                    } + layout.commits.flatMap { pc ->
                        listOfNotNull(
                            SvgBox(pc.nodeRect, "", theme.seriesFill(pc.laneIndex), theme.seriesColor(pc.laneIndex), theme.nodeText, shape = NodeShape.Circle),
                            pc.commitLabelRect?.let { lr ->
                                SvgBox(lr, pc.commit.id, theme.subgraphFill, Color.Transparent, theme.nodeText, rotationDeg = pc.commitLabelAngleDeg)
                            },
                            pc.tagRect?.let { tr -> SvgBox(tr, pc.commit.tag.orEmpty(), theme.noteFill, theme.noteBorder, theme.noteText) },
                        )
                    },
                    lines = layout.branches.map { b ->
                        SvgLine(listOf(b.lineStart, b.lineEnd), theme.seriesColor(b.laneIndex), true, null to null)
                    } + layout.edges.map { e ->
                        SvgLine(e.points, theme.seriesColor(e.branchLaneIndex), e.isCherryPick, null to null)
                    },
                )
            }
            is MaradonaDiagramModel.TimelineModel -> {
                val d = model.diagram
                val layout = TimelineLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.sections.map { s ->
                        SvgBox(s.rect, s.name, theme.seriesFill(s.sectionIndex), theme.seriesColor(s.sectionIndex), theme.nodeText)
                    },
                    boxes = layout.periods.flatMap { pp ->
                        listOf(
                            SvgBox(pp.rect, pp.period.title, theme.seriesFill(pp.colorIndex), theme.seriesColor(pp.colorIndex), theme.nodeText),
                        ) + pp.events.map { ev ->
                            SvgBox(ev.rect, ev.text, theme.nodeFill, theme.seriesColor(pp.colorIndex), theme.nodeText)
                        }
                    },
                    lines = listOf(
                        SvgLine(listOf(layout.spineStart, layout.spineEnd), theme.edge, false, null to null),
                    ) + layout.periods.mapNotNull { pp ->
                        val cs = pp.connectorStart
                        val ce = pp.connectorEnd
                        if (cs != null && ce != null) {
                            SvgLine(listOf(cs, ce), theme.seriesColor(pp.colorIndex), true, null to null)
                        } else {
                            null
                        }
                    },
                )
            }
            is MaradonaDiagramModel.JourneyModel -> {
                val d = model.diagram
                val layout = JourneyLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.sections.map { s ->
                        SvgBox(s.rect, s.name, theme.seriesFill(s.sectionIndex), theme.seriesColor(s.sectionIndex), theme.nodeText)
                    },
                    boxes = layout.actorLegend.map { al ->
                        SvgBox(al.labelRect, al.actor, theme.seriesFill(al.actorIndex), theme.seriesColor(al.actorIndex), theme.nodeText)
                    } + layout.tasks.map { pt ->
                        val actors = pt.task.actors.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "\n[$it]" }.orEmpty()
                        SvgBox(pt.cardRect, "${pt.task.label} (${pt.task.score})$actors", theme.seriesFill(pt.sectionIndex), theme.seriesColor(pt.sectionIndex), theme.nodeText)
                    },
                    lines = listOf(
                        SvgLine(listOf(layout.spineStart, layout.spineEnd), theme.edge, false, null to null, endMarker = SvgMarker.FilledArrow),
                    ),
                )
            }
            is MaradonaDiagramModel.QuadrantModel -> {
                val d = model.diagram
                val layout = QuadrantLayout.layout(diagram = d, options = options)
                val pointCircles = layout.points.map { pt ->
                    val fillHex = (parseCssColor(pt.point.style?.color) ?: theme.accentFill).toSvgHex()
                    val strokeHex = (parseCssColor(pt.point.style?.strokeColor) ?: theme.accentBorder).toSvgHex()
                    """  <circle class="quadrant-point" cx="${fmt(pt.center.x)}" cy="${fmt(pt.center.y)}" r="${fmt(pt.radius)}" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(pt.strokeWidth)}"/>"""
                }
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.quadrants.map { q ->
                        SvgBox(q.rect, q.text.orEmpty(), theme.seriesFill(q.quadrantNumber - 1), theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.axisLabels.map { al ->
                        SvgBox(al.rect, al.text, Color.Transparent, Color.Transparent, theme.edgeLabelText, rotationDeg = al.rotationDeg)
                    } + layout.points.map { pt ->
                        SvgBox(pt.labelRect, pt.point.label, Color.Transparent, Color.Transparent, theme.nodeText)
                    },
                    extraSvgElements = pointCircles,
                )
            }
            is MaradonaDiagramModel.XyChartModel -> {
                val d = model.diagram
                val layout = XyChartLayout.layout(diagram = d, options = options)
                val linePointElements = layout.lines.flatMap { ls ->
                    val fillHex = theme.seriesColor(ls.seriesIndex).toSvgHex()
                    val strokeHex = theme.background.toSvgHex()
                    ls.points.flatMap { pt ->
                        buildList {
                            add("""  <circle class="xy-line-point" cx="${fmt(pt.position.x)}" cy="${fmt(pt.position.y)}" r="3.5" fill="$fillHex" stroke="$strokeHex" stroke-width="1"/>""")
                            val lbl = pt.label
                            val lr = pt.labelRect
                            if (!lbl.isNullOrBlank() && lr != null) {
                                add("""  <text x="${fmt(lr.centerX)}" y="${fmt(lr.centerY + 4f)}" text-anchor="middle" fill="${theme.nodeText.toSvgHex()}" font-family="sans-serif" font-size="${fmt(theme.edgeLabelFontSize.value)}">${escapeXml(plainTextLabel(lbl))}</text>""")
                            }
                        }
                    }
                }
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.bars.map { b ->
                        SvgBox(b.rect, b.dataLabel.orEmpty(), theme.seriesColor(b.seriesIndex), theme.seriesColor(b.seriesIndex), theme.nodeText)
                    } + layout.axisTitles.map { at ->
                        SvgBox(at.rect, at.text, Color.Transparent, Color.Transparent, theme.nodeText, rotationDeg = at.rotationDeg)
                    } + (layout.xTicks + layout.yTicks).map { tk ->
                        SvgBox(tk.labelRect, tk.label, Color.Transparent, Color.Transparent, theme.edgeLabelText)
                    } + layout.legendItems.map { li ->
                        SvgBox(li.labelRect, li.series.title ?: li.series.id, theme.seriesFill(li.seriesIndex), theme.seriesColor(li.seriesIndex), theme.nodeText)
                    },
                    lines = listOf(
                        SvgLine(listOf(layout.xAxisLineStart, layout.xAxisLineEnd), theme.nodeBorder, false, null to null),
                        SvgLine(listOf(layout.yAxisLineStart, layout.yAxisLineEnd), theme.nodeBorder, false, null to null),
                    ) + (layout.xTicks + layout.yTicks).map { tk ->
                        SvgLine(listOf(tk.lineStart, tk.lineEnd), theme.nodeBorder, false, null to null)
                    },
                    overlayLines = layout.lines.map { ls ->
                        SvgLine(ls.points.map { it.position }, theme.seriesColor(ls.seriesIndex), false, null to null)
                    },
                    overlaySvgElements = linePointElements,
                )
            }
            is MaradonaDiagramModel.KanbanModel -> {
                val d = model.diagram
                val layout = KanbanLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.columns.map { c ->
                        SvgBox(c.rect, c.column.label, theme.seriesFill(c.columnIndex), theme.subgraphBorder, theme.nodeText)
                    },
                    boxes = layout.columns.flatMap { c ->
                        c.tasks.map { pt ->
                            val meta = listOfNotNull(pt.task.ticket, pt.task.assigned?.let { "@$it" }).joinToString(" ")
                            val text = if (meta.isNotEmpty()) "${pt.task.label}\n$meta" else pt.task.label
                            SvgBox(
                                rect = pt.cardRect,
                                label = text,
                                fill = theme.background,
                                stroke = theme.nodeBorder,
                                textColor = theme.nodeText,
                                url = pt.task.ticket?.let { d.resolveTicketUrl(it) },
                            )
                        }
                    },
                )
            }
            is MaradonaDiagramModel.SankeyModel -> {
                val d = model.diagram
                val layout = SankeyLayout.layout(diagram = d, options = options)
                val linkElements = layout.links.map { pl ->
                    val strokeHex = theme.seriesColor(pl.sourceNodeIndex).toSvgHex()
                    val pathD = "M ${fmt(pl.start.x)} ${fmt(pl.start.y)} C ${fmt(pl.control1.x)} ${fmt(pl.control1.y)}, ${fmt(pl.control2.x)} ${fmt(pl.control2.y)}, ${fmt(pl.end.x)} ${fmt(pl.end.y)}"
                    """  <path class="sankey-link" d="$pathD" fill="none" stroke="$strokeHex" stroke-width="${fmt(pl.thickness.coerceAtLeast(1f))}" stroke-opacity="0.45"/>"""
                }
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.nodes.flatMap { pn ->
                        listOfNotNull(
                            SvgBox(pn.rect, "", theme.seriesColor(pn.nodeIndex), theme.nodeBorder, theme.nodeText, shape = NodeShape.Rect),
                            pn.labelText.takeIf { it.isNotBlank() }?.let { lbl ->
                                SvgBox(pn.labelRect, lbl, Color.Transparent, Color.Transparent, theme.nodeText)
                            },
                        )
                    },
                    extraSvgElements = linkElements,
                )
            }
            is MaradonaDiagramModel.RequirementModel -> {
                val d = model.diagram
                val layout = RequirementLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.items.map { pi ->
                        val fullText = (listOf("<<${pi.item.stereotype}>>", pi.item.name) + pi.item.bodyLines).joinToString("\n")
                        SvgBox(pi.rect, fullText, theme.nodeFill, theme.nodeBorder, theme.nodeText)
                    },
                    lines = layout.relations.map { pr ->
                        SvgLine(
                            points = pr.points,
                            color = theme.edge,
                            dashed = !pr.relation.isContains,
                            label = pr.relation.label to pr.labelRect,
                            endMarker = if (pr.relation.isContains) SvgMarker.Circle else SvgMarker.OpenArrow,
                        )
                    },
                )
            }
            is MaradonaDiagramModel.PacketModel -> {
                val d = model.diagram
                val layout = PacketLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.blocks.map { pb ->
                        val bitInfo = if (layout.showBits) {
                            if (pb.isSingleBit) " [${pb.leadingBitLabel}]" else " [${pb.leadingBitLabel}-${pb.trailingBitLabel}]"
                        } else {
                            ""
                        }
                        SvgBox(pb.rect, "${pb.block.label}$bitInfo", theme.nodeFill, theme.nodeBorder, theme.nodeText)
                    },
                )
            }
            is MaradonaDiagramModel.RadarModel -> {
                val d = model.diagram
                val layout = RadarLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.axes.map { pa ->
                        SvgBox(pa.labelRect, pa.axis.label, theme.nodeFill, theme.nodeBorder, theme.nodeText)
                    } + layout.legendItems.map { li ->
                        SvgBox(li.labelRect, li.label, theme.seriesFill(li.curveIndex), theme.seriesColor(li.curveIndex), theme.nodeText)
                    },
                    lines = layout.axes.map { pa ->
                        SvgLine(listOf(layout.center, pa.lineEnd), theme.subgraphBorder, true, null to null)
                    } + layout.curves.map { pc ->
                        SvgLine(pc.points + listOfNotNull(pc.points.firstOrNull()), theme.seriesColor(pc.curveIndex), false, null to null)
                    },
                )
            }
            is MaradonaDiagramModel.BlockModel -> {
                val d = model.diagram
                val layout = BlockLayout.layout(diagram = d, options = options)
                val blockArrowElements = layout.blocks
                    .filter { !it.isCompositeContainer && it.item.shape == dev.maradona.model.BlockShape.BlockArrow }
                    .map { pb ->
                        val r = pb.rect
                        val fill = (parseCssColor(pb.item.style?.fill) ?: theme.nodeFill).toSvgHex()
                        val stroke = (parseCssColor(pb.item.style?.stroke) ?: theme.nodeBorder).toSvgHex()
                        val dir = pb.item.arrowDirections.firstOrNull() ?: dev.maradona.model.BlockArrowDir.Right
                        val dirTag = dir.name.lowercase()
                        val pts = buildBlockArrowPoints(r, dir)
                        """  <polygon class="block-arrow" data-dir="$dirTag" points="$pts" fill="$fill" stroke="$stroke" stroke-width="1.3"/>"""
                    }
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.blocks.filter { it.isCompositeContainer }.map { pb ->
                        SvgBox(pb.rect, pb.item.label, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.blocks.filter { !it.isCompositeContainer && it.item.shape != dev.maradona.model.BlockShape.Space }.map { pb ->
                        val isArrow = pb.item.shape == dev.maradona.model.BlockShape.BlockArrow
                        SvgBox(
                            rect = pb.rect,
                            label = pb.item.label,
                            fill = if (isArrow) Color.Transparent else (parseCssColor(pb.item.style?.fill) ?: theme.nodeFill),
                            stroke = if (isArrow) Color.Transparent else (parseCssColor(pb.item.style?.stroke) ?: theme.nodeBorder),
                            textColor = parseCssColor(pb.item.style?.textColor) ?: theme.nodeText,
                            shape = mapBlockNodeShape(pb.item.shape),
                        )
                    },
                    lines = layout.edges.map { pe ->
                        SvgLine(
                            points = pe.points,
                            color = theme.edge,
                            dashed = pe.edge.pattern == dev.maradona.model.BlockEdgePattern.Dotted,
                            label = pe.edge.label to pe.labelRect,
                            startMarker = mapBlockEndMarker(pe.edge.startHead),
                            endMarker = mapBlockEndMarker(pe.edge.endHead),
                        )
                    },
                    extraSvgElements = blockArrowElements,
                )
            }
            is MaradonaDiagramModel.ArchitectureModel -> {
                val d = model.diagram
                val layout = ArchitectureLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.groups.map { pg ->
                        SvgBox(pg.rect, pg.group.title ?: pg.group.id, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.services.flatMap { ps ->
                        val iconBadge = ps.service.iconText ?: ps.service.icon ?: ""
                        val titleText = ps.service.title ?: ps.service.id
                        listOfNotNull(
                            SvgBox(ps.iconRect, iconBadge, theme.accentBorder, theme.accentBorder, theme.accentText),
                            ps.labelRect?.let { lr ->
                                SvgBox(lr, titleText, Color.Transparent, Color.Transparent, theme.nodeText)
                            } ?: SvgBox(ps.iconRect, titleText, theme.accentBorder, theme.accentBorder, theme.accentText),
                        )
                    } + layout.junctions.map { pj ->
                        SvgBox(pj.rect, "", theme.edge, theme.edge, theme.nodeText, shape = NodeShape.Circle)
                    },
                    lines = layout.edges.map { pe ->
                        SvgLine(
                            points = pe.points,
                            color = theme.edge,
                            dashed = false,
                            label = pe.edge.title to pe.labelRect,
                            startMarker = if (pe.edge.lhsInto) SvgMarker.FilledArrow else SvgMarker.None,
                            endMarker = if (pe.edge.rhsInto) SvgMarker.FilledArrow else SvgMarker.None,
                        )
                    },
                )
            }
            is MaradonaDiagramModel.TreemapModel -> {
                val d = model.diagram
                val layout = TreemapLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.sections.map { sec ->
                        SvgBox(sec.rect, sec.node.name, theme.seriesFill(sec.colorIndex), theme.seriesColor(sec.colorIndex), theme.nodeText)
                    },
                    boxes = layout.leaves.map { lf ->
                        SvgBox(lf.rect, "${lf.node.name} (${lf.formattedValue})", theme.seriesFill(lf.colorIndex), theme.seriesColor(lf.colorIndex), theme.nodeText)
                    },
                )
            }
            is MaradonaDiagramModel.C4Model -> {
                val d = model.diagram
                val layout = C4Layout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.boundaries.map { pb ->
                        val hdr = listOfNotNull(pb.boundary.label, pb.boundary.type?.takeIf { it.isNotBlank() }?.let { "[$it]" }).joinToString(" ")
                        SvgBox(pb.rect, hdr, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.elements.map { pe ->
                        val cardText = listOfNotNull(
                            pe.element.label,
                            pe.element.technology?.takeIf { it.isNotBlank() }?.let { "[$it]" },
                            pe.element.description?.takeIf { it.isNotBlank() },
                        ).joinToString("\n")
                        SvgBox(
                            rect = pe.rect,
                            label = cardText,
                            fill = theme.c4Palette.systemFill,
                            stroke = theme.c4Palette.systemBorder,
                            textColor = theme.c4Palette.systemText,
                            url = pe.element.link,
                            tooltip = pe.element.description,
                        )
                    },
                    lines = layout.relationships.map { pr ->
                        val relLabel = listOfNotNull(
                            pr.relationship.label,
                            pr.relationship.technology?.takeIf { it.isNotBlank() }?.let { "[$it]" },
                        ).joinToString(" ")
                        SvgLine(
                            points = pr.points,
                            color = theme.edge,
                            dashed = true,
                            label = relLabel to pr.labelRect,
                            startMarker = if (pr.startArrow) SvgMarker.FilledArrow else SvgMarker.None,
                            endMarker = if (pr.endArrow) SvgMarker.FilledArrow else SvgMarker.None,
                        )
                    },
                )
            }
            is MaradonaDiagramModel.ZenUmlModel -> {
                val d = model.diagram
                val layout = ZenUmlLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.groups.map { pg ->
                        SvgBox(pg.rect, pg.group.name, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    } + layout.blocks.map { blk ->
                        val hdr = listOfNotNull(blk.keyword, blk.condition?.takeIf { it.isNotBlank() }?.let { "[$it]" }).joinToString(" ")
                        SvgBox(blk.rect, hdr, Color.Transparent, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.participants.flatMap { pp ->
                        listOfNotNull(
                            SvgBox(pp.topRect, pp.participant.label, theme.nodeFill, theme.nodeBorder, theme.nodeText),
                            pp.bottomRect?.let { SvgBox(it, pp.participant.label, theme.nodeFill, theme.nodeBorder, theme.nodeText) },
                        )
                    } + layout.activations.map { act ->
                        SvgBox(act.rect, "", theme.subgraphFill, theme.nodeBorder, theme.nodeText)
                    } + layout.messages.mapNotNull { pm ->
                        val ct = pm.commentText
                        val cr = pm.commentRect
                        if (!ct.isNullOrBlank() && cr != null) SvgBox(cr, ct, theme.noteFill, theme.noteBorder, theme.noteText) else null
                    },
                    lines = layout.participants.map { pp ->
                        SvgLine(
                            points = listOf(DiagramPoint(pp.lifelineX, pp.lifelineTopY), DiagramPoint(pp.lifelineX, pp.lifelineBottomY)),
                            color = theme.subgraphBorder,
                            dashed = true,
                            label = null to null,
                        )
                    } + layout.blocks.flatMap { blk ->
                        blk.dividers.map { div ->
                            SvgLine(
                                points = listOf(DiagramPoint(blk.rect.x, div.y), DiagramPoint(blk.rect.right, div.y)),
                                color = theme.subgraphBorder,
                                dashed = true,
                                label = div.label to div.labelRect,
                            )
                        }
                    } + layout.messages.map { pm ->
                        val pts = if (pm.isSelf) {
                            val loopW = 32f
                            val loopH = pm.selfLoopHeight.coerceAtLeast(18f)
                            listOf(
                                DiagramPoint(pm.startX, pm.y),
                                DiagramPoint(pm.startX + loopW, pm.y),
                                DiagramPoint(pm.startX + loopW, pm.y + loopH),
                                DiagramPoint(pm.startX, pm.y + loopH),
                            )
                        } else {
                            listOf(DiagramPoint(pm.startX, pm.y), DiagramPoint(pm.endX, pm.y))
                        }
                        SvgLine(
                            points = pts,
                            color = theme.edge,
                            dashed = pm.kind == dev.maradona.model.ZenUmlMessageKind.RETURN,
                            label = pm.displayText to pm.labelRect,
                            endMarker = if (pm.kind == dev.maradona.model.ZenUmlMessageKind.ASYNC) SvgMarker.OpenArrow else SvgMarker.FilledArrow,
                        )
                    },
                )
            }
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
        val w = layout.width.roundToInt().coerceAtLeast(1)
        val h = layout.height.roundToInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h">""").append('\n')
        sb.append("""  <rect width="100%" height="100%" fill="${theme.background.toSvgHex()}"/>""").append('\n')

        // 1. Subgraph containers
        for (psg in layout.subgraphs) {
            val r = psg.rect
            val sgLabel = escapeXml(plainTextLabel(psg.subgraph.label))
            sb.append(
                """  <g class="subgraph" data-id="${escapeXml(psg.subgraph.id)}">""" + "\n" +
                    """    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.subgraphFill.toSvgHex()}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1"/>""" + "\n" +
                    """    <text x="${fmt(r.centerX)}" y="${fmt(r.y + 18f)}" text-anchor="middle" fill="${theme.subgraphText.toSvgHex()}" font-family="sans-serif" font-size="${fmt(theme.subgraphFontSize.value)}" font-weight="600">$sgLabel</text>""" + "\n" +
                    """  </g>""",
            ).append('\n')
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
                appendSvgArrowHead(sb, pe.points.last(), pe.points[pe.points.size - 2], pe.edge.endHead, edgeColor)
            }
            if (pe.edge.startHead != ArrowHead.None) {
                appendSvgArrowHead(sb, pe.points.first(), pe.points[1], pe.edge.startHead, edgeColor)
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
                    rawText = lbl,
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
            val lineH = fontSize * 1.35f
            val startY = r.centerY - ((lines.size - 1) * lineH) / 2f + fontSize * 0.35f
            for ((idx, line) in lines.withIndex()) {
                val ly = startY + idx * lineH
                sb.append(
                    """    <text x="${fmt(r.centerX)}" y="${fmt(ly)}" text-anchor="middle" fill="$textColor" font-family="sans-serif" font-size="${fmt(fontSize)}">${escapeXml(line)}</text>""",
                ).append('\n')
            }
            sb.append("""  </g>""").append('\n')
            if (url != null) {
                sb.append("""  </a>""").append('\n')
            }
        }

        sb.append("</svg>")
        return sb.toString()
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
            NodeShape.Diamond -> {
                val pts = "${fmt(r.centerX)},$y ${fmt(r.right)},${fmt(r.centerY)} ${fmt(r.centerX)},${fmt(r.bottom)} $x,${fmt(r.centerY)}"
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Hexagon -> {
                val inset = r.height / 4f
                val pts = "${fmt(r.x + inset)},$y ${fmt(r.right - inset)},$y ${fmt(r.right)},${fmt(r.centerY)} ${fmt(r.right - inset)},${fmt(r.bottom)} ${fmt(r.x + inset)},${fmt(r.bottom)} $x,${fmt(r.centerY)}"
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Parallelogram -> {
                val skew = r.height * 0.3f
                val pts = "${fmt(r.x + skew)},$y ${fmt(r.right)},$y ${fmt(r.right - skew)},${fmt(r.bottom)} $x,${fmt(r.bottom)}"
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.ParallelogramAlt -> {
                val skew = r.height * 0.3f
                val pts = "$x,$y ${fmt(r.right - skew)},$y ${fmt(r.right)},${fmt(r.bottom)} ${fmt(r.x + skew)},${fmt(r.bottom)}"
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Trapezoid -> {
                val skew = r.height * 0.3f
                val pts = "${fmt(r.x + skew)},$y ${fmt(r.right - skew)},$y ${fmt(r.right)},${fmt(r.bottom)} $x,${fmt(r.bottom)}"
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.TrapezoidAlt -> {
                val skew = r.height * 0.3f
                val pts = "$x,$y ${fmt(r.right)},$y ${fmt(r.right - skew)},${fmt(r.bottom)} ${fmt(r.x + skew)},${fmt(r.bottom)}"
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Asymmetric -> {
                val notch = r.height / 2f
                val pts = "$x,$y ${fmt(r.right)},$y ${fmt(r.right)},${fmt(r.bottom)} $x,${fmt(r.bottom)} ${fmt(r.x + notch)},${fmt(r.centerY)}"
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Cylinder ->
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" rx="8" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
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
                sb.append("""  <circle cx="${fmt(tip.x)}" cy="${fmt(tip.y)}" r="4" fill="none" stroke="$color" stroke-width="1.5"/>""").append('\n')
            }
            ArrowHead.Cross -> {
                val d = 4f
                sb.append("""  <line x1="${fmt(tip.x - d)}" y1="${fmt(tip.y - d)}" x2="${fmt(tip.x + d)}" y2="${fmt(tip.y + d)}" stroke="$color" stroke-width="1.8"/>""").append('\n')
                sb.append("""  <line x1="${fmt(tip.x - d)}" y1="${fmt(tip.y + d)}" x2="${fmt(tip.x + d)}" y2="${fmt(tip.y - d)}" stroke="$color" stroke-width="1.8"/>""").append('\n')
            }
            ArrowHead.None -> Unit
        }
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
        ErExactlyOne,
        ErZeroOrOne,
        ErOneOrMore,
        ErZeroOrMore,
    }

    private fun mapClassEndMarker(end: dev.maradona.model.ClassRelationEnd): SvgMarker = when (end) {
        dev.maradona.model.ClassRelationEnd.None -> SvgMarker.None
        dev.maradona.model.ClassRelationEnd.Extension -> SvgMarker.HollowTriangle
        dev.maradona.model.ClassRelationEnd.Composition -> SvgMarker.FilledDiamond
        dev.maradona.model.ClassRelationEnd.Aggregation -> SvgMarker.HollowDiamond
        dev.maradona.model.ClassRelationEnd.Dependency -> SvgMarker.OpenArrow
        dev.maradona.model.ClassRelationEnd.Lollipop -> SvgMarker.Lollipop
    }

    private fun mapErCardinalityMarker(card: dev.maradona.model.ErCardinality): SvgMarker = when (card) {
        dev.maradona.model.ErCardinality.ExactlyOne -> SvgMarker.ErExactlyOne
        dev.maradona.model.ErCardinality.ZeroOrOne -> SvgMarker.ErZeroOrOne
        dev.maradona.model.ErCardinality.OneOrMore -> SvgMarker.ErOneOrMore
        dev.maradona.model.ErCardinality.ZeroOrMore -> SvgMarker.ErZeroOrMore
    }

    private fun mapBlockNodeShape(shape: dev.maradona.model.BlockShape): NodeShape = when (shape) {
        dev.maradona.model.BlockShape.Rect, dev.maradona.model.BlockShape.BlockArrow, dev.maradona.model.BlockShape.Composite, dev.maradona.model.BlockShape.Space -> NodeShape.Rect
        dev.maradona.model.BlockShape.Round -> NodeShape.RoundedRect
        dev.maradona.model.BlockShape.Stadium -> NodeShape.Stadium
        dev.maradona.model.BlockShape.Subroutine -> NodeShape.Subroutine
        dev.maradona.model.BlockShape.Cylinder -> NodeShape.Cylinder
        dev.maradona.model.BlockShape.Circle -> NodeShape.Circle
        dev.maradona.model.BlockShape.DoubleCircle -> NodeShape.DoubleCircle
        dev.maradona.model.BlockShape.Asymmetric -> NodeShape.Asymmetric
        dev.maradona.model.BlockShape.Diamond -> NodeShape.Diamond
        dev.maradona.model.BlockShape.Hexagon -> NodeShape.Hexagon
        dev.maradona.model.BlockShape.LeanRight -> NodeShape.Parallelogram
        dev.maradona.model.BlockShape.LeanLeft -> NodeShape.ParallelogramAlt
        dev.maradona.model.BlockShape.Trapezoid -> NodeShape.Trapezoid
        dev.maradona.model.BlockShape.InvTrapezoid -> NodeShape.TrapezoidAlt
    }

    private fun buildBlockArrowPoints(r: DiagramRect, dir: dev.maradona.model.BlockArrowDir): String {
        val x = r.x
        val y = r.y
        val right = r.right
        val bottom = r.bottom
        val cx = r.centerX
        val cy = r.centerY
        val sw = r.width * 0.22f
        val sh = r.height * 0.22f
        return when (dir) {
            dev.maradona.model.BlockArrowDir.Right ->
                "${fmt(x)},${fmt(y + sh)} ${fmt(right - sw)},${fmt(y + sh)} ${fmt(right - sw)},${fmt(y)} ${fmt(right)},${fmt(cy)} ${fmt(right - sw)},${fmt(bottom)} ${fmt(right - sw)},${fmt(bottom - sh)} ${fmt(x)},${fmt(bottom - sh)}"
            dev.maradona.model.BlockArrowDir.Left ->
                "${fmt(right)},${fmt(y + sh)} ${fmt(x + sw)},${fmt(y + sh)} ${fmt(x + sw)},${fmt(y)} ${fmt(x)},${fmt(cy)} ${fmt(x + sw)},${fmt(bottom)} ${fmt(x + sw)},${fmt(bottom - sh)} ${fmt(right)},${fmt(bottom - sh)}"
            dev.maradona.model.BlockArrowDir.Down ->
                "${fmt(x + sw)},${fmt(y)} ${fmt(right - sw)},${fmt(y)} ${fmt(right - sw)},${fmt(bottom - sh)} ${fmt(right)},${fmt(bottom - sh)} ${fmt(cx)},${fmt(bottom)} ${fmt(x)},${fmt(bottom - sh)} ${fmt(x + sw)},${fmt(bottom - sh)}"
            dev.maradona.model.BlockArrowDir.Up ->
                "${fmt(x + sw)},${fmt(bottom)} ${fmt(right - sw)},${fmt(bottom)} ${fmt(right - sw)},${fmt(y + sh)} ${fmt(right)},${fmt(y + sh)} ${fmt(cx)},${fmt(y)} ${fmt(x)},${fmt(y + sh)} ${fmt(x + sw)},${fmt(y + sh)}"
            dev.maradona.model.BlockArrowDir.X ->
                "${fmt(x)},${fmt(cy)} ${fmt(x + sw)},${fmt(y)} ${fmt(right - sw)},${fmt(y)} ${fmt(right)},${fmt(cy)} ${fmt(right - sw)},${fmt(bottom)} ${fmt(x + sw)},${fmt(bottom)}"
            dev.maradona.model.BlockArrowDir.Y ->
                "${fmt(cx)},${fmt(y)} ${fmt(right)},${fmt(y + sh)} ${fmt(right)},${fmt(bottom - sh)} ${fmt(cx)},${fmt(bottom)} ${fmt(x)},${fmt(bottom - sh)} ${fmt(x)},${fmt(y + sh)}"
        }
    }

    private fun mapBlockEndMarker(head: dev.maradona.model.BlockArrowHead): SvgMarker = when (head) {
        dev.maradona.model.BlockArrowHead.None -> SvgMarker.None
        dev.maradona.model.BlockArrowHead.Point -> SvgMarker.FilledArrow
        dev.maradona.model.BlockArrowHead.Circle -> SvgMarker.Circle
        dev.maradona.model.BlockArrowHead.Cross -> SvgMarker.Cross
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

    private data class SvgBox(
        val rect: DiagramRect,
        val label: String,
        val fill: Color,
        val stroke: Color,
        val textColor: Color,
        val url: String? = null,
        val tooltip: String? = null,
        val shape: NodeShape = NodeShape.RoundedRect,
        val rotationDeg: Float = 0f,
    )

    private data class SvgLine(
        val points: List<DiagramPoint>,
        val color: Color,
        val dashed: Boolean,
        val label: Pair<String?, DiagramRect?>,
        val startMarker: SvgMarker = SvgMarker.None,
        val endMarker: SvgMarker = SvgMarker.None,
        val startLabel: Pair<String?, DiagramRect?> = null to null,
        val endLabel: Pair<String?, DiagramRect?> = null to null,
    )

    private fun buildGenericBoxSvg(
        width: Float,
        height: Float,
        theme: MaradonaTheme,
        title: Pair<String?, DiagramRect?> = null to null,
        containers: List<SvgBox> = emptyList(),
        boxes: List<SvgBox> = emptyList(),
        lines: List<SvgLine> = emptyList(),
        extraSvgElements: List<String> = emptyList(),
        overlayLines: List<SvgLine> = emptyList(),
        overlaySvgElements: List<String> = emptyList(),
    ): String {
        val w = width.roundToInt().coerceAtLeast(1)
        val h = height.roundToInt().coerceAtLeast(1)
        val bgHex = theme.background.toSvgHex()
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h">""").append('\n')
        sb.append("""  <rect width="100%" height="100%" fill="$bgHex"/>""").append('\n')

        val (titleText, titleRect) = title
        if (!titleText.isNullOrBlank() && titleRect != null) {
            sb.append(
                """  <text x="${fmt(titleRect.centerX)}" y="${fmt(titleRect.centerY + 5f)}" text-anchor="middle" fill="${theme.nodeText.toSvgHex()}" font-family="sans-serif" font-size="${fmt(theme.titleFontSize.value)}" font-weight="600">${escapeXml(plainTextLabel(titleText))}</text>""",
            ).append('\n')
        }

        for (elem in extraSvgElements) {
            sb.append(elem).append('\n')
        }

        for (c in containers) {
            val r = c.rect
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${c.fill.toSvgHex()}" stroke="${c.stroke.toSvgHex()}" stroke-width="1.2"/>""",
            ).append('\n')
            if (c.label.isNotBlank()) {
                sb.append(
                    """  <text x="${fmt(r.centerX)}" y="${fmt(r.y + 18f)}" text-anchor="middle" fill="${c.textColor.toSvgHex()}" font-family="sans-serif" font-size="${fmt(theme.subgraphFontSize.value)}" font-weight="600">${escapeXml(plainTextLabel(c.label))}</text>""",
                ).append('\n')
            }
        }

        fun emitSvgLines(lineList: List<SvgLine>) {
            for (ln in lineList) {
                if (ln.points.size < 2) continue
                val d = buildSvgEdgePath(ln.points)
                val dashAttr = if (ln.dashed) """ stroke-dasharray="5,4"""" else ""
                val lineColorHex = ln.color.toSvgHex()
                sb.append("""  <path d="$d" fill="none" stroke="$lineColorHex" stroke-width="1.6"$dashAttr/>""").append('\n')
                if (ln.startMarker != SvgMarker.None) {
                    appendSvgMarker(sb, ln.points.first(), ln.points[1], ln.startMarker, lineColorHex, bgHex)
                }
                if (ln.endMarker != SvgMarker.None) {
                    appendSvgMarker(sb, ln.points.last(), ln.points[ln.points.size - 2], ln.endMarker, lineColorHex, bgHex)
                }
                for ((lbl, lr) in listOf(ln.label, ln.startLabel, ln.endLabel)) {
                    if (!lbl.isNullOrBlank() && lr != null) {
                        appendMultilineText(
                            sb = sb,
                            indent = "  ",
                            centerX = lr.centerX,
                            centerY = lr.centerY,
                            rawText = lbl,
                            colorHex = theme.edgeLabelText.toSvgHex(),
                            fontSize = theme.edgeLabelFontSize.value,
                        )
                    }
                }
            }
        }

        emitSvgLines(lines)

        for (b in boxes) {
            val r = b.rect
            val url = b.url?.takeIf { it.isNotBlank() }
            if (url != null) {
                sb.append("""  <a href="${escapeXml(url)}">""").append('\n')
            }
            sb.append("""  <g class="node">""").append('\n')
            b.tooltip?.takeIf { it.isNotBlank() }?.let {
                sb.append("""    <title>${escapeXml(it)}</title>""").append('\n')
            }
            appendSvgNodeShape(sb, b.shape, r, b.fill.toSvgHex(), b.stroke.toSvgHex(), 1.3f)
            if (b.label.isNotBlank()) {
                appendMultilineText(
                    sb = sb,
                    indent = "    ",
                    centerX = r.centerX,
                    centerY = r.centerY,
                    rawText = b.label,
                    colorHex = b.textColor.toSvgHex(),
                    fontSize = theme.nodeFontSize.value,
                    rotationDeg = b.rotationDeg,
                )
            }
            sb.append("""  </g>""").append('\n')
            if (url != null) {
                sb.append("""  </a>""").append('\n')
            }
        }

        emitSvgLines(overlayLines)
        for (elem in overlaySvgElements) {
            sb.append(elem).append('\n')
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun appendMultilineText(
        sb: StringBuilder,
        indent: String,
        centerX: Float,
        centerY: Float,
        rawText: String,
        colorHex: String,
        fontSize: Float,
        rotationDeg: Float = 0f,
    ) {
        val textLines = plainTextLabel(rawText).split('\n')
        val lineH = fontSize * 1.35f
        val startY = centerY - ((textLines.size - 1) * lineH) / 2f + fontSize * 0.35f
        val rotateAttr = if (rotationDeg != 0f) {
            """ transform="rotate(${fmt(rotationDeg)} ${fmt(centerX)} ${fmt(centerY)})""""
        } else {
            ""
        }
        for ((idx, textLine) in textLines.withIndex()) {
            val ly = startY + idx * lineH
            sb.append(
                """$indent<text x="${fmt(centerX)}" y="${fmt(ly)}" text-anchor="middle" fill="$colorHex" font-family="sans-serif" font-size="${fmt(fontSize)}"$rotateAttr>${escapeXml(textLine)}</text>""",
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
