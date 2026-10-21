package dev.maradona.parse

import dev.maradona.model.ClassDiagram
import dev.maradona.model.Flowchart
import dev.maradona.model.SequenceDiagram

/** Parsed representation of any Mermaid diagram type supported by Maradona. */
sealed interface MaradonaDiagramModel {
    data class FlowchartModel(val chart: Flowchart) : MaradonaDiagramModel
    data class SequenceModel(val diagram: SequenceDiagram) : MaradonaDiagramModel
    data class ClassModel(val diagram: ClassDiagram) : MaradonaDiagramModel
}

/**
 * Top-level Mermaid parser that detects the diagram type from the header line
 * (after optional YAML frontmatter, `%%{init}%%` directives, and `%%` comments)
 * and dispatches to [FlowchartParser], [SequenceParser], or [ClassParser].
 */
object MaradonaParser {

    fun parse(source: String): MaradonaDiagramModel {
        val header = firstHeaderToken(source)
        return when {
            header.startsWith("sequencediagram") ->
                MaradonaDiagramModel.SequenceModel(SequenceParser.parse(source))
            header.startsWith("classdiagram") ->
                MaradonaDiagramModel.ClassModel(ClassParser.parse(source))
            else ->
                MaradonaDiagramModel.FlowchartModel(FlowchartParser.parse(source))
        }
    }

    private fun firstHeaderToken(source: String): String {
        val lines = FlowchartParser.stripDirectives(source).lines()
        var inFrontmatter = false
        for (rawLine in lines) {
            val trimmedRaw = rawLine.trim()
            if (inFrontmatter) {
                if (trimmedRaw == "---" || trimmedRaw == "...") inFrontmatter = false
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
