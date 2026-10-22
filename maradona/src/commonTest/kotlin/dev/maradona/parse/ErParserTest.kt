package dev.maradona.parse

import dev.maradona.model.Direction
import dev.maradona.model.ErCardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ErParserTest {

    @Test
    fun parsesAttributesKeysCommentsAndCrowsFootRelationships() {
        val src = """
            ---
            title: Fleet Schema
            ---
            erDiagram
                direction LR
                CAR ||--o{ NAMED-DRIVER : allows
                CAR {
                    string registrationNumber PK
                    string make
                    string[] parts
                }
                PERSON ||--o{ NAMED-DRIVER : "is"
                PERSON {
                    string driversLicense PK "The license #"
                    string(99) firstName "Only 99 characters are allowed"
                    string? middleName
                    string phone UK
                }
                NAMED-DRIVER {
                    string carRegistrationNumber PK, FK
                    string driverLicense PK, FK
                }
                MANUFACTURER only one to zero or more CAR : makes
                PERSON }o..o{ ADDRESS : "lives at"
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.ErModel>(model)
        val d = model.diagram
        assertEquals("Fleet Schema", d.title)
        assertEquals(Direction.LR, d.direction)

        val car = assertNotNull(d.entity("CAR"))
        assertEquals(3, car.attributes.size)
        assertEquals(listOf("PK"), car.attributes[0].keys)
        assertEquals("string[]", car.attributes[2].type)

        val person = assertNotNull(d.entity("PERSON"))
        assertEquals("The license #", person.attributes[0].comment)
        assertEquals("string(99)", person.attributes[1].type)
        assertEquals("Only 99 characters are allowed", person.attributes[1].comment)
        assertEquals("string?", person.attributes[2].type)
        assertEquals(listOf("UK"), person.attributes[3].keys)

        val namedDriver = assertNotNull(d.entity("NAMED-DRIVER"))
        assertEquals(listOf("PK", "FK"), namedDriver.attributes[0].keys)

        assertEquals(4, d.relationships.size)
        val r0 = d.relationships[0]
        assertEquals(ErCardinality.ExactlyOne, r0.fromCardinality)
        assertEquals(ErCardinality.ZeroOrMore, r0.toCardinality)
        assertTrue(r0.identifying)
        assertEquals("allows", r0.label)

        val r2 = d.relationships[2]
        assertEquals("MANUFACTURER", r2.from)
        assertEquals("CAR", r2.to)
        assertEquals(ErCardinality.ExactlyOne, r2.fromCardinality)
        assertEquals(ErCardinality.ZeroOrMore, r2.toCardinality)
        assertTrue(r2.identifying)

        val r3 = d.relationships[3]
        assertEquals(ErCardinality.ZeroOrMore, r3.fromCardinality)
        assertEquals(ErCardinality.ZeroOrMore, r3.toCardinality)
        assertFalse(r3.identifying)
        assertEquals("lives at", r3.label)
    }

    @Test
    fun parsesBracketAliasesSubgraphsAndStyles() {
        val src = """
            erDiagram
                classDef default fill:#f9f,stroke-width:2px
                classDef highlight fill:#d3e5f5,stroke:#205ea6
                class p highlight
                subgraph id1 [Customer Domain]
                    p[Person] {
                        string firstName
                    }
                    a["Customer Account"] {
                        string email
                    }
                    p ||--o| a : has
                end
                subgraph id2 ["Vehicle Domain"]
                    CAR:::highlight
                end
                id1 ||--|| id2 : links
        """.trimIndent()

        val d = ErParser.parse(src)
        val p = assertNotNull(d.entity("p"))
        assertEquals("Person", p.label)
        assertEquals("id1", p.subgraphId)
        assertEquals("#d3e5f5", p.style?.fill)

        val a = assertNotNull(d.entity("a"))
        assertEquals("Customer Account", a.label)
        assertEquals("id1", a.subgraphId)
        assertEquals("#f9f", a.style?.fill)

        val sg1 = assertNotNull(d.subgraph("id1"))
        assertEquals("Customer Domain", sg1.label)
        val sg2 = assertNotNull(d.subgraph("id2"))
        assertEquals("Vehicle Domain", sg2.label)

        assertEquals(2, d.relationships.size)
        assertEquals("id1", d.relationships[1].from)
        assertEquals("id2", d.relationships[1].to)

        // Multiple attributes on a single line and mixed symbolic/natural-language cardinalities.
        val mixed = ErParser.parse(
            """
            erDiagram
                CUSTOMER { string id PK "Primary" int age }
                CUSTOMER ||--many(0) ORDER : places
                PERSON only one to o{ ADDRESS : uses
                A }o to one B:uses
                p[Customer: Account] one to one q["Target: Account"] : links
            """.trimIndent(),
        )
        val cust = assertNotNull(mixed.entity("CUSTOMER"))
        assertEquals(2, cust.attributes.size)
        assertEquals("id", cust.attributes[0].name)
        assertEquals(listOf("PK"), cust.attributes[0].keys)
        assertEquals("Primary", cust.attributes[0].comment)
        assertEquals("int", cust.attributes[1].type)
        assertEquals("age", cust.attributes[1].name)

        assertEquals(4, mixed.relationships.size)
        assertEquals(ErCardinality.ExactlyOne, mixed.relationships[0].fromCardinality)
        assertEquals(ErCardinality.ZeroOrMore, mixed.relationships[0].toCardinality)
        assertEquals(ErCardinality.ExactlyOne, mixed.relationships[1].fromCardinality)
        assertEquals(ErCardinality.ZeroOrMore, mixed.relationships[1].toCardinality)
        assertEquals("A", mixed.relationships[2].from)
        assertEquals("B", mixed.relationships[2].to)
        assertEquals(ErCardinality.ZeroOrMore, mixed.relationships[2].fromCardinality)
        assertEquals(ErCardinality.ExactlyOne, mixed.relationships[2].toCardinality)
        assertEquals("uses", mixed.relationships[2].label)
        assertEquals("p", mixed.relationships[3].from)
        assertEquals("q", mixed.relationships[3].to)
        assertEquals("links", mixed.relationships[3].label)
        assertEquals("Customer: Account", mixed.entity("p")?.label)
        assertEquals("Target: Account", mixed.entity("q")?.label)
    }
}
