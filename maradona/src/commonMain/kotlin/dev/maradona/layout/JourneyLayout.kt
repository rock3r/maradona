package dev.maradona.layout

import dev.maradona.model.JourneyDiagram
import dev.maradona.model.JourneyMood
import dev.maradona.model.JourneyTask
import kotlin.math.max
import kotlin.math.min

data class PositionedJourneyActorLegend(
    val actor: String,
    val actorIndex: Int,
    val circleCenter: DiagramPoint,
    val circleRadius: Float,
    val labelRect: DiagramRect,
)

data class PositionedJourneyActorDot(
    val actor: String,
    val actorIndex: Int,
    val center: DiagramPoint,
    val radius: Float,
)

data class PositionedJourneySection(
    val runIndex: Int,
    val name: String,
    val sectionIndex: Int,
    val rect: DiagramRect,
)

data class PositionedJourneyTask(
    val task: JourneyTask,
    val taskIndex: Int,
    val sectionIndex: Int,
    val cardRect: DiagramRect,
    val actorDots: List<PositionedJourneyActorDot>,
    val lineStart: DiagramPoint,
    val lineEnd: DiagramPoint,
    val faceCenter: DiagramPoint,
    val faceRadius: Float,
    val mood: JourneyMood,
)

data class JourneyDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val spineStart: DiagramPoint,
    val spineEnd: DiagramPoint,
    val actorLegend: List<PositionedJourneyActorLegend>,
    val sections: List<PositionedJourneySection>,
    val tasks: List<PositionedJourneyTask>,
) {
    fun task(id: String): PositionedJourneyTask? = tasks.firstOrNull { it.task.id == id }
}

/**
 * Pure-Kotlin layout engine for Mermaid `journey` (User Journey) diagrams.
 *
 * Computes the left-hand actor legend, section banners, task cards with actor indicator dots,
 * the horizontal journey spine, vertical dashed score tracks, and satisfaction mood faces.
 */
object JourneyLayout {

    internal data class SectionRun(
        val runIndex: Int,
        val name: String?,
        val sectionIndex: Int,
        val tasks: List<Pair<Int, JourneyTask>>,
    )

    internal fun allActors(diagram: JourneyDiagram): List<String> =
        (diagram.actors + diagram.tasks.flatMap { it.actors }).distinct().sorted()

    internal fun buildSectionRuns(diagram: JourneyDiagram): List<SectionRun> {
        val runs = mutableListOf<SectionRun>()
        val sectionOrder = (diagram.sections + diagram.tasks.mapNotNull { it.section }).distinct()
        val secIdxMap = sectionOrder.withIndex().associate { (i, s) -> s to i }
        val populatedSections = diagram.tasks.mapNotNullTo(HashSet()) { it.section }
        val emittedEmptySections = HashSet<String>()
        var declCursor = 0

        for ((tIdx, t) in diagram.tasks.withIndex()) {
            val tSec = t.section
            if (tSec != null) {
                val targetDeclIdx = diagram.sections.indexOf(tSec)
                if (targetDeclIdx >= 0) {
                    while (declCursor < targetDeclIdx) {
                        val candidateSec = diagram.sections[declCursor++]
                        if (candidateSec !in populatedSections && emittedEmptySections.add(candidateSec)) {
                            runs += SectionRun(
                                runIndex = runs.size,
                                name = candidateSec,
                                sectionIndex = secIdxMap[candidateSec] ?: 0,
                                tasks = emptyList(),
                            )
                        }
                    }
                    declCursor = max(declCursor, targetDeclIdx + 1)
                }
            }
            val last = runs.lastOrNull()
            if (last != null && last.name == tSec) {
                runs[runs.lastIndex] = last.copy(tasks = last.tasks + (tIdx to t))
            } else {
                val sIdx = tSec?.let { secIdxMap[it] ?: 0 } ?: 0
                runs += SectionRun(runIndex = runs.size, name = tSec, sectionIndex = sIdx, tasks = listOf(tIdx to t))
            }
        }
        for (sec in diagram.sections) {
            if (sec !in populatedSections && emittedEmptySections.add(sec)) {
                runs += SectionRun(runIndex = runs.size, name = sec, sectionIndex = secIdxMap[sec] ?: 0, tasks = emptyList())
            }
        }
        return runs
    }

    fun layout(
        diagram: JourneyDiagram,
        taskLabelSize: (JourneyTask) -> DiagramSize = { t -> defaultTextSize(t.label, 1f) },
        sectionTitleSize: (String) -> DiagramSize = { s -> defaultTextSize(s, 1f) },
        actorLabelSize: (String) -> DiagramSize = { a -> defaultTextSize(a, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): JourneyDiagramLayout {
        val d = options.density
        val outerPad = 24f * d
        val colGap = 18f * d
        val sectionGap = 26f * d

        val resolvedTitleSize = titleSize ?: diagram.title?.let {
            DiagramSize(max(120f * d, it.length * 9f * d), 24f * d)
        }

        var contentTopY = outerPad
        val titleTopY = contentTopY
        if (resolvedTitleSize != null) {
            contentTopY += resolvedTitleSize.height + 18f * d
        }

        val actors = allActors(diagram)
        val actorIdxByName = actors.withIndex().associate { (i, a) -> a to i }
        val legendCircleRadius = 7f * d
        val legendRowGap = 10f * d

        val actorLegendItems = mutableListOf<PositionedJourneyActorLegend>()
        var legendRightX = outerPad
        var legendBottomY = contentTopY
        if (actors.isNotEmpty()) {
            var curY = contentTopY + 6f * d
            for ((idx, actor) in actors.withIndex()) {
                val lblSz = actorLabelSize(actor)
                val rowH = max(legendCircleRadius * 2f, lblSz.height)
                val cx = outerPad + legendCircleRadius
                val cy = curY + rowH / 2f
                val lRect = DiagramRect(
                    x = outerPad + legendCircleRadius * 2f + 8f * d,
                    y = cy - lblSz.height / 2f,
                    width = lblSz.width,
                    height = lblSz.height,
                )
                actorLegendItems += PositionedJourneyActorLegend(
                    actor = actor,
                    actorIndex = idx,
                    circleCenter = DiagramPoint(cx, cy),
                    circleRadius = legendCircleRadius,
                    labelRect = lRect,
                )
                legendRightX = max(legendRightX, lRect.right)
                legendBottomY = max(legendBottomY, lRect.bottom)
                curY += rowH + legendRowGap
            }
        }

        val runs = buildSectionRuns(diagram)
        val hasSections = runs.any { it.name != null }
        val mainLeftX = if (actorLegendItems.isNotEmpty()) legendRightX + 28f * d else outerPad

        if (diagram.tasks.isEmpty() && !hasSections) {
            val w = max(260f * d, max(mainLeftX + 180f * d, (resolvedTitleSize?.width ?: 0f) + outerPad * 2f))
            val h = max(legendBottomY, contentTopY + 80f * d) + outerPad
            val tRect = resolvedTitleSize?.let {
                DiagramRect(max(outerPad, (w - it.width) / 2f), titleTopY, it.width, it.height)
            }
            return JourneyDiagramLayout(
                width = w,
                height = h,
                titleRect = tRect,
                spineStart = DiagramPoint(mainLeftX, contentTopY + 40f * d),
                spineEnd = DiagramPoint(w - outerPad, contentTopY + 40f * d),
                actorLegend = actorLegendItems,
                sections = emptyList(),
                tasks = emptyList(),
            )
        }

        val sectionHeaderHeight = if (hasSections) {
            (runs.mapNotNull { it.name }.maxOfOrNull { sectionTitleSize(it).height + 18f * d } ?: (36f * d))
                .coerceAtLeast(36f * d)
        } else {
            0f
        }

        val taskCardSizes = diagram.tasks.map { t ->
            val sz = taskLabelSize(t)
            val dotsMinW = t.actors.size * 11f * d + 20f * d
            DiagramSize(
                width = max(118f * d, max(sz.width + 24f * d, dotsMinW)),
                height = max(44f * d, sz.height + 20f * d),
            )
        }
        val maxTaskCardHeight = taskCardSizes.maxOfOrNull { it.height } ?: (44f * d)

        val colWidths = FloatArray(diagram.tasks.size) { idx -> taskCardSizes[idx].width }
        for (run in runs) {
            val secName = run.name ?: continue
            if (run.tasks.isEmpty()) continue
            val minSecW = max(118f * d, sectionTitleSize(secName).width + 28f * d)
            val curW = run.tasks.sumOf { (idx, _) -> colWidths[idx].toDouble() }.toFloat() +
                (run.tasks.size - 1) * colGap
            if (curW < minSecW) {
                val extraPerCol = (minSecW - curW) / run.tasks.size
                for ((idx, _) in run.tasks) {
                    colWidths[idx] += extraPerCol
                }
            }
        }

        val sectionRowY = contentTopY
        val taskRowY = if (hasSections) sectionRowY + sectionHeaderHeight + 20f * d else contentTopY
        val taskRowBottomY = taskRowY + maxTaskCardHeight
        val spineY = taskRowBottomY + 20f * d
        val faceRadius = 14f * d
        val scoreStepY = 26f * d
        val scoreTrackTopY = spineY + 24f * d
        val scoreTrackBottomY = scoreTrackTopY + 4f * scoreStepY + faceRadius + 6f * d

        val positionedSections = mutableListOf<PositionedJourneySection>()
        val positionedTasks = mutableListOf<PositionedJourneyTask>()

        var cursorX = mainLeftX
        for ((runIdx, run) in runs.withIndex()) {
            if (runIdx > 0) cursorX += if (hasSections) sectionGap else colGap
            val runStartX = cursorX

            if (run.tasks.isEmpty() && run.name != null) {
                val secW = max(118f * d, sectionTitleSize(run.name).width + 28f * d)
                positionedSections += PositionedJourneySection(
                    runIndex = run.runIndex,
                    name = run.name,
                    sectionIndex = run.sectionIndex,
                    rect = DiagramRect(runStartX, sectionRowY, secW, sectionHeaderHeight),
                )
                cursorX += secW
                continue
            }

            for ((localIdx, pair) in run.tasks.withIndex()) {
                if (localIdx > 0) cursorX += colGap
                val (tIdx, t) = pair
                val cW = colWidths[tIdx]
                val cardRect = DiagramRect(
                    x = cursorX,
                    y = taskRowY,
                    width = cW,
                    height = maxTaskCardHeight,
                )

                val dotRadius = 6f * d
                val dots = t.actors.mapIndexed { aIdx, actor ->
                    PositionedJourneyActorDot(
                        actor = actor,
                        actorIndex = actorIdxByName[actor] ?: 0,
                        center = DiagramPoint(
                            x = cardRect.x + 12f * d + aIdx * 11f * d,
                            y = cardRect.y,
                        ),
                        radius = dotRadius,
                    )
                }

                val clampedScore = t.score.coerceIn(1.0, 5.0).toFloat()
                val faceY = scoreTrackTopY + (5f - clampedScore) * scoreStepY
                val centerX = cardRect.centerX

                positionedTasks += PositionedJourneyTask(
                    task = t,
                    taskIndex = tIdx,
                    sectionIndex = run.sectionIndex,
                    cardRect = cardRect,
                    actorDots = dots,
                    lineStart = DiagramPoint(centerX, cardRect.bottom),
                    lineEnd = DiagramPoint(centerX, scoreTrackBottomY),
                    faceCenter = DiagramPoint(centerX, faceY),
                    faceRadius = faceRadius,
                    mood = t.mood,
                )
                cursorX += cW
            }

            val runEndX = cursorX
            if (run.name != null && runEndX > runStartX) {
                positionedSections += PositionedJourneySection(
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
                positionedSections.maxOfOrNull { it.rect.right } ?: mainLeftX,
                positionedTasks.maxOfOrNull { it.cardRect.right } ?: mainLeftX,
            ),
        )
        val titleMinWidth = resolvedTitleSize?.let { it.width + outerPad * 2f } ?: 0f
        val finalWidth = max(titleMinWidth, maxContentRight + outerPad)
        val finalHeight = max(legendBottomY, scoreTrackBottomY) + outerPad

        val spineStartX = min(
            positionedTasks.minOfOrNull { it.cardRect.x } ?: mainLeftX,
            positionedSections.minOfOrNull { it.rect.x } ?: mainLeftX,
        ) - 8f * d
        val spineEndX = maxContentRight + 14f * d

        val titleRect = resolvedTitleSize?.let {
            DiagramRect(
                x = max(outerPad, (finalWidth - it.width) / 2f),
                y = titleTopY,
                width = it.width,
                height = it.height,
            )
        }

        return JourneyDiagramLayout(
            width = finalWidth,
            height = finalHeight,
            titleRect = titleRect,
            spineStart = DiagramPoint(spineStartX.coerceAtLeast(8f * d), spineY),
            spineEnd = DiagramPoint(spineEndX, spineY),
            actorLegend = actorLegendItems,
            sections = positionedSections,
            tasks = positionedTasks,
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
