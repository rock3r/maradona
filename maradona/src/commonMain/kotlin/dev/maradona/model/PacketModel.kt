package dev.maradona.model

/**
 * Bit numbering order within each word row of a Mermaid `packet` / `packet-beta` diagram.
 */
enum class PacketBitOrder {
    Ascending,
    Descending,
}

/**
 * Layout and display configuration for a [PacketDiagram].
 */
data class PacketConfig(
    val bitsPerRow: Int = 32,
    val showBits: Boolean = true,
    val bitOrder: PacketBitOrder = PacketBitOrder.Ascending,
    val rowHeight: Float = 32f,
    val bitWidth: Float = 32f,
    val paddingX: Float = 5f,
    val paddingY: Float = 5f,
)

/**
 * A logical packet field declaration before word-row splitting.
 */
data class PacketField(
    val start: Int,
    val end: Int,
    val bits: Int,
    val label: String,
)

/**
 * A single visual block within a word row (after splitting fields that cross `bitsPerRow` boundaries).
 */
data class PacketBlock(
    val start: Int,
    val end: Int,
    val bits: Int,
    val label: String,
)

/**
 * Parsed representation of a Mermaid `packet` / `packet-beta` diagram.
 */
data class PacketDiagram(
    val fields: List<PacketField>,
    val words: List<List<PacketBlock>>,
    val config: PacketConfig = PacketConfig(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
