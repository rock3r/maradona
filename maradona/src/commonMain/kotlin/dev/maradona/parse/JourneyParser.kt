package dev.maradona.parse

import dev.maradona.model.JourneyDiagram
import dev.maradona.model.JourneyTask

/**
 * Hand-written parser for Mermaid `journey` (User Journey) diagrams.
 *
 * Supports:
 * - `journey` header, YAML frontmatter (`title`), and `%%{init}%%` directives
 * - `title`, `accTitle`, `accDescr` (single-line and multiline `{ ... }`)
 * - `section <title>` groupings
 * - `<taskName> : <score> [: <actor1>, <actor2>, ...]` task declarations
 * - `%%` and `#` comments and optional `;` statement terminators
 */
object JourneyParser {

    fun parse(source: String): JourneyDiagram {
        var frontmatterTitle: String? = null

        val initMatch = Regex("""%%\{[\s\S]*?\}%%""").find(source)
        if (initMatch != null) {
            val initBody = initMatch.value
            val titleMatch = Regex(
                """['"]?title['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)')""",
                RegexOption.IGNORE_CASE,
            ).find(initBody)
            if (titleMatch != null) {
                val rawTitle = titleMatch.groupValues[1].ifEmpty { titleMatch.groupValues[2] }
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
                    .replace("\\\\", "\\")
                    .trim()
                if (rawTitle.isNotEmpty()) {
                    frontmatterTitle = normalizeText(rawTitle)
                }
            }
        }

        val rawLines = FlowchartParser.stripDirectives(source).lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripLineComment(rawLines[idx]).trim().isEmpty()) {
            idx++
        }
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            var activeFmQuote: Char? = null
            while (idx < rawLines.size) {
                val rawFm = rawLines[idx]
                idx++
                val trimmedFm = rawFm.trim()
                if (activeFmQuote == null && (trimmedFm == "---" || trimmedFm == "...")) break
                val cleanFm = GanttParser.stripYamlCommentWithQuoteState(rawFm, activeFmQuote)
                activeFmQuote = cleanFm.second
                val fmLine = cleanFm.first.trim()
                if (fmLine.isEmpty()) continue
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    val value = unquote(fmLine.substring(colon + 1).trim())
                    if (key == "title" && value.isNotEmpty()) {
                        frontmatterTitle = normalizeText(value)
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val sections = mutableListOf<String>()
        var currentSection: String? = null
        val tasks = mutableListOf<JourneyTask>()

        for (rawLine in bodyLines) {
            val rawTrimmed = rawLine.trim()
            if (inAccDescrBlock) {
                val closeIdx = rawTrimmed.indexOf('}')
                if (closeIdx >= 0) {
                    val beforeClose = rawTrimmed.substring(0, closeIdx).trim()
                    if (beforeClose.isNotEmpty()) accDescrLines += beforeClose
                    accDescr = accDescrLines.joinToString("\n").trim()
                    accDescrLines.clear()
                    inAccDescrBlock = false
                } else if (rawTrimmed.isNotEmpty()) {
                    accDescrLines += rawTrimmed
                }
                continue
            }

            val accBlockMatch = Regex("""^accDescr\s*\{(.*)$""", RegexOption.IGNORE_CASE).find(rawTrimmed)
            if (accBlockMatch != null) {
                headerSeen = true
                val afterOpen = accBlockMatch.groupValues[1]
                val closeIdx = afterOpen.indexOf('}')
                if (closeIdx >= 0) {
                    accDescr = normalizeText(afterOpen.substring(0, closeIdx).trim())
                } else {
                    inAccDescrBlock = true
                    accDescrLines.clear()
                    val firstLine = afterOpen.trim()
                    if (firstLine.isNotEmpty()) accDescrLines += firstLine
                }
                continue
            }

            val clean = stripLineComment(rawLine).trim().trimEnd(';').trim()
            if (clean.isEmpty()) continue
            val lower = clean.lowercase()

            if (!headerSeen) {
                if (lower == "journey" || lower.startsWith("journey ") || lower.startsWith("journey\t")) {
                    headerSeen = true
                    val rest = clean.substring("journey".length).trim()
                    val inlineTitle = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(rest)
                    if (inlineTitle != null) {
                        val afterTitle = inlineTitle.groupValues[1].trim()
                        if (afterTitle.isNotEmpty()) chartTitle = normalizeText(afterTitle)
                    }
                    continue
                }
                headerSeen = true
            }

            val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(clean)
            if (titleMatch != null) {
                val after = titleMatch.groupValues[1].trim()
                if (after.isNotEmpty()) chartTitle = normalizeText(after)
                continue
            }
            val accTitleMatch = Regex("""^accTitle\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(clean)
            if (accTitleMatch != null) {
                accTitle = normalizeText(accTitleMatch.groupValues[1].trim())
                continue
            }
            val accDescrMatch = Regex("""^accDescr\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(clean)
            if (accDescrMatch != null) {
                accDescr = normalizeText(accDescrMatch.groupValues[1].trim())
                continue
            }
            val sectionMatch = Regex("""^section(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(clean)
            if (sectionMatch != null) {
                val sec = normalizeText(sectionMatch.groupValues[1].trim())
                if (sec.isNotEmpty()) {
                    currentSection = sec
                    if (sec !in sections) sections += sec
                }
                continue
            }

            val colonIdx = findTaskDelimiterColon(clean)
            if (colonIdx <= 0) {
                throw IllegalArgumentException("Invalid journey task syntax (expected 'Task : score [: actors]'): $clean")
            }
            val taskLabel = normalizeText(clean.substring(0, colonIdx))
            if (taskLabel.isEmpty()) {
                throw IllegalArgumentException("Journey task name cannot be empty: $clean")
            }
            val taskData = clean.substring(colonIdx + 1).trim()
            val pieces = taskData.split(':', limit = 2)
            val scoreText = pieces[0].trim()
            val score = scoreText.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid numeric score '$scoreText' in journey task: $clean")
            if (!score.isFinite()) {
                throw IllegalArgumentException("Journey task score must be finite: $clean")
            }
            val actors = if (pieces.size >= 2) {
                pieces[1].split(',')
                    .map { normalizeText(it) }
                    .filter { it.isNotEmpty() }
            } else {
                emptyList()
            }

            tasks += JourneyTask(
                id = "jtask_${tasks.size}",
                label = taskLabel,
                score = score,
                actors = actors,
                section = currentSection,
            )
        }

        val sortedActors = tasks.flatMap { it.actors }.distinct().sorted()

        return JourneyDiagram(
            tasks = tasks,
            sections = sections,
            actors = sortedActors,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun findTaskDelimiterColon(line: String): Int {
        var inDoubleQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inDoubleQuotes && i + 1 < line.length) {
                i += 2
                continue
            }
            if (c == '"' && (inDoubleQuotes || line.substring(0, i).isBlank())) {
                inDoubleQuotes = !inDoubleQuotes
                i++
                continue
            }
            if (!inDoubleQuotes && c == ':') {
                return i
            }
            i++
        }
        return -1
    }

    private fun stripLineComment(line: String): String {
        var inDoubleQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inDoubleQuotes && i + 1 < line.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inDoubleQuotes = !inDoubleQuotes
                i++
                continue
            }
            if (!inDoubleQuotes) {
                if (i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                    return line.substring(0, i)
                }
                if (c == '#') {
                    return line.substring(0, i)
                }
            }
            i++
        }
        return line
    }

    private fun normalizeText(s: String): String =
        unquote(s)
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<(b|strong)>(.*?)</\1>""", RegexOption.IGNORE_CASE), "**$2**")
            .replace(Regex("""<(i|em)>(.*?)</\1>""", RegexOption.IGNORE_CASE), "*$2*")
            .trim()

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .trim()
        }
        return t
    }
}
