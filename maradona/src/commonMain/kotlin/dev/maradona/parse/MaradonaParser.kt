package dev.maradona.parse

import dev.maradona.model.ClassDiagram
import dev.maradona.model.ErDiagram
import dev.maradona.model.Flowchart
import dev.maradona.model.GanttDiagram
import dev.maradona.model.GitGraphDiagram
import dev.maradona.model.MindmapDiagram
import dev.maradona.model.PieDiagram
import dev.maradona.model.SequenceDiagram
import dev.maradona.model.StateDiagram
import dev.maradona.model.TimelineDiagram

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
}

/**
 * Top-level Mermaid parser that detects the diagram type from the header line
 * (after optional YAML frontmatter, `%%{init}%%` directives, and `%%` comments)
 * and dispatches to [FlowchartParser], [SequenceParser], [ClassParser], [StateParser],
 * [ErParser], [MindmapParser], [GanttParser], [PieParser], [GitGraphParser], or [TimelineParser].
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
            val commentAt = trimmedRaw.indexOf("%%")
            val line = (if (commentAt >= 0) trimmedRaw.substring(0, commentAt) else trimmedRaw).trim()
            if (line.isNotEmpty()) return line.lowercase()
        }
        return ""
    }
}
