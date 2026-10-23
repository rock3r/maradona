package dev.maradona.parse

import dev.maradona.model.Direction
import dev.maradona.model.RequirementKind
import dev.maradona.model.RequirementRelationType
import dev.maradona.model.RequirementRisk
import dev.maradona.model.RequirementVerifyMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequirementParserTest {

    @Test
    fun parsesAllRequirementKindsElementsForwardAndReverseRelationsAndStyles() {
        val src = """
            requirementDiagram
              direction LR
              requirement test_req:::important {
                id: 1
                text: the test text.
                risk: high
                verifymethod: test
              }
              functionalRequirement test_req2 {
                id: 1.1
                text: the second test text.
                risk: low
                verifymethod: inspection
              }
              performanceRequirement test_req3 {
                id: 1.2
                text: the third test text.
                risk: medium
                verifymethod: demonstration
              }
              interfaceRequirement test_req4 {
                id: 1.2.1
                text: the fourth test text.
                risk: medium
                verifymethod: analysis
              }
              physicalRequirement test_req5 {
                id: 1.2.2
                text: the fifth test text.
                risk: medium
                verifymethod: analysis
              }
              designConstraint "__test_req6__" {
                id: 1.2.3
                text: "*italicized* **bold**"
                risk: medium
                verifymethod: analysis
              }
              element test_entity {
                type: simulation
              }
              element test_entity2 {
                type: word doc
                docRef: reqs/test_entity
              }
              element test_entity3 {
                type: "test suite"
                docRef: github.com/all_the_tests
              }
              test_entity - satisfies -> test_req2
              test_req - traces -> test_req2
              test_req - contains -> test_req3
              test_req3 - contains -> test_req4
              test_req4 - derives -> test_req5
              test_req5 - refines -> "__test_req6__"
              test_entity3 - verifies -> test_req5
              test_req <- copies - test_entity2
              classDef important fill:#eef6ff,stroke:#205ea6,stroke-width:2px,font-weight:bold
              class test_entity important
              style test_req2 fill:,stroke:, color: green
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.RequirementModel>(model)
        val d = model.diagram
        assertEquals(Direction.LR, d.direction)
        assertEquals(6, d.requirements.size)
        assertEquals(3, d.elements.size)
        assertEquals(8, d.relations.size)

        val r1 = assertNotNull(d.requirement("test_req"))
        assertEquals(RequirementKind.Requirement, r1.kind)
        assertEquals("1", r1.requirementId)
        assertEquals(RequirementRisk.High, r1.risk)
        assertEquals(RequirementVerifyMethod.Test, r1.verifyMethod)
        assertEquals("#eef6ff", r1.style?.fill)
        assertEquals(2f, r1.style?.strokeWidth)
        assertTrue(r1.style?.fontWeightBold == true)

        val r2 = assertNotNull(d.requirement("test_req2"))
        assertEquals("green", r2.style?.textColor)

        val r6 = assertNotNull(d.requirement("__test_req6__"))
        assertEquals(RequirementKind.DesignConstraint, r6.kind)

        val e2 = assertNotNull(d.element("test_entity2"))
        assertEquals("word doc", e2.type)
        assertEquals("reqs/test_entity", e2.docRef)

        // Reverse relation `test_req <- copies - test_entity2` has source=test_entity2, target=test_req.
        val lastRel = d.relations.last()
        assertEquals(RequirementRelationType.Copies, lastRel.type)
        assertEquals("test_entity2", lastRel.source)
        assertEquals("test_req", lastRel.target)

        // Implicit `default` class, quoted target names with spaces, multiline quoted text, and font-weight:normal override.
        val advanced = RequirementParser.parse(
            """
            requirementDiagram
              requirement "Login requirement":::important {
                id: 1
                text: "First line
            Second line"
              }
              classDef default fill:#f0f0f0,stroke:#111111
              classDef important font-weight:bold
              class "Login requirement" important
              style "Login requirement" stroke:#222222,font-weight:normal
            """.trimIndent(),
        )
        val loginReq = assertNotNull(advanced.requirement("Login requirement"))
        assertEquals("First line Second line", loginReq.text)
        assertEquals("#f0f0f0", loginReq.style?.fill)
        assertEquals("#222222", loginReq.style?.stroke)
        assertEquals(false, loginReq.style?.fontWeightBold)
    }

    @Test
    fun rejectsInvalidRiskVerifyMethodRelationTypeAndUnclosedBlocks() {
        assertFailsWith<IllegalArgumentException> {
            RequirementParser.parse(
                """
                requirementDiagram
                  requirement r1 {
                    risk: critical
                  }
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RequirementParser.parse(
                """
                requirementDiagram
                  requirement r1 {
                    verifymethod: guesswork
                  }
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RequirementParser.parse(
                """
                requirementDiagram
                  requirement r1 {
                    id: 1
                  }
                  element e1 {
                    type: service
                  }
                  e1 - breaks -> r1
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RequirementParser.parse(
                """
                requirementDiagram
                  requirement r1 {
                    id: 1
                """.trimIndent(),
            )
        }
    }
}
