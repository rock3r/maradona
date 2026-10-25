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
                    boxes = layout.participants.flatMap { pp ->
                        listOfNotNull(
                            SvgBox(pp.topRect, pp.participant.label, theme.nodeFill, theme.nodeBorder, theme.nodeText),
                            pp.bottomRect?.let { SvgBox(it, pp.participant.label, theme.nodeFill, theme.nodeBorder, theme.nodeText) },
                        )
                    } + layout.notes.map { n ->
                        SvgBox(n.rect, n.note.text, theme.noteFill, theme.noteBorder, theme.noteText)
                    },
                    lines = layout.messages.map { m ->
                        SvgLine(
                            points = listOf(DiagramPoint(m.startX, m.lineY), DiagramPoint(m.stopX, m.lineY)),
                            color = theme.edge,
                            dashed = m.statement.arrow.dashed,
                            label = m.statement.text to m.labelRect,
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
                        SvgLine(r.points, theme.edge, r.relation.dashed, r.relation.label to r.labelRect)
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
                        SvgBox(
                            rect = ps.rect,
                            label = ps.state.label,
                            fill = parseCssColor(ps.state.style?.fill) ?: theme.nodeFill,
                            stroke = parseCssColor(ps.state.style?.stroke) ?: theme.nodeBorder,
                            textColor = parseCssColor(ps.state.style?.textColor) ?: theme.nodeText,
                        )
                    },
                    lines = layout.transitions.map { tr ->
                        SvgLine(tr.points, theme.edge, false, tr.transition.label to tr.labelRect)
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
                        SvgBox(
                            rect = pe.rect,
                            label = pe.entity.label,
                            fill = parseCssColor(pe.entity.style?.fill) ?: theme.nodeFill,
                            stroke = parseCssColor(pe.entity.style?.stroke) ?: theme.nodeBorder,
                            textColor = parseCssColor(pe.entity.style?.textColor) ?: theme.nodeText,
                        )
                    },
                    lines = layout.relationships.map { pr ->
                        SvgLine(pr.points, theme.edge, !pr.relationship.identifying, pr.relationship.label to pr.labelRect)
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
                        SvgBox(pn.rect, pn.node.label, theme.nodeFill, stroke, theme.nodeText)
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
                    boxes = layout.tasks.map { pt ->
                        val idx = pt.sectionIndex.coerceAtLeast(0)
                        SvgBox(
                            rect = pt.barRect,
                            label = pt.task.label,
                            fill = theme.seriesFill(idx),
                            stroke = theme.seriesColor(idx),
                            textColor = theme.nodeText,
                            url = pt.task.action?.url,
                            tooltip = pt.task.action?.tooltip,
                        )
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
                            add("""  <circle class="pie-slice" cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(layout.outerRadius)}" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(layout.outerStrokeWidth)}"/>""")
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
                    boxes = layout.legendItems.map { li ->
                        SvgBox(li.labelRect, li.displayText, theme.seriesFill(li.index), theme.seriesColor(li.index), theme.nodeText)
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
                    boxes = layout.commits.map { pc ->
                        SvgBox(pc.nodeRect, pc.commit.id, theme.seriesFill(pc.laneIndex), theme.seriesColor(pc.laneIndex), theme.nodeText)
                    },
                    lines = layout.edges.map { e ->
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
                    boxes = layout.periods.map { pp ->
                        SvgBox(pp.rect, pp.period.title, theme.seriesFill(pp.colorIndex), theme.seriesColor(pp.colorIndex), theme.nodeText)
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
                    boxes = layout.tasks.map { pt ->
                        SvgBox(pt.cardRect, pt.task.label, theme.seriesFill(pt.sectionIndex), theme.seriesColor(pt.sectionIndex), theme.nodeText)
                    },
                )
            }
            is MaradonaDiagramModel.QuadrantModel -> {
                val d = model.diagram
                val layout = QuadrantLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.points.map { pt ->
                        SvgBox(pt.labelRect, pt.point.label, theme.accentFill, theme.accentBorder, theme.nodeText)
                    },
                )
            }
            is MaradonaDiagramModel.XyChartModel -> {
                val d = model.diagram
                val layout = XyChartLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    boxes = layout.bars.map { b ->
                        SvgBox(b.rect, "", theme.seriesColor(b.seriesIndex), theme.seriesColor(b.seriesIndex), theme.nodeText)
                    },
                    lines = layout.lines.map { ls ->
                        SvgLine(ls.points.map { it.position }, theme.seriesColor(ls.seriesIndex), false, null to null)
                    },
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
                            SvgBox(
                                rect = pt.cardRect,
                                label = pt.task.label,
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
                    boxes = layout.nodes.map { pn ->
                        SvgBox(pn.rect, pn.labelText, theme.seriesColor(pn.nodeIndex), theme.nodeBorder, theme.nodeText)
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
                        SvgBox(pi.rect, pi.item.name, theme.nodeFill, theme.nodeBorder, theme.nodeText)
                    },
                    lines = layout.relations.map { pr ->
                        SvgLine(pr.points, theme.edge, !pr.relation.isContains, pr.relation.label to pr.labelRect)
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
                        SvgBox(pb.rect, pb.block.label, theme.nodeFill, theme.nodeBorder, theme.nodeText)
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
                    },
                    lines = layout.curves.map { pc ->
                        SvgLine(pc.points + listOfNotNull(pc.points.firstOrNull()), theme.seriesColor(pc.curveIndex), false, null to null)
                    },
                )
            }
            is MaradonaDiagramModel.BlockModel -> {
                val d = model.diagram
                val layout = BlockLayout.layout(diagram = d, options = options)
                buildGenericBoxSvg(
                    width = layout.width,
                    height = layout.height,
                    theme = theme,
                    title = d.title to layout.titleRect,
                    containers = layout.blocks.filter { it.isCompositeContainer }.map { pb ->
                        SvgBox(pb.rect, pb.item.label, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.blocks.filter { !it.isCompositeContainer }.map { pb ->
                        SvgBox(
                            rect = pb.rect,
                            label = pb.item.label,
                            fill = parseCssColor(pb.item.style?.fill) ?: theme.nodeFill,
                            stroke = parseCssColor(pb.item.style?.stroke) ?: theme.nodeBorder,
                            textColor = parseCssColor(pb.item.style?.textColor) ?: theme.nodeText,
                        )
                    },
                    lines = layout.edges.map { pe ->
                        SvgLine(pe.points, theme.edge, false, pe.edge.label to pe.labelRect)
                    },
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
                    boxes = layout.services.map { ps ->
                        SvgBox(ps.iconRect, ps.service.title ?: ps.service.id, theme.accentBorder, theme.accentBorder, theme.accentText)
                    },
                    lines = layout.edges.map { pe ->
                        SvgLine(pe.points, theme.edge, false, pe.edge.title to pe.labelRect)
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
                        SvgBox(pb.rect, pb.boundary.label, theme.subgraphFill, theme.subgraphBorder, theme.subgraphText)
                    },
                    boxes = layout.elements.map { pe ->
                        SvgBox(
                            rect = pe.rect,
                            label = pe.element.label,
                            fill = theme.c4Palette.systemFill,
                            stroke = theme.c4Palette.systemBorder,
                            textColor = theme.c4Palette.systemText,
                            url = pe.element.link,
                            tooltip = pe.element.description,
                        )
                    },
                    lines = layout.relationships.map { pr ->
                        SvgLine(pr.points, theme.edge, true, pr.relationship.label to pr.labelRect)
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
                    },
                    boxes = layout.participants.map { pp ->
                        SvgBox(pp.topRect, pp.participant.label, theme.nodeFill, theme.nodeBorder, theme.nodeText)
                    },
                    lines = layout.messages.map { pm ->
                        SvgLine(
                            points = listOf(DiagramPoint(pm.startX, pm.y), DiagramPoint(pm.endX, pm.y)),
                            color = theme.edge,
                            dashed = false,
                            label = pm.displayText to pm.labelRect,
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
                val plainLbl = escapeXml(plainTextLabel(lbl))
                sb.append(
                    """  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""" + "\n" +
                        """  <text x="${fmt(lr.centerX)}" y="${fmt(lr.centerY + 4f)}" text-anchor="middle" fill="${theme.edgeLabelText.toSvgHex()}" font-family="sans-serif" font-size="${fmt(theme.edgeLabelFontSize.value)}">$plainLbl</text>""",
                ).append('\n')
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
            var cur = StringBuilder()
            for (word in words) {
                if (cur.isEmpty()) {
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
                } else if (cur.length + 1 + word.length <= maxChars) {
                    cur.append(' ').append(word)
                } else {
                    out += cur.toString()
                    cur = StringBuilder(word)
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

    private data class SvgBox(
        val rect: DiagramRect,
        val label: String,
        val fill: Color,
        val stroke: Color,
        val textColor: Color,
        val url: String? = null,
        val tooltip: String? = null,
    )

    private data class SvgLine(
        val points: List<DiagramPoint>,
        val color: Color,
        val dashed: Boolean,
        val label: Pair<String?, DiagramRect?>,
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
    ): String {
        val w = width.roundToInt().coerceAtLeast(1)
        val h = height.roundToInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h">""").append('\n')
        sb.append("""  <rect width="100%" height="100%" fill="${theme.background.toSvgHex()}"/>""").append('\n')

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

        for (ln in lines) {
            if (ln.points.size < 2) continue
            val d = buildSvgEdgePath(ln.points)
            val dashAttr = if (ln.dashed) """ stroke-dasharray="5,4"""" else ""
            sb.append("""  <path d="$d" fill="none" stroke="${ln.color.toSvgHex()}" stroke-width="1.6"$dashAttr/>""").append('\n')
            val (lbl, lr) = ln.label
            if (!lbl.isNullOrBlank() && lr != null) {
                sb.append(
                    """  <text x="${fmt(lr.centerX)}" y="${fmt(lr.centerY + 4f)}" text-anchor="middle" fill="${theme.edgeLabelText.toSvgHex()}" font-family="sans-serif" font-size="${fmt(theme.edgeLabelFontSize.value)}">${escapeXml(plainTextLabel(lbl))}</text>""",
                ).append('\n')
            }
        }

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
            sb.append(
                """    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="5" fill="${b.fill.toSvgHex()}" stroke="${b.stroke.toSvgHex()}" stroke-width="1.3"/>""",
            ).append('\n')
            if (b.label.isNotBlank()) {
                val fontSize = theme.nodeFontSize.value
                val textLines = plainTextLabel(b.label).split('\n')
                val lineH = fontSize * 1.35f
                val startY = r.centerY - ((textLines.size - 1) * lineH) / 2f + fontSize * 0.35f
                for ((idx, textLine) in textLines.withIndex()) {
                    val ly = startY + idx * lineH
                    sb.append(
                        """    <text x="${fmt(r.centerX)}" y="${fmt(ly)}" text-anchor="middle" fill="${b.textColor.toSvgHex()}" font-family="sans-serif" font-size="${fmt(fontSize)}">${escapeXml(textLine)}</text>""",
                    ).append('\n')
                }
            }
            sb.append("""  </g>""").append('\n')
            if (url != null) {
                sb.append("""  </a>""").append('\n')
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun Color.toSvgHex(): String {
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
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
