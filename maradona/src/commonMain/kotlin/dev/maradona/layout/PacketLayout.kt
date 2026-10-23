package dev.maradona.layout

import dev.maradona.model.PacketBitOrder
import dev.maradona.model.PacketBlock
import dev.maradona.model.PacketDiagram
import kotlin.math.max

/**
 * Positioned block within a laid-out [PacketDiagram].
 */
data class PositionedPacketBlock(
    val blockIndex: Int,
    val block: PacketBlock,
    val rowIndex: Int,
    val colOffset: Int,
    val rect: DiagramRect,
    val leadingBitLabel: String,
    val trailingBitLabel: String,
    val isSingleBit: Boolean,
)

/**
 * Complete layout geometry for a [PacketDiagram].
 */
data class PacketDiagramLayout(
    val width: Float,
    val height: Float,
    val blocks: List<PositionedPacketBlock>,
    val showBits: Boolean,
    val bitOrder: PacketBitOrder,
    val bitsPerRow: Int,
    val titleRect: DiagramRect?,
)

/**
 * Computes word-row grid geometry for Mermaid `packet` / `packet-beta` diagrams.
 */
object PacketLayout {

    private const val OUTER_MARGIN = 16f
    private const val BIT_LABEL_HEIGHT = 16f
    private const val TITLE_GAP = 14f

    fun defaultTitleSize(text: String, density: Float = 1f): DiagramSize =
        DiagramSize(text.length * 9.5f * density, 22f * density)

    fun layout(
        diagram: PacketDiagram,
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): PacketDiagramLayout {
        val d = options.density.coerceAtLeast(0.01f)
        val config = diagram.config
        val bitsPerRow = config.bitsPerRow.coerceAtLeast(1)
        val bitWidth = config.bitWidth.coerceAtLeast(1f) * d
        val rowHeight = config.rowHeight.coerceAtLeast(1f) * d
        val padX = config.paddingX.coerceAtLeast(0f) * d
        val padY = config.paddingY.coerceAtLeast(0f) * d
        val margin = OUTER_MARGIN * d
        val bitLabelSpace = if (config.showBits) BIT_LABEL_HEIGHT * d else 0f

        val titleText = diagram.title?.takeIf { it.isNotBlank() }
        val resolvedTitleSize = if (titleText != null) {
            titleSize ?: defaultTitleSize(titleText, d)
        } else {
            null
        }
        val titleTopSpace = if (resolvedTitleSize != null) resolvedTitleSize.height + TITLE_GAP * d else 0f

        val gridWidth = (bitsPerRow * bitWidth - padX).coerceAtLeast(bitWidth)
        val naturalWidth = gridWidth + margin * 2f
        val minTitleWidth = if (resolvedTitleSize != null) resolvedTitleSize.width + margin * 2f else 0f
        val totalWidth = max(naturalWidth, minTitleWidth)

        val titleRect = if (resolvedTitleSize != null) {
            DiagramRect(
                x = (totalWidth - resolvedTitleSize.width) / 2f,
                y = margin,
                width = resolvedTitleSize.width,
                height = resolvedTitleSize.height,
            )
        } else {
            null
        }

        val gridLeft = (totalWidth - gridWidth) / 2f
        val gridTop = margin + titleTopSpace + bitLabelSpace
        val rowPitch = rowHeight + padY + bitLabelSpace

        val positioned = mutableListOf<PositionedPacketBlock>()
        var blockCounter = 0
        for (rowIndex in diagram.words.indices) {
            val word = diagram.words[rowIndex]
            val rowY = gridTop + rowIndex * rowPitch
            for (block in word) {
                val colOffset = when (config.bitOrder) {
                    PacketBitOrder.Ascending -> block.start % bitsPerRow
                    PacketBitOrder.Descending -> bitsPerRow - (block.start % bitsPerRow) - block.bits
                }
                val blockX = gridLeft + colOffset * bitWidth
                val blockW = (block.bits * bitWidth - padX).coerceAtLeast(1f)
                val leadingBit = when (config.bitOrder) {
                    PacketBitOrder.Ascending -> block.start.toString()
                    PacketBitOrder.Descending -> block.end.toString()
                }
                val trailingBit = when (config.bitOrder) {
                    PacketBitOrder.Ascending -> block.end.toString()
                    PacketBitOrder.Descending -> block.start.toString()
                }
                positioned += PositionedPacketBlock(
                    blockIndex = blockCounter++,
                    block = block,
                    rowIndex = rowIndex,
                    colOffset = colOffset,
                    rect = DiagramRect(
                        x = blockX,
                        y = rowY,
                        width = blockW,
                        height = rowHeight,
                    ),
                    leadingBitLabel = leadingBit,
                    trailingBitLabel = trailingBit,
                    isSingleBit = block.bits == 1,
                )
            }
        }

        val lastRowBottom = if (diagram.words.isEmpty()) {
            gridTop + rowHeight
        } else {
            gridTop + (diagram.words.size - 1) * rowPitch + rowHeight
        }
        val totalHeight = lastRowBottom + padY + margin

        return PacketDiagramLayout(
            width = totalWidth,
            height = totalHeight,
            blocks = positioned,
            showBits = config.showBits,
            bitOrder = config.bitOrder,
            bitsPerRow = bitsPerRow,
            titleRect = titleRect,
        )
    }
}
