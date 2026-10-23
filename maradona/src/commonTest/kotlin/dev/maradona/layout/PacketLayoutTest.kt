package dev.maradona.layout

import dev.maradona.model.PacketBitOrder
import dev.maradona.parse.PacketParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PacketLayoutTest {

    @Test
    fun laysOutAscendingAndDescendingBitOrdersWithoutOverlap() {
        val ascDiagram = PacketParser.parse(
            """
            packet-beta
              title "UDP Header"
              0-15: "Source Port"
              16-31: "Destination Port"
              32-47: "Length"
              48-63: "Checksum"
            """.trimIndent(),
        )
        val ascLayout = PacketLayout.layout(ascDiagram)
        assertNotNull(ascLayout.titleRect)
        assertEquals(PacketBitOrder.Ascending, ascLayout.bitOrder)
        assertEquals(4, ascLayout.blocks.size)

        val srcAsc = ascLayout.blocks[0]
        val dstAsc = ascLayout.blocks[1]
        assertEquals(0, srcAsc.rowIndex)
        assertEquals(0, srcAsc.colOffset)
        assertEquals("0", srcAsc.leadingBitLabel)
        assertEquals("15", srcAsc.trailingBitLabel)

        assertEquals(0, dstAsc.rowIndex)
        assertEquals(16, dstAsc.colOffset)
        assertEquals("16", dstAsc.leadingBitLabel)
        assertEquals("31", dstAsc.trailingBitLabel)
        // Adjacent blocks in the same row are separated by config.paddingX (5f by default).
        assertEquals(ascDiagram.config.paddingX, dstAsc.rect.x - srcAsc.rect.right, 0.01f)

        val descDiagram = PacketParser.parse(
            """
            ---
            bitOrder: descending
            paddingX: 8
            ---
            packet-beta
              0-15: "Source Port"
              16-31: "Destination Port"
            """.trimIndent(),
        )
        val descLayout = PacketLayout.layout(descDiagram)
        assertEquals(PacketBitOrder.Descending, descLayout.bitOrder)
        val srcDesc = descLayout.blocks[0]
        val dstDesc = descLayout.blocks[1]
        // In descending order, bits 0..15 occupy the right half (colOffset = 16) with labels 15..0,
        // and bits 16..31 occupy the left half (colOffset = 0) with labels 31..16.
        assertEquals(16, srcDesc.colOffset)
        assertEquals("15", srcDesc.leadingBitLabel)
        assertEquals("0", srcDesc.trailingBitLabel)
        assertEquals(0, dstDesc.colOffset)
        assertEquals("31", dstDesc.leadingBitLabel)
        assertEquals("16", dstDesc.trailingBitLabel)
        assertTrue(dstDesc.rect.x < srcDesc.rect.x)
        assertEquals(8f, srcDesc.rect.x - dstDesc.rect.right, 0.01f)
    }

    @Test
    fun splitsMultiRowFieldsIntoVerticallyOrderedRows() {
        val diagram = PacketParser.parse(
            """
            packet-beta
              0-15: "Header"
              16-63: "Spanning Payload"
            """.trimIndent(),
        )
        val layout = PacketLayout.layout(diagram)
        assertEquals(3, layout.blocks.size)
        val row0First = layout.blocks[0]
        val row0Second = layout.blocks[1]
        val row1Full = layout.blocks[2]

        assertEquals(0, row0First.rowIndex)
        assertEquals(0, row0Second.rowIndex)
        assertEquals(1, row1Full.rowIndex)
        assertTrue(row1Full.rect.y > row0First.rect.bottom)
        assertEquals(row0First.rect.x, row1Full.rect.x, 0.01f)
        assertEquals(row0Second.rect.right, row1Full.rect.right, 0.01f)
    }
}
