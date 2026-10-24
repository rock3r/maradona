package dev.maradona.parse

import dev.maradona.model.BlockArrowDir
import dev.maradona.model.BlockEdgePattern
import dev.maradona.model.BlockShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BlockParserTest {

    @Test
    fun parsesNestedCompositeBlocksColumnSpansSpacesAndEdges() {
        val model = MaradonaParser.parse(
            """
            block-beta
              columns 3
              a["Wide Header"]:3
              block:group1:2
                columns 2
                h i j k
              end
              g[("DB")]
              space:2 d(("End"))
              group1 --> d
              k -- "feeds" --> d
            """.trimIndent(),
        )
        val blockModel = assertIs<MaradonaDiagramModel.BlockModel>(model)
        val diagram = blockModel.diagram

        assertEquals(3, diagram.rootColumns)
        // Children at root: a, group1, g, space_0, space_1, d -> 6 items
        assertEquals(6, diagram.children.size)

        val header = diagram.children[0]
        assertEquals("a", header.id)
        assertEquals("Wide Header", header.label)
        assertEquals(3, header.widthInColumns)

        val group1 = diagram.children[1]
        assertEquals("group1", group1.id)
        assertEquals(BlockShape.Composite, group1.shape)
        assertEquals(2, group1.widthInColumns)
        assertEquals(2, group1.columns)
        assertEquals(listOf("h", "i", "j", "k"), group1.children.map { it.id })

        val g = diagram.children[2]
        assertEquals(BlockShape.Cylinder, g.shape)
        assertEquals("DB", g.label)

        assertEquals(BlockShape.Space, diagram.children[3].shape)
        assertEquals(BlockShape.Space, diagram.children[4].shape)
        assertEquals(BlockShape.Circle, diagram.children[5].shape)

        assertEquals(2, diagram.edges.size)
        assertEquals("group1", diagram.edges[0].from)
        assertEquals("d", diagram.edges[0].to)
        assertEquals("k", diagram.edges[1].from)
        assertEquals("d", diagram.edges[1].to)
        assertEquals("feeds", diagram.edges[1].label)
    }

    @Test
    fun parsesAllFifteenShapesInPlaceEdgeNodeUpdatesAndStyling() {
        val diagram = BlockParser.parse(
            """
            ---
            config: { block: { padding: 10 } }
            ---
            block
              title Block Shapes Showcase
              accTitle: Block Accessibility
              accDescr {
                Tests all shapes, block arrows,
                and in-place node updates.
              }
              s1["Rect"] s2("Round") s3(["Stadium"]) s4[["Subroutine"]] s5[("Cylinder")]
              s6(("Circle")) s7((("Double"))) s8>"Asymmetric"] s9{"Diamond"} s10{{"Hexagon"}}
              s11[/"LeanRight"/] s12[\"LeanLeft"\] s13[/"Trapezoid"\] s14[\"InvTrapezoid"/]
              arr<["&nbsp;Flow&nbsp;"]>(x, down)
              id1 space id2
              id1("Start") -.-> id2("Stop")
              classDef default fill:#f8fafc,stroke:#334155
              classDef highlight fill:#dbeafe,stroke:#2563eb,color:#1e3a8a,stroke-width:2px
              class s1,id1 highlight
              style id2 fill:,stroke:,stroke-width:3px,stroke-dasharray: 5 5
              style s2 fill;
            """.trimIndent(),
        )

        assertEquals("Block Shapes Showcase", diagram.title)
        assertEquals("Block Accessibility", diagram.accTitle)
        assertEquals(10f, diagram.config.padding)

        val byId = diagram.children.associateBy { it.id }
        assertEquals(BlockShape.Rect, byId.getValue("s1").shape)
        assertEquals(BlockShape.Round, byId.getValue("s2").shape)
        assertEquals(BlockShape.Stadium, byId.getValue("s3").shape)
        assertEquals(BlockShape.Subroutine, byId.getValue("s4").shape)
        assertEquals(BlockShape.Cylinder, byId.getValue("s5").shape)
        assertEquals(BlockShape.Circle, byId.getValue("s6").shape)
        assertEquals(BlockShape.DoubleCircle, byId.getValue("s7").shape)
        assertEquals(BlockShape.Asymmetric, byId.getValue("s8").shape)
        assertEquals(BlockShape.Diamond, byId.getValue("s9").shape)
        assertEquals(BlockShape.Hexagon, byId.getValue("s10").shape)
        assertEquals(BlockShape.LeanRight, byId.getValue("s11").shape)
        assertEquals(BlockShape.LeanLeft, byId.getValue("s12").shape)
        assertEquals(BlockShape.Trapezoid, byId.getValue("s13").shape)
        assertEquals(BlockShape.InvTrapezoid, byId.getValue("s14").shape)

        val arr = byId.getValue("arr")
        assertEquals(BlockShape.BlockArrow, arr.shape)
        assertEquals("Flow", arr.label)
        assertEquals(listOf(BlockArrowDir.X, BlockArrowDir.Down), arr.arrowDirections)

        // id1 and id2 were updated in-place by `id1("Start") -.-> id2("Stop")` without duplicating children.
        assertEquals(18, diagram.children.size)
        val id1 = byId.getValue("id1")
        val id2 = byId.getValue("id2")
        assertEquals("Start", id1.label)
        assertEquals(BlockShape.Round, id1.shape)
        assertEquals("#dbeafe", id1.style?.fill)
        assertEquals(2f, id1.style?.strokeWidth)

        assertEquals("Stop", id2.label)
        assertEquals(BlockShape.Round, id2.shape)
        assertEquals("#f8fafc", id2.style?.fill)
        assertEquals(3f, id2.style?.strokeWidth)
        assertEquals(listOf(5f, 5f), id2.style?.strokeDashArray)

        val edge = diagram.edges.single()
        assertEquals("id1", edge.from)
        assertEquals("id2", edge.to)
        assertEquals(BlockEdgePattern.Dotted, edge.pattern)
    }

    @Test
    fun supportsUserRootNodeAnonymousBlockSpanAndSemicolonFollowingStyle() {
        val diagram = BlockParser.parse(
            """
            block
              columns 2
              root["Root Node"]
              block:2
                inner1 inner2
              end
              style root fill:#dbeafe; afterStyle["After Style"]:::my-class
              classDef my-class stroke:#2563eb
              class root hot; foo-->afterStyle
            """.trimIndent(),
        )

        assertEquals(4, diagram.children.size)
        val rootNode = diagram.children[0]
        assertEquals("root", rootNode.id)
        assertEquals("Root Node", rootNode.label)
        assertEquals("#dbeafe", rootNode.style?.fill)
        assertEquals(listOf("default", "hot"), rootNode.classes)

        val anonComp = diagram.children[1]
        assertEquals(BlockShape.Composite, anonComp.shape)
        assertEquals(2, anonComp.widthInColumns)
        assertEquals(listOf("inner1", "inner2"), anonComp.children.map { it.id })

        val afterStyle = diagram.children[2]
        assertEquals("afterStyle", afterStyle.id)
        assertEquals("After Style", afterStyle.label)
        assertEquals(listOf("default", "my-class"), afterStyle.classes)
        assertEquals("#2563eb", afterStyle.style?.stroke)

        val fooNode = diagram.children[3]
        assertEquals("foo", fooNode.id)

        val edge = diagram.edges.single()
        assertEquals("foo", edge.from)
        assertEquals("afterStyle", edge.to)
        assertEquals(dev.maradona.model.BlockArrowHead.None, edge.startHead)
    }

    @Test
    fun rejectsUnclosedBlockUnexpectedEndAndInvalidColumns() {
        assertFailsWith<IllegalArgumentException> {
            BlockParser.parse(
                """
                block
                  block:g1
                    A B
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BlockParser.parse(
                """
                block
                  A B
                  end
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BlockParser.parse(
                """
                block
                  columns 0
                  A B
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BlockParser.parse(
                """
                block
                  arr<["Bad"]>(diagonal)
                """.trimIndent(),
            )
        }
    }
}
