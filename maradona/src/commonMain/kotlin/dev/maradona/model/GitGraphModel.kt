package dev.maradona.model

enum class GitCommitType {
    Normal,
    Reverse,
    Highlight,
}

enum class GitGraphDirection {
    LR,
    TB,
    BT,
}

data class GitBranch(
    val name: String,
    val order: Int? = null,
    val insertionIndex: Int = 0,
)

data class GitCommit(
    val id: String,
    val hasExplicitId: Boolean = false,
    val message: String? = null,
    val branch: String,
    val type: GitCommitType = GitCommitType.Normal,
    val tag: String? = null,
    val parents: List<String> = emptyList(),
    val isMerge: Boolean = false,
    val isCherryPick: Boolean = false,
    val seq: Int = 0,
)

data class GitGraphDiagram(
    val branches: List<GitBranch>,
    val commits: List<GitCommit>,
    val direction: GitGraphDirection = GitGraphDirection.LR,
    val title: String? = null,
    val showBranches: Boolean = true,
    val showCommitLabel: Boolean = true,
    val rotateCommitLabel: Boolean = true,
    val parallelCommits: Boolean = false,
    val mainBranchName: String = "main",
    val mainBranchOrder: Int = 0,
    val accTitle: String? = null,
    val accDescr: String? = null,
) {
    fun commit(id: String): GitCommit? = commits.firstOrNull { it.id == id }
    fun branch(name: String): GitBranch? = branches.firstOrNull { it.name == name }
}
