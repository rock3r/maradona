package dev.maradona.layout

import dev.maradona.model.KanbanColumn
import dev.maradona.model.KanbanDiagram
import dev.maradona.model.KanbanTask
import kotlin.math.ceil
import kotlin.math.max

data class PositionedKanbanTask(
    val columnIndex: Int,
    val taskIndex: Int,
    val task: KanbanTask,
    val cardRect: DiagramRect,
    val labelRect: DiagramRect,
    val ticketRect: DiagramRect? = null,
    val assignedRect: DiagramRect? = null,
)

data class PositionedKanbanColumn(
    val columnIndex: Int,
    val column: KanbanColumn,
    val rect: DiagramRect,
    val headerRect: DiagramRect,
    val tasks: List<PositionedKanbanTask>,
)

data class KanbanDiagramLayout(
    val width: Float,
    val height: Float,
    val titleRect: DiagramRect?,
    val columns: List<PositionedKanbanColumn>,
) {
    fun column(idOrLabel: String): PositionedKanbanColumn? =
        columns.firstOrNull { it.column.id == idOrLabel || it.column.label == idOrLabel }

    fun task(idOrLabel: String): PositionedKanbanTask? =
        columns.asSequence().flatMap { it.tasks.asSequence() }
            .firstOrNull { it.task.id == idOrLabel || it.task.label == idOrLabel }
}

/**
 * Pure-Kotlin layout engine for Mermaid `kanban` diagrams.
 *
 * Positions Kanban columns left-to-right, stacks task cards vertically inside each column,
 * and places task descriptions alongside optional `ticket` (bottom-left) and `assigned`
 * (bottom-right) metadata badges.
 */
object KanbanLayout {

    fun layout(
        diagram: KanbanDiagram,
        columnHeaderSize: ((Int, KanbanColumn) -> DiagramSize)? = null,
        taskLabelSize: ((Int, Int, KanbanTask, Float) -> DiagramSize)? = null,
        metaBadgeSize: ((String) -> DiagramSize)? = null,
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): KanbanDiagramLayout {
        val d = options.density
        val resolveHeaderSize = columnHeaderSize ?: { _, c -> defaultHeaderSize(c.label, d) }
        val resolveTaskLabelSize = taskLabelSize ?: { _, _, t, maxW -> defaultWrappedTaskSize(t.label, maxW, d) }
        val resolveMetaBadgeSize = metaBadgeSize ?: { s -> defaultBadgeSize(s, d) }
        val outerPad = 20f * d
        val colGap = 14f * d
        val cardInset = 10f * d
        val cardGap = 10f * d
        val cardPadX = 12f * d
        val cardPadY = 10f * d
        val priorityExtraPadLeft = 4f * d
        val metaTopGap = 6f * d
        val metaBetweenGap = 8f * d

        val resolvedTitleSize = if (!diagram.title.isNullOrBlank()) {
            titleSize ?: DiagramSize(max(120f * d, diagram.title.length * 9.5f * d), 24f * d)
        } else {
            null
        }

        var contentTopY = outerPad
        val titleTopY = contentTopY
        if (resolvedTitleSize != null) {
            contentTopY += resolvedTitleSize.height + 16f * d
        }

        if (diagram.columns.isEmpty()) {
            val totalW = max(240f * d, (resolvedTitleSize?.width ?: 0f) + outerPad * 2f)
            val totalH = contentTopY + outerPad + 40f * d
            val titleRect = resolvedTitleSize?.let {
                DiagramRect((totalW - it.width) / 2f, titleTopY, it.width, it.height)
            }
            return KanbanDiagramLayout(
                width = totalW,
                height = totalH,
                titleRect = titleRect,
                columns = emptyList(),
            )
        }

        val headerSizes = diagram.columns.mapIndexed { idx, col -> resolveHeaderSize(idx, col) }
        val maxHeaderH = headerSizes.maxOfOrNull { it.height } ?: (20f * d)
        val headerBandH = maxHeaderH + 22f * d

        val baseColW = max(120f, diagram.sectionWidth) * d
        val positionedColumns = mutableListOf<PositionedKanbanColumn>()
        var curColX = outerPad

        for ((colIdx, col) in diagram.columns.withIndex()) {
            val hdrSz = headerSizes[colIdx]

            // Ensure column is wide enough for its header and for any task's ticket + assigned row.
            var requiredCardW = max(100f * d, baseColW - cardInset * 2f)
            for (t in col.tasks) {
                val tktW = t.ticket?.takeIf { it.isNotBlank() }?.let { resolveMetaBadgeSize(it).width } ?: 0f
                val asgW = t.assigned?.takeIf { it.isNotBlank() }?.let { resolveMetaBadgeSize(it).width } ?: 0f
                val bothGap = if (tktW > 0f && asgW > 0f) metaBetweenGap else 0f
                val pExtra = if (t.priority != null) priorityExtraPadLeft else 0f
                val footerNeeded = cardPadX * 2f + pExtra + tktW + asgW + bothGap
                if (footerNeeded > requiredCardW) {
                    requiredCardW = footerNeeded
                }
            }
            val colW = maxOf(
                baseColW,
                hdrSz.width + cardInset * 2f + 12f * d,
                requiredCardW + cardInset * 2f,
            )
            val cardW = colW - cardInset * 2f

            val headerRect = DiagramRect(
                x = curColX + (colW - hdrSz.width) / 2f,
                y = contentTopY + (headerBandH - hdrSz.height) / 2f,
                width = hdrSz.width,
                height = hdrSz.height,
            )

            var curCardY = contentTopY + headerBandH
            val positionedTasks = mutableListOf<PositionedKanbanTask>()

            for ((taskIdx, task) in col.tasks.withIndex()) {
                val pExtra = if (task.priority != null) priorityExtraPadLeft else 0f
                val maxLabelW = max(40f * d, cardW - cardPadX * 2f - pExtra)
                val lblSz = resolveTaskLabelSize(colIdx, taskIdx, task, maxLabelW)

                val tktSz = task.ticket?.takeIf { it.isNotBlank() }?.let(resolveMetaBadgeSize)
                val asgSz = task.assigned?.takeIf { it.isNotBlank() }?.let(resolveMetaBadgeSize)
                val metaH = max(tktSz?.height ?: 0f, asgSz?.height ?: 0f)
                val hasMeta = metaH > 0f

                val cardH = cardPadY * 2f + lblSz.height + (if (hasMeta) metaTopGap + metaH else 0f)
                val cardX = curColX + cardInset
                val cardRect = DiagramRect(cardX, curCardY, cardW, cardH)

                val labelRect = DiagramRect(
                    x = cardX + cardPadX + pExtra,
                    y = curCardY + cardPadY,
                    width = minOf(lblSz.width, maxLabelW),
                    height = lblSz.height,
                )

                val metaRowCenterY = curCardY + cardPadY + lblSz.height + metaTopGap + metaH / 2f
                val ticketRect = tktSz?.let {
                    DiagramRect(
                        x = cardX + cardPadX + pExtra,
                        y = metaRowCenterY - it.height / 2f,
                        width = it.width,
                        height = it.height,
                    )
                }
                val assignedRect = asgSz?.let {
                    DiagramRect(
                        x = cardRect.right - cardPadX - it.width,
                        y = metaRowCenterY - it.height / 2f,
                        width = it.width,
                        height = it.height,
                    )
                }

                positionedTasks += PositionedKanbanTask(
                    columnIndex = colIdx,
                    taskIndex = taskIdx,
                    task = task,
                    cardRect = cardRect,
                    labelRect = labelRect,
                    ticketRect = ticketRect,
                    assignedRect = assignedRect,
                )
                curCardY += cardH + cardGap
            }

            val colH = if (positionedTasks.isNotEmpty()) {
                (curCardY - cardGap + cardInset) - contentTopY
            } else {
                headerBandH + 56f * d
            }
            val colRect = DiagramRect(curColX, contentTopY, colW, colH)
            positionedColumns += PositionedKanbanColumn(
                columnIndex = colIdx,
                column = col,
                rect = colRect,
                headerRect = headerRect,
                tasks = positionedTasks,
            )
            curColX += colW + colGap
        }

        val colsRight = positionedColumns.maxOf { it.rect.right }
        val colsBottom = positionedColumns.maxOf { it.rect.bottom }
        val totalW = max(
            colsRight + outerPad,
            (resolvedTitleSize?.width ?: 0f) + outerPad * 2f,
        )
        val totalH = colsBottom + outerPad

        val titleRect = resolvedTitleSize?.let {
            val boardCenterX = (outerPad + colsRight) / 2f
            val tx = (boardCenterX - it.width / 2f).coerceIn(outerPad, max(outerPad, totalW - outerPad - it.width))
            DiagramRect(tx, titleTopY, it.width, it.height)
        }

        return KanbanDiagramLayout(
            width = totalW,
            height = totalH,
            titleRect = titleRect,
            columns = positionedColumns,
        )
    }

    internal fun defaultHeaderSize(text: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(48f * density, (text.length * 7.8f + 12f) * density),
            height = 20f * density,
        )

    internal fun defaultWrappedTaskSize(text: String, maxWidth: Float, density: Float): DiagramSize {
        val rawW = (text.length * 7.0f + 6f) * density
        val lines = max(1, ceil(rawW / max(40f * density, maxWidth)).toInt())
        val w = minOf(maxWidth, max(36f * density, rawW))
        val h = (lines * 16f + 2f) * density
        return DiagramSize(w, h)
    }

    internal fun defaultBadgeSize(text: String, density: Float): DiagramSize =
        DiagramSize(
            width = max(24f * density, (text.length * 6.5f + 8f) * density),
            height = 15f * density,
        )
}
