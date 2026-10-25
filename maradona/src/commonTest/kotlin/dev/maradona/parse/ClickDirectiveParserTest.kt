package dev.maradona.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClickDirectiveParserTest {

    @Test
    fun flowchartClickUrlHrefAndCallback() {
        val chart = FlowchartParser.parse(
            """
            flowchart TD
              A[Docs] --> B[Repo]
              B --> C[Callback Node]
              click A "https://mermaid.js.org" "Open Mermaid Docs" _blank
              click B href "https://github.com/rock3r/maradona" "Open GitHub" _self
              click C call onSelectNode("C") "Trigger callback"
            """.trimIndent(),
        )

        val nodeA = chart.nodes.first { it.id == "A" }
        val actA = assertNotNull(nodeA.action)
        assertEquals("https://mermaid.js.org", actA.url)
        assertEquals("Open Mermaid Docs", actA.tooltip)
        assertEquals("_blank", actA.target)

        val nodeB = chart.nodes.first { it.id == "B" }
        val actB = assertNotNull(nodeB.action)
        assertEquals("https://github.com/rock3r/maradona", actB.url)
        assertEquals("Open GitHub", actB.tooltip)
        assertEquals("_self", actB.target)

        val nodeC = chart.nodes.first { it.id == "C" }
        val actC = assertNotNull(nodeC.action)
        assertEquals("onSelectNode(\"C\")", actC.callback)
        assertEquals("Trigger callback", actC.tooltip)
    }

    @Test
    fun classDiagramLinkClickAndCallback() {
        val diagram = ClassParser.parse(
            """
            classDiagram
              class Order {
                +String id
              }
              class Customer {
                +String email
              }
              class Payment
              Customer --> Order : places
              link Order "https://example.com/order" "Order docs" _blank
              callback Customer "openCustomer" "Customer callback"
              click Payment href "https://example.com/payment" "Payment link"
            """.trimIndent(),
        )

        val order = diagram.classes.first { it.id == "Order" }
        assertEquals("https://example.com/order", order.action?.url)
        assertEquals("Order docs", order.action?.tooltip)
        assertEquals("_blank", order.action?.target)

        val customer = diagram.classes.first { it.id == "Customer" }
        assertEquals("openCustomer", customer.action?.callback)
        assertEquals("Customer callback", customer.action?.tooltip)

        val payment = diagram.classes.first { it.id == "Payment" }
        assertEquals("https://example.com/payment", payment.action?.url)
        assertEquals("Payment link", payment.action?.tooltip)
    }

    @Test
    fun ganttClickHrefAndCall() {
        val diagram = GanttParser.parse(
            """
            gantt
              dateFormat YYYY-MM-DD
              section Core
              Design :des1, 2026-10-01, 3d
              Implementation :impl1, after des1, 4d
              click des1 href "https://example.com/design"
              click impl1 call onTaskClick("impl1")
            """.trimIndent(),
        )

        val des1 = diagram.task("des1")
        assertNotNull(des1?.action)
        assertEquals("https://example.com/design", des1?.action?.url)

        val impl1 = diagram.task("impl1")
        assertNotNull(impl1?.action)
        assertEquals("onTaskClick(\"impl1\")", impl1?.action?.callback)
    }

    @Test
    fun sequenceParticipantLinks() {
        val diagram = SequenceParser.parse(
            """
            sequenceDiagram
              participant A as Alice
              participant B as Bob
              link A: Contact @ mailto:alice@example.com
              links B: {"Logs": "https://logs.example.com/@bob", "Metrics": "https://metrics.example.com/bob"}
              A->>B: Ping
            """.trimIndent(),
        )

        val alice = diagram.participants.first { it.id == "A" }
        assertEquals(mapOf("Contact" to "mailto:alice@example.com"), alice.links)

        val bob = diagram.participants.first { it.id == "B" }
        assertEquals(
            mapOf(
                "Logs" to "https://logs.example.com/@bob",
                "Metrics" to "https://metrics.example.com/bob",
            ),
            bob.links,
        )
    }
}
