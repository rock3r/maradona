package dev.maradona.parse

import dev.maradona.model.C4BoundaryKind
import dev.maradona.model.C4DiagramType
import dev.maradona.model.C4ElementKind
import dev.maradona.model.C4RelKind
import dev.maradona.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C4ParserTest {

    @Test
    fun parsesC4ContextWithNestedBoundariesAndRelationships() {
        val diagram = C4Parser.parse(
            """
            C4Context
              title System Context diagram for Internet Banking System
              Enterprise_Boundary(b0, "BankBoundary0") {
                Person(customerA, "Banking Customer A", "A customer of the bank.")
                System(SystemAA, "Internet Banking System", "Allows customers to view info.")

                Enterprise_Boundary(b1, "BankBoundary") {
                  SystemDb_Ext(SystemE, "Mainframe Banking System", "Stores core banking info.")
                  System_Boundary(b2, "BankBoundary2") {
                    System(SystemA, "Banking System A")
                    SystemQueue(SystemB, "Banking Queue B", "Message queue")
                  }
                }
              }
              BiRel(customerA, SystemAA, "Uses")
              Rel(SystemAA, SystemE, "Uses", "HTTPS")
              Rel_R(SystemAA, SystemA, "Dispatches")
              UpdateElementStyle(customerA, ${'$'}fontColor="red", ${'$'}bgColor="grey", ${'$'}borderColor="red")
              UpdateRelStyle(customerA, SystemAA, ${'$'}textColor="blue", ${'$'}lineColor="blue", ${'$'}offsetX="5")
              UpdateLayoutConfig(${'$'}c4ShapeInRow="3", ${'$'}c4BoundaryInRow="1")
            """.trimIndent(),
        )

        assertEquals(C4DiagramType.CONTEXT, diagram.type)
        assertEquals("System Context diagram for Internet Banking System", diagram.title)
        assertEquals(3, diagram.config.c4ShapeInRow)
        assertEquals(1, diagram.config.c4BoundaryInRow)

        assertEquals(3, diagram.boundaries.size)
        val b0 = diagram.boundaries.first { it.id == "b0" }
        assertEquals(C4BoundaryKind.ENTERPRISE, b0.kind)
        assertEquals(null, b0.parentBoundaryId)
        val b1 = diagram.boundaries.first { it.id == "b1" }
        assertEquals("b0", b1.parentBoundaryId)
        val b2 = diagram.boundaries.first { it.id == "b2" }
        assertEquals("b1", b2.parentBoundaryId)
        assertEquals(C4BoundaryKind.SYSTEM, b2.kind)

        assertEquals(5, diagram.elements.size)
        val customerA = diagram.elements.first { it.id == "customerA" }
        assertEquals(C4ElementKind.PERSON, customerA.kind)
        assertEquals("b0", customerA.parentBoundaryId)
        assertNotNull(customerA.style)
        assertEquals("red", customerA.style.fontColor)
        assertEquals("grey", customerA.style.bgColor)
        assertEquals("red", customerA.style.borderColor)

        val systemE = diagram.elements.first { it.id == "SystemE" }
        assertEquals(C4ElementKind.SYSTEM_DB_EXT, systemE.kind)
        assertEquals("b1", systemE.parentBoundaryId)

        assertEquals(3, diagram.relationships.size)
        val biRel = diagram.relationships[0]
        assertEquals(C4RelKind.BI_REL, biRel.kind)
        assertEquals("blue", biRel.style?.lineColor)
        assertEquals(5f, biRel.style?.offsetX)
    }

    @Test
    fun parsesContainerComponentDynamicAndDeploymentVariants() {
        val containerDiag = C4Parser.parse(
            """
            C4Container
              direction LR
              Container(web, "Web App", "Kotlin/Compose", "Delivers UI")
              ContainerDb(db, "Database", "PostgreSQL", "Stores users")
              Component(auth, "Auth Controller", "Ktor", "Handles login")
              RelIndex(1, web, auth, "Calls", "HTTPS/JSON")
              Rel_Back(db, auth, "Reads from", "JDBC")
            """.trimIndent(),
        )
        assertEquals(C4DiagramType.CONTAINER, containerDiag.type)
        assertEquals(Direction.LR, containerDiag.direction)
        assertEquals(3, containerDiag.elements.size)
        val web = containerDiag.elements.first { it.id == "web" }
        assertEquals("Kotlin/Compose", web.technology)
        assertEquals("Delivers UI", web.description)
        assertEquals("1", containerDiag.relationships[0].index)
        assertEquals(C4RelKind.REL_BACK, containerDiag.relationships[1].kind)

        val deployDiag = C4Parser.parse(
            """
            C4Deployment
              Deployment_Node_L(aws, "AWS Cloud", "Amazon Web Services") {
                Node_R(eks, "EKS Cluster", "Kubernetes") {
                  Container(api, "API Service", "Kotlin", "REST backend")
                }
              }
            """.trimIndent(),
        )
        assertEquals(C4DiagramType.DEPLOYMENT, deployDiag.type)
        assertEquals(2, deployDiag.boundaries.size)
        assertEquals(C4BoundaryKind.DEPLOYMENT_NODE, deployDiag.boundaries[0].kind)
        assertEquals("eks", deployDiag.elements.single().parentBoundaryId)
    }

    @Test
    fun explicitZeroRelStyleOffsetOverridesEarlierOffset() {
        val diagram = C4Parser.parse(
            """
            C4Context
              System(a, "A")
              System(b, "B")
              Rel(a, b, "Uses")
              UpdateElementStyle(a, "red", "white", "darkred")
              UpdateRelStyle(a, b, ${'$'}offsetX="100", ${'$'}offsetY="50")
              UpdateRelStyle(a, b, ${'$'}offsetX="0", ${'$'}offsetY="0")
            """.trimIndent(),
        )
        val aEl = diagram.elements.first { it.id == "a" }
        assertEquals("red", aEl.style?.bgColor)
        assertEquals("white", aEl.style?.fontColor)
        assertEquals("darkred", aEl.style?.borderColor)
        val rel = diagram.relationships.single()
        assertEquals(0f, rel.style?.offsetX)
        assertEquals(0f, rel.style?.offsetY)
    }

    @Test
    fun topLevelMaradonaParserDispatchesC4() {
        val model = MaradonaParser.parse(
            """
            C4Context
              Person(u, "User")
              System(s, "System")
              Rel(u, s, "Uses")
            """.trimIndent(),
        )
        assertTrue(model is MaradonaDiagramModel.C4Model)
        assertEquals(2, model.diagram.elements.size)
    }

    @Test
    fun rejectsUnclosedBoundaryOrNonC4Header() {
        assertFailsWith<IllegalArgumentException> {
            C4Parser.parse("flowchart TD\n  A --> B")
        }
        assertFailsWith<IllegalArgumentException> {
            C4Parser.parse(
                """
                C4Context
                  Enterprise_Boundary(b0, "Open") {
                    System(s, "Sys")
                """.trimIndent(),
            )
        }
    }
}
