package dev.maradona.parse

import dev.maradona.model.ClassClassifier
import dev.maradona.model.ClassRelationEnd
import dev.maradona.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassParserTest {

    @Test
    fun classesMembersGenericsAndClassifiers() {
        val diagram = ClassParser.parse(
            """
            ---
            title: Reef Animal Model
            ---
            classDiagram
              direction LR
              class `Animal Class!`["Animal"] {
                <<abstract>>
                +int age
                #String species$
                +isMammal()* bool
                +migrate(int miles) : void
              }
              class Square~Shape~ {
                -List~int~ position
                +List~int~[] items
                +Map~List~int~, List~string~~ values
                +List~Map~string, int~~ nestedMapList
                +getDistanceMatrix() List~List~int~~
              }
              Square : +setMessages(List~string~ messages)
              <<interface>> Drawable
            """.trimIndent(),
        )
        assertEquals("Reef Animal Model", diagram.title)
        assertEquals(Direction.LR, diagram.direction)
        assertEquals(3, diagram.classes.size)

        val animal = diagram.classes.first { it.id == "Animal Class!" }
        assertEquals("Animal", animal.label)
        assertEquals(listOf("abstract"), animal.annotations)
        assertEquals(2, animal.attributes.size)
        assertEquals("+int age", animal.attributes[0].displayText)
        assertEquals(ClassClassifier.None, animal.attributes[0].classifier)
        assertEquals("#String species", animal.attributes[1].displayText)
        assertEquals(ClassClassifier.Static, animal.attributes[1].classifier)
        assertEquals(2, animal.methods.size)
        assertEquals("+isMammal() : bool", animal.methods[0].displayText)
        assertEquals(ClassClassifier.Abstract, animal.methods[0].classifier)
        assertEquals("+migrate(int miles) : void", animal.methods[1].displayText)

        val square = diagram.classes.first { it.id == "Square" }
        assertEquals("Shape", square.genericType)
        assertEquals("Square<Shape>", square.displayTitle)
        assertEquals("-List<int> position", square.attributes[0].displayText)
        assertEquals("+List<int>[] items", square.attributes[1].displayText)
        assertEquals("+Map<List<int>, List<string>> values", square.attributes[2].displayText)
        assertEquals("+List<Map<string, int>> nestedMapList", square.attributes[3].displayText)
        assertEquals("+getDistanceMatrix() : List<List<int>>", square.methods[0].displayText)
        assertEquals("+setMessages(List<string> messages)", square.methods[1].displayText)

        val drawable = diagram.classes.first { it.id == "Drawable" }
        assertEquals(listOf("interface"), drawable.annotations)
    }

    @Test
    fun relationshipsCardinalitiesNotesAndStyles() {
        val diagram = ClassParser.parse(
            """
            classDiagram
              note "Domain model overview"
              Customer "1" --> "*" Order : places
              Order "1" *-- "1..*" LineItem : contains
              Portfolio o-- Asset : holds
              Animal <|-- Duck : extends
              Service ..|> Contract : implements
              Client ..> Service : uses
              Socket ()-- Server
              PeerA <|--|> PeerB
              note for Duck "can fly<br/>can swim"
              class Duck:::featured
              classDef featured fill:#d3e5f5,stroke:#205ea6
              style Order fill:#ffffcc
            """.trimIndent(),
        )
        assertEquals(8, diagram.relations.size)
        val r0 = diagram.relations[0]
        assertEquals("Customer", r0.from)
        assertEquals("Order", r0.to)
        assertEquals("1", r0.fromCardinality)
        assertEquals("*", r0.toCardinality)
        assertEquals("places", r0.label)
        assertEquals(ClassRelationEnd.None, r0.startEnd)
        assertEquals(ClassRelationEnd.Dependency, r0.endEnd)

        val r1 = diagram.relations[1]
        assertEquals(ClassRelationEnd.Composition, r1.startEnd)
        assertEquals("1..*", r1.toCardinality)

        val r2 = diagram.relations[2]
        assertEquals(ClassRelationEnd.Aggregation, r2.startEnd)

        val r3 = diagram.relations[3]
        assertEquals(ClassRelationEnd.Extension, r3.startEnd)

        val r4 = diagram.relations[4]
        assertTrue(r4.dashed)
        assertEquals(ClassRelationEnd.Extension, r4.endEnd)

        val r6 = diagram.relations[6]
        assertEquals(ClassRelationEnd.Lollipop, r6.startEnd)

        val r7 = diagram.relations[7]
        assertEquals(ClassRelationEnd.Extension, r7.startEnd)
        assertEquals(ClassRelationEnd.Extension, r7.endEnd)

        assertEquals(2, diagram.notes.size)
        assertEquals(null, diagram.notes[0].forClassId)
        assertEquals("Duck", diagram.notes[1].forClassId)
        assertEquals("can fly\ncan swim", diagram.notes[1].text)

        val duck = diagram.classes.first { it.id == "Duck" }
        assertEquals("#d3e5f5", duck.style?.fill)
        val order = diagram.classes.first { it.id == "Order" }
        assertEquals("#ffffcc", order.style?.fill)
    }

    @Test
    fun hierarchicalAndFlatNamespaces() {
        val hierarchical = ClassParser.parse(
            """
            classDiagram
              namespace Company.Engineering.Backend {
                class Developer {
                  +writeCode()
                }
              }
            """.trimIndent(),
        )
        assertEquals(listOf("Company", "Company.Engineering", "Company.Engineering.Backend"), hierarchical.namespaces.map { it.id })
        assertEquals("Company.Engineering", hierarchical.namespaces.last().parentId)
        assertEquals("Company.Engineering.Backend", hierarchical.classes.single().namespaceId)

        val flat = ClassParser.parse(
            """
            ---
            config:
              class:
                hierarchicalNamespaces: false
                hideEmptyMembersBox: true
            ---
            classDiagram
              namespace Company.Engineering.Backend {
                class Developer
              }
            """.trimIndent(),
        )
        assertTrue(flat.hideEmptyMembersBox)
        assertEquals(listOf("Company.Engineering.Backend"), flat.namespaces.map { it.id })
        assertEquals(null, flat.namespaces.single().parentId)
    }

    @Test
    fun semicolonBeforeBraceAndQuotedSpecialCharsInLabels() {
        val diagram = ClassParser.parse(
            """
            classDiagram
              class A; class B { #id; +name; +run() }
              class C["value:::important"]
              class D["<<interface>>"]
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B", "C", "D"), diagram.classes.map { it.id })
        assertEquals(listOf("#id", "+name"), diagram.classes.first { it.id == "B" }.attributes.map { it.displayText })
        assertEquals(listOf("+run()"), diagram.classes.first { it.id == "B" }.methods.map { it.displayText })
        val c = diagram.classes.first { it.id == "C" }
        assertEquals("value:::important", c.label)
        val d = diagram.classes.first { it.id == "D" }
        assertEquals("<<interface>>", d.label)
        assertTrue(d.annotations.isEmpty())

        val nsPreserve = ClassParser.parse(
            """
            classDiagram
              namespace First {
                class X
              }
              namespace Second {
                class Y
                X --> Y
              }
            """.trimIndent(),
        )
        assertEquals("First", nsPreserve.classes.first { it.id == "X" }.namespaceId)
        assertEquals("Second", nsPreserve.classes.first { it.id == "Y" }.namespaceId)
    }
}
