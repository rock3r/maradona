package dev.maradona.parse

import dev.maradona.model.ArchitectureAlignDir
import dev.maradona.model.ArchitectureAlignment
import dev.maradona.model.ArchitectureConfig
import dev.maradona.model.ArchitectureDiagram
import dev.maradona.model.ArchitectureEdge
import dev.maradona.model.ArchitectureGroup
import dev.maradona.model.ArchitectureJunction
import dev.maradona.model.ArchitecturePortDir
import dev.maradona.model.ArchitectureService

/**
 * Hand-written parser for Mermaid `architecture` / `architecture-beta` diagrams.
 *
 * Supports:
 * - `architecture` and `architecture-beta` headers, YAML frontmatter, `%%{init}%%` configuration
 *   (`padding`, `iconSize`, `fontSize`, `nodeSpacing`, `title`), `title`, `accTitle`, and `accDescr`
 * - `group <id>(<icon>)?([<title>])? (in <parentGroupId>)?`
 * - `service <id>(<icon> | "<iconText>")?([<title>])? (in <parentGroupId>)?`
 * - `junction <id> (in <parentGroupId>)?`
 * - Port-to-port edges: `<lhsId>({group})?:<L|R|T|B> (<)? ( -- | -[<title>]- ) (>)? <L|R|T|B>:<rhsId>({group})?`
 * - Alignment directives: `align <row|column> <id1> <id2> ...`
 */
object ArchitectureParser {

    private enum class RegisteredKind {
        Group,
        Node,
    }

    private class MutableConfig(
        var padding: Float = 32f,
        var iconSize: Float = 64f,
        var fontSize: Float = 13f,
        var nodeSpacing: Float = 56f,
    ) {
        fun toImmutable(): ArchitectureConfig = ArchitectureConfig(
            padding = padding,
            iconSize = iconSize,
            fontSize = fontSize,
            nodeSpacing = nodeSpacing,
        )
    }

    fun parse(source: String): ArchitectureDiagram {
        var frontmatterTitle: String? = null
        val config = MutableConfig()

        val (strippedSource, initDirectives) = stripAndExtractActiveInitDirectives(source)
        val rawLines = strippedSource.lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripLineComment(rawLines[idx]).trim().isEmpty()) {
            idx++
        }
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            var activeFmQuote: Char? = null
            val sectionStack = mutableListOf<Pair<Int, String>>()
            while (idx < rawLines.size) {
                val rawFm = rawLines[idx]
                idx++
                val trimmedFm = rawFm.trim()
                if (activeFmQuote == null && (trimmedFm == "---" || trimmedFm == "...")) break
                val cleanFm = GanttParser.stripYamlCommentWithQuoteState(rawFm, activeFmQuote)
                activeFmQuote = cleanFm.second
                val fmLine = cleanFm.first.trim()
                if (fmLine.isEmpty()) continue
                val indent = cleanFm.first.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                while (sectionStack.isNotEmpty() && sectionStack.last().first >= indent) {
                    sectionStack.removeAt(sectionStack.lastIndex)
                }
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    var rawVal = fmLine.substring(colon + 1).trim()
                    if (rawVal.isEmpty()) {
                        sectionStack += indent to key
                        continue
                    }
                    if (rawVal.startsWith("{")) {
                        var braceBalance = countUnquotedBraceDelta(rawVal)
                        val flowBuilder = StringBuilder(rawVal)
                        while (braceBalance > 0 && idx < rawLines.size) {
                            val nextFm = rawLines[idx]
                            val nextTrimmed = nextFm.trim()
                            if (activeFmQuote == null && (nextTrimmed == "---" || nextTrimmed == "...")) break
                            idx++
                            val nextClean = GanttParser.stripYamlCommentWithQuoteState(nextFm, activeFmQuote)
                            activeFmQuote = nextClean.second
                            val nextPart = nextClean.first.trim()
                            if (nextPart.isNotEmpty()) {
                                flowBuilder.append(' ').append(nextPart)
                                braceBalance += countUnquotedBraceDelta(nextPart)
                            }
                        }
                        rawVal = flowBuilder.toString().trim()
                    }
                    val path = sectionStack.map { it.second }
                    val inArchConfig = path.isEmpty() ||
                        path == listOf("architecture") ||
                        path == listOf("config", "architecture")
                    if (rawVal.startsWith("{") && rawVal.endsWith("}")) {
                        if (key == "architecture" && (path.isEmpty() || path == listOf("config"))) {
                            parseInitDirective(rawVal, config) { t ->
                                if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeText(t)
                            }
                        } else if (key == "config" && path.isEmpty()) {
                            parseInitDirective(rawVal, config) { t ->
                                if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeText(t)
                            }
                        }
                        continue
                    }
                    val value = unquote(rawVal)
                    if (path.isEmpty() && key == "title" && value.isNotEmpty()) {
                        frontmatterTitle = normalizeText(value)
                    } else if (inArchConfig) {
                        applyConfigEntry(config, key, value) { t ->
                            if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeText(t)
                        }
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        for (initDirective in initDirectives) {
            parseInitDirective(initDirective, config) { t ->
                if (t.isNotEmpty()) frontmatterTitle = normalizeText(t)
            }
        }

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val registeredIds = LinkedHashMap<String, RegisteredKind>()
        val parentGroupById = HashMap<String, String?>()
        val groups = mutableListOf<ArchitectureGroup>()
        val services = mutableListOf<ArchitectureService>()
        val junctions = mutableListOf<ArchitectureJunction>()
        val edges = mutableListOf<ArchitectureEdge>()
        val alignments = mutableListOf<ArchitectureAlignment>()

        for (rawLine in bodyLines) {
            val rawTrimmed = rawLine.trim()
            if (inAccDescrBlock) {
                val closeIdx = rawTrimmed.indexOf('}')
                if (closeIdx >= 0) {
                    val beforeClose = rawTrimmed.substring(0, closeIdx).trim()
                    if (beforeClose.isNotEmpty()) accDescrLines += beforeClose
                    accDescr = accDescrLines.joinToString("\n").trim()
                    accDescrLines.clear()
                    inAccDescrBlock = false
                } else if (rawTrimmed.isNotEmpty()) {
                    accDescrLines += rawTrimmed
                }
                continue
            }

            val accBlockMatch = Regex("""^accDescr\s*\{(.*)$""", RegexOption.IGNORE_CASE).find(rawTrimmed)
            if (accBlockMatch != null) {
                headerSeen = true
                val afterOpen = accBlockMatch.groupValues[1]
                val closeIdx = afterOpen.indexOf('}')
                if (closeIdx >= 0) {
                    accDescr = normalizeText(afterOpen.substring(0, closeIdx).trim())
                } else {
                    inAccDescrBlock = true
                    accDescrLines.clear()
                    val firstLine = afterOpen.trim()
                    if (firstLine.isNotEmpty()) accDescrLines += firstLine
                }
                continue
            }

            val commentFree = stripLineComment(rawLine).trim()
            if (commentFree.isEmpty()) continue

            if (!headerSeen) {
                val headerMatch = Regex(
                    """^architecture(?:-beta)?(?:\s+title(?:\s+|:)\s*(.*)|\s*)?$""",
                    RegexOption.IGNORE_CASE,
                ).find(commentFree)
                if (headerMatch != null) {
                    headerSeen = true
                    val inlineTitle = headerMatch.groupValues[1].trim()
                    if (inlineTitle.isNotEmpty()) {
                        chartTitle = normalizeText(inlineTitle)
                    }
                    continue
                }
                if (commentFree.lowercase().startsWith("architecture")) {
                    throw IllegalArgumentException("Unexpected tokens on architecture header line: $commentFree")
                }
                headerSeen = true
            }

            val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (titleMatch != null) {
                val after = titleMatch.groupValues[1].trim()
                if (after.isNotEmpty()) chartTitle = normalizeText(after)
                continue
            }
            val accTitleMatch = Regex("""^accTitle\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (accTitleMatch != null) {
                accTitle = normalizeText(accTitleMatch.groupValues[1].trim())
                continue
            }
            val accDescrMatch = Regex("""^accDescr\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (accDescrMatch != null) {
                accDescr = normalizeText(accDescrMatch.groupValues[1].trim())
                continue
            }

            for (rawStmt in splitSemicolonOutsideQuotesAndBrackets(commentFree)) {
                val stmt = rawStmt.trim()
                if (stmt.isEmpty()) continue

                // 1. `group <id>(<icon>)?([<title>])? (in <parent>)?`
                if (Regex("""^group\s+""", RegexOption.IGNORE_CASE).containsMatchIn(stmt)) {
                    val group = parseGroupStatement(stmt, registeredIds)
                    registeredIds[group.id] = RegisteredKind.Group
                    parentGroupById[group.id] = group.parentId
                    groups += group
                    continue
                }

                // 2. `service <id>(<icon>|"<iconText>")?([<title>])? (in <parent>)?`
                if (Regex("""^service\s+""", RegexOption.IGNORE_CASE).containsMatchIn(stmt)) {
                    val service = parseServiceStatement(stmt, registeredIds)
                    registeredIds[service.id] = RegisteredKind.Node
                    parentGroupById[service.id] = service.parentId
                    services += service
                    continue
                }

                // 3. `junction <id> (in <parent>)?`
                if (Regex("""^junction\s+""", RegexOption.IGNORE_CASE).containsMatchIn(stmt)) {
                    val junction = parseJunctionStatement(stmt, registeredIds)
                    registeredIds[junction.id] = RegisteredKind.Node
                    parentGroupById[junction.id] = junction.parentId
                    junctions += junction
                    continue
                }

                // 4. `align <row|column> <id1> <id2> ...`
                if (Regex("""^align\s+""", RegexOption.IGNORE_CASE).containsMatchIn(stmt)) {
                    alignments += parseAlignStatement(stmt, registeredIds)
                    continue
                }

                // 5. Edge statement
                edges += parseEdgeStatement(stmt, registeredIds, parentGroupById)
            }
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in architecture diagram")
        }

        val diagram = ArchitectureDiagram(
            groups = groups,
            services = services,
            junctions = junctions,
            edges = edges,
            alignments = alignments,
            config = config.toImmutable(),
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
        validateAlignmentsAndEdges(diagram)
        return diagram
    }

    internal fun validateAlignmentsAndEdges(diagram: ArchitectureDiagram) {
        val rowOrder = HashSet<Pair<String, String>>()
        val colOrder = HashSet<Pair<String, String>>()
        val sameRowPairs = HashSet<Pair<String, String>>()
        val sameColPairs = HashSet<Pair<String, String>>()

        for (align in diagram.alignments) {
            for (i in 0 until align.members.size) {
                for (j in i + 1 until align.members.size) {
                    val u = align.members[i]
                    val v = align.members[j]
                    if (align.direction == ArchitectureAlignDir.Row) {
                        if ((v to u) in rowOrder) {
                            throw IllegalArgumentException("Contradictory 'align row' ordering between [$u] and [$v]")
                        }
                        if ((u to v) in sameColPairs || (v to u) in sameColPairs) {
                            throw IllegalArgumentException("Contradictory 'align row' and 'align column' on [$u] and [$v]")
                        }
                        rowOrder += (u to v)
                        sameRowPairs += (u to v)
                    } else {
                        if ((v to u) in colOrder) {
                            throw IllegalArgumentException("Contradictory 'align column' ordering between [$u] and [$v]")
                        }
                        if ((u to v) in sameRowPairs || (v to u) in sameRowPairs) {
                            throw IllegalArgumentException("Contradictory 'align row' and 'align column' on [$u] and [$v]")
                        }
                        colOrder += (u to v)
                        sameColPairs += (u to v)
                    }
                }
            }
        }

        for (edge in diagram.edges) {
            if (edge.lhsDir == edge.rhsDir) continue
            val lhsX = edge.lhsDir == ArchitecturePortDir.L || edge.lhsDir == ArchitecturePortDir.R
            val rhsX = edge.rhsDir == ArchitecturePortDir.L || edge.rhsDir == ArchitecturePortDir.R
            val dx = when {
                lhsX && !rhsX -> if (edge.lhsDir == ArchitecturePortDir.L) -1 else 1
                lhsX && rhsX -> if (edge.lhsDir == ArchitecturePortDir.L) -1 else 1
                !lhsX && rhsX -> if (edge.rhsDir == ArchitecturePortDir.L) 1 else -1
                else -> 0
            }
            val dy = when {
                lhsX && !rhsX -> if (edge.rhsDir == ArchitecturePortDir.T) 1 else -1
                lhsX && rhsX -> 0
                !lhsX && rhsX -> if (edge.lhsDir == ArchitecturePortDir.T) -1 else 1
                else -> if (edge.lhsDir == ArchitecturePortDir.T) -1 else 1
            }
            if (dx > 0 && (edge.rhsId to edge.lhsId) in rowOrder) {
                throw IllegalArgumentException(
                    "Edge ${edge.lhsId}:${edge.lhsDir} -- ${edge.rhsDir}:${edge.rhsId} contradicts declared 'align row' order",
                )
            }
            if (dx < 0 && (edge.lhsId to edge.rhsId) in rowOrder) {
                throw IllegalArgumentException(
                    "Edge ${edge.lhsId}:${edge.lhsDir} -- ${edge.rhsDir}:${edge.rhsId} contradicts declared 'align row' order",
                )
            }
            if (dy > 0 && (edge.rhsId to edge.lhsId) in colOrder) {
                throw IllegalArgumentException(
                    "Edge ${edge.lhsId}:${edge.lhsDir} -- ${edge.rhsDir}:${edge.rhsId} contradicts declared 'align column' order",
                )
            }
            if (dy < 0 && (edge.lhsId to edge.rhsId) in colOrder) {
                throw IllegalArgumentException(
                    "Edge ${edge.lhsId}:${edge.lhsDir} -- ${edge.rhsDir}:${edge.rhsId} contradicts declared 'align column' order",
                )
            }
        }
    }

    private fun parseGroupStatement(
        stmt: String,
        registeredIds: Map<String, RegisteredKind>,
    ): ArchitectureGroup {
        val rest = stmt.replaceFirst(Regex("""^group\s+""", RegexOption.IGNORE_CASE), "").trim()
        val (headPart, parentId) = extractTrailingInParent(rest)
        val (id, icon, iconText, title) = parseEntitySignature(headPart, allowIconText = false, stmt = stmt)
        if (iconText != null) {
            throw IllegalArgumentException("Group '$id' does not support quoted iconText in: $stmt")
        }
        validateNewRegistration(id, "group", parentId, registeredIds)
        return ArchitectureGroup(
            id = id,
            icon = icon,
            title = title,
            parentId = parentId,
        )
    }

    private fun parseServiceStatement(
        stmt: String,
        registeredIds: Map<String, RegisteredKind>,
    ): ArchitectureService {
        val rest = stmt.replaceFirst(Regex("""^service\s+""", RegexOption.IGNORE_CASE), "").trim()
        val (headPart, parentId) = extractTrailingInParent(rest)
        val (id, icon, iconText, title) = parseEntitySignature(headPart, allowIconText = true, stmt = stmt)
        validateNewRegistration(id, "service", parentId, registeredIds)
        return ArchitectureService(
            id = id,
            icon = icon,
            iconText = iconText,
            title = title,
            parentId = parentId,
        )
    }

    private fun parseJunctionStatement(
        stmt: String,
        registeredIds: Map<String, RegisteredKind>,
    ): ArchitectureJunction {
        val rest = stmt.replaceFirst(Regex("""^junction\s+""", RegexOption.IGNORE_CASE), "").trim()
        val (headPart, parentId) = extractTrailingInParent(rest)
        val id = headPart.trim()
        if (!Regex("""^[A-Za-z0-9_][A-Za-z0-9_-]*$""").matches(id)) {
            throw IllegalArgumentException("Invalid junction id '$id' in architecture diagram: $stmt")
        }
        validateNewRegistration(id, "junction", parentId, registeredIds)
        return ArchitectureJunction(
            id = id,
            parentId = parentId,
        )
    }

    private fun validateNewRegistration(
        id: String,
        entityType: String,
        parentId: String?,
        registeredIds: Map<String, RegisteredKind>,
    ) {
        val existing = registeredIds[id]
        if (existing != null) {
            throw IllegalArgumentException("The $entityType id [$id] is already in use")
        }
        if (parentId != null) {
            if (parentId == id) {
                throw IllegalArgumentException("The $entityType [$id] cannot be placed within itself")
            }
            val parentKind = registeredIds[parentId]
                ?: throw IllegalArgumentException(
                    "The $entityType [$id]'s parent [$parentId] does not exist. Declare the parent group before [$id].",
                )
            if (parentKind != RegisteredKind.Group) {
                throw IllegalArgumentException("The $entityType [$id]'s parent [$parentId] is not a group")
            }
        }
    }

    private data class EntitySig(
        val id: String,
        val icon: String?,
        val iconText: String?,
        val title: String?,
    )

    private fun parseEntitySignature(
        headPart: String,
        allowIconText: Boolean,
        stmt: String,
    ): EntitySig {
        var i = 0
        val s = headPart.trim()
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) {
            i++
        }
        val id = s.substring(0, i).trim()
        if (id.isEmpty()) {
            throw IllegalArgumentException("Missing identifier in architecture declaration: $stmt")
        }
        while (i < s.length && s[i].isWhitespace()) i++

        var icon: String? = null
        var iconText: String? = null
        if (i < s.length && s[i] == '(') {
            val closeParen = findClosingCharOutsideQuotes(s, i, ')')
            if (closeParen < 0) {
                throw IllegalArgumentException("Unclosed '(' in icon specification: $stmt")
            }
            val rawIcon = s.substring(i + 1, closeParen).trim()
            if (rawIcon.isEmpty()) {
                throw IllegalArgumentException("Empty icon specification '()' in: $stmt")
            }
            val isQuotedIconText = rawIcon.length >= 2 &&
                ((rawIcon.startsWith("\"") && rawIcon.endsWith("\"")) || (rawIcon.startsWith("'") && rawIcon.endsWith("'")))
            if (isQuotedIconText) {
                if (!allowIconText) {
                    throw IllegalArgumentException("Group '$id' does not support quoted iconText in: $stmt")
                }
                iconText = normalizeText(rawIcon)
            } else {
                icon = rawIcon
            }
            i = closeParen + 1
            while (i < s.length && s[i].isWhitespace()) i++
        } else if (allowIconText && i < s.length && (s[i] == '"' || s[i] == '\'')) {
            val quote = s[i]
            val sb = StringBuilder()
            i++
            var closed = false
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) {
                    closed = true
                    i++
                    break
                }
                sb.append(c)
                i++
            }
            if (!closed) {
                throw IllegalArgumentException("Unclosed quote in service iconText: $stmt")
            }
            iconText = normalizeText(sb.toString())
            while (i < s.length && s[i].isWhitespace()) i++
        }

        var title: String? = null
        if (i < s.length && s[i] == '[') {
            val closeBracket = findClosingBracketOutsideQuotes(s, i)
            if (closeBracket < 0) {
                throw IllegalArgumentException("Unclosed '[' in title specification: $stmt")
            }
            val rawTitle = s.substring(i + 1, closeBracket).trim()
            title = normalizeText(rawTitle)
            i = closeBracket + 1
            while (i < s.length && s[i].isWhitespace()) i++
        }

        if (i < s.length) {
            throw IllegalArgumentException("Unexpected trailing tokens '${s.substring(i)}' in architecture declaration: $stmt")
        }

        return EntitySig(id = id, icon = icon, iconText = iconText, title = title)
    }

    private fun extractTrailingInParent(s: String): Pair<String, String?> {
        // Match trailing `\s+in\s+([A-Za-z0-9_][A-Za-z0-9_-]*)$` outside `[...]` and quotes.
        val closeBracket = findLastClosingBracketOutsideQuotes(s)
        val searchStart = if (closeBracket >= 0) closeBracket + 1 else 0
        val tail = s.substring(searchStart)
        val m = Regex("""^(.*?)\s+in\s+([A-Za-z0-9_][A-Za-z0-9_-]*)$""", RegexOption.IGNORE_CASE).find(tail)
            ?: return s.trim() to null
        val head = (s.substring(0, searchStart) + m.groupValues[1]).trim()
        val parent = m.groupValues[2].trim()
        return head to parent
    }

    private fun parseAlignStatement(
        stmt: String,
        registeredIds: Map<String, RegisteredKind>,
    ): ArchitectureAlignment {
        val m = Regex("""^align\s+(row|column)\s+(.+)$""", RegexOption.IGNORE_CASE).find(stmt)
            ?: throw IllegalArgumentException("Expected 'align <row|column> <id1> <id2> ...' in architecture diagram: $stmt")
        val dir = when (m.groupValues[1].lowercase()) {
            "row" -> ArchitectureAlignDir.Row
            "column" -> ArchitectureAlignDir.Column
            else -> throw IllegalArgumentException("Invalid align direction '${m.groupValues[1]}'")
        }
        val members = m.groupValues[2].trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (members.size < 2) {
            throw IllegalArgumentException("An align directive requires at least two members; got ${members.size} in: $stmt")
        }
        val seen = HashSet<String>()
        for (id in members) {
            if (registeredIds[id] != RegisteredKind.Node) {
                throw IllegalArgumentException("align ${m.groupValues[1]} references [$id], which is not a service or junction")
            }
            if (!seen.add(id)) {
                throw IllegalArgumentException("align ${m.groupValues[1]} lists [$id] more than once")
            }
        }
        return ArchitectureAlignment(direction = dir, members = members)
    }

    private fun parseEdgeStatement(
        stmt: String,
        registeredIds: Map<String, RegisteredKind>,
        parentGroupById: Map<String, String?>,
    ): ArchitectureEdge {
        val edgeRegex = Regex(
            """^([A-Za-z0-9_][A-Za-z0-9_-]*)\s*(\{group\})?\s*:\s*([LRTBlrtb])\s*([<>])?\s*(?:--|-+\s*\[((?:\\.|[^\]])*)\]\s*-+|\[((?:\\.|[^\]])*)\])\s*([<>])?\s*([LRTBlrtb])\s*:\s*([A-Za-z0-9_][A-Za-z0-9_-]*)\s*(\{group\})?$""",
        )
        val m = edgeRegex.find(stmt)
            ?: throw IllegalArgumentException("Invalid architecture edge syntax: $stmt")

        val lhsId = m.groupValues[1]
        val lhsGroup = m.groupValues[2].isNotEmpty()
        val lhsDir = parsePortDir(m.groupValues[3])
        val lhsArrowTok = m.groupValues[4]
        val rawTitle = m.groupValues[5].ifEmpty { m.groupValues[6] }
        val hasTitleGroup = m.groups[5] != null || m.groups[6] != null
        val rhsArrowTok = m.groupValues[7]
        val rhsDir = parsePortDir(m.groupValues[8])
        val rhsId = m.groupValues[9]
        val rhsGroup = m.groupValues[10].isNotEmpty()

        if (lhsId !in registeredIds) {
            throw IllegalArgumentException("The left-hand id [$lhsId] does not yet exist in: $stmt")
        }
        if (rhsId !in registeredIds) {
            throw IllegalArgumentException("The right-hand id [$rhsId] does not yet exist in: $stmt")
        }

        val lhsParent = parentGroupById[lhsId]
        val rhsParent = parentGroupById[rhsId]
        if (lhsGroup && lhsParent != null && rhsParent != null && lhsParent == rhsParent) {
            throw IllegalArgumentException(
                "The left-hand id [$lhsId] has {group}, but both endpoints belong to the same group [$lhsParent]",
            )
        }
        if (rhsGroup && lhsParent != null && rhsParent != null && lhsParent == rhsParent) {
            throw IllegalArgumentException(
                "The right-hand id [$rhsId] has {group}, but both endpoints belong to the same group [$rhsParent]",
            )
        }

        val title = if (hasTitleGroup) normalizeText(rawTitle) else null

        return ArchitectureEdge(
            lhsId = lhsId,
            lhsDir = lhsDir,
            lhsInto = lhsArrowTok.isNotEmpty(),
            lhsGroup = lhsGroup,
            rhsId = rhsId,
            rhsDir = rhsDir,
            rhsInto = rhsArrowTok.isNotEmpty(),
            rhsGroup = rhsGroup,
            title = title?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parsePortDir(raw: String): ArchitecturePortDir =
        when (raw.uppercase()) {
            "L" -> ArchitecturePortDir.L
            "R" -> ArchitecturePortDir.R
            "T" -> ArchitecturePortDir.T
            "B" -> ArchitecturePortDir.B
            else -> throw IllegalArgumentException("Invalid architecture port direction '$raw' (expected L, R, T, or B)")
        }

    private fun isQuoteOpeningContext(s: String, index: Int): Boolean {
        var j = index - 1
        while (j >= 0 && (s[j] == ' ' || s[j] == '\t')) {
            j--
        }
        if (j < 0 || s[j] == '\n' || s[j] == '\r' || s[j] == ':' || s[j] == '[' || s[j] == '(' || s[j] == '{' || s[j] == ',' || s[j] == ';') {
            return true
        }
        // Also allow quote opening for `service <id>"iconText"` or `service <id>'iconText'` or `title '...'`
        val prefix = s.substring(0, j + 1).trimStart()
        if (Regex("""^(?:service\s+[A-Za-z0-9_][A-Za-z0-9_-]*|(?:architecture(?:-beta)?\s+)?title)$""", RegexOption.IGNORE_CASE).matches(prefix)) {
            return true
        }
        return false
    }

    private fun findClosingBracketOutsideQuotes(s: String, openIdx: Int): Int =
        findClosingCharOutsideQuotes(s, openIdx, ']')

    private fun findClosingCharOutsideQuotes(s: String, openIdx: Int, target: Char): Int {
        var activeQuote: Char? = null
        var i = openIdx + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && (c == s[openIdx + 1] || isQuoteOpeningContext(s, i))))) {
                activeQuote = c
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                i++
                continue
            }
            if (activeQuote == null && c == target) return i
            i++
        }
        return -1
    }

    private fun findLastClosingBracketOutsideQuotes(s: String): Int {
        var activeQuote: Char? = null
        var lastIdx = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(s, i)))) {
                activeQuote = c
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                i++
                continue
            }
            if (activeQuote == null && c == ']') lastIdx = i
            i++
        }
        return lastIdx
    }

    private fun splitSemicolonOutsideQuotesAndBrackets(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var activeQuote: Char? = null
        var bracketDepth = 0
        var parenDepth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                sb.append(s[i]).append(s[i + 1])
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(s, i)))) {
                activeQuote = c
                sb.append(c)
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                sb.append(c)
                i++
                continue
            }
            if (activeQuote == null) {
                when (c) {
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    ';' -> if (bracketDepth == 0 && parenDepth == 0) {
                        out += sb.toString()
                        sb.setLength(0)
                        i++
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        out += sb.toString()
        return out
    }

    private fun applyConfigEntry(
        config: MutableConfig,
        key: String,
        value: String,
        onTitle: (String) -> Unit,
    ) {
        when (key) {
            "title" -> onTitle(value)
            "padding" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid architecture padding '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Architecture padding must be >= 0, got $value")
                config.padding = v
            }
            "iconsize" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid architecture iconSize '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Architecture iconSize must be > 0, got $value")
                config.iconSize = v
            }
            "fontsize" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid architecture fontSize '$value'")
                if (!v.isFinite() || v <= 0f) throw IllegalArgumentException("Architecture fontSize must be > 0, got $value")
                config.fontSize = v
            }
            "nodeseparation", "nodespacing" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid architecture nodeSeparation '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("Architecture nodeSeparation must be >= 0, got $value")
                config.nodeSpacing = v
            }
        }
    }

    private fun parseInitDirective(
        initDirective: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        for (blockMatch in Regex(
            """['"]?architecture['"]?\s*:\s*\{([^{}]*)\}""",
            RegexOption.IGNORE_CASE,
        ).findAll(initDirective)) {
            parseJsonKeyValuePairs(blockMatch.groupValues[1], config, onTitle)
        }
        val topLevel = Regex("""['"]?[A-Za-z0-9_-]+['"]?\s*:\s*\{[^{}]*\}""").replace(initDirective, "")
        parseJsonKeyValuePairs(topLevel, config, onTitle)
    }

    private fun parseJsonKeyValuePairs(
        segment: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        val pairRegex = Regex(
            """['"]?([A-Za-z0-9_]+)['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([A-Za-z0-9_.+#-]+))""",
        )
        for (m in pairRegex.findAll(segment)) {
            val key = m.groupValues[1].lowercase()
            val rawVal = when {
                m.groups[2] != null -> m.groupValues[2]
                m.groups[3] != null -> m.groupValues[3]
                else -> m.groupValues[4]
            }.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\").trim()
            applyConfigEntry(config, key, rawVal, onTitle)
        }
    }

    private fun countUnquotedBraceDelta(s: String): Int {
        var delta = 0
        var activeQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || c == '\'')) {
                activeQuote = c
            } else if (activeQuote == c) {
                activeQuote = null
            } else if (activeQuote == null) {
                if (c == '{') delta++
                else if (c == '}') delta--
            }
            i++
        }
        return delta
    }

    private fun stripAndExtractActiveInitDirectives(source: String): Pair<String, List<String>> {
        val stripped = StringBuilder(source.length)
        val results = mutableListOf<String>()
        var i = 0
        var activeQuote: Char? = null
        while (i < source.length) {
            val c = source[i]
            if (c == '\\' && activeQuote != null && i + 1 < source.length) {
                stripped.append(source[i]).append(source[i + 1])
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(source, i)))) {
                activeQuote = c
                stripped.append(c)
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                stripped.append(c)
                i++
                continue
            }
            if (activeQuote == null) {
                if (source.startsWith("%%{", i)) {
                    val endIdx = source.indexOf("}%%", i + 3)
                    if (endIdx >= 0) {
                        results += source.substring(i, endIdx + 3)
                        i = endIdx + 3
                        continue
                    }
                }
                if (c == '#' || c == '%') {
                    val nl = source.indexOf('\n', i)
                    if (nl < 0) {
                        stripped.append(source, i, source.length)
                        break
                    }
                    stripped.append(source, i, nl + 1)
                    i = nl + 1
                    continue
                }
            }
            stripped.append(c)
            i++
        }
        return stripped.toString() to results
    }

    private fun stripLineComment(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#") || trimmed.startsWith("%%")) return ""
        var activeQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && activeQuote != null && i + 1 < line.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(line, i)))) {
                activeQuote = c
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                i++
                continue
            }
            if (activeQuote == null && line.startsWith("%%", i)) {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .trim()
        }
        return t
    }

    private fun normalizeText(s: String): String {
        val u = unquote(s)
        val brReplaced = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(u, " ")
        return brReplaced.trim()
    }
}
