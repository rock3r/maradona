package dev.maradona.parse

import dev.maradona.model.GitCommitType
import dev.maradona.model.GitGraphDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitGraphParserTest {

    @Test
    fun parsesCommitsBranchesMergesCherryPickAndFrontmatterOptions() {
        val src = """
            ---
            title: Example Git diagram
            config:
              gitGraph:
                mainBranchName: 'MetroLine1'
                mainBranchOrder: 2
            ---
            gitGraph TB:
                commit id: "ZERO" tag: "v0.1"
                branch develop order: 3
                branch release order: 1
                commit id:"A" type:HIGHLIGHT
                checkout MetroLine1
                commit id:"ONE" type: REVERSE
                checkout develop
                commit id:"B"
                checkout MetroLine1
                merge develop id:"MERGE" tag:"v1.0"
                commit id:"TWO"
                checkout release
                cherry-pick id:"MERGE" parent:"B"
                commit id:"THREE"
        """.trimIndent()

        val model = MaradonaParser.parse(src)
        assertIs<MaradonaDiagramModel.GitGraphModel>(model)
        val d = model.diagram
        assertEquals("Example Git diagram", d.title)
        assertEquals(GitGraphDirection.TB, d.direction)
        assertEquals("MetroLine1", d.mainBranchName)
        assertEquals(listOf("release", "MetroLine1", "develop"), d.branches.map { it.name })

        val a = assertNotNull(d.commit("A"))
        assertEquals("release", a.branch)
        assertEquals(GitCommitType.Highlight, a.type)
        assertEquals(listOf("ZERO"), a.parents)

        val one = assertNotNull(d.commit("ONE"))
        assertEquals(GitCommitType.Reverse, one.type)

        val merge = assertNotNull(d.commit("MERGE"))
        assertTrue(merge.isMerge)
        assertEquals(listOf("ONE", "B"), merge.parents)
        assertEquals("v1.0", merge.tag)

        val cherry = d.commits.first { it.isCherryPick }
        assertEquals("release", cherry.branch)
        assertEquals(listOf("A", "MERGE"), cherry.parents)
    }

    @Test
    fun supportsParallelCommitsAndValidatesInvalidOperations() {
        val parallel = GitGraphParser.parse(
            """
            ---
            config:
              gitGraph:
                parallelCommits: true
                showBranches: false
                showCommitLabel: false
            ---
            gitGraph:
              commit id: "c0"
              branch develop
              commit id: "d1"
              commit id: "d2"
              checkout main
              commit id: "m1"
              commit id: "m2"
            """.trimIndent(),
        )
        assertTrue(parallel.parallelCommits)
        assertFalse(parallel.showBranches)
        assertFalse(parallel.showCommitLabel)
        // With parallelCommits: true, d1 and m1 both have parent c0 (seq=0), so both get seq=1.
        assertEquals(1, assertNotNull(parallel.commit("d1")).seq)
        assertEquals(1, assertNotNull(parallel.commit("m1")).seq)

        // Merging a branch into itself must fail.
        assertFailsWith<IllegalArgumentException> {
            GitGraphParser.parse(
                """
                gitGraph
                  commit
                  merge main
                """.trimIndent(),
            )
        }

        // Cherry-picking a merge commit without `parent:` must fail.
        assertFailsWith<IllegalArgumentException> {
            GitGraphParser.parse(
                """
                gitGraph
                  commit id: "0"
                  branch dev
                  commit id: "1"
                  checkout main
                  commit id: "2"
                  merge dev id: "M"
                  branch rel
                  cherry-pick id: "M"
                """.trimIndent(),
            )
        }

        // `msg:` must not overwrite `id:`, and explicit `order: 0` must sort before unordered non-main branches.
        val msgAndOrder = GitGraphParser.parse(
            """
            gitGraph
              commit id:"A" msg:"initial implementation"
              branch develop
              commit
              branch release order: 0
              cherry-pick id:"A"
            """.trimIndent(),
        )
        assertEquals(listOf("main", "release", "develop"), msgAndOrder.branches.map { it.name })
        val commitA = assertNotNull(msgAndOrder.commit("A"))
        assertEquals("initial implementation", commitA.message)

        // Merging into an empty branch or merging a branch with identical head must fail.
        assertFailsWith<IllegalArgumentException> {
            GitGraphParser.parse(
                """
                gitGraph
                  branch dev
                  commit
                  checkout main
                  merge dev
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GitGraphParser.parse(
                """
                gitGraph
                  commit
                  branch dev
                  checkout main
                  merge dev
                """.trimIndent(),
            )
        }

        // Cherry-picking an ordinary commit with an invalid `parent:` must fail.
        assertFailsWith<IllegalArgumentException> {
            GitGraphParser.parse(
                """
                gitGraph
                  commit id:"root"
                  branch dev
                  commit id:"feature"
                  checkout main
                  cherry-pick id:"feature" parent:"does-not-exist"
                """.trimIndent(),
            )
        }
    }
}
