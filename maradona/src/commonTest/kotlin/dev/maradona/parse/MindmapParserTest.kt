package dev.maradona.parse

import dev.maradona.model.MindmapShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class MindmapParserTest {

    @Test
    fun parsesHierarchyShapesIconsClassesAndMarkdownStrings() {
        val src = """
            ---
            title: Mindmap Overview
            ---
            mindmap
              root((mindmap))
                Origins
                  Long history
                  ::icon(fa fa-book)
                  Popularization
                    British popular psychology author Tony Buzan
                Shapes
                  s1[I am a square]
                  :::urgent large
                  s2(I am a rounded square)
                  s3((I am a circle))
                  s4))I am a bang((
                  s5)I am a cloud(
                  s6{{I am a hexagon}}
                  inlineChild[Books]::icon(fa fa-bookmark) :::featured
                id1["`**Root** with
            50%% complete
            and *italic* text`"]
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.MindmapModel>(model)
        val d = model.diagram
        assertEquals("Mindmap Overview", d.title)

        val root = assertNotNull(d.root)
        assertEquals("root", root.id)
        assertEquals("mindmap", root.label)
        assertEquals(MindmapShape.Circle, root.shape)

        val origins = assertNotNull(d.node("Origins"))
        assertEquals("root", origins.parentId)
        assertEquals(MindmapShape.Default, origins.shape)

        val longHistory = assertNotNull(d.node("Long history"))
        assertEquals(origins.id, longHistory.parentId)
        assertEquals("fa fa-book", longHistory.icon)

        val s1 = assertNotNull(d.node("s1"))
        assertEquals("I am a square", s1.label)
        assertEquals(MindmapShape.Square, s1.shape)
        assertEquals(listOf("urgent", "large"), s1.classes)

        assertEquals(MindmapShape.Rounded, assertNotNull(d.node("s2")).shape)
        assertEquals(MindmapShape.Circle, assertNotNull(d.node("s3")).shape)
        assertEquals(MindmapShape.Bang, assertNotNull(d.node("s4")).shape)
        assertEquals(MindmapShape.Cloud, assertNotNull(d.node("s5")).shape)
        assertEquals(MindmapShape.Hexagon, assertNotNull(d.node("s6")).shape)

        val inlineChild = assertNotNull(d.node("inlineChild"))
        assertEquals("Books", inlineChild.label)
        assertEquals(MindmapShape.Square, inlineChild.shape)
        assertEquals("fa fa-bookmark", inlineChild.icon)
        assertEquals(listOf("featured"), inlineChild.classes)

        val id1 = assertNotNull(d.node("id1"))
        assertEquals("<b>Root</b> with\n50%% complete\nand <i>italic</i> text", id1.label)
    }

    @Test
    fun handlesUnevenSiblingIndentationAndStyles() {
        val src = """
            mindmap
            Root
                A
                    B
                  C
            classDef default fill:#f9f
            style A fill:#d3e5f5,stroke:#205ea6
        """.trimIndent()

        val d = MindmapParser.parse(src)
        val a = assertNotNull(d.node("A"))
        val b = assertNotNull(d.node("B"))
        val c = assertNotNull(d.node("C"))
        assertEquals("Root", a.parentId)
        assertEquals(a.id, b.parentId)
        assertEquals(a.id, c.parentId)
        assertEquals("#d3e5f5", a.style?.fill)
        assertEquals("#f9f", b.style?.fill)
    }
}
