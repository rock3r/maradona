package dev.maradona.export

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ArchitectureDiagramLayout
import dev.maradona.layout.ArchitectureLayout
import dev.maradona.layout.BlockDiagramLayout
import dev.maradona.layout.BlockLayout
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
import dev.maradona.layout.ErEntityMetrics
import dev.maradona.layout.ErLayout
import dev.maradona.layout.FlowchartLayout
import dev.maradona.layout.GanttDiagramLayout
import dev.maradona.layout.GanttLayout
import dev.maradona.layout.GitGraphDiagramLayout
import dev.maradona.layout.GitGraphLayout
import dev.maradona.layout.JourneyDiagramLayout
import dev.maradona.layout.JourneyLayout
import dev.maradona.layout.KanbanDiagramLayout
import dev.maradona.layout.KanbanLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MindmapDiagramLayout
import dev.maradona.layout.MindmapLayout
import dev.maradona.layout.NodeOutline
import dev.maradona.layout.NodeSizing
import dev.maradona.layout.PacketDiagramLayout
import dev.maradona.layout.PacketLayout
import dev.maradona.layout.PieDiagramLayout
import dev.maradona.layout.PieLayout
import dev.maradona.layout.QuadrantDiagramLayout
import dev.maradona.layout.QuadrantLayout
import dev.maradona.layout.RadarDiagramLayout
import dev.maradona.layout.RadarLayout
import dev.maradona.layout.RequirementBoxMetrics
import dev.maradona.layout.RequirementDiagramLayout
import dev.maradona.layout.RequirementLayout
import dev.maradona.layout.SankeyDiagramLayout
import dev.maradona.layout.SankeyLayout
import dev.maradona.layout.SequenceDiagramLayout
import dev.maradona.layout.SequenceLayout
import dev.maradona.layout.StateBoxMetrics
import dev.maradona.layout.StateDiagramLayout
import dev.maradona.layout.StateLayout
import dev.maradona.layout.TimelineDiagramLayout
import dev.maradona.layout.TimelineLayout
import dev.maradona.layout.TreemapDiagramLayout
import dev.maradona.layout.TreemapLayout
import dev.maradona.layout.XyChartDiagramLayout
import dev.maradona.layout.XyChartLayout
import dev.maradona.layout.ZenUmlDiagramLayout
import dev.maradona.layout.ZenUmlLayout
import dev.maradona.model.ArchitectureDiagram
import dev.maradona.model.ArrowHead
import dev.maradona.model.BlockArrowDir
import dev.maradona.model.BlockArrowHead
import dev.maradona.model.BlockDiagram
import dev.maradona.model.BlockEdgePattern
import dev.maradona.model.BlockShape
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
import dev.maradona.model.GanttDiagram
import dev.maradona.model.GanttTaskStatus
import dev.maradona.model.GitCommitType
import dev.maradona.model.GitGraphDiagram
import dev.maradona.model.JourneyDiagram
import dev.maradona.model.JourneyMood
import dev.maradona.model.KanbanDiagram
import dev.maradona.model.KanbanPriority
import dev.maradona.model.MindmapDiagram
import dev.maradona.model.MindmapShape
import dev.maradona.model.NodeShape
import dev.maradona.model.PacketDiagram
import dev.maradona.model.ParticipantKind
import dev.maradona.model.PieDiagram
import dev.maradona.model.QuadrantDiagram
import dev.maradona.model.RadarDiagram
import dev.maradona.model.RadarGraticule
import dev.maradona.model.RequirementDiagram
import dev.maradona.model.SankeyDiagram
import dev.maradona.model.SequenceBlockKind
import dev.maradona.model.SequenceDiagram
import dev.maradona.model.SequenceHead
import dev.maradona.model.StateDiagram
import dev.maradona.model.StateKind
import dev.maradona.model.TimelineDiagram
import dev.maradona.model.TreemapDiagram
import dev.maradona.model.XyChartDiagram
import dev.maradona.model.ZenUmlDiagram
import dev.maradona.model.ZenUmlMessageKind
import dev.maradona.model.ZenUmlParticipant
import dev.maradona.model.ZenUmlParticipantStereotype
import dev.maradona.parse.MaradonaDiagramModel
import dev.maradona.parse.MaradonaParser
import dev.maradona.render.FormattedTextSpan
import dev.maradona.render.MaradonaTheme
import dev.maradona.render.extractInlineImages
import dev.maradona.render.measureArchitectureLayoutForExport
import dev.maradona.render.measureBlockLayoutForExport
import dev.maradona.render.measureC4LayoutForExport
import dev.maradona.render.measureClassLayoutForExport
import dev.maradona.render.measureErLayoutForExport
import dev.maradona.render.measureFlowchartLayoutForExport
import dev.maradona.render.measureGanttLayoutForExport
import dev.maradona.render.measureGitGraphLayoutForExport
import dev.maradona.render.measureJourneyLayoutForExport
import dev.maradona.render.measureKanbanLayoutForExport
import dev.maradona.render.measureMindmapLayoutForExport
import dev.maradona.render.measurePacketLayoutForExport
import dev.maradona.render.measurePieLayoutForExport
import dev.maradona.render.measureQuadrantLayoutForExport
import dev.maradona.render.measureRadarLayoutForExport
import dev.maradona.render.measureRequirementLayoutForExport
import dev.maradona.render.measureSankeyLayoutForExport
import dev.maradona.render.measureSequenceLayoutForExport
import dev.maradona.render.measureStateLayoutForExport
import dev.maradona.render.measureTimelineLayoutForExport
import dev.maradona.render.measureTreemapLayoutForExport
import dev.maradona.render.measureXyChartLayoutForExport
import dev.maradona.render.measureZenUmlLayoutForExport
import dev.maradona.render.parseCssColor
import dev.maradona.render.parseFormattedLabelLines
import dev.maradona.render.plainTextLabel
import dev.maradona.render.resolveMermaidIconGlyph
import dev.maradona.render.wrapFormattedLabelLines
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
     *
     * When [textMeasurer] is supplied, node and label geometry is computed through the exact
     * same Compose [TextMeasurer] pipeline used by [dev.maradona.render.MaradonaDiagram] at
     * [density]. When [textMeasurer] is `null`, span-, icon-, and image-aware pure-Kotlin
     * sizing is used so headless export works without a Compose runtime.
     */
    fun exportToSvg(
        source: String,
        theme: MaradonaTheme = MaradonaTheme.light(),
        options: LayoutOptions = LayoutOptions(),
        textMeasurer: TextMeasurer? = null,
        density: Density = Density(options.density.coerceAtLeast(0.01f), 1f),
    ): String {
        val model = MaradonaParser.parse(source)
        if (textMeasurer != null) {
            val dens = density.density.coerceAtLeast(0.01f)
            val textScale = (dens * density.fontScale.coerceAtLeast(0.01f))
            val svgTheme = if (kotlin.math.abs(textScale - 1f) < 1e-4f) {
                theme
            } else {
                theme.copy(
                    nodeFontSize = theme.nodeFontSize * textScale,
                    edgeLabelFontSize = theme.edgeLabelFontSize * textScale,
                    subgraphFontSize = theme.subgraphFontSize * textScale,
                    titleFontSize = theme.titleFontSize * textScale,
                    captionFontSize = theme.captionFontSize * textScale,
                )
            }
            return when (model) {
                is MaradonaDiagramModel.FlowchartModel -> {
                    val imageUrls = model.chart.nodes.asSequence()
                        .flatMap { extractInlineImages(it.label).asSequence() }
                        .map { it.url }
                        .toSet()
                    val layout = measureFlowchartLayoutForExport(
                        chart = model.chart,
                        theme = theme,
                        layoutOptions = options,
                        densityObj = density,
                        textMeasurer = textMeasurer,
                        resolvedImageUrls = imageUrls,
                    )
                    exportFlowchartToSvg(model.chart, layout, svgTheme, textScale, textMeasurer, density)
                }
                is MaradonaDiagramModel.SequenceModel -> {
                    val seqOpts = dev.maradona.layout.SequenceLayoutOptions(density = dens)
                    val layout = measureSequenceLayoutForExport(model.diagram, theme, seqOpts, density, textMeasurer)
                    exportSequenceToSvg(model.diagram, layout, svgTheme, dens)
                }
                is MaradonaDiagramModel.ClassModel -> {
                    val layout = measureClassLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportClassToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.StateModel -> {
                    val layout = measureStateLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportStateToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.ErModel -> {
                    val layout = measureErLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportErToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.RequirementModel -> {
                    val layout = measureRequirementLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportRequirementToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.C4Model -> {
                    val layout = measureC4LayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportC4ToSvg(model.diagram, layout, svgTheme, dens, textMeasurer, density)
                }
                is MaradonaDiagramModel.ArchitectureModel -> {
                    val layout = measureArchitectureLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportArchitectureToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.ZenUmlModel -> {
                    val layout = measureZenUmlLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportZenUmlToSvg(model.diagram, layout, svgTheme, dens, textMeasurer, density)
                }
                is MaradonaDiagramModel.MindmapModel -> {
                    val layout = measureMindmapLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportMindmapToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.GanttModel -> {
                    val layout = measureGanttLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportGanttToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.PieModel -> {
                    val layout = measurePieLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportPieToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.GitGraphModel -> {
                    val layout = measureGitGraphLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportGitGraphToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.TimelineModel -> {
                    val layout = measureTimelineLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportTimelineToSvg(model.diagram, layout, svgTheme, dens, textMeasurer, density)
                }
                is MaradonaDiagramModel.JourneyModel -> {
                    val layout = measureJourneyLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportJourneyToSvg(model.diagram, layout, svgTheme, dens, textMeasurer, density)
                }
                is MaradonaDiagramModel.QuadrantModel -> {
                    val layout = measureQuadrantLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportQuadrantToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.XyChartModel -> {
                    val layout = measureXyChartLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportXyChartToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.KanbanModel -> {
                    val layout = measureKanbanLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportKanbanToSvg(model.diagram, layout, svgTheme, dens, textMeasurer, density)
                }
                is MaradonaDiagramModel.SankeyModel -> {
                    val layout = measureSankeyLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportSankeyToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.PacketModel -> {
                    val layout = measurePacketLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportPacketToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.RadarModel -> {
                    val layout = measureRadarLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportRadarToSvg(model.diagram, layout, svgTheme)
                }
                is MaradonaDiagramModel.BlockModel -> {
                    val layout = measureBlockLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportBlockToSvg(model.diagram, layout, svgTheme, dens)
                }
                is MaradonaDiagramModel.TreemapModel -> {
                    val layout = measureTreemapLayoutForExport(model.diagram, theme, options, density, textMeasurer)
                    exportTreemapToSvg(model.diagram, layout, svgTheme)
                }
            }
        }
        return when (model) {
            is MaradonaDiagramModel.FlowchartModel -> {
                val layout = FlowchartLayout.layout(
                    chart = model.chart,
                    nodeSize = { defaultFlowNodeSize(it, theme) },
                    edgeLabelSize = { e -> e.label?.let { defaultEdgeLabelSize(it, theme.edgeLabelFontSize.value) } },
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
                    messageLabelSize = { idx, m ->
                        val msgLabel = if (d.autonumber && m.text.isNotBlank()) "${idx + 1}. ${m.text}" else m.text
                        defaultTextSize(msgLabel, theme.nodeFontSize.value)
                    },
                    noteTextSize = { _, n -> defaultTextSize(n.text, theme.nodeFontSize.value) },
                    blockLabelSize = { _, _, b -> defaultTextSize(b, theme.edgeLabelFontSize.value) },
                    boxLabelSize = { _, bx -> bx.label?.let { defaultTextSize(it, theme.subgraphFontSize.value) } ?: DiagramSize(0f, 0f) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = seqOpts,
                )
                exportSequenceToSvg(d, layout, theme, options.density)
            }
            is MaradonaDiagramModel.ClassModel -> {
                val d = model.diagram
                val memberLineH = theme.edgeLabelFontSize.value * 1.4f
                val layout = ClassLayout.layout(
                    diagram = d,
                    classBoxSize = { c ->
                        val hdrLines = classHeaderSpanLines(c, theme)
                        val hdrW = hdrLines.maxOfOrNull { (lf, line) -> line.sumOf { it.text.length } * lf * 0.62f }?.coerceAtLeast(1f) ?: 1f
                        val hdrTextH = hdrLines.sumOf { (lf, _) -> (lf * 1.4f).toDouble() }.toFloat()
                        val memberWidths = (c.attributes + c.methods).map {
                            defaultTextSize(it.displayText, theme.edgeLabelFontSize.value).width
                        }
                        val maxMemberW = memberWidths.maxOrNull() ?: 0f
                        val showCompartments = !d.hideEmptyMembersBox || c.attributes.isNotEmpty() || c.methods.isNotEmpty()
                        val attrH = if (!showCompartments) 0f else if (c.attributes.isEmpty()) 8f else c.attributes.size * memberLineH + 12f
                        val methH = if (!showCompartments) 0f else if (c.methods.isEmpty()) 8f else c.methods.size * memberLineH + 12f
                        val hdrH = max(32f, hdrTextH + 16f)
                        val totalW = max(110f, max(hdrW, maxMemberW) + 24f)
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
                val dens = options.density.coerceAtLeast(0.01f)
                val compositeIds = d.states
                    .filter { s ->
                        s.kind == StateKind.Composite ||
                            d.states.any { it.parentId == s.id } ||
                            d.notes.any { it.parentId == s.id }
                    }
                    .map { it.id }
                    .toSet()
                val layout = StateLayout.layout(
                    diagram = d,
                    stateBoxSize = { s ->
                        val isComp = s.id in compositeIds
                        if (!isComp && s.kind != StateKind.Normal) {
                            StateLayout.defaultStateMetrics(s, dens)
                        } else {
                            val hdrFont = if (isComp) theme.subgraphFontSize.value else theme.nodeFontSize.value
                            val hdrSz = defaultTextSize(s.label, hdrFont)
                            val hdrH = max(28f * dens, hdrSz.height + 16f * dens)
                            if (isComp || s.descriptions.isEmpty()) {
                                val w = max(84f * dens, hdrSz.width + 28f * dens)
                                StateBoxMetrics(width = w, height = hdrH, headerHeight = hdrH, descriptionHeight = 0f)
                            } else {
                                val descSz = defaultTextSize(s.descriptions.joinToString("\n"), theme.edgeLabelFontSize.value)
                                val descH = descSz.height + 12f * dens
                                val w = maxOf(84f * dens, hdrSz.width + 28f * dens, descSz.width + 28f * dens)
                                StateBoxMetrics(width = w, height = hdrH + descH, headerHeight = hdrH, descriptionHeight = descH)
                            }
                        }
                    },
                    transitionLabelSize = { _, tr ->
                        tr.label?.takeIf { it.isNotEmpty() }?.let { defaultTextSize(it, theme.edgeLabelFontSize.value) }
                    },
                    noteBoxSize = { _, note ->
                        val sz = defaultTextSize(note.text, theme.edgeLabelFontSize.value)
                        DiagramSize(max(80f * dens, sz.width + 20f * dens), max(32f * dens, sz.height + 12f * dens))
                    },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportStateToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.ErModel -> {
                val d = model.diagram
                val dens = options.density.coerceAtLeast(0.01f)
                val regFont = theme.scaledSp(11.5f).value
                val capFont = theme.captionFontSize.value
                val layout = ErLayout.layout(
                    diagram = d,
                    entityBoxSize = { e ->
                        val hdrSz = defaultTextSize(e.label, theme.nodeFontSize.value)
                        val hdrH = max(32f * dens, hdrSz.height + 14f * dens)
                        if (e.attributes.isEmpty()) {
                            ErEntityMetrics(max(100f * dens, hdrSz.width + 28f * dens), hdrH, hdrH)
                        } else {
                            val rowSizes = e.attributes.map { attr ->
                                val regW = defaultTextSize("${attr.type} ${attr.name}", regFont).width
                                val keyW = attr.keys.takeIf { it.isNotEmpty() }?.let { defaultTextSize(" ${it.joinToString(", ")}", capFont).width } ?: 0f
                                val cmtW = attr.comment?.takeIf { it.isNotBlank() }?.let { defaultTextSize(" \"$it\"", capFont).width } ?: 0f
                                val rowH = max(regFont * 1.4f, capFont * 1.4f)
                                DiagramSize(regW + keyW + cmtW, rowH)
                            }
                            val rowHeights = rowSizes.map { max(22f * dens, it.height + 6f * dens) }
                            val maxRowW = rowSizes.maxOfOrNull { it.width } ?: 0f
                            val w = max(120f * dens, max(hdrSz.width, maxRowW) + 24f * dens)
                            ErEntityMetrics(w, hdrH + rowHeights.sum(), hdrH, rowHeights)
                        }
                    },
                    relationshipLabelSize = { _, rel ->
                        rel.label?.takeIf { it.isNotEmpty() }?.let { defaultTextSize(it, theme.edgeLabelFontSize.value) }
                    },
                    subgraphLabelSize = { sg -> defaultTextSize(sg.label, theme.subgraphFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportErToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.RequirementModel -> {
                val d = model.diagram
                val dens = options.density.coerceAtLeast(0.01f)
                val stereoFont = theme.captionFontSize.value
                val nameFont = theme.scaledSp(13.5f).value
                val bodyFont = theme.scaledSp(11.5f).value
                val relFont = theme.captionFontSize.value
                val layout = RequirementLayout.layout(
                    diagram = d,
                    itemBoxSize = { item ->
                        val stereoSz = defaultTextSize(item.stereotype, stereoFont)
                        val nameSz = defaultTextSize(item.name, nameFont)
                        val hdrH = max(44f * dens, stereoSz.height + nameSz.height + 16f * dens)
                        val bodySizes = item.bodyLines.map { defaultTextSize(it, bodyFont) }
                        val bodyHeights = bodySizes.map { max(18f * dens, it.height) }
                        val bodyH = if (bodyHeights.isEmpty()) 0f else bodyHeights.sum() + 14f * dens
                        val maxBodyW = bodySizes.maxOfOrNull { it.width } ?: 0f
                        val w = maxOf(150f * dens, stereoSz.width + 24f * dens, nameSz.width + 24f * dens, maxBodyW + 24f * dens)
                        RequirementBoxMetrics(
                            width = w,
                            height = hdrH + bodyH,
                            headerHeight = hdrH,
                            stereotypeHeight = hdrH * 0.45f,
                            titleHeight = hdrH * 0.55f,
                            bodyLineHeights = bodyHeights,
                        )
                    },
                    relationLabelSize = { _, rel -> defaultTextSize(rel.label, relFont) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportRequirementToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.C4Model -> {
                val d = model.diagram
                val dens = options.density.coerceAtLeast(0.01f)
                val elementSizes = d.elements.associate { el ->
                    val defaultSz = C4Layout.defaultElementSize(el, dens)
                    val initialLines = c4WrappedCardSpanLines(el, c4WrapWidth(defaultSz.width, dens), theme)
                    val maxLineW = initialLines.maxOfOrNull { (lineFont, line) ->
                        line.sumOf { span -> span.inlineImage?.let { 4 } ?: span.text.length } * lineFont * 0.62f
                    }?.coerceAtLeast(1f) ?: 1f
                    val neededW = max(defaultSz.width, (maxLineW + 28f * dens).coerceAtMost(280f * dens))
                    val wrappedLines = c4WrappedCardSpanLines(el, c4WrapWidth(neededW, dens), theme)
                    val personExtra = if (el.kind.isPerson) 22f * dens else 0f
                    val dbExtra = if (el.kind.isDatabase) 12f * dens else 0f
                    val totalTextH = wrappedLines.sumOf { (lineFont, _) -> (lineFont * 1.38f).toDouble() }.toFloat()
                    val neededH = max(defaultSz.height, totalTextH + 28f * dens + personExtra + dbExtra)
                    el.id to DiagramSize(neededW, neededH)
                }
                val boundaryHeaderSizes = d.boundaries.associate { b ->
                    val hdr = listOfNotNull(
                        b.label.takeIf { it.isNotBlank() },
                        b.type.takeIf { it.isNotBlank() }?.let { "[$it]" },
                    ).joinToString(" ")
                    b.id to defaultTextSize(hdr, theme.scaledSp(12.5f).value)
                }
                val relLabelSizes = d.relationships.mapIndexedNotNull { idx, rel ->
                    val indexedLabel = if (!rel.index.isNullOrBlank()) "${rel.index}: ${rel.label}" else rel.label
                    val mainFont = theme.scaledSp(10.5f).value
                    val techFont = theme.scaledSp(9.5f).value
                    val relWrapW = 188f * dens
                    val mainSz = if (indexedLabel.isNotBlank()) {
                        measureSpanLinesSize(wrapFormattedLabelLines(indexedLabel, mainFont, relWrapW), mainFont)
                    } else {
                        null
                    }
                    val techSz = if (!rel.technology.isNullOrBlank()) {
                        measureSpanLinesSize(wrapFormattedLabelLines("[${rel.technology}]", techFont, relWrapW), techFont)
                    } else {
                        null
                    }
                    if (mainSz == null && techSz == null) {
                        null
                    } else {
                        idx to DiagramSize(
                            width = max(mainSz?.width ?: 0f, techSz?.width ?: 0f),
                            height = (mainSz?.height ?: 0f) + (techSz?.height ?: 0f),
                        )
                    }
                }.toMap()
                val layout = C4Layout.layout(
                    diagram = d,
                    elementSizes = elementSizes,
                    boundaryHeaderSizes = boundaryHeaderSizes,
                    relLabelSizes = relLabelSizes,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportC4ToSvg(d, layout, theme, options.density)
            }
            is MaradonaDiagramModel.ArchitectureModel -> {
                val d = model.diagram
                val labelFontSp = d.config.fontSize.coerceAtLeast(1f)
                val sgScale = if (theme.subgraphFontSize.value > 0f) theme.subgraphFontSize.value / 14f else 1f
                val grpFont = labelFontSp * sgScale
                val svcFont = theme.scaledSp(labelFontSp).value
                val layout = ArchitectureLayout.layout(
                    diagram = d,
                    serviceLabelSize = { _, lbl -> defaultTextSize(lbl, svcFont) },
                    groupLabelSize = { _, lbl -> defaultTextSize(lbl, grpFont) },
                    edgeLabelSize = { lbl -> defaultTextSize(lbl, theme.edgeLabelFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportArchitectureToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.ZenUmlModel -> {
                val d = model.diagram
                val dens = options.density.coerceAtLeast(0.01f)
                val (msgSpecs, blockSpecs) = ZenUmlLayout.collectSpecs(d)
                val participantSizes = d.participants.associate { p ->
                    val pLines = zenUmlParticipantSpanLines(p, theme)
                    val pW = pLines.maxOfOrNull { (lf, line) -> line.sumOf { it.text.length } * lf * 0.62f }?.coerceAtLeast(1f) ?: 1f
                    val pH = pLines.sumOf { (lf, _) -> (lf * 1.4f).toDouble() }.toFloat()
                    p.id to DiagramSize(max(116f * dens, pW + 28f * dens), max(48f * dens, pH + 16f * dens))
                }
                val grpHdrFont = theme.scaledSp(11.5f).value
                val msgFont = theme.scaledSp(11.5f).value
                val commentFont = theme.scaledSp(10.5f).value
                val tabFont = theme.captionFontSize.value
                val divFont = theme.scaledSp(10.5f).value
                val groupHeaderSizes = d.groups.mapIndexed { idx, grp ->
                    idx to defaultTextSize(grp.name, grpHdrFont)
                }.toMap()
                val messageLabelSizes = msgSpecs.mapNotNull { spec ->
                    if (spec.displayText.isBlank()) null else spec.index to defaultTextSize(spec.displayText, msgFont)
                }.toMap()
                val commentSizes = msgSpecs.mapNotNull { spec ->
                    val ct = spec.commentText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val initialLines = wrapSvgLines(plainTextLabel(ct), commentFont, zenUmlCommentWrapWidth(240f * dens, dens))
                    val maxCols = initialLines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
                    val w = min(240f * dens, max(64f * dens, maxCols * commentFont * 0.62f + 16f * dens))
                    val wrapped = wrapSvgLines(plainTextLabel(ct), commentFont, zenUmlCommentWrapWidth(w, dens))
                    val h = max(24f * dens, wrapped.size * commentFont * 1.4f + 10f * dens)
                    spec.index to DiagramSize(w, h)
                }.toMap()
                val blockTabSizes = blockSpecs.associate { blk ->
                    blk.index to defaultTextSize(blk.keyword, tabFont)
                }
                val blockCondSizes = blockSpecs.mapNotNull { blk ->
                    val cond = blk.condition?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    blk.index to defaultTextSize("[$cond]", tabFont)
                }.toMap()
                val blockDividerSizes = blockSpecs.flatMap { blk ->
                    blk.dividerLabels.mapIndexed { dIdx, lbl ->
                        (blk.index to dIdx) to defaultTextSize(lbl, divFont)
                    }
                }.toMap()
                val layout = ZenUmlLayout.layout(
                    diagram = d,
                    participantSizes = participantSizes,
                    groupHeaderSizes = groupHeaderSizes,
                    messageLabelSizes = messageLabelSizes,
                    commentSizes = commentSizes,
                    blockTabSizes = blockTabSizes,
                    blockCondSizes = blockCondSizes,
                    blockDividerSizes = blockDividerSizes,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportZenUmlToSvg(d, layout, theme, options.density)
            }
            is MaradonaDiagramModel.MindmapModel -> {
                val d = model.diagram
                val dens = options.density.coerceAtLeast(0.01f)
                val layout = MindmapLayout.layout(
                    diagram = d,
                    nodeSize = { n ->
                        val display = if (!n.icon.isNullOrBlank()) "${resolveMermaidIconGlyph(n.icon)} ${n.label}" else n.label
                        val nodeFont = when {
                            n.parentId == null -> theme.scaledSp(15f).value
                            "large" in n.classes -> theme.scaledSp(14.5f).value
                            else -> theme.nodeFontSize.value
                        }
                        val sz = defaultTextSize(display, nodeFont)
                        DiagramSize(max(72f * dens, sz.width + 28f * dens), max(34f * dens, sz.height + 16f * dens))
                    },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportMindmapToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.GanttModel -> {
                val d = model.diagram
                val layout = GanttLayout.layout(
                    diagram = d,
                    taskLabelSize = { t -> defaultTextSize(t.label, theme.edgeLabelFontSize.value) },
                    sectionLabelSize = { s -> defaultTextSize(s, theme.scaledSp(13f).value) },
                    tickLabelSize = { s -> defaultTextSize(s, theme.captionFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportGanttToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.PieModel -> {
                val d = model.diagram
                val layout = PieLayout.layout(
                    diagram = d,
                    pctLabelSize = { _, s -> defaultTextSize(s, theme.edgeLabelFontSize.value) },
                    legendLabelSize = { _, s -> defaultTextSize(s, theme.scaledSp(13f).value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportPieToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.GitGraphModel -> {
                val d = model.diagram
                val commitFont = theme.scaledSp(10.5f).value
                val layout = GitGraphLayout.layout(
                    diagram = d,
                    branchLabelSize = { s -> defaultTextSize(s, theme.edgeLabelFontSize.value) },
                    commitLabelSize = { c -> defaultTextSize(c.id, commitFont) },
                    tagLabelSize = { c -> defaultTextSize(c.tag.orEmpty(), commitFont) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportGitGraphToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.TimelineModel -> {
                val d = model.diagram
                val dens = options.density.coerceAtLeast(0.01f)
                val hdrFont = theme.scaledSp(13f).value
                val layout = TimelineLayout.layout(
                    diagram = d,
                    periodTitleSize = { p -> defaultTextSize(p.title, hdrFont) },
                    eventTextSize = { _, _, s ->
                        measureSpanLinesSize(
                            wrapFormattedLabelLines(s, theme.edgeLabelFontSize.value, 220f * dens),
                            theme.edgeLabelFontSize.value,
                        )
                    },
                    sectionTitleSize = { s -> defaultTextSize(s, hdrFont) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportTimelineToSvg(d, layout, theme, dens)
            }
            is MaradonaDiagramModel.JourneyModel -> {
                val d = model.diagram
                val dens = options.density.coerceAtLeast(0.01f)
                val layout = JourneyLayout.layout(
                    diagram = d,
                    taskLabelSize = { t ->
                        measureSpanLinesSize(
                            wrapFormattedLabelLines(t.label, theme.edgeLabelFontSize.value, 180f * dens),
                            theme.edgeLabelFontSize.value,
                        )
                    },
                    sectionTitleSize = { s -> defaultTextSize(s, theme.scaledSp(13f).value) },
                    actorLabelSize = { a -> defaultTextSize(a, theme.edgeLabelFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportJourneyToSvg(d, layout, theme, dens)
            }
            is MaradonaDiagramModel.QuadrantModel -> {
                val d = model.diagram
                val layout = QuadrantLayout.layout(
                    diagram = d,
                    pointLabelSize = { p -> defaultTextSize(p.label, theme.scaledSp(11.5f).value) },
                    quadrantLabelSize = { _, s -> defaultTextSize(s, theme.scaledSp(13f).value) },
                    axisLabelSize = { s -> defaultTextSize(s, theme.scaledSp(12.5f).value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportQuadrantToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.XyChartModel -> {
                val d = model.diagram
                val layout = XyChartLayout.layout(
                    diagram = d,
                    tickLabelSize = { s -> defaultTextSize(s, theme.captionFontSize.value) },
                    axisTitleSize = { s -> defaultTextSize(s, theme.scaledSp(12.5f).value) },
                    legendLabelSize = { s -> defaultTextSize(s, theme.edgeLabelFontSize.value) },
                    pointLabelSize = { s -> defaultTextSize(s, theme.captionFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportXyChartToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.KanbanModel -> {
                val d = model.diagram
                val dens = options.density.coerceAtLeast(0.01f)
                val taskFont = theme.edgeLabelFontSize.value
                val colHdrFont = theme.scaledSp(13.5f).value
                val metaFont = theme.scaledSp(10.5f).value
                val layout = KanbanLayout.layout(
                    diagram = d,
                    columnHeaderSize = { _, col ->
                        val hdr = if (!col.icon.isNullOrBlank()) "${resolveMermaidIconGlyph(col.icon)} ${col.label}" else col.label
                        defaultTextSize(hdr, colHdrFont)
                    },
                    taskLabelSize = { _, _, task, maxW ->
                        val display = if (!task.icon.isNullOrBlank()) "${resolveMermaidIconGlyph(task.icon)} ${task.label}" else task.label
                        val wrapW = maxW.coerceAtLeast(60f * dens)
                        val lines = wrapSvgLines(plainTextLabel(display), taskFont, wrapW)
                        val maxCols = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
                        DiagramSize(
                            width = min(maxW, maxCols * taskFont * 0.62f + 8f * dens),
                            height = lines.size * taskFont * 1.38f + 4f * dens,
                        )
                    },
                    metaBadgeSize = { s -> defaultTextSize(s, metaFont) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportKanbanToSvg(d, layout, theme, dens)
            }
            is MaradonaDiagramModel.SankeyModel -> {
                val d = model.diagram
                val layout = SankeyLayout.layout(
                    diagram = d,
                    nodeLabelSize = { s -> defaultTextSize(s, theme.scaledSp(11.5f).value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportSankeyToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.PacketModel -> {
                val d = model.diagram
                val layout = PacketLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportPacketToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.RadarModel -> {
                val d = model.diagram
                val layout = RadarLayout.layout(
                    diagram = d,
                    axisLabelSize = { s -> defaultTextSize(s, theme.edgeLabelFontSize.value) },
                    legendLabelSize = { s -> defaultTextSize(s, theme.edgeLabelFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportRadarToSvg(d, layout, theme)
            }
            is MaradonaDiagramModel.BlockModel -> {
                val d = model.diagram
                val layout = BlockLayout.layout(
                    diagram = d,
                    nodeLabelSize = { _, lbl -> defaultTextSize(lbl, theme.scaledSp(13f).value) },
                    edgeLabelSize = { lbl -> defaultTextSize(lbl, theme.edgeLabelFontSize.value) },
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportBlockToSvg(d, layout, theme, options.density)
            }
            is MaradonaDiagramModel.TreemapModel -> {
                val d = model.diagram
                val layout = TreemapLayout.layout(
                    diagram = d,
                    titleSize = d.title?.let { defaultTextSize(it, theme.titleFontSize.value) },
                    options = options,
                )
                exportTreemapToSvg(d, layout, theme)
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
        imageScale: Float = 1f,
        textMeasurer: TextMeasurer? = null,
        density: Density = Density(imageScale.coerceAtLeast(0.01f), 1f),
    ): String {
        val imgScale = imageScale.coerceAtLeast(0.01f)
        val wrapScale = density.density.coerceAtLeast(0.01f)
        val w = ceil(layout.width).toInt().coerceAtLeast(1)
        val h = ceil(layout.height).toInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h" font-family="${theme.fontFamily.toSvgFontFamily()}">""").append('\n')
        sb.append("""  <rect width="100%" height="100%" fill="${theme.background.toSvgHex()}"/>""").append('\n')

        // 1. Subgraph containers
        for (psg in layout.subgraphs) {
            val r = psg.rect
            sb.append("""  <g class="subgraph" data-id="${escapeXml(psg.subgraph.id)}">""").append('\n')
            sb.append(
                """    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.subgraphFill.toSvgHex()}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
            val sgSpanLines = parseFormattedLabelLines(psg.subgraph.label)
            val sgFont = theme.subgraphFontSize.value
            val sgLineH = sgFont * 1.3f
            val sgFirstLineOffset = 6f + sgFont * 0.85f
            for ((idx, spanLine) in sgSpanLines.withIndex()) {
                val ly = r.y + sgFirstLineOffset + idx * sgLineH
                appendFormattedLineWithImages(
                    sb = sb,
                    indent = "    ",
                    centerX = r.centerX,
                    lineCenterY = ly - sgFont * 0.35f,
                    baselineY = ly,
                    spanLine = spanLine,
                    colorHex = theme.subgraphText.toSvgHex(),
                    fontSize = sgFont,
                    extraTextAttrs = """ font-weight="600"""",
                    imageScale = imgScale,
                )
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
                val edgeFontSize = theme.edgeLabelFontSize.value
                val edgeTextStyle = if (textMeasurer != null) {
                    TextStyle(
                        fontFamily = theme.fontFamily,
                        fontSize = (edgeFontSize / (density.density * density.fontScale).coerceAtLeast(0.01f)).sp,
                    )
                } else {
                    null
                }
                val edgeSpanLines = wrapFormattedLabelLines(
                    label = lbl,
                    fontSp = edgeFontSize,
                    maxWidth = 172f * wrapScale,
                    textMeasurer = textMeasurer,
                    textStyle = edgeTextStyle,
                    density = density,
                )
                appendRichSpanLines(
                    sb = sb,
                    indent = "  ",
                    centerX = lr.centerX,
                    centerY = lr.centerY,
                    spanLines = edgeSpanLines,
                    colorHex = theme.edgeLabelText.toSvgHex(),
                    fontSize = edgeFontSize,
                    imageScale = imgScale,
                )
            }
        }

        // 3. Nodes (with optional <a> links, data-callback, <title> tooltips, rich <tspan> text, and <image>)
        for (pn in layout.nodes) {
            val node = pn.node
            val r = pn.rect
            val fill = (parseCssColor(node.style?.fill) ?: theme.nodeFill).toSvgHex()
            val stroke = (parseCssColor(node.style?.stroke) ?: theme.nodeBorder).toSvgHex()
            val textColor = (parseCssColor(node.style?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = node.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1f
            val url = node.action?.url?.takeIf { it.isNotBlank() }
            val callback = node.action?.callback?.takeIf { it.isNotBlank() }
            val tooltip = node.action?.tooltip?.takeIf { it.isNotBlank() }
            val cbAttr = callback?.let { """ data-callback="${escapeXml(it)}"""" } ?: ""

            if (url != null) {
                val targetAttr = node.action.target?.let { """ target="${escapeXml(it)}"""" } ?: ""
                sb.append("""  <a href="${escapeXml(url)}"$targetAttr>""").append('\n')
            }
            sb.append("""  <g class="node" data-id="${escapeXml(node.id)}"$cbAttr>""").append('\n')
            if (tooltip != null) {
                sb.append("""    <title>${escapeXml(tooltip)}</title>""").append('\n')
            }
            appendSvgNodeShape(sb, node.shape, r, fill, stroke, strokeW)
            val fontSize = theme.nodeFontSize.value
            val nodeTextStyle = if (textMeasurer != null) {
                TextStyle(
                    fontFamily = theme.fontFamily,
                    fontSize = (fontSize / (density.density * density.fontScale).coerceAtLeast(0.01f)).sp,
                )
            } else {
                null
            }
            val spanLines = wrapFormattedLabelLines(
                label = node.label,
                fontSp = fontSize,
                maxWidth = NodeSizing.WRAPPING_WIDTH * wrapScale,
                textMeasurer = textMeasurer,
                textStyle = nodeTextStyle,
                density = density,
                includeInlineImages = true,
            )
            appendRichSpanLines(
                sb = sb,
                indent = "    ",
                centerX = r.centerX,
                centerY = r.centerY,
                spanLines = spanLines,
                colorHex = textColor,
                fontSize = fontSize,
                imageScale = imgScale,
                textMeasurer = textMeasurer,
                textStyle = nodeTextStyle,
                density = density,
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
        density: Float = 1f,
    ): String {
        val dens = density.coerceAtLeast(0.01f)
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
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(lbl), theme.subgraphText.toSvgHex(), theme.subgraphFontSize.value)
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
            appendPlainMultilineText(sb, "  ", tr.centerX, tr.centerY, rawSvgLines(blk.tag), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            if (!blk.label.isNullOrBlank() && blk.labelRect != null) {
                appendMultilineText(sb, "  ", blk.labelRect.centerX, blk.labelRect.centerY, rawSvgLines("[${blk.label}]"), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
            for (div in blk.dividers) {
                sb.append(
                    """  <line x1="${fmt(r.x)}" y1="${fmt(div.y)}" x2="${fmt(r.right)}" y2="${fmt(div.y)}" stroke="$borderHex" stroke-width="1.5" stroke-dasharray="4,3"/>""",
                ).append('\n')
                if (!div.label.isNullOrBlank() && div.labelRect != null) {
                    appendMultilineText(sb, "  ", div.labelRect.centerX, div.labelRect.centerY, rawSvgLines("[${div.label}]"), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
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
                val headR = 9f * dens
                val headCy = r.y + 6f * dens + headR
                val neckY = headCy + headR
                val hipY = neckY + 14f * dens
                val armY = neckY + 5f * dens
                val footY = hipY + 11f * dens
                val span = 11f * dens
                sb.append("""    <circle cx="${fmt(cx)}" cy="${fmt(headCy)}" r="${fmt(headR)}" fill="$fillHex" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx)}" y1="${fmt(neckY)}" x2="${fmt(cx)}" y2="${fmt(hipY)}" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx - span)}" y1="${fmt(armY)}" x2="${fmt(cx + span)}" y2="${fmt(armY)}" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx)}" y1="${fmt(hipY)}" x2="${fmt(cx - span * 0.85f)}" y2="${fmt(footY)}" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                sb.append("""    <line x1="${fmt(cx)}" y1="${fmt(hipY)}" x2="${fmt(cx + span * 0.85f)}" y2="${fmt(footY)}" stroke="$strokeHex" stroke-width="1.5"/>""").append('\n')
                val actorSpanLines = parseFormattedLabelLines(p.label)
                val actorFont = theme.nodeFontSize.value
                val actorLineH = actorFont * 1.35f
                val actorCenterY = r.bottom - 4f * dens - actorFont * 0.35f - ((actorSpanLines.size - 1) * actorLineH) / 2f
                appendRichSpanLines(sb, "    ", r.centerX, actorCenterY, actorSpanLines, textHex, actorFont)
            } else {
                sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="$fillHex" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
                appendMultilineText(sb, "    ", r.centerX, r.centerY, rawSvgLines(p.label), textHex, theme.nodeFontSize.value)
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
            appendMultilineText(sb, "  ", r.centerX, r.centerY, rawSvgLines(n.note.text), theme.noteText.toSvgHex(), theme.nodeFontSize.value)
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
                val loopW = 36f * dens
                val loopH = 22f * dens
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
                val badgeX = m.startX + dir * 8f * dens
                sb.append("""  <circle cx="${fmt(badgeX)}" cy="${fmt(m.lineY)}" r="${fmt(7f * dens)}" fill="$edgeHex"/>""").append('\n')
                sb.append("""  <text x="${fmt(badgeX)}" y="${fmt(m.lineY + 3.5f * dens)}" text-anchor="middle" fill="$bgHex" font-family="sans-serif" font-size="9" font-weight="600">${m.sequenceNumber}</text>""").append('\n')
            }
            if (m.statement.text.isNotBlank()) {
                val msgLabel = if (m.sequenceNumber != null) "${m.sequenceNumber}. ${m.statement.text}" else m.statement.text
                appendMultilineText(sb, "  ", m.labelRect.centerX, m.labelRect.centerY, rawSvgLines(msgLabel), theme.nodeText.toSvgHex(), theme.nodeFontSize.value)
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
                val hr = ns.headerRect
                appendMultilineText(sb, "  ", hr.centerX, hr.centerY, rawSvgLines(ns.namespace.label), theme.subgraphText.toSvgHex(), theme.subgraphFontSize.value)
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
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(lbl), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
            val fc = r.relation.fromCardinality
            val fcr = r.fromCardinalityRect
            if (!fc.isNullOrBlank() && fcr != null) {
                appendPlainMultilineText(sb, "  ", fcr.centerX, fcr.centerY, rawSvgLines(fc), theme.edgeLabelText.toSvgHex(), theme.captionFontSize.value)
            }
            val tc = r.relation.toCardinality
            val tcr = r.toCardinalityRect
            if (!tc.isNullOrBlank() && tcr != null) {
                appendPlainMultilineText(sb, "  ", tcr.centerX, tcr.centerY, rawSvgLines(tc), theme.edgeLabelText.toSvgHex(), theme.captionFontSize.value)
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
            val callback = c.action?.callback?.takeIf { it.isNotBlank() }
            val tooltip = c.action?.tooltip?.takeIf { it.isNotBlank() }
            val cbAttr = callback?.let { """ data-callback="${escapeXml(it)}"""" } ?: ""

            if (url != null) {
                val targetAttr = c.action.target?.let { """ target="${escapeXml(it)}"""" } ?: ""
                sb.append("""  <a href="${escapeXml(url)}"$targetAttr>""").append('\n')
            }
            sb.append("""  <g class="class-node" data-id="${escapeXml(c.id)}"$cbAttr>""").append('\n')
            if (tooltip != null) {
                sb.append("""    <title>${escapeXml(tooltip)}</title>""").append('\n')
            }
            sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(strokeW)}"/>""").append('\n')

            val hdrSpanLines = classHeaderSpanLines(c, theme)
            appendSizedSpanLines(sb, "    ", r.centerX, r.y + pc.headerHeight / 2f, hdrSpanLines, textHex)

            val showCompartments = !diagram.hideEmptyMembersBox || c.attributes.isNotEmpty() || c.methods.isNotEmpty()
            if (showCompartments) {
                val div1Y = r.y + pc.headerHeight
                val div2Y = div1Y + pc.attributesHeight
                sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(div1Y)}" x2="${fmt(r.right)}" y2="${fmt(div1Y)}" stroke="$strokeHex" stroke-width="1"/>""").append('\n')
                sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(div2Y)}" x2="${fmt(r.right)}" y2="${fmt(div2Y)}" stroke="$strokeHex" stroke-width="1"/>""").append('\n')

                val memberFont = theme.edgeLabelFontSize.value
                val lineH = memberFont * 1.4f
                val firstLineOffset = 6f + memberFont * 0.85f
                for ((idx, attr) in c.attributes.withIndex()) {
                    val ay = div1Y + firstLineOffset + idx * lineH
                    val styleAttr = when (attr.classifier) {
                        ClassClassifier.Abstract -> """ font-style="italic""""
                        ClassClassifier.Static -> """ text-decoration="underline""""
                        else -> ""
                    }
                    sb.append("""    <text x="${fmt(r.x + 10f)}" y="${fmt(ay)}" text-anchor="start" fill="$textHex" font-family="sans-serif" font-size="${fmt(memberFont)}"$styleAttr>${escapeXml(attr.displayText)}</text>""").append('\n')
                }
                for ((idx, meth) in c.methods.withIndex()) {
                    val my = div2Y + firstLineOffset + idx * lineH
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
            appendMultilineText(sb, "  ", r.centerX, r.centerY, rawSvgLines(n.note.text), theme.noteText.toSvgHex(), theme.edgeLabelFontSize.value)
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
            appendMultilineText(sb, "    ", r.centerX, r.y + ps.headerHeight / 2f, rawSvgLines(ps.state.label), textHex, theme.subgraphFontSize.value)
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
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(lbl), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
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
                        appendMultilineText(sb, "    ", r.centerX, r.y + ps.headerHeight / 2f, rawSvgLines(s.label), textHex, theme.nodeFontSize.value)
                        val descLines = s.descriptions.flatMap { rawSvgLines(it) }
                        appendMultilineText(sb, "    ", r.centerX, divY + (r.height - ps.headerHeight) / 2f, descLines, textHex, theme.edgeLabelFontSize.value)
                    } else {
                        appendMultilineText(sb, "    ", r.centerX, r.centerY, rawSvgLines(s.label), textHex, theme.nodeFontSize.value)
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
            appendMultilineText(sb, "  ", r.centerX, r.centerY, rawSvgLines(n.note.text), theme.noteText.toSvgHex(), theme.edgeLabelFontSize.value)
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
                appendMultilineText(sb, "  ", r.centerX, r.y + sg.headerHeight / 2f, rawSvgLines(sg.subgraph.label), textHex, theme.subgraphFontSize.value)
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
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(lbl), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
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
                appendMultilineText(sb, "    ", r.centerX, r.y + hdrH / 2f, rawSvgLines(e.label), textHex, theme.nodeFontSize.value)

                var rowY = r.y + hdrH
                val regFont = theme.scaledSp(11.5f).value
                val capFont = theme.captionFontSize.value
                val baseOffset = max(regFont, capFont) * 0.35f
                for ((rIdx, attr) in e.attributes.withIndex()) {
                    val rH = pe.rowHeights.getOrElse(rIdx) { 22f }
                    if (rIdx % 2 == 1) {
                        sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(rowY)}" width="${fmt(r.width)}" height="${fmt(rH)}" fill="${theme.subgraphFill.copy(alpha = 0.25f).toSvgHex()}"/>""").append('\n')
                    }
                    val rowSpans = buildString {
                        append("${escapeXml(attr.type)} ${escapeXml(attr.name)}")
                        if (attr.keys.isNotEmpty()) {
                            append(""" <tspan font-size="${fmt(capFont)}" font-weight="600">${escapeXml(attr.keys.joinToString(", "))}</tspan>""")
                        }
                        if (!attr.comment.isNullOrBlank()) {
                            val cSpans = parseFormattedLabelLines(attr.comment).flatten()
                            append(""" <tspan font-size="${fmt(capFont)}" font-style="italic">&quot;${renderSpansToSvgContent(cSpans)}&quot;</tspan>""")
                        }
                    }
                    sb.append(
                        """    <text x="${fmt(r.x + 8f)}" y="${fmt(rowY + rH / 2f + baseOffset)}" text-anchor="start" fill="$textHex" font-size="${fmt(regFont)}">$rowSpans</text>""",
                    ).append('\n')
                    rowY += rH
                    if (rIdx < e.attributes.lastIndex) {
                        sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(rowY)}" x2="${fmt(r.right)}" y2="${fmt(rowY)}" stroke="$strokeHex" stroke-width="0.75" stroke-opacity="0.65"/>""").append('\n')
                    }
                }
            } else {
                appendMultilineText(sb, "    ", r.centerX, r.centerY, rawSvgLines(e.label), textHex, theme.nodeFontSize.value)
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
            val stereoFont = theme.captionFontSize.value
            val nameFont = theme.scaledSp(13.5f).value
            val bodyFont = theme.scaledSp(11.5f).value
            val hdrSpans = buildList<Pair<Float, List<FormattedTextSpan>>> {
                for (line in parseFormattedLabelLines(pi.item.stereotype)) {
                    add(stereoFont to line)
                }
                for (line in parseFormattedLabelLines(pi.item.name)) {
                    add(nameFont to line)
                }
            }
            fun appendRequirementHdr(centerY: Float) {
                val totalHdrH = hdrSpans.sumOf { (lf, _) -> (lf * 1.35f).toDouble() }.toFloat()
                var curY = centerY - totalHdrH / 2f
                for ((lf, spanLine) in hdrSpans) {
                    val lh = lf * 1.35f
                    val lcY = curY + lh / 2f
                    appendFormattedLineWithImages(sb, "    ", r.centerX, lcY, lcY + lf * 0.35f, spanLine, textHex, lf)
                    curY += lh
                }
            }
            if (pi.bodyLineHeights.isNotEmpty() && pi.headerHeight < r.height) {
                val divY = r.y + pi.headerHeight
                sb.append("""    <line x1="${fmt(r.x)}" y1="${fmt(divY)}" x2="${fmt(r.right)}" y2="${fmt(divY)}" stroke="$strokeHex" stroke-width="1.1"/>""").append('\n')
                appendRequirementHdr(r.y + hdrH / 2f)
                val bodyLines = pi.item.bodyLines.flatMap { rawSvgLines(it) }
                appendMultilineText(sb, "    ", r.centerX, divY + (r.height - hdrH) / 2f, bodyLines, textHex, bodyFont)
            } else {
                appendRequirementHdr(r.centerY)
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
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(pr.relation.label), theme.edgeLabelText.toSvgHex(), theme.captionFontSize.value)
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
        density: Float = 1f,
        textMeasurer: TextMeasurer? = null,
        densityObj: Density = Density(density.coerceAtLeast(0.01f), 1f),
    ): String {
        val dens = density.coerceAtLeast(0.01f)
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
            val hdrSpans = buildList {
                if (pb.boundary.label.isNotBlank()) {
                    addAll(parseFormattedLabelLines(pb.boundary.label).flatten())
                }
                pb.boundary.type.takeIf { it.isNotBlank() }?.let {
                    add(FormattedTextSpan(if (isNotEmpty()) " [$it]" else "[$it]"))
                }
            }
            if (hdrSpans.isNotEmpty()) {
                appendRichSpanLines(sb, "  ", pb.headerRect.centerX, pb.headerRect.centerY, listOf(hdrSpans), bTextHex, theme.scaledSp(12.5f).value)
            }
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
                    val headR = 11f * dens
                    val bodyTop = r.y + headR * 1.55f
                    val bodyH = max(20f * dens, r.bottom - bodyTop)
                    sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(bodyTop)}" width="${fmt(r.width)}" height="${fmt(bodyH)}" rx="${fmt(12f * dens)}" fill="$fillHex" stroke="$strokeHex" stroke-width="1.6"/>""").append('\n')
                    sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.y + headR + 2f * dens)}" r="${fmt(headR)}" fill="$fillHex" stroke="$strokeHex" stroke-width="1.6"/>""").append('\n')
                }
                el.kind.isDatabase -> {
                    appendSvgNodeShape(sb, NodeShape.Cylinder, r, fillHex, strokeHex, 1.6f)
                }
                el.kind.isQueue -> {
                    val capRx = (r.height * 0.22f).coerceIn(8f * dens, 18f * dens)
                    appendSvgNodeShape(sb, NodeShape.Stadium, r, fillHex, strokeHex, 1.6f)
                    sb.append("""    <ellipse cx="${fmt(r.right - capRx)}" cy="${fmt(r.centerY)}" rx="${fmt(capRx)}" ry="${fmt(r.height / 2f)}" fill="none" stroke="$strokeHex" stroke-width="1.3"/>""").append('\n')
                }
                else -> {
                    appendSvgNodeShape(sb, NodeShape.RoundedRect, r, fillHex, strokeHex, 1.6f)
                }
            }

            val wrapW = c4WrapWidth(r.width, dens)
            val cardSpanLines = c4WrappedCardSpanLines(el, wrapW, theme, textMeasurer, densityObj)
            val totalCardTextH = cardSpanLines.sumOf { (lf, _) -> (lf * 1.35f).toDouble() }.toFloat()
            var curCardY = r.centerY - totalCardTextH / 2f
            for ((lineFont, spanLine) in cardSpanLines) {
                val lh = lineFont * 1.35f
                val lineCenterY = curCardY + lh / 2f
                appendFormattedLineWithImages(
                    sb = sb,
                    indent = "    ",
                    centerX = r.centerX,
                    lineCenterY = lineCenterY,
                    baselineY = lineCenterY + lineFont * 0.35f,
                    spanLine = spanLine,
                    colorHex = textHex,
                    fontSize = lineFont,
                )
                curCardY += lh
            }
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
            val mainFontSize = theme.scaledSp(10.5f).value
            val techFontSize = theme.scaledSp(9.5f).value
            val relWrapW = 188f * dens
            val mainStyle = exportTextStyle(theme, mainFontSize, densityObj, textMeasurer)
            val techStyle = exportTextStyle(theme, techFontSize, densityObj, textMeasurer)
            val relSpanLines = buildList<Pair<Float, List<FormattedTextSpan>>> {
                if (indexedLabel.isNotBlank()) {
                    for (line in wrapFormattedLabelLines(
                        label = indexedLabel,
                        fontSp = mainFontSize,
                        maxWidth = relWrapW,
                        textMeasurer = textMeasurer,
                        textStyle = mainStyle,
                        density = densityObj,
                    )) {
                        add(mainFontSize to line)
                    }
                }
                if (!pr.relationship.technology.isNullOrBlank()) {
                    for (line in wrapFormattedLabelLines(
                        label = "[${pr.relationship.technology}]",
                        fontSp = techFontSize,
                        maxWidth = relWrapW,
                        textMeasurer = textMeasurer,
                        textStyle = techStyle,
                        density = densityObj,
                    )) {
                        add(techFontSize to line)
                    }
                }
            }
            if (lr != null && relSpanLines.isNotEmpty()) {
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="4" fill="${theme.background.copy(alpha = 0.92f).toSvgHex()}"/>""").append('\n')
                val totalRelTextH = relSpanLines.sumOf { (lf, _) -> (lf * 1.35f).toDouble() }.toFloat()
                var curRelY = lr.centerY - totalRelTextH / 2f
                for ((lineFont, spanLine) in relSpanLines) {
                    val lh = lineFont * 1.35f
                    val lineCenterY = curRelY + lh / 2f
                    appendFormattedLineWithImages(
                        sb = sb,
                        indent = "  ",
                        centerX = lr.centerX,
                        lineCenterY = lineCenterY,
                        baselineY = lineCenterY + lineFont * 0.35f,
                        spanLine = spanLine,
                        colorHex = textHex,
                        fontSize = lineFont,
                    )
                    curRelY += lh
                }
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
                val grpIconAttr = pg.group.icon?.takeIf { it.isNotBlank() }?.let { """ data-icon="${escapeXml(it)}"""" }.orEmpty()
                sb.append("""  <rect class="arch-group-icon"$grpIconAttr x="${fmt(ir.x)}" y="${fmt(ir.y)}" width="${fmt(ir.width)}" height="${fmt(ir.height)}" rx="4" fill="${theme.accentBorder.toSvgHex()}"/>""").append('\n')
                val grpIconGlyph = pg.group.icon?.takeIf { it.isNotBlank() }?.let(::resolveMermaidIconGlyph).orEmpty()
                if (grpIconGlyph.isNotBlank()) {
                    val iconFont = (ir.height * 0.65f).coerceIn(9f, theme.nodeFontSize.value)
                    appendMultilineText(sb, "  ", ir.centerX, ir.centerY, listOf(grpIconGlyph), theme.accentText.toSvgHex(), iconFont)
                }
            }
            val grpTitle = pg.group.title ?: pg.group.id
            val lr = pg.labelRect
            val labelFontSp = diagram.config.fontSize.coerceAtLeast(1f)
            val sgScale = if (theme.subgraphFontSize.value > 0f) theme.subgraphFontSize.value / 14f else 1f
            val grpFont = labelFontSp * sgScale
            if (lr != null) {
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(grpTitle), theme.subgraphText.toSvgHex(), grpFont)
            } else {
                appendMultilineText(sb, "  ", r.centerX, r.y + 6f + grpFont * 0.65f, rawSvgLines(grpTitle), theme.subgraphText.toSvgHex(), grpFont)
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
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(lbl), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        // 3. Services
        val svcFont = theme.scaledSp(diagram.config.fontSize.coerceAtLeast(1f)).value
        val svcIconTextFont = theme.scaledSp(11.5f).value
        for (ps in layout.services) {
            val ir = ps.iconRect
            val titleText = ps.service.title ?: ps.service.id
            val iconGlyph = ps.service.iconText?.takeIf { it.isNotBlank() }
                ?: ps.service.icon?.let(::resolveMermaidIconGlyph).orEmpty()
            sb.append("""  <g class="arch-service" data-id="${escapeXml(ps.service.id)}">""").append('\n')
            sb.append("""    <rect x="${fmt(ir.x)}" y="${fmt(ir.y)}" width="${fmt(ir.width)}" height="${fmt(ir.height)}" rx="8" fill="${theme.accentBorder.toSvgHex()}" stroke="${theme.nodeBorder.toSvgHex()}" stroke-width="1.2"/>""").append('\n')
            if (iconGlyph.isNotBlank()) {
                appendMultilineText(sb, "    ", ir.centerX, ir.centerY, rawSvgLines(iconGlyph), theme.accentText.toSvgHex(), svcIconTextFont)
            }
            val lr = ps.labelRect
            if (lr != null) {
                appendMultilineText(sb, "    ", lr.centerX, lr.centerY, rawSvgLines(titleText), theme.nodeText.toSvgHex(), svcFont)
            } else if (iconGlyph.isBlank()) {
                appendMultilineText(sb, "    ", ir.centerX, ir.centerY, rawSvgLines(titleText), theme.accentText.toSvgHex(), svcFont)
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
        density: Float = 1f,
        textMeasurer: TextMeasurer? = null,
        densityObj: Density = Density(density.coerceAtLeast(0.01f), 1f),
    ): String {
        val dens = density.coerceAtLeast(0.01f)
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val edgeHex = theme.edge.toSvgHex()

        // 1. Participant groups
        for (pg in layout.groups) {
            val r = pg.rect
            sb.append(
                """  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.subgraphFill.copy(alpha = 0.55f).toSvgHex()}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1.2"/>""",
            ).append('\n')
            appendMultilineText(sb, "  ", pg.headerRect.centerX, pg.headerRect.centerY, rawSvgLines(pg.group.name), theme.subgraphText.toSvgHex(), theme.scaledSp(11.5f).value)
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
            appendPlainMultilineText(sb, "  ", tr.centerX, tr.centerY, rawSvgLines(blk.keyword), theme.nodeText.toSvgHex(), theme.captionFontSize.value)
            if (!blk.condition.isNullOrBlank() && blk.conditionRect != null) {
                appendPlainMultilineText(sb, "  ", blk.conditionRect.centerX, blk.conditionRect.centerY, rawSvgLines("[${blk.condition}]"), theme.nodeText.toSvgHex(), theme.captionFontSize.value)
            }
            for (div in blk.dividers) {
                sb.append("""  <line x1="${fmt(r.x)}" y1="${fmt(div.y)}" x2="${fmt(r.right)}" y2="${fmt(div.y)}" stroke="$borderHex" stroke-width="1.2" stroke-dasharray="4,4"/>""").append('\n')
                appendPlainMultilineText(sb, "  ", div.labelRect.centerX, div.labelRect.centerY, rawSvgLines(div.label), theme.nodeText.toSvgHex(), theme.scaledSp(10.5f).value)
            }
        }

        // 4. Activation bars
        for (act in layout.activations) {
            val r = act.rect
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="${theme.accentFill.toSvgHex()}" stroke="${theme.accentBorder.toSvgHex()}" stroke-width="1"/>""").append('\n')
        }

        // 5. Participant headers (top and optional mirrored bottom)
        for (pp in layout.participants) {
            val spanLines = zenUmlParticipantSpanLines(pp.participant, theme)
            for (r in listOfNotNull(pp.topRect, pp.bottomRect)) {
                sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.nodeFill.toSvgHex()}" stroke="${theme.nodeBorder.toSvgHex()}" stroke-width="1.6"/>""").append('\n')
                appendSizedSpanLines(sb, "  ", r.centerX, r.centerY, spanLines, theme.nodeText.toSvgHex())
            }
        }

        // 6. Messages and inline comments
        for (pm in layout.messages) {
            val ct = pm.commentText
            val cr = pm.commentRect
            if (!ct.isNullOrBlank() && cr != null) {
                sb.append("""  <rect x="${fmt(cr.x)}" y="${fmt(cr.y)}" width="${fmt(cr.width)}" height="${fmt(cr.height)}" rx="4" fill="${theme.noteFill.toSvgHex()}" stroke="${theme.noteBorder.toSvgHex()}" stroke-width="1"/>""").append('\n')
                val commentFont = theme.scaledSp(10.5f).value
                val commentLines = wrapFormattedLabelLines(
                    label = ct,
                    fontSp = commentFont,
                    maxWidth = zenUmlCommentWrapWidth(cr.width, dens),
                    textMeasurer = textMeasurer,
                    textStyle = exportTextStyle(theme, commentFont, densityObj, textMeasurer),
                    density = densityObj,
                )
                appendRichSpanLines(sb, "  ", cr.centerX, cr.centerY, commentLines, theme.noteText.toSvgHex(), commentFont)
            }
            if (pm.displayText.isBlank() && cr != null) continue

            val pts = if (pm.isSelf) {
                val loopW = 34f * dens
                val loopH = max(18f * dens, pm.selfLoopHeight)
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
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(pm.displayText), theme.nodeText.toSvgHex(), theme.scaledSp(11.5f).value)
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun classHeaderSpanLines(
        c: dev.maradona.model.ClassNode,
        theme: MaradonaTheme,
    ): List<Pair<Float, List<FormattedTextSpan>>> = buildList {
        val annFont = theme.captionFontSize.value
        val titleFont = theme.nodeFontSize.value
        for (ann in c.annotations) {
            if (ann.isNotBlank()) add(annFont to listOf(FormattedTextSpan("«$ann»", italic = true)))
        }
        val titleLines = parseFormattedLabelLines(c.label).toMutableList()
        if (!c.genericType.isNullOrBlank()) {
            if (titleLines.isEmpty()) {
                titleLines.add(listOf(FormattedTextSpan("<${c.genericType}>")))
            } else {
                titleLines[titleLines.lastIndex] = titleLines.last() + FormattedTextSpan("<${c.genericType}>")
            }
        }
        for (line in titleLines) {
            add(titleFont to line.map { it.copy(bold = true) })
        }
    }

    private fun zenUmlParticipantSpanLines(
        p: ZenUmlParticipant,
        theme: MaradonaTheme,
    ): List<Pair<Float, List<FormattedTextSpan>>> {
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
        val stereoFont = theme.scaledSp(10f).value
        val lblFont = theme.scaledSp(13f).value
        return buildList {
            if (!stereo.isNullOrBlank()) add(stereoFont to listOf(FormattedTextSpan("«$stereo»", italic = true)))
            for (line in parseFormattedLabelLines(p.label)) {
                add(lblFont to line.map { it.copy(bold = true) })
            }
        }
    }

    private fun appendSizedSpanLines(
        sb: StringBuilder,
        indent: String,
        centerX: Float,
        centerY: Float,
        sizedLines: List<Pair<Float, List<FormattedTextSpan>>>,
        colorHex: String,
    ) {
        if (sizedLines.isEmpty()) return
        val totalH = sizedLines.sumOf { (lf, _) -> (lf * 1.35f).toDouble() }.toFloat()
        var curY = centerY - totalH / 2f
        for ((lineFont, spanLine) in sizedLines) {
            val lh = lineFont * 1.35f
            val lineCenterY = curY + lh / 2f
            appendFormattedLineWithImages(
                sb = sb,
                indent = indent,
                centerX = centerX,
                lineCenterY = lineCenterY,
                baselineY = lineCenterY + lineFont * 0.35f,
                spanLine = spanLine,
                colorHex = colorHex,
                fontSize = lineFont,
            )
            curY += lh
        }
    }

    /**
     * Exports a pre-laid-out [MindmapDiagram] and [MindmapDiagramLayout] to a standalone SVG string.
     */
    fun exportMindmapToSvg(
        diagram: MindmapDiagram,
        layout: MindmapDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        for (e in layout.edges) {
            if (e.points.size < 2) continue
            val strokeHex = (if (e.branchIndex >= 0) theme.seriesColor(e.branchIndex) else theme.edge).toSvgHex()
            sb.append("""  <path class="mindmap-edge" d="${buildSvgEdgePath(e.points)}" fill="none" stroke="$strokeHex" stroke-width="1.8"/>""").append('\n')
        }
        for (pn in layout.nodes) {
            val n = pn.node
            val r = pn.rect
            val isUrgent = "urgent" in n.classes
            val fillHex = (parseCssColor(n.style?.fill) ?: when {
                isUrgent -> theme.criticalFill
                pn.depth == 0 -> theme.subgraphFill
                else -> theme.nodeFill
            }).toSvgHex()
            val strokeHex = (parseCssColor(n.style?.stroke) ?: when {
                isUrgent -> theme.criticalBorder
                pn.branchIndex >= 0 -> theme.seriesColor(pn.branchIndex)
                else -> theme.nodeBorder
            }).toSvgHex()
            val textHex = (parseCssColor(n.style?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = n.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull()
                ?: if (pn.depth == 0 || isUrgent) 2f else 1.35f
            val shape = when (n.shape) {
                MindmapShape.Square -> NodeShape.Rect
                MindmapShape.Circle -> NodeShape.Circle
                MindmapShape.Hexagon -> NodeShape.Hexagon
                else -> NodeShape.RoundedRect
            }
            sb.append("""  <g class="mindmap-node" data-id="${escapeXml(n.id)}">""").append('\n')
            appendSvgNodeShape(sb, shape, r, fillHex, strokeHex, strokeW)
            val display = if (!n.icon.isNullOrBlank()) "${resolveMermaidIconGlyph(n.icon)} ${n.label}" else n.label
            val nodeFont = when {
                n.parentId == null -> theme.scaledSp(15f).value
                "large" in n.classes -> theme.scaledSp(14.5f).value
                else -> theme.nodeFontSize.value
            }
            appendMultilineText(sb, "    ", r.centerX, r.centerY, rawSvgLines(display), textHex, nodeFont)
            sb.append("""  </g>""").append('\n')
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [GanttDiagram] and [GanttDiagramLayout] to a standalone SVG string.
     */
    fun exportGanttToSvg(
        diagram: GanttDiagram,
        layout: GanttDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)

        // 1. Section bands and labels
        for (s in layout.sections) {
            val r = s.rect
            val fillHex = theme.seriesFill(s.sectionIndex.coerceAtLeast(0)).toSvgHex()
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fillHex" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="0.75"/>""").append('\n')
            if (s.name.isNotBlank()) {
                appendMultilineText(sb, "  ", s.labelRect.centerX, s.labelRect.centerY, rawSvgLines(s.name), theme.nodeText.toSvgHex(), theme.scaledSp(13f).value)
            }
        }

        // 2. Excluded-day shading bands
        for (eb in layout.excludedBands) {
            sb.append("""  <rect class="gantt-excluded-band" x="${fmt(eb.x)}" y="${fmt(eb.y)}" width="${fmt(eb.width)}" height="${fmt(eb.height)}" fill="${theme.mutedFill.copy(alpha = 0.55f).toSvgHex()}"/>""").append('\n')
        }

        // 3. Ticks and grid lines
        for (t in layout.ticks) {
            sb.append("""  <line x1="${fmt(t.x)}" y1="${fmt(layout.plotRect.y)}" x2="${fmt(t.x)}" y2="${fmt(layout.plotRect.bottom)}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="0.75" stroke-dasharray="3,3"/>""").append('\n')
            appendPlainMultilineText(sb, "  ", t.bottomLabelRect.centerX, t.bottomLabelRect.centerY, listOf(t.label), theme.edgeLabelText.toSvgHex(), theme.captionFontSize.value)
            t.topLabelRect?.let { tr ->
                appendPlainMultilineText(sb, "  ", tr.centerX, tr.centerY, listOf(t.label), theme.edgeLabelText.toSvgHex(), theme.captionFontSize.value)
            }
        }

        // 4. Tasks (status & critical-path colors, milestones, and separate labelRect)
        for (pt in layout.tasks) {
            val t = pt.task
            val idx = pt.sectionIndex.coerceAtLeast(0)
            val defaultFill = theme.seriesFill(idx)
            val defaultStroke = theme.seriesColor(idx)
            val (fill, border) = when {
                t.isCritical && t.status == GanttTaskStatus.Done ->
                    theme.criticalFill.copy(alpha = 0.78f) to theme.criticalBorder
                t.isCritical && t.status == GanttTaskStatus.Active ->
                    lerp(theme.criticalFill, theme.criticalBorder, 0.24f) to theme.criticalBorder
                t.isCritical -> theme.criticalFill to theme.criticalBorder
                t.status == GanttTaskStatus.Done -> theme.mutedFill to theme.mutedBorder
                t.status == GanttTaskStatus.Active -> theme.accentFill to theme.accentBorder
                else -> defaultFill to defaultStroke
            }
            val url = t.action?.url?.takeIf { it.isNotBlank() }
            val callback = t.action?.callback?.takeIf { it.isNotBlank() }
            val tooltip = t.action?.tooltip?.takeIf { it.isNotBlank() }
            val cbAttr = callback?.let { """ data-callback="${escapeXml(it)}"""" } ?: ""
            if (url != null) {
                val targetAttr = t.action.target?.let { """ target="${escapeXml(it)}"""" } ?: ""
                sb.append("""  <a href="${escapeXml(url)}"$targetAttr>""").append('\n')
            }
            sb.append("""  <g class="gantt-task" data-id="${escapeXml(t.id)}"$cbAttr>""").append('\n')
            if (tooltip != null) sb.append("""    <title>${escapeXml(tooltip)}</title>""").append('\n')
            val shape = if (t.isMilestone) NodeShape.Diamond else NodeShape.RoundedRect
            appendSvgNodeShape(sb, shape, pt.barRect, fill.toSvgHex(), border.toSvgHex(), 1.3f)
            if (t.label.isNotBlank()) {
                appendMultilineText(sb, "    ", pt.labelRect.centerX, pt.labelRect.centerY, rawSvgLines(t.label), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
            sb.append("""  </g>""").append('\n')
            if (url != null) sb.append("""  </a>""").append('\n')
        }

        // 5. Vertical markers
        for (vm in layout.verticalMarkers) {
            sb.append("""  <line x1="${fmt(vm.x)}" y1="${fmt(vm.topY)}" x2="${fmt(vm.x)}" y2="${fmt(vm.bottomY)}" stroke="${theme.accentBorder.toSvgHex()}" stroke-width="1.5" stroke-dasharray="4,3"/>""").append('\n')
            val lr = vm.labelRect
            sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="4" fill="${theme.accentFill.toSvgHex()}" stroke="${theme.accentBorder.toSvgHex()}" stroke-width="1"/>""").append('\n')
            appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(vm.task.label), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [PieDiagram] and [PieDiagramLayout] to a standalone SVG string.
     */
    fun exportPieToSvg(
        diagram: PieDiagram,
        layout: PieDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
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
                    sb.append("""  <path class="pie-slice" fill-rule="evenodd" d="$dPath" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(layout.outerStrokeWidth)}"/>""").append('\n')
                } else {
                    sb.append("""  <circle class="pie-slice" cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(rOut)}" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(layout.outerStrokeWidth)}"/>""").append('\n')
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
                sb.append("""  <path class="pie-slice" d="$pathData" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(layout.outerStrokeWidth)}"/>""").append('\n')
            }
            s.pctLabelRect?.let { pr ->
                appendPlainMultilineText(sb, "  ", pr.centerX, pr.centerY, listOf(s.percentageText), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }
        for (li in layout.legendItems) {
            val sr = li.swatchRect
            val colorHex = theme.seriesColor(li.index).toSvgHex()
            sb.append("""  <rect x="${fmt(sr.x)}" y="${fmt(sr.y)}" width="${fmt(sr.width)}" height="${fmt(sr.height)}" fill="$colorHex" stroke="$colorHex" stroke-width="1"/>""").append('\n')
            appendMultilineText(sb, "  ", li.labelRect.centerX, li.labelRect.centerY, rawSvgLines(li.displayText), theme.nodeText.toSvgHex(), theme.scaledSp(13f).value)
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [GitGraphDiagram] and [GitGraphDiagramLayout] to a standalone SVG string.
     */
    fun exportGitGraphToSvg(
        diagram: GitGraphDiagram,
        layout: GitGraphDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()

        for (b in layout.branches) {
            val strokeHex = theme.seriesColor(b.laneIndex).toSvgHex()
            val fillHex = theme.seriesFill(b.laneIndex).toSvgHex()
            sb.append("""  <line x1="${fmt(b.lineStart.x)}" y1="${fmt(b.lineStart.y)}" x2="${fmt(b.lineEnd.x)}" y2="${fmt(b.lineEnd.y)}" stroke="$strokeHex" stroke-width="1.4" stroke-dasharray="5,4"/>""").append('\n')
            b.labelRect?.let { lr ->
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="6" fill="$fillHex" stroke="$strokeHex" stroke-width="1.4"/>""").append('\n')
                appendPlainMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(b.branch.name), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        for (e in layout.edges) {
            val pts = e.points
            if (pts.size < 2) continue
            val strokeHex = theme.seriesColor(e.branchLaneIndex).toSvgHex()
            val d = if (pts.size == 4) {
                "M ${fmt(pts[0].x)} ${fmt(pts[0].y)} C ${fmt(pts[1].x)} ${fmt(pts[1].y)}, ${fmt(pts[2].x)} ${fmt(pts[2].y)}, ${fmt(pts[3].x)} ${fmt(pts[3].y)}"
            } else {
                buildSvgEdgePath(pts)
            }
            val dashAttr = if (e.isCherryPick) """ stroke-dasharray="6,4"""" else ""
            sb.append("""  <path class="git-edge" d="$d" fill="none" stroke="$strokeHex" stroke-width="2.4"$dashAttr/>""").append('\n')
        }

        for (pc in layout.commits) {
            val branchFillHex = theme.seriesFill(pc.laneIndex).toSvgHex()
            val branchStrokeHex = theme.seriesColor(pc.laneIndex).toSvgHex()
            val nr = pc.nodeRect
            val cx = pc.center.x
            val cy = pc.center.y
            val rad = nr.width / 2f
            val typeTag = when {
                pc.commit.type == GitCommitType.Highlight -> "highlight"
                pc.commit.type == GitCommitType.Reverse -> "reverse"
                pc.commit.isMerge -> "merge"
                else -> "normal"
            }
            sb.append("""  <g class="git-commit" data-id="${escapeXml(pc.commit.id)}" data-commit-type="$typeTag">""").append('\n')
            when (pc.commit.type) {
                GitCommitType.Highlight -> {
                    sb.append("""    <rect x="${fmt(nr.x)}" y="${fmt(nr.y)}" width="${fmt(nr.width)}" height="${fmt(nr.height)}" fill="$branchFillHex" stroke="$branchStrokeHex" stroke-width="2.2"/>""").append('\n')
                    val inset = nr.width * 0.18f
                    sb.append("""    <rect x="${fmt(nr.x + inset)}" y="${fmt(nr.y + inset)}" width="${fmt(nr.width - inset * 2f)}" height="${fmt(nr.height - inset * 2f)}" fill="$branchStrokeHex"/>""").append('\n')
                }
                GitCommitType.Reverse -> {
                    sb.append("""    <circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(rad)}" fill="$bgHex" stroke="$branchStrokeHex" stroke-width="2.2"/>""").append('\n')
                    val d = rad * 0.52f
                    sb.append("""    <line x1="${fmt(cx - d)}" y1="${fmt(cy - d)}" x2="${fmt(cx + d)}" y2="${fmt(cy + d)}" stroke="$branchStrokeHex" stroke-width="2"/>""").append('\n')
                    sb.append("""    <line x1="${fmt(cx - d)}" y1="${fmt(cy + d)}" x2="${fmt(cx + d)}" y2="${fmt(cy - d)}" stroke="$branchStrokeHex" stroke-width="2"/>""").append('\n')
                }
                GitCommitType.Normal -> {
                    if (pc.commit.isMerge) {
                        sb.append("""    <circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(rad)}" fill="$bgHex" stroke="$branchStrokeHex" stroke-width="2.2"/>""").append('\n')
                        sb.append("""    <circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(rad * 0.48f)}" fill="$branchStrokeHex"/>""").append('\n')
                    } else {
                        sb.append("""    <circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(rad)}" fill="$branchStrokeHex" stroke="$bgHex" stroke-width="1.6"/>""").append('\n')
                    }
                }
            }
            pc.commitLabelRect?.let { clRect ->
                appendPlainMultilineText(
                    sb = sb,
                    indent = "    ",
                    centerX = clRect.centerX,
                    centerY = clRect.centerY,
                    lines = listOf(pc.commit.id),
                    colorHex = theme.nodeText.toSvgHex(),
                    fontSize = theme.scaledSp(10.5f).value,
                    rotationDeg = pc.commitLabelAngleDeg,
                )
            }
            pc.tagRect?.let { tgRect ->
                sb.append("""    <rect x="${fmt(tgRect.x)}" y="${fmt(tgRect.y)}" width="${fmt(tgRect.width)}" height="${fmt(tgRect.height)}" rx="4" fill="${theme.warningFill.toSvgHex()}" stroke="${theme.warningBorder.toSvgHex()}" stroke-width="1.3"/>""").append('\n')
                appendPlainMultilineText(sb, "    ", tgRect.centerX, tgRect.centerY, listOf(pc.commit.tag.orEmpty()), theme.nodeText.toSvgHex(), theme.scaledSp(10.5f).value)
            }
            sb.append("""  </g>""").append('\n')
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [TimelineDiagram] and [TimelineDiagramLayout] to a standalone SVG string.
     */
    fun exportTimelineToSvg(
        diagram: TimelineDiagram,
        layout: TimelineDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
        density: Float = 1f,
        textMeasurer: TextMeasurer? = null,
        densityObj: Density = Density(density.coerceAtLeast(0.01f), 1f),
    ): String {
        val dens = density.coerceAtLeast(0.01f)
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        for (s in layout.sections) {
            val r = s.rect
            val fillHex = theme.seriesFill(s.sectionIndex).toSvgHex()
            val strokeHex = theme.seriesColor(s.sectionIndex).toSvgHex()
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="$fillHex" stroke="$strokeHex" stroke-width="1.2"/>""").append('\n')
            appendMultilineText(sb, "  ", r.centerX, r.centerY, rawSvgLines(s.name), theme.nodeText.toSvgHex(), theme.scaledSp(13f).value)
        }
        sb.append("""  <line x1="${fmt(layout.spineStart.x)}" y1="${fmt(layout.spineStart.y)}" x2="${fmt(layout.spineEnd.x)}" y2="${fmt(layout.spineEnd.y)}" stroke="${theme.edge.toSvgHex()}" stroke-width="2"/>""").append('\n')
        val evFont = theme.edgeLabelFontSize.value
        val evStyle = exportTextStyle(theme, evFont, densityObj, textMeasurer)
        for (pp in layout.periods) {
            val strokeHex = theme.seriesColor(pp.colorIndex).toSvgHex()
            val fillHex = theme.seriesFill(pp.colorIndex).toSvgHex()
            val cs = pp.connectorStart
            val ce = pp.connectorEnd
            if (cs != null && ce != null) {
                sb.append("""  <line x1="${fmt(cs.x)}" y1="${fmt(cs.y)}" x2="${fmt(ce.x)}" y2="${fmt(ce.y)}" stroke="$strokeHex" stroke-width="1.4" stroke-dasharray="4,3"/>""").append('\n')
            }
            val r = pp.rect
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="$fillHex" stroke="$strokeHex" stroke-width="1.4"/>""").append('\n')
            appendMultilineText(sb, "  ", r.centerX, r.centerY, rawSvgLines(pp.period.title), theme.nodeText.toSvgHex(), theme.scaledSp(13f).value)
            for (ev in pp.events) {
                val er = ev.rect
                sb.append("""  <rect x="${fmt(er.x)}" y="${fmt(er.y)}" width="${fmt(er.width)}" height="${fmt(er.height)}" rx="5" fill="${theme.nodeFill.toSvgHex()}" stroke="$strokeHex" stroke-width="1.2"/>""").append('\n')
                val evSpanLines = wrapFormattedLabelLines(
                    label = ev.text,
                    fontSp = evFont,
                    maxWidth = 220f * dens,
                    textMeasurer = textMeasurer,
                    textStyle = evStyle,
                    density = densityObj,
                )
                appendRichSpanLines(sb, "  ", er.centerX, er.centerY, evSpanLines, theme.nodeText.toSvgHex(), evFont)
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [JourneyDiagram] and [JourneyDiagramLayout] to a standalone SVG string.
     */
    fun exportJourneyToSvg(
        diagram: JourneyDiagram,
        layout: JourneyDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
        density: Float = 1f,
        textMeasurer: TextMeasurer? = null,
        densityObj: Density = Density(density.coerceAtLeast(0.01f), 1f),
    ): String {
        val dens = density.coerceAtLeast(0.01f)
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        for (al in layout.actorLegend) {
            sb.append("""  <circle cx="${fmt(al.circleCenter.x)}" cy="${fmt(al.circleCenter.y)}" r="${fmt(al.circleRadius)}" fill="${theme.seriesFill(al.actorIndex).toSvgHex()}" stroke="${theme.seriesColor(al.actorIndex).toSvgHex()}" stroke-width="1.2"/>""").append('\n')
            appendMultilineText(sb, "  ", al.labelRect.centerX, al.labelRect.centerY, rawSvgLines(al.actor), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
        }
        for (s in layout.sections) {
            val r = s.rect
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.seriesFill(s.sectionIndex).toSvgHex()}" stroke="${theme.seriesColor(s.sectionIndex).toSvgHex()}" stroke-width="1.2"/>""").append('\n')
            appendMultilineText(sb, "  ", r.centerX, r.centerY, rawSvgLines(s.name), theme.nodeText.toSvgHex(), theme.scaledSp(13f).value)
        }
        val spineHex = theme.nodeBorder.toSvgHex()
        sb.append("""  <line x1="${fmt(layout.spineStart.x)}" y1="${fmt(layout.spineStart.y)}" x2="${fmt(layout.spineEnd.x)}" y2="${fmt(layout.spineEnd.y)}" stroke="$spineHex" stroke-width="2.4"/>""").append('\n')
        appendSvgMarker(sb, layout.spineEnd, layout.spineStart, SvgMarker.FilledArrow, spineHex, bgHex)

        val faceFillHex = theme.noteFill.toSvgHex()
        val faceStrokeHex = theme.noteBorder.toSvgHex()
        val featHex = theme.noteText.toSvgHex()
        val taskFont = theme.edgeLabelFontSize.value
        val taskStyle = exportTextStyle(theme, taskFont, densityObj, textMeasurer, fontWeight = FontWeight.Medium)
        for (pt in layout.tasks) {
            sb.append("""  <line x1="${fmt(pt.lineStart.x)}" y1="${fmt(pt.lineStart.y)}" x2="${fmt(pt.lineEnd.x)}" y2="${fmt(pt.lineEnd.y)}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1.3" stroke-dasharray="4,3"/>""").append('\n')
            val r = pt.cardRect
            val fillHex = theme.seriesFill(pt.sectionIndex).toSvgHex()
            val strokeHex = theme.seriesColor(pt.sectionIndex).toSvgHex()
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="$fillHex" stroke="$strokeHex" stroke-width="1.4"/>""").append('\n')
            val taskSpanLines = wrapFormattedLabelLines(
                label = pt.task.label,
                fontSp = taskFont,
                maxWidth = 180f * dens,
                textMeasurer = textMeasurer,
                textStyle = taskStyle,
                density = densityObj,
            )
            appendRichSpanLines(sb, "  ", r.centerX, r.centerY, taskSpanLines, theme.nodeText.toSvgHex(), taskFont)
            for (dot in pt.actorDots) {
                sb.append("""  <circle cx="${fmt(dot.center.x)}" cy="${fmt(dot.center.y)}" r="${fmt(dot.radius)}" fill="${theme.seriesFill(dot.actorIndex).toSvgHex()}" stroke="${theme.seriesColor(dot.actorIndex).toSvgHex()}" stroke-width="1.2"/>""").append('\n')
            }
            val fc = pt.faceCenter
            val fr = pt.faceRadius
            sb.append("""  <circle class="journey-face" data-mood="${pt.mood.name.lowercase()}" cx="${fmt(fc.x)}" cy="${fmt(fc.y)}" r="${fmt(fr)}" fill="$faceFillHex" stroke="$faceStrokeHex" stroke-width="1.6"/>""").append('\n')
            val mY = fc.y + fr * 0.25f
            val mSpan = fr * 0.36f
            val mouthD = when (pt.mood) {
                JourneyMood.Happy -> "M ${fmt(fc.x - mSpan)} ${fmt(mY - 2f)} Q ${fmt(fc.x)} ${fmt(mY + 4f)} ${fmt(fc.x + mSpan)} ${fmt(mY - 2f)}"
                JourneyMood.Sad -> "M ${fmt(fc.x - mSpan)} ${fmt(mY + 2f)} Q ${fmt(fc.x)} ${fmt(mY - 4f)} ${fmt(fc.x + mSpan)} ${fmt(mY + 2f)}"
                JourneyMood.Neutral -> "M ${fmt(fc.x - mSpan)} ${fmt(mY)} L ${fmt(fc.x + mSpan)} ${fmt(mY)}"
            }
            sb.append("""  <path d="$mouthD" fill="none" stroke="$featHex" stroke-width="1.5"/>""").append('\n')
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [QuadrantDiagram] and [QuadrantDiagramLayout] to a standalone SVG string.
     */
    fun exportQuadrantToSvg(
        diagram: QuadrantDiagram,
        layout: QuadrantDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        // 1. Quadrant background boxes (drawn first so data points sit on top)
        for (q in layout.quadrants) {
            val r = q.rect
            val fillHex = theme.seriesFill(q.quadrantNumber - 1).toSvgHex()
            sb.append("""  <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fillHex" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1"/>""").append('\n')
            val lbl = q.text
            val lr = q.labelRect
            if (!lbl.isNullOrBlank() && lr != null) {
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(lbl), theme.subgraphText.toSvgHex(), theme.scaledSp(13f).value)
            }
        }
        // 2. Axis labels (including rotated Y-axis labels)
        for (al in layout.axisLabels) {
            appendMultilineText(
                sb = sb,
                indent = "  ",
                centerX = al.rect.centerX,
                centerY = al.rect.centerY,
                lines = rawSvgLines(al.text),
                colorHex = theme.edgeLabelText.toSvgHex(),
                fontSize = theme.scaledSp(12.5f).value,
                rotationDeg = al.rotationDeg,
            )
        }
        // 3. Data points and labels (drawn above quadrant backgrounds)
        for (pt in layout.points) {
            val fillHex = (parseCssColor(pt.point.style?.color) ?: theme.accentFill).toSvgHex()
            val strokeHex = (parseCssColor(pt.point.style?.strokeColor) ?: theme.accentBorder).toSvgHex()
            sb.append("""  <circle class="quadrant-point" cx="${fmt(pt.center.x)}" cy="${fmt(pt.center.y)}" r="${fmt(pt.radius)}" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(pt.strokeWidth)}"/>""").append('\n')
            appendMultilineText(sb, "  ", pt.labelRect.centerX, pt.labelRect.centerY, rawSvgLines(pt.point.label), theme.nodeText.toSvgHex(), theme.scaledSp(11.5f).value)
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [XyChartDiagram] and [XyChartDiagramLayout] to a standalone SVG string.
     */
    fun exportXyChartToSvg(
        diagram: XyChartDiagram,
        layout: XyChartDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val borderHex = theme.nodeBorder.toSvgHex()
        val gridHex = theme.subgraphBorder.toSvgHex()

        // 1. Grid lines, axes, ticks, and rotated axis titles
        for (tk in layout.xTicks + layout.yTicks) {
            sb.append("""  <line x1="${fmt(tk.gridStart.x)}" y1="${fmt(tk.gridStart.y)}" x2="${fmt(tk.gridEnd.x)}" y2="${fmt(tk.gridEnd.y)}" stroke="$gridHex" stroke-width="0.75" stroke-dasharray="3,3"/>""").append('\n')
            sb.append("""  <line x1="${fmt(tk.lineStart.x)}" y1="${fmt(tk.lineStart.y)}" x2="${fmt(tk.lineEnd.x)}" y2="${fmt(tk.lineEnd.y)}" stroke="$borderHex" stroke-width="1.2"/>""").append('\n')
            appendMultilineText(sb, "  ", tk.labelRect.centerX, tk.labelRect.centerY, rawSvgLines(tk.label), theme.edgeLabelText.toSvgHex(), theme.captionFontSize.value)
        }
        sb.append("""  <line x1="${fmt(layout.xAxisLineStart.x)}" y1="${fmt(layout.xAxisLineStart.y)}" x2="${fmt(layout.xAxisLineEnd.x)}" y2="${fmt(layout.xAxisLineEnd.y)}" stroke="$borderHex" stroke-width="1.5"/>""").append('\n')
        sb.append("""  <line x1="${fmt(layout.yAxisLineStart.x)}" y1="${fmt(layout.yAxisLineStart.y)}" x2="${fmt(layout.yAxisLineEnd.x)}" y2="${fmt(layout.yAxisLineEnd.y)}" stroke="$borderHex" stroke-width="1.5"/>""").append('\n')
        for (at in layout.axisTitles) {
            appendMultilineText(
                sb = sb,
                indent = "  ",
                centerX = at.rect.centerX,
                centerY = at.rect.centerY,
                lines = rawSvgLines(at.text),
                colorHex = theme.nodeText.toSvgHex(),
                fontSize = theme.scaledSp(12.5f).value,
                rotationDeg = at.rotationDeg,
            )
        }

        fun resolveXySeriesColor(seriesIndex: Int, explicitColor: String?): Color {
            parseCssColor(explicitColor)?.let { return it }
            if (diagram.plotColorPalette.isNotEmpty()) {
                val raw = diagram.plotColorPalette[seriesIndex % diagram.plotColorPalette.size]
                parseCssColor(raw)?.let { return it }
            }
            return theme.seriesColor(seriesIndex)
        }

        // 2. Bar series (drawn before line series so bars never obscure line plots)
        for (b in layout.bars) {
            val r = b.rect
            val explicitCol = diagram.plots.getOrNull(b.seriesIndex)?.color
            val colorHex = resolveXySeriesColor(b.seriesIndex, explicitCol).toSvgHex()
            sb.append("""  <rect class="xy-bar" x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$colorHex" stroke="$colorHex" stroke-width="1"/>""").append('\n')
            val dl = b.dataLabel
            val dlr = b.dataLabelRect
            if (!dl.isNullOrBlank() && dlr != null) {
                appendMultilineText(sb, "  ", dlr.centerX, dlr.centerY, rawSvgLines(dl), theme.nodeText.toSvgHex(), theme.captionFontSize.value)
            }
        }

        // 3. Line series and point markers (drawn above bars; single-point series still emit point markers)
        val bgHex = theme.background.toSvgHex()
        for (ls in layout.lines) {
            val strokeHex = resolveXySeriesColor(ls.seriesIndex, ls.series.color).toSvgHex()
            if (ls.points.size >= 2) {
                sb.append("""  <path class="xy-line" d="${buildSvgEdgePath(ls.points.map { it.position })}" fill="none" stroke="$strokeHex" stroke-width="2.2"/>""").append('\n')
            }
            for (pt in ls.points) {
                sb.append("""  <circle class="xy-line-point" cx="${fmt(pt.position.x)}" cy="${fmt(pt.position.y)}" r="3.5" fill="$strokeHex" stroke="$bgHex" stroke-width="1"/>""").append('\n')
                val lbl = pt.label
                val lr = pt.labelRect
                if (!lbl.isNullOrBlank() && lr != null) {
                    appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(lbl), theme.nodeText.toSvgHex(), theme.captionFontSize.value)
                }
            }
        }

        // 4. Legend items
        for (li in layout.legendItems) {
            val mr = li.markerRect
            val colorHex = resolveXySeriesColor(li.seriesIndex, li.series.color).toSvgHex()
            sb.append("""  <rect x="${fmt(mr.x)}" y="${fmt(mr.y)}" width="${fmt(mr.width)}" height="${fmt(mr.height)}" fill="$colorHex"/>""").append('\n')
            val legendText = li.series.title ?: li.series.id
            appendMultilineText(sb, "  ", li.labelRect.centerX, li.labelRect.centerY, rawSvgLines(legendText), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [KanbanDiagram] and [KanbanDiagramLayout] to a standalone SVG string.
     */
    fun exportKanbanToSvg(
        diagram: KanbanDiagram,
        layout: KanbanDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
        density: Float = 1f,
        textMeasurer: TextMeasurer? = null,
        densityObj: Density = Density(density.coerceAtLeast(0.01f), 1f),
    ): String {
        val dens = density.coerceAtLeast(0.01f)
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val borderHex = theme.nodeBorder.toSvgHex()

        for (col in layout.columns) {
            val cr = col.rect
            val colFillHex = lerp(theme.seriesFill(col.columnIndex), theme.background, 0.45f).toSvgHex()
            sb.append("""  <rect x="${fmt(cr.x)}" y="${fmt(cr.y)}" width="${fmt(cr.width)}" height="${fmt(cr.height)}" rx="6" fill="$colFillHex" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1.2"/>""").append('\n')
            val colHdr = if (!col.column.icon.isNullOrBlank()) {
                "${resolveMermaidIconGlyph(col.column.icon)} ${col.column.label}"
            } else {
                col.column.label
            }
            appendMultilineText(sb, "  ", col.headerRect.centerX, col.headerRect.centerY, rawSvgLines(colHdr), theme.nodeText.toSvgHex(), theme.scaledSp(13.5f).value)

            for (pt in col.tasks) {
                val t = pt.task
                val r = pt.cardRect
                val ticketUrl = t.ticket?.takeIf { it.isNotBlank() }?.let { diagram.resolveTicketUrl(it) }
                if (ticketUrl != null) sb.append("""  <a href="${escapeXml(ticketUrl)}">""").append('\n')
                sb.append("""  <g class="kanban-card" data-id="${escapeXml(t.id)}">""").append('\n')
                sb.append("""    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="5" fill="$bgHex" stroke="$borderHex" stroke-width="1.1"/>""").append('\n')

                val stripeColor = when (t.priority) {
                    KanbanPriority.VeryHigh -> theme.criticalBorder
                    KanbanPriority.High -> theme.warningBorder
                    KanbanPriority.Medium -> null
                    KanbanPriority.Low -> theme.accentBorder
                    KanbanPriority.VeryLow -> theme.seriesColor(1)
                    null -> null
                }
                if (stripeColor != null && t.priority != null) {
                    val lineX = r.x + 3f * dens
                    val insetY = 3.5f * dens
                    sb.append("""    <line class="kanban-priority-stripe" data-priority="${t.priority.name.lowercase()}" x1="${fmt(lineX)}" y1="${fmt(r.y + insetY)}" x2="${fmt(lineX)}" y2="${fmt(r.bottom - insetY)}" stroke="${stripeColor.toSvgHex()}" stroke-width="${fmt(3.6f * dens)}"/>""").append('\n')
                }

                val display = if (!t.icon.isNullOrBlank()) "${resolveMermaidIconGlyph(t.icon)} ${t.label}" else t.label
                val taskFont = theme.edgeLabelFontSize.value
                val spanLines = wrapFormattedLabelLines(
                    label = display,
                    fontSp = taskFont,
                    maxWidth = pt.labelRect.width.coerceAtLeast(60f * dens),
                    textMeasurer = textMeasurer,
                    textStyle = exportTextStyle(theme, taskFont, densityObj, textMeasurer),
                    density = densityObj,
                )
                appendRichSpanLines(sb, "    ", pt.labelRect.centerX, pt.labelRect.centerY, spanLines, theme.nodeText.toSvgHex(), taskFont)

                val metaFont = theme.scaledSp(10.5f).value
                val tktRect = pt.ticketRect
                if (tktRect != null && !t.ticket.isNullOrBlank()) {
                    appendMultilineText(sb, "    ", tktRect.centerX, tktRect.centerY, rawSvgLines(t.ticket), theme.nodeText.toSvgHex(), metaFont)
                }
                val asgRect = pt.assignedRect
                if (asgRect != null && !t.assigned.isNullOrBlank()) {
                    appendMultilineText(sb, "    ", asgRect.centerX, asgRect.centerY, rawSvgLines(t.assigned), theme.nodeText.toSvgHex(), metaFont)
                }
                sb.append("""  </g>""").append('\n')
                if (ticketUrl != null) sb.append("""  </a>""").append('\n')
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [SankeyDiagram] and [SankeyDiagramLayout] to a standalone SVG string.
     */
    fun exportSankeyToSvg(
        diagram: SankeyDiagram,
        layout: SankeyDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val nodeColors = layout.nodes.associate { pn ->
            val col = parseCssColor(pn.node.color)
                ?: parseCssColor(diagram.nodeColors[pn.node.id])
                ?: theme.seriesColor(pn.nodeIndex)
            pn.nodeIndex to col
        }
        val linkMode = diagram.linkColor.trim().lowercase()
        val customLinkColor = if (linkMode != "gradient" && linkMode != "source" && linkMode != "target") {
            parseCssColor(diagram.linkColor)
        } else {
            null
        }
        if (linkMode == "gradient" && layout.links.isNotEmpty()) {
            sb.append("  <defs>\n")
            for (pl in layout.links) {
                val srcHex = (nodeColors[pl.sourceNodeIndex] ?: theme.seriesColor(0)).toSvgHex()
                val dstHex = (nodeColors[pl.targetNodeIndex] ?: theme.seriesColor(1)).toSvgHex()
                sb.append(
                    """    <linearGradient id="sankey-grad-${pl.linkIndex}" gradientUnits="userSpaceOnUse" x1="${fmt(pl.start.x)}" y1="${fmt(pl.start.y)}" x2="${fmt(pl.end.x)}" y2="${fmt(pl.end.y)}">""" +
                        """<stop offset="0%" stop-color="$srcHex"/>""" +
                        """<stop offset="100%" stop-color="$dstHex"/>""" +
                        """</linearGradient>""",
                ).append('\n')
            }
            sb.append("  </defs>\n")
        }
        for (pl in layout.links) {
            val srcCol = nodeColors[pl.sourceNodeIndex] ?: theme.seriesColor(0)
            val dstCol = nodeColors[pl.targetNodeIndex] ?: theme.seriesColor(1)
            val strokeRef = when (linkMode) {
                "gradient" -> "url(#sankey-grad-${pl.linkIndex})"
                "target" -> dstCol.toSvgHex()
                "source" -> srcCol.toSvgHex()
                else -> (customLinkColor ?: srcCol).toSvgHex()
            }
            val pathD = "M ${fmt(pl.start.x)} ${fmt(pl.start.y)} C ${fmt(pl.control1.x)} ${fmt(pl.control1.y)}, ${fmt(pl.control2.x)} ${fmt(pl.control2.y)}, ${fmt(pl.end.x)} ${fmt(pl.end.y)}"
            sb.append("""  <path class="sankey-link" d="$pathD" fill="none" stroke="$strokeRef" stroke-width="${fmt(pl.thickness.coerceAtLeast(1f))}" stroke-opacity="0.45"/>""").append('\n')
        }
        for (pn in layout.nodes) {
            val r = pn.rect
            val fillHex = (nodeColors[pn.nodeIndex] ?: theme.seriesColor(pn.nodeIndex)).toSvgHex()
            sb.append("""  <rect class="sankey-node" x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fillHex" stroke="${theme.nodeBorder.toSvgHex()}" stroke-width="1"/>""").append('\n')
            if (pn.labelText.isNotBlank()) {
                appendMultilineText(sb, "  ", pn.labelRect.centerX, pn.labelRect.centerY, rawSvgLines(pn.labelText), theme.nodeText.toSvgHex(), theme.scaledSp(11.5f).value)
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [PacketDiagram] and [PacketDiagramLayout] to a standalone SVG string.
     */
    fun exportPacketToSvg(
        diagram: PacketDiagram,
        layout: PacketDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val strokeHex = theme.nodeBorder.toSvgHex()
        val textHex = theme.nodeText.toSvgHex()
        for (pb in layout.blocks) {
            val r = pb.rect
            val fillHex = (if (pb.isSingleBit) theme.subgraphFill else theme.nodeFill).toSvgHex()
            sb.append("""  <rect class="packet-block" x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" fill="$fillHex" stroke="$strokeHex" stroke-width="1.35"/>""").append('\n')
            if (layout.showBits) {
                val bitY = (r.y - 4f).coerceAtLeast(10f)
                if (pb.isSingleBit) {
                    sb.append("""  <text x="${fmt(r.centerX)}" y="${fmt(bitY)}" text-anchor="middle" fill="$textHex" font-family="sans-serif" font-size="9.5">${escapeXml(pb.leadingBitLabel)}</text>""").append('\n')
                } else {
                    sb.append("""  <text x="${fmt(r.x + 3f)}" y="${fmt(bitY)}" text-anchor="start" fill="$textHex" font-family="sans-serif" font-size="9.5">${escapeXml(pb.leadingBitLabel)}</text>""").append('\n')
                    sb.append("""  <text x="${fmt(r.right - 3f)}" y="${fmt(bitY)}" text-anchor="end" fill="$textHex" font-family="sans-serif" font-size="9.5">${escapeXml(pb.trailingBitLabel)}</text>""").append('\n')
                }
            }
            val availW = (r.width - 6f).coerceAtLeast(10f)
            val plain = plainTextLabel(pb.block.label).replace('\n', ' ')
            val len = plain.length.coerceAtLeast(1)
            val lblSp = when {
                pb.block.bits == 1 && len >= 3 -> 9.5f
                len * 7.2f <= availW -> 12.5f
                len * 6.2f <= availW -> 11f
                len * 5.4f <= availW -> 9.5f
                else -> 9f
            }
            val maxChars = (availW / (lblSp * 0.58f)).toInt().coerceAtLeast(1)
            val fitted = if (plain.length <= maxChars) {
                plain
            } else if (maxChars <= 1) {
                plain.take(1)
            } else {
                plain.take(maxChars - 1) + "…"
            }
            appendPlainMultilineText(sb, "  ", r.centerX, r.centerY, listOf(fitted), textHex, lblSp)
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [RadarDiagram] and [RadarDiagramLayout] to a standalone SVG string.
     */
    fun exportRadarToSvg(
        diagram: RadarDiagram,
        layout: RadarDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val cx = layout.center.x
        val cy = layout.center.y
        val gridHex = theme.subgraphBorder.toSvgHex()
        val numAxes = layout.axes.size

        for (t in layout.ticks downTo 1) {
            val r = layout.radius * t.toFloat() / layout.ticks.coerceAtLeast(1).toFloat()
            if (layout.graticule == RadarGraticule.Polygon && numAxes >= 3) {
                val pts = (0 until numAxes).joinToString(" ") { i ->
                    val angle = layout.axes[i].angleRad.toDouble()
                    "${fmt(cx + (r * cos(angle)).toFloat())},${fmt(cy + (r * sin(angle)).toFloat())}"
                }
                sb.append("""  <polygon points="$pts" fill="none" stroke="$gridHex" stroke-width="1"/>""").append('\n')
            } else {
                sb.append("""  <circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(r)}" fill="none" stroke="$gridHex" stroke-width="1"/>""").append('\n')
            }
        }
        for (pa in layout.axes) {
            sb.append("""  <line x1="${fmt(cx)}" y1="${fmt(cy)}" x2="${fmt(pa.lineEnd.x)}" y2="${fmt(pa.lineEnd.y)}" stroke="$gridHex" stroke-width="1"/>""").append('\n')
            appendMultilineText(sb, "  ", pa.labelRect.centerX, pa.labelRect.centerY, rawSvgLines(pa.axis.label), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
        }
        val fillAlpha = diagram.config.curveOpacity.coerceIn(0f, 1f)
        val rawStrokeW = diagram.config.curveStrokeWidth.coerceAtLeast(0f)
        fun resolveRadarColor(curveIndex: Int): Color =
            parseCssColor(diagram.config.customPalette[curveIndex]) ?: theme.seriesColor(curveIndex)
        for (pc in layout.curves) {
            if (pc.points.isEmpty()) continue
            val cColor = resolveRadarColor(pc.curveIndex)
            val strokeHex = if (rawStrokeW > 0f) cColor.toSvgHex() else "none"
            val fillHex = cColor.copy(alpha = fillAlpha).toSvgHex()
            if (pc.points.size >= 2 && (fillAlpha > 0f || rawStrokeW > 0f)) {
                val pts = pc.points.joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" }
                sb.append("""  <polygon class="radar-curve" points="$pts" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(rawStrokeW)}"/>""").append('\n')
            }
            if (rawStrokeW > 0f) {
                for (pt in pc.points) {
                    sb.append("""  <circle cx="${fmt(pt.x)}" cy="${fmt(pt.y)}" r="3.2" fill="$strokeHex"/>""").append('\n')
                }
            }
        }
        for (li in layout.legendItems) {
            val cColor = resolveRadarColor(li.curveIndex)
            val sr = li.swatchRect
            sb.append("""  <rect x="${fmt(sr.x)}" y="${fmt(sr.y)}" width="${fmt(sr.width)}" height="${fmt(sr.height)}" fill="${cColor.copy(alpha = max(0.35f, fillAlpha)).toSvgHex()}" stroke="${cColor.toSvgHex()}" stroke-width="1.2"/>""").append('\n')
            appendMultilineText(sb, "  ", li.labelRect.centerX, li.labelRect.centerY, rawSvgLines(li.label), theme.nodeText.toSvgHex(), theme.edgeLabelFontSize.value)
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [BlockDiagram] and [BlockDiagramLayout] to a standalone SVG string.
     */
    fun exportBlockToSvg(
        diagram: BlockDiagram,
        layout: BlockDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
        density: Float = 1f,
    ): String {
        val dens = density.coerceAtLeast(0.01f)
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        val bgHex = theme.background.toSvgHex()
        val edgeHex = theme.edge.toSvgHex()

        // 1. Composite containers (drawn first so inner blocks and block arrows sit on top)
        for (pb in layout.blocks) {
            if (!pb.isCompositeContainer) continue
            val r = pb.rect
            val fillHex = (parseCssColor(pb.item.style?.fill) ?: theme.subgraphFill).toSvgHex()
            val strokeHex = (parseCssColor(pb.item.style?.stroke) ?: theme.subgraphBorder).toSvgHex()
            val textHex = (parseCssColor(pb.item.style?.textColor) ?: theme.subgraphText).toSvgHex()
            sb.append("""  <rect class="block-container" x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="$fillHex" stroke="$strokeHex" stroke-width="1.2"/>""").append('\n')
            if (pb.item.label.isNotBlank()) {
                val lr = pb.labelRect ?: DiagramRect(r.x, r.y, r.width, 22f)
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(pb.item.label), textHex, theme.subgraphFontSize.value)
            }
        }

        // 2. Leaf blocks and directional block arrows
        for (pb in layout.blocks) {
            if (pb.isCompositeContainer || pb.item.shape == BlockShape.Space) continue
            val r = pb.rect
            val fillHex = (parseCssColor(pb.item.style?.fill) ?: theme.nodeFill).toSvgHex()
            val strokeHex = (parseCssColor(pb.item.style?.stroke) ?: theme.nodeBorder).toSvgHex()
            val textHex = (parseCssColor(pb.item.style?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = pb.item.style?.strokeWidth ?: 1.3f

            sb.append("""  <g class="block-node" data-id="${escapeXml(pb.item.id)}">""").append('\n')
            if (pb.item.shape == BlockShape.BlockArrow) {
                val effectiveDirs = pb.item.arrowDirections.ifEmpty { listOf(BlockArrowDir.Right) }
                val dirAttr = effectiveDirs.joinToString(",") { it.name.lowercase() }
                val pts = BlockLayout.computeBlockArrowVertices(r, effectiveDirs, dens)
                    .joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" }
                sb.append("""    <polygon class="block-arrow" data-dir="$dirAttr" points="$pts" fill="$fillHex" stroke="$strokeHex" stroke-width="${fmt(strokeW)}"/>""").append('\n')
            } else {
                appendSvgNodeShape(sb, mapBlockNodeShape(pb.item.shape), r, fillHex, strokeHex, strokeW)
            }
            if (pb.item.label.isNotBlank()) {
                appendMultilineText(sb, "    ", r.centerX, r.centerY, rawSvgLines(pb.item.label), textHex, theme.scaledSp(13f).value)
            }
            sb.append("""  </g>""").append('\n')
        }

        // 3. Edges
        for (pe in layout.edges) {
            val pts = pe.points
            if (pts.size < 2) continue
            val dashAttr = if (pe.edge.pattern == BlockEdgePattern.Dotted) """ stroke-dasharray="4,3"""" else ""
            sb.append("""  <path class="block-edge" d="${buildSvgEdgePath(pts)}" fill="none" stroke="$edgeHex" stroke-width="1.5"$dashAttr/>""").append('\n')
            val startToward = pts.drop(1).firstOrNull { it != pts.first() } ?: pts[1]
            val endFrom = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
            appendSvgMarker(sb, pts.first(), startToward, mapBlockEndMarker(pe.edge.startHead), edgeHex, bgHex)
            appendSvgMarker(sb, pts.last(), endFrom, mapBlockEndMarker(pe.edge.endHead), edgeHex, bgHex)
            val lr = pe.labelRect
            val lbl = pe.edge.label
            if (lr != null && !lbl.isNullOrBlank()) {
                sb.append("""  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""").append('\n')
                appendMultilineText(sb, "  ", lr.centerX, lr.centerY, rawSvgLines(lbl), theme.edgeLabelText.toSvgHex(), theme.edgeLabelFontSize.value)
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Exports a pre-laid-out [TreemapDiagram] and [TreemapDiagramLayout] to a standalone SVG string.
     */
    fun exportTreemapToSvg(
        diagram: TreemapDiagram,
        layout: TreemapDiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val sb = startSvgDocument(layout.width, layout.height, theme, diagram.title, layout.titleRect)
        fun fitTreemapLine(text: String, availW: Float, fontSp: Float): String {
            val maxChars = ((availW - 8f) / (fontSp * 0.62f)).toInt()
            if (maxChars <= 0) return ""
            if (text.length <= maxChars) return text
            return if (maxChars <= 1) text.take(1) else text.take(maxChars - 1) + "…"
        }
        for (sec in layout.sections) {
            val r = sec.rect
            val baseCol = theme.seriesColor(sec.colorIndex)
            val fillHex = (parseCssColor(sec.node.style?.fill)?.copy(alpha = 0.25f) ?: baseCol.copy(alpha = 0.18f)).toSvgHex()
            val strokeHex = (parseCssColor(sec.node.style?.stroke) ?: baseCol.copy(alpha = 0.65f)).toSvgHex()
            val textHex = (parseCssColor(sec.node.style?.textColor) ?: theme.nodeText).toSvgHex()
            sb.append("""  <rect class="treemap-section" x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="4" fill="$fillHex" stroke="$strokeHex" stroke-width="1.2"/>""").append('\n')
            val hdrFont = theme.subgraphFontSize.value
            if (sec.headerRect.height >= hdrFont && sec.headerRect.width >= 20f) {
                val rawHdr = if (layout.showValues && sec.formattedValue.isNotBlank()) "${sec.node.name} (${sec.formattedValue})" else sec.node.name
                val fittedHdr = fitTreemapLine(plainTextLabel(rawHdr).replace('\n', ' '), sec.headerRect.width, hdrFont)
                if (fittedHdr.isNotEmpty()) {
                    appendPlainMultilineText(sb, "  ", sec.headerRect.centerX, sec.headerRect.centerY, listOf(fittedHdr), textHex, hdrFont)
                }
            }
        }
        for (lf in layout.leaves) {
            val r = lf.rect
            val baseCol = theme.seriesColor(lf.colorIndex)
            val fillHex = (parseCssColor(lf.node.style?.fill)?.copy(alpha = 0.42f) ?: baseCol.copy(alpha = 0.34f)).toSvgHex()
            val strokeHex = (parseCssColor(lf.node.style?.stroke) ?: baseCol).toSvgHex()
            val textHex = (parseCssColor(lf.node.style?.textColor) ?: theme.nodeText).toSvgHex()
            sb.append("""  <rect class="treemap-leaf" x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="3" fill="$fillHex" stroke="$strokeHex" stroke-width="1.2"/>""").append('\n')
            val plainName = plainTextLabel(lf.node.name).replace('\n', ' ')
            val nameLen = plainName.length.coerceAtLeast(1)
            val maxSpByW = ((r.width - 10f) / (nameLen * 0.62f)).coerceIn(8f, 18f)
            val maxSpByH = (r.height * 0.34f).coerceIn(8f, 18f)
            val lblSp = minOf(max(theme.nodeFontSize.value, 14f), maxSpByW, maxSpByH).coerceIn(8f, 18f)
            if (r.height >= lblSp + 4f && r.width >= 16f) {
                val fittedName = fitTreemapLine(plainName, r.width, lblSp)
                if (fittedName.isNotEmpty()) {
                    val lineH = lblSp * 1.35f
                    val valFits = layout.showValues &&
                        lf.formattedValue.isNotBlank() &&
                        r.height >= lineH * 2f + 6f &&
                        lf.formattedValue.length * lblSp * 0.55f <= r.width - 8f
                    val leafLines = if (valFits) listOf(fittedName, lf.formattedValue) else listOf(fittedName)
                    appendPlainMultilineText(sb, "  ", r.centerX, r.centerY, leafLines, textHex, lblSp)
                }
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun mapBlockNodeShape(shape: BlockShape): NodeShape = when (shape) {
        BlockShape.Rect, BlockShape.BlockArrow, BlockShape.Composite, BlockShape.Space -> NodeShape.Rect
        BlockShape.Round -> NodeShape.RoundedRect
        BlockShape.Stadium -> NodeShape.Stadium
        BlockShape.Subroutine -> NodeShape.Subroutine
        BlockShape.Cylinder -> NodeShape.Cylinder
        BlockShape.Circle -> NodeShape.Circle
        BlockShape.DoubleCircle -> NodeShape.DoubleCircle
        BlockShape.Asymmetric -> NodeShape.Asymmetric
        BlockShape.Diamond -> NodeShape.Diamond
        BlockShape.Hexagon -> NodeShape.Hexagon
        BlockShape.LeanRight -> NodeShape.Parallelogram
        BlockShape.LeanLeft -> NodeShape.ParallelogramAlt
        BlockShape.Trapezoid -> NodeShape.Trapezoid
        BlockShape.InvTrapezoid -> NodeShape.TrapezoidAlt
    }

    private fun mapBlockEndMarker(head: BlockArrowHead): SvgMarker = when (head) {
        BlockArrowHead.None -> SvgMarker.None
        BlockArrowHead.Point -> SvgMarker.FilledArrow
        BlockArrowHead.Circle -> SvgMarker.Circle
        BlockArrowHead.Cross -> SvgMarker.Cross
    }

    private fun c4WrapWidth(cardWidth: Float, dens: Float): Float =
        (cardWidth - 28f * dens).coerceAtLeast(80f * dens)

    private fun zenUmlCommentWrapWidth(noteWidth: Float, dens: Float): Float =
        (noteWidth - 16f * dens).coerceAtLeast(48f * dens)

    private fun exportTextStyle(
        theme: MaradonaTheme,
        fontSp: Float,
        densityObj: Density,
        textMeasurer: TextMeasurer?,
        fontWeight: FontWeight? = null,
    ): TextStyle? {
        if (textMeasurer == null) return null
        val scale = (densityObj.density * densityObj.fontScale).coerceAtLeast(0.01f)
        return TextStyle(
            fontFamily = theme.fontFamily,
            fontSize = (fontSp / scale).sp,
            fontWeight = fontWeight,
        )
    }

    private fun FontFamily.toSvgFontFamily(): String = when (this) {
        FontFamily.Monospace -> "monospace"
        FontFamily.Serif -> "serif"
        FontFamily.Cursive -> "cursive"
        else -> "sans-serif"
    }

    private fun c4WrappedCardSpanLines(
        el: dev.maradona.model.C4Element,
        maxWidth: Float,
        theme: MaradonaTheme = MaradonaTheme.light(),
        textMeasurer: TextMeasurer? = null,
        densityObj: Density = Density(1f),
    ): List<Pair<Float, List<FormattedTextSpan>>> {
        val displayLabel = if (!el.sprite.isNullOrBlank()) {
            "${resolveMermaidIconGlyph(el.sprite)} ${el.label}"
        } else {
            el.label
        }
        val hdrFont = theme.scaledSp(10f).value
        val lblFont = theme.scaledSp(13.5f).value
        val descFont = theme.captionFontSize.value
        val hdrStyle = exportTextStyle(theme, hdrFont, densityObj, textMeasurer)
        val lblStyle = exportTextStyle(theme, lblFont, densityObj, textMeasurer)
        val descStyle = exportTextStyle(theme, descFont, densityObj, textMeasurer)
        return buildList {
            for (line in wrapFormattedLabelLines(el.typeHeaderText, hdrFont, maxWidth, textMeasurer, hdrStyle, densityObj)) {
                add(hdrFont to line)
            }
            for (line in wrapFormattedLabelLines(displayLabel, lblFont, maxWidth, textMeasurer, lblStyle, densityObj)) {
                add(lblFont to line)
            }
            if (!el.description.isNullOrBlank()) {
                for (line in wrapFormattedLabelLines(el.description, descFont, maxWidth, textMeasurer, descStyle, densityObj)) {
                    add(descFont to line)
                }
            }
        }
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
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h" font-family="${theme.fontFamily.toSvgFontFamily()}">""").append('\n')
        sb.append("""  <rect width="100%" height="100%" fill="${theme.background.toSvgHex()}"/>""").append('\n')
        if (!titleText.isNullOrBlank() && titleRect != null) {
            appendMultilineText(
                sb = sb,
                indent = "  ",
                centerX = titleRect.centerX,
                centerY = titleRect.centerY,
                lines = rawSvgLines(titleText),
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
                val rad = if (marker == SvgMarker.Lollipop) 6f else 4.5f
                val cx = if (marker == SvgMarker.Lollipop) tip.x - cos(angle).toFloat() * rad else tip.x
                val cy = if (marker == SvgMarker.Lollipop) tip.y - sin(angle).toFloat() * rad else tip.y
                sb.append("""  <circle class="edge-marker" data-marker="$tag" cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(rad)}" fill="$bgColor" stroke="$color" stroke-width="1.5"/>""").append('\n')
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
        val spanLines = wrapFormattedLabelLines(
            label = node.label,
            fontSp = fontSize,
            maxWidth = NodeSizing.WRAPPING_WIDTH,
            includeInlineImages = true,
        )
        var maxLineW = 0f
        var totalH = 0f
        for (line in spanLines) {
            var lineW = 0f
            var lineH = fontSize * 1.5f
            for (span in line) {
                val img = span.inlineImage
                if (img != null) {
                    lineW += img.widthPx
                    lineH = max(lineH, img.heightPx)
                } else {
                    val mult = when {
                        span.code -> 0.65f
                        span.bold -> 0.66f
                        else -> 0.62f
                    }
                    lineW += span.text.length * fontSize * mult
                }
            }
            maxLineW = max(maxLineW, lineW)
            totalH += lineH
        }
        val textW = maxLineW.coerceAtLeast(fontSize * 0.62f)
        val textH = totalH.coerceAtLeast(fontSize * 1.5f)
        val labelBox = NodeSizing.labelBox(node.shape, textW, textH)
        return NodeSizing.nodeBox(node.shape, labelBox.width, labelBox.height)
    }

    private fun defaultEdgeLabelSize(text: String, fontSp: Float): DiagramSize =
        measureSpanLinesSize(wrapFormattedLabelLines(text, fontSp, 172f), fontSp)

    private fun defaultTextSize(text: String, fontSp: Float): DiagramSize =
        measureSpanLinesSize(parseFormattedLabelLines(text), fontSp)

    private fun measureSpanLinesSize(
        spanLines: List<List<FormattedTextSpan>>,
        fontSp: Float,
    ): DiagramSize {
        var maxLineW = 0f
        var totalH = 0f
        for (line in spanLines) {
            var lineW = 0f
            var lineH = fontSp * 1.4f
            for (span in line) {
                val img = span.inlineImage
                if (img != null) {
                    lineW += img.widthPx
                    lineH = max(lineH, img.heightPx)
                } else {
                    val mult = when {
                        span.code -> 0.65f
                        span.bold -> 0.66f
                        else -> 0.62f
                    }
                    lineW += span.text.length * fontSp * mult
                }
            }
            maxLineW = max(maxLineW, lineW)
            totalH += lineH
        }
        return DiagramSize(
            width = max(24f, maxLineW + 8f),
            height = max(18f, totalH + 4f),
        )
    }

    private fun rawSvgLines(label: String): List<String> =
        label.split('\n')

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

    private fun renderSpansToSvgContent(spans: List<FormattedTextSpan>): String {
        val textSpans = spans.filter { it.inlineImage == null && it.text.isNotEmpty() }
        if (textSpans.isEmpty()) return ""
        if (textSpans.size == 1 && !textSpans[0].hasFormatting) {
            return escapeXml(textSpans[0].text)
        }
        val sb = StringBuilder()
        for (span in textSpans) {
            if (!span.hasFormatting) {
                sb.append(escapeXml(span.text))
            } else {
                val attrs = StringBuilder()
                if (span.bold) attrs.append(""" font-weight="bold"""")
                if (span.italic) attrs.append(""" font-style="italic"""")
                if (span.code) attrs.append(""" font-family="monospace"""")
                val decs = buildList {
                    if (span.underline) add("underline")
                    if (span.strikethrough) add("line-through")
                }
                if (decs.isNotEmpty()) {
                    attrs.append(""" text-decoration="${decs.joinToString(" ")}"""")
                }
                when {
                    span.subscript -> attrs.append(""" baseline-shift="sub" font-size="80%"""")
                    span.superscript -> attrs.append(""" baseline-shift="super" font-size="80%"""")
                }
                sb.append("""<tspan$attrs>${escapeXml(span.text)}</tspan>""")
            }
        }
        return sb.toString()
    }

    private fun appendFormattedLineWithImages(
        sb: StringBuilder,
        indent: String,
        centerX: Float,
        lineCenterY: Float,
        baselineY: Float,
        spanLine: List<FormattedTextSpan>,
        colorHex: String,
        fontSize: Float,
        extraTextAttrs: String = "",
        imageScale: Float = 1f,
        textMeasurer: TextMeasurer? = null,
        textStyle: TextStyle? = null,
        density: Density = Density(1f),
    ) {
        if (spanLine.none { it.inlineImage != null }) {
            val innerXml = renderSpansToSvgContent(spanLine)
            sb.append(
                """$indent<text x="${fmt(centerX)}" y="${fmt(baselineY)}" text-anchor="middle" fill="$colorHex" font-size="${fmt(fontSize)}"$extraTextAttrs>$innerXml</text>""",
            ).append('\n')
            return
        }
        // Mixed or image-containing line: compute advances in span sequence so images and text
        // sit side by side in their declared order without overlapping.
        fun spanAdvance(span: FormattedTextSpan): Float {
            val img = span.inlineImage
            if (img != null) return img.widthPx * imageScale
            if (span.text.isEmpty()) return 0f
            if (textMeasurer != null && textStyle != null) {
                val styled = androidx.compose.ui.text.buildAnnotatedString {
                    val st = if (span.bold || span.italic || span.code) {
                        androidx.compose.ui.text.SpanStyle(
                            fontWeight = if (span.bold) FontWeight.Bold else null,
                            fontStyle = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                            fontFamily = if (span.code) FontFamily.Monospace else null,
                        )
                    } else {
                        null
                    }
                    if (st != null) {
                        pushStyle(st)
                        append(span.text)
                        pop()
                    } else {
                        append(span.text)
                    }
                }
                return textMeasurer.measure(
                    text = styled,
                    style = textStyle,
                    constraints = androidx.compose.ui.unit.Constraints(),
                    density = density,
                ).size.width.toFloat()
            }
            val mult = when {
                span.code -> 0.65f
                span.bold -> 0.66f
                else -> 0.62f
            }
            return span.text.length * fontSize * mult
        }

        val advances = spanLine.map { spanAdvance(it) }
        val totalWidth = advances.sum()
        var curX = centerX - totalWidth / 2f
        val pendingTextSpans = ArrayList<FormattedTextSpan>()
        var textRunStartX = curX
        var pendingRunWidth = 0f

        fun flushPendingText() {
            if (pendingTextSpans.isEmpty()) return
            val innerXml = renderSpansToSvgContent(pendingTextSpans)
            if (innerXml.isNotEmpty()) {
                val lenAttr = if (pendingRunWidth > 0f) """ textLength="${fmt(pendingRunWidth)}"""" else ""
                sb.append(
                    """$indent<text x="${fmt(textRunStartX)}" y="${fmt(baselineY)}" text-anchor="start" fill="$colorHex" font-size="${fmt(fontSize)}"$lenAttr$extraTextAttrs>$innerXml</text>""",
                ).append('\n')
            }
            pendingTextSpans.clear()
            pendingRunWidth = 0f
        }

        for ((idx, span) in spanLine.withIndex()) {
            val adv = advances[idx]
            val img = span.inlineImage
            if (img != null) {
                flushPendingText()
                val imgW = img.widthPx * imageScale
                val imgH = img.heightPx * imageScale
                val imgX = curX
                val imgY = lineCenterY - imgH / 2f
                sb.append(
                    """$indent<image class="inline-image" href="${escapeXml(img.url)}" x="${fmt(imgX)}" y="${fmt(imgY)}" width="${fmt(imgW)}" height="${fmt(imgH)}" preserveAspectRatio="xMidYMid meet"><title>${escapeXml(img.alt)}</title></image>""",
                ).append('\n')
                curX += adv
                textRunStartX = curX
            } else {
                if (pendingTextSpans.isEmpty()) {
                    textRunStartX = curX
                }
                pendingTextSpans += span
                pendingRunWidth += adv
                curX += adv
            }
        }
        flushPendingText()
    }

    private fun appendRichSpanLines(
        sb: StringBuilder,
        indent: String,
        centerX: Float,
        centerY: Float,
        spanLines: List<List<FormattedTextSpan>>,
        colorHex: String,
        fontSize: Float,
        rotationDeg: Float = 0f,
        imageScale: Float = 1f,
        textMeasurer: TextMeasurer? = null,
        textStyle: TextStyle? = null,
        density: Density = Density(1f),
    ) {
        if (spanLines.isEmpty()) return
        val lineHeights = spanLines.map { line ->
            val maxImgH = (line.mapNotNull { it.inlineImage?.heightPx }.maxOrNull() ?: 0f) * imageScale
            if (maxImgH > 0f) {
                max(fontSize * 1.35f, maxImgH)
            } else {
                fontSize * 1.35f
            }
        }
        val totalH = lineHeights.sum()
        var curTopY = centerY - totalH / 2f
        val rotateAttr = if (rotationDeg != 0f) {
            """ transform="rotate(${fmt(rotationDeg)} ${fmt(centerX)} ${fmt(centerY)})""""
        } else {
            ""
        }
        for ((idx, spanLine) in spanLines.withIndex()) {
            val lh = lineHeights[idx]
            val lineCenterY = curTopY + lh / 2f
            val ly = lineCenterY + fontSize * 0.35f
            appendFormattedLineWithImages(
                sb = sb,
                indent = indent,
                centerX = centerX,
                lineCenterY = lineCenterY,
                baselineY = ly,
                spanLine = spanLine,
                colorHex = colorHex,
                fontSize = fontSize,
                extraTextAttrs = rotateAttr,
                imageScale = imageScale,
                textMeasurer = textMeasurer,
                textStyle = textStyle,
                density = density,
            )
            curTopY += lh
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
        rotationDeg: Float = 0f,
    ) {
        val spanLines = if (lines.isEmpty()) {
            emptyList()
        } else {
            parseFormattedLabelLines(lines.joinToString("\n"))
        }
        appendRichSpanLines(
            sb = sb,
            indent = indent,
            centerX = centerX,
            centerY = centerY,
            spanLines = spanLines,
            colorHex = colorHex,
            fontSize = fontSize,
            rotationDeg = rotationDeg,
        )
    }

    private fun appendPlainMultilineText(
        sb: StringBuilder,
        indent: String,
        centerX: Float,
        centerY: Float,
        lines: List<String>,
        colorHex: String,
        fontSize: Float,
        rotationDeg: Float = 0f,
    ) {
        appendRichSpanLines(
            sb = sb,
            indent = indent,
            centerX = centerX,
            centerY = centerY,
            spanLines = lines.map { listOf(FormattedTextSpan(it)) },
            colorHex = colorHex,
            fontSize = fontSize,
            rotationDeg = rotationDeg,
        )
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
