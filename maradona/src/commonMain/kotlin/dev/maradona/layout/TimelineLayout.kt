package dev.maradona.layout

import dev.maradona.model.TimelineDiagram
import dev.maradona.model.TimelineDirection
import dev.maradona.model.TimelinePeriod
import kotlin.math.max
import kotlin.math.min

data class PositionedTimelineSection(
    val runIndex: Int,
    val name: String,
    val sectionIndex: Int,
    val rect: DiagramRect,
)

data class PositionedTimelineEvent(
    val periodId: String,
    val eventIndex: Int,
    val text: String,
    val rect: DiagramRect,
)

data class PositionedTimelinePeriod(
    val period: TimelinePeriod,
    val periodIndex: Int,
    val colorIndex: Int,
    val rect: DiagramRect,
    val events: List<PositionedTimelineEvent>,
    val connectorStart: DiagramPoint?,
    val connectorEnd: DiagramPoint?,
)

data class TimelineDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val direction: TimelineDirection,
    val disableMulticolor: Boolean,
    val spineStart: DiagramPoint,
    val spineEnd: DiagramPoint,
    val sections: List<PositionedTimelineSection>,
    val periods: List<PositionedTimelinePeriod>,
) {
    fun period(id: String): PositionedTimelinePeriod? = periods.firstOrNull { it.period.id == id }
}

/**
 * Pure-Kotlin layout engine for Mermaid `timeline` diagrams.
 *
 * Supports both horizontal (`LR`) and vertical (`TD`/`TB`) timelines, section banners,
 * period cards on a continuous spine, and connected event cards.
 */
object TimelineLayout {

    fun layout(
        diagram: TimelineDiagram,
        periodTitleSize: (TimelinePeriod) -> DiagramSize = { p -> defaultTextSize(p.title, 1f) },
        eventTextSize: (TimelinePeriod, Int, String) -> DiagramSize = { _, _, s -> defaultTextSize(s, 1f) },
        sectionTitleSize: (String) -> DiagramSize = { s -> defaultTextSize(s, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): TimelineDiagramLayout {
        val d = options.density
        val outerPad = 24f * d

        val resolvedTitleSize = titleSize ?: diagram.title?.let {
            DiagramSize(max(120f * d, it.length * 9f * d), 24f * d)
        }

        var contentTopY = outerPad
        val titleTopY = contentTopY
        if (resolvedTitleSize != null) {
            contentTopY += resolvedTitleSize.height + 18f * d
        }

        if (diagram.periods.isEmpty() && diagram.sections.isEmpty()) {
            val w = max(240f * d, (resolvedTitleSize?.width ?: 0f) + outerPad * 2f)
            val h = contentTopY + 60f * d
            val tRect = resolvedTitleSize?.let {
                DiagramRect(max(outerPad, (w - it.width) / 2f), titleTopY, it.width, it.height)
            }
            return TimelineDiagramLayout(
                width = w,
                height = h,
                titleRect = tRect,
                direction = diagram.direction,
                disableMulticolor = diagram.disableMulticolor,
                spineStart = DiagramPoint(outerPad, contentTopY + 20f * d),
                spineEnd = DiagramPoint(w - outerPad, contentTopY + 20f * d),
                sections = emptyList(),
                periods = emptyList(),
            )
        }

        return if (diagram.direction == TimelineDirection.TD) {
            layoutVertical(
                diagram = diagram,
                periodTitleSize = periodTitleSize,
                eventTextSize = eventTextSize,
                sectionTitleSize = sectionTitleSize,
                resolvedTitleSize = resolvedTitleSize,
                titleTopY = titleTopY,
                contentTopY = contentTopY,
                outerPad = outerPad,
                density = d,
            )
        } else {
            layoutHorizontal(
                diagram = diagram,
                periodTitleSize = periodTitleSize,
                eventTextSize = eventTextSize,
                sectionTitleSize = sectionTitleSize,
                resolvedTitleSize = resolvedTitleSize,
                titleTopY = titleTopY,
                contentTopY = contentTopY,
                outerPad = outerPad,
                density = d,
            )
        }
    }

    internal data class SectionRun(
        val runIndex: Int,
        val name: String?,
        val sectionIndex: Int,
        val periods: List<Pair<Int, TimelinePeriod>>,
    )

    internal fun buildSectionRuns(diagram: TimelineDiagram): List<SectionRun> {
        val runs = mutableListOf<SectionRun>()
        val sectionOrder = (diagram.sections + diagram.periods.mapNotNull { it.section }).distinct()
        val secIdxMap = sectionOrder.withIndex().associate { (i, s) -> s to i }
        val populatedSections = diagram.periods.mapNotNullTo(HashSet()) { it.section }
        val emittedEmptySections = HashSet<String>()
        var declCursor = 0

        for ((pIdx, p) in diagram.periods.withIndex()) {
            val pSec = p.section
            if (pSec != null) {
                val targetDeclIdx = diagram.sections.indexOf(pSec)
                if (targetDeclIdx >= 0) {
                    while (declCursor < targetDeclIdx) {
                        val candidateSec = diagram.sections[declCursor++]
                        if (candidateSec !in populatedSections && emittedEmptySections.add(candidateSec)) {
                            runs += SectionRun(
                                runIndex = runs.size,
                                name = candidateSec,
                                sectionIndex = secIdxMap[candidateSec] ?: 0,
                                periods = emptyList(),
                            )
                        }
                    }
                    declCursor = max(declCursor, targetDeclIdx + 1)
                }
            }
            val last = runs.lastOrNull()
            if (last != null && last.name == pSec) {
                runs[runs.lastIndex] = last.copy(periods = last.periods + (pIdx to p))
            } else {
                val sIdx = pSec?.let { secIdxMap[it] ?: 0 } ?: 0
                runs += SectionRun(runIndex = runs.size, name = pSec, sectionIndex = sIdx, periods = listOf(pIdx to p))
            }
        }
        // Preserve any trailing declared sections that had no periods.
        for (sec in diagram.sections) {
            if (sec !in populatedSections && emittedEmptySections.add(sec)) {
                runs += SectionRun(runIndex = runs.size, name = sec, sectionIndex = secIdxMap[sec] ?: 0, periods = emptyList())
            }
        }
        return runs
    }

    private fun layoutHorizontal(
        diagram: TimelineDiagram,
        periodTitleSize: (TimelinePeriod) -> DiagramSize,
        eventTextSize: (TimelinePeriod, Int, String) -> DiagramSize,
        sectionTitleSize: (String) -> DiagramSize,
        resolvedTitleSize: DiagramSize?,
        titleTopY: Float,
        contentTopY: Float,
        outerPad: Float,
        density: Float,
    ): TimelineDiagramLayout {
        val d = density
        val colGap = 20f * d
        val sectionGap = 28f * d
        val eventGap = 12f * d
        val runs = buildSectionRuns(diagram)
        val hasSections = runs.any { it.name != null }

        val sectionHeaderHeight = if (hasSections) {
            (runs.mapNotNull { it.name }.maxOfOrNull { sectionTitleSize(it).height + 18f * d } ?: (36f * d))
                .coerceAtLeast(36f * d)
        } else {
            0f
        }

        val periodBoxSizes = diagram.periods.map { p ->
            val sz = periodTitleSize(p)
            DiagramSize(
                width = max(96f * d, sz.width + 24f * d),
                height = max(36f * d, sz.height + 16f * d),
            )
        }
        val maxPeriodBoxH = periodBoxSizes.maxOfOrNull { it.height } ?: (36f * d)

        val eventBoxSizesByPeriod = diagram.periods.map { p ->
            p.events.mapIndexed { eIdx, ev ->
                val sz = eventTextSize(p, eIdx, ev)
                DiagramSize(
                    width = max(96f * d, sz.width + 22f * d),
                    height = max(32f * d, sz.height + 16f * d),
                )
            }
        }

        val colWidths = FloatArray(diagram.periods.size) { pIdx ->
            val pW = periodBoxSizes[pIdx].width
            val eW = eventBoxSizesByPeriod[pIdx].maxOfOrNull { it.width } ?: 0f
            max(pW, eW)
        }

        // Expand columns within a section if the section header text is wider than its columns.
        for (run in runs) {
            val secName = run.name ?: continue
            if (run.periods.isEmpty()) continue
            val minSecW = max(110f * d, sectionTitleSize(secName).width + 28f * d)
            val curW = run.periods.sumOf { (idx, _) -> colWidths[idx].toDouble() }.toFloat() +
                (run.periods.size - 1) * colGap
            if (curW < minSecW) {
                val extraPerCol = (minSecW - curW) / run.periods.size
                for ((idx, _) in run.periods) {
                    colWidths[idx] += extraPerCol
                }
            }
        }

        val sectionRowY = contentTopY
        val periodRowY = if (hasSections) sectionRowY + sectionHeaderHeight + 20f * d else contentTopY
        val spineY = periodRowY + maxPeriodBoxH / 2f
        val eventsStartY = periodRowY + maxPeriodBoxH + 24f * d

        val positionedSections = mutableListOf<PositionedTimelineSection>()
        val positionedPeriods = mutableListOf<PositionedTimelinePeriod>()

        var cursorX = outerPad
        for ((runIdx, run) in runs.withIndex()) {
            if (runIdx > 0) {
                cursorX += if (hasSections) sectionGap else colGap
            }
            val runStartX = cursorX
            if (run.periods.isEmpty() && run.name != null) {
                val secW = max(110f * d, sectionTitleSize(run.name).width + 28f * d)
                positionedSections += PositionedTimelineSection(
                    runIndex = run.runIndex,
                    name = run.name,
                    sectionIndex = run.sectionIndex,
                    rect = DiagramRect(runStartX, sectionRowY, secW, sectionHeaderHeight),
                )
                cursorX += secW
                continue
            }

            for ((localIdx, pair) in run.periods.withIndex()) {
                if (localIdx > 0) cursorX += colGap
                val (pIdx, p) = pair
                val cW = colWidths[pIdx]
                val pBoxSz = periodBoxSizes[pIdx]
                val pRect = DiagramRect(
                    x = cursorX,
                    y = spineY - pBoxSz.height / 2f,
                    width = cW,
                    height = pBoxSz.height,
                )

                var evY = eventsStartY
                val posEvents = ArrayList<PositionedTimelineEvent>(p.events.size)
                for ((eIdx, evText) in p.events.withIndex()) {
                    val eSz = eventBoxSizesByPeriod[pIdx][eIdx]
                    val eRect = DiagramRect(
                        x = cursorX,
                        y = evY,
                        width = cW,
                        height = eSz.height,
                    )
                    posEvents += PositionedTimelineEvent(
                        periodId = p.id,
                        eventIndex = eIdx,
                        text = evText,
                        rect = eRect,
                    )
                    evY += eSz.height + eventGap
                }

                val connStart = if (posEvents.isNotEmpty()) DiagramPoint(pRect.centerX, pRect.bottom) else null
                val connEnd = posEvents.lastOrNull()?.let { DiagramPoint(pRect.centerX, it.rect.y) }
                val colorIdx = if (diagram.disableMulticolor) 0 else (if (hasSections) run.sectionIndex else pIdx)

                positionedPeriods += PositionedTimelinePeriod(
                    period = p,
                    periodIndex = pIdx,
                    colorIndex = colorIdx,
                    rect = pRect,
                    events = posEvents,
                    connectorStart = connStart,
                    connectorEnd = connEnd,
                )
                cursorX += cW
            }

            val runEndX = cursorX
            if (run.name != null && runEndX > runStartX) {
                positionedSections += PositionedTimelineSection(
                    runIndex = run.runIndex,
                    name = run.name,
                    sectionIndex = run.sectionIndex,
                    rect = DiagramRect(runStartX, sectionRowY, runEndX - runStartX, sectionHeaderHeight),
                )
            }
        }

        val maxContentRight = max(
            cursorX,
            max(
                positionedSections.maxOfOrNull { it.rect.right } ?: outerPad,
                positionedPeriods.maxOfOrNull { p ->
                    max(p.rect.right, p.events.maxOfOrNull { it.rect.right } ?: p.rect.right)
                } ?: outerPad,
            ),
        )
        val titleMinWidth = resolvedTitleSize?.let { it.width + outerPad * 2f } ?: 0f
        val finalWidth = max(titleMinWidth, maxContentRight + outerPad)

        val maxContentBottom = max(
            periodRowY + maxPeriodBoxH,
            max(
                positionedSections.maxOfOrNull { it.rect.bottom } ?: contentTopY,
                positionedPeriods.maxOfOrNull { p ->
                    max(p.rect.bottom, p.events.maxOfOrNull { it.rect.bottom } ?: p.rect.bottom)
                } ?: (periodRowY + maxPeriodBoxH),
            ),
        )
        val finalHeight = maxContentBottom + outerPad

        val spineStartX = min(
            positionedPeriods.minOfOrNull { it.rect.x } ?: outerPad,
            positionedSections.minOfOrNull { it.rect.x } ?: outerPad,
        ) - 10f * d
        val spineEndX = maxContentRight + 14f * d

        val titleRect = resolvedTitleSize?.let {
            DiagramRect(
                x = max(outerPad, (finalWidth - it.width) / 2f),
                y = titleTopY,
                width = it.width,
                height = it.height,
            )
        }

        return TimelineDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            titleRect = titleRect,
            direction = diagram.direction,
            disableMulticolor = diagram.disableMulticolor,
            spineStart = DiagramPoint(spineStartX.coerceAtLeast(8f * d), spineY),
            spineEnd = DiagramPoint(spineEndX, spineY),
            sections = positionedSections,
            periods = positionedPeriods,
        )
    }

    private fun layoutVertical(
        diagram: TimelineDiagram,
        periodTitleSize: (TimelinePeriod) -> DiagramSize,
        eventTextSize: (TimelinePeriod, Int, String) -> DiagramSize,
        sectionTitleSize: (String) -> DiagramSize,
        resolvedTitleSize: DiagramSize?,
        titleTopY: Float,
        contentTopY: Float,
        outerPad: Float,
        density: Float,
    ): TimelineDiagramLayout {
        val d = density
        val rowGap = 16f * d
        val sectionGap = 22f * d
        val eventGapX = 14f * d
        val runs = buildSectionRuns(diagram)
        val hasSections = runs.any { it.name != null }

        val sectionColWidth = if (hasSections) {
            (runs.mapNotNull { it.name }.maxOfOrNull { sectionTitleSize(it).width + 28f * d } ?: (110f * d))
                .coerceAtLeast(110f * d)
        } else {
            0f
        }

        val periodBoxSizes = diagram.periods.map { p ->
            val sz = periodTitleSize(p)
            DiagramSize(
                width = max(96f * d, sz.width + 24f * d),
                height = max(36f * d, sz.height + 16f * d),
            )
        }
        val periodColWidth = periodBoxSizes.maxOfOrNull { it.width } ?: (110f * d)

        val eventBoxSizesByPeriod = diagram.periods.map { p ->
            p.events.mapIndexed { eIdx, ev ->
                val sz = eventTextSize(p, eIdx, ev)
                DiagramSize(
                    width = max(92f * d, sz.width + 22f * d),
                    height = max(32f * d, sz.height + 16f * d),
                )
            }
        }

        val sectionColX = outerPad
        val periodColX = if (hasSections) sectionColX + sectionColWidth + 20f * d else outerPad
        val spineX = periodColX + periodColWidth / 2f
        val eventsStartX = periodColX + periodColWidth + 24f * d

        val positionedSections = mutableListOf<PositionedTimelineSection>()
        val positionedPeriods = mutableListOf<PositionedTimelinePeriod>()

        var cursorY = contentTopY
        for ((runIdx, run) in runs.withIndex()) {
            if (runIdx > 0) cursorY += if (hasSections) sectionGap else rowGap
            val runStartY = cursorY
            val minSecH = run.name?.let { max(42f * d, sectionTitleSize(it).height + 20f * d) } ?: 0f

            if (run.periods.isEmpty() && run.name != null) {
                positionedSections += PositionedTimelineSection(
                    runIndex = run.runIndex,
                    name = run.name,
                    sectionIndex = run.sectionIndex,
                    rect = DiagramRect(sectionColX, runStartY, sectionColWidth, minSecH),
                )
                cursorY += minSecH
                continue
            }

            for ((localIdx, pair) in run.periods.withIndex()) {
                if (localIdx > 0) cursorY += rowGap
                val (pIdx, p) = pair
                val pSz = periodBoxSizes[pIdx]
                val evSizes = eventBoxSizesByPeriod[pIdx]
                val maxEvH = evSizes.maxOfOrNull { it.height } ?: 0f
                val baseRowH = max(pSz.height, maxEvH)
                val rowH = if (run.periods.size == 1 && localIdx == 0) max(baseRowH, minSecH) else baseRowH

                val pRect = DiagramRect(
                    x = periodColX,
                    y = cursorY + (rowH - pSz.height) / 2f,
                    width = periodColWidth,
                    height = pSz.height,
                )

                var evX = eventsStartX
                val posEvents = ArrayList<PositionedTimelineEvent>(p.events.size)
                for ((eIdx, evText) in p.events.withIndex()) {
                    val eSz = evSizes[eIdx]
                    val eRect = DiagramRect(
                        x = evX,
                        y = cursorY + (rowH - eSz.height) / 2f,
                        width = eSz.width,
                        height = eSz.height,
                    )
                    posEvents += PositionedTimelineEvent(
                        periodId = p.id,
                        eventIndex = eIdx,
                        text = evText,
                        rect = eRect,
                    )
                    evX += eSz.width + eventGapX
                }

                val connStart = if (posEvents.isNotEmpty()) DiagramPoint(pRect.right, pRect.centerY) else null
                val connEnd = posEvents.lastOrNull()?.let { DiagramPoint(it.rect.x, pRect.centerY) }
                val colorIdx = if (diagram.disableMulticolor) 0 else (if (hasSections) run.sectionIndex else pIdx)

                positionedPeriods += PositionedTimelinePeriod(
                    period = p,
                    periodIndex = pIdx,
                    colorIndex = colorIdx,
                    rect = pRect,
                    events = posEvents,
                    connectorStart = connStart,
                    connectorEnd = connEnd,
                )
                cursorY += rowH
            }

            val runEndY = cursorY
            if (run.name != null && runEndY > runStartY) {
                val secH = max(minSecH, runEndY - runStartY)
                if (secH > runEndY - runStartY) cursorY = runStartY + secH
                positionedSections += PositionedTimelineSection(
                    runIndex = run.runIndex,
                    name = run.name,
                    sectionIndex = run.sectionIndex,
                    rect = DiagramRect(sectionColX, runStartY, sectionColWidth, secH),
                )
            }
        }

        val maxContentRight = max(
            periodColX + periodColWidth,
            max(
                positionedSections.maxOfOrNull { it.rect.right } ?: outerPad,
                positionedPeriods.maxOfOrNull { p ->
                    max(p.rect.right, p.events.maxOfOrNull { it.rect.right } ?: p.rect.right)
                } ?: outerPad,
            ),
        )
        val titleMinWidth = resolvedTitleSize?.let { it.width + outerPad * 2f } ?: 0f
        val finalWidth = max(titleMinWidth, maxContentRight + outerPad)
        val finalHeight = cursorY + outerPad

        val spineStartY = (positionedPeriods.minOfOrNull { it.rect.y } ?: contentTopY) - 10f * d
        val spineEndY = (positionedPeriods.maxOfOrNull { it.rect.bottom } ?: cursorY) + 14f * d

        val titleRect = resolvedTitleSize?.let {
            DiagramRect(
                x = max(outerPad, (finalWidth - it.width) / 2f),
                y = titleTopY,
                width = it.width,
                height = it.height,
            )
        }

        return TimelineDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            titleRect = titleRect,
            direction = diagram.direction,
            disableMulticolor = diagram.disableMulticolor,
            spineStart = DiagramPoint(spineX, spineStartY.coerceAtLeast(contentTopY)),
            spineEnd = DiagramPoint(spineX, spineEndY),
            sections = positionedSections,
            periods = positionedPeriods,
        )
    }

    internal fun defaultTextSize(text: String, density: Float): DiagramSize {
        val plain = text.replace("**", "").replace("*", "")
        val lines = plain.lines().ifEmpty { listOf("") }
        val maxLen = lines.maxOfOrNull { it.length } ?: 3
        return DiagramSize(
            width = max(36f * density, maxLen * 7.4f * density),
            height = lines.size * 17f * density,
        )
    }
}
