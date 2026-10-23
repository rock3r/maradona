package dev.maradona.parse

import dev.maradona.model.RadarAxis
import dev.maradona.model.RadarConfig
import dev.maradona.model.RadarCurve
import dev.maradona.model.RadarDiagram
import dev.maradona.model.RadarGraticule

/**
 * Hand-written parser for Mermaid `radar` / `radar-beta` diagrams.
 *
 * Supports:
 * - `radar`, `radar:`, `radar-beta`, and `radar-beta:` headers
 * - YAML frontmatter (block and flow mappings) and `%%{init}%%` configuration
 * - `title`, `accTitle`, `accDescr` (single-line and multi-line `{ ... }`)
 * - `axis <id>["<label>"], ...` declarations
 * - `curve <id>["<label>"]{<v1>, <v2>, ...}` (positional) and
 *   `curve <id>["<label>"]{<axisId>: <v1>, <axisId> <v2>, ...}` (keyed by axis ID)
 * - Option statements: `max <num>`, `min <num>`, `ticks <int>`, `showLegend <bool>`,
 *   `graticule <circle|polygon>` (single or comma-separated)
 */
object RadarParser {

    private const val MAX_TICKS = 32

    private class MutableConfig(
        var width: Float = 600f,
        var height: Float = 600f,
        var marginTop: Float = 50f,
        var marginRight: Float = 50f,
        var marginBottom: Float = 50f,
        var marginLeft: Float = 50f,
        var showLegend: Boolean = true,
        var ticks: Int = 5,
        var max: Double? = null,
        var min: Double = 0.0,
        var graticule: RadarGraticule = RadarGraticule.Circle,
        var axisScaleFactor: Float = 1.0f,
        var axisLabelFactor: Float = 1.05f,
        var curveTension: Float = 0.17f,
        var curveOpacity: Float = 0.25f,
        var curveStrokeWidth: Float = 2.2f,
        val customPalette: LinkedHashMap<Int, String> = LinkedHashMap(),
    ) {
        fun toImmutable(): RadarConfig {
            val resolvedMax = max
            if (resolvedMax != null && resolvedMax < min) {
                throw IllegalArgumentException("Radar max ($resolvedMax) must be >= min ($min)")
            }
            val paletteMap = customPalette.entries
                .filter { it.value.isNotBlank() }
                .associate { it.key to it.value }
            return RadarConfig(
                width = width,
                height = height,
                marginTop = marginTop,
                marginRight = marginRight,
                marginBottom = marginBottom,
                marginLeft = marginLeft,
                showLegend = showLegend,
                ticks = ticks,
                max = resolvedMax,
                min = min,
                graticule = graticule,
                axisScaleFactor = axisScaleFactor,
                axisLabelFactor = axisLabelFactor,
                curveTension = curveTension,
                curveOpacity = curveOpacity,
                curveStrokeWidth = curveStrokeWidth,
                customPalette = paletteMap,
            )
        }
    }

    private sealed interface RawCurveEntry {
        data class Positional(val value: Double) : RawCurveEntry
        data class Keyed(val axisId: String, val value: Double) : RawCurveEntry
    }

    private data class RawCurve(
        val id: String,
        val label: String,
        val entries: List<RawCurveEntry>,
    )

    fun parse(source: String): RadarDiagram {
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
                    val inRadarConfig = path.isEmpty() ||
                        path == listOf("radar") ||
                        path == listOf("config", "radar") ||
                        path == listOf("themevariables") ||
                        path == listOf("themevariables", "radar") ||
                        path == listOf("config", "themevariables") ||
                        path == listOf("config", "themevariables", "radar")
                    if (rawVal.startsWith("{") && rawVal.endsWith("}")) {
                        if ((key == "radar" || key == "themevariables") &&
                            (path.isEmpty() || path == listOf("config") || path == listOf("themevariables") || path == listOf("config", "themevariables"))
                        ) {
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
                    } else if (inRadarConfig) {
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

        val mergedLines = mergeLogicalLines(bodyLines)

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val axes = mutableListOf<RadarAxis>()
        val axisIds = HashSet<String>()
        val rawCurves = mutableListOf<RawCurve>()

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
                    """^radar(?:-beta)?\s*:?(?:\s+title(?:\s+|:)\s*(.*)|\s*)?$""",
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
                if (commentFree.lowercase().startsWith("radar")) {
                    throw IllegalArgumentException("Unexpected tokens on radar header line: $commentFree")
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

            for (rawStmt in splitSemicolonOutsideQuotesAndGroups(commentFree)) {
                val stmt = rawStmt.trim()
                if (stmt.isEmpty()) continue

                val axisMatch = Regex("""^axis\s+(.+)$""", RegexOption.IGNORE_CASE).find(stmt)
                if (axisMatch != null) {
                    val items = splitTopLevelComma(axisMatch.groupValues[1])
                    if (items.isEmpty()) {
                        throw IllegalArgumentException("Empty axis declaration in radar diagram: $stmt")
                    }
                    for (rawItem in items) {
                        val item = rawItem.trim()
                        if (item.isEmpty()) {
                            throw IllegalArgumentException("Empty axis item in radar diagram: $stmt")
                        }
                        val axis = parseAxisItem(item)
                        if (!axisIds.add(axis.id)) {
                            throw IllegalArgumentException("Duplicate radar axis id '${axis.id}'")
                        }
                        axes += axis
                    }
                    continue
                }

                val curveMatch = Regex("""^curve\s+(.+)$""", RegexOption.IGNORE_CASE).find(stmt)
                if (curveMatch != null) {
                    val items = splitTopLevelComma(curveMatch.groupValues[1])
                    if (items.isEmpty()) {
                        throw IllegalArgumentException("Empty curve declaration in radar diagram: $stmt")
                    }
                    for (rawItem in items) {
                        val item = rawItem.trim()
                        if (item.isEmpty()) {
                            throw IllegalArgumentException("Empty curve item in radar diagram: $stmt")
                        }
                        rawCurves += parseCurveItem(item)
                    }
                    continue
                }

                // Option statement(s), optionally comma-separated (`max 100, min 0`).
                val optionItems = splitTopLevelComma(stmt)
                for (rawOpt in optionItems) {
                    val opt = rawOpt.trim()
                    if (opt.isEmpty()) continue
                    parseOptionStatement(opt, config)
                }
            }
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in radar diagram")
        }

        val resolvedCurves = rawCurves.map { rc ->
            resolveCurveEntries(rc, axes)
        }

        return RadarDiagram(
            axes = axes,
            curves = resolvedCurves,
            config = config.toImmutable(),
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun parseAxisItem(item: String): RadarAxis {
        val openBracket = findCharOutsideQuotes(item, '[')
        if (openBracket < 0) {
            val id = normalizeId(item)
            if (id.isEmpty()) {
                throw IllegalArgumentException("Empty axis id in radar diagram: $item")
            }
            return RadarAxis(id = id, label = id)
        }
        val closeBracket = findLastCharOutsideQuotes(item, ']')
        if (closeBracket <= openBracket || item.substring(closeBracket + 1).trim().isNotEmpty()) {
            throw IllegalArgumentException("Invalid axis label syntax in radar diagram: $item")
        }
        val id = normalizeId(item.substring(0, openBracket).trim())
        if (id.isEmpty()) {
            throw IllegalArgumentException("Missing axis id before '[' in radar diagram: $item")
        }
        val rawLabel = item.substring(openBracket + 1, closeBracket).trim()
        val label = normalizeText(rawLabel).ifEmpty { id }
        return RadarAxis(id = id, label = label)
    }

    private fun parseCurveItem(item: String): RawCurve {
        val openBrace = findCharOutsideQuotes(item, '{')
        val closeBrace = findLastCharOutsideQuotes(item, '}')
        if (openBrace <= 0 || closeBrace < openBrace || item.substring(closeBrace + 1).trim().isNotEmpty()) {
            throw IllegalArgumentException("Expected '<id>[\"<label>\"]{...}' in radar curve: $item")
        }
        val headerPart = item.substring(0, openBrace).trim()
        val entriesPart = item.substring(openBrace + 1, closeBrace).trim()

        val axisLike = parseAxisItem(headerPart)
        if (entriesPart.isEmpty()) {
            throw IllegalArgumentException("Empty curve entries in radar diagram: $item")
        }
        val rawTokens = splitTopLevelComma(entriesPart)
        val entries = mutableListOf<RawCurveEntry>()
        for (rawTok in rawTokens) {
            val tok = rawTok.trim()
            if (tok.isEmpty()) continue
            val numVal = tok.toDoubleOrNull()
            if (numVal != null) {
                if (!numVal.isFinite()) {
                    throw IllegalArgumentException("Non-finite radar curve value '$tok' in: $item")
                }
                entries += RawCurveEntry.Positional(numVal)
                continue
            }
            // Keyed entry: `<axisId>: <val>` or `<axisId> <val>`
            val colonIdx = findCharOutsideQuotes(tok, ':')
            val (axisRefRaw, valStr) = if (colonIdx > 0) {
                tok.substring(0, colonIdx).trim() to tok.substring(colonIdx + 1).trim()
            } else {
                val wsIdx = tok.indexOfFirst { it.isWhitespace() }
                if (wsIdx <= 0) {
                    throw IllegalArgumentException("Invalid radar curve entry '$tok' in: $item")
                }
                tok.substring(0, wsIdx).trim() to tok.substring(wsIdx + 1).trim()
            }
            val axisId = normalizeId(axisRefRaw)
            val v = valStr.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid numeric value '$valStr' for axis '$axisId' in: $item")
            if (!v.isFinite()) {
                throw IllegalArgumentException("Non-finite numeric value '$valStr' for axis '$axisId' in: $item")
            }
            entries += RawCurveEntry.Keyed(axisId = axisId, value = v)
        }
        if (entries.isEmpty()) {
            throw IllegalArgumentException("No valid entries in radar curve: $item")
        }
        return RawCurve(
            id = axisLike.id,
            label = axisLike.label,
            entries = entries,
        )
    }

    private fun resolveCurveEntries(rawCurve: RawCurve, axes: List<RadarAxis>): RadarCurve {
        if (axes.isEmpty()) {
            throw IllegalArgumentException("Radar diagram must declare at least one axis before resolving curve '${rawCurve.id}'")
        }
        val hasPositional = rawCurve.entries.any { it is RawCurveEntry.Positional }
        val hasKeyed = rawCurve.entries.any { it is RawCurveEntry.Keyed }
        if (hasPositional && hasKeyed) {
            throw IllegalArgumentException("Cannot mix positional and axis-keyed entries in radar curve '${rawCurve.id}'")
        }
        if (hasPositional) {
            if (rawCurve.entries.size != axes.size) {
                throw IllegalArgumentException(
                    "Radar curve '${rawCurve.id}' has ${rawCurve.entries.size} entries, but ${axes.size} axes were declared",
                )
            }
            return RadarCurve(
                id = rawCurve.id,
                label = rawCurve.label,
                entries = rawCurve.entries.map { (it as RawCurveEntry.Positional).value },
            )
        }

        val byAxis = HashMap<String, Double>(rawCurve.entries.size)
        for (entry in rawCurve.entries) {
            val k = entry as RawCurveEntry.Keyed
            if (byAxis.put(k.axisId, k.value) != null) {
                throw IllegalArgumentException("Duplicate entry for axis '${k.axisId}' in radar curve '${rawCurve.id}'")
            }
        }
        val axisIds = axes.map { it.id }.toSet()
        for (key in byAxis.keys) {
            if (key !in axisIds) {
                throw IllegalArgumentException("Unknown axis '$key' referenced in radar curve '${rawCurve.id}'")
            }
        }
        val orderedValues = axes.map { axis ->
            byAxis[axis.id]
                ?: throw IllegalArgumentException("Missing entry for axis '${axis.label}' in radar curve '${rawCurve.id}'")
        }
        return RadarCurve(
            id = rawCurve.id,
            label = rawCurve.label,
            entries = orderedValues,
        )
    }

    private fun parseOptionStatement(opt: String, config: MutableConfig) {
        val m = Regex("""^([A-Za-z0-9_]+)(?:\s*:\s*|\s+)(.+)$""").find(opt)
            ?: throw IllegalArgumentException("Unexpected statement in radar diagram: $opt")
        val key = m.groupValues[1].lowercase()
        val valStr = unquote(m.groupValues[2].trim())
        when (key) {
            "showlegend", "ticks", "max", "min", "graticule" ->
                applyConfigEntry(config, key, valStr, strictOption = true, onTitle = {})
            else ->
                throw IllegalArgumentException("Unknown radar option '$key' in: $opt")
        }
    }

    private fun applyConfigEntry(
        config: MutableConfig,
        key: String,
        value: String,
        strictOption: Boolean = false,
        onTitle: (String) -> Unit,
    ) {
        val cScaleMatch = Regex("""^cscale(\d+)$""").find(key)
        if (cScaleMatch != null) {
            val idx = cScaleMatch.groupValues[1].toIntOrNull()
            if (idx != null && idx in 0..23 && value.isNotBlank()) {
                config.customPalette[idx] = value.trim()
            }
            return
        }
        when (key) {
            "title" -> onTitle(value)
            "showlegend" -> {
                config.showLegend = when (value.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Invalid radar showLegend '$value' (expected true or false)")
                }
            }
            "ticks" -> {
                val v = value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid radar ticks '$value'")
                if (v < 1) {
                    throw IllegalArgumentException("Radar ticks must be >= 1, got $v")
                }
                config.ticks = v.coerceAtMost(MAX_TICKS)
            }
            "max" -> {
                val v = value.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid radar max '$value'")
                if (!v.isFinite()) {
                    throw IllegalArgumentException("Radar max must be finite, got $value")
                }
                config.max = v
            }
            "min" -> {
                val v = value.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid radar min '$value'")
                if (!v.isFinite()) {
                    throw IllegalArgumentException("Radar min must be finite, got $value")
                }
                config.min = v
            }
            "graticule" -> {
                config.graticule = when (value.lowercase()) {
                    "circle" -> RadarGraticule.Circle
                    "polygon" -> RadarGraticule.Polygon
                    else -> throw IllegalArgumentException("Invalid radar graticule '$value' (expected circle or polygon)")
                }
            }
            "width" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar width '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Radar width must be > 0, got $value")
                config.width = v
            }
            "height" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar height '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Radar height must be > 0, got $value")
                config.height = v
            }
            "margintop" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar marginTop '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Radar marginTop must be >= 0, got $value")
                config.marginTop = v
            }
            "marginright" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar marginRight '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Radar marginRight must be >= 0, got $value")
                config.marginRight = v
            }
            "marginbottom" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar marginBottom '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Radar marginBottom must be >= 0, got $value")
                config.marginBottom = v
            }
            "marginleft" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar marginLeft '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Radar marginLeft must be >= 0, got $value")
                config.marginLeft = v
            }
            "axisscalefactor" -> {
                val v = value.toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar axisScaleFactor '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Radar axisScaleFactor must be > 0, got $value")
                config.axisScaleFactor = v
            }
            "axislabelFactor", "axislabelfactor" -> {
                val v = value.toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar axisLabelFactor '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Radar axisLabelFactor must be > 0, got $value")
                config.axisLabelFactor = v
            }
            "curvetension" -> {
                val v = value.toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar curveTension '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Radar curveTension must be >= 0, got $value")
                config.curveTension = v
            }
            "curveopacity" -> {
                val v = value.toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar curveOpacity '$value'")
                if (!v.isFinite() || v < 0f || v > 1f) {
                    throw IllegalArgumentException("Radar curveOpacity must be in [0, 1], got $value")
                }
                config.curveOpacity = v
            }
            "curvestrokewidth" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid radar curveStrokeWidth '$value'")
                if (!v.isFinite() || v < 0f) {
                    throw IllegalArgumentException("Radar curveStrokeWidth must be >= 0, got $value")
                }
                config.curveStrokeWidth = v
            }
            else -> if (strictOption) {
                throw IllegalArgumentException("Unknown radar option '$key'")
            }
        }
    }

    private fun parseInitDirective(
        initDirective: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        for (blockMatch in Regex(
            """['"]?(?:radar|themeVariables)['"]?\s*:\s*\{([^{}]*)\}""",
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
            """['"]?([A-Za-z0-9_]+)['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([A-Za-z0-9_.+#-]+))""",
        )
        for (m in pairRegex.findAll(segment)) {
            val key = m.groupValues[1].lowercase()
            val rawVal = when {
                m.groups[2] != null -> m.groupValues[2]
                m.groups[3] != null -> m.groupValues[3]
                else -> m.groupValues[4]
            }.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\").trim()
            applyConfigEntry(config, key, rawVal, onTitle = onTitle)
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

    private fun mergeLogicalLines(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var activeQuote: Char? = null
        var braceDepth = 0
        var bracketDepth = 0
        var inAccBlock = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (activeQuote == null && braceDepth == 0 && bracketDepth == 0) {
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
            if (activeQuote == null && braceDepth == 0 && bracketDepth == 0) {
                if (clean.trim().isEmpty()) continue
                current.setLength(0)
                current.append(clean)
            } else if (clean.trim().isNotEmpty()) {
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
                } else if (activeQuote == null) {
                    when (c) {
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth-- else throw IllegalArgumentException("Unexpected '}' in radar diagram")
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth-- else throw IllegalArgumentException("Unexpected ']' in radar diagram")
                    }
                }
                i++
            }
            if (activeQuote == null && braceDepth == 0 && bracketDepth == 0 && current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
        }
        if (activeQuote != null) {
            throw IllegalArgumentException("Unclosed quote in radar diagram")
        }
        if (braceDepth > 0) {
            throw IllegalArgumentException("Unclosed '{' in radar diagram")
        }
        if (bracketDepth > 0) {
            throw IllegalArgumentException("Unclosed '[' in radar diagram")
        }
        return out
    }

    private fun isQuoteOpeningContext(s: String, index: Int): Boolean {
        var j = index - 1
        while (j >= 0 && (s[j] == ' ' || s[j] == '\t')) {
            j--
        }
        if (j < 0 || s[j] == '\n' || s[j] == '\r' || s[j] == ':' || s[j] == '[' || s[j] == ',' || s[j] == '{') {
            return true
        }
        if (j >= 4) {
            val word = s.substring(j - 4, j + 1)
            val beforeWord = if (j >= 5) s[j - 5] else ' '
            if (word.equals("title", ignoreCase = true) && (beforeWord.isWhitespace() || beforeWord == ';' || beforeWord == ':')) {
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

    private fun splitTopLevelComma(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var activeQuote: Char? = null
        var braceDepth = 0
        var bracketDepth = 0
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
            if (activeQuote == null) {
                when (c) {
                    '{' -> braceDepth++
                    '}' -> if (braceDepth > 0) braceDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    ',' -> if (braceDepth == 0 && bracketDepth == 0) {
                        out += sb.toString()
                        sb.setLength(0)
                        i++
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        out += sb.toString()
        return out
    }

    private fun splitSemicolonOutsideQuotesAndGroups(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var activeQuote: Char? = null
        var braceDepth = 0
        var bracketDepth = 0
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
            if (activeQuote == null) {
                when (c) {
                    '{' -> braceDepth++
                    '}' -> if (braceDepth > 0) braceDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    ';' -> if (braceDepth == 0 && bracketDepth == 0) {
                        out += sb.toString()
                        sb.setLength(0)
                        i++
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        out += sb.toString()
        return out
    }

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

    private fun findLastCharOutsideQuotes(s: String, target: Char): Int {
        var activeQuote: Char? = null
        var lastIdx = -1
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
            if (activeQuote == null && c == target) lastIdx = i
            i++
        }
        return lastIdx
    }

    private fun normalizeId(s: String): String = normalizeText(s)

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
