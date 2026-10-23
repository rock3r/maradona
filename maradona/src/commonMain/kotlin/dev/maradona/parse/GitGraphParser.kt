package dev.maradona.parse

import dev.maradona.model.GitBranch
import dev.maradona.model.GitCommit
import dev.maradona.model.GitCommitType
import dev.maradona.model.GitGraphDiagram
import dev.maradona.model.GitGraphDirection
import kotlin.math.max

/**
 * Hand-written parser for Mermaid `gitGraph` diagrams.
 *
 * Supports:
 * - `gitGraph`, `gitGraph:`, `gitGraph LR:`, `gitGraph TB:`, `gitGraph BT:`
 * - YAML frontmatter and `%%{init}%%` configuration (`title`, `showBranches`, `showCommitLabel`,
 *   `rotateCommitLabel`, `parallelCommits`, `mainBranchName`, `mainBranchOrder`)
 * - `commit` (`id:`, `tag:`, `type: NORMAL | REVERSE | HIGHLIGHT`, `msg:`)
 * - `branch <name> [order: <int>]` (creates and checks out `<name>`)
 * - `checkout <name>` / `switch <name>`
 * - `merge <branch>` (`id:`, `tag:`, `type:`)
 * - `cherry-pick id: "<id>" [parent: "<parentId>"] [tag: "..."] [type: ...]`
 */
object GitGraphParser {

    private sealed interface PendingCommand {
        data class CommitCmd(
            val explicitId: String?,
            val message: String?,
            val tag: String?,
            val type: GitCommitType,
        ) : PendingCommand

        data class BranchCmd(
            val name: String,
            val order: Int?,
        ) : PendingCommand

        data class CheckoutCmd(
            val name: String,
        ) : PendingCommand

        data class MergeCmd(
            val branch: String,
            val explicitId: String?,
            val tag: String?,
            val type: GitCommitType,
        ) : PendingCommand

        data class CherryPickCmd(
            val targetId: String,
            val parentId: String?,
            val tag: String?,
            val type: GitCommitType,
        ) : PendingCommand
    }

    fun parse(source: String): GitGraphDiagram {
        var frontmatterTitle: String? = null
        var showBranches = true
        var showCommitLabel = true
        var rotateCommitLabel = true
        var parallelCommits = false
        var mainBranchName = "main"
        var mainBranchOrder = 0

        val initMatch = Regex("""%%\{.*?\}%%""", setOf(RegexOption.DOT_MATCHES_ALL)).find(source)
        if (initMatch != null) {
            val initBody = initMatch.value
            parseBooleanSetting(initBody, "showBranches")?.let { showBranches = it }
            parseBooleanSetting(initBody, "showCommitLabel")?.let { showCommitLabel = it }
            parseBooleanSetting(initBody, "rotateCommitLabel")?.let { rotateCommitLabel = it }
            parseBooleanSetting(initBody, "parallelCommits")?.let { parallelCommits = it }
            Regex("""['"]?mainBranchName['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    mainBranchName = it
                }
            Regex("""['"]?mainBranchOrder['"]?\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
                .find(initBody)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    mainBranchOrder = it
                }
        }

        val rawLines = FlowchartParser.stripDirectives(source).lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripLineComment(rawLines[idx]).trim().isEmpty()) {
            idx++
        }
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            var activeFmQuote: Char? = null
            while (idx < rawLines.size) {
                val rawFm = rawLines[idx]
                idx++
                val trimmedFm = rawFm.trim()
                if (activeFmQuote == null && (trimmedFm == "---" || trimmedFm == "...")) break
                val cleanFm = GanttParser.stripYamlCommentWithQuoteState(rawFm, activeFmQuote)
                activeFmQuote = cleanFm.second
                val fmLine = cleanFm.first.trim()
                if (fmLine.isEmpty()) continue
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    val value = unquote(fmLine.substring(colon + 1).trim())
                    when (key) {
                        "title" -> if (value.isNotEmpty()) frontmatterTitle = value
                        "showbranches" -> value.toBooleanStrictOrNull()?.let { showBranches = it }
                        "showcommitlabel" -> value.toBooleanStrictOrNull()?.let { showCommitLabel = it }
                        "rotatecommitlabel" -> value.toBooleanStrictOrNull()?.let { rotateCommitLabel = it }
                        "parallelcommits" -> value.toBooleanStrictOrNull()?.let { parallelCommits = it }
                        "mainbranchname" -> if (value.isNotEmpty()) mainBranchName = value
                        "mainbranchorder" -> value.toIntOrNull()?.let { mainBranchOrder = it }
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        var headerSeen = false
        var direction = GitGraphDirection.LR
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()
        val commands = mutableListOf<PendingCommand>()

        for (rawLine in bodyLines) {
            val clean = stripLineComment(rawLine).trim()
            if (inAccDescrBlock) {
                if (clean == "}") {
                    accDescr = accDescrLines.joinToString("\n").trim()
                    accDescrLines.clear()
                    inAccDescrBlock = false
                } else {
                    accDescrLines += clean
                }
                continue
            }
            if (clean.isEmpty()) continue
            val lower = clean.lowercase()

            if (!headerSeen) {
                if (lower.startsWith("gitgraph")) {
                    headerSeen = true
                    val headerRest = clean.substring("gitGraph".length).trim().removeSuffix(":").trim().uppercase()
                    direction = when (headerRest) {
                        "TB", "TD" -> GitGraphDirection.TB
                        "BT" -> GitGraphDirection.BT
                        else -> GitGraphDirection.LR
                    }
                    continue
                }
                headerSeen = true
            }

            when {
                lower.startsWith("title ") || lower.startsWith("title:") -> {
                    val after = clean.substring(5).trim().removePrefix(":").trim()
                    if (after.isNotEmpty()) chartTitle = unquote(after)
                    continue
                }
                lower.startsWith("acctitle:") -> {
                    accTitle = unquote(clean.substring("acctitle:".length).trim())
                    continue
                }
                lower.startsWith("accdescr:") -> {
                    accDescr = unquote(clean.substring("accdescr:".length).trim())
                    continue
                }
                lower.startsWith("accdescr") && clean.endsWith("{") -> {
                    inAccDescrBlock = true
                    accDescrLines.clear()
                    continue
                }
            }

            when {
                lower == "commit" || lower.startsWith("commit ") || lower.startsWith("commit\t") -> {
                    val opts = parseCommitOptions(clean.substring(6).trim())
                    commands += PendingCommand.CommitCmd(
                        explicitId = opts.id,
                        message = opts.message,
                        tag = opts.tag,
                        type = opts.type,
                    )
                }
                lower.startsWith("branch ") || lower.startsWith("branch\t") -> {
                    val rest = clean.substring(6).trim()
                    val orderMatch = Regex("""\s+order\s*:\s*(-?\d+)\s*$""", RegexOption.IGNORE_CASE).find(rest)
                    val branchName = if (orderMatch != null) {
                        unquote(rest.substring(0, orderMatch.range.first).trim())
                    } else {
                        unquote(rest)
                    }
                    val order = orderMatch?.groupValues?.get(1)?.toIntOrNull()
                    if (branchName.isEmpty()) {
                        throw IllegalArgumentException("Branch name cannot be empty: $clean")
                    }
                    commands += PendingCommand.BranchCmd(branchName, order)
                }
                lower.startsWith("checkout ") || lower.startsWith("checkout\t") -> {
                    val name = unquote(clean.substring(8).trim())
                    commands += PendingCommand.CheckoutCmd(name)
                }
                lower.startsWith("switch ") || lower.startsWith("switch\t") -> {
                    val name = unquote(clean.substring(6).trim())
                    commands += PendingCommand.CheckoutCmd(name)
                }
                lower.startsWith("merge ") || lower.startsWith("merge\t") -> {
                    val rest = clean.substring(5).trim()
                    val (targetBranch, optsText) = splitFirstToken(rest)
                    val opts = parseCommitOptions(optsText)
                    commands += PendingCommand.MergeCmd(
                        branch = unquote(targetBranch),
                        explicitId = opts.id,
                        tag = opts.tag,
                        type = opts.type,
                    )
                }
                lower.startsWith("cherry-pick") -> {
                    val rest = clean.substring("cherry-pick".length).trim()
                    val opts = parseCommitOptions(rest)
                    val targetId = opts.id
                        ?: throw IllegalArgumentException("cherry-pick requires id: \"<commitId>\" in: $clean")
                    commands += PendingCommand.CherryPickCmd(
                        targetId = targetId,
                        parentId = opts.parent,
                        tag = opts.tag,
                        type = opts.type,
                    )
                }
                else -> throw IllegalArgumentException("Unrecognized gitGraph statement: $clean")
            }
        }

        // First reserve all explicit commit IDs so auto-generated commit IDs never collide with an explicit ID.
        val usedCommitIds = HashSet<String>()
        for (cmd in commands) {
            val expId = when (cmd) {
                is PendingCommand.CommitCmd -> cmd.explicitId
                is PendingCommand.MergeCmd -> cmd.explicitId
                else -> null
            }
            if (expId != null && !usedCommitIds.add(expId)) {
                throw IllegalArgumentException("Duplicate commit ID '$expId' in gitGraph")
            }
        }

        var autoCommitIndex = 0
        fun nextAutoCommitId(): String {
            var candidate: String
            do {
                val n = autoCommitIndex++
                val hex = (0x10a0000 + n * 0x137).toString(16).takeLast(7)
                candidate = "$n-$hex"
            } while (!usedCommitIds.add(candidate))
            return candidate
        }

        val branchesByName = LinkedHashMap<String, GitBranch>()
        branchesByName[mainBranchName] = GitBranch(
            name = mainBranchName,
            order = mainBranchOrder,
            insertionIndex = 0,
        )
        var currentBranch = mainBranchName
        val branchHeads = HashMap<String, String?>()
        branchHeads[mainBranchName] = null

        val commits = mutableListOf<GitCommit>()
        val commitsById = HashMap<String, GitCommit>()
        val seqByCommitId = HashMap<String, Int>()
        val occupiedBranchSeq = HashSet<Pair<String, Int>>()

        fun allocateSeq(branch: String, parents: List<String>): Int {
            if (!parallelCommits) {
                return commits.size
            }
            var seq = if (parents.isEmpty()) {
                0
            } else {
                parents.maxOf { seqByCommitId[it] ?: 0 } + 1
            }
            while (!occupiedBranchSeq.add(branch to seq)) {
                seq++
            }
            return seq
        }

        for (cmd in commands) {
            when (cmd) {
                is PendingCommand.CommitCmd -> {
                    val id = cmd.explicitId ?: nextAutoCommitId()
                    val parent = branchHeads[currentBranch]
                    val parents = listOfNotNull(parent)
                    val seq = allocateSeq(currentBranch, parents)
                    val c = GitCommit(
                        id = id,
                        hasExplicitId = cmd.explicitId != null,
                        message = cmd.message,
                        branch = currentBranch,
                        type = cmd.type,
                        tag = cmd.tag,
                        parents = parents,
                        isMerge = false,
                        isCherryPick = false,
                        seq = seq,
                    )
                    commits += c
                    commitsById[id] = c
                    seqByCommitId[id] = seq
                    branchHeads[currentBranch] = id
                }
                is PendingCommand.BranchCmd -> {
                    if (cmd.name in branchesByName) {
                        throw IllegalArgumentException("Branch '${cmd.name}' already exists")
                    }
                    branchesByName[cmd.name] = GitBranch(
                        name = cmd.name,
                        order = cmd.order,
                        insertionIndex = branchesByName.size,
                    )
                    branchHeads[cmd.name] = branchHeads[currentBranch]
                    currentBranch = cmd.name
                }
                is PendingCommand.CheckoutCmd -> {
                    val target = branchesByName[cmd.name]
                        ?: throw IllegalArgumentException("Cannot checkout non-existent branch '${cmd.name}'")
                    currentBranch = target.name
                }
                is PendingCommand.MergeCmd -> {
                    if (cmd.branch == currentBranch) {
                        throw IllegalArgumentException("Cannot merge branch '${cmd.branch}' into itself")
                    }
                    if (cmd.branch !in branchesByName) {
                        throw IllegalArgumentException("Cannot merge non-existent branch '${cmd.branch}'")
                    }
                    val sourceHead = branchHeads[cmd.branch]
                        ?: throw IllegalArgumentException("Cannot merge branch '${cmd.branch}' with no commits")
                    val currentHead = branchHeads[currentBranch]
                        ?: throw IllegalArgumentException("Cannot merge into empty branch '$currentBranch'")
                    if (currentHead == sourceHead) {
                        throw IllegalArgumentException("Cannot merge branch '${cmd.branch}' when head '$sourceHead' is already identical")
                    }
                    val parents = listOf(currentHead, sourceHead)
                    val id = cmd.explicitId ?: nextAutoCommitId()
                    val seq = allocateSeq(currentBranch, parents)
                    val c = GitCommit(
                        id = id,
                        hasExplicitId = cmd.explicitId != null,
                        branch = currentBranch,
                        type = cmd.type,
                        tag = cmd.tag,
                        parents = parents,
                        isMerge = true,
                        isCherryPick = false,
                        seq = seq,
                    )
                    commits += c
                    commitsById[id] = c
                    seqByCommitId[id] = seq
                    branchHeads[currentBranch] = id
                }
                is PendingCommand.CherryPickCmd -> {
                    val sourceCommit = commitsById[cmd.targetId]
                        ?: throw IllegalArgumentException("Cannot cherry-pick non-existent commit '${cmd.targetId}'")
                    if (sourceCommit.branch == currentBranch) {
                        throw IllegalArgumentException("Cannot cherry-pick commit '${cmd.targetId}' onto its own branch '$currentBranch'")
                    }
                    val currentHead = branchHeads[currentBranch]
                        ?: throw IllegalArgumentException("Cannot cherry-pick onto empty branch '$currentBranch'")
                    if (sourceCommit.isMerge && cmd.parentId == null) {
                        throw IllegalArgumentException("cherry-pick of merge commit '${cmd.targetId}' requires parent: \"<parentId>\"")
                    }
                    if (cmd.parentId != null && cmd.parentId !in sourceCommit.parents) {
                        throw IllegalArgumentException("Specified parent '${cmd.parentId}' is not an immediate parent of commit '${cmd.targetId}'")
                    }
                    val parents = listOf(currentHead, sourceCommit.id).distinct()
                    val id = nextAutoCommitId()
                    val seq = allocateSeq(currentBranch, parents)
                    val c = GitCommit(
                        id = id,
                        hasExplicitId = false,
                        branch = currentBranch,
                        type = cmd.type,
                        tag = cmd.tag ?: "cherry-pick:${sourceCommit.id}",
                        parents = parents,
                        isMerge = false,
                        isCherryPick = true,
                        seq = seq,
                    )
                    commits += c
                    commitsById[id] = c
                    seqByCommitId[id] = seq
                    branchHeads[currentBranch] = id
                }
            }
        }

        val sortedBranches = branchesByName.values.sortedWith(
            compareBy<GitBranch> { b -> b.order?.toDouble() ?: (b.insertionIndex.toDouble() / 1000.0) }
                .thenBy { it.insertionIndex },
        )

        return GitGraphDiagram(
            branches = sortedBranches,
            commits = commits,
            direction = direction,
            title = chartTitle ?: frontmatterTitle,
            showBranches = showBranches,
            showCommitLabel = showCommitLabel,
            rotateCommitLabel = rotateCommitLabel,
            parallelCommits = parallelCommits,
            mainBranchName = mainBranchName,
            mainBranchOrder = mainBranchOrder,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private data class ParsedCommitOptions(
        val id: String? = null,
        val message: String? = null,
        val tag: String? = null,
        val type: GitCommitType = GitCommitType.Normal,
        val parent: String? = null,
    )

    private fun parseCommitOptions(text: String): ParsedCommitOptions {
        var id: String? = null
        var message: String? = null
        var tag: String? = null
        var type = GitCommitType.Normal
        var parent: String? = null

        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break

            // Standalone quoted string on `commit "message"` sets message without overwriting explicit `id:`.
            if (text[i] == '"' || text[i] == '\'') {
                val (valStr, nextIdx) = readQuotedOrToken(text, i)
                if (message == null && valStr.isNotEmpty()) message = valStr
                i = nextIdx
                continue
            }

            val colonIdx = text.indexOf(':', i)
            val spaceIdx = (i until n).firstOrNull { text[it].isWhitespace() } ?: n
            if (colonIdx in (i + 1)..spaceIdx) {
                val key = text.substring(i, colonIdx).trim().lowercase()
                var valStart = colonIdx + 1
                while (valStart < n && text[valStart].isWhitespace()) valStart++
                val (valStr, nextIdx) = readQuotedOrToken(text, valStart)
                when (key) {
                    "id" -> if (valStr.isNotEmpty()) id = valStr
                    "msg" -> if (valStr.isNotEmpty()) message = valStr
                    "tag" -> if (valStr.isNotEmpty()) tag = valStr
                    "parent" -> if (valStr.isNotEmpty()) parent = valStr
                    "type" -> {
                        type = when (valStr.uppercase()) {
                            "REVERSE" -> GitCommitType.Reverse
                            "HIGHLIGHT" -> GitCommitType.Highlight
                            "NORMAL" -> GitCommitType.Normal
                            else -> throw IllegalArgumentException("Unknown commit type '$valStr'")
                        }
                    }
                }
                i = nextIdx
            } else {
                val (_, nextIdx) = readQuotedOrToken(text, i)
                i = nextIdx
            }
        }

        return ParsedCommitOptions(id = id, message = message, tag = tag, type = type, parent = parent)
    }

    private fun splitFirstToken(text: String): Pair<String, String> {
        val s = text.trim()
        if (s.isEmpty()) return "" to ""
        val (tok, nextIdx) = readQuotedOrToken(s, 0)
        return tok to s.substring(nextIdx).trim()
    }

    private fun readQuotedOrToken(text: String, start: Int): Pair<String, Int> {
        if (start >= text.length) return "" to start
        val first = text[start]
        if (first == '"' || first == '\'') {
            val sb = StringBuilder()
            var i = start + 1
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    sb.append(text[i + 1])
                    i += 2
                    continue
                }
                if (c == first) {
                    return sb.toString() to (i + 1)
                }
                sb.append(c)
                i++
            }
            throw IllegalArgumentException("Unclosed quoted string in gitGraph: $text")
        }
        var i = start
        while (i < text.length && !text[i].isWhitespace()) i++
        return text.substring(start, i) to i
    }

    private fun parseBooleanSetting(body: String, key: String): Boolean? {
        val m = Regex("""['"]?$key['"]?\s*:\s*['"]?(true|false)['"]?""", RegexOption.IGNORE_CASE).find(body)
        return m?.groupValues?.get(1)?.lowercase()?.toBooleanStrictOrNull()
    }

    private fun stripLineComment(line: String): String {
        var inQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuote != null && i + 1 < line.length) {
                i += 2
                continue
            }
            if (inQuote == null && (c == '"' || c == '\'')) {
                inQuote = c
                i++
                continue
            }
            if (inQuote != null && c == inQuote) {
                inQuote = null
                i++
                continue
            }
            if (inQuote == null && i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .trim()
        }
        return t
    }
}
