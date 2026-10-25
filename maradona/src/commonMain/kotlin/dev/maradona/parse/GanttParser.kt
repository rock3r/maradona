package dev.maradona.parse

import dev.maradona.model.GanttDiagram
import dev.maradona.model.GanttTask
import dev.maradona.model.GanttTaskStatus
import dev.maradona.model.GanttWeekendStart
import dev.maradona.model.NodeAction
import kotlin.math.max
import kotlin.math.min

/**
 * Hand-written parser and timeline resolver for Mermaid `gantt` diagrams.
 *
 * Supports:
 * - `gantt` header, YAML frontmatter (`title`, `displayMode: compact`, `topAxis`), `%%{init}%%` directives
 * - `title`, `accTitle`, `accDescr`, `dateFormat`, `axisFormat`, `tickInterval`, `weekday`, `weekend`,
 *   `excludes` (`weekends`, weekday names, and `YYYY-MM-DD` dates), `todayMarker`, `inclusiveEndDates`, `topAxis`
 * - `section` grouping
 * - Task status/type tags (`done`, `active`, `crit`, `milestone`, `vert`) in any order
 * - Explicit and auto-generated task IDs
 * - Start specifications: explicit timestamps/dates, `after <id1> [id2 ...]`, or implicit sequential start
 * - End specifications: explicit timestamps/dates, durations (`30d`, `20h`, `2m`, `1w`, `1s`), or `until <id1> [id2 ...]`
 * - Forward references in `after` and `until` with cycle and invalid-reference detection
 */
object GanttParser {

    internal const val DAY_MS: Long = 86_400_000L
    private const val HOUR_MS: Long = 3_600_000L
    private const val MINUTE_MS: Long = 60_000L
    private const val SECOND_MS: Long = 1_000L

    private val TAG_KEYWORDS = setOf("done", "active", "crit", "milestone", "vert")

    internal val WEEKDAY_NUMBERS = mapOf(
        "sunday" to 0,
        "sun" to 0,
        "monday" to 1,
        "mon" to 1,
        "tuesday" to 2,
        "tue" to 2,
        "wednesday" to 3,
        "wed" to 3,
        "thursday" to 4,
        "thu" to 4,
        "friday" to 5,
        "fri" to 5,
        "saturday" to 6,
        "sat" to 6,
    )

    private val SHORT_MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    private val FULL_MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private val SHORT_WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val FULL_WEEKDAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    private data class PendingTaskSpec(
        val explicitId: String?,
        val label: String,
        val section: String?,
        val status: GanttTaskStatus,
        val isCritical: Boolean,
        val isMilestone: Boolean,
        val isVerticalMarker: Boolean,
        val startSpec: String?,
        val endSpec: String?,
    )

    private data class RawTaskSpec(
        val id: String,
        val label: String,
        val section: String?,
        val status: GanttTaskStatus,
        val isCritical: Boolean,
        val isMilestone: Boolean,
        val isVerticalMarker: Boolean,
        val startSpec: String?,
        val endSpec: String?,
        val prevTaskId: String?,
    )

    fun parse(source: String): GanttDiagram {
        var frontmatterTitle: String? = null
        var compact = false
        var topAxis = false

        val initMatch = Regex("""%%\{.*?\}%%""", setOf(RegexOption.DOT_MATCHES_ALL)).find(source)
        if (initMatch != null) {
            val initBody = initMatch.value
            if (Regex("""['"]?displayMode['"]?\s*:\s*['"]?compact['"]?""", RegexOption.IGNORE_CASE).containsMatchIn(initBody)) {
                compact = true
            }
            if (Regex("""['"]?topAxis['"]?\s*:\s*['"]?true['"]?""", RegexOption.IGNORE_CASE).containsMatchIn(initBody)) {
                topAxis = true
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
                val cleanFm = stripYamlCommentWithQuoteState(rawFm, activeFmQuote)
                activeFmQuote = cleanFm.second
                val fmLine = cleanFm.first.trim()
                if (fmLine.isEmpty()) continue
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    val value = unquote(fmLine.substring(colon + 1).trim())
                    when (key) {
                        "title" -> if (value.isNotEmpty()) frontmatterTitle = value
                        "displaymode" -> if (value.equals("compact", ignoreCase = true)) compact = true
                        "topaxis" -> if (value.equals("true", ignoreCase = true)) topAxis = true
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

        var dateFormat = "YYYY-MM-DD"
        var axisFormat: String? = null
        var tickInterval: String? = null
        var weekday: String? = null
        var weekend = GanttWeekendStart.Saturday
        val excludes = mutableListOf<String>()
        var inclusiveEndDates = false
        var todayMarker: String? = null

        val sections = mutableListOf<String>()
        var currentSection: String? = null
        val pendingTasks = mutableListOf<PendingTaskSpec>()
        val taskActions = HashMap<String, NodeAction>()

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
                if (lower == "gantt" || lower.startsWith("gantt ")) {
                    headerSeen = true
                    continue
                }
                headerSeen = true
            }

            when {
                lower.startsWith("title ") || lower.startsWith("title:") -> {
                    chartTitle = unquote(clean.substring(6).trim())
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
                lower.startsWith("dateformat ") -> {
                    dateFormat = unquote(clean.substring("dateFormat ".length).trim()).ifEmpty { "YYYY-MM-DD" }
                    continue
                }
                lower.startsWith("axisformat ") -> {
                    axisFormat = unquote(clean.substring("axisFormat ".length).trim())
                    continue
                }
                lower.startsWith("tickinterval ") -> {
                    tickInterval = unquote(clean.substring("tickInterval ".length).trim())
                    continue
                }
                lower.startsWith("weekday ") -> {
                    weekday = unquote(clean.substring("weekday ".length).trim()).lowercase()
                    continue
                }
                lower.startsWith("weekend ") -> {
                    val wk = unquote(clean.substring("weekend ".length).trim()).lowercase()
                    weekend = if (wk.startsWith("fri")) GanttWeekendStart.Friday else GanttWeekendStart.Saturday
                    continue
                }
                lower.startsWith("excludes ") -> {
                    val items = clean.substring("excludes ".length)
                        .split(Regex("[\\s,]+"))
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                    excludes += items
                    continue
                }
                lower.startsWith("todaymarker ") -> {
                    todayMarker = clean.substring("todayMarker ".length).trim()
                    continue
                }
                lower == "inclusiveenddates" -> {
                    inclusiveEndDates = true
                    continue
                }
                lower == "topaxis" -> {
                    topAxis = true
                    continue
                }
                lower.startsWith("displaymode ") -> {
                    if (clean.substring("displayMode ".length).trim().equals("compact", ignoreCase = true)) {
                        compact = true
                    }
                    continue
                }
                lower.startsWith("section ") -> {
                    val sec = unquote(clean.substring("section ".length).trim())
                    currentSection = sec
                    if (sec.isNotEmpty() && sec !in sections) {
                        sections += sec
                    }
                    continue
                }
                lower.startsWith("click ") -> {
                    ClickDirectiveParser.parseClick(clean.substring("click ".length).trim())?.let { parsed ->
                        for (id in parsed.nodeIds) {
                            taskActions[id] = taskActions[id]?.merge(parsed.action) ?: parsed.action
                        }
                    }
                    continue
                }
            }

            val colonIdx = clean.indexOf(':')
            if (colonIdx > 0) {
                val label = unquote(clean.substring(0, colonIdx).trim())
                val meta = clean.substring(colonIdx + 1).trim()
                val tokens = meta.split(',').map { it.trim() }.filter { it.isNotEmpty() }

                var status = GanttTaskStatus.Default
                var isCritical = false
                var isMilestone = false
                var isVerticalMarker = false
                var tIdx = 0
                while (tIdx < tokens.size && tokens[tIdx].lowercase() in TAG_KEYWORDS) {
                    when (tokens[tIdx].lowercase()) {
                        "done" -> status = GanttTaskStatus.Done
                        "active" -> status = GanttTaskStatus.Active
                        "crit" -> isCritical = true
                        "milestone" -> isMilestone = true
                        "vert" -> isVerticalMarker = true
                    }
                    tIdx++
                }

                val rem = tokens.subList(tIdx, tokens.size)
                var explicitId: String? = null
                var startSpec: String? = null
                var endSpec: String? = null

                when (rem.size) {
                    0 -> Unit
                    1 -> {
                        val tok = rem[0]
                        when {
                            isAfterSpec(tok) || parseTimestamp(tok, dateFormat) != null -> startSpec = tok
                            isUntilSpec(tok) || parseDurationMs(tok) != null -> endSpec = tok
                            else -> explicitId = tok
                        }
                    }
                    2 -> {
                        val first = rem[0]
                        val second = rem[1]
                        if (isAfterSpec(first) || parseTimestamp(first, dateFormat) != null) {
                            startSpec = first
                            endSpec = second
                        } else {
                            explicitId = first
                            endSpec = second
                        }
                    }
                    else -> {
                        explicitId = rem[0]
                        startSpec = rem[1]
                        endSpec = rem[2]
                    }
                }

                pendingTasks += PendingTaskSpec(
                    explicitId = explicitId?.trim()?.takeIf { it.isNotEmpty() },
                    label = label,
                    section = currentSection,
                    status = status,
                    isCritical = isCritical,
                    isMilestone = isMilestone,
                    isVerticalMarker = isVerticalMarker,
                    startSpec = startSpec,
                    endSpec = endSpec,
                )
            }
        }

        // First reserve all explicit IDs so auto-generated `taskN` IDs never collide with a later explicit `task1`.
        val usedIds = HashSet<String>()
        var autoCounter = 0
        val allocatedIds = ArrayList<String>(pendingTasks.size)
        for (pt in pendingTasks) {
            val exp = pt.explicitId
            if (exp != null) {
                if (usedIds.add(exp)) {
                    allocatedIds += exp
                } else {
                    var suffix = 2
                    while (!usedIds.add("${exp}_$suffix")) suffix++
                    allocatedIds += "${exp}_$suffix"
                }
            } else {
                allocatedIds += ""
            }
        }
        for (i in pendingTasks.indices) {
            if (allocatedIds[i].isEmpty()) {
                var candidate: String
                do {
                    candidate = "task${++autoCounter}"
                } while (!usedIds.add(candidate))
                allocatedIds[i] = candidate
            }
        }

        val rawTasks = ArrayList<RawTaskSpec>(pendingTasks.size)
        var lastRegularTaskId: String? = null
        for ((i, pt) in pendingTasks.withIndex()) {
            val id = allocatedIds[i]
            rawTasks += RawTaskSpec(
                id = id,
                label = pt.label,
                section = pt.section,
                status = pt.status,
                isCritical = pt.isCritical,
                isMilestone = pt.isMilestone,
                isVerticalMarker = pt.isVerticalMarker,
                startSpec = pt.startSpec,
                endSpec = pt.endSpec,
                prevTaskId = lastRegularTaskId,
            )
            if (!pt.isVerticalMarker) {
                lastRegularTaskId = id
            }
        }

        val resolvedTasks = resolveTasks(
            rawTasks = rawTasks,
            dateFormat = dateFormat,
            excludes = excludes.distinct(),
            weekend = weekend,
            inclusiveEndDates = inclusiveEndDates,
        ).map { task ->
            val act = taskActions[task.id]
            if (act != null) task.copy(action = act) else task
        }

        return GanttDiagram(
            tasks = resolvedTasks,
            sections = sections,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
            dateFormat = dateFormat,
            axisFormat = axisFormat,
            tickInterval = tickInterval,
            weekday = weekday,
            weekend = weekend,
            excludes = excludes.distinct(),
            inclusiveEndDates = inclusiveEndDates,
            topAxis = topAxis,
            compact = compact,
            todayMarker = todayMarker,
        )
    }

    private fun resolveTasks(
        rawTasks: List<RawTaskSpec>,
        dateFormat: String,
        excludes: List<String>,
        weekend: GanttWeekendStart,
        inclusiveEndDates: Boolean,
    ): List<GanttTask> {
        if (rawTasks.isEmpty()) return emptyList()

        val knownIds = rawTasks.mapTo(HashSet()) { it.id }

        // Validate explicit timestamps and dependency references upfront.
        for (t in rawTasks) {
            val sSpec = t.startSpec
            if (sSpec != null) {
                if (isAfterSpec(sSpec)) {
                    val refIds = sSpec.substring(5).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val unknown = refIds.firstOrNull { it !in knownIds }
                    if (unknown != null) {
                        throw IllegalArgumentException("Unknown Gantt task '$unknown' referenced in 'after' of '${t.id}'")
                    }
                } else if (parseTimestamp(sSpec, dateFormat) == null) {
                    throw IllegalArgumentException("Invalid Gantt start timestamp '$sSpec' for dateFormat '$dateFormat'")
                }
            }
            val eSpec = t.endSpec
            if (eSpec != null) {
                if (isUntilSpec(eSpec)) {
                    val refIds = eSpec.substring(5).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val unknown = refIds.firstOrNull { it !in knownIds }
                    if (unknown != null) {
                        throw IllegalArgumentException("Unknown Gantt task '$unknown' referenced in 'until' of '${t.id}'")
                    }
                } else if (parseDurationMs(eSpec) == null && parseTimestamp(eSpec, dateFormat) == null) {
                    throw IllegalArgumentException("Invalid Gantt end/duration '$eSpec' for dateFormat '$dateFormat'")
                }
            }
        }

        val defaultBaseMs = rawTasks.firstNotNullOfOrNull { t ->
            t.startSpec?.takeIf { !isAfterSpec(it) }?.let { parseTimestamp(it, dateFormat) }
        } ?: defaultEpochForFormat(dateFormat)

        val defaultStepMs = defaultStepForFormat(dateFormat)
        val useCalendarExcludes = excludes.isNotEmpty() && isCalendarDateFormat(dateFormat)

        val startById = HashMap<String, Long>(rawTasks.size)
        val endById = HashMap<String, Long>(rawTasks.size)

        val maxPasses = rawTasks.size * 2 + 2
        for (pass in 0 until maxPasses) {
            var progressed = false
            for (t in rawTasks) {
                if (t.id !in startById) {
                    val rawStart: Long? = when {
                        t.startSpec == null -> {
                            if (t.prevTaskId != null) endById[t.prevTaskId] else defaultBaseMs
                        }
                        isAfterSpec(t.startSpec) -> {
                            val refIds = t.startSpec.substring(5).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                            if (refIds.all { it in endById }) {
                                refIds.maxOf { endById.getValue(it) }
                            } else {
                                null
                            }
                        }
                        else -> parseTimestamp(t.startSpec, dateFormat)
                    }
                    if (rawStart != null) {
                        val snappedStart = if (useCalendarExcludes && !t.isMilestone && !t.isVerticalMarker) {
                            snapToWorkingDay(rawStart, excludes, weekend)
                        } else {
                            rawStart
                        }
                        startById[t.id] = snappedStart
                        progressed = true
                    }
                }

                val snappedStart = startById[t.id]
                if (snappedStart != null && t.id !in endById) {
                    val computedEnd: Long? = when {
                        t.endSpec == null -> {
                            if (t.isMilestone || t.isVerticalMarker) snappedStart else snappedStart + defaultStepMs
                        }
                        isUntilSpec(t.endSpec) -> {
                            val refIds = t.endSpec.substring(5).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                            if (refIds.all { it in startById }) {
                                max(snappedStart, refIds.minOf { startById.getValue(it) })
                            } else {
                                null
                            }
                        }
                        else -> {
                            val dur = parseDurationMs(t.endSpec)
                            if (dur != null) {
                                if (useCalendarExcludes && dur > 0L && !t.isMilestone && !t.isVerticalMarker) {
                                    addWorkingDuration(snappedStart, dur, excludes, weekend)
                                } else {
                                    snappedStart + dur
                                }
                            } else {
                                val explicitEnd = parseTimestamp(t.endSpec, dateFormat)!!
                                val adjusted = if (inclusiveEndDates && isCalendarDateFormat(dateFormat)) {
                                    explicitEnd + DAY_MS
                                } else {
                                    explicitEnd
                                }
                                max(snappedStart, adjusted)
                            }
                        }
                    }
                    if (computedEnd != null) {
                        endById[t.id] = computedEnd
                        progressed = true
                    }
                }
            }
            if (startById.size == rawTasks.size && endById.size == rawTasks.size) break
            if (!progressed) break
        }

        val unresolved = rawTasks.firstOrNull { it.id !in startById || it.id !in endById }
        if (unresolved != null) {
            throw IllegalArgumentException("Cyclic or unresolved Gantt task dependency involving '${unresolved.id}'")
        }

        return rawTasks.map { t ->
            GanttTask(
                id = t.id,
                label = t.label,
                section = t.section,
                status = t.status,
                isCritical = t.isCritical,
                isMilestone = t.isMilestone,
                isVerticalMarker = t.isVerticalMarker,
                startMs = startById.getValue(t.id),
                endMs = endById.getValue(t.id),
            )
        }
    }

    private fun isAfterSpec(s: String): Boolean {
        val t = s.trim()
        return t.length > 6 && t.regionMatches(0, "after ", 0, 6, ignoreCase = true)
    }

    private fun isUntilSpec(s: String): Boolean {
        val t = s.trim()
        return t.length > 6 && t.regionMatches(0, "until ", 0, 6, ignoreCase = true)
    }

    internal fun parseDurationMs(spec: String): Long? {
        val m = Regex(
            """^(\d+(?:\.\d+)?)\s*(ms|s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks|M|mon|month|months|y|yr|yrs|year|years)$""",
        ).matchEntire(spec.trim()) ?: Regex(
            """^(\d+(?:\.\d+)?)\s*(ms|s|sec|secs|second|seconds|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks|mon|month|months|y|yr|yrs|year|years)$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(spec.trim()) ?: return null
        val amount = m.groupValues[1].toDoubleOrNull() ?: return null
        val rawUnit = m.groupValues[2]
        if (rawUnit == "M") return (amount * 30.0 * DAY_MS).toLong()
        return when (rawUnit.lowercase()) {
            "ms" -> amount.toLong()
            "s", "sec", "secs", "second", "seconds" -> (amount * SECOND_MS).toLong()
            "m", "min", "mins", "minute", "minutes" -> (amount * MINUTE_MS).toLong()
            "h", "hr", "hrs", "hour", "hours" -> (amount * HOUR_MS).toLong()
            "d", "day", "days" -> (amount * DAY_MS).toLong()
            "w", "wk", "wks", "week", "weeks" -> (amount * 7.0 * DAY_MS).toLong()
            "mon", "month", "months" -> (amount * 30.0 * DAY_MS).toLong()
            "y", "yr", "yrs", "year", "years" -> (amount * 365.0 * DAY_MS).toLong()
            else -> null
        }
    }

    private enum class DateToken {
        YEAR4, YEAR2, MONTH, DAY, HOUR, MINUTE, SECOND, MILLIS,
    }

    internal fun parseTimestamp(spec: String, dateFormat: String): Long? {
        val s = spec.trim()
        if (s.isEmpty() || isAfterSpec(s) || isUntilSpec(s) || parseDurationMs(s) != null) return null
        val fmt = dateFormat.trim()

        if (fmt == "X") {
            return s.toDoubleOrNull()?.let { (it * 1000.0).toLong() }
        }
        if (fmt == "x") {
            return s.toLongOrNull()
        }

        // Format-driven parser supporting arbitrary Mermaid dateFormat strings (e.g. YYYYMMDD, YYYY-MM-DD HH:mm:ss.SSS, DD/MM/YYYY, HH:mm).
        val parsedByFormat = parseWithFormatPattern(s, fmt)
        if (parsedByFormat != null) return parsedByFormat

        // Fallback to standard ISO YYYY-MM-DD [HH:mm[:ss]] when format is default YYYY-MM-DD but task includes time.
        val iso = Regex("""^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[T\s]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$""").matchEntire(s)
        if (iso != null) {
            val y = iso.groupValues[1].toInt()
            val m = iso.groupValues[2].toInt()
            val d = iso.groupValues[3].toInt()
            if (m !in 1..12 || d !in 1..31) return null
            val hh = iso.groupValues[4].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val mm = iso.groupValues[5].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val ss = iso.groupValues[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            return daysFromCivil(y, m, d) * DAY_MS + (hh * 3600L + mm * 60L + ss) * 1000L
        }

        return null
    }

    private fun parseWithFormatPattern(input: String, fmt: String): Long? {
        val tokenOrder = mutableListOf<DateToken>()
        val pattern = StringBuilder("^")
        var i = 0
        while (i < fmt.length) {
            val nextStartsWithToken = fun(afterIdx: Int): Boolean {
                if (afterIdx >= fmt.length) return false
                val c = fmt[afterIdx]
                return c in "YMDHmsS"
            }
            when {
                fmt.startsWith("YYYY", i) -> {
                    tokenOrder += DateToken.YEAR4
                    pattern.append("(\\d{4})")
                    i += 4
                }
                fmt.startsWith("YY", i) -> {
                    tokenOrder += DateToken.YEAR2
                    pattern.append("(\\d{2})")
                    i += 2
                }
                fmt.startsWith("MM", i) -> {
                    tokenOrder += DateToken.MONTH
                    pattern.append(if (nextStartsWithToken(i + 2)) "(\\d{2})" else "(\\d{1,2})")
                    i += 2
                }
                fmt.startsWith("M", i) -> {
                    tokenOrder += DateToken.MONTH
                    pattern.append("(\\d{1,2})")
                    i += 1
                }
                fmt.startsWith("DD", i) -> {
                    tokenOrder += DateToken.DAY
                    pattern.append(if (nextStartsWithToken(i + 2)) "(\\d{2})" else "(\\d{1,2})")
                    i += 2
                }
                fmt.startsWith("D", i) -> {
                    tokenOrder += DateToken.DAY
                    pattern.append("(\\d{1,2})")
                    i += 1
                }
                fmt.startsWith("HH", i) || fmt.startsWith("hh", i) -> {
                    tokenOrder += DateToken.HOUR
                    pattern.append(if (nextStartsWithToken(i + 2)) "(\\d{2})" else "(\\d{1,2})")
                    i += 2
                }
                fmt.startsWith("H", i) || fmt.startsWith("h", i) -> {
                    tokenOrder += DateToken.HOUR
                    pattern.append("(\\d{1,2})")
                    i += 1
                }
                fmt.startsWith("mm", i) -> {
                    tokenOrder += DateToken.MINUTE
                    pattern.append(if (nextStartsWithToken(i + 2)) "(\\d{2})" else "(\\d{1,2})")
                    i += 2
                }
                fmt.startsWith("m", i) -> {
                    tokenOrder += DateToken.MINUTE
                    pattern.append("(\\d{1,2})")
                    i += 1
                }
                fmt.startsWith("SSS", i) -> {
                    tokenOrder += DateToken.MILLIS
                    pattern.append("(\\d{1,3})")
                    i += 3
                }
                fmt.startsWith("ss", i) -> {
                    tokenOrder += DateToken.SECOND
                    pattern.append(if (nextStartsWithToken(i + 2)) "(\\d{2})" else "(\\d{1,2})")
                    i += 2
                }
                fmt.startsWith("s", i) -> {
                    tokenOrder += DateToken.SECOND
                    pattern.append("(\\d{1,2})")
                    i += 1
                }
                fmt[i].isWhitespace() -> {
                    pattern.append("[T\\s]+")
                    while (i < fmt.length && fmt[i].isWhitespace()) i++
                }
                fmt[i] == '-' || fmt[i] == '/' -> {
                    pattern.append("[-/]")
                    i++
                }
                else -> {
                    pattern.append(Regex.escape(fmt[i].toString()))
                    i++
                }
            }
        }
        if (tokenOrder.isEmpty()) return null
        pattern.append("$")
        val match = Regex(pattern.toString()).matchEntire(input) ?: return null

        var year: Int? = null
        var month = 1
        var day = 1
        var hour = 0
        var minute = 0
        var second = 0
        var millis = 0
        var hasDate = false

        for ((idx, tok) in tokenOrder.withIndex()) {
            val raw = match.groupValues[idx + 1]
            val v = raw.toIntOrNull() ?: return null
            when (tok) {
                DateToken.YEAR4 -> {
                    year = v
                    hasDate = true
                }
                DateToken.YEAR2 -> {
                    year = if (v >= 70) 1900 + v else 2000 + v
                    hasDate = true
                }
                DateToken.MONTH -> {
                    if (v !in 1..12) return null
                    month = v
                    hasDate = true
                }
                DateToken.DAY -> {
                    if (v !in 1..31) return null
                    day = v
                    hasDate = true
                }
                DateToken.HOUR -> {
                    if (v !in 0..24) return null
                    hour = v
                }
                DateToken.MINUTE -> {
                    if (v !in 0..59) return null
                    minute = v
                }
                DateToken.SECOND -> {
                    if (v !in 0..59) return null
                    second = v
                }
                DateToken.MILLIS -> {
                    millis = raw.padEnd(3, '0').toInt()
                }
            }
        }

        val timeMs = (hour * 3600L + minute * 60L + second) * 1000L + millis
        return if (hasDate) {
            daysFromCivil(year ?: 2024, month, day) * DAY_MS + timeMs
        } else {
            timeMs
        }
    }

    internal fun isCalendarDateFormat(dateFormat: String): Boolean {
        val fmt = dateFormat.trim()
        return fmt != "X" && fmt != "x" && !fmt.startsWith("HH:mm", ignoreCase = true) && !fmt.equals("YYYY", ignoreCase = true)
    }

    private fun defaultEpochForFormat(dateFormat: String): Long {
        val fmt = dateFormat.trim()
        return when {
            fmt == "X" || fmt == "x" || fmt.startsWith("HH:mm", ignoreCase = true) -> 0L
            fmt.equals("YYYY", ignoreCase = true) -> daysFromCivil(2000, 1, 1) * DAY_MS
            else -> daysFromCivil(2024, 1, 1) * DAY_MS
        }
    }

    private fun defaultStepForFormat(dateFormat: String): Long {
        val fmt = dateFormat.trim()
        return when {
            fmt == "X" || fmt == "x" -> 1000L
            fmt.startsWith("HH:mm", ignoreCase = true) -> 15 * MINUTE_MS
            fmt.equals("YYYY", ignoreCase = true) -> 365 * DAY_MS
            else -> DAY_MS
        }
    }

    private fun snapToWorkingDay(ms: Long, excludes: List<String>, weekend: GanttWeekendStart): Long {
        var dayIdx = ms.floorDiv(DAY_MS)
        val timeOfDay = ms - dayIdx * DAY_MS
        var guard = 0
        while (guard < 366 && isExcludedDay(dayIdx, excludes, weekend)) {
            dayIdx++
            guard++
        }
        return dayIdx * DAY_MS + timeOfDay
    }

    /**
     * Advances [startMs] by [durationMs] across working (non-excluded) calendar intervals,
     * properly skipping excluded days for both multi-day, sub-day, and fractional-day durations.
     */
    private fun addWorkingDuration(
        startMs: Long,
        durationMs: Long,
        excludes: List<String>,
        weekend: GanttWeekendStart,
    ): Long {
        var curMs = snapToWorkingDay(startMs, excludes, weekend)
        var remainingMs = durationMs
        var stepGuard = 0
        while (remainingMs > 0L && stepGuard < 20_000) {
            stepGuard++
            val curDay = curMs.floorDiv(DAY_MS)
            if (isExcludedDay(curDay, excludes, weekend)) {
                curMs = (curDay + 1L) * DAY_MS
                continue
            }
            val dayEndMs = (curDay + 1L) * DAY_MS
            val availMs = (dayEndMs - curMs).coerceAtLeast(1L)
            if (remainingMs <= availMs) {
                curMs += remainingMs
                remainingMs = 0L
            } else {
                remainingMs -= availMs
                curMs = dayEndMs
            }
        }
        return curMs
    }

    internal fun isExcludedDay(
        dayIdx: Long,
        excludes: List<String>,
        weekend: GanttWeekendStart,
    ): Boolean {
        if (excludes.isEmpty()) return false
        val dow = dayOfWeek(dayIdx) // 0 = Sun .. 6 = Sat
        for (ex in excludes) {
            if (ex == "weekends") {
                val isWk = when (weekend) {
                    GanttWeekendStart.Saturday -> dow == 6 || dow == 0
                    GanttWeekendStart.Friday -> dow == 5 || dow == 6
                }
                if (isWk) return true
            }
            val targetDow = WEEKDAY_NUMBERS[ex]
            if (targetDow != null && dow == targetDow) return true
            if (ex.length == 10 && ex[4] == '-' && ex[7] == '-') {
                if (formatIsoDate(dayIdx) == ex) return true
            }
        }
        return false
    }

    /** Howard Hinnant's proleptic Gregorian days-since-1970-01-01. */
    internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = if (y >= 0) y / 400 else (y - 399) / 400
        val yoe = y - era * 400 // [0, 399]
        val m = month.toLong()
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + day - 1 // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
        return era * 146097 + doe - 719468
    }

    /** Inverse of [daysFromCivil]: returns Triple(year, month 1..12, day 1..31). */
    internal fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
        val z = daysSinceEpoch + 719468
        val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
        val doe = z - era * 146097 // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
        val mp = (5 * doy + 2) / 153 // [0, 11]
        val d = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
        val m = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
        val finalY = if (m <= 2) y + 1 else y
        return Triple(finalY.toInt(), m.toInt(), d.toInt())
    }

    /** 0 = Sunday, 1 = Monday, ..., 6 = Saturday. */
    internal fun dayOfWeek(daysSinceEpoch: Long): Int =
        (daysSinceEpoch + 4L).mod(7)

    internal fun formatIsoDate(daysSinceEpoch: Long): String {
        val (y, m, d) = civilFromDays(daysSinceEpoch)
        val ys = y.toString().padStart(4, '0')
        val ms = m.toString().padStart(2, '0')
        val ds = d.toString().padStart(2, '0')
        return "$ys-$ms-$ds"
    }

    internal fun formatTickLabel(ms: Long, dateFormat: String, axisFormat: String?): String {
        val fmt = dateFormat.trim()
        val ax = axisFormat?.trim()?.takeIf { it.isNotEmpty() }

        val dayIdx = ms.floorDiv(DAY_MS)
        val (y, m, d) = civilFromDays(dayIdx)
        val dow = dayOfWeek(dayIdx)
        val timeOfDayMs = (ms - dayIdx * DAY_MS).coerceAtLeast(0L)
        val hh24 = ((timeOfDayMs / HOUR_MS) % 24L).toInt()
        val mm = ((timeOfDayMs % HOUR_MS) / MINUTE_MS).toInt()
        val ss = ((timeOfDayMs % MINUTE_MS) / SECOND_MS).toInt()
        val millis = (timeOfDayMs % SECOND_MS).toInt()
        val hh12 = when {
            hh24 == 0 -> 12
            hh24 > 12 -> hh24 - 12
            else -> hh24
        }

        if (ax != null) {
            val sb = StringBuilder()
            var i = 0
            while (i < ax.length) {
                if (ax[i] == '%' && i + 1 < ax.length) {
                    when (val spec = ax[i + 1]) {
                        'Y' -> sb.append(y.toString().padStart(4, '0'))
                        'y' -> sb.append((y % 100).toString().padStart(2, '0'))
                        'm' -> sb.append(m.toString().padStart(2, '0'))
                        'b', 'h' -> sb.append(SHORT_MONTHS[(m - 1).coerceIn(0, 11)])
                        'B' -> sb.append(FULL_MONTHS[(m - 1).coerceIn(0, 11)])
                        'd' -> sb.append(d.toString().padStart(2, '0'))
                        'e' -> sb.append(d.toString())
                        'a' -> sb.append(SHORT_WEEKDAYS[dow.coerceIn(0, 6)])
                        'A' -> sb.append(FULL_WEEKDAYS[dow.coerceIn(0, 6)])
                        'H' -> sb.append(hh24.toString().padStart(2, '0'))
                        'I' -> sb.append(hh12.toString().padStart(2, '0'))
                        'p' -> sb.append(if (hh24 < 12) "AM" else "PM")
                        'M' -> sb.append(mm.toString().padStart(2, '0'))
                        'S' -> sb.append(ss.toString().padStart(2, '0'))
                        'L' -> sb.append(millis.toString().padStart(3, '0'))
                        's' -> sb.append((ms / 1000L).toString())
                        '%' -> sb.append('%')
                        else -> sb.append('%').append(spec)
                    }
                    i += 2
                } else {
                    sb.append(ax[i])
                    i++
                }
            }
            return sb.toString()
        }

        if (fmt == "X") return (ms / 1000L).toString()
        if (fmt == "x") return ms.toString()
        if (fmt.startsWith("HH:mm", ignoreCase = true)) {
            return "${hh24.toString().padStart(2, '0')}:${mm.toString().padStart(2, '0')}"
        }
        if (fmt.equals("YYYY", ignoreCase = true)) {
            return y.toString()
        }
        if (fmt.contains('H', ignoreCase = true) || fmt.contains('m')) {
            return "${formatIsoDate(dayIdx)} ${hh24.toString().padStart(2, '0')}:${mm.toString().padStart(2, '0')}"
        }
        return formatIsoDate(dayIdx)
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

    internal fun stripYamlCommentWithQuoteState(line: String, startQuoteChar: Char?): Pair<String, Char?> {
        var quoteChar = startQuoteChar
        var i = 0
        if (quoteChar == null) {
            val trimmedStart = line.indexOfFirst { !it.isWhitespace() }
            if (trimmedStart < 0) return line to null
            if (line[trimmedStart] == '#') return line.substring(0, trimmedStart) to null
            val colonIdx = line.indexOf(':', trimmedStart)
            if (colonIdx < 0) {
                var j = trimmedStart
                while (j < line.length) {
                    if (line[j] == '#' && (j == 0 || line[j - 1].isWhitespace())) {
                        return line.substring(0, j) to null
                    }
                    j++
                }
                return line to null
            }
            val valStart = (colonIdx + 1 until line.length).firstOrNull { !line[it].isWhitespace() }
                ?: return line to null
            val firstValChar = line[valStart]
            if (firstValChar == '#') {
                return line.substring(0, valStart) to null
            }
            if (firstValChar == '"' || firstValChar == '\'') {
                quoteChar = firstValChar
                i = valStart + 1
            } else {
                // Plain unquoted YAML scalar: quotes inside the value (e.g. `title: 6" screen`) do not start a quoted block.
                var j = valStart
                while (j < line.length) {
                    if (line[j] == '#' && line[j - 1].isWhitespace()) {
                        return line.substring(0, j) to null
                    }
                    j++
                }
                return line to null
            }
        }

        while (i < line.length) {
            val c = line[i]
            if (quoteChar == '"' && c == '\\' && i + 1 < line.length) {
                i += 2
                continue
            }
            if (quoteChar == '\'' && c == '\'' && i + 1 < line.length && line[i + 1] == '\'') {
                i += 2
                continue
            }
            if (quoteChar != null && c == quoteChar) {
                quoteChar = null
                i++
                while (i < line.length) {
                    if (line[i] == '#' && (i == 0 || line[i - 1].isWhitespace())) {
                        return line.substring(0, i) to null
                    }
                    i++
                }
                return line to null
            }
            i++
        }
        return line to quoteChar
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) {
            return t.substring(1, t.length - 1).trim()
        }
        return t
    }
}
