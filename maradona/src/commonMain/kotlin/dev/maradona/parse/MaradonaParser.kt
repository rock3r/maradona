package dev.maradona.parse

import dev.maradona.model.BlockDiagram
import dev.maradona.model.ClassDiagram
import dev.maradona.model.ErDiagram
import dev.maradona.model.Flowchart
import dev.maradona.model.GanttDiagram
import dev.maradona.model.GitGraphDiagram
import dev.maradona.model.JourneyDiagram
import dev.maradona.model.KanbanDiagram
import dev.maradona.model.MindmapDiagram
import dev.maradona.model.PacketDiagram
import dev.maradona.model.PieDiagram
import dev.maradona.model.QuadrantDiagram
import dev.maradona.model.RadarDiagram
import dev.maradona.model.RequirementDiagram
import dev.maradona.model.SankeyDiagram
import dev.maradona.model.SequenceDiagram
import dev.maradona.model.StateDiagram
import dev.maradona.model.TimelineDiagram
import dev.maradona.model.XyChartDiagram

/** Parsed representation of any Mermaid diagram type supported by Maradona. */
sealed interface MaradonaDiagramModel {
    data class FlowchartModel(val chart: Flowchart) : MaradonaDiagramModel
    data class SequenceModel(val diagram: SequenceDiagram) : MaradonaDiagramModel
    data class ClassModel(val diagram: ClassDiagram) : MaradonaDiagramModel
    data class StateModel(val diagram: StateDiagram) : MaradonaDiagramModel
    data class ErModel(val diagram: ErDiagram) : MaradonaDiagramModel
    data class MindmapModel(val diagram: MindmapDiagram) : MaradonaDiagramModel
    data class GanttModel(val diagram: GanttDiagram) : MaradonaDiagramModel
    data class PieModel(val diagram: PieDiagram) : MaradonaDiagramModel
    data class GitGraphModel(val diagram: GitGraphDiagram) : MaradonaDiagramModel
    data class TimelineModel(val diagram: TimelineDiagram) : MaradonaDiagramModel
    data class JourneyModel(val diagram: JourneyDiagram) : MaradonaDiagramModel
    data class QuadrantModel(val diagram: QuadrantDiagram) : MaradonaDiagramModel
    data class XyChartModel(val diagram: XyChartDiagram) : MaradonaDiagramModel
    data class KanbanModel(val diagram: KanbanDiagram) : MaradonaDiagramModel
    data class SankeyModel(val diagram: SankeyDiagram) : MaradonaDiagramModel
    data class RequirementModel(val diagram: RequirementDiagram) : MaradonaDiagramModel
    data class PacketModel(val diagram: PacketDiagram) : MaradonaDiagramModel
    data class RadarModel(val diagram: RadarDiagram) : MaradonaDiagramModel
    data class BlockModel(val diagram: BlockDiagram) : MaradonaDiagramModel
}

/**
 * Top-level Mermaid parser that detects the diagram type from the header line
 * (after optional YAML frontmatter, `%%{init}%%` directives, and `%%` comments)
 * and dispatches to [FlowchartParser], [SequenceParser], [ClassParser], [StateParser],
 * [ErParser], [MindmapParser], [GanttParser], [PieParser], [GitGraphParser], [TimelineParser],
 * [JourneyParser], [QuadrantParser], [XyChartParser], [KanbanParser], [SankeyParser],
 * [RequirementParser], [PacketParser], [RadarParser], or [BlockParser].
 */
object MaradonaParser {

    fun parse(source: String): MaradonaDiagramModel {
        val header = firstHeaderToken(source)
        return when {
            header.startsWith("sequencediagram") ->
                MaradonaDiagramModel.SequenceModel(SequenceParser.parse(source))
            header.startsWith("classdiagram") ->
                MaradonaDiagramModel.ClassModel(ClassParser.parse(source))
            header.startsWith("statediagram") ->
                MaradonaDiagramModel.StateModel(StateParser.parse(source))
            header.startsWith("erdiagram") ->
                MaradonaDiagramModel.ErModel(ErParser.parse(source))
            header.startsWith("mindmap") ->
                MaradonaDiagramModel.MindmapModel(MindmapParser.parse(source))
            header.startsWith("gantt") ->
                MaradonaDiagramModel.GanttModel(GanttParser.parse(source))
            header == "pie" || header.startsWith("pie ") || header.startsWith("pie\t") ->
                MaradonaDiagramModel.PieModel(PieParser.parse(source))
            header.startsWith("gitgraph") ->
                MaradonaDiagramModel.GitGraphModel(GitGraphParser.parse(source))
            header == "timeline" || header.startsWith("timeline ") || header.startsWith("timeline\t") || header.startsWith("timeline:") ->
                MaradonaDiagramModel.TimelineModel(TimelineParser.parse(source))
            header == "journey" || header.startsWith("journey ") || header.startsWith("journey\t") || header.startsWith("journey;") ->
                MaradonaDiagramModel.JourneyModel(JourneyParser.parse(source))
            header == "quadrantchart" || header.startsWith("quadrantchart ") || header.startsWith("quadrantchart\t") ->
                MaradonaDiagramModel.QuadrantModel(QuadrantParser.parse(source))
            header == "xychart" || header.startsWith("xychart ") || header.startsWith("xychart\t") ||
                header == "xychart-beta" || header.startsWith("xychart-beta ") || header.startsWith("xychart-beta\t") ->
                MaradonaDiagramModel.XyChartModel(XyChartParser.parse(source))
            header == "kanban" || header.startsWith("kanban ") || header.startsWith("kanban\t") ->
                MaradonaDiagramModel.KanbanModel(KanbanParser.parse(source))
            header == "sankey" || header.startsWith("sankey ") || header.startsWith("sankey\t") ||
                header == "sankey-beta" || header.startsWith("sankey-beta ") || header.startsWith("sankey-beta\t") ->
                MaradonaDiagramModel.SankeyModel(SankeyParser.parse(source))
            header == "requirementdiagram" || header.startsWith("requirementdiagram ") || header.startsWith("requirementdiagram\t") ->
                MaradonaDiagramModel.RequirementModel(RequirementParser.parse(source))
            header == "packet" || header.startsWith("packet ") || header.startsWith("packet\t") ||
                header == "packet-beta" || header.startsWith("packet-beta ") || header.startsWith("packet-beta\t") ->
                MaradonaDiagramModel.PacketModel(PacketParser.parse(source))
            header == "radar" || header.startsWith("radar ") || header.startsWith("radar\t") || header.startsWith("radar:") ||
                header == "radar-beta" || header.startsWith("radar-beta ") || header.startsWith("radar-beta\t") || header.startsWith("radar-beta:") ->
                MaradonaDiagramModel.RadarModel(RadarParser.parse(source))
            header == "block" || header.startsWith("block ") || header.startsWith("block\t") ||
                header == "block-beta" || header.startsWith("block-beta ") || header.startsWith("block-beta\t") ->
                MaradonaDiagramModel.BlockModel(BlockParser.parse(source))
            else ->
                MaradonaDiagramModel.FlowchartModel(FlowchartParser.parse(source))
        }
    }

    private fun firstHeaderToken(source: String): String {
        val lines = FlowchartParser.stripDirectives(source).lines()
        var inFrontmatter = false
        var activeQuoteChar: Char? = null
        for (rawLine in lines) {
            val trimmedRaw = rawLine.trim()
            if (inFrontmatter) {
                if (activeQuoteChar == null && (trimmedRaw == "---" || trimmedRaw == "...")) {
                    inFrontmatter = false
                    continue
                }
                activeQuoteChar = GanttParser.stripYamlCommentWithQuoteState(rawLine, activeQuoteChar).second
                continue
            }
            if (trimmedRaw == "---") {
                inFrontmatter = true
                continue
            }
            if (trimmedRaw.startsWith("%")) continue
            val commentAt = trimmedRaw.indexOf("%%")
            val line = (if (commentAt >= 0) trimmedRaw.substring(0, commentAt) else trimmedRaw)
                .let { s ->
                    val hashAt = s.indexOf('#')
                    if (hashAt >= 0) s.substring(0, hashAt) else s
                }
                .let { s ->
                    val semiAt = s.indexOf(';')
                    if (semiAt >= 0) s.substring(0, semiAt) else s
                }
                .trim()
            if (line.isNotEmpty()) return line.lowercase()
        }
        return ""
    }
}
