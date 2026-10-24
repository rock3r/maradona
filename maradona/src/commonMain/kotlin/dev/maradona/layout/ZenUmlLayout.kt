package dev.maradona.layout

import dev.maradona.model.ZenUmlControlKind
import dev.maradona.model.ZenUmlDiagram
import dev.maradona.model.ZenUmlMessageKind
import dev.maradona.model.ZenUmlParticipant
import dev.maradona.model.ZenUmlParticipantGroup
import dev.maradona.model.ZenUmlParticipantStereotype
import dev.maradona.model.ZenUmlStatement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Positioned lifeline participant header(s) and vertical lifeline in a laid-out [ZenUmlDiagram].
 */
data class PositionedZenUmlParticipant(
    val participant: ZenUmlParticipant,
    val topRect: DiagramRect,
    val bottomRect: DiagramRect?,
    val lifelineX: Float,
    val lifelineTopY: Float,
    val lifelineBottomY: Float,
)

/**
 * Positioned participant group box (`group Name { ... }`) in a laid-out [ZenUmlDiagram].
 */
data class PositionedZenUmlGroup(
    val group: ZenUmlParticipantGroup,
    val rect: DiagramRect,
    val headerRect: DiagramRect,
)

/**
 * Positioned execution activation bar on a participant lifeline.
 */
data class PositionedZenUmlActivation(
    val participantId: String,
    val depth: Int,
    val rect: DiagramRect,
)

/**
 * Positioned interaction arrow (sync call, async message, creation, or return) in a laid-out [ZenUmlDiagram].
 */
data class PositionedZenUmlMessage(
    val messageIndex: Int,
    val fromId: String,
    val toId: String,
    val displayText: String,
    val kind: ZenUmlMessageKind,
    val y: Float,
    val startX: Float,
    val endX: Float,
    val selfLoopHeight: Float,
    val labelRect: DiagramRect,
    val commentText: String? = null,
    val commentRect: DiagramRect? = null,
) {
    val isSelf: Boolean get() = fromId == toId
}

/**
 * Positioned horizontal branch divider inside a [PositionedZenUmlBlock] (`else if`, `else`, `catch`, `finally`).
 */
data class PositionedZenUmlDivider(
    val y: Float,
    val label: String,
    val labelRect: DiagramRect,
)

/**
 * Positioned combined fragment / control flow frame (`if`/`else`, `while`/`loop`, `opt`, `par`, `try`/`catch`).
 */
data class PositionedZenUmlBlock(
    val blockIndex: Int,
    val kind: ZenUmlControlKind,
    val keyword: String,
    val condition: String?,
    val rect: DiagramRect,
    val tabRect: DiagramRect,
    val conditionRect: DiagramRect?,
    val dividers: List<PositionedZenUmlDivider>,
)

/**
 * Complete layout geometry for a [ZenUmlDiagram].
 */
data class ZenUmlDiagramLayout(
    val width: Float,
    val height: Float,
    val participants: List<PositionedZenUmlParticipant>,
    val groups: List<PositionedZenUmlGroup>,
    val activations: List<PositionedZenUmlActivation>,
    val messages: List<PositionedZenUmlMessage>,
    val blocks: List<PositionedZenUmlBlock>,
    val titleRect: DiagramRect?,
)

/**
 * Flattened visual item descriptors used by both [ZenUmlLayout] and `MaradonaZenUmlLayout` so
 * composable measurement IDs match layout indices 1:1.
 */
internal data class FlattenedZenUmlMessageSpec(
    val index: Int,
    val fromId: String,
    val toId: String,
    val displayText: String,
    val kind: ZenUmlMessageKind,
    val commentText: String?,
)

internal data class FlattenedZenUmlBlockSpec(
    val index: Int,
    val kind: ZenUmlControlKind,
    val keyword: String,
    val condition: String?,
    val dividerLabels: List<String>,
)

/**
 * Computes participant lifelines, nested activation bars, interaction arrows, and control frames for a [ZenUmlDiagram].
 */
object ZenUmlLayout {

    private const val OUTER_MARGIN_X = 24f
    private const val OUTER_MARGIN_Y = 20f
    private const val PARTICIPANT_MIN_W = 116f
    private const val PARTICIPANT_H = 48f
    private const val STEREOTYPE_EXTRA_H = 16f
    private const val PARTICIPANT_GAP_X = 52f
    private const val ACTIVATION_WIDTH = 12f
    private const val ACTIVATION_NEST_OFFSET = 5f
    private const val SELF_LOOP_WIDTH = 38f
    private const val SELF_LOOP_HEIGHT = 24f
    private const val ROW_STEP_Y = 44f
    private const val GROUP_HEADER_H = 26f
    private const val GROUP_PAD_X = 12f
    private const val GROUP_PAD_TOP = 8f
    private const val TITLE_GAP = 16f

    fun defaultParticipantSize(p: ZenUmlParticipant, density: Float = 1f): DiagramSize {
        val stereoLen = (p.rawAnnotation?.length ?: 0) + 4
        val maxChars = max(p.label.length, stereoLen).coerceAtLeast(6)
        val w = max(PARTICIPANT_MIN_W, maxChars * 8.2f + 28f) * density
        val extraH = if (p.stereotype != ZenUmlParticipantStereotype.DEFAULT || !p.rawAnnotation.isNullOrBlank()) {
            STEREOTYPE_EXTRA_H
        } else {
            0f
        }
        return DiagramSize(w, (PARTICIPANT_H + extraH) * density)
    }

    fun defaultMessageLabelSize(text: String, density: Float = 1f): DiagramSize {
        val len = text.length.coerceAtLeast(3)
        return DiagramSize((len * 7.0f + 14f) * density, 18f * density)
    }

    fun defaultCommentSize(text: String, density: Float = 1f): DiagramSize {
        val lines = text.lines()
        val maxLen = (lines.maxOfOrNull { it.length } ?: 6).coerceAtLeast(4)
        return DiagramSize(
            width = (maxLen * 6.5f + 16f).coerceAtMost(240f) * density,
            height = (lines.size * 15f + 8f) * density,
        )
    }

    fun defaultTitleSize(text: String, density: Float = 1f): DiagramSize =
        DiagramSize((text.length * 9.5f + 12f) * density, 24f * density)

    internal fun collectSpecs(diagram: ZenUmlDiagram): Pair<List<FlattenedZenUmlMessageSpec>, List<FlattenedZenUmlBlockSpec>> {
        val messages = mutableListOf<FlattenedZenUmlMessageSpec>()
        val blocks = mutableListOf<FlattenedZenUmlBlockSpec>()

        fun visitStatements(stmts: List<ZenUmlStatement>) {
            for (stmt in stmts) {
                when (stmt) {
                    is ZenUmlStatement.Message -> {
                        val label = buildMessageDisplayText(stmt)
                        messages += FlattenedZenUmlMessageSpec(
                            index = messages.size,
                            fromId = stmt.from,
                            toId = stmt.to,
                            displayText = label,
                            kind = stmt.kind,
                            commentText = stmt.comment,
                        )
                        visitStatements(stmt.nestedStatements)
                        val hasExplicitReturn = containsExplicitReturnInCallScope(stmt.nestedStatements)
                        if (!stmt.returnVar.isNullOrBlank() && !hasExplicitReturn && stmt.from != stmt.to) {
                            messages += FlattenedZenUmlMessageSpec(
                                index = messages.size,
                                fromId = stmt.to,
                                toId = stmt.from,
                                displayText = stmt.returnVar,
                                kind = ZenUmlMessageKind.RETURN,
                                commentText = null,
                            )
                        }
                    }
                    is ZenUmlStatement.ControlBlock -> {
                        if (!stmt.comment.isNullOrBlank()) {
                            val target = diagram.participants.firstOrNull()?.id ?: "_STARTER_"
                            messages += FlattenedZenUmlMessageSpec(
                                index = messages.size,
                                fromId = target,
                                toId = target,
                                displayText = "",
                                kind = ZenUmlMessageKind.ASYNC,
                                commentText = stmt.comment,
                            )
                        }
                        val firstBranch = stmt.branches.firstOrNull()
                        val divLabels = stmt.branches.drop(1).map { b ->
                            if (!b.condition.isNullOrBlank()) "${b.keyword} (${b.condition})" else b.keyword
                        }
                        blocks += FlattenedZenUmlBlockSpec(
                            index = blocks.size,
                            kind = stmt.kind,
                            keyword = firstBranch?.keyword ?: stmt.kind.name.lowercase(),
                            condition = firstBranch?.condition,
                            dividerLabels = divLabels,
                        )
                        for (b in stmt.branches) {
                            visitStatements(b.statements)
                        }
                    }
                    is ZenUmlStatement.NoteComment -> {
                        val target = stmt.onParticipant ?: diagram.participants.firstOrNull()?.id ?: "_STARTER_"
                        messages += FlattenedZenUmlMessageSpec(
                            index = messages.size,
                            fromId = target,
                            toId = target,
                            displayText = "",
                            kind = ZenUmlMessageKind.ASYNC,
                            commentText = stmt.text,
                        )
                    }
                }
            }
        }

        visitStatements(diagram.statements)
        return messages to blocks
    }

    private fun containsExplicitReturnInCallScope(stmts: List<ZenUmlStatement>): Boolean {
        for (s in stmts) {
            when (s) {
                is ZenUmlStatement.Message -> if (s.kind == ZenUmlMessageKind.RETURN) return true
                is ZenUmlStatement.ControlBlock -> {
                    if (s.branches.any { b -> containsExplicitReturnInCallScope(b.statements) }) return true
                }
                is ZenUmlStatement.NoteComment -> Unit
            }
        }
        return false
    }

    private fun buildMessageDisplayText(msg: ZenUmlStatement.Message): String {
        if (msg.kind == ZenUmlMessageKind.RETURN) return msg.text
        val prefix = buildString {
            if (!msg.returnType.isNullOrBlank()) append(msg.returnType).append(' ')
            if (!msg.returnVar.isNullOrBlank()) append(msg.returnVar).append(" = ")
        }
        return "$prefix${msg.text}"
    }

    fun layout(
        diagram: ZenUmlDiagram,
        participantSizes: Map<String, DiagramSize> = emptyMap(),
        groupHeaderSizes: Map<Int, DiagramSize> = emptyMap(),
        messageLabelSizes: Map<Int, DiagramSize> = emptyMap(),
        commentSizes: Map<Int, DiagramSize> = emptyMap(),
        blockTabSizes: Map<Int, DiagramSize> = emptyMap(),
        blockCondSizes: Map<Int, DiagramSize> = emptyMap(),
        blockDividerSizes: Map<Pair<Int, Int>, DiagramSize> = emptyMap(),
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): ZenUmlDiagramLayout {
        val d = options.density.coerceAtLeast(0.01f)
        val marginX = OUTER_MARGIN_X * d
        val marginY = OUTER_MARGIN_Y * d
        val baseGapX = PARTICIPANT_GAP_X * d
        val actW = ACTIVATION_WIDTH * d
        val actNestOff = ACTIVATION_NEST_OFFSET * d
        val selfLoopW = SELF_LOOP_WIDTH * d
        val selfLoopH = SELF_LOOP_HEIGHT * d
        val rowStepY = ROW_STEP_Y * d

        val titleText = diagram.title?.takeIf { it.isNotBlank() }
        val resolvedTitleSize = if (titleText != null) {
            titleSize ?: defaultTitleSize(titleText, d)
        } else {
            null
        }
        val titleTopSpace = if (resolvedTitleSize != null) resolvedTitleSize.height + TITLE_GAP * d else 0f

        val (msgSpecs, blockSpecs) = collectSpecs(diagram)

        // Ensure all participants exist in order
        val participants = diagram.participants.ifEmpty {
            listOf(ZenUmlParticipant(id = "_STARTER_", label = "Starter"))
        }
        val pIndexById = participants.withIndex().associate { (i, p) -> p.id to i }
        val pSizes = participants.map { p -> participantSizes[p.id] ?: defaultParticipantSize(p, d) }

        // Compute horizontal centers for participants, ensuring adjacent and spanning messages fit
        val centerX = FloatArray(participants.size)
        for (i in participants.indices) {
            if (i == 0) {
                centerX[0] = marginX + pSizes[0].width / 2f
            } else {
                val prevGroup = participants[i - 1].groupName
                val curGroup = participants[i].groupName
                val groupExtra = if (prevGroup != curGroup) GROUP_PAD_X * 2f * d else 0f
                val minGap = (pSizes[i - 1].width + pSizes[i].width) / 2f + baseGapX + groupExtra
                centerX[i] = centerX[i - 1] + minGap
            }
        }

        // Reserve horizontal space for wide group headers so neighboring groups never overlap
        for ((gIdx, grp) in diagram.groups.withIndex()) {
            val memberIndices = grp.participantIds.mapNotNull { pIndexById[it] }.sorted()
            if (memberIndices.isEmpty()) continue
            val firstIdx = memberIndices.first()
            val lastIdx = memberIndices.last()
            val rawLeft = centerX[firstIdx] - pSizes[firstIdx].width / 2f - GROUP_PAD_X * d
            val rawRight = centerX[lastIdx] + pSizes[lastIdx].width / 2f + GROUP_PAD_X * d
            val rawW = rawRight - rawLeft
            val minHdrW = (groupHeaderSizes[gIdx]?.width ?: 0f) + 20f * d
            if (minHdrW > rawW) {
                val extra = minHdrW - rawW
                val halfExtra = extra / 2f
                for (k in firstIdx..lastIdx) {
                    centerX[k] += halfExtra
                }
                for (k in (lastIdx + 1) until participants.size) {
                    centerX[k] += extra
                }
            }
        }

        // Expand participant spacing if any message label between pA and pB is wider than current span
        for (spec in msgSpecs) {
            val idxA = pIndexById[spec.fromId] ?: continue
            val idxB = pIndexById[spec.toId] ?: continue
            if (idxA == idxB) continue
            val leftIdx = min(idxA, idxB)
            val rightIdx = max(idxA, idxB)
            val lblSize = messageLabelSizes[spec.index] ?: defaultMessageLabelSize(spec.displayText, d)
            val neededSpan = lblSize.width + 44f * d
            val currentSpan = centerX[rightIdx] - centerX[leftIdx]
            if (neededSpan > currentSpan) {
                val delta = neededSpan - currentSpan
                for (k in rightIdx until participants.size) {
                    centerX[k] += delta
                }
            }
        }

        val hasGroups = diagram.groups.isNotEmpty()
        val groupTopReserve = if (hasGroups) (GROUP_HEADER_H + GROUP_PAD_TOP) * d else 0f
        val maxParticipantHeaderH = pSizes.maxOfOrNull { it.height } ?: (PARTICIPANT_H * d)
        val headerTopY = marginY + titleTopSpace + groupTopReserve
        val lifelineStartY = headerTopY + maxParticipantHeaderH

        var curY = lifelineStartY + 24f * d
        var msgCursor = 0
        var blockCursor = 0

        val activations = mutableListOf<PositionedZenUmlActivation>()
        val positionedMessages = mutableListOf<PositionedZenUmlMessage>()
        val positionedBlocks = mutableListOf<PositionedZenUmlBlock>()
        val activeDepthByParticipant = HashMap<String, Int>()

        fun activationOffsetX(participantId: String): Float {
            val depth = activeDepthByParticipant[participantId] ?: 0
            return if (depth <= 1) 0f else (depth - 1) * actNestOff
        }

        fun layoutStatements(
            stmts: List<ZenUmlStatement>,
            blockNestDepth: Int,
            touchedParticipants: MutableSet<Int>,
        ) {
            for (stmt in stmts) {
                when (stmt) {
                    is ZenUmlStatement.Message -> {
                        val spec = msgSpecs[msgCursor++]
                        val fromIdx = pIndexById[spec.fromId] ?: 0
                        val toIdx = pIndexById[spec.toId] ?: 0
                        touchedParticipants += fromIdx
                        touchedParticipants += toIdx

                        val commText = spec.commentText
                        var commRect: DiagramRect? = null
                        if (!commText.isNullOrBlank()) {
                            val cSize = commentSizes[spec.index] ?: defaultCommentSize(commText, d)
                            val anchorX = centerX[fromIdx] + 10f * d
                            commRect = DiagramRect(anchorX, curY, cSize.width, cSize.height)
                            curY += cSize.height + 6f * d
                        }

                        val lblSize = messageLabelSizes[spec.index] ?: defaultMessageLabelSize(spec.displayText, d)
                        curY += lblSize.height + 6f * d
                        val msgY = curY

                        val newToDepth = if (stmt.kind == ZenUmlMessageKind.SYNC || stmt.kind == ZenUmlMessageKind.CREATE) {
                            val nextD = (activeDepthByParticipant[spec.toId] ?: 0) + 1
                            activeDepthByParticipant[spec.toId] = nextD
                            nextD
                        } else {
                            activeDepthByParticipant[spec.toId] ?: 0
                        }

                        val fromDepth = activeDepthByParticipant[spec.fromId] ?: 0
                        val fromCenter = centerX[fromIdx] + (if (fromDepth > 1) (fromDepth - 1) * actNestOff else 0f)
                        val toCenter = centerX[toIdx] + (if (newToDepth > 1) (newToDepth - 1) * actNestOff else 0f)

                        val startX: Float
                        val endX: Float
                        val lblRect: DiagramRect
                        if (fromIdx == toIdx) {
                            startX = fromCenter + (if (fromDepth > 0) actW / 2f else 0f)
                            endX = toCenter + (if (newToDepth > 0) actW / 2f else 0f)
                            lblRect = DiagramRect(
                                x = max(startX, endX) + 8f * d,
                                y = msgY - lblSize.height - 2f * d,
                                width = lblSize.width,
                                height = lblSize.height,
                            )
                            curY += selfLoopH + 12f * d
                        } else {
                            val goingRight = toCenter > fromCenter
                            val fromHalf = if (fromDepth > 0) actW / 2f else 0f
                            val toHalf = if (newToDepth > 0) actW / 2f else 0f
                            startX = if (goingRight) fromCenter + fromHalf else fromCenter - fromHalf
                            endX = if (goingRight) toCenter - toHalf else toCenter + toHalf
                            val midX = (startX + endX) / 2f
                            lblRect = DiagramRect(
                                x = midX - lblSize.width / 2f,
                                y = msgY - lblSize.height - 4f * d,
                                width = lblSize.width,
                                height = lblSize.height,
                            )
                            curY += rowStepY * 0.55f
                        }

                        positionedMessages += PositionedZenUmlMessage(
                            messageIndex = spec.index,
                            fromId = spec.fromId,
                            toId = spec.toId,
                            displayText = spec.displayText,
                            kind = spec.kind,
                            y = msgY,
                            startX = startX,
                            endX = endX,
                            selfLoopHeight = if (fromIdx == toIdx) selfLoopH else 0f,
                            labelRect = lblRect,
                            commentText = commText,
                            commentRect = commRect,
                        )

                        if (stmt.kind == ZenUmlMessageKind.SYNC || stmt.kind == ZenUmlMessageKind.CREATE) {
                            val actTopY = msgY
                            if (stmt.nestedStatements.isNotEmpty()) {
                                layoutStatements(stmt.nestedStatements, blockNestDepth, touchedParticipants)
                            } else {
                                curY = max(curY, actTopY + 20f * d)
                            }

                            val hasExplicitReturn = containsExplicitReturnInCallScope(stmt.nestedStatements)
                            if (!stmt.returnVar.isNullOrBlank() && !hasExplicitReturn && stmt.from != stmt.to) {
                                val retSpec = msgSpecs[msgCursor++]
                                val retLblSize = messageLabelSizes[retSpec.index]
                                    ?: defaultMessageLabelSize(retSpec.displayText, d)
                                curY += retLblSize.height + 4f * d
                                val retY = curY
                                val goingRight = fromCenter > toCenter
                                val retStartX = if (goingRight) toCenter + actW / 2f else toCenter - actW / 2f
                                val retEndX = if (goingRight) {
                                    fromCenter - (if (fromDepth > 0) actW / 2f else 0f)
                                } else {
                                    fromCenter + (if (fromDepth > 0) actW / 2f else 0f)
                                }
                                val retMidX = (retStartX + retEndX) / 2f
                                positionedMessages += PositionedZenUmlMessage(
                                    messageIndex = retSpec.index,
                                    fromId = retSpec.fromId,
                                    toId = retSpec.toId,
                                    displayText = retSpec.displayText,
                                    kind = ZenUmlMessageKind.RETURN,
                                    y = retY,
                                    startX = retStartX,
                                    endX = retEndX,
                                    selfLoopHeight = 0f,
                                    labelRect = DiagramRect(
                                        x = retMidX - retLblSize.width / 2f,
                                        y = retY - retLblSize.height - 4f * d,
                                        width = retLblSize.width,
                                        height = retLblSize.height,
                                    ),
                                )
                                curY += 14f * d
                            }

                            val actBottomY = max(actTopY + 18f * d, curY - 6f * d)
                            val actCenterX = centerX[toIdx] + (if (newToDepth > 1) (newToDepth - 1) * actNestOff else 0f)
                            activations += PositionedZenUmlActivation(
                                participantId = spec.toId,
                                depth = newToDepth,
                                rect = DiagramRect(
                                    x = actCenterX - actW / 2f,
                                    y = actTopY,
                                    width = actW,
                                    height = actBottomY - actTopY,
                                ),
                            )
                            activeDepthByParticipant[spec.toId] = (newToDepth - 1).coerceAtLeast(0)
                        } else if (stmt.nestedStatements.isNotEmpty()) {
                            layoutStatements(stmt.nestedStatements, blockNestDepth, touchedParticipants)
                        }
                    }

                    is ZenUmlStatement.ControlBlock -> {
                        val blockCommSpec = if (!stmt.comment.isNullOrBlank()) msgSpecs[msgCursor++] else null
                        val blockCommSize = if (blockCommSpec != null) {
                            commentSizes[blockCommSpec.index] ?: defaultCommentSize(blockCommSpec.commentText ?: "", d)
                        } else {
                            null
                        }
                        val blockCommTopY = curY
                        if (blockCommSize != null) {
                            curY += blockCommSize.height + 6f * d
                        }

                        val bSpec = blockSpecs[blockCursor++]
                        val blockTopY = curY
                        val measuredTab = blockTabSizes[bSpec.index]
                        val measuredCond = blockCondSizes[bSpec.index]
                        val tabW = max(
                            (bSpec.keyword.length * 7.5f + 22f).coerceAtLeast(46f) * d,
                            (measuredTab?.width ?: 0f) + 12f * d,
                        )
                        val tabH = max(
                            22f * d,
                            max(measuredTab?.height ?: 0f, measuredCond?.height ?: 0f) + 4f * d,
                        )
                        val condW = if (!bSpec.condition.isNullOrBlank()) {
                            max((bSpec.condition.length * 7f + 20f) * d, measuredCond?.width ?: 0f)
                        } else {
                            0f
                        }
                        curY += tabH + 8f * d

                        val blockTouched = mutableSetOf<Int>()
                        val dividers = mutableListOf<PositionedZenUmlDivider>()
                        val msgStartIndex = positionedMessages.size
                        val blkStartIndex = positionedBlocks.size

                        for ((bIdx, branch) in stmt.branches.withIndex()) {
                            if (bIdx > 0) {
                                val divIdx = bIdx - 1
                                val divY = curY + 4f * d
                                val divLabel = bSpec.dividerLabels.getOrElse(divIdx) { branch.keyword }
                                val measuredDiv = blockDividerSizes[bSpec.index to divIdx]
                                val divW = max((divLabel.length * 6.8f + 16f) * d, measuredDiv?.width ?: 0f)
                                val divH = max(18f * d, measuredDiv?.height ?: 0f)
                                dividers += PositionedZenUmlDivider(
                                    y = divY,
                                    label = divLabel,
                                    labelRect = DiagramRect(0f, divY + 4f * d, divW, divH),
                                )
                                curY = divY + divH + 10f * d
                            }
                            layoutStatements(branch.statements, blockNestDepth + 1, blockTouched)
                            if (branch.statements.isEmpty()) {
                                curY += 20f * d
                            }
                        }

                        curY += 10f * d
                        val blockBottomY = curY
                        curY += 12f * d

                        touchedParticipants.addAll(blockTouched)
                        val minPIdx = (blockTouched.minOrNull() ?: 0).coerceIn(0, participants.lastIndex)
                        val maxPIdx = (blockTouched.maxOrNull() ?: min(1, participants.lastIndex))
                            .coerceIn(minPIdx, participants.lastIndex)
                        val inset = (blockNestDepth * 8f) * d
                        var leftX = centerX[minPIdx] - pSizes[minPIdx].width / 2f - 14f * d + inset
                        var rightX = max(
                            centerX[maxPIdx] + pSizes[maxPIdx].width / 2f + 14f * d - inset,
                            leftX + tabW + condW + 32f * d,
                        )
                        // Expand control frame horizontally so all child messages, self-loops, comments, dividers, and nested blocks fit inside
                        for (mIdx in msgStartIndex until positionedMessages.size) {
                            val childM = positionedMessages[mIdx]
                            if (childM.labelRect.width > 0f) {
                                leftX = min(leftX, childM.labelRect.x - 10f * d)
                                rightX = max(rightX, childM.labelRect.right + 10f * d)
                            }
                            if (childM.isSelf) {
                                rightX = max(rightX, max(childM.startX, childM.endX) + selfLoopW + 12f * d)
                            }
                            childM.commentRect?.let { cr ->
                                leftX = min(leftX, cr.x - 10f * d)
                                rightX = max(rightX, cr.right + 10f * d)
                            }
                        }
                        for (cBlkIdx in blkStartIndex until positionedBlocks.size) {
                            val childB = positionedBlocks[cBlkIdx]
                            leftX = min(leftX, childB.rect.x - 8f * d)
                            rightX = max(rightX, childB.rect.right + 8f * d)
                        }
                        for (div in dividers) {
                            rightX = max(rightX, leftX + 20f * d + div.labelRect.width)
                        }

                        if (blockCommSpec != null && blockCommSize != null) {
                            val cRect = DiagramRect(leftX, blockCommTopY, blockCommSize.width, blockCommSize.height)
                            positionedMessages += PositionedZenUmlMessage(
                                messageIndex = blockCommSpec.index,
                                fromId = blockCommSpec.fromId,
                                toId = blockCommSpec.toId,
                                displayText = "",
                                kind = ZenUmlMessageKind.ASYNC,
                                y = cRect.centerY,
                                startX = leftX,
                                endX = leftX,
                                selfLoopHeight = 0f,
                                labelRect = DiagramRect(leftX, cRect.y, 0f, 0f),
                                commentText = blockCommSpec.commentText,
                                commentRect = cRect,
                            )
                        }

                        val bRect = DiagramRect(leftX, blockTopY, rightX - leftX, blockBottomY - blockTopY)
                        val tRect = DiagramRect(leftX, blockTopY, tabW, tabH)
                        val condRect = if (condW > 0f) {
                            DiagramRect(tRect.right + 8f * d, blockTopY + 2f * d, condW, tabH - 4f * d)
                        } else {
                            null
                        }
                        val placedDividers = dividers.map { div ->
                            div.copy(
                                labelRect = div.labelRect.copy(x = leftX + 10f * d),
                            )
                        }
                        positionedBlocks += PositionedZenUmlBlock(
                            blockIndex = bSpec.index,
                            kind = bSpec.kind,
                            keyword = bSpec.keyword,
                            condition = bSpec.condition,
                            rect = bRect,
                            tabRect = tRect,
                            conditionRect = condRect,
                            dividers = placedDividers,
                        )
                    }

                    is ZenUmlStatement.NoteComment -> {
                        val spec = msgSpecs[msgCursor++]
                        val pIdx = pIndexById[spec.fromId] ?: 0
                        touchedParticipants += pIdx
                        val cText = spec.commentText ?: ""
                        val cSize = commentSizes[spec.index] ?: defaultCommentSize(cText, d)
                        val cRect = DiagramRect(centerX[pIdx] + 10f * d, curY, cSize.width, cSize.height)
                        curY += cSize.height + 10f * d
                        positionedMessages += PositionedZenUmlMessage(
                            messageIndex = spec.index,
                            fromId = spec.fromId,
                            toId = spec.toId,
                            displayText = "",
                            kind = ZenUmlMessageKind.ASYNC,
                            y = cRect.centerY,
                            startX = centerX[pIdx],
                            endX = centerX[pIdx],
                            selfLoopHeight = 0f,
                            labelRect = DiagramRect(centerX[pIdx], cRect.y, 0f, 0f),
                            commentText = cText,
                            commentRect = cRect,
                        )
                    }
                }
            }
        }

        layoutStatements(diagram.statements, blockNestDepth = 0, touchedParticipants = mutableSetOf())

        val lifelineEndY = curY + 16f * d
        val positionedParticipants = participants.mapIndexed { i, p ->
            val sz = pSizes[i]
            val cx = centerX[i]
            val topRect = DiagramRect(
                x = cx - sz.width / 2f,
                y = headerTopY + (maxParticipantHeaderH - sz.height),
                width = sz.width,
                height = sz.height,
            )
            val bottomRect = if (diagram.mirrorParticipants) {
                DiagramRect(
                    x = cx - sz.width / 2f,
                    y = lifelineEndY,
                    width = sz.width,
                    height = sz.height,
                )
            } else {
                null
            }
            PositionedZenUmlParticipant(
                participant = p,
                topRect = topRect,
                bottomRect = bottomRect,
                lifelineX = cx,
                lifelineTopY = topRect.bottom,
                lifelineBottomY = lifelineEndY,
            )
        }

        val positionedGroups = diagram.groups.mapIndexedNotNull { gIdx, grp ->
            val memberParticipants = positionedParticipants.filter { it.participant.id in grp.participantIds }
            if (memberParticipants.isEmpty()) return@mapIndexedNotNull null
            val rawMinX = memberParticipants.minOf { it.topRect.x } - GROUP_PAD_X * d
            val rawMaxX = memberParticipants.maxOf { it.topRect.right } + GROUP_PAD_X * d
            val rawW = rawMaxX - rawMinX
            val hdrSize = groupHeaderSizes[gIdx]
            val minHdrW = (hdrSize?.width ?: 0f) + 20f * d
            val boxW = max(rawW, minHdrW)
            val boxX = (rawMinX + rawMaxX) / 2f - boxW / 2f
            val topY = headerTopY - groupTopReserve
            val bottomY = (if (diagram.mirrorParticipants) {
                memberParticipants.maxOf { it.bottomRect?.bottom ?: it.lifelineBottomY }
            } else {
                lifelineEndY
            }) + 8f * d
            val rect = DiagramRect(boxX, topY, boxW, bottomY - topY)
            val headerRect = DiagramRect(boxX, topY, boxW, max(GROUP_HEADER_H * d, hdrSize?.height ?: 0f))
            PositionedZenUmlGroup(
                group = grp,
                rect = rect,
                headerRect = headerRect,
            )
        }

        // Compute overall bounds including groups, blocks, self-loops, comments, and title
        var maxX = positionedParticipants.maxOfOrNull { it.topRect.right } ?: (200f * d)
        var maxY = if (diagram.mirrorParticipants) {
            positionedParticipants.maxOfOrNull { it.bottomRect?.bottom ?: lifelineEndY } ?: lifelineEndY
        } else {
            lifelineEndY
        }
        var minX = positionedParticipants.minOfOrNull { it.topRect.x } ?: marginX

        for (grp in positionedGroups) {
            minX = min(minX, grp.rect.x)
            maxX = max(maxX, grp.rect.right)
            maxY = max(maxY, grp.rect.bottom)
        }
        for (blk in positionedBlocks) {
            minX = min(minX, blk.rect.x)
            maxX = max(maxX, blk.rect.right)
            blk.conditionRect?.let { maxX = max(maxX, it.right) }
            maxY = max(maxY, blk.rect.bottom)
        }
        for (msg in positionedMessages) {
            maxX = max(maxX, msg.labelRect.right)
            if (msg.isSelf) {
                maxX = max(maxX, max(msg.startX, msg.endX) + selfLoopW + 8f * d)
            }
            msg.commentRect?.let { maxX = max(maxX, it.right) }
        }

        val shiftX = if (minX < marginX) marginX - minX else 0f
        val contentWidth = maxX + shiftX + marginX
        val minTitleWidth = if (resolvedTitleSize != null) resolvedTitleSize.width + marginX * 2f else 0f
        val totalWidth = max(contentWidth, minTitleWidth)
        val totalHeight = maxY + marginY

        val titleRect = if (resolvedTitleSize != null) {
            DiagramRect(
                x = (totalWidth - resolvedTitleSize.width) / 2f,
                y = marginY,
                width = resolvedTitleSize.width,
                height = resolvedTitleSize.height,
            )
        } else {
            null
        }

        fun shiftRect(r: DiagramRect): DiagramRect =
            if (shiftX == 0f) r else DiagramRect(r.x + shiftX, r.y, r.width, r.height)

        return ZenUmlDiagramLayout(
            width = totalWidth,
            height = totalHeight,
            participants = if (shiftX == 0f) {
                positionedParticipants
            } else {
                positionedParticipants.map {
                    it.copy(
                        topRect = shiftRect(it.topRect),
                        bottomRect = it.bottomRect?.let { br -> shiftRect(br) },
                        lifelineX = it.lifelineX + shiftX,
                    )
                }
            },
            groups = if (shiftX == 0f) {
                positionedGroups
            } else {
                positionedGroups.map {
                    it.copy(rect = shiftRect(it.rect), headerRect = shiftRect(it.headerRect))
                }
            },
            activations = activations.sortedBy { it.depth }.map {
                if (shiftX == 0f) it else it.copy(rect = shiftRect(it.rect))
            },
            messages = positionedMessages.sortedBy { it.messageIndex }.map {
                if (shiftX == 0f) {
                    it
                } else {
                    it.copy(
                        startX = it.startX + shiftX,
                        endX = it.endX + shiftX,
                        labelRect = shiftRect(it.labelRect),
                        commentRect = it.commentRect?.let { cr -> shiftRect(cr) },
                    )
                }
            },
            blocks = positionedBlocks.sortedBy { it.blockIndex }.map { blk ->
                if (shiftX == 0f) {
                    blk
                } else {
                    blk.copy(
                        rect = shiftRect(blk.rect),
                        tabRect = shiftRect(blk.tabRect),
                        conditionRect = blk.conditionRect?.let { shiftRect(it) },
                        dividers = blk.dividers.map { d -> d.copy(labelRect = shiftRect(d.labelRect)) },
                    )
                }
            },
            titleRect = titleRect,
        )
    }
}
