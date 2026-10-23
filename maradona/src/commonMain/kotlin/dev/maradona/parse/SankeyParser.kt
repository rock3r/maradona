package dev.maradona.parse

import dev.maradona.model.SankeyDiagram
import dev.maradona.model.SankeyLabelStyle
import dev.maradona.model.SankeyLink
import dev.maradona.model.SankeyNode
import dev.maradona.model.SankeyNodeAlignment

/**
 * Hand-written parser for Mermaid `sankey` and `sankey-beta` diagrams.
 *
 * Supports:
 * - `sankey` and `sankey-beta` headers
 * - YAML frontmatter and `%%{init}%%` directives (`title`, `width`, `height`, `nodeWidth`,
 *   `nodePadding`, `nodeAlignment`, `linkColor`, `showValues`, `prefix`, `suffix`,
 *   `labelStyle`, `nodeColors`)
 * - `title`, `accTitle`, `accDescr` (single-line and multiline `{ ... }`)
 * - RFC-4180 CSV flow records `source,target,value` with quoted fields, escaped `""` quotes,
 *   and `%%` comments
 */
object SankeyParser {

    fun parse(source: String): SankeyDiagram {
        var frontmatterTitle: String? = null
        var chartWidth = 640f
        var chartHeight = 400f
        var nodeWidth = 12f
        var nodePadding = 16f
        var nodeAlignment = SankeyNodeAlignment.Justify
        var linkColor = "gradient"
        var showValues = true
        var prefix = ""
        var suffix = ""
        var labelStyle = SankeyLabelStyle.Legacy
        val nodeColors = LinkedHashMap<String, String>()

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
            Regex("""['"]?(?:chartWidth|width)['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it >= 200f) chartWidth = it
                }
            Regex("""['"]?(?:chartHeight|height)['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it >= 160f) chartHeight = it
                }
            Regex("""['"]?nodeWidth['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it > 0f) nodeWidth = it
                }
            Regex("""['"]?nodePadding['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it >= 0f) nodePadding = it
                }
            Regex("""['"]?showValues['"]?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.lowercase()?.let { showValues = it == "true" }
            Regex("""['"]?nodeAlignment['"]?\s*:\s*['"]?(justify|left|right|center)['"]?""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.let { nodeAlignment = parseAlignment(it) }
            Regex("""['"]?labelStyle['"]?\s*:\s*['"]?(legacy|outlined)['"]?""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.let { labelStyle = parseLabelStyle(it) }
            Regex("""['"]?linkColor['"]?\s*:\s*(?:"([^"]*)"|'([^']*)'|([A-Za-z0-9#]+))""", RegexOption.IGNORE_CASE)
                .find(initBody)?.let { m ->
                    val v = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }.trim()
                    if (v.isNotEmpty()) linkColor = v
                }
            Regex("""['"]?prefix['"]?\s*:\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
                .find(initBody)?.let { m ->
                    prefix = m.groupValues[1].ifEmpty { m.groupValues[2] }
                }
            Regex("""['"]?suffix['"]?\s*:\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
                .find(initBody)?.let { m ->
                    suffix = m.groupValues[1].ifEmpty { m.groupValues[2] }
                }
            Regex("""['"]?nodeColors['"]?\s*:\s*\{([^}]*)\}""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(initBody)?.groupValues?.get(1)?.let { ncBody ->
                    val entryRegex = Regex("""(?:"([^"]+)"|'([^']+)'|([A-Za-z0-9_ -]+))\s*:\s*(?:"([^"]*)"|'([^']*)')""")
                    for (m in entryRegex.findAll(ncBody)) {
                        val k = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }.trim()
                        val v = m.groupValues[4].ifEmpty { m.groupValues[5] }.trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            nodeColors[k] = v
                        }
                    }
                }
        }

        val rawLines = FlowchartParser.stripDirectives(source).lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripLineCommentOutsideQuotes(rawLines[idx]).trim().isEmpty()) {
            idx++
        }
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            var activeFmQuote: Char? = null
            var inNodeColorsBlock = false
            var nodeColorsIndent = -1
            while (idx < rawLines.size) {
                val rawFm = rawLines[idx]
                idx++
                val trimmedFm = rawFm.trim()
                if (activeFmQuote == null && (trimmedFm == "---" || trimmedFm == "...")) break
                val cleanFm = GanttParser.stripYamlCommentWithQuoteState(rawFm, activeFmQuote)
                activeFmQuote = cleanFm.second
                val fmLine = cleanFm.first.trim()
                if (fmLine.isEmpty()) continue
                val lineIndent = rawFm.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                if (inNodeColorsBlock) {
                    if (lineIndent > nodeColorsIndent) {
                        val colon = fmLine.indexOf(':')
                        if (colon > 0) {
                            val nodeKey = unquote(fmLine.substring(0, colon).trim())
                            val colorVal = unquote(fmLine.substring(colon + 1).trim())
                            if (nodeKey.isNotEmpty() && colorVal.isNotEmpty()) {
                                nodeColors[nodeKey] = colorVal
                            }
                        }
                        continue
                    } else {
                        inNodeColorsBlock = false
                    }
                }
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    val rawVal = fmLine.substring(colon + 1).trim()
                    val value = unquote(rawVal)
                    when (key) {
                        "title" -> if (value.isNotEmpty()) frontmatterTitle = normalizeText(value)
                        "width", "chartwidth" -> value.toFloatOrNull()?.let { if (it.isFinite() && it >= 200f) chartWidth = it }
                        "height", "chartheight" -> value.toFloatOrNull()?.let { if (it.isFinite() && it >= 160f) chartHeight = it }
                        "nodewidth" -> value.toFloatOrNull()?.let { if (it.isFinite() && it > 0f) nodeWidth = it }
                        "nodepadding" -> value.toFloatOrNull()?.let { if (it.isFinite() && it >= 0f) nodePadding = it }
                        "nodealignment" -> if (value.isNotEmpty()) nodeAlignment = parseAlignment(value)
                        "linkcolor" -> if (value.isNotEmpty()) linkColor = value
                        "showvalues" -> value.lowercase().toBooleanStrictOrNull()?.let { showValues = it }
                        "prefix" -> prefix = unquoteExact(rawVal)
                        "suffix" -> suffix = unquoteExact(rawVal)
                        "labelstyle" -> if (value.isNotEmpty()) labelStyle = parseLabelStyle(value)
                        "nodecolors" -> if (rawVal.isEmpty()) {
                            inNodeColorsBlock = true
                            nodeColorsIndent = lineIndent
                        }
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        val mergedRecords = mergeCsvLines(bodyLines)

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val nodeMap = LinkedHashMap<String, SankeyNode>()
        val links = mutableListOf<SankeyLink>()

        fun getOrCreateNode(id: String) {
            if (!nodeMap.containsKey(id)) {
                nodeMap[id] = SankeyNode(id = id, color = nodeColors[id])
            }
        }

        for (rawLine in mergedRecords) {
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

            val cleanForCheck = stripLineCommentOutsideQuotes(rawLine).trim()
            val accBlockMatch = Regex("""^accDescr\s*\{(.*)$""", RegexOption.IGNORE_CASE).find(rawTrimmed)
            if (accBlockMatch != null && !isThreeFieldNumericCsvRecord(cleanForCheck)) {
                val afterOpen = accBlockMatch.groupValues[1]
                val closeIdx = afterOpen.indexOf('}')
                if (closeIdx >= 0) {
                    val afterClose = stripLineCommentOutsideQuotes(afterOpen.substring(closeIdx + 1)).trim()
                    if (afterClose.isEmpty()) {
                        headerSeen = true
                        accDescr = normalizeText(afterOpen.substring(0, closeIdx).trim())
                        continue
                    }
                } else {
                    headerSeen = true
                    inAccDescrBlock = true
                    accDescrLines.clear()
                    val firstLine = afterOpen.trim()
                    if (firstLine.isNotEmpty()) accDescrLines += firstLine
                    continue
                }
            }

            val clean = stripLineCommentOutsideQuotes(rawLine).trim()
            if (clean.isEmpty()) continue

            if (!headerSeen) {
                val headerMatch = Regex("""^sankey(?:-beta)?$""", RegexOption.IGNORE_CASE).find(clean)
                if (headerMatch != null) {
                    headerSeen = true
                    continue
                }
                if (clean.lowercase().startsWith("sankey")) {
                    throw IllegalArgumentException("Unexpected tokens on sankey header line: $clean")
                }
                headerSeen = true
            }

            val accTitleMatch = Regex("""^accTitle\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(clean)
            if (accTitleMatch != null && !isThreeFieldNumericCsvRecord(clean)) {
                accTitle = normalizeText(accTitleMatch.groupValues[1].trim())
                continue
            }
            val accDescrMatch = Regex("""^accDescr\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(clean)
            if (accDescrMatch != null && !isThreeFieldNumericCsvRecord(clean)) {
                accDescr = normalizeText(accDescrMatch.groupValues[1].trim())
                continue
            }
            val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(clean)
            if (titleMatch != null && !isThreeFieldNumericCsvRecord(clean)) {
                val after = titleMatch.groupValues[1].trim()
                if (after.isNotEmpty()) chartTitle = normalizeText(after)
                continue
            }

            val fields = parseCsvRecord(clean)
            if (fields.size != 3) {
                throw IllegalArgumentException(
                    "Expected 3 CSV fields (source,target,value) in sankey record, got ${fields.size}: $clean",
                )
            }
            val srcName = fields[0].trim()
            val dstName = fields[1].trim()
            val valStr = fields[2].trim()
            if (srcName.isEmpty() || dstName.isEmpty()) {
                throw IllegalArgumentException("Sankey source and target cannot be empty: $clean")
            }
            val value = valStr.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid sankey flow value '$valStr' in: $clean")
            if (!value.isFinite() || value <= 0.0) {
                throw IllegalArgumentException("Sankey flow value must be finite and positive: $valStr in $clean")
            }

            getOrCreateNode(srcName)
            getOrCreateNode(dstName)
            links += SankeyLink(
                source = srcName,
                target = dstName,
                value = value,
            )
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in sankey")
        }

        validateAcyclic(nodeMap.keys, links)

        return SankeyDiagram(
            nodes = nodeMap.values.toList(),
            links = links,
            chartWidth = chartWidth,
            chartHeight = chartHeight,
            nodeWidth = nodeWidth,
            nodePadding = nodePadding,
            nodeAlignment = nodeAlignment,
            linkColor = linkColor,
            showValues = showValues,
            prefix = prefix,
            suffix = suffix,
            labelStyle = labelStyle,
            nodeColors = nodeColors,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun parseCsvRecord(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        val n = line.length
        while (i < n) {
            while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
            if (i < n && line[i] == '"') {
                // Quoted RFC-4180 field (`""` escapes `"`).
                i++
                sb.setLength(0)
                var closed = false
                while (i < n) {
                    val c = line[i]
                    if (c == '"') {
                        if (i + 1 < n && line[i + 1] == '"') {
                            sb.append('"')
                            i += 2
                        } else {
                            closed = true
                            i++
                            break
                        }
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                if (!closed) {
                    throw IllegalArgumentException("Unclosed double quote in sankey CSV record: $line")
                }
                while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
                if (i < n && line[i] != ',') {
                    throw IllegalArgumentException("Unexpected characters after quoted CSV field in sankey: $line")
                }
                fields += sb.toString()
                if (i < n && line[i] == ',') {
                    i++
                    if (i == n) fields += ""
                }
            } else {
                val start = i
                while (i < n && line[i] != ',') {
                    if (line[i] == '"') {
                        throw IllegalArgumentException("Unexpected quote inside unquoted sankey CSV field: $line")
                    }
                    i++
                }
                fields += line.substring(start, i)
                if (i < n && line[i] == ',') {
                    i++
                    if (i == n) fields += ""
                }
            }
        }
        return fields
    }

    private fun isThreeFieldNumericCsvRecord(line: String): Boolean {
        val fields = runCatching { parseCsvRecord(line) }.getOrNull() ?: return false
        if (fields.size != 3) return false
        val v = fields[2].trim().toDoubleOrNull() ?: return false
        return v.isFinite() && v > 0.0 && fields[0].trim().isNotEmpty() && fields[1].trim().isNotEmpty()
    }

    private fun validateAcyclic(nodes: Set<String>, links: List<SankeyLink>) {
        if (links.isEmpty()) return
        val adj = HashMap<String, MutableList<String>>()
        val inDegree = HashMap<String, Int>()
        for (n in nodes) {
            adj[n] = mutableListOf()
            inDegree[n] = 0
        }
        for (l in links) {
            if (l.source == l.target) {
                throw IllegalArgumentException("Self-link cycle '${l.source} -> ${l.target}' is not allowed in sankey")
            }
            adj.getOrPut(l.source) { mutableListOf() }.add(l.target)
            inDegree[l.target] = (inDegree[l.target] ?: 0) + 1
        }
        val queue = ArrayDeque<String>()
        for ((n, deg) in inDegree) {
            if (deg == 0) queue.addLast(n)
        }
        var visited = 0
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            visited++
            for (v in adj[u].orEmpty()) {
                val nextDeg = (inDegree[v] ?: 1) - 1
                inDegree[v] = nextDeg
                if (nextDeg == 0) queue.addLast(v)
            }
        }
        if (visited < inDegree.size) {
            throw IllegalArgumentException("Cycle detected in sankey diagram links")
        }
    }

    private fun mergeCsvLines(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var inAccBlock = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (!inQuotes) {
                if (inAccBlock) {
                    out += rawLine
                    if (trimmed.contains('}')) inAccBlock = false
                    continue
                }
                if (
                    Regex("""^accDescr\s*\{""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) &&
                    !isThreeFieldNumericCsvRecord(stripLineCommentOutsideQuotes(rawLine).trim())
                ) {
                    out += rawLine
                    if (!trimmed.contains('}')) inAccBlock = true
                    continue
                }
            }

            val clean = stripLineCommentOutsideQuotes(rawLine, startInQuotes = inQuotes)
            if (!inQuotes) {
                if (clean.trim().isEmpty()) continue
                current.setLength(0)
                current.append(clean)
            } else {
                current.append('\n').append(clean)
            }

            var i = 0
            while (i < clean.length) {
                if (clean[i] == '"') {
                    if (inQuotes && i + 1 < clean.length && clean[i + 1] == '"') {
                        i += 2
                        continue
                    }
                    inQuotes = !inQuotes
                }
                i++
            }
            if (!inQuotes) {
                out += current.toString()
                current.setLength(0)
            }
        }
        if (inQuotes) {
            throw IllegalArgumentException("Unclosed double quote in sankey CSV")
        }
        return out
    }

    private fun stripLineCommentOutsideQuotes(line: String, startInQuotes: Boolean = false): String {
        var inQuotes = startInQuotes
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    i += 2
                    continue
                }
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && i + 1 < line.length && c == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun parseAlignment(raw: String): SankeyNodeAlignment =
        when (raw.trim().lowercase()) {
            "left" -> SankeyNodeAlignment.Left
            "right" -> SankeyNodeAlignment.Right
            "center" -> SankeyNodeAlignment.Center
            else -> SankeyNodeAlignment.Justify
        }

    private fun parseLabelStyle(raw: String): SankeyLabelStyle =
        when (raw.trim().lowercase()) {
            "outlined" -> SankeyLabelStyle.Outlined
            else -> SankeyLabelStyle.Legacy
        }

    private fun unquoteExact(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
        }
        return t
    }

    private fun unquote(s: String): String = unquoteExact(s).trim()

    private fun normalizeText(s: String): String {
        val u = unquote(s)
        val brReplaced = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(u, " ")
        return brReplaced.trim()
    }
}
