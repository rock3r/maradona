package dev.maradona.layout

import dev.maradona.model.GanttDiagram
import dev.maradona.model.GanttTask
import dev.maradona.parse.GanttParser
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class PositionedGanttSection(
    val name: String,
    val sectionIndex: Int,
    val rect: DiagramRect,
    val labelRect: DiagramRect,
)

data class PositionedGanttTask(
    val task: GanttTask,
    val sectionIndex: Int,
    val rowIndex: Int,
    val barRect: DiagramRect,
    val labelRect: DiagramRect,
    val labelInsideBar: Boolean,
)

data class PositionedGanttVert(
    val task: GanttTask,
    val x: Float,
    val topY: Float,
    val bottomY: Float,
    val labelRect: DiagramRect,
)

data class PositionedGanttTick(
    val timeMs: Long,
    val x: Float,
    val label: String,
    val bottomLabelRect: DiagramRect,
    val topLabelRect: DiagramRect?,
)

data class GanttDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val plotRect: DiagramRect,
    val sections: List<PositionedGanttSection>,
    val rowRects: List<DiagramRect>,
    val excludedBands: List<DiagramRect>,
    val tasks: List<PositionedGanttTask>,
    val verticalMarkers: List<PositionedGanttVert>,
    val ticks: List<PositionedGanttTick>,
) {
    fun task(id: String): PositionedGanttTask? = tasks.firstOrNull { it.task.id == id }
}

/**
 * Pure-Kotlin timeline layout engine for Mermaid `gantt` diagrams.
 *
 * Computes section bands, standard and `compact`-mode row packing (preventing both bar and
 * outside-label collisions), weekend/excluded-date shading bands, milestone diamonds,
 * `vert` reference lines, and non-overlapping axis ticks.
 */
object GanttLayout {

    fun layout(
        diagram: GanttDiagram,
        taskLabelSize: (GanttTask) -> DiagramSize = { t -> defaultLabelSize(t.label, 1f) },
        sectionLabelSize: (String) -> DiagramSize = { s -> defaultLabelSize(s, 1f) },
        tickLabelSize: (String) -> DiagramSize = { s -> defaultTickSize(s, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): GanttDiagramLayout {
        val d = options.density
        val outerPad = 20f * d
        val rowHeight = 30f * d
        val rowBarHeight = 20f * d
        val axisHeight = 26f * d

        val regularTasks = diagram.tasks.filter { !it.isVerticalMarker }
        val vertTasks = diagram.tasks.filter { it.isVerticalMarker }

        if (diagram.tasks.isEmpty()) {
            val emptyPlot = DiagramRect(outerPad, outerPad, 480f * d, rowHeight)
            return GanttDiagramLayout(
                width = emptyPlot.right + outerPad,
                height = emptyPlot.bottom + axisHeight + outerPad,
                titleRect = null,
                plotRect = emptyPlot,
                sections = emptyList(),
                rowRects = emptyList(),
                excludedBands = emptyList(),
                tasks = emptyList(),
                verticalMarkers = emptyList(),
                ticks = emptyList(),
            )
        }

        val allStartMs = diagram.tasks.minOf { min(it.startMs, it.endMs) }
        val rawMaxMs = diagram.tasks.maxOf { max(it.startMs, it.endMs) }
        val minSpanMs = when {
            diagram.dateFormat.trim() == "x" -> 1L
            diagram.dateFormat.trim() == "X" -> 1000L
            diagram.dateFormat.trim().startsWith("HH:mm", ignoreCase = true) -> 60_000L
            else -> GanttParser.DAY_MS
        }
        val allEndMs = max(allStartMs + minSpanMs, rawMaxMs)
        val totalSpanMs = (allEndMs - allStartMs).coerceAtLeast(1L)

        val allSectionNames = (diagram.sections + regularTasks.mapNotNull { it.section }).distinct()
        val hasSections = allSectionNames.isNotEmpty()
        val orderedSections: List<String?> = if (hasSections) {
            val explicit = allSectionNames.toMutableList<String?>()
            if (regularTasks.any { it.section == null }) {
                explicit.add(0, null)
            }
            explicit
        } else {
            listOf(null)
        }

        val sectionColWidth = if (hasSections) {
            val maxSecW = orderedSections.filterNotNull().maxOfOrNull { sectionLabelSize(it).width } ?: (72f * d)
            max(108f * d, maxSecW + 28f * d)
        } else {
            0f
        }

        val plotLeft = outerPad + sectionColWidth
        val plotInnerPadX = 16f * d
        val plotTrackWidth = max(560f * d, regularTasks.size * 38f * d).coerceAtMost(920f * d)

        fun timeToX(ms: Long): Float {
            val frac = ((ms - allStartMs).toDouble() / totalSpanMs.toDouble()).toFloat().coerceIn(0f, 1f)
            return plotLeft + plotInnerPadX + frac * plotTrackWidth
        }

        // Ensure plotWidth includes enough right padding to keep outside labels of late tasks inside plotRect.
        var maxLabelRightX = plotLeft + plotInnerPadX + plotTrackWidth
        for (t in regularTasks) {
            val probe = buildPositionedTask(
                task = t,
                sectionIndex = 0,
                rowIndex = 0,
                rowTopY = 0f,
                rowHeight = rowHeight,
                rowBarHeight = rowBarHeight,
                timeToX = ::timeToX,
                labelSize = taskLabelSize(t),
                density = d,
            )
            maxLabelRightX = max(maxLabelRightX, max(probe.barRect.right, probe.labelRect.right))
        }
        val plotRightPadX = max(plotInnerPadX, (maxLabelRightX - (plotLeft + plotInnerPadX + plotTrackWidth)) + 16f * d)
        val plotWidth = plotInnerPadX + plotTrackWidth + plotRightPadX

        val resolvedTitleSize = titleSize ?: diagram.title?.let {
            DiagramSize(max(120f * d, it.length * 9f * d), 24f * d)
        }
        var cursorY = outerPad
        val titleTopY = cursorY
        if (resolvedTitleSize != null) {
            cursorY += resolvedTitleSize.height + 12f * d
        }
        val topAxisY = if (diagram.topAxis) {
            val y = cursorY
            cursorY += axisHeight
            y
        } else {
            null
        }

        val plotTopY = cursorY

        data class CompactRowTrack(
            val globalRowIdx: Int,
            var lastEndMs: Long,
            var lastVisualRightX: Float,
            var maxRowHeight: Float,
        )

        fun requiredRowHeightForTask(t: GanttTask): Float =
            max(rowHeight, taskLabelSize(t).height + 10f * d)

        val positionedSections = mutableListOf<PositionedGanttSection>()
        val rowRects = mutableListOf<DiagramRect>()
        val positionedTasks = mutableListOf<PositionedGanttTask>()

        for ((secIdx, secName) in orderedSections.withIndex()) {
            val secTasks = regularTasks.filter { it.section == secName }
            val secStartRow = rowRects.size
            val minSecHeight = if (hasSections && secName != null) sectionLabelSize(secName).height + 10f * d else rowHeight
            if (secTasks.isEmpty()) {
                val rH = max(rowHeight, minSecHeight)
                val rRect = DiagramRect(outerPad, cursorY, sectionColWidth + plotWidth, rH)
                rowRects += rRect
                cursorY += rH
            } else if (!diagram.compact) {
                for ((tIdx, t) in secTasks.withIndex()) {
                    val rIdx = rowRects.size
                    val baseH = requiredRowHeightForTask(t)
                    val rH = if (secTasks.size == 1 && tIdx == 0) max(baseH, minSecHeight) else baseH
                    val bH = max(rowBarHeight, rH - 10f * d)
                    val rRect = DiagramRect(outerPad, cursorY, sectionColWidth + plotWidth, rH)
                    rowRects += rRect
                    positionedTasks += buildPositionedTask(
                        task = t,
                        sectionIndex = secIdx,
                        rowIndex = rIdx,
                        rowTopY = cursorY,
                        rowHeight = rH,
                        rowBarHeight = bH,
                        timeToX = ::timeToX,
                        labelSize = taskLabelSize(t),
                        density = d,
                    )
                    cursorY += rH
                }
            } else {
                // Compact mode: pack non-overlapping tasks within the section onto shared horizontal rows.
                val tracks = mutableListOf<CompactRowTrack>()
                val pendingByTask = ArrayList<Pair<GanttTask, Int>>(secTasks.size)
                for (t in secTasks) {
                    val reqH = requiredRowHeightForTask(t)
                    val tempPos = buildPositionedTask(
                        task = t,
                        sectionIndex = secIdx,
                        rowIndex = 0,
                        rowTopY = 0f,
                        rowHeight = reqH,
                        rowBarHeight = max(rowBarHeight, reqH - 10f * d),
                        timeToX = ::timeToX,
                        labelSize = taskLabelSize(t),
                        density = d,
                    )
                    val visualLeft = min(tempPos.barRect.x, tempPos.labelRect.x)
                    val visualRight = max(tempPos.barRect.right, tempPos.labelRect.right)
                    val reuseTrack = tracks.firstOrNull { tr ->
                        t.startMs >= tr.lastEndMs && visualLeft >= tr.lastVisualRightX + 10f * d
                    }
                    val assignedRow = if (reuseTrack != null) {
                        reuseTrack.lastEndMs = max(reuseTrack.lastEndMs, t.endMs)
                        reuseTrack.lastVisualRightX = max(reuseTrack.lastVisualRightX, visualRight)
                        reuseTrack.maxRowHeight = max(reuseTrack.maxRowHeight, reqH)
                        reuseTrack.globalRowIdx
                    } else {
                        val newGlobalRow = secStartRow + tracks.size
                        tracks += CompactRowTrack(
                            globalRowIdx = newGlobalRow,
                            lastEndMs = t.endMs,
                            lastVisualRightX = visualRight,
                            maxRowHeight = reqH,
                        )
                        newGlobalRow
                    }
                    pendingByTask += t to assignedRow
                }
                if (tracks.size == 1) {
                    tracks[0].maxRowHeight = max(tracks[0].maxRowHeight, minSecHeight)
                }
                for (tr in tracks) {
                    rowRects += DiagramRect(outerPad, cursorY, sectionColWidth + plotWidth, tr.maxRowHeight)
                    cursorY += tr.maxRowHeight
                }
                for ((t, assignedRow) in pendingByTask) {
                    val rRect = rowRects[assignedRow]
                    val rH = rRect.height
                    val bH = max(rowBarHeight, rH - 10f * d)
                    positionedTasks += buildPositionedTask(
                        task = t,
                        sectionIndex = secIdx,
                        rowIndex = assignedRow,
                        rowTopY = rRect.y,
                        rowHeight = rH,
                        rowBarHeight = bH,
                        timeToX = ::timeToX,
                        labelSize = taskLabelSize(t),
                        density = d,
                    )
                }
            }

            val secEndRow = rowRects.size
            if (hasSections && secName != null && secEndRow > secStartRow) {
                val topY = rowRects[secStartRow].y
                val botY = rowRects[secEndRow - 1].bottom
                val secRect = DiagramRect(outerPad, topY, sectionColWidth, botY - topY)
                val secLblSz = sectionLabelSize(secName)
                val secLblRect = DiagramRect(
                    x = secRect.x + 10f * d,
                    y = secRect.centerY - secLblSz.height / 2f,
                    width = secLblSz.width,
                    height = secLblSz.height,
                )
                positionedSections += PositionedGanttSection(
                    name = secName,
                    sectionIndex = secIdx,
                    rect = secRect,
                    labelRect = secLblRect,
                )
            }
        }

        if (rowRects.isEmpty()) {
            rowRects += DiagramRect(outerPad, cursorY, sectionColWidth + plotWidth, rowHeight)
            cursorY += rowHeight
        }

        val plotRect = DiagramRect(
            x = plotLeft,
            y = plotTopY,
            width = plotWidth,
            height = (cursorY - plotTopY).coerceAtLeast(rowHeight),
        )

        val bottomAxisY = plotRect.bottom
        cursorY = bottomAxisY + axisHeight

        // Excluded day shading bands (weekends, specific weekdays/dates).
        val excludedBands = computeExcludedBands(diagram, allStartMs, allEndMs, plotRect, ::timeToX)

        // Axis ticks.
        val ticks = computeTicks(
            diagram = diagram,
            minTimeMs = allStartMs,
            maxTimeMs = allEndMs,
            plotRect = plotRect,
            topAxisY = topAxisY,
            bottomAxisY = bottomAxisY,
            axisHeight = axisHeight,
            tickLabelSize = tickLabelSize,
            timeToX = ::timeToX,
            density = d,
        )

        // Vertical markers (`vert`).
        val positionedVerts = mutableListOf<PositionedGanttVert>()
        var vertRowBottom = cursorY
        val occupiedVertLabelRects = mutableListOf<DiagramRect>()
        for (vt in vertTasks) {
            val vx = timeToX(vt.startMs)
            val lblSz = taskLabelSize(vt)
            var lblY = bottomAxisY + axisHeight + 4f * d
            var candidate = DiagramRect(
                x = max(outerPad, vx - lblSz.width / 2f),
                y = lblY,
                width = lblSz.width,
                height = lblSz.height,
            )
            while (occupiedVertLabelRects.any { overlaps(it, candidate, 4f * d) }) {
                lblY += lblSz.height + 4f * d
                candidate = DiagramRect(
                    x = max(outerPad, vx - lblSz.width / 2f),
                    y = lblY,
                    width = lblSz.width,
                    height = lblSz.height,
                )
            }
            occupiedVertLabelRects += candidate
            vertRowBottom = max(vertRowBottom, candidate.bottom + 4f * d)
            positionedVerts += PositionedGanttVert(
                task = vt,
                x = vx,
                topY = plotRect.y,
                bottomY = plotRect.bottom,
                labelRect = candidate,
            )
        }
        cursorY = vertRowBottom

        val maxTaskRight = positionedTasks.maxOfOrNull { max(it.barRect.right, it.labelRect.right) } ?: plotRect.right
        val maxTickRight = ticks.maxOfOrNull { it.bottomLabelRect.right } ?: plotRect.right
        val maxVertRight = positionedVerts.maxOfOrNull { it.labelRect.right } ?: plotRect.right
        val titleMinWidth = resolvedTitleSize?.let { it.width + outerPad * 2f } ?: 0f
        val finalWidth = max(
            titleMinWidth,
            max(plotRect.right, max(maxTaskRight, max(maxTickRight, maxVertRight))) + outerPad,
        )
        val finalHeight = cursorY + outerPad

        val titleRect = resolvedTitleSize?.let {
            DiagramRect(
                x = max(outerPad, (finalWidth - it.width) / 2f),
                y = titleTopY,
                width = it.width,
                height = it.height,
            )
        }

        return GanttDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            titleRect = titleRect,
            plotRect = plotRect,
            sections = positionedSections,
            rowRects = rowRects,
            excludedBands = excludedBands,
            tasks = positionedTasks,
            verticalMarkers = positionedVerts,
            ticks = ticks,
        )
    }

    private fun buildPositionedTask(
        task: GanttTask,
        sectionIndex: Int,
        rowIndex: Int,
        rowTopY: Float,
        rowHeight: Float,
        rowBarHeight: Float,
        timeToX: (Long) -> Float,
        labelSize: DiagramSize,
        density: Float,
    ): PositionedGanttTask {
        val barTopY = rowTopY + (rowHeight - rowBarHeight) / 2f
        val barRect = if (task.isMilestone) {
            val midMs = (task.startMs + task.endMs) / 2L
            val cx = timeToX(midMs)
            val size = 16f * density
            DiagramRect(
                x = cx - size / 2f,
                y = rowTopY + (rowHeight - size) / 2f,
                width = size,
                height = size,
            )
        } else {
            val x0 = timeToX(task.startMs)
            val x1 = max(x0 + 8f * density, timeToX(task.endMs))
            DiagramRect(
                x = x0,
                y = barTopY,
                width = x1 - x0,
                height = rowBarHeight,
            )
        }

        val insideFits = !task.isMilestone && barRect.width >= labelSize.width + 14f * density
        val labelRect = if (insideFits) {
            DiagramRect(
                x = barRect.centerX - labelSize.width / 2f,
                y = barRect.centerY - labelSize.height / 2f,
                width = labelSize.width,
                height = labelSize.height,
            )
        } else {
            DiagramRect(
                x = barRect.right + 8f * density,
                y = rowTopY + (rowHeight - labelSize.height) / 2f,
                width = labelSize.width,
                height = labelSize.height,
            )
        }

        return PositionedGanttTask(
            task = task,
            sectionIndex = sectionIndex,
            rowIndex = rowIndex,
            barRect = barRect,
            labelRect = labelRect,
            labelInsideBar = insideFits,
        )
    }

    private fun computeExcludedBands(
        diagram: GanttDiagram,
        minTimeMs: Long,
        maxTimeMs: Long,
        plotRect: DiagramRect,
        timeToX: (Long) -> Float,
    ): List<DiagramRect> {
        if (diagram.excludes.isEmpty() || !GanttParser.isCalendarDateFormat(diagram.dateFormat)) {
            return emptyList()
        }
        val startDay = minTimeMs.floorDiv(GanttParser.DAY_MS)
        val endDay = ceil(maxTimeMs.toDouble() / GanttParser.DAY_MS.toDouble()).toLong()
        if (endDay - startDay > 400L) return emptyList()

        val bands = mutableListOf<DiagramRect>()
        var runStartDay: Long? = null
        for (day in startDay..endDay) {
            val excluded = day < endDay && GanttParser.isExcludedDay(day, diagram.excludes, diagram.weekend)
            if (excluded) {
                if (runStartDay == null) runStartDay = day
            } else if (runStartDay != null) {
                val x0 = timeToX(max(minTimeMs, runStartDay * GanttParser.DAY_MS))
                val x1 = timeToX(min(maxTimeMs, day * GanttParser.DAY_MS))
                if (x1 > x0 + 1f) {
                    bands += DiagramRect(x0, plotRect.y, x1 - x0, plotRect.height)
                }
                runStartDay = null
            }
        }
        return bands
    }

    internal fun candidateTicks(diagram: GanttDiagram): List<Pair<Long, String>> {
        if (diagram.tasks.isEmpty()) return emptyList()
        val allStartMs = diagram.tasks.minOf { min(it.startMs, it.endMs) }
        val rawMaxMs = diagram.tasks.maxOf { max(it.startMs, it.endMs) }
        val minSpanMs = when {
            diagram.dateFormat.trim() == "x" -> 1L
            diagram.dateFormat.trim() == "X" -> 1000L
            diagram.dateFormat.trim().startsWith("HH:mm", ignoreCase = true) -> 60_000L
            else -> GanttParser.DAY_MS
        }
        val allEndMs = max(allStartMs + minSpanMs, rawMaxMs)
        val spanMs = (allEndMs - allStartMs).coerceAtLeast(1L)
        val targetTickCount = 6
        val rawStep = max(1L, spanMs / targetTickCount)
        val stepMs = chooseCleanStepMs(rawStep, diagram.dateFormat, diagram.tickInterval)

        val candidates = mutableListOf<Long>()
        val isCal = GanttParser.isCalendarDateFormat(diagram.dateFormat)
        if (isCal && stepMs == 7L * GanttParser.DAY_MS) {
            val targetDow = diagram.weekday?.let { GanttParser.WEEKDAY_NUMBERS[it.lowercase()] }
                ?: GanttParser.dayOfWeek(allStartMs.floorDiv(GanttParser.DAY_MS))
            var day = allStartMs.floorDiv(GanttParser.DAY_MS)
            while (GanttParser.dayOfWeek(day) != targetDow) day++
            var cur = day * GanttParser.DAY_MS
            if (cur - allStartMs > 2L * GanttParser.DAY_MS) {
                candidates += allStartMs
            }
            var guard = 0
            while (cur <= allEndMs && guard < 32) {
                candidates += cur
                cur += 7L * GanttParser.DAY_MS
                guard++
            }
        } else if (isCal && stepMs in (28L * GanttParser.DAY_MS)..(35L * GanttParser.DAY_MS)) {
            val (startY, startM, startD) = GanttParser.civilFromDays(allStartMs.floorDiv(GanttParser.DAY_MS))
            if (startD > 5) candidates += allStartMs
            var y = startY
            var m = if (startD == 1) startM else startM + 1
            if (m > 12) {
                m = 1
                y++
            }
            var guard = 0
            while (guard < 36) {
                val ms = GanttParser.daysFromCivil(y, m, 1) * GanttParser.DAY_MS
                if (ms > allEndMs) break
                candidates += ms
                m++
                if (m > 12) {
                    m = 1
                    y++
                }
                guard++
            }
        } else {
            val firstAligned = (allStartMs / stepMs) * stepMs
            var cur = if (firstAligned < allStartMs) firstAligned + stepMs else firstAligned
            if (cur - allStartMs > stepMs / 3) {
                candidates += allStartMs
            }
            var guard = 0
            while (cur <= allEndMs && guard < 32) {
                candidates += cur
                cur += stepMs
                guard++
            }
        }
        if (candidates.isEmpty() || allEndMs - candidates.last() > stepMs / 2) {
            candidates += allEndMs
        }
        return candidates.distinct().map { ms ->
            ms to GanttParser.formatTickLabel(ms, diagram.dateFormat, diagram.axisFormat)
        }
    }

    private fun computeTicks(
        diagram: GanttDiagram,
        minTimeMs: Long,
        maxTimeMs: Long,
        plotRect: DiagramRect,
        topAxisY: Float?,
        bottomAxisY: Float,
        axisHeight: Float,
        tickLabelSize: (String) -> DiagramSize,
        timeToX: (Long) -> Float,
        density: Float,
    ): List<PositionedGanttTick> {
        val candidates = candidateTicks(diagram)
        val ticks = mutableListOf<PositionedGanttTick>()
        var lastRightX = Float.NEGATIVE_INFINITY
        for ((ms, lbl) in candidates) {
            val x = timeToX(ms)
            val sz = tickLabelSize(lbl)
            val tickX = max(20f * density, x - sz.width / 2f)
            val bRect = DiagramRect(
                x = tickX,
                y = bottomAxisY + (axisHeight - sz.height) / 2f,
                width = sz.width,
                height = sz.height,
            )
            if (bRect.x < lastRightX + 8f * density) continue
            lastRightX = bRect.right
            val tRect = topAxisY?.let { ty ->
                DiagramRect(
                    x = tickX,
                    y = ty + (axisHeight - sz.height) / 2f,
                    width = sz.width,
                    height = sz.height,
                )
            }
            ticks += PositionedGanttTick(
                timeMs = ms,
                x = x,
                label = lbl,
                bottomLabelRect = bRect,
                topLabelRect = tRect,
            )
        }
        return ticks
    }

    private fun chooseCleanStepMs(rawStep: Long, dateFormat: String, tickInterval: String?): Long {
        if (!tickInterval.isNullOrBlank()) {
            val parsed = GanttParser.parseDurationMs(tickInterval)
            if (parsed != null && parsed >= rawStep / 4) return parsed
        }
        val fmt = dateFormat.trim()
        if (fmt == "x") {
            val msSteps = longArrayOf(
                1L, 2L, 5L, 10L, 20L, 50L, 100L, 200L, 500L,
                1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 50_000L,
                100_000L, 200_000L, 500_000L, 1_000_000L,
            )
            return msSteps.firstOrNull { it >= rawStep } ?: rawStep
        }
        if (fmt == "X") {
            val secSteps = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 60_000L, 120_000L, 300_000L)
            return secSteps.firstOrNull { it >= rawStep } ?: rawStep
        }
        if (fmt.startsWith("HH:mm", ignoreCase = true)) {
            val minMs = 60_000L
            val timeSteps = longArrayOf(minMs, 2 * minMs, 5 * minMs, 10 * minMs, 15 * minMs, 30 * minMs, 60 * minMs, 120 * minMs)
            return timeSteps.firstOrNull { it >= rawStep } ?: rawStep
        }
        val day = GanttParser.DAY_MS
        val calSteps = longArrayOf(
            day,
            2 * day,
            3 * day,
            7 * day,
            14 * day,
            30 * day,
            60 * day,
            90 * day,
            180 * day,
            365 * day,
            5 * 365 * day,
            10 * 365 * day,
        )
        return calSteps.firstOrNull { it >= rawStep } ?: rawStep
    }

    private fun overlaps(a: DiagramRect, b: DiagramRect, pad: Float): Boolean =
        a.x - pad < b.right && b.x - pad < a.right && a.y - pad < b.bottom && b.y - pad < a.bottom

    internal fun defaultLabelSize(label: String, density: Float): DiagramSize {
        val lines = label.lines().ifEmpty { listOf("") }
        val maxLen = lines.maxOfOrNull { it.length } ?: 3
        return DiagramSize(
            width = max(24f * density, maxLen * 7.4f * density),
            height = lines.size * 16f * density,
        )
    }

    internal fun defaultTickSize(label: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(22f * density, label.length * 6.8f * density),
            height = 14f * density,
        )
}
