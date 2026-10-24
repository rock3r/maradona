package dev.maradona.parse

import dev.maradona.model.TreemapConfig
import dev.maradona.model.TreemapDiagram
import dev.maradona.model.TreemapNode
import dev.maradona.model.TreemapNodeStyle

/**
 * Hand-written parser for Mermaid `treemap` / `treemap-beta` diagrams.
 *
 * Supports:
 * - `treemap` and `treemap-beta` headers, YAML frontmatter, `%%{init}%%` configuration
 *   (`title`, `padding`, `diagramPadding`, `showValues`, `nodeWidth`, `nodeHeight`, `width`,
 *   `height`, `borderWidth`, `valueFontSize`, `labelFontSize`, `valueFormat`)
 * - `title`, `accTitle`, and `accDescr` (single-line and multi-line `{ ... }`)
 * - Indentation-based hierarchy of sections (`"Section"[:::class]`) and leaves
 *   (`"Leaf"[:::class]: <value>[:::class]` or `"Leaf", <value>`)
 * - `classDef <className> <styles>` directives
 */
object TreemapParser {

    private class MutableConfig(
        var padding: Float = 10f,
        var diagramPadding: Float = 8f,
        var showValues: Boolean = true,
        var width: Float = 760f,
        var height: Float = 440f,
        var borderWidth: Float = 1.5f,
        var valueFontSize: Float = 12f,
        var labelFontSize: Float = 14f,
        var valueFormat: String = ",",
    ) {
        fun toImmutable(): TreemapConfig = TreemapConfig(
            padding = padding,
            diagramPadding = diagramPadding,
            showValues = showValues,
            width = width,
            height = height,
            borderWidth = borderWidth,
            valueFontSize = valueFontSize,
            labelFontSize = labelFontSize,
            valueFormat = valueFormat,
        )
    }

    private data class RawRow(
        val indent: Int,
        val name: String,
        val value: Double?,
        val isExplicitLeaf: Boolean,
        val classes: List<String>,
    )

    private class MutableTreeNode(
        val indent: Int,
        val name: String,
        val value: Double?,
        val isExplicitLeaf: Boolean,
        val classes: List<String>,
        val children: MutableList<MutableTreeNode> = mutableListOf(),
    )

    fun parse(source: String): TreemapDiagram {
        var frontmatterTitle: String? = null
        val config = MutableConfig()

        val (strippedSource, initDirectives) = stripAndExtractActiveInitDirectives(source)
        val rawLines = strippedSource.lines()
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
                    var rawVal = fmLine.substring(colon + 1).trim()
                    if (rawVal.isEmpty()) {
                        sectionStack += indent to key
                        continue
                    }
                    if (rawVal.startsWith("{")) {
                        var braceBalance = countUnquotedBraceDelta(rawVal)
                        val flowBuilder = StringBuilder(rawVal)
                        while (braceBalance > 0 && idx < rawLines.size) {
                            val nextFm = rawLines[idx]
                            val nextTrimmed = nextFm.trim()
                            if (activeFmQuote == null && (nextTrimmed == "---" || nextTrimmed == "...")) break
                            idx++
                            val nextClean = GanttParser.stripYamlCommentWithQuoteState(nextFm, activeFmQuote)
                            activeFmQuote = nextClean.second
                            val nextPart = nextClean.first.trim()
                            if (nextPart.isNotEmpty()) {
                                flowBuilder.append(' ').append(nextPart)
                                braceBalance += countUnquotedBraceDelta(nextPart)
                            }
                        }
                        rawVal = flowBuilder.toString().trim()
                    }
                    val path = sectionStack.map { it.second }
                    val inTreemapConfig = path.isEmpty() ||
                        path == listOf("treemap") ||
                        path == listOf("config", "treemap")
                    if (rawVal.startsWith("{") && rawVal.endsWith("}")) {
                        if (key == "treemap" && (path.isEmpty() || path == listOf("config"))) {
                            parseInitDirective(rawVal, config) { t ->
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
                    } else if (inTreemapConfig) {
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

        for (initDirective in initDirectives) {
            parseInitDirective(initDirective, config) { t ->
                if (t.isNotEmpty()) frontmatterTitle = normalizeText(t)
            }
        }

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val classDefs = LinkedHashMap<String, TreemapNodeStyle>()
        val rawRows = mutableListOf<RawRow>()

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

            val commentFree = stripLineComment(rawLine)
            val trimmed = commentFree.trim()
            if (trimmed.isEmpty()) continue

            if (!headerSeen) {
                val headerMatch = Regex(
                    """^treemap(?:-beta)?(?:\s+title(?:\s+|:)\s*(.*)|\s*)?$""",
                    RegexOption.IGNORE_CASE,
                ).find(trimmed)
                if (headerMatch != null) {
                    headerSeen = true
                    val inlineTitle = headerMatch.groupValues[1].trim()
                    if (inlineTitle.isNotEmpty()) {
                        chartTitle = normalizeText(inlineTitle)
                    }
                    continue
                }
                throw IllegalArgumentException("Expected treemap or treemap-beta header, got: $trimmed")
            }

            val accBlockMatch = Regex("""^accDescr\s*\{(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (accBlockMatch != null) {
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

            val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (titleMatch != null && !trimmed.startsWith("\"") && !trimmed.startsWith("'")) {
                val after = titleMatch.groupValues[1].trim()
                if (after.isNotEmpty()) chartTitle = normalizeText(after)
                continue
            }
            val accTitleMatch = Regex("""^accTitle\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (accTitleMatch != null) {
                accTitle = normalizeText(accTitleMatch.groupValues[1].trim())
                continue
            }
            val accDescrMatch = Regex("""^accDescr\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (accDescrMatch != null) {
                accDescr = normalizeText(accDescrMatch.groupValues[1].trim())
                continue
            }

            val classDefMatch = Regex("""^classDef\s+([A-Za-z_][A-Za-z0-9_-]*)(?:\s+(.*))?$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (classDefMatch != null) {
                val clsName = classDefMatch.groupValues[1].trim()
                val styleSpec = classDefMatch.groupValues[2].trim().removeSuffix(";").trim()
                val st = parseNodeStyle(styleSpec)
                classDefs[clsName] = classDefs[clsName]?.merge(st) ?: st
                continue
            }

            val indent = computeIndentation(commentFree)
            rawRows += parseTreemapRow(trimmed, indent)
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in treemap diagram")
        }
        if (!headerSeen) {
            throw IllegalArgumentException("Expected treemap or treemap-beta header")
        }

        val roots = buildHierarchy(rawRows, classDefs)

        return TreemapDiagram(
            roots = roots,
            config = config.toImmutable(),
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun computeIndentation(line: String): Int {
        var indent = 0
        for (c in line) {
            when (c) {
                ' ' -> indent += 1
                '\t' -> indent += 4
                else -> break
            }
        }
        return indent
    }

    private fun parseTreemapRow(line: String, indent: Int): RawRow {
        var i = 0
        val s = line.trim()
        val name: String
        if (s.startsWith("\"") || s.startsWith("'")) {
            val quote = s[0]
            val sb = StringBuilder()
            i = 1
            var closed = false
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) {
                    closed = true
                    i++
                    break
                }
                sb.append(c)
                i++
            }
            if (!closed) {
                throw IllegalArgumentException("Unclosed quote in treemap item: $line")
            }
            name = normalizeText(sb.toString())
        } else {
            val start = 0
            while (i < s.length) {
                if (s.startsWith(":::", i) || s[i] == ':' || s[i] == ',') break
                i++
            }
            name = normalizeText(s.substring(start, i).trim())
        }

        if (name.isEmpty()) {
            throw IllegalArgumentException("Empty item name in treemap row: $line")
        }

        val classes = mutableListOf<String>()
        while (i < s.length && s[i].isWhitespace()) i++
        if (s.startsWith(":::", i)) {
            i += 3
            val clsStart = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != ':' && s[i] != ';') {
                i++
            }
            val clsRaw = s.substring(clsStart, i).trim()
            if (clsRaw.isEmpty()) {
                throw IllegalArgumentException("Missing class name after ':::' in treemap row: $line")
            }
            classes += clsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            while (i < s.length && s[i].isWhitespace()) i++
        }

        if (i >= s.length || s[i] == ';') {
            return RawRow(
                indent = indent,
                name = name,
                value = null,
                isExplicitLeaf = false,
                classes = classes,
            )
        }

        if (s[i] != ':' && s[i] != ',') {
            throw IllegalArgumentException("Expected ':' or ',' before leaf value in treemap row: $line")
        }
        i++ // consume ':' or ','
        while (i < s.length && s[i].isWhitespace()) i++

        // Read numeric value (may be followed by `:::className`)
        var rest = s.substring(i).trim().removeSuffix(";").trim()
        val tripleColon = rest.indexOf(":::")
        if (tripleColon >= 0) {
            val afterCls = rest.substring(tripleColon + 3).trim()
            if (afterCls.isEmpty()) {
                throw IllegalArgumentException("Missing class name after ':::' in treemap leaf: $line")
            }
            classes += afterCls.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            rest = rest.substring(0, tripleColon).trim()
        }

        val cleanNumStr = rest.replace("_", "").replace(",", "")
        val numVal = cleanNumStr.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid numeric value '$rest' in treemap leaf: $line")
        if (!numVal.isFinite() || numVal < 0.0) {
            throw IllegalArgumentException("Treemap leaf value must be finite and non-negative, got $rest in: $line")
        }

        return RawRow(
            indent = indent,
            name = name,
            value = numVal,
            isExplicitLeaf = true,
            classes = classes,
        )
    }

    private fun buildHierarchy(
        rows: List<RawRow>,
        classDefs: Map<String, TreemapNodeStyle>,
    ): List<TreemapNode> {
        if (rows.isEmpty()) return emptyList()
        val roots = mutableListOf<MutableTreeNode>()
        val stack = mutableListOf<MutableTreeNode>()

        for (row in rows) {
            val node = MutableTreeNode(
                indent = row.indent,
                name = row.name,
                value = row.value,
                isExplicitLeaf = row.isExplicitLeaf,
                classes = row.classes,
            )
            while (stack.isNotEmpty() && stack.last().indent >= row.indent) {
                stack.removeAt(stack.lastIndex)
            }
            if (stack.isEmpty()) {
                roots += node
            } else {
                val parent = stack.last()
                parent.children += node
            }
            if (!row.isExplicitLeaf) {
                stack += node
            }
        }

        fun resolveStyle(classes: List<String>): TreemapNodeStyle? {
            var acc: TreemapNodeStyle? = null
            for (c in classes) {
                val cd = classDefs[c] ?: continue
                acc = acc?.merge(cd) ?: cd
            }
            return acc
        }

        fun toImmutable(m: MutableTreeNode): TreemapNode = TreemapNode(
            name = m.name,
            value = m.value,
            children = m.children.map { toImmutable(it) },
            classSelector = m.classes.lastOrNull(),
            style = resolveStyle(m.classes),
        )

        return roots.map { toImmutable(it) }
    }

    private fun parseNodeStyle(spec: String): TreemapNodeStyle {
        var fill: String? = null
        var stroke: String? = null
        var strokeWidth: Float? = null
        var textColor: String? = null
        var fontWeightBold: Boolean? = null

        val normalized = spec.replace("\\,", "\u0000")
        for (rawEntry in normalized.split(',', ';')) {
            val entry = rawEntry.replace('\u0000', ',').trim()
            if (entry.isEmpty()) continue
            val colon = entry.indexOf(':')
            if (colon <= 0) continue
            val key = entry.substring(0, colon).trim().lowercase()
            val value = entry.substring(colon + 1).trim()
            if (value.isEmpty()) continue
            when (key) {
                "fill", "background", "background-color" -> fill = value
                "stroke", "border-color" -> stroke = value
                "color" -> textColor = value
                "stroke-width" -> {
                    val sw = value.removeSuffix("px").trim().toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid stroke-width '$value' in treemap classDef")
                    if (!sw.isFinite() || sw < 0f) {
                        throw IllegalArgumentException("stroke-width must be finite and non-negative: $value")
                    }
                    strokeWidth = sw
                }
                "font-weight" -> {
                    val vLower = value.lowercase()
                    if (vLower == "bold" || vLower == "700" || vLower == "800" || vLower == "900") {
                        fontWeightBold = true
                    } else if (vLower == "normal" || vLower == "regular" || vLower == "400" || vLower == "300") {
                        fontWeightBold = false
                    }
                }
            }
        }

        return TreemapNodeStyle(
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            textColor = textColor,
            fontWeightBold = fontWeightBold,
        )
    }

    private fun applyConfigEntry(
        config: MutableConfig,
        key: String,
        value: String,
        onTitle: (String) -> Unit,
    ) {
        when (key) {
            "title" -> onTitle(value)
            "padding" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap padding '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Treemap padding must be >= 0, got $value")
                config.padding = v
            }
            "diagrampadding" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap diagramPadding '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Treemap diagramPadding must be >= 0, got $value")
                config.diagramPadding = v
            }
            "showvalues" -> {
                config.showValues = when (value.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Invalid treemap showValues '$value'")
                }
            }
            "nodewidth" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap nodeWidth '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Treemap nodeWidth must be > 0, got $value")
                config.width = v * 7.6f
            }
            "nodeheight" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap nodeHeight '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Treemap nodeHeight must be > 0, got $value")
                config.height = v * 8.8f
            }
            "width" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap width '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Treemap width must be > 0, got $value")
                config.width = v
            }
            "height" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap height '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Treemap height must be > 0, got $value")
                config.height = v
            }
            "borderwidth" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap borderWidth '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Treemap borderWidth must be >= 0, got $value")
                config.borderWidth = v
            }
            "valuefontsize" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap valueFontSize '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Treemap valueFontSize must be > 0, got $value")
                config.valueFontSize = v
            }
            "labelfontsize" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid treemap labelFontSize '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Treemap labelFontSize must be > 0, got $value")
                config.labelFontSize = v
            }
            "valueformat" -> {
                if (value.isNotEmpty()) config.valueFormat = value
            }
        }
    }

    private fun parseInitDirective(
        initDirective: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        for (blockMatch in Regex(
            """['"]?treemap['"]?\s*:\s*\{([^{}]*)\}""",
            RegexOption.IGNORE_CASE,
        ).findAll(initDirective)) {
            parseJsonKeyValuePairs(blockMatch.groupValues[1], config, onTitle)
        }
        val topLevel = Regex("""['"]?[A-Za-z0-9_-]+['"]?\s*:\s*\{[^{}]*\}""").replace(initDirective, "")
        parseJsonKeyValuePairs(topLevel, config, onTitle)
    }

    private fun parseJsonKeyValuePairs(
        segment: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        val pairRegex = Regex(
            """['"]?([A-Za-z0-9_]+)['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([A-Za-z0-9_.+#$%-]+))""",
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

    private fun countUnquotedBraceDelta(s: String): Int {
        var delta = 0
        var activeQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || c == '\'')) {
                activeQuote = c
            } else if (activeQuote == c) {
                activeQuote = null
            } else if (activeQuote == null) {
                if (c == '{') delta++
                else if (c == '}') delta--
            }
            i++
        }
        return delta
    }

    private fun isQuoteOpeningContext(s: String, index: Int): Boolean {
        var j = index - 1
        while (j >= 0 && (s[j] == ' ' || s[j] == '\t')) {
            j--
        }
        return j < 0 || s[j] == '\n' || s[j] == '\r' || s[j] == ':' || s[j] == '{' || s[j] == ','
    }

    private fun stripAndExtractActiveInitDirectives(source: String): Pair<String, List<String>> {
        val stripped = StringBuilder(source.length)
        val results = mutableListOf<String>()
        var i = 0
        var activeQuote: Char? = null
        while (i < source.length) {
            val c = source[i]
            if (c == '\\' && activeQuote != null && i + 1 < source.length) {
                stripped.append(source[i]).append(source[i + 1])
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(source, i)))) {
                activeQuote = c
                stripped.append(c)
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                stripped.append(c)
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
                    if (nl < 0) {
                        stripped.append(source, i, source.length)
                        break
                    }
                    stripped.append(source, i, nl + 1)
                    i = nl + 1
                    continue
                }
            }
            stripped.append(c)
            i++
        }
        return stripped.toString() to results
    }

    private fun stripLineComment(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#") || trimmed.startsWith("%%")) return ""
        var activeQuote: Char? = null
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
            if (activeQuote == null && line.startsWith("%%", i)) {
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
        val brReplaced = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(u, " ")
        return brReplaced.trim()
    }
}
