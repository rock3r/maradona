package dev.maradona.parse

import dev.maradona.model.XyAxisSpec
import dev.maradona.model.XyChartDiagram
import dev.maradona.model.XyChartOrientation
import dev.maradona.model.XyDataPoint
import dev.maradona.model.XyPlotSeries
import dev.maradona.model.XyPlotType
import kotlin.math.max
import kotlin.math.min

/**
 * Hand-written parser for Mermaid `xychart` and `xychart-beta` diagrams.
 *
 * Supports:
 * - `xychart` and `xychart-beta` headers, optional `horizontal` / `vertical` orientation
 * - YAML frontmatter and `%%{init}%%` directives (`title`, `width`, `height`, `chartOrientation`,
 *   `showTitle`, `showLegend`, `showDataLabel`, `showDataLabelOutsideBar`, `plotColorPalette`)
 * - `title`, `accTitle`, `accDescr` (single-line and multiline `{ ... }`)
 * - `x-axis [<title>] [<cat1>, <cat2>, ...]` (categorical band) and `x-axis [<title>] <min> --> <max>` (numeric range)
 * - `y-axis [<title>] [<min> --> <max>]`
 * - `bar [<title>] [<v1>, <v2>, ...]`
 * - `line [<title>] [<v1> ["<pointLabel>"], <v2>, ...]`
 */
object XyChartParser {

    fun parse(source: String): XyChartDiagram {
        var frontmatterTitle: String? = null
        var chartWidth = 700f
        var chartHeight = 500f
        var orientation = XyChartOrientation.Vertical
        var showTitle = true
        var showLegend = true
        var showDataLabel = false
        var showDataLabelOutsideBar = false
        var plotColorPalette = emptyList<String>()

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
            Regex("""['"]?(?:chartWidth|width)['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it >= 200f) chartWidth = it
                }
            Regex("""['"]?(?:chartHeight|height)['"]?\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    if (it.isFinite() && it >= 160f) chartHeight = it
                }
            Regex("""['"]?(?:chartOrientation|orientation)['"]?\s*:\s*['"]?(horizontal|vertical)['"]?""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.lowercase()?.let {
                    orientation = if (it == "horizontal") XyChartOrientation.Horizontal else XyChartOrientation.Vertical
                }
            Regex("""['"]?showTitle['"]?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.lowercase()?.let { showTitle = it == "true" }
            Regex("""['"]?showLegend['"]?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.lowercase()?.let { showLegend = it == "true" }
            Regex("""['"]?showDataLabel['"]?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.lowercase()?.let { showDataLabel = it == "true" }
            Regex("""['"]?showDataLabelOutsideBar['"]?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.lowercase()?.let { showDataLabelOutsideBar = it == "true" }
            Regex("""['"]?plotColorPalette['"]?\s*:\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
                .find(initBody)?.let { m ->
                    val rawPalette = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    val parsed = splitPaletteColors(rawPalette)
                    if (parsed.isNotEmpty()) plotColorPalette = parsed
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
                        "width", "chartwidth" -> value.toFloatOrNull()?.let { if (it.isFinite() && it >= 200f) chartWidth = it }
                        "height", "chartheight" -> value.toFloatOrNull()?.let { if (it.isFinite() && it >= 160f) chartHeight = it }
                        "orientation", "chartorientation" -> {
                            when (value.lowercase()) {
                                "horizontal" -> orientation = XyChartOrientation.Horizontal
                                "vertical" -> orientation = XyChartOrientation.Vertical
                            }
                        }
                        "showtitle" -> value.lowercase().toBooleanStrictOrNull()?.let { showTitle = it }
                        "showlegend" -> value.lowercase().toBooleanStrictOrNull()?.let { showLegend = it }
                        "showdatalabel" -> value.lowercase().toBooleanStrictOrNull()?.let { showDataLabel = it }
                        "showdatalabeloutsidebar" -> value.lowercase().toBooleanStrictOrNull()?.let { showDataLabelOutsideBar = it }
                        "plotcolorpalette" -> {
                            val parsed = splitPaletteColors(value)
                            if (parsed.isNotEmpty()) plotColorPalette = parsed
                        }
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        // Join lines that have an unclosed '[' bracket outside quotes (when not in accDescr block).
        val mergedLines = mergeMultilineBracketLines(bodyLines)

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        var xAxisTitle: String? = null
        var xAxisCategories: List<String>? = null
        var xAxisRange: Pair<Double, Double>? = null

        var yAxisTitle: String? = null
        var yAxisRange: Pair<Double, Double>? = null

        val plots = mutableListOf<XyPlotSeries>()

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
            val statements = splitStatementsOutsideQuotesAndBrackets(commentFree)
            for (stmt in statements) {
                val clean = stmt.trim()
                if (clean.isEmpty()) continue

                if (!headerSeen) {
                    val headerMatch = Regex(
                        """^xychart(?:-beta)?(?:\s+(horizontal|vertical))?$""",
                        RegexOption.IGNORE_CASE,
                    ).find(clean)
                    if (headerMatch != null) {
                        headerSeen = true
                        val orientToken = headerMatch.groupValues[1].lowercase()
                        if (orientToken == "horizontal") orientation = XyChartOrientation.Horizontal
                        else if (orientToken == "vertical") orientation = XyChartOrientation.Vertical
                        continue
                    }
                    headerSeen = true
                }

                val lower = clean.lowercase()
                if (lower == "horizontal") {
                    orientation = XyChartOrientation.Horizontal
                    continue
                }
                if (lower == "vertical") {
                    orientation = XyChartOrientation.Vertical
                    continue
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

                val xAxisMatch = Regex("""^x-axis(?:\s+|(?=\[))(.*)$""", RegexOption.IGNORE_CASE).find(clean)
                if (xAxisMatch != null) {
                    val spec = xAxisMatch.groupValues[1].trim()
                    if (spec.isEmpty()) throw IllegalArgumentException("Empty x-axis in xychart")
                    val parsedX = parseXAxisSpec(spec)
                    if (parsedX.categories != null) {
                        xAxisTitle = parsedX.title
                        xAxisCategories = parsedX.categories
                        xAxisRange = null
                    } else if (parsedX.range != null) {
                        xAxisTitle = parsedX.title
                        xAxisRange = parsedX.range
                        xAxisCategories = null
                    } else {
                        xAxisTitle = parsedX.title
                    }
                    continue
                }

                val yAxisMatch = Regex("""^y-axis\s+(.+)$""", RegexOption.IGNORE_CASE).find(clean)
                if (yAxisMatch != null) {
                    val spec = yAxisMatch.groupValues[1].trim()
                    val parsedY = parseYAxisSpec(spec)
                    if (parsedY.range != null) {
                        yAxisTitle = parsedY.title
                        yAxisRange = parsedY.range
                    } else {
                        yAxisTitle = parsedY.title
                    }
                    continue
                }

                val barMatch = Regex("""^bar(?:\s+|(?=\[))(.*)$""", RegexOption.IGNORE_CASE).find(clean)
                if (barMatch != null) {
                    val series = parsePlotSeries(
                        spec = barMatch.groupValues[1].trim(),
                        type = XyPlotType.Bar,
                        index = plots.size,
                        palette = plotColorPalette,
                    )
                    plots += series
                    continue
                }

                val lineMatch = Regex("""^line(?:\s+|(?=\[))(.*)$""", RegexOption.IGNORE_CASE).find(clean)
                if (lineMatch != null) {
                    val series = parsePlotSeries(
                        spec = lineMatch.groupValues[1].trim(),
                        type = XyPlotType.Line,
                        index = plots.size,
                        palette = plotColorPalette,
                    )
                    plots += series
                    continue
                }

                throw IllegalArgumentException("Unexpected xychart statement: $clean")
            }
        }
        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in xychart")
        }

        // Resolve X-axis spec.
        val resolvedXAxis: XyAxisSpec = when {
            xAxisCategories != null -> XyAxisSpec.BandAxis(
                title = xAxisTitle,
                categories = xAxisCategories,
            )
            xAxisRange != null -> XyAxisSpec.LinearAxis(
                title = xAxisTitle,
                min = xAxisRange.first,
                max = xAxisRange.second,
            )
            else -> {
                val maxPoints = plots.maxOfOrNull { it.points.size } ?: 1
                XyAxisSpec.LinearAxis(
                    title = xAxisTitle,
                    min = 1.0,
                    max = max(1, maxPoints).toDouble(),
                )
            }
        }

        // Slice plot data to band category count when X-axis is categorical, matching Mermaid xychartDb.ts.
        val normalizedPlots = if (resolvedXAxis is XyAxisSpec.BandAxis) {
            val catCount = resolvedXAxis.categories.size
            plots.map { s ->
                if (s.points.size > catCount) s.copy(points = s.points.take(catCount)) else s
            }
        } else {
            plots.toList()
        }

        // Resolve Y-axis spec (auto-ranging over all plot values when not explicitly specified).
        val resolvedYAxis: XyAxisSpec.LinearAxis = if (yAxisRange != null) {
            XyAxisSpec.LinearAxis(
                title = yAxisTitle,
                min = yAxisRange.first,
                max = yAxisRange.second,
            )
        } else {
            val allValues = normalizedPlots.flatMap { s -> s.points.map { it.value } }
            val minVal = allValues.minOrNull() ?: 0.0
            val maxVal = allValues.maxOrNull() ?: 10.0
            XyAxisSpec.LinearAxis(
                title = yAxisTitle,
                min = minVal,
                max = maxVal,
            )
        }

        return XyChartDiagram(
            plots = normalizedPlots,
            xAxis = resolvedXAxis,
            yAxis = resolvedYAxis,
            orientation = orientation,
            chartWidth = chartWidth,
            chartHeight = chartHeight,
            showTitle = showTitle,
            showLegend = showLegend,
            showDataLabel = showDataLabel,
            showDataLabelOutsideBar = showDataLabelOutsideBar,
            plotColorPalette = plotColorPalette,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private data class ParsedAxisSpec(
        val title: String?,
        val categories: List<String>? = null,
        val range: Pair<Double, Double>? = null,
    )

    private fun parseXAxisSpec(spec: String): ParsedAxisSpec {
        val bracketIdx = findCharOutsideQuotes(spec, '[')
        if (bracketIdx >= 0) {
            val closeIdx = findCharOutsideQuotes(spec, ']', bracketIdx + 1)
            if (closeIdx < 0) {
                throw IllegalArgumentException("Unclosed '[' in x-axis: $spec")
            }
            val trailing = spec.substring(closeIdx + 1).trim()
            if (trailing.isNotEmpty()) {
                throw IllegalArgumentException("Unexpected trailing tokens after x-axis categories: $spec")
            }
            val titlePart = spec.substring(0, bracketIdx).trim()
            val title = if (titlePart.isNotEmpty()) normalizeText(titlePart) else null
            val inner = spec.substring(bracketIdx + 1, closeIdx).trim()
            if (inner.isEmpty()) {
                throw IllegalArgumentException("x-axis category list cannot be empty")
            }
            val categories = splitCommaOutsideQuotes(inner).map { token ->
                val t = normalizeText(token)
                if (t.isEmpty()) throw IllegalArgumentException("Empty category in x-axis: $spec")
                t
            }
            return ParsedAxisSpec(title = title, categories = categories)
        }

        val arrowSpan = findArrowOutsideQuotes(spec)
        if (arrowSpan != null) {
            val (title, range) = parseTitleAndRange(spec, arrowSpan, "x-axis")
            return ParsedAxisSpec(title = title, range = range)
        }

        val title = normalizeText(spec)
        if (title.isEmpty()) throw IllegalArgumentException("Empty x-axis title in xychart")
        return ParsedAxisSpec(title = title)
    }

    private fun parseYAxisSpec(spec: String): ParsedAxisSpec {
        if (findCharOutsideQuotes(spec, '[') >= 0) {
            throw IllegalArgumentException("y-axis does not support categorical bands in xychart: $spec")
        }
        val arrowSpan = findArrowOutsideQuotes(spec)
        if (arrowSpan != null) {
            val (title, range) = parseTitleAndRange(spec, arrowSpan, "y-axis")
            return ParsedAxisSpec(title = title, range = range)
        }
        val title = normalizeText(spec)
        if (title.isEmpty()) throw IllegalArgumentException("Empty y-axis title in xychart")
        return ParsedAxisSpec(title = title)
    }

    private fun parseTitleAndRange(
        spec: String,
        arrowSpan: IntRange,
        axisName: String,
    ): Pair<String?, Pair<Double, Double>> {
        val leftPart = spec.substring(0, arrowSpan.first).trim()
        val rightPart = spec.substring(arrowSpan.last + 1).trim()
        val maxVal = rightPart.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid $axisName max value '$rightPart' in: $spec")
        if (!maxVal.isFinite()) {
            throw IllegalArgumentException("$axisName max value must be finite in: $spec")
        }

        val (titleRaw, minStr) = splitOptionalTitleAndMin(leftPart)
            ?: throw IllegalArgumentException("Invalid $axisName min value in: $spec")
        val minVal = minStr.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid $axisName min value '$minStr' in: $spec")
        if (!minVal.isFinite()) {
            throw IllegalArgumentException("$axisName min value must be finite in: $spec")
        }
        val title = if (titleRaw.isNotEmpty()) normalizeText(titleRaw).takeIf { it.isNotEmpty() } else null
        return title to (minVal to maxVal)
    }

    private fun splitOptionalTitleAndMin(leftPart: String): Pair<String, String>? {
        val trimmed = leftPart.trim()
        if (trimmed.isEmpty()) return null
        val numRegex = Regex("""^[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?$""")
        // If the entire leftPart is an unquoted number, there is no title.
        if (numRegex.matches(trimmed)) {
            return "" to trimmed
        }
        // Otherwise scan quote-aware to find where the title ends and the trailing number begins.
        var inQuotes = false
        var lastQuoteClose = -1
        var lastSpaceOutsideQuotes = -1
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (c == '\\' && inQuotes && i + 1 < trimmed.length) {
                i += 2
                continue
            }
            if (c == '"') {
                if (inQuotes) lastQuoteClose = i
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && c.isWhitespace()) {
                lastSpaceOutsideQuotes = i
            }
            i++
        }
        if (inQuotes) return null
        val splitAt = when {
            lastSpaceOutsideQuotes >= 0 -> lastSpaceOutsideQuotes + 1
            lastQuoteClose >= 0 && lastQuoteClose + 1 < trimmed.length -> lastQuoteClose + 1
            else -> return null
        }
        val titleCandidate = trimmed.substring(0, splitAt).trim()
        val minCandidate = trimmed.substring(splitAt).trim()
        if (!numRegex.matches(minCandidate)) return null
        return titleCandidate to minCandidate
    }

    private fun parsePlotSeries(
        spec: String,
        type: XyPlotType,
        index: Int,
        palette: List<String>,
    ): XyPlotSeries {
        val openIdx = findCharOutsideQuotes(spec, '[')
        if (openIdx < 0) {
            throw IllegalArgumentException("Missing '[' data array in ${type.name.lowercase()} plot: $spec")
        }
        val closeIdx = findCharOutsideQuotes(spec, ']', openIdx + 1)
        if (closeIdx < 0) {
            throw IllegalArgumentException("Unclosed '[' data array in ${type.name.lowercase()} plot: $spec")
        }
        val trailing = spec.substring(closeIdx + 1).trim()
        if (trailing.isNotEmpty()) {
            throw IllegalArgumentException("Unexpected trailing text after ${type.name.lowercase()} array: $spec")
        }

        val titleRaw = spec.substring(0, openIdx).trim()
        val title = if (titleRaw.isNotEmpty()) normalizeText(titleRaw).takeIf { it.isNotEmpty() } else null
        val inner = spec.substring(openIdx + 1, closeIdx).trim()
        if (inner.isEmpty()) {
            throw IllegalArgumentException("${type.name} plot data array cannot be empty")
        }

        val items = splitCommaOutsideQuotes(inner)
        val points = items.map { rawItem ->
            val trimmedItem = rawItem.trim()
            if (trimmedItem.isEmpty()) {
                throw IllegalArgumentException("Empty data point in ${type.name.lowercase()} plot: $spec")
            }
            val m = Regex(
                """^([+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?)(?:\s+(.+))?$""",
            ).find(trimmedItem) ?: throw IllegalArgumentException("Invalid data point '$trimmedItem' in: $spec")

            val numStr = m.groupValues[1]
            val value = numStr.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid numeric value '$numStr' in: $spec")
            if (!value.isFinite()) {
                throw IllegalArgumentException("Plot data value must be finite: $numStr")
            }
            val labelRaw = m.groupValues[2].trim()
            val pointLabel = if (labelRaw.isNotEmpty()) normalizeText(labelRaw).takeIf { it.isNotEmpty() } else null
            XyDataPoint(value = value, label = pointLabel)
        }

        val color = if (palette.isNotEmpty()) palette[index % palette.size] else null
        return XyPlotSeries(
            id = "plot_$index",
            type = type,
            title = title,
            points = points,
            color = color,
        )
    }

    private fun mergeMultilineBracketLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var bracketDepth = 0
        var inAccBlock = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (bracketDepth == 0) {
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

            val clean = stripLineComment(rawLine)
            if (bracketDepth > 0) {
                current.append(' ').append(clean.trim())
            } else {
                current.setLength(0)
                current.append(clean)
            }
            bracketDepth += countBracketDeltaOutsideQuotes(clean)
            if (bracketDepth < 0) {
                throw IllegalArgumentException("Unmatched ']' in xychart: $rawLine")
            }
            if (bracketDepth == 0) {
                result += current.toString()
                current.setLength(0)
            }
        }
        if (bracketDepth > 0) {
            throw IllegalArgumentException("Unclosed '[' bracket in xychart")
        }
        return result
    }

    private fun countBracketDeltaOutsideQuotes(s: String): Int {
        var inQuotes = false
        var delta = 0
        var i = 0
        while (i < s.length) {
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
            if (!inQuotes) {
                if (c == '[') delta++
                else if (c == ']') delta--
            }
            i++
        }
        return delta
    }

    private fun splitStatementsOutsideQuotesAndBrackets(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var bracketDepth = 0
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
            if (!inQuotes) {
                if (c == '[') bracketDepth++
                else if (c == ']') bracketDepth = max(0, bracketDepth - 1)
                else if (c == ';' && bracketDepth == 0) {
                    out += sb.toString()
                    sb.setLength(0)
                    i++
                    continue
                }
            }
            sb.append(c)
            i++
        }
        out += sb.toString()
        return out
    }

    private fun splitCommaOutsideQuotes(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && inQuotes && i + 1 < s.length) {
                sb.append(s[i]).append(s[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                sb.append(c)
                i++
                continue
            }
            if (!inQuotes && c == ',') {
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

    private fun splitPaletteColors(raw: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var parenDepth = 0
        for (c in raw) {
            when (c) {
                '(' -> {
                    parenDepth++
                    sb.append(c)
                }
                ')' -> {
                    if (parenDepth > 0) parenDepth--
                    sb.append(c)
                }
                ',' -> if (parenDepth == 0) {
                    val token = sb.toString().trim()
                    if (token.isNotEmpty()) out += token
                    sb.setLength(0)
                } else {
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        val last = sb.toString().trim()
        if (last.isNotEmpty()) out += last
        return out
    }

    private fun findCharOutsideQuotes(s: String, target: Char, startIndex: Int = 0): Int {
        var inQuotes = false
        var i = 0
        while (i < s.length) {
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
            if (!inQuotes && i >= startIndex && c == target) {
                return i
            }
            i++
        }
        return -1
    }

    private fun findArrowOutsideQuotes(s: String): IntRange? {
        var inQuotes = false
        var i = 0
        var lastMatch: IntRange? = null
        while (i < s.length) {
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
            if (!inQuotes && s.startsWith("-->", i)) {
                lastMatch = i..(i + 2)
                i += 3
                continue
            }
            i++
        }
        return lastMatch
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
        val mdStripped = Regex("""^`([\s\S]*)`$""")
            .find(brReplaced)?.groupValues?.get(1) ?: brReplaced
        return mdStripped.trim()
    }
}
