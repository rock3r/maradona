package dev.maradona.parse

import dev.maradona.model.ArchitectureAlignDir
import dev.maradona.model.ArchitecturePortDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ArchitectureParserTest {

    @Test
    fun parsesGroupsServicesJunctionsEdgesAndAlignments() {
        val model = MaradonaParser.parse(
            """
            architecture-beta
              group sources(cloud)[Sources]
                service src_a(server)[Source A] in sources
                service src_b(server)[Source B] in sources
                service src_c(server)[Source C] in sources

              group storage(database)[Storage]
                service db_one(database)[DB One] in storage
                service db_two(database)[DB Two] in storage
                service db_three(database)[DB Three] in storage

              junction j_mid

              src_a:B --> T:db_one
              src_b{group}:B <[Sync]> T:db_two{group}
              src_c:B -- T:j_mid
              j_mid:B --> T:db_three

              align row src_a src_b src_c
              align row db_one db_two db_three
              align column src_a db_one
            """.trimIndent(),
        )
        val archModel = assertIs<MaradonaDiagramModel.ArchitectureModel>(model)
        val diagram = archModel.diagram

        assertEquals(2, diagram.groups.size)
        assertEquals("sources", diagram.groups[0].id)
        assertEquals("cloud", diagram.groups[0].icon)
        assertEquals("Sources", diagram.groups[0].title)

        assertEquals(6, diagram.services.size)
        assertEquals("src_a", diagram.services[0].id)
        assertEquals("server", diagram.services[0].icon)
        assertEquals("Source A", diagram.services[0].title)
        assertEquals("sources", diagram.services[0].parentId)

        assertEquals(1, diagram.junctions.size)
        assertEquals("j_mid", diagram.junctions[0].id)

        assertEquals(4, diagram.edges.size)
        val e1 = diagram.edges[1]
        assertEquals("src_b", e1.lhsId)
        assertEquals(ArchitecturePortDir.B, e1.lhsDir)
        assertEquals(true, e1.lhsGroup)
        assertEquals(true, e1.lhsInto)
        assertEquals("db_two", e1.rhsId)
        assertEquals(ArchitecturePortDir.T, e1.rhsDir)
        assertEquals(true, e1.rhsGroup)
        assertEquals(true, e1.rhsInto)
        assertEquals("Sync", e1.title)

        assertEquals(3, diagram.alignments.size)
        assertEquals(ArchitectureAlignDir.Row, diagram.alignments[0].direction)
        assertEquals(listOf("src_a", "src_b", "src_c"), diagram.alignments[0].members)
    }

    @Test
    fun parsesIconTextFrontmatterAndAccessibilityDirectives() {
        val diagram = ArchitectureParser.parse(
            """
            ---
            title: 'Literal %%{init: {"architecture": {"iconSize": 120}}}%%'
            config: { architecture: { padding: 28, iconSize: 72, fontSize: 14, nodeSpacing: 48 } }
            ---
            architecture
              title Customer's Cloud Service Mesh
              accTitle: Architecture Accessibility
              accDescr {
                Shows AWS services inside a cloud group.
              }
              group api(logos:aws-lambda)[Customer's API]
              service fn('Lambda; %%100%')[Function] in api
              service s3(logos:aws-s3)[Bucket] in api
              fn:R --> L:s3
            """.trimIndent(),
        )

        assertEquals("Customer's Cloud Service Mesh", diagram.title)
        assertEquals("Architecture Accessibility", diagram.accTitle)
        assertEquals("Shows AWS services inside a cloud group.", diagram.accDescr)
        assertEquals(28f, diagram.config.padding)
        assertEquals(72f, diagram.config.iconSize)
        assertEquals(14f, diagram.config.fontSize)
        assertEquals(48f, diagram.config.nodeSpacing)
        assertEquals("Customer's API", diagram.groups[0].title)
        assertEquals(null, diagram.services[0].icon)
        assertEquals("Lambda; %%100%", diagram.services[0].iconText)
        assertEquals("Function", diagram.services[0].title)
    }

    @Test
    fun rejectsDuplicateIdsUndeclaredParentsSameGroupEdgesAndInvalidAlignments() {
        // Duplicate ID
        assertFailsWith<IllegalArgumentException> {
            ArchitectureParser.parse(
                """
                architecture-beta
                  service a(server)[A]
                  service a(database)[Dup]
                """.trimIndent(),
            )
        }
        // Undeclared parent group
        assertFailsWith<IllegalArgumentException> {
            ArchitectureParser.parse(
                """
                architecture-beta
                  service a(server)[A] in missingGroup
                """.trimIndent(),
            )
        }
        // {group} modifier when both endpoints are in the same group
        assertFailsWith<IllegalArgumentException> {
            ArchitectureParser.parse(
                """
                architecture-beta
                  group g1(cloud)[G1]
                  service a(server)[A] in g1
                  service b(database)[B] in g1
                  a{group}:R --> L:b
                """.trimIndent(),
            )
        }
        // align referencing a group instead of a service/junction
        assertFailsWith<IllegalArgumentException> {
            ArchitectureParser.parse(
                """
                architecture-beta
                  group g1(cloud)[G1]
                  service a(server)[A] in g1
                  align row g1 a
                """.trimIndent(),
            )
        }

        // Single-quoted title after a service declaration preserves literal init directive text
        val singleTitleDiag = ArchitectureParser.parse(
            """
            architecture-beta
              service a
              title 'Literal %%{init: {"architecture": {"iconSize": 120}}}%%'
            """.trimIndent(),
        )
        assertEquals("Literal %%{init: {\"architecture\": {\"iconSize\": 120}}}%%", singleTitleDiag.title)
        assertEquals(64f, singleTitleDiag.config.iconSize)
    }
}
