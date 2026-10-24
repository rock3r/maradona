package dev.maradona.layout

import dev.maradona.parse.ArchitectureParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchitectureLayoutTest {

    @Test
    fun laysOutAlignedServicesGroupsAndJunctionsConsistentWithPortDirections() {
        val diagram = ArchitectureParser.parse(
            """
            architecture-beta
              group api(cloud)[API]
              service left_disk(disk)[Left Disk] in api
              service top_disk(disk)[Top Disk] in api
              service bottom_disk(disk)[Bottom Disk] in api
              junction j_center in api
              service right_srv(server)[Server] in api

              left_disk:R -- L:j_center
              top_disk:B -- T:j_center
              bottom_disk:T -- B:j_center
              j_center:R --> L:right_srv
            """.trimIndent(),
        )
        val layout = ArchitectureLayout.layout(diagram)

        val svcById = layout.services.associateBy { it.service.id }
        val jCenter = layout.junctions.single()
        val leftDisk = svcById.getValue("left_disk")
        val topDisk = svcById.getValue("top_disk")
        val bottomDisk = svcById.getValue("bottom_disk")
        val rightSrv = svcById.getValue("right_srv")

        // left_disk -> j_center -> right_srv are ordered left-to-right on the same row.
        assertTrue(leftDisk.iconRect.centerX < jCenter.center.x)
        assertTrue(jCenter.center.x < rightSrv.iconRect.centerX)
        assertEquals(leftDisk.iconRect.centerY, jCenter.center.y, 0.5f)
        assertEquals(jCenter.center.y, rightSrv.iconRect.centerY, 0.5f)

        // top_disk -> j_center -> bottom_disk are ordered top-to-bottom on the same column.
        assertTrue(topDisk.iconRect.centerY < jCenter.center.y)
        assertTrue(jCenter.center.y < bottomDisk.iconRect.centerY)
        assertEquals(topDisk.iconRect.centerX, jCenter.center.x, 0.5f)
        assertEquals(jCenter.center.x, bottomDisk.iconRect.centerX, 0.5f)

        // Enclosing group `api` contains all 4 services and the junction.
        val groupBox = layout.groups.single().rect
        for (s in layout.services) {
            assertTrue(s.bounds.x >= groupBox.x && s.bounds.right <= groupBox.right)
            assertTrue(s.bounds.y >= groupBox.y && s.bounds.bottom <= groupBox.bottom)
        }
    }

    @Test
    fun rejectsContradictoryAlignments() {
        assertFailsWith<IllegalArgumentException> {
            ArchitectureParser.parse(
                """
                architecture-beta
                  service a(server)[A]
                  service b(server)[B]
                  align row a b
                  align column a b
                """.trimIndent(),
            )
        }
    }

    @Test
    fun separatesGroupedNodesFromOutsideNodesAndRoutesSameSidePortsExternally() {
        val diagram = ArchitectureParser.parse(
            """
            architecture-beta
              group g(cloud)[Group]
              service a(server)[A] in g
              service outside(server)[Outside]
              service b(server)[B] in g
              a:R --> R:b
              align row a b
              align row a outside
            """.trimIndent(),
        )
        val layout = ArchitectureLayout.layout(diagram)
        val svcById = layout.services.associateBy { it.service.id }
        val a = svcById.getValue("a")
        val b = svcById.getValue("b")
        val outside = svcById.getValue("outside")
        val groupRect = layout.groups.single().rect

        // `b` and `outside` do not overlap despite overlapping `align row` directives.
        assertTrue(kotlin.math.abs(b.iconRect.centerX - outside.iconRect.centerX) > 10f)
        // `outside` is not enclosed inside group `g`.
        assertTrue(outside.bounds.x > groupRect.right || outside.bounds.right < groupRect.x)

        // Same-side edge `a:R --> R:b` routes externally to the right of both `a` and `b`.
        val edge = layout.edges.single()
        assertEquals(4, edge.points.size)
        assertTrue(edge.points[1].x > maxOf(a.iconRect.right, b.iconRect.right))

        // When a grouped node connects to an outside node and the group also has a disconnected member,
        // the group enclosure still excludes the outside node.
        val crossCompDiagram = ArchitectureParser.parse(
            """
            architecture-beta
              group g(cloud)[Group]
              service a(server)[A] in g
              service b(server)[B] in g
              service outside(server)[Outside]
              a:R -- L:outside
            """.trimIndent(),
        )
        val crossCompLayout = ArchitectureLayout.layout(crossCompDiagram)
        val ccSvcById = crossCompLayout.services.associateBy { it.service.id }
        val ccOut = ccSvcById.getValue("outside")
        val ccGrp = crossCompLayout.groups.single().rect
        assertTrue(
            ccOut.bounds.x > ccGrp.right ||
                ccOut.bounds.right < ccGrp.x ||
                ccOut.bounds.y > ccGrp.bottom ||
                ccOut.bounds.bottom < ccGrp.y,
        )

        // Fan-out on an explicitly row-aligned node preserves the row alignment.
        val fanOutAligned = ArchitectureParser.parse(
            """
            architecture-beta
              service a(server)[A]
              service b(server)[B]
              service x(server)[X]
              service y(server)[Y]
              align row a b
              a:R -- L:x
              a:R -- L:y
            """.trimIndent(),
        )
        val foLayout = ArchitectureLayout.layout(fanOutAligned)
        val foById = foLayout.services.associateBy { it.service.id }
        assertEquals(
            foById.getValue("a").iconRect.centerY,
            foById.getValue("b").iconRect.centerY,
            0.5f,
        )
    }
}
