package dev.maradona.parse

import dev.maradona.model.QuadrantDiagram
import dev.maradona.model.QuadrantPoint
import dev.maradona.model.QuadrantPointStyle

/**
 * Hand-written parser for Mermaid `quadrantChart` diagrams.
 *
 * Supports:
 * - `quadrantChart` header, YAML frontmatter (`title`, `chartWidth`, `chartHeight`), and `%%{init}%%` directives
 * - `title`, `accTitle`, `accDescr` (single-line and multiline `{ ... }`)
 * - `x-axis <left> [--> <right>]` and `y-axis <bottom> [--> <top>]`
 * - `quadrant-1`, `quadrant-2`, `quadrant-3`, `quadrant-4` labels
 * - `Point Label[:::<class>] : [x, y] [color: ..., radius: ..., stroke-color: ..., stroke-width: ...]`
 * - `classDef <name> color: ..., radius: ..., stroke-color: ..., stroke-width: ...`
 */
object QuadrantParser {

    private data class RawPoint(
        val id: String,
        val label: String,
        val x: Double,
        val y: Double,
        val className: String?,
        val inlineStyle: QuadrantPointStyle?,
    )

    fun parse(source: String): QuadrantDiagram {
        var frontmatterTitle: String? = null
        var chartWidth = 500f
        var chartHeight = 500f

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
            Regex("""['"]?chartWidth['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it >= 160f) chartWidth = it
                }
            Regex("""['"]?chartHeight['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it >= 160f) chartHeight = it
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
                        "chartwidth" -> value.toFloatOrNull()?.let { if (it.isFinite() && it >= 160f) chartWidth = it }
                        "chartheight" -> value.toFloatOrNull()?.let { if (it.isFinite() && it >= 160f) chartHeight = it }
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

        var q1Text: String? = null
        var q2Text: String? = null
        var q3Text: String? = null
        var q4Text: String? = null
        var xLeft: String? = null
        var xRight: String? = null
        var yBottom: String? = null
        var yTop: String? = null

        val classDefs = HashMap<String, QuadrantPointStyle>()
        val rawPoints = mutableListOf<RawPoint>()

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

            val commentFree = stripLineComment(rawLine).trim()
            if (commentFree.isEmpty()) continue
            val statements = splitStatementsOutsideQuotes(commentFree)
            for (stmt in statements) {
                val clean = stmt.trim()
                if (clean.isEmpty()) continue
                val lower = clean.lowercase()

                if (!headerSeen) {
                    if (lower == "quadrantchart" || lower.startsWith("quadrantchart ") || lower.startsWith("quadrantchart\t")) {
                        headerSeen = true
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

                val qMatch = Regex("""^quadrant-([1-4])\s+(.+)$""", RegexOption.IGNORE_CASE).find(clean)
                if (qMatch != null) {
                    val qNum = qMatch.groupValues[1].toInt()
                    val qVal = normalizeText(qMatch.groupValues[2])
                    when (qNum) {
                        1 -> q1Text = qVal
                        2 -> q2Text = qVal
                        3 -> q3Text = qVal
                        4 -> q4Text = qVal
                    }
                    continue
                }

                val xAxisMatch = Regex("""^x-axis\s+(.+)$""", RegexOption.IGNORE_CASE).find(clean)
                if (xAxisMatch != null) {
                    val (left, right) = splitAxisSpec(xAxisMatch.groupValues[1])
                    xLeft = left
                    xRight = right
                    continue
                }

                val yAxisMatch = Regex("""^y-axis\s+(.+)$""", RegexOption.IGNORE_CASE).find(clean)
                if (yAxisMatch != null) {
                    val (bottom, top) = splitAxisSpec(yAxisMatch.groupValues[1])
                    yBottom = bottom
                    yTop = top
                    continue
                }

                val classDefMatch = Regex("""^classDef\s+(\S+)\s+(.+)$""", RegexOption.IGNORE_CASE).find(clean)
                if (classDefMatch != null) {
                    val clsName = classDefMatch.groupValues[1].trim()
                    val style = parsePointStyle(classDefMatch.groupValues[2].trim())
                    classDefs[clsName] = style
                    continue
                }

                rawPoints += parsePointLine(clean, rawPoints.size)
            }
        }

        val resolvedPoints = rawPoints.map { rp ->
            val clsStyle = rp.className?.let { classDefs[it] }
            val merged = when {
                clsStyle != null && rp.inlineStyle != null -> clsStyle.merge(rp.inlineStyle)
                clsStyle != null -> clsStyle
                else -> rp.inlineStyle
            }
            QuadrantPoint(
                id = rp.id,
                label = rp.label,
                x = rp.x,
                y = rp.y,
                className = rp.className,
                style = merged,
            )
        }

        return QuadrantDiagram(
            points = resolvedPoints,
            quadrant1Text = q1Text,
            quadrant2Text = q2Text,
            quadrant3Text = q3Text,
            quadrant4Text = q4Text,
            xAxisLeftText = xLeft,
            xAxisRightText = xRight,
            yAxisBottomText = yBottom,
            yAxisTopText = yTop,
            chartWidth = chartWidth,
            chartHeight = chartHeight,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun splitStatementsOutsideQuotes(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                sb.append(line[i]).append(line[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                sb.append(c)
                i++
                continue
            }
            if (!inQuotes && c == ';') {
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

    private fun splitAxisSpec(spec: String): Pair<String, String?> {
        var inQuotes = false
        var i = 0
        while (i < spec.length) {
            val c = spec[i]
            if (c == '\\' && inQuotes && i + 1 < spec.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && c == '-') {
                var j = i
                while (j < spec.length && spec[j] == '-') j++
                if (j - i >= 2 && j < spec.length && spec[j] == '>') {
                    val left = normalizeText(spec.substring(0, i))
                    val right = normalizeText(spec.substring(j + 1))
                    return if (right.isEmpty()) {
                        "$left \u27F6 " to null
                    } else {
                        left to right
                    }
                }
            }
            i++
        }
        return normalizeText(spec) to null
    }

    private fun parsePointLine(line: String, index: Int): RawPoint {
        var inQuotes = false
        var colonIdx = -1
        var openBracketIdx = -1
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && c == ':') {
                if (i + 2 < line.length && line[i + 1] == ':' && line[i + 2] == ':') {
                    i += 3
                    continue
                }
                var j = i + 1
                while (j < line.length && line[j].isWhitespace()) j++
                if (j < line.length && line[j] == '[') {
                    colonIdx = i
                    openBracketIdx = j
                    break
                }
            }
            i++
        }
        if (colonIdx <= 0 || openBracketIdx < 0) {
            throw IllegalArgumentException("Invalid quadrantChart point syntax: $line")
        }
        val closeBracketIdx = line.indexOf(']', openBracketIdx + 1)
        if (closeBracketIdx < 0) {
            throw IllegalArgumentException("Unclosed coordinate bracket in quadrantChart point: $line")
        }

        val rawNameAndClass = line.substring(0, colonIdx).trim()
        val coordsText = line.substring(openBracketIdx + 1, closeBracketIdx)
        val coordParts = coordsText.split(',')
        if (coordParts.size != 2) {
            throw IllegalArgumentException("Expected [x, y] coordinates in quadrantChart point: $line")
        }
        val xStr = coordParts[0].trim()
        val yStr = coordParts[1].trim()
        val styleTail = line.substring(closeBracketIdx + 1).trim()

        val x = xStr.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid x coordinate '$xStr' in quadrantChart: $line")
        val y = yStr.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid y coordinate '$yStr' in quadrantChart: $line")
        if (!x.isFinite() || x < 0.0 || x > 1.0 || !y.isFinite() || y < 0.0 || y > 1.0) {
            throw IllegalArgumentException("Quadrant point coordinates must be in [0, 1], got [$x, $y] in: $line")
        }

        val classSplit = findClassSplitOutsideQuotes(rawNameAndClass)
        val label: String
        val className: String?
        if (classSplit > 0) {
            label = normalizeText(rawNameAndClass.substring(0, classSplit))
            className = rawNameAndClass.substring(classSplit + 3).trim().takeIf { it.isNotEmpty() }
        } else {
            label = normalizeText(rawNameAndClass)
            className = null
        }

        val inlineStyle = if (styleTail.isNotEmpty()) parsePointStyle(styleTail) else null
        return RawPoint(
            id = "qpt_$index",
            label = label,
            x = x,
            y = y,
            className = className,
            inlineStyle = inlineStyle,
        )
    }

    private fun findClassSplitOutsideQuotes(s: String): Int {
        var inQuotes = false
        var i = 0
        while (i <= s.length - 3) {
            val c = s[i]
            if (c == '\\' && inQuotes && i + 1 < s.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && s.startsWith(":::", i)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun parsePointStyle(spec: String): QuadrantPointStyle {
        var color: String? = null
        var radius: Float? = null
        var strokeColor: String? = null
        var strokeWidth: Float? = null

        for (entry in splitStyleEntriesOutsideParens(spec)) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim().lowercase()
            val value = trimmed.substring(colon + 1).trim()
            when (key) {
                "color" -> if (value.isNotEmpty()) color = value
                "radius" -> {
                    val r = value.removeSuffix("px").trim().toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid point radius '$value'")
                    if (!r.isFinite() || r <= 0f) throw IllegalArgumentException("Point radius must be finite and positive: $value")
                    radius = r
                }
                "stroke-color" -> if (value.isNotEmpty()) strokeColor = value
                "stroke-width" -> {
                    val sw = value.removeSuffix("px").trim().toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid stroke-width '$value'")
                    if (!sw.isFinite() || sw < 0f) throw IllegalArgumentException("stroke-width must be finite and non-negative: $value")
                    strokeWidth = sw
                }
            }
        }

        return QuadrantPointStyle(
            color = color,
            radius = radius,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
        )
    }

    private fun splitStyleEntriesOutsideParens(spec: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var parenDepth = 0
        for (c in spec) {
            when (c) {
                '(' -> {
                    parenDepth++
                    sb.append(c)
                }
                ')' -> {
                    parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    sb.append(c)
                }
                ',' -> {
                    if (parenDepth == 0) {
                        out += sb.toString()
                        sb.setLength(0)
                    } else {
                        sb.append(c)
                    }
                }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }

    private fun stripLineComment(line: String): String {
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun normalizeText(s: String): String {
        var t = unquote(s)
        if (t.length >= 2 && t.startsWith('`') && t.endsWith('`')) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<(b|strong)>(.*?)</\1>""", RegexOption.IGNORE_CASE), "**$2**")
            .replace(Regex("""<(i|em)>(.*?)</\1>""", RegexOption.IGNORE_CASE), "*$2*")
            .trim()
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
