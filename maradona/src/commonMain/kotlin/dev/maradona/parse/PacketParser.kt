package dev.maradona.parse

import dev.maradona.model.PacketBitOrder
import dev.maradona.model.PacketBlock
import dev.maradona.model.PacketConfig
import dev.maradona.model.PacketDiagram
import dev.maradona.model.PacketField

/**
 * Hand-written parser for Mermaid `packet` / `packet-beta` diagrams.
 *
 * Supports:
 * - `packet` and `packet-beta` headers
 * - YAML frontmatter and `%%{init}%%` configuration (`title`, `bitsPerRow`, `showBits`,
 *   `bitOrder`, `rowHeight`, `bitWidth`, `paddingX`, `paddingY`)
 * - `title`, `accTitle`, `accDescr` (single-line and multi-line `{ ... }`)
 * - Bit-range fields (`<start>-<end>: "<label>"`), single-bit fields (`<bit>: "<label>"`),
 *   and relative bit-count fields (`+<bits>: "<label>"`)
 * - Automatic word-row splitting across `bitsPerRow` boundaries and contiguity validation
 */
object PacketParser {

    private const val MAX_PACKET_ROWS = 10_000L

    private class MutableConfig(
        var bitsPerRow: Int = 32,
        var showBits: Boolean = true,
        var bitOrder: PacketBitOrder = PacketBitOrder.Ascending,
        var rowHeight: Float = 32f,
        var bitWidth: Float = 32f,
        var paddingX: Float = 5f,
        var paddingY: Float = 5f,
    ) {
        fun toImmutable(): PacketConfig = PacketConfig(
            bitsPerRow = bitsPerRow,
            showBits = showBits,
            bitOrder = bitOrder,
            rowHeight = rowHeight,
            bitWidth = bitWidth,
            paddingX = paddingX,
            paddingY = paddingY,
        )
    }

    fun parse(source: String): PacketDiagram {
        var frontmatterTitle: String? = null
        val config = MutableConfig()

        val rawLines = FlowchartParser.stripDirectives(source).lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripLineComment(rawLines[idx]).trim().isEmpty()) {
            idx++
        }
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            var activeFmQuote: Char? = null
            val sectionStack = mutableListOf<Pair<Int, String>>()
            while (idx < rawLines.size) {
                val rawFm = rawLines[idx]
                idx++
                val trimmedFm = rawFm.trim()
                if (activeFmQuote == null && (trimmedFm == "---" || trimmedFm == "...")) break
                val cleanFm = GanttParser.stripYamlCommentWithQuoteState(rawFm, activeFmQuote)
                activeFmQuote = cleanFm.second
                val fmLine = cleanFm.first.trim()
                if (fmLine.isEmpty()) continue
                val indent = cleanFm.first.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                while (sectionStack.isNotEmpty() && sectionStack.last().first >= indent) {
                    sectionStack.removeAt(sectionStack.lastIndex)
                }
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    val rawVal = fmLine.substring(colon + 1).trim()
                    if (rawVal.isEmpty()) {
                        sectionStack += indent to key
                        continue
                    }
                    val path = sectionStack.map { it.second }
                    val inPacketConfig = path.isEmpty() ||
                        path == listOf("packet") ||
                        path == listOf("config", "packet")
                    if (rawVal.startsWith("{") && rawVal.endsWith("}")) {
                        if (key == "packet" && (path.isEmpty() || path == listOf("config"))) {
                            parseJsonKeyValuePairs(rawVal.substring(1, rawVal.length - 1), config) { t ->
                                if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeText(t)
                            }
                        } else if (key == "config" && path.isEmpty()) {
                            parseInitDirective(rawVal, config) { t ->
                                if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeText(t)
                            }
                        }
                        continue
                    }
                    val value = unquote(rawVal)
                    if (path.isEmpty() && key == "title" && value.isNotEmpty()) {
                        frontmatterTitle = normalizeText(value)
                    } else if (inPacketConfig) {
                        applyConfigEntry(config, key, value) { t ->
                            if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeText(t)
                        }
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        // Apply `%%{init: ...}%%` directives that are real top-level directives (not inside comments or quotes).
        for (initDirective in extractActiveInitDirectives(source)) {
            parseInitDirective(initDirective, config) { t ->
                if (t.isNotEmpty()) frontmatterTitle = normalizeText(t)
            }
        }

        val mergedLines = mergeMultilineQuotedLines(bodyLines)

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val fields = mutableListOf<PacketField>()
        var lastBit = -1

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

            val commentFree = stripLineComment(rawLine).trim()
            if (commentFree.isEmpty()) continue

            if (!headerSeen) {
                val headerMatch = Regex(
                    """^packet(?:-beta)?(?:\s+title(?:\s+|:)\s*(.*)|\s*)?$""",
                    RegexOption.IGNORE_CASE,
                ).find(commentFree)
                if (headerMatch != null) {
                    headerSeen = true
                    val inlineTitle = headerMatch.groupValues[1].trim()
                    if (inlineTitle.isNotEmpty()) {
                        chartTitle = normalizeText(inlineTitle)
                    }
                    continue
                }
                if (commentFree.lowercase().startsWith("packet")) {
                    throw IllegalArgumentException("Unexpected tokens on packet header line: $commentFree")
                }
                headerSeen = true
            }

            val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (titleMatch != null) {
                val after = titleMatch.groupValues[1].trim()
                if (after.isNotEmpty()) chartTitle = normalizeText(after)
                continue
            }
            val accTitleMatch = Regex("""^accTitle\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (accTitleMatch != null) {
                accTitle = normalizeText(accTitleMatch.groupValues[1].trim())
                continue
            }
            val accDescrMatch = Regex("""^accDescr\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (accDescrMatch != null) {
                accDescr = normalizeText(accDescrMatch.groupValues[1].trim())
                continue
            }

            for (rawStmt in splitSemicolonOutsideQuotes(commentFree)) {
                val stmt = rawStmt.trim()
                if (stmt.isEmpty()) continue
                val field = parseFieldStatement(stmt, lastBit, config.bitsPerRow)
                fields += field
                lastBit = field.end
            }
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in packet diagram")
        }

        val immutableConfig = config.toImmutable()
        val words = splitFieldsIntoWords(fields, immutableConfig.bitsPerRow)

        return PacketDiagram(
            fields = fields,
            words = words,
            config = immutableConfig,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun parseFieldStatement(stmt: String, lastBit: Int, bitsPerRow: Int): PacketField {
        val colonIdx = findCharOutsideQuotes(stmt, ':')
        if (colonIdx <= 0) {
            throw IllegalArgumentException("Expected '<bits>: <label>' in packet diagram, got: $stmt")
        }
        val bitSpec = stmt.substring(0, colonIdx).trim()
        val rawLabel = stmt.substring(colonIdx + 1).trim()
        val label = parseFieldLabel(rawLabel, stmt)
        val maxAllowedBit = MAX_PACKET_ROWS * bitsPerRow.coerceAtLeast(1).toLong() - 1L

        if (bitSpec.startsWith("+")) {
            val countStr = bitSpec.substring(1).trim()
            val countLong = countStr.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid relative bit count '$bitSpec' in packet diagram: $stmt")
            if (countLong <= 0L) {
                throw IllegalArgumentException("Packet field bit count must be >= 1, got $countLong in: $stmt")
            }
            val startLong = lastBit.toLong() + 1L
            val endLong = startLong + countLong - 1L
            if (endLong > maxAllowedBit || endLong > Int.MAX_VALUE.toLong() || countLong > Int.MAX_VALUE.toLong()) {
                throw IllegalArgumentException(
                    "Packet diagram exceeds maximum size of $MAX_PACKET_ROWS rows ($maxAllowedBit bits) in: $stmt",
                )
            }
            return PacketField(
                start = startLong.toInt(),
                end = endLong.toInt(),
                bits = countLong.toInt(),
                label = label,
            )
        }

        val dashIdx = bitSpec.indexOf('-')
        if (dashIdx >= 0) {
            val startStr = bitSpec.substring(0, dashIdx).trim()
            val endStr = bitSpec.substring(dashIdx + 1).trim()
            val startLong = startStr.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid start bit '$startStr' in packet diagram: $stmt")
            val endLong = endStr.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid end bit '$endStr' in packet diagram: $stmt")
            if (startLong != lastBit.toLong() + 1L) {
                throw IllegalArgumentException(
                    "Packet field start bit $startLong must be contiguous with previous end bit $lastBit (expected ${lastBit + 1}) in: $stmt",
                )
            }
            if (endLong < startLong) {
                throw IllegalArgumentException(
                    "Packet field end bit $endLong must be >= start bit $startLong in: $stmt",
                )
            }
            if (endLong > maxAllowedBit || endLong > Int.MAX_VALUE.toLong()) {
                throw IllegalArgumentException(
                    "Packet diagram exceeds maximum size of $MAX_PACKET_ROWS rows ($maxAllowedBit bits) in: $stmt",
                )
            }
            val bits = (endLong - startLong + 1L).toInt()
            return PacketField(
                start = startLong.toInt(),
                end = endLong.toInt(),
                bits = bits,
                label = label,
            )
        }

        val singleBitLong = bitSpec.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid bit specification '$bitSpec' in packet diagram: $stmt")
        if (singleBitLong != lastBit.toLong() + 1L) {
            throw IllegalArgumentException(
                "Packet field bit $singleBitLong must be contiguous with previous end bit $lastBit (expected ${lastBit + 1}) in: $stmt",
            )
        }
        if (singleBitLong > maxAllowedBit || singleBitLong > Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException(
                "Packet diagram exceeds maximum size of $MAX_PACKET_ROWS rows ($maxAllowedBit bits) in: $stmt",
            )
        }
        return PacketField(
            start = singleBitLong.toInt(),
            end = singleBitLong.toInt(),
            bits = 1,
            label = label,
        )
    }

    private fun parseFieldLabel(rawLabel: String, stmt: String): String {
        if (rawLabel.isEmpty()) {
            throw IllegalArgumentException("Missing label in packet field declaration: $stmt")
        }
        val first = rawLabel.first()
        if (first == '"' || first == '\'') {
            var i = 1
            while (i < rawLabel.length) {
                val c = rawLabel[i]
                if (c == '\\' && i + 1 < rawLabel.length) {
                    i += 2
                    continue
                }
                if (c == first) {
                    val trailing = rawLabel.substring(i + 1).trim()
                    if (trailing.isNotEmpty()) {
                        throw IllegalArgumentException("Unexpected trailing tokens after quoted label in packet field: $stmt")
                    }
                    val inner = rawLabel.substring(1, i)
                        .replace("\\\"", "\"")
                        .replace("\\'", "'")
                        .replace("\\\\", "\\")
                    return Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(inner, " ").trim()
                }
                i++
            }
            throw IllegalArgumentException("Unclosed quoted label in packet field: $stmt")
        }
        return normalizeText(rawLabel)
    }

    private fun splitFieldsIntoWords(fields: List<PacketField>, bitsPerRow: Int): List<List<PacketBlock>> {
        if (fields.isEmpty()) return emptyList()
        val lastEnd = fields.last().end.toLong()
        val totalRows = lastEnd / bitsPerRow.coerceAtLeast(1).toLong() + 1L
        if (totalRows > MAX_PACKET_ROWS) {
            throw IllegalArgumentException("Packet diagram exceeds maximum size of $MAX_PACKET_ROWS rows ($totalRows rows)")
        }
        val rows = LinkedHashMap<Int, MutableList<PacketBlock>>()
        for (field in fields) {
            var curStart = field.start.toLong()
            val fieldEnd = field.end.toLong()
            while (curStart <= fieldEnd) {
                val rowIdx = (curStart / bitsPerRow).toInt()
                val rowEndBit = (rowIdx.toLong() + 1L) * bitsPerRow.toLong() - 1L
                val sliceEnd = minOf(fieldEnd, rowEndBit)
                val sliceBits = (sliceEnd - curStart + 1L).toInt()
                val rowList = rows.getOrPut(rowIdx) { mutableListOf() }
                rowList += PacketBlock(
                    start = curStart.toInt(),
                    end = sliceEnd.toInt(),
                    bits = sliceBits,
                    label = field.label,
                )
                curStart = sliceEnd + 1L
            }
        }
        return rows.entries.sortedBy { it.key }.map { it.value.toList() }
    }

    private fun extractActiveInitDirectives(source: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        var activeQuote: Char? = null
        while (i < source.length) {
            val c = source[i]
            if (c == '\\' && activeQuote != null && i + 1 < source.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(source, i)))) {
                activeQuote = c
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                i++
                continue
            }
            if (activeQuote == null) {
                if (source.startsWith("%%{", i)) {
                    val endIdx = source.indexOf("}%%", i + 3)
                    if (endIdx >= 0) {
                        results += source.substring(i, endIdx + 3)
                        i = endIdx + 3
                        continue
                    }
                }
                if (c == '#' || c == '%') {
                    val nl = source.indexOf('\n', i)
                    if (nl < 0) break
                    i = nl + 1
                    continue
                }
            }
            i++
        }
        return results
    }

    private fun parseInitDirective(
        initDirective: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        val packetBlockMatch = Regex(
            """['"]?packet['"]?\s*:\s*\{([^{}]*)\}""",
            RegexOption.IGNORE_CASE,
        ).find(initDirective)
        if (packetBlockMatch != null) {
            parseJsonKeyValuePairs(packetBlockMatch.groupValues[1], config, onTitle)
        }
        // Also parse top-level keys outside nested `{ ... }` blocks.
        val topLevel = Regex("""['"]?[A-Za-z0-9_-]+['"]?\s*:\s*\{[^{}]*\}""").replace(initDirective, "")
        parseJsonKeyValuePairs(topLevel, config, onTitle)
    }

    private fun parseJsonKeyValuePairs(
        segment: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        val pairRegex = Regex(
            """['"]?([A-Za-z0-9_]+)['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([A-Za-z0-9_.+-]+))""",
        )
        for (m in pairRegex.findAll(segment)) {
            val key = m.groupValues[1].lowercase()
            val rawVal = when {
                m.groups[2] != null -> m.groupValues[2]
                m.groups[3] != null -> m.groupValues[3]
                else -> m.groupValues[4]
            }.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\").trim()
            applyConfigEntry(config, key, rawVal, onTitle)
        }
    }

    private fun applyConfigEntry(
        config: MutableConfig,
        key: String,
        value: String,
        onTitle: (String) -> Unit,
    ) {
        when (key) {
            "title" -> onTitle(value)
            "bitsperrow" -> {
                val v = value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid packet bitsPerRow '$value'")
                if (v < 1) {
                    throw IllegalArgumentException("packet bitsPerRow must be >= 1, got $v")
                }
                config.bitsPerRow = v
            }
            "showbits" -> {
                config.showBits = when (value.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Invalid packet showBits '$value' (expected true or false)")
                }
            }
            "bitorder" -> {
                config.bitOrder = when (value.lowercase()) {
                    "ascending" -> PacketBitOrder.Ascending
                    "descending" -> PacketBitOrder.Descending
                    else -> throw IllegalArgumentException(
                        "Invalid packet bitOrder '$value' (expected ascending or descending)",
                    )
                }
            }
            "rowheight" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid packet rowHeight '$value'")
                if (!v.isFinite() || v <= 0f) {
                    throw IllegalArgumentException("packet rowHeight must be finite and > 0, got $value")
                }
                config.rowHeight = v
            }
            "bitwidth" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid packet bitWidth '$value'")
                if (!v.isFinite() || v <= 0f) {
                    throw IllegalArgumentException("packet bitWidth must be finite and > 0, got $value")
                }
                config.bitWidth = v
            }
            "paddingx" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid packet paddingX '$value'")
                if (!v.isFinite() || v < 0f) {
                    throw IllegalArgumentException("packet paddingX must be finite and >= 0, got $value")
                }
                config.paddingX = v
            }
            "paddingy" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid packet paddingY '$value'")
                if (!v.isFinite() || v < 0f) {
                    throw IllegalArgumentException("packet paddingY must be finite and >= 0, got $value")
                }
                config.paddingY = v
            }
        }
    }

    private fun mergeMultilineQuotedLines(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var activeQuote: Char? = null
        var inAccBlock = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (activeQuote == null) {
                if (inAccBlock) {
                    out += rawLine
                    if (trimmed.contains('}')) inAccBlock = false
                    continue
                }
                if (Regex("""^accDescr\s*\{""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                    out += rawLine
                    if (!trimmed.contains('}')) inAccBlock = true
                    continue
                }
            }

            val clean = stripLineCommentWithQuoteState(rawLine, startQuote = activeQuote)
            if (activeQuote == null) {
                if (clean.trim().isEmpty()) continue
                current.setLength(0)
                current.append(clean)
            } else {
                current.append(' ').append(clean.trim())
            }

            var i = 0
            while (i < clean.length) {
                val c = clean[i]
                if (c == '\\' && activeQuote != null && i + 1 < clean.length) {
                    i += 2
                    continue
                }
                if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(clean, i)))) {
                    activeQuote = c
                } else if (activeQuote == c) {
                    activeQuote = null
                }
                i++
            }
            if (activeQuote == null) {
                out += current.toString()
                current.setLength(0)
            }
        }
        if (activeQuote != null) {
            throw IllegalArgumentException("Unclosed quote in packet diagram")
        }
        return out
    }

    private fun isQuoteOpeningContext(s: String, index: Int): Boolean {
        var j = index - 1
        while (j >= 0 && (s[j] == ' ' || s[j] == '\t')) {
            j--
        }
        if (j < 0 || s[j] == '\n' || s[j] == '\r' || s[j] == ':') return true
        if (j >= 4) {
            val word = s.substring(j - 4, j + 1)
            val beforeWord = if (j >= 5) s[j - 5] else ' '
            if (word.equals("title", ignoreCase = true) && (beforeWord.isWhitespace() || beforeWord == ';')) {
                return true
            }
        }
        return false
    }

    private fun stripLineCommentWithQuoteState(line: String, startQuote: Char?): String {
        val trimmed = line.trimStart()
        if (startQuote == null) {
            if (trimmed.startsWith("#") || trimmed.startsWith("%")) return ""
        }
        var activeQuote = startQuote
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && activeQuote != null && i + 1 < line.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(line, i)))) {
                activeQuote = c
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                i++
                continue
            }
            if (activeQuote == null) {
                if (i + 1 < line.length && c == '%' && line[i + 1] == '%') {
                    return line.substring(0, i)
                }
                if (c == '#' || c == '%') {
                    return line.substring(0, i)
                }
            }
            i++
        }
        return line
    }

    private fun stripLineComment(line: String): String =
        stripLineCommentWithQuoteState(line, startQuote = null)

    private fun findCharOutsideQuotes(s: String, target: Char): Int {
        var activeQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(s, i)))) {
                activeQuote = c
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                i++
                continue
            }
            if (activeQuote == null && c == target) return i
            i++
        }
        return -1
    }

    private fun splitSemicolonOutsideQuotes(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var activeQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                sb.append(s[i]).append(s[i + 1])
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(s, i)))) {
                activeQuote = c
                sb.append(c)
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                sb.append(c)
                i++
                continue
            }
            if (activeQuote == null && c == ';') {
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
        val brReplaced = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(u, " ")
        return brReplaced.trim()
    }
}
