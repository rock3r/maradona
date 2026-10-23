package dev.maradona.parse

import dev.maradona.model.TimelineDiagram
import dev.maradona.model.TimelineDirection
import dev.maradona.model.TimelinePeriod

/**
 * Hand-written parser for Mermaid `timeline` diagrams.
 *
 * Supports:
 * - `timeline`, `timeline LR`, `timeline TD` / `timeline TB`
 * - YAML frontmatter and `%%{init}%%` configuration (`title`, `disableMulticolor`)
 * - `title`, `accTitle`, `accDescr` (single-line and multiline `{ ... }`)
 * - `section <title>` groupings (including `<br>` multiline titles)
 * - `period : event1 : event2` declarations, bare `period` declarations, and `: event` continuation lines
 */
object TimelineParser {

    private class MutablePeriod(
        val id: String,
        val title: String,
        val section: String?,
        val events: MutableList<String> = mutableListOf(),
    )

    fun parse(source: String): TimelineDiagram {
        var frontmatterTitle: String? = null
        var disableMulticolor = false

        val initMatch = Regex("""%%\{.*?\}%%""", setOf(RegexOption.DOT_MATCHES_ALL)).find(source)
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
            Regex("""['"]?disableMulticolor['"]?\s*:\s*['"]?(true|false)['"]?""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.lowercase()?.toBooleanStrictOrNull()?.let {
                    disableMulticolor = it
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
                    when (key) {
                        "title" -> if (value.isNotEmpty()) frontmatterTitle = normalizeText(value)
                        "disablemulticolor" -> value.lowercase().toBooleanStrictOrNull()?.let { disableMulticolor = it }
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        var headerSeen = false
        var direction = TimelineDirection.LR
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val sections = mutableListOf<String>()
        var currentSection: String? = null
        val periods = mutableListOf<MutablePeriod>()

        for (rawLine in bodyLines) {
            val clean = stripLineComment(rawLine).trim()
            if (inAccDescrBlock) {
                val closeIdx = clean.indexOf('}')
                if (closeIdx >= 0) {
                    val beforeClose = clean.substring(0, closeIdx).trim()
                    if (beforeClose.isNotEmpty()) accDescrLines += beforeClose
                    accDescr = accDescrLines.joinToString("\n").trim()
                    accDescrLines.clear()
                    inAccDescrBlock = false
                } else {
                    accDescrLines += clean
                }
                continue
            }
            if (clean.isEmpty()) continue
            val lower = clean.lowercase()

            if (!headerSeen) {
                if (lower == "timeline" || lower.startsWith("timeline ") || lower.startsWith("timeline\t") || lower.startsWith("timeline:")) {
                    headerSeen = true
                    val headerRest = clean.substring("timeline".length).trim().removeSuffix(":").trim().uppercase()
                    direction = when (headerRest) {
                        "TD", "TB" -> TimelineDirection.TD
                        else -> TimelineDirection.LR
                    }
                    continue
                }
                headerSeen = true
            }

            when {
                lower.startsWith("title ") || lower.startsWith("title:") -> {
                    val after = clean.substring(5).trim().removePrefix(":").trim()
                    if (after.isNotEmpty()) chartTitle = normalizeText(after)
                    continue
                }
                lower.startsWith("acctitle:") -> {
                    accTitle = normalizeText(clean.substring("acctitle:".length).trim())
                    continue
                }
                lower.startsWith("accdescr:") -> {
                    accDescr = normalizeText(clean.substring("accdescr:".length).trim())
                    continue
                }
                lower.startsWith("accdescr") && '{' in clean -> {
                    val openIdx = clean.indexOf('{')
                    val afterOpen = clean.substring(openIdx + 1)
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
                lower.startsWith("section ") || lower.startsWith("section:") -> {
                    val sec = normalizeText(clean.substring(7).trim().removePrefix(":").trim())
                    if (sec.isNotEmpty()) {
                        currentSection = sec
                        if (sec !in sections) sections += sec
                    }
                    continue
                }
            }

            if (clean.startsWith(":")) {
                val target = periods.lastOrNull()
                    ?: throw IllegalArgumentException("Timeline event continuation '$clean' has no preceding period")
                val parts = splitTimelineEvents(clean.substring(1))
                    .map { normalizeText(it) }
                    .filter { it.isNotEmpty() }
                target.events.addAll(parts)
            } else {
                val (rawPeriod, eventsRest) = splitPeriodAndEvents(clean)
                val pTitle = normalizeText(rawPeriod)
                if (pTitle.isEmpty()) continue
                val evs = if (eventsRest != null) {
                    splitTimelineEvents(eventsRest).map { normalizeText(it) }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
                val period = MutablePeriod(
                    id = "period_${periods.size}",
                    title = pTitle,
                    section = currentSection,
                )
                period.events.addAll(evs)
                periods += period
            }
        }

        return TimelineDiagram(
            periods = periods.map { p ->
                TimelinePeriod(
                    id = p.id,
                    title = p.title,
                    section = p.section,
                    events = p.events.toList(),
                )
            },
            sections = sections,
            direction = direction,
            title = chartTitle ?: frontmatterTitle,
            disableMulticolor = disableMulticolor,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun splitPeriodAndEvents(line: String): Pair<String, String?> {
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
                return line.substring(0, i) to line.substring(i + 1)
            }
            i++
        }
        return line to null
    }

    /**
     * Splits event text on `:` delimiters. Following Mermaid's timeline lexer, a colon inside
     * an event (such as `https://example.com` or `10:30`) is preserved unless it is followed
     * by whitespace or end-of-line.
     */
    private fun splitTimelineEvents(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inDoubleQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && inDoubleQuotes && i + 1 < text.length) {
                sb.append(text[i])
                sb.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '"' && (inDoubleQuotes || sb.toString().isBlank())) {
                inDoubleQuotes = !inDoubleQuotes
                sb.append(c)
                i++
                continue
            }
            if (!inDoubleQuotes && c == ':' && (i + 1 >= text.length || text[i + 1].isWhitespace())) {
                out += sb.toString()
                sb.setLength(0)
                i++
                continue
            }
            sb.append(c)
            i++
        }
        out += sb.toString()
        return out
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
            if (!inDoubleQuotes && i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
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
