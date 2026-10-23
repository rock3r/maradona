package dev.maradona.parse

import dev.maradona.model.KanbanColumn
import dev.maradona.model.KanbanDiagram
import dev.maradona.model.KanbanPriority
import dev.maradona.model.KanbanTask

/**
 * Hand-written parser for Mermaid `kanban` diagrams.
 *
 * Supports:
 * - `kanban` header, YAML frontmatter (`title`, `sectionWidth`, `ticketBaseUrl`), and `%%{init}%%` directives
 * - `title`, `accTitle`, `accDescr` (single-line and multiline `{ ... }`)
 * - Indentation-based columns (at the first node's indentation level) and task cards (indented deeper)
 * - Bracketed (`id[Label]`, `[Label]`, `id(Label)`, `id((Label))`, `id{{Label}}`, `id))Label((`) and bare (`Todo`) nodes
 * - Metadata blocks `@{ ticket: ..., assigned: ..., priority: 'Very High' | 'High' | 'Medium' | 'Low' | 'Very Low', label: ..., icon: ... }`
 * - Decorators `::icon(...)` and `:::className` (inline or on subsequent indented lines)
 */
object KanbanParser {

    private class MutableTask(
        var id: String,
        var label: String,
        var ticket: String? = null,
        var assigned: String? = null,
        var priority: KanbanPriority? = null,
        var rawPriority: String? = null,
        var icon: String? = null,
        var cssClass: String? = null,
    ) {
        fun toModel(): KanbanTask = KanbanTask(
            id = id,
            label = label,
            ticket = ticket,
            assigned = assigned,
            priority = priority,
            rawPriority = rawPriority,
            icon = icon,
            cssClass = cssClass,
        )
    }

    private class MutableColumn(
        var id: String,
        var label: String,
        val tasks: MutableList<MutableTask> = mutableListOf(),
        var ticket: String? = null,
        var icon: String? = null,
        var cssClass: String? = null,
    ) {
        fun toModel(): KanbanColumn = KanbanColumn(
            id = id,
            label = label,
            tasks = tasks.map { it.toModel() },
            ticket = ticket,
            icon = icon,
            cssClass = cssClass,
        )
    }

    fun parse(source: String): KanbanDiagram {
        var frontmatterTitle: String? = null
        var sectionWidth = 210f
        var ticketBaseUrl: String? = null

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
            Regex("""['"]?sectionWidth['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it >= 100f) sectionWidth = it
                }
            Regex("""['"]?ticketBaseUrl['"]?\s*:\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
                .find(initBody)?.let { m ->
                    val url = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
                    if (url.isNotEmpty()) ticketBaseUrl = url
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
                        "sectionwidth" -> value.toFloatOrNull()?.let { if (it.isFinite() && it >= 100f) sectionWidth = it }
                        "ticketbaseurl" -> if (value.isNotEmpty()) ticketBaseUrl = value
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        val mergedLines = mergeMultilineKanbanLines(bodyLines)

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        var sectionIndent: Int? = null
        val columns = mutableListOf<MutableColumn>()
        var lastTask: MutableTask? = null
        var autoIdCounter = 0

        for (rawLine in mergedLines) {
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

            val commentFree = stripLineComment(rawLine)
            val clean = commentFree.trim()
            if (clean.isEmpty()) continue

            if (!headerSeen) {
                val lower = clean.lowercase()
                if (lower == "kanban" || lower.startsWith("kanban ") || lower.startsWith("kanban\t")) {
                    headerSeen = true
                    val afterHeader = clean.substring(6).trim()
                    if (afterHeader.isNotEmpty()) {
                        throw IllegalArgumentException("Unexpected tokens on kanban header line: $clean")
                    }
                    continue
                }
                headerSeen = true
            }

            val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(clean)
            if (titleMatch != null && sectionIndent == null) {
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

            // Standalone ::icon(...) or :::class decorator line.
            val iconOnlyMatch = Regex("""^::icon\(([^)]+)\)$""", RegexOption.IGNORE_CASE).find(clean)
            if (iconOnlyMatch != null) {
                val iconVal = iconOnlyMatch.groupValues[1].trim()
                if (lastTask != null) {
                    lastTask.icon = iconVal
                } else if (columns.isNotEmpty()) {
                    columns.last().icon = iconVal
                }
                continue
            }
            val classOnlyMatch = Regex("""^:::(\S+)$""").find(clean)
            if (classOnlyMatch != null) {
                val clsVal = classOnlyMatch.groupValues[1].trim()
                if (lastTask != null) {
                    lastTask.cssClass = clsVal
                } else if (columns.isNotEmpty()) {
                    columns.last().cssClass = clsVal
                }
                continue
            }

            val indent = measureIndent(commentFree)
            val secIndent = sectionIndent ?: indent.also { sectionIndent = it }
            if (indent < secIndent) {
                throw IllegalArgumentException(
                    "Kanban item '$clean' has indentation ($indent) less than column indentation ($secIndent)",
                )
            }

            val parsedNode = parseNodeLine(clean, autoIdCounter)
            if (parsedNode.usedAutoId) autoIdCounter++

            if (indent == secIndent) {
                columns += MutableColumn(
                    id = parsedNode.id,
                    label = parsedNode.label,
                    ticket = parsedNode.ticket,
                    icon = parsedNode.icon,
                    cssClass = parsedNode.cssClass,
                )
                lastTask = null
            } else {
                val col = columns.lastOrNull()
                    ?: throw IllegalArgumentException("Kanban task '$clean' has no parent column")
                val task = MutableTask(
                    id = parsedNode.id,
                    label = parsedNode.label,
                    ticket = parsedNode.ticket,
                    assigned = parsedNode.assigned,
                    priority = parsedNode.priority,
                    rawPriority = parsedNode.rawPriority,
                    icon = parsedNode.icon,
                    cssClass = parsedNode.cssClass,
                )
                col.tasks += task
                lastTask = task
            }
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in kanban")
        }

        return KanbanDiagram(
            columns = columns.map { it.toModel() },
            sectionWidth = sectionWidth,
            ticketBaseUrl = ticketBaseUrl,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private data class ParsedKanbanNode(
        val id: String,
        val label: String,
        val usedAutoId: Boolean,
        val ticket: String? = null,
        val assigned: String? = null,
        val priority: KanbanPriority? = null,
        val rawPriority: String? = null,
        val icon: String? = null,
        val cssClass: String? = null,
    )

    private fun parseNodeLine(line: String, autoIndex: Int): ParsedKanbanNode {
        var working = line.trim()
        var inlineIcon: String? = null
        var inlineClass: String? = null
        val metaBlocks = mutableListOf<String>()

        // Extract trailing `@{ ... }`, `::icon(...)`, or `:::class` in any order outside quotes/brackets.
        while (true) {
            val trimmed = working.trimEnd()
            val metaStart = findMetadataStartOutsideQuotesAndShapes(trimmed)
            if (metaStart >= 0 && trimmed.endsWith("}")) {
                metaBlocks += trimmed.substring(metaStart + 2, trimmed.length - 1).trim()
                working = trimmed.substring(0, metaStart).trimEnd()
                continue
            }
            val iconMatch = Regex("""^(.*?)\s*::icon\(([^)]+)\)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(trimmed)
            if (iconMatch != null) {
                inlineIcon = iconMatch.groupValues[2].trim()
                working = iconMatch.groupValues[1].trimEnd()
                continue
            }
            val classMatch = Regex("""^(.*?)\s*:::(\S+)$""", setOf(RegexOption.DOT_MATCHES_ALL)).find(trimmed)
            if (classMatch != null && classMatch.groupValues[1].isNotBlank()) {
                inlineClass = classMatch.groupValues[2].trim()
                working = classMatch.groupValues[1].trimEnd()
                continue
            }
            break
        }

        val (rawId, rawLabel, usedAutoId) = extractIdAndLabel(working.trim(), autoIndex)
        var finalLabel = normalizeText(rawLabel)
        var ticket: String? = null
        var assigned: String? = null
        var priority: KanbanPriority? = null
        var rawPriority: String? = null
        var icon: String? = inlineIcon

        for (metaBlock in metaBlocks.asReversed()) {
            val metaMap = parseMetadataEntries(metaBlock)
            val shapeVal = metaMap["shape"]
            if (shapeVal != null) {
                if (shapeVal != shapeVal.lowercase() || shapeVal.contains('_')) {
                    throw IllegalArgumentException("Invalid kanban shape '$shapeVal': shape names must be lowercase")
                }
            }
            metaMap["label"]?.takeIf { it.isNotEmpty() }?.let { finalLabel = normalizeText(it) }
            metaMap["ticket"]?.takeIf { it.isNotEmpty() }?.let { ticket = it }
            metaMap["assigned"]?.takeIf { it.isNotEmpty() }?.let { assigned = it }
            metaMap["icon"]?.takeIf { it.isNotEmpty() }?.let { icon = it }
            metaMap["priority"]?.takeIf { it.isNotEmpty() }?.let { pRaw ->
                rawPriority = pRaw
                priority = parsePriority(pRaw)
            }
        }

        if (finalLabel.isEmpty()) {
            throw IllegalArgumentException("Empty kanban node label in: $line")
        }

        return ParsedKanbanNode(
            id = rawId,
            label = finalLabel,
            usedAutoId = usedAutoId,
            ticket = ticket,
            assigned = assigned,
            priority = priority,
            rawPriority = rawPriority,
            icon = icon,
            cssClass = inlineClass,
        )
    }

    private val DELIM_PAIRS = listOf(
        "((" to "))",
        "))" to "((",
        "(-" to "-)",
        "{{" to "}}",
        "[" to "]",
        "(" to ")",
        ")" to "(",
    )

    private fun extractIdAndLabel(nodeSpec: String, autoIndex: Int): Triple<String, String, Boolean> {
        if (nodeSpec.isEmpty()) {
            throw IllegalArgumentException("Empty kanban node")
        }
        // Find the earliest shape opener outside quotes.
        var openIdx = -1
        var matchedPair: Pair<String, String>? = null
        var inQuotes = false
        var i = 0
        while (i < nodeSpec.length) {
            val c = nodeSpec[i]
            if (c == '\\' && inQuotes && i + 1 < nodeSpec.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes) {
                for (pair in DELIM_PAIRS) {
                    if (nodeSpec.startsWith(pair.first, i)) {
                        openIdx = i
                        matchedPair = pair
                        break
                    }
                }
                if (openIdx >= 0) break
            }
            i++
        }

        if (openIdx < 0 || matchedPair == null) {
            // Bare node without delimiters: both id and label are nodeSpec.
            val clean = normalizeText(nodeSpec)
            return Triple(clean.ifEmpty { "kbn$autoIndex" }, clean, clean.isEmpty())
        }

        val (openStr, closeStr) = matchedPair
        if (!nodeSpec.endsWith(closeStr) || nodeSpec.length < openIdx + openStr.length + closeStr.length) {
            throw IllegalArgumentException("Unclosed kanban node delimiter '$openStr' in: $nodeSpec")
        }
        val prefix = nodeSpec.substring(0, openIdx).trim()
        val inner = nodeSpec.substring(openIdx + openStr.length, nodeSpec.length - closeStr.length).trim()
        if (inner.isEmpty()) {
            throw IllegalArgumentException("Empty kanban node description in: $nodeSpec")
        }
        return if (prefix.isEmpty()) {
            val normalizedInner = normalizeText(inner)
            Triple("kbn$autoIndex", normalizedInner, true)
        } else {
            Triple(normalizeText(prefix), inner, false)
        }
    }

    private fun parseMetadataEntries(block: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val entries = splitMetadataEntries(block)
        for (rawEntry in entries) {
            val trimmed = rawEntry.trim()
            if (trimmed.isEmpty()) continue
            val colon = findCharOutsideQuotes(trimmed, ':')
            if (colon <= 0) {
                throw IllegalArgumentException("Invalid kanban metadata entry '$trimmed' in @{$block}")
            }
            val key = unquote(trimmed.substring(0, colon)).trim().lowercase()
            val value = unquote(trimmed.substring(colon + 1)).trim()
            if (key.isEmpty() || value.isEmpty()) {
                throw IllegalArgumentException("Empty key or value in kanban metadata '@{$block}'")
            }
            map[key] = value
        }
        return map
    }

    private fun splitMetadataEntries(block: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoteChar: Char? = null
        var i = 0
        while (i < block.length) {
            val c = block[i]
            if (c == '\\' && quoteChar != null && i + 1 < block.length) {
                sb.append(block[i]).append(block[i + 1])
                i += 2
                continue
            }
            if (quoteChar != null) {
                if (c == quoteChar) quoteChar = null
                sb.append(c)
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quoteChar = c
                sb.append(c)
                i++
                continue
            }
            if (c == ',' || c == '\n') {
                out += sb.toString()
                sb.setLength(0)
                i++
                continue
            }
            sb.append(c)
            i++
        }
        if (quoteChar != null) {
            throw IllegalArgumentException("Unclosed quote in kanban metadata: @{$block}")
        }
        out += sb.toString()
        return out
    }

    private fun parsePriority(raw: String): KanbanPriority? {
        return when (raw.trim().lowercase().replace(Regex("""[\s_-]+"""), " ")) {
            "very high" -> KanbanPriority.VeryHigh
            "high" -> KanbanPriority.High
            "medium" -> KanbanPriority.Medium
            "low" -> KanbanPriority.Low
            "very low" -> KanbanPriority.VeryLow
            else -> null
        }
    }

    private fun findMetadataStartOutsideQuotesAndShapes(s: String): Int {
        var quoteChar: Char? = null
        var activeCloseDelim: String? = null
        var metaBraceDepth = 0
        var i = 0
        var lastAtBrace = -1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && quoteChar != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (quoteChar != null) {
                if (c == quoteChar) quoteChar = null
                i++
                continue
            }
            if (c == '"' || (metaBraceDepth > 0 && c == '\'')) {
                quoteChar = c
                i++
                continue
            }
            if (metaBraceDepth > 0) {
                if (c == '{') metaBraceDepth++
                else if (c == '}') metaBraceDepth--
                i++
                continue
            }
            if (activeCloseDelim != null) {
                if (s.startsWith(activeCloseDelim, i)) {
                    i += activeCloseDelim.length
                    activeCloseDelim = null
                    continue
                }
                i++
                continue
            }
            if (c == '@' && i + 1 < s.length && s[i + 1] == '{') {
                lastAtBrace = i
                metaBraceDepth = 1
                i += 2
                continue
            }
            var opened = false
            for (pair in DELIM_PAIRS) {
                if (s.startsWith(pair.first, i)) {
                    activeCloseDelim = pair.second
                    i += pair.first.length
                    opened = true
                    break
                }
            }
            if (!opened) i++
        }
        return lastAtBrace
    }

    private fun mergeMultilineKanbanLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoteChar: Char? = null
        var squareDepth = 0
        var metaBraceDepth = 0
        var inAccBlock = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (quoteChar == null && squareDepth == 0 && metaBraceDepth == 0) {
                if (inAccBlock) {
                    result += rawLine
                    if (trimmed.contains('}')) inAccBlock = false
                    continue
                }
                if (Regex("""^accDescr\s*\{""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                    result += rawLine
                    if (!trimmed.contains('}')) inAccBlock = true
                    continue
                }
            }

            val clean = if (quoteChar == null) stripLineComment(rawLine, startInMeta = metaBraceDepth > 0) else rawLine
            if (quoteChar == null && squareDepth == 0 && metaBraceDepth == 0) {
                if (clean.trim().isEmpty()) continue
                current.setLength(0)
                current.append(clean)
            } else if (metaBraceDepth > 0 && quoteChar == null) {
                current.append('\n').append(clean.trim())
            } else {
                current.append(' ').append(clean.trim())
            }

            var i = 0
            while (i < clean.length) {
                val c = clean[i]
                if (c == '\\' && quoteChar != null && i + 1 < clean.length) {
                    i += 2
                    continue
                }
                if (quoteChar != null) {
                    if (c == quoteChar) quoteChar = null
                    i++
                    continue
                }
                if (c == '"' || (metaBraceDepth > 0 && c == '\'')) {
                    quoteChar = c
                    i++
                    continue
                }
                if (metaBraceDepth == 0 && c == '@' && i + 1 < clean.length && clean[i + 1] == '{') {
                    metaBraceDepth = 1
                    i += 2
                    continue
                }
                if (metaBraceDepth > 0) {
                    if (c == '{') metaBraceDepth++
                    else if (c == '}') metaBraceDepth--
                } else {
                    if (c == '[') squareDepth++
                    else if (c == ']') squareDepth--
                }
                if (squareDepth < 0 || metaBraceDepth < 0) {
                    throw IllegalArgumentException("Unmatched closing bracket in kanban: $rawLine")
                }
                i++
            }

            if (quoteChar == null && squareDepth == 0 && metaBraceDepth == 0) {
                result += current.toString()
                current.setLength(0)
            }
        }

        if (quoteChar != null || squareDepth > 0 || metaBraceDepth > 0) {
            throw IllegalArgumentException("Unclosed string, bracket, or metadata block in kanban")
        }
        return result
    }

    private fun measureIndent(line: String): Int {
        var count = 0
        for (c in line) {
            when (c) {
                ' ' -> count += 1
                '\t' -> count += 4
                else -> break
            }
        }
        return count
    }

    private fun findCharOutsideQuotes(s: String, target: Char): Int {
        var quoteChar: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && quoteChar != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (quoteChar != null) {
                if (c == quoteChar) quoteChar = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quoteChar = c
                i++
                continue
            }
            if (c == target) return i
            i++
        }
        return -1
    }

    private fun stripLineComment(line: String, startInMeta: Boolean = false): String {
        var quoteChar: Char? = null
        var inMeta = startInMeta
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && quoteChar != null && i + 1 < line.length) {
                i += 2
                continue
            }
            if (quoteChar != null) {
                if (c == quoteChar) quoteChar = null
                i++
                continue
            }
            if (c == '@' && i + 1 < line.length && line[i + 1] == '{') {
                inMeta = true
                i += 2
                continue
            }
            if (inMeta && c == '}') {
                inMeta = false
                i++
                continue
            }
            if (c == '"' || (inMeta && c == '\'')) {
                quoteChar = c
                i++
                continue
            }
            if (i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .trim()
        }
        return t
    }

    private fun normalizeText(s: String): String {
        val u = unquote(s)
        val mdStripped = Regex("""^`(.*)`$""", RegexOption.DOT_MATCHES_ALL)
            .find(u)?.groupValues?.get(1) ?: u
        val brReplaced = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(mdStripped, " ")
        return brReplaced.trim()
    }
}
