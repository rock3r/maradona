package dev.maradona.parse

import dev.maradona.model.PacketBitOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PacketParserTest {

    @Test
    fun parsesTcpPacketWithRangeSingleBitAndMultiRowFields() {
        val model = MaradonaParser.parse(
            """
            ---
            title: "TCP Packet"
            ---
            packet-beta
              0-15: "Source Port"
              16-31: "Destination Port"
              32-63: "Sequence Number"
              64-95: "Acknowledgment Number"
              96-99: "Data Offset"
              100-105: "Reserved"
              106: "URG"
              107: "ACK"
              108: "PSH"
              109: "RST"
              110: "SYN"
              111: "FIN"
              112-127: "Window"
              128-143: "Checksum"
              144-159: "Urgent Pointer"
              160-191: "(Options and Padding)"
              192-255: "Data (variable length)"
            """.trimIndent(),
        )
        val packetModel = assertIs<MaradonaDiagramModel.PacketModel>(model)
        val diagram = packetModel.diagram

        assertEquals("TCP Packet", diagram.title)
        assertEquals(17, diagram.fields.size)
        // 256 bits across 32 bitsPerRow -> 8 rows (192..255 splits into 192..223 and 224..255).
        assertEquals(8, diagram.words.size)

        val row0 = diagram.words[0]
        assertEquals(2, row0.size)
        assertEquals(0, row0[0].start)
        assertEquals(15, row0[0].end)
        assertEquals(16, row0[0].bits)
        assertEquals("Source Port", row0[0].label)

        val row3 = diagram.words[3]
        assertEquals(9, row3.size)
        assertEquals(106, row3[2].start)
        assertEquals(106, row3[2].end)
        assertEquals(1, row3[2].bits)
        assertEquals("URG", row3[2].label)

        val row6 = diagram.words[6]
        assertEquals(1, row6.size)
        assertEquals(192, row6[0].start)
        assertEquals(223, row6[0].end)
        assertEquals(32, row6[0].bits)
        assertEquals("Data (variable length)", row6[0].label)

        val row7 = diagram.words[7]
        assertEquals(1, row7.size)
        assertEquals(224, row7[0].start)
        assertEquals(255, row7[0].end)
        assertEquals(32, row7[0].bits)
        assertEquals("Data (variable length)", row7[0].label)
    }

    @Test
    fun parsesRelativeBitCountsConfigAndAccessibilityDirectives() {
        val diagram = PacketParser.parse(
            """
            ---
            config:
              flowchart:
                paddingX: 99
              packet:
                bitsPerRow: 16
                showBits: false
                bitOrder: descending
                rowHeight: 40
                bitWidth: 28
                paddingX: 8
                paddingY: 6
            ---
            packet # inline comment
              title: Custom 16-bit Protocol # title comment
              accTitle: Packet Accessibility Title
              accDescr {
                Describes a 16-bit protocol header
                with relative bit counts.
              }
              +8: "Type #1 (100%)" # inline comment
              +8: "Code"
              +1: "Flag A"; +15: "Payload Length"
              +32: "Two-Row Payload"
            """.trimIndent(),
        )

        assertEquals("Custom 16-bit Protocol", diagram.title)
        assertEquals("Packet Accessibility Title", diagram.accTitle)
        assertEquals("Describes a 16-bit protocol header\nwith relative bit counts.", diagram.accDescr)
        assertEquals(16, diagram.config.bitsPerRow)
        assertEquals(false, diagram.config.showBits)
        assertEquals(PacketBitOrder.Descending, diagram.config.bitOrder)
        assertEquals(40f, diagram.config.rowHeight)
        assertEquals(28f, diagram.config.bitWidth)
        assertEquals(8f, diagram.config.paddingX)
        assertEquals(6f, diagram.config.paddingY)

        assertEquals(5, diagram.fields.size)
        assertEquals(0, diagram.fields[0].start)
        assertEquals(7, diagram.fields[0].end)
        assertEquals("Type #1 (100%)", diagram.fields[0].label)

        assertEquals(8, diagram.fields[1].start)
        assertEquals(15, diagram.fields[1].end)

        assertEquals(16, diagram.fields[2].start)
        assertEquals(16, diagram.fields[2].end)

        assertEquals(17, diagram.fields[3].start)
        assertEquals(31, diagram.fields[3].end)

        // 64 total bits at 16 bitsPerRow -> 4 rows.
        assertEquals(4, diagram.words.size)
        assertEquals(32, diagram.words[2].single().start)
        assertEquals(47, diagram.words[2].single().end)
        assertEquals(48, diagram.words[3].single().start)
        assertEquals(63, diagram.words[3].single().end)
    }

    @Test
    fun parsesFlowMappingFrontmatterInitDirectivesAndApostrophesInTitles() {
        val diagram = PacketParser.parse(
            """
            ---
            config:
              packet: {bitsPerRow: 8, bitOrder: descending, showBits: false, paddingX: 6}
            ---
            %% disabled: %%{init: {'packet': {'bitsPerRow': 32, 'showBits': true}}}%%
            packet
              title TCP's 'special' header
              accTitle: Sender's header
              0: "%%{init: {'packet': {'bitsPerRow': 16, 'bitOrder': 'ascending'}}}%%"
              +15: "Payload"
            """.trimIndent(),
        )

        assertEquals("TCP's 'special' header", diagram.title)
        assertEquals("Sender's header", diagram.accTitle)
        assertEquals(8, diagram.config.bitsPerRow)
        assertEquals(PacketBitOrder.Descending, diagram.config.bitOrder)
        assertEquals(false, diagram.config.showBits)
        assertEquals(6f, diagram.config.paddingX)
        assertEquals("%%{init: {'packet': {'bitsPerRow': 16, 'bitOrder': 'ascending'}}}%%", diagram.fields[0].label)
        assertEquals(2, diagram.words.size)
    }

    @Test
    fun rejectsNonContiguousOrInvalidBitRanges() {
        // Non-zero first bit
        assertFailsWith<IllegalArgumentException> {
            PacketParser.parse(
                """
                packet-beta
                  1-15: "Source Port"
                """.trimIndent(),
            )
        }
        // Gap between fields
        assertFailsWith<IllegalArgumentException> {
            PacketParser.parse(
                """
                packet-beta
                  0-7: "Type"
                  9-15: "Code"
                """.trimIndent(),
            )
        }
        // Overlap between fields
        assertFailsWith<IllegalArgumentException> {
            PacketParser.parse(
                """
                packet-beta
                  0-7: "Type"
                  7-15: "Code"
                """.trimIndent(),
            )
        }
        // End < start
        assertFailsWith<IllegalArgumentException> {
            PacketParser.parse(
                """
                packet-beta
                  0-7: "Type"
                  15-8: "Code"
                """.trimIndent(),
            )
        }
        // Non-positive relative bit count
        assertFailsWith<IllegalArgumentException> {
            PacketParser.parse(
                """
                packet-beta
                  +0: "Invalid"
                """.trimIndent(),
            )
        }
        // Invalid bitOrder
        assertFailsWith<IllegalArgumentException> {
            PacketParser.parse(
                """
                ---
                bitOrder: sideways
                ---
                packet-beta
                  0-7: "Type"
                """.trimIndent(),
            )
        }
        // Excessive packet row count (> 10,000 rows)
        assertFailsWith<IllegalArgumentException> {
            PacketParser.parse(
                """
                packet-beta
                  +2147483647: "Too Large"
                """.trimIndent(),
            )
        }
    }
}
