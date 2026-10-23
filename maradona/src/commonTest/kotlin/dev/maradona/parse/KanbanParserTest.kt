package dev.maradona.parse

import dev.maradona.model.KanbanPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class KanbanParserTest {

    @Test
    fun parsesColumnsTasksMetadataDecoratorsAndDuplicateTaskIdsAcrossColumns() {
        val src = """
            ---
            config:
              kanban:
                sectionWidth: 220
                ticketBaseUrl: 'https://mermaidchart.atlassian.net/browse/#TICKET#'
            ---
            kanban
              Todo
                [Create Documentation]
                docs[Create Blog about the new diagram]
              [In progress]
                id6[Create renderer so that it works in all cases.]
              id9[Ready for deploy]
                id8[Design grammar]@{ assigned: 'knsv' }
              id10[Ready for test]
                id4[Create parsing tests]@{ ticket: MC-2038, assigned: 'K.Sveidqvist', priority: 'High' }
                id66[last item]@{ priority: 'Very Low', assigned: 'knsv' }
              id11[Done]
                id5[define getData]
                id2[Title of diagram is more than 100 chars]@{ ticket: MC-2036, priority: 'Very High'}
                id3[Update DB function]@{ ticket: MC-2037, assigned: knsv, priority: 'High' }
              id12[Can't reproduce]
                id3[Weird flickering in Firefox] %% trailing comment after apostrophe
                ::icon(fa fa-bug)
                :::muted
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.KanbanModel>(model)
        val d = model.diagram
        assertEquals(220f, d.sectionWidth)
        assertEquals("https://mermaidchart.atlassian.net/browse/#TICKET#", d.ticketBaseUrl)
        assertEquals(6, d.columns.size)

        assertEquals("Todo", d.columns[0].label)
        assertEquals(2, d.columns[0].tasks.size)
        assertEquals("Create Documentation", d.columns[0].tasks[0].label)

        assertEquals("In progress", d.columns[1].label)
        assertEquals(1, d.columns[1].tasks.size)

        val testCol = assertNotNull(d.column("Ready for test"))
        assertEquals(2, testCol.tasks.size)
        assertEquals("MC-2038", testCol.tasks[0].ticket)
        assertEquals("K.Sveidqvist", testCol.tasks[0].assigned)
        assertEquals(KanbanPriority.High, testCol.tasks[0].priority)
        assertEquals(KanbanPriority.VeryLow, testCol.tasks[1].priority)

        val doneCol = assertNotNull(d.column("Done"))
        assertEquals(KanbanPriority.VeryHigh, doneCol.tasks[1].priority)
        assertEquals("MC-2037", doneCol.tasks[2].ticket)

        val cantCol = assertNotNull(d.column("Can't reproduce"))
        assertEquals("Weird flickering in Firefox", cantCol.tasks[0].label)
        assertEquals("fa fa-bug", cantCol.tasks[0].icon)
        assertEquals("muted", cantCol.tasks[0].cssClass)

        val multilineMeta = KanbanParser.parse(
            """
            kanban
              Todo
                t1[Task]@{
                  ticket: MC-99
                  assigned: 'alice'
                  label: 'Fix { and " and 50%% handling'
                }
                t2))Bang Task((@{ ticket: X-1 }
                t3)Cloud Task(@{ assigned: 'bob' }
            """.trimIndent(),
        )
        val mt = multilineMeta.columns.single().tasks[0]
        assertEquals("MC-99", mt.ticket)
        assertEquals("alice", mt.assigned)
        assertEquals("Fix { and \" and 50%% handling", mt.label)
        assertEquals("X-1", multilineMeta.columns.single().tasks[1].ticket)
        assertEquals("bob", multilineMeta.columns.single().tasks[2].assigned)
        assertEquals(
            "https://mermaidchart.atlassian.net/browse/MC-2038",
            d.resolveTicketUrl("MC-2038"),
        )
    }

    @Test
    fun rejectsOutdentedNodesUnclosedMetadataAndInvalidShapeNames() {
        assertFailsWith<IllegalArgumentException> {
            KanbanParser.parse(
                """
                kanban
                    col1[Todo]
                      task1[Item]
                  col2[Outdented Column]
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KanbanParser.parse(
                """
                kanban
                  col1[Todo]
                    task1[Item]@{ shape: Invalid_Shape }
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KanbanParser.parse(
                """
                kanban
                  col1[Todo]
                    task1[Item]@{ ticket: MC-1
                """.trimIndent(),
            )
        }
    }
}
