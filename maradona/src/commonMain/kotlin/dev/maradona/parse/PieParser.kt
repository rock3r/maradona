package dev.maradona.parse

import dev.maradona.model.PieDiagram
import dev.maradona.model.PieSlice

/**
 * Hand-written parser for Mermaid `pie` diagrams.
 *
 * Supports:
 * - `pie`, `pie showData`, `pie title <text>`, `pie showData title <text>`
 * - YAML frontmatter (`title`, `textPosition`, `donutHole`, `highlightSlice`, `pieOuterStrokeWidth`)
 *   and `%%{init}%%` directives (single- or double-quoted)
 * - `title`, `showData`, `accTitle`, `accDescr` (single-line and multiline `{ ... }`)
 * - Quoted (`"Dogs" : 386`, `'Ratio 1:2' : 12.5`) and unquoted slice entries with positive numeric validation
 */
object PieParser {

    fun parse(source: String): PieDiagram {
        var frontmatterTitle: String? = null
        var textPosition = 0.75f
        var donutHole = 0f
        var highlightSlice: String? = null
        var outerStrokeWidth = 2f

        val initMatch = Regex("""%%\{[\s\S]*?\}%%""").find(source)
        if (initMatch != null) {
            val initBody = initMatch.value
            Regex("""['"]?textPosition['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    textPosition = it.coerceIn(0f, 1f)
                }
            Regex("""['"]?donutHole['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    donutHole = it.coerceIn(0f, 0.85f)
                }
            Regex("""['"]?highlightSlice['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    highlightSlice = it
                }
            Regex("""['"]?pieOuterStrokeWidth['"]?\s*:\s*['"]?([0-9.]+)(?:px)?['"]?""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it > 0f) outerStrokeWidth = it
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
                        "title" -> if (value.isNotEmpty()) frontmatterTitle = value
                        "textposition" -> value.toFloatOrNull()?.let { textPosition = it.coerceIn(0f, 1f) }
                        "donuthole" -> value.toFloatOrNull()?.let { donutHole = it.coerceIn(0f, 0.85f) }
                        "highlightslice" -> if (value.isNotEmpty()) highlightSlice = value
                        "pieouterstrokewidth" -> {
                            value.removeSuffix("px").trim().toFloatOrNull()?.let {
                                if (it > 0f) outerStrokeWidth = it
                            }
                        }
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
        var showData = false
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()
        val slices = mutableListOf<PieSlice>()
        val seenLabels = HashSet<String>()

        for (rawLine in bodyLines) {
            val clean = stripLineComment(rawLine).trim()
            if (inAccDescrBlock) {
                if (clean == "}") {
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
                if (lower == "pie" || lower.startsWith("pie ") || lower.startsWith("pie\t")) {
                    headerSeen = true
                    var rest = clean.substring(3).trim()
                    if (rest.regionMatches(0, "showdata", 0, 8, ignoreCase = true) &&
                        (rest.length == 8 || rest[8].isWhitespace())
                    ) {
                        showData = true
                        rest = rest.substring(8).trim()
                    }
                    if (rest.regionMatches(0, "title", 0, 5, ignoreCase = true) &&
                        (rest.length == 5 || rest[5].isWhitespace() || rest[5] == ':')
                    ) {
                        val afterTitle = rest.substring(5).trim().removePrefix(":").trim()
                        if (afterTitle.isNotEmpty()) {
                            chartTitle = unquote(afterTitle)
                        }
                    }
                    continue
                }
                headerSeen = true
            }

            when {
                lower == "showdata" -> {
                    showData = true
                    continue
                }
                lower.startsWith("title ") || lower.startsWith("title:") -> {
                    val afterTitle = clean.substring(5).trim().removePrefix(":").trim()
                    if (afterTitle.isNotEmpty()) {
                        chartTitle = unquote(afterTitle)
                    }
                    continue
                }
                lower.startsWith("acctitle:") -> {
                    accTitle = unquote(clean.substring("acctitle:".length).trim())
                    continue
                }
                lower.startsWith("accdescr:") -> {
                    accDescr = unquote(clean.substring("accdescr:".length).trim())
                    continue
                }
                lower.startsWith("accdescr") && clean.endsWith("{") -> {
                    inAccDescrBlock = true
                    accDescrLines.clear()
                    continue
                }
            }

            val parsedSlice = parseSliceLine(clean, slices.size)
            if (seenLabels.add(parsedSlice.label)) {
                slices += parsedSlice
            }
        }

        return PieDiagram(
            slices = slices,
            title = chartTitle ?: frontmatterTitle,
            showData = showData,
            textPosition = textPosition,
            donutHole = donutHole,
            highlightSlice = highlightSlice,
            outerStrokeWidth = outerStrokeWidth,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun parseSliceLine(line: String, index: Int): PieSlice {
        val firstChar = line.first()
        val label: String
        val valueText: String
        if (firstChar == '"' || firstChar == '\'') {
            var i = 1
            val sb = StringBuilder()
            var closed = false
            while (i < line.length) {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    sb.append(line[i + 1])
                    i += 2
                    continue
                }
                if (c == firstChar) {
                    closed = true
                    i++
                    break
                }
                sb.append(c)
                i++
            }
            if (!closed) {
                throw IllegalArgumentException("Unclosed quoted label in pie slice: $line")
            }
            val rest = line.substring(i).trim()
            if (!rest.startsWith(":")) {
                throw IllegalArgumentException("Expected ':' after pie slice label: $line")
            }
            label = sb.toString()
            valueText = rest.substring(1).trim()
        } else {
            val colonIdx = line.lastIndexOf(':')
            if (colonIdx <= 0) {
                throw IllegalArgumentException("Invalid pie slice syntax (expected '\"label\" : value'): $line")
            }
            label = unquote(line.substring(0, colonIdx).trim())
            valueText = line.substring(colonIdx + 1).trim()
        }

        val value = valueText.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid numeric value '$valueText' in pie slice: $line")
        if (!value.isFinite() || value < 0.0) {
            throw IllegalArgumentException("Pie slice value must be non-negative, got $value in: $line")
        }

        return PieSlice(
            id = "slice_$index",
            label = label,
            value = value,
        )
    }

    private fun stripLineComment(line: String): String {
        var inQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuote != null && i + 1 < line.length) {
                i += 2
                continue
            }
            if (inQuote == null && (c == '"' || c == '\'')) {
                inQuote = c
                i++
                continue
            }
            if (inQuote != null && c == inQuote) {
                inQuote = null
                i++
                continue
            }
            if (inQuote == null && i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

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
