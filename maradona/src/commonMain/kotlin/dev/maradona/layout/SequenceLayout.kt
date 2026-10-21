package dev.maradona.layout

import dev.maradona.model.NotePlacement
import dev.maradona.model.SequenceBlockKind
import dev.maradona.model.SequenceDiagram
import dev.maradona.model.SequenceParticipant
import dev.maradona.model.SequenceStatement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PositionedParticipant(
    val participant: SequenceParticipant,
    val topRect: DiagramRect,
    val bottomRect: DiagramRect?,
    val lifelineX: Float,
    val lifelineTopY: Float,
    val lifelineBottomY: Float,
)

data class PositionedActivation(
    val participantId: String,
    val rect: DiagramRect,
    val depth: Int,
)

data class PositionedSequenceMessage(
    val index: Int,
    val sequenceNumber: Int?,
    val statement: SequenceStatement.Message,
    val startX: Float,
    val stopX: Float,
    val lineY: Float,
    val labelRect: DiagramRect,
    val isSelf: Boolean,
)

data class PositionedSequenceNote(
    val index: Int,
    val note: SequenceStatement.Note,
    val rect: DiagramRect,
)

data class PositionedSequenceDivider(
    val y: Float,
    val label: String?,
    val labelRect: DiagramRect?,
)

data class PositionedSequenceBlock(
    val index: Int,
    val kind: SequenceBlockKind,
    val tag: String,
    val label: String?,
    val rect: DiagramRect,
    val tagRect: DiagramRect,
    val labelRect: DiagramRect?,
    val dividers: List<PositionedSequenceDivider>,
)

data class SequenceDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val participants: List<PositionedParticipant>,
    val activations: List<PositionedActivation>,
    val messages: List<PositionedSequenceMessage>,
    val notes: List<PositionedSequenceNote>,
    val blocks: List<PositionedSequenceBlock>,
)

data class SequenceLayoutOptions(
    val actorWidth: Float = 150f,
    val actorHeight: Float = 74f,
    val actorMargin: Float = 50f,
    val activationWidth: Float = 10f,
    val boxMargin: Float = 10f,
    val boxTextMargin: Float = 5f,
    val noteMargin: Float = 10f,
    val wrapPadding: Float = 10f,
    val labelBoxWidth: Float = 50f,
    val labelBoxHeight: Float = 20f,
    val paddingX: Float = 16f,
    val paddingY: Float = 12f,
    val density: Float = 1f,
)

/**
 * Sequence diagram layout ported from Mermaid's `sequenceRenderer`:
 * horizontal participant columns with message-driven spacing, vertical
 * time axis for messages, notes, activations, and control-flow blocks,
 * and mirrored header/footer participant boxes connected by lifelines.
 */
object SequenceLayout {

    fun layout(
        diagram: SequenceDiagram,
        participantLabelSize: (SequenceParticipant) -> DiagramSize,
        messageLabelSize: (Int, SequenceStatement.Message) -> DiagramSize,
        noteTextSize: (Int, SequenceStatement.Note) -> DiagramSize,
        blockLabelSize: (Int, Int, String) -> DiagramSize,
        titleSize: DiagramSize? = null,
        options: SequenceLayoutOptions = SequenceLayoutOptions(),
    ): SequenceDiagramLayout {
        if (diagram.participants.isEmpty()) {
            return SequenceDiagramLayout(0f, 0f, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        return Engine(
            diagram = diagram,
            participantLabelSize = participantLabelSize,
            messageLabelSize = messageLabelSize,
            noteTextSize = noteTextSize,
            blockLabelSize = blockLabelSize,
            titleSize = titleSize,
            options = options,
        ).compute()
    }

    private class ActiveSpan(
        val participantId: String,
        val startX: Float,
        val stopX: Float,
        var startY: Float,
        val depth: Int,
    )

    private class Engine(
        private val diagram: SequenceDiagram,
        private val participantLabelSize: (SequenceParticipant) -> DiagramSize,
        private val messageLabelSize: (Int, SequenceStatement.Message) -> DiagramSize,
        private val noteTextSize: (Int, SequenceStatement.Note) -> DiagramSize,
        private val blockLabelSize: (Int, Int, String) -> DiagramSize,
        private val titleSize: DiagramSize?,
        private val options: SequenceLayoutOptions,
    ) {
        private val participants = diagram.participants
        private val indexById = participants.withIndex().associate { (i, p) -> p.id to i }
        private val widths = FloatArray(participants.size)
        private val heights = FloatArray(participants.size)
        private val margins = FloatArray(participants.size) { options.actorMargin }
        private val actorX = FloatArray(participants.size)
        private var headerHeight = options.actorHeight

        private var verticalPos = 0f
        private var minX = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var minY = 0f
        private var maxY = 0f

        private val activeStacks = HashMap<String, ArrayDeque<ActiveSpan>>()
        private val finishedActivations = mutableListOf<PositionedActivation>()
        private val positionedMessages = mutableListOf<PositionedSequenceMessage>()
        private val positionedNotes = mutableListOf<PositionedSequenceNote>()
        private val positionedBlocks = mutableListOf<PositionedSequenceBlock>()

        private var msgCounter = 0
        private var seqNum = 1
        private var noteCounter = 0
        private var blockCounter = 0

        private fun includeBounds(x0: Float, y0: Float, x1: Float, y1: Float) {
            minX = min(minX, min(x0, x1))
            maxX = max(maxX, max(x0, x1))
            minY = min(minY, min(y0, y1))
            maxY = max(maxY, max(y0, y1))
        }

        private fun bump(dy: Float) {
            verticalPos += dy
            maxY = max(maxY, verticalPos)
        }

        fun compute(): SequenceDiagramLayout {
            // 1. Measure participant boxes and required inter-participant gaps.
            val actorGlyphBand = 48f * options.density
            for ((i, p) in participants.withIndex()) {
                val sz = participantLabelSize(p)
                widths[i] = max(options.actorWidth, sz.width + 2f * options.wrapPadding)
                val neededH = if (p.kind == dev.maradona.model.ParticipantKind.Actor) {
                    actorGlyphBand + sz.height + options.wrapPadding
                } else {
                    sz.height + 2f * options.wrapPadding
                }
                heights[i] = max(options.actorHeight, neededH)
                headerHeight = max(headerHeight, heights[i])
            }

            collectMessageSpacing(diagram.statements, IntArray(1), IntArray(1))

            var curX = 0f
            for (i in participants.indices) {
                actorX[i] = curX
                includeBounds(curX, 0f, curX + widths[i], headerHeight)
                curX += widths[i] + margins[i]
            }

            var titleRect: DiagramRect? = null
            if (diagram.title != null && titleSize != null) {
                val totalActorsW = (actorX.last() + widths.last()) - actorX.first()
                titleRect = DiagramRect(
                    x = (totalActorsW - titleSize.width) / 2f,
                    y = 0f,
                    width = titleSize.width,
                    height = titleSize.height,
                )
                includeBounds(titleRect.x, titleRect.y, titleRect.right, titleRect.bottom)
                bump(titleSize.height + options.boxMargin * 1.5f)
            }

            val topBoxY = verticalPos
            for (i in participants.indices) {
                includeBounds(actorX[i], topBoxY, actorX[i] + widths[i], topBoxY + headerHeight)
            }
            bump(headerHeight)

            // 2. Walk statements along the vertical time axis.
            layoutStatements(diagram.statements)

            // Close any activations left open at the end of the diagram.
            for ((pid, stack) in activeStacks) {
                while (stack.isNotEmpty()) {
                    val span = stack.removeLast()
                    val endY = max(verticalPos, span.startY + 12f * options.density)
                    finishedActivations += PositionedActivation(
                        participantId = pid,
                        rect = DiagramRect(span.startX, span.startY, span.stopX - span.startX, endY - span.startY),
                        depth = span.depth,
                    )
                }
            }

            // 3. Footer participant boxes and lifelines.
            if (diagram.mirrorActors) {
                bump(options.boxMargin * 2f)
            } else {
                bump(options.boxMargin)
            }
            val lifelineBottomY = verticalPos
            val bottomBoxY = if (diagram.mirrorActors) verticalPos else null
            if (bottomBoxY != null) {
                for (i in participants.indices) {
                    includeBounds(actorX[i], bottomBoxY, actorX[i] + widths[i], bottomBoxY + headerHeight)
                }
                bump(headerHeight)
            }

            // 4. Normalize coordinates so minX/minY sit at padding.
            if (!minX.isFinite()) minX = 0f
            val dx = options.paddingX - minX
            val dy = options.paddingY - minY
            fun DiagramRect.shifted() = copy(x = x + dx, y = y + dy)

            val positionedParticipants = participants.mapIndexed { i, p ->
                val cx = actorX[i] + widths[i] / 2f + dx
                val top = DiagramRect(actorX[i] + dx, topBoxY + dy, widths[i], headerHeight)
                val bottom = bottomBoxY?.let { DiagramRect(actorX[i] + dx, it + dy, widths[i], headerHeight) }
                PositionedParticipant(
                    participant = p,
                    topRect = top,
                    bottomRect = bottom,
                    lifelineX = cx,
                    lifelineTopY = topBoxY + headerHeight + dy,
                    lifelineBottomY = lifelineBottomY + dy,
                )
            }

            return SequenceDiagramLayout(
                width = (maxX - minX) + options.paddingX * 2f,
                height = (maxY - minY) + options.paddingY * 2f,
                titleRect = titleRect?.shifted(),
                participants = positionedParticipants,
                activations = finishedActivations.map { it.copy(rect = it.rect.shifted()) },
                messages = positionedMessages.map {
                    it.copy(
                        startX = it.startX + dx,
                        stopX = it.stopX + dx,
                        lineY = it.lineY + dy,
                        labelRect = it.labelRect.shifted(),
                    )
                },
                notes = positionedNotes.map { it.copy(rect = it.rect.shifted()) },
                blocks = positionedBlocks.map { block ->
                    block.copy(
                        rect = block.rect.shifted(),
                        tagRect = block.tagRect.shifted(),
                        labelRect = block.labelRect?.shifted(),
                        dividers = block.dividers.map { div ->
                            div.copy(y = div.y + dy, labelRect = div.labelRect?.shifted())
                        },
                    )
                },
            )
        }

        private fun collectMessageSpacing(
            statements: List<SequenceStatement>,
            msgIdx: IntArray,
            noteIdx: IntArray,
        ) {
            for (stmt in statements) {
                when (stmt) {
                    is SequenceStatement.Message -> {
                        val mIndex = msgIdx[0]++
                        val sz = messageLabelSize(mIndex, stmt)
                        val fromIdx = indexById[stmt.from] ?: continue
                        val toIdx = indexById[stmt.to] ?: continue
                        val msgWidth = sz.width + 2f * options.wrapPadding
                        if (fromIdx == toIdx) {
                            val need = msgWidth / 2f + options.actorMargin - widths[fromIdx] / 2f
                            margins[fromIdx] = max(margins[fromIdx], need)
                        } else if (abs(fromIdx - toIdx) == 1) {
                            val left = min(fromIdx, toIdx)
                            val right = max(fromIdx, toIdx)
                            val need = msgWidth + options.actorMargin - widths[left] / 2f - widths[right] / 2f
                            margins[left] = max(margins[left], need)
                        }
                    }
                    is SequenceStatement.Note -> {
                        val nIndex = noteIdx[0]++
                        val sz = noteTextSize(nIndex, stmt)
                        val firstId = stmt.participants.firstOrNull() ?: continue
                        val firstIdx = indexById[firstId] ?: continue
                        val noteW = max(options.actorWidth, sz.width + 2f * options.noteMargin)
                        if (stmt.placement == NotePlacement.RightOf) {
                            val rightHalf = if (firstIdx + 1 < widths.size) widths[firstIdx + 1] / 2f else 0f
                            val need = noteW + options.actorMargin - widths[firstIdx] / 2f - rightHalf
                            margins[firstIdx] = max(margins[firstIdx], need)
                        } else if (stmt.placement == NotePlacement.LeftOf && firstIdx > 0) {
                            val need = noteW + options.actorMargin - widths[firstIdx - 1] / 2f - widths[firstIdx] / 2f
                            margins[firstIdx - 1] = max(margins[firstIdx - 1], need)
                        }
                    }
                    is SequenceStatement.Block -> {
                        for (sec in stmt.sections) {
                            collectMessageSpacing(sec.statements, msgIdx, noteIdx)
                        }
                    }
                    else -> Unit
                }
            }
        }

        private fun activationBounds(participantId: String): Pair<Float, Float> {
            val idx = indexById[participantId] ?: return 0f to 0f
            val center = actorX[idx] + widths[idx] / 2f
            val stack = activeStacks[participantId]
            if (stack.isNullOrEmpty()) return (center - 1f * options.density) to (center + 1f * options.density)
            var left = center - 1f * options.density
            var right = center + 1f * options.density
            for (span in stack) {
                left = min(left, span.startX)
                right = max(right, span.stopX)
            }
            return left to right
        }

        private fun startActivation(participantId: String, atY: Float) {
            val idx = indexById[participantId] ?: return
            val center = actorX[idx] + widths[idx] / 2f
            val stack = activeStacks.getOrPut(participantId) { ArrayDeque() }
            val depth = stack.size
            val x = center + (depth - 1) * options.activationWidth / 2f
            stack.addLast(
                ActiveSpan(
                    participantId = participantId,
                    startX = x,
                    stopX = x + options.activationWidth,
                    startY = atY,
                    depth = depth,
                ),
            )
        }

        private fun endActivation(participantId: String, atY: Float) {
            val stack = activeStacks[participantId] ?: return
            if (stack.isEmpty()) return
            val span = stack.removeLast()
            var startY = span.startY
            var stopY = atY
            if (startY + 18f * options.density > stopY) {
                startY = stopY - 6f * options.density
                stopY += 12f * options.density
            }
            finishedActivations += PositionedActivation(
                participantId = participantId,
                rect = DiagramRect(span.startX, startY, span.stopX - span.startX, stopY - startY),
                depth = span.depth,
            )
            includeBounds(span.startX, startY, span.stopX, stopY)
        }

        private fun layoutStatements(statements: List<SequenceStatement>): Pair<Float, Float> {
            var spanMinX = Float.POSITIVE_INFINITY
            var spanMaxX = Float.NEGATIVE_INFINITY
            fun recordX(x0: Float, x1: Float) {
                spanMinX = min(spanMinX, min(x0, x1))
                spanMaxX = max(spanMaxX, max(x0, x1))
            }

            for (stmt in statements) {
                when (stmt) {
                    is SequenceStatement.Activate -> startActivation(stmt.participantId, verticalPos + 2f * options.density)
                    is SequenceStatement.Deactivate -> endActivation(stmt.participantId, verticalPos)
                    is SequenceStatement.Message -> {
                        val mIndex = msgCounter++
                        val number = if (diagram.autonumber) seqNum++ else null
                        val labelSz = messageLabelSize(mIndex, stmt)
                        val (fromLeft, fromRight) = activationBounds(stmt.from)
                        val (toLeft, toRight) = activationBounds(stmt.to)
                        val isSelf = stmt.from == stmt.to
                        val toRightDir = fromLeft <= toLeft
                        var startX = if (toRightDir) fromRight else fromLeft
                        var stopX = if (isSelf) startX else if (toRightDir) toLeft else toRight

                        if (!isSelf && stmt.activateTarget && activeStacks[stmt.to].isNullOrEmpty()) {
                            stopX += if (toRightDir) -(options.activationWidth / 2f - 1f * options.density)
                            else (options.activationWidth / 2f - 1f * options.density)
                        }

                        bump(options.boxMargin)
                        val labelY = verticalPos
                        bump(labelSz.height)
                        val lineY: Float
                        val labelRect: DiagramRect
                        if (isSelf) {
                            bump(options.boxMargin)
                            lineY = verticalPos
                            val loopW = max(36f * options.density, options.actorWidth * 0.35f)
                            labelRect = DiagramRect(
                                x = startX + options.boxTextMargin,
                                y = labelY,
                                width = labelSz.width,
                                height = labelSz.height,
                            )
                            val selfHeight = 25f * options.density
                            bump(selfHeight + options.boxTextMargin)
                            val rightExtent = max(startX + loopW, labelRect.right)
                            includeBounds(startX, labelY, rightExtent, verticalPos)
                            recordX(startX - options.boxMargin, rightExtent + options.boxMargin)
                        } else {
                            bump(options.boxTextMargin)
                            lineY = verticalPos
                            val midX = (startX + stopX) / 2f
                            labelRect = DiagramRect(
                                x = midX - labelSz.width / 2f,
                                y = labelY,
                                width = labelSz.width,
                                height = labelSz.height,
                            )
                            includeBounds(min(startX, labelRect.x), labelY, max(stopX, labelRect.right), lineY)
                            recordX(min(fromLeft, toLeft), max(fromRight, toRight))
                        }

                        if (stmt.activateTarget) {
                            startActivation(stmt.to, lineY)
                        }
                        if (stmt.deactivateSource) {
                            endActivation(stmt.from, lineY)
                        }

                        positionedMessages += PositionedSequenceMessage(
                            index = mIndex,
                            sequenceNumber = number,
                            statement = stmt,
                            startX = startX,
                            stopX = stopX,
                            lineY = lineY,
                            labelRect = labelRect,
                            isSelf = isSelf,
                        )
                    }
                    is SequenceStatement.Note -> {
                        val nIndex = noteCounter++
                        val textSz = noteTextSize(nIndex, stmt)
                        bump(options.boxMargin)
                        val startY = verticalPos
                        val firstIdx = indexById[stmt.participants.first()] ?: 0
                        val lastIdx = indexById[stmt.participants.last()] ?: firstIdx
                        val minIdx = min(firstIdx, lastIdx)
                        val maxIdx = max(firstIdx, lastIdx)
                        val noteW: Float
                        val noteX: Float
                        when (stmt.placement) {
                            NotePlacement.RightOf -> {
                                noteW = max(options.actorWidth * 0.75f, textSz.width + 2f * options.noteMargin)
                                noteX = actorX[maxIdx] + (widths[maxIdx] + options.actorMargin) / 2f
                            }
                            NotePlacement.LeftOf -> {
                                noteW = max(options.actorWidth * 0.75f, textSz.width + 2f * options.noteMargin)
                                noteX = actorX[minIdx] - noteW + (widths[minIdx] - options.actorMargin) / 2f
                            }
                            NotePlacement.Over -> {
                                if (minIdx == maxIdx) {
                                    noteW = max(widths[minIdx], textSz.width + 2f * options.noteMargin)
                                    noteX = actorX[minIdx] + (widths[minIdx] - noteW) / 2f
                                } else {
                                    val spanCenters = (actorX[maxIdx] + widths[maxIdx] / 2f) - (actorX[minIdx] + widths[minIdx] / 2f)
                                    noteW = max(spanCenters + options.actorMargin, textSz.width + 2f * options.noteMargin)
                                    val mid = ((actorX[minIdx] + widths[minIdx] / 2f) + (actorX[maxIdx] + widths[maxIdx] / 2f)) / 2f
                                    noteX = mid - noteW / 2f
                                }
                            }
                        }
                        val noteH = textSz.height + 2f * options.noteMargin
                        val rect = DiagramRect(noteX, startY, noteW, noteH)
                        bump(noteH)
                        includeBounds(rect.x, rect.y, rect.right, rect.bottom)
                        recordX(rect.x, rect.right)
                        positionedNotes += PositionedSequenceNote(nIndex, stmt, rect)
                    }
                    is SequenceStatement.Block -> {
                        val bIndex = blockCounter++
                        // Reserve parent slot before child blocks so backgrounds draw parent-first.
                        val slotIndex = positionedBlocks.size
                        positionedBlocks += PositionedSequenceBlock(
                            index = bIndex,
                            kind = stmt.kind,
                            tag = "",
                            label = stmt.label,
                            rect = DiagramRect(0f, 0f, 0f, 0f),
                            tagRect = DiagramRect(0f, 0f, 0f, 0f),
                            labelRect = null,
                            dividers = emptyList(),
                        )
                        val tag = stmt.kind.name.lowercase()
                        bump(options.boxMargin)
                        val blockStartY = verticalPos
                        val headerTextSize = stmt.label?.let { blockLabelSize(bIndex, 0, "[$it]") }
                        val headerH = max(options.labelBoxHeight, headerTextSize?.height ?: 0f) + options.boxTextMargin
                        bump(headerH)

                        var blockMinX = Float.POSITIVE_INFINITY
                        var blockMaxX = Float.NEGATIVE_INFINITY
                        var maxSectionLabelW = 0f
                        val dividers = mutableListOf<PositionedSequenceDivider>()

                        for ((secIdx, sec) in stmt.sections.withIndex()) {
                            if (secIdx > 0) {
                                bump(options.boxMargin)
                                val divY = verticalPos
                                val secSize = sec.label?.let { blockLabelSize(bIndex, secIdx, "[$it]") }
                                if (secSize != null) {
                                    maxSectionLabelW = max(maxSectionLabelW, secSize.width)
                                }
                                bump(options.boxTextMargin + (secSize?.height ?: 0f))
                                dividers += PositionedSequenceDivider(
                                    y = divY,
                                    label = sec.label,
                                    labelRect = secSize?.let {
                                        DiagramRect(0f, divY + options.boxTextMargin, it.width, it.height)
                                    },
                                )
                            }
                            val (sMinX, sMaxX) = layoutStatements(sec.statements)
                            if (sMinX.isFinite()) {
                                blockMinX = min(blockMinX, sMinX)
                                blockMaxX = max(blockMaxX, sMaxX)
                            }
                        }
                        bump(options.boxMargin)
                        val blockStopY = verticalPos
                        if (!blockMinX.isFinite()) {
                            blockMinX = actorX.first()
                            blockMaxX = actorX.last() + widths.last()
                        } else {
                            // Leave room for the 50dp tag tab to the left of the leftmost
                            // enclosed lifeline/activation bar, plus padding on the right.
                            blockMinX -= (options.labelBoxWidth + options.boxMargin * 1.5f)
                            blockMaxX += options.boxMargin * 2.5f
                        }
                        val minHeaderW = options.labelBoxWidth + (headerTextSize?.width ?: 0f) + options.boxMargin * 3f
                        val minSecW = maxSectionLabelW + options.boxMargin * 3f
                        val minBlockW = max(minHeaderW, minSecW)
                        if (blockMaxX - blockMinX < minBlockW) {
                            val mid = (blockMinX + blockMaxX) / 2f
                            blockMinX = mid - minBlockW / 2f
                            blockMaxX = mid + minBlockW / 2f
                        }
                        val blockRect = DiagramRect(blockMinX, blockStartY, blockMaxX - blockMinX, blockStopY - blockStartY)
                        val tagRect = DiagramRect(blockMinX, blockStartY, options.labelBoxWidth, options.labelBoxHeight)
                        val labelRect = headerTextSize?.let {
                            val availStart = blockMinX + options.labelBoxWidth + options.boxTextMargin
                            val centeredX = (availStart + blockMaxX - it.width) / 2f
                            DiagramRect(
                                x = max(availStart, centeredX),
                                y = blockStartY + (options.labelBoxHeight - it.height) / 2f,
                                width = it.width,
                                height = it.height,
                            )
                        }
                        val placedDividers = dividers.map { div ->
                            div.copy(
                                labelRect = div.labelRect?.let { r ->
                                    r.copy(x = blockMinX + (blockRect.width - r.width) / 2f)
                                },
                            )
                        }
                        includeBounds(blockRect.x, blockRect.y, blockRect.right, blockRect.bottom)
                        recordX(blockRect.x, blockRect.right)
                        positionedBlocks[slotIndex] = PositionedSequenceBlock(
                            index = bIndex,
                            kind = stmt.kind,
                            tag = tag,
                            label = stmt.label,
                            rect = blockRect,
                            tagRect = tagRect,
                            labelRect = labelRect,
                            dividers = placedDividers,
                        )
                    }
                }
            }
            return spanMinX to spanMaxX
        }
    }
}
