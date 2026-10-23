package dev.maradona.layout

import dev.maradona.parse.GitGraphParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitGraphLayoutTest {

    @Test
    fun laysOutHorizontalAndVerticalGitGraphsWithoutLabelOrNodeOverlaps() {
        val lrDiagram = GitGraphParser.parse(
            """
            ---
            title: Git Workflow
            ---
            gitGraph
                commit id: "feat(api): initial" tag: "v0.1"
                commit id: "fix(client): extra long commit label here"
                branch develop
                commit id: "feat(modules): add layout" type: HIGHLIGHT
                checkout main
                commit id: "hotfix: urgent" type: REVERSE
                merge develop id: "merge-dev" tag: "v1.0"
            """.trimIndent(),
        )

        val lrLayout = GitGraphLayout.layout(lrDiagram)
        assertTrue(lrLayout.width > 0f && lrLayout.height > 0f)
        assertEquals(2, lrLayout.branches.size)
        assertEquals(5, lrLayout.commits.size)
        assertTrue(lrLayout.edges.size >= 5)

        // Commit nodes, commit labels, and tags must stay within layout bounds and never overlap each other.
        val allCommitBoxes = buildList {
            for (c in lrLayout.commits) {
                add("node:${c.commit.id}" to c.nodeRect)
                c.commitLabelRect?.let { add("lbl:${c.commit.id}" to it) }
                c.tagRect?.let { add("tag:${c.commit.id}" to it) }
            }
        }
        for (i in allCommitBoxes.indices) {
            val (nameA, a) = allCommitBoxes[i]
            assertTrue(a.x >= 0f && a.right <= lrLayout.width + 0.5f && a.y >= 0f && a.bottom <= lrLayout.height + 0.5f, "$nameA out of bounds: $a")
            for (j in i + 1 until allCommitBoxes.size) {
                val (nameB, b) = allCommitBoxes[j]
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertTrue(!overlap, "GitGraph items $nameA ($a) and $nameB ($b) must not overlap")
            }
        }

        // Bottom-to-top (BT) direction places earlier commits below later commits.
        val btDiagram = GitGraphParser.parse(
            """
            gitGraph BT:
                commit id: "first"
                commit id: "second"
            """.trimIndent(),
        )
        val btLayout = GitGraphLayout.layout(btDiagram)
        val firstPos = assertNotNull(btLayout.commit("first"))
        val secondPos = assertNotNull(btLayout.commit("second"))
        assertTrue(firstPos.center.y > secondPos.center.y, "In BT direction, first commit must be below second commit")
        assertTrue(lrLayout.commits.first().commitLabelAngleDeg < 0f, "Default rotateCommitLabel=true in LR must rotate commit labels")

        // Vertical TB layout with long branch names must space lanes so branch pills never overlap.
        val tbLongBranches = GitGraphParser.parse(
            """
            gitGraph TB:
                commit
                branch very-long-development-branch-name
                commit
                branch very-long-release-branch-name
                commit
            """.trimIndent(),
        )
        val tbLayout = GitGraphLayout.layout(tbLongBranches)
        val branchRects = tbLayout.branches.mapNotNull { it.labelRect }
        assertEquals(3, branchRects.size)
        for (i in branchRects.indices) {
            val a = branchRects[i]
            assertTrue(a.x >= 0f && a.right <= tbLayout.width + 0.5f)
            for (j in i + 1 until branchRects.size) {
                val b = branchRects[j]
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertTrue(!overlap, "Vertical branch pills $a and $b must not overlap")
            }
        }

        // Cross-branch edge from `root` to `feature1` must detour around intervening `dev1` commit node.
        val forkDiagram = GitGraphParser.parse(
            """
            gitGraph
                commit id:"root"
                branch dev
                commit id:"dev1"
                checkout main
                branch feature
                commit id:"feature1"
            """.trimIndent(),
        )
        val forkLayout = GitGraphLayout.layout(forkDiagram)
        val dev1Node = assertNotNull(forkLayout.commit("dev1")).nodeRect
        val rootToFeatureEdge = forkLayout.edges.first { it.fromCommitId == "root" && it.toCommitId == "feature1" }
        val samples = GitGraphLayout.sampleCubicBezier(
            rootToFeatureEdge.points[0],
            rootToFeatureEdge.points[1],
            rootToFeatureEdge.points[2],
            rootToFeatureEdge.points[3],
            steps = 24,
        )
        for (pt in samples) {
            val hitsDev1 = pt.x >= dev1Node.x && pt.x <= dev1Node.right && pt.y >= dev1Node.y && pt.y <= dev1Node.bottom
            assertTrue(!hitsDev1, "Cross-branch curve root->feature1 must not pass through intervening dev1 commit ($dev1Node), hit at $pt")
        }
    }
}
