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

        // Same-side edge `a:R --> R:b` on row-aligned nodes detours below both `a` and `b`.
        val edge = layout.edges.single()
        assertEquals(6, edge.points.size)
        assertTrue(edge.points[2].y > maxOf(a.iconRect.bottom, b.iconRect.bottom))

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

        // A group with a very wide title does not expand over neighboring ungrouped services.
        val wideGroupTitle = ArchitectureParser.parse(
            """
            architecture-beta
              group g(cloud)[This Is A Very Long Forty Character Group Header Title]
              service a(server) in g
              service outside(server)[Outside]
              a:R -- L:outside
            """.trimIndent(),
        )
        val wgLayout = ArchitectureLayout.layout(wideGroupTitle)
        val wgOut = wgLayout.services.first { it.service.id == "outside" }
        val wgGrp = wgLayout.groups.single().rect
        assertTrue(wgOut.bounds.x > wgGrp.right)

        // Intersecting `align row a b` and `align column x b` satisfies both simultaneously.
        val intersecting = ArchitectureParser.parse(
            """
            architecture-beta
              service a(server)[A]
              service b(server)[B]
              service x(server)[X]
              service y(server)[Y]
              align row a b
              align column x b
              a:R -- L:x
              a:B -- T:y
            """.trimIndent(),
        )
        val icLayout = ArchitectureLayout.layout(intersecting)
        val icById = icLayout.services.associateBy { it.service.id }
        assertEquals(icById.getValue("a").iconRect.centerY, icById.getValue("b").iconRect.centerY, 0.5f)
        assertEquals(icById.getValue("x").iconRect.centerX, icById.getValue("b").iconRect.centerX, 0.5f)
        assertTrue(icById.getValue("y").iconRect.y > icById.getValue("a").iconRect.bottom)

        // Cyclic backward horizontal edges detour outside the row of icons.
        val cyclic = ArchitectureParser.parse(
            """
            architecture-beta
              service a(server)[A]
              service b(server)[B]
              service c(server)[C]
              a:R --> L:b
              b:R --> L:c
              c:R --> L:a
            """.trimIndent(),
        )
        val cycLayout = ArchitectureLayout.layout(cyclic)
        val cycById = cycLayout.services.associateBy { it.service.id }
        val backEdge = cycLayout.edges.first { it.points.size == 6 }
        assertTrue(backEdge.points[2].y > cycById.getValue("a").iconRect.bottom)

        // Empty groups (including empty child groups) and deeply nested groups do not overlap unrelated ungrouped services.
        val emptyAndNested = ArchitectureParser.parse(
            """
            architecture-beta
              group emptyGrp(cloud)[Empty Group]
              group outer(cloud)[Outer]
              group emptyChild(cloud)[Empty Child] in outer
              group middle(cloud)[Middle] in outer
              group inner(cloud)[Inner] in middle
              group leaf(cloud)[Leaf] in inner
              service a(server)[A] in leaf
              service outside(server)[Outside]
              outside:B -- T:a
              align row outside a
              outside:R --> T:a
            """.trimIndent(),
        )
        val enLayout = ArchitectureLayout.layout(emptyAndNested)
        val enOut = enLayout.services.first { it.service.id == "outside" }
        val enA = enLayout.services.first { it.service.id == "a" }
        val emptyRect = enLayout.groups.first { it.group.id == "emptyGrp" }.rect
        val outerRect = enLayout.groups.first { it.group.id == "outer" }.rect
        assertTrue(enOut.bounds.right < emptyRect.x || enOut.bounds.x > emptyRect.right)
        assertTrue(enOut.bounds.right < outerRect.x || enOut.bounds.x > outerRect.right)
        // Mixed-axis edge `outside:R --> T:a` on row-aligned nodes approaches `a`'s top port from above `a.iconRect.y`.
        val mixedEdge = enLayout.edges.last()
        assertTrue(mixedEdge.points[mixedEdge.points.size - 2].y < enA.iconRect.y)

        // Fan-out/fan-in diamond (`a -> b,c,d -> z`) keeps at least one full grid cell between `b`, `c`, and `d`.
        val fanDiamond = ArchitectureParser.parse(
            """
            architecture-beta
              service a(server)
              service b(server)
              service c(server)
              service d(server)
              service z(server)
              a:R -- L:b
              a:R -- L:c
              a:R -- L:d
              b:R -- L:z
              c:R -- L:z
              d:R -- L:z
            """.trimIndent(),
        )
        val fdLayout = ArchitectureLayout.layout(fanDiamond)
        val fdById = fdLayout.services.associateBy { it.service.id }
        assertTrue(fdById.getValue("b").bounds.bottom < fdById.getValue("c").bounds.y)
        assertTrue(fdById.getValue("c").bounds.bottom < fdById.getValue("d").bounds.y)

        // Forward edge skipping an intervening aligned service (`align row a b c` and `a:R --> L:c`) detours below `b`.
        val skipAligned = ArchitectureParser.parse(
            """
            architecture-beta
              service a(server)[A]
              service b(server)[B]
              service c(server)[C]
              align row a b c
              a:R --> L:c
            """.trimIndent(),
        )
        val saLayout = ArchitectureLayout.layout(skipAligned)
        val saB = saLayout.services.first { it.service.id == "b" }
        val saEdge = saLayout.edges.single()
        assertEquals(6, saEdge.points.size)
        assertTrue(saEdge.points[2].y > saB.bounds.bottom)

        // Mixed-axis L-bend obstructed by a corner service (`align row a b`, `align column b c`, `a:R --> T:c`)
        // detours around `b`, and an ungrouped node column-aligned with a group member shifts down instead of breaking column alignment.
        val cornerAndIntruder = ArchitectureParser.parse(
            """
            architecture-beta
              group g(cloud)[G]
              service a(server)[A] in g
              service c(server)[C] in g
              service b(server)[B]
              align column a b
              a:R --> T:c
            """.trimIndent(),
        )
        val ciLayout = ArchitectureLayout.layout(cornerAndIntruder)
        val ciById = ciLayout.services.associateBy { it.service.id }
        val ciGrp = ciLayout.groups.single().rect
        assertEquals(ciById.getValue("a").iconRect.centerX, ciById.getValue("b").iconRect.centerX, 0.5f)
        assertTrue(ciById.getValue("b").bounds.y > ciGrp.bottom)

        // Same-side connection `a:R --> R:c` obstructed by `b` (`align row a b`, `align column b c`) detours around `b`.
        val sameSideCorner = ArchitectureParser.parse(
            """
            architecture-beta
              service a(server)
              service b(server)
              service c(server)
              align row a b
              align column b c
              a:R --> R:c
            """.trimIndent(),
        )
        val sscLayout = ArchitectureLayout.layout(sameSideCorner)
        val sscB = sscLayout.services.first { it.service.id == "b" }
        val sscEdge = sscLayout.edges.single()
        assertEquals(6, sscEdge.points.size)
        assertTrue(sscEdge.points[1].x < sscB.bounds.x)

        // Forward edge skipping an intervening aligned junction (`align row a j c` and `a:R --> L:c`) detours below `j`.
        val skipJunction = ArchitectureParser.parse(
            """
            architecture-beta
              service a(server)[A]
              junction j
              service c(server)[C]
              align row a j c
              a:R --> L:c
            """.trimIndent(),
        )
        val sjLayout = ArchitectureLayout.layout(skipJunction)
        val sjJ = sjLayout.junctions.single()
        val sjEdge = sjLayout.edges.single()
        assertEquals(6, sjEdge.points.size)
        assertTrue(sjEdge.points[2].y > sjJ.rect.bottom)

        // Same-side connection on a service with a very wide label clears the endpoint's own label rectangle.
        val wideLabelSameSide = ArchitectureParser.parse(
            """
            architecture-beta
              service a(server)[A very long service label]
              service b(server)[B]
              a:R -- R:b
            """.trimIndent(),
        )
        val wlLayout = ArchitectureLayout.layout(wideLabelSameSide)
        val wlA = wlLayout.services.first { it.service.id == "a" }
        val wlEdge = wlLayout.edges.single()
        assertTrue(wlEdge.points[1].x > wlA.labelRect!!.right)

        // Tall measured group header height reserves vertical pitch so group box does not overlap service above it.
        val tallHeaderDiag = ArchitectureParser.parse(
            """
            architecture-beta
              group g[G]
              service a in g
              service outside
              outside:B -- T:a
            """.trimIndent(),
        )
        val thLayout = ArchitectureLayout.layout(
            diagram = tallHeaderDiag,
            groupLabelSize = { _, _ -> DiagramSize(100f, 200f) },
        )
        val outsideSvc = thLayout.services.first { it.service.id == "outside" }
        val gBox = thLayout.groups.single()
        assertTrue(outsideSvc.bounds.bottom < gBox.rect.y)

        // Same-side T->T fallback with intervening obstacle does not cross either endpoint's icon.
        val sameSideEndpointDiag = ArchitectureParser.parse(
            """
            architecture-beta
              service a
              service b
              service c
              a:L -- T:c
              b:T --> T:c
              a:R -- L:b
            """.trimIndent(),
        )
        val sseLayout = ArchitectureLayout.layout(sameSideEndpointDiag)
        val bIcon = sseLayout.services.first { it.service.id == "b" }.iconRect
        val bcEdge = sseLayout.edges[1]
        for (i in 0 until bcEdge.points.size - 1) {
            val p0 = bcEdge.points[i]
            val p1 = bcEdge.points[i + 1]
            // No interior point of any segment of b:T --> T:c may pass inside b's icon
            val midX = (p0.x + p1.x) / 2f
            val midY = (p0.y + p1.y) / 2f
            val insideB = midX > bIcon.x + 1f && midX < bIcon.right - 1f &&
                midY > bIcon.y + 1f && midY < bIcon.bottom - 1f
            assertTrue(!insideB, "Segment ($p0 -> $p1) crossed endpoint b's icon $bIcon")
        }
        assertTrue(sseLayout.width > 0f && sseLayout.height > 0f)

        // Opposite-port fallback `b:R -- L:d` checks detour legs against intervening tile `a`
        val oppositeDetourDiag = ArchitectureParser.parse(
            """
            architecture-beta
              service a
              service b
              service c
              service d
              b:R -- L:d
              b:B -- T:a
              c:B -- T:a
              a:B -- T:d
            """.trimIndent(),
        )
        val odLayout = ArchitectureLayout.layout(oppositeDetourDiag)
        val aIcon = odLayout.services.first { it.service.id == "a" }.iconRect
        val bdEdge = odLayout.edges.first()
        for (i in 0 until bdEdge.points.size - 1) {
            val p0 = bdEdge.points[i]
            val p1 = bdEdge.points[i + 1]
            val midX = (p0.x + p1.x) / 2f
            val midY = (p0.y + p1.y) / 2f
            val insideA = midX > aIcon.x + 1f && midX < aIcon.right - 1f &&
                midY > aIcon.y + 1f && midY < aIcon.bottom - 1f
            assertTrue(!insideA, "Segment ($p0 -> $p1) crossed intervening service a's icon $aIcon")
        }

        // Mixed-axis fallback `a:T -- R:d` checks detour legs against intervening tile `b`
        val mixedFallbackDiag = ArchitectureParser.parse(
            """
            architecture-beta
              service a
              service b
              service d
              align row d b
              b:B -- B:a
              a:T -- R:d
              a:B -- L:b
            """.trimIndent(),
        )
        val mfLayout = ArchitectureLayout.layout(mixedFallbackDiag)
        val mfBIcon = mfLayout.services.first { it.service.id == "b" }.iconRect
        val adEdge = mfLayout.edges[1]
        for (i in 0 until adEdge.points.size - 1) {
            val p0 = adEdge.points[i]
            val p1 = adEdge.points[i + 1]
            val midX = (p0.x + p1.x) / 2f
            val midY = (p0.y + p1.y) / 2f
            val insideB = midX > mfBIcon.x + 1f && midX < mfBIcon.right - 1f &&
                midY > mfBIcon.y + 1f && midY < mfBIcon.bottom - 1f
            assertTrue(!insideB, "Segment ($p0 -> $p1) crossed intervening service b's icon $mfBIcon")
        }
    }
}
