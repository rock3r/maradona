package dev.maradona.parse

import dev.maradona.model.ZenUmlControlBranch
import dev.maradona.model.ZenUmlControlKind
import dev.maradona.model.ZenUmlDiagram
import dev.maradona.model.ZenUmlMessageKind
import dev.maradona.model.ZenUmlParticipant
import dev.maradona.model.ZenUmlParticipantGroup
import dev.maradona.model.ZenUmlParticipantStereotype
import dev.maradona.model.ZenUmlStatement

/**
 * Hand-written parser for Mermaid `zenuml` sequence diagrams.
 *
 * Supports:
 * - `zenuml` header, YAML frontmatter, `%%{init}%%` configuration (`title`, `mirrorParticipants`),
 *   `title`, `accTitle`, and `accDescr`
 * - Participant declarations (`@Actor`, `@Boundary`, `@Control`, `@Entity`, `@Database`,
 *   `@Collections`, `@Queue`, `@Starter(...)`, `A as "Alias"`, and `group Name { ... }`)
 * - Synchronous calls (`A->B.method(args) { ... }`, `B.method(args) { ... }`, `ret = B.method(args)`),
 *   self-calls (`internalMethod(args)`), constructor calls (`p = new Service(args)`),
 *   asynchronous messages (`A->B: message`), and return statements (`return value`, `@return A->B: value`)
 * - Control blocks (`if`/`else if`/`else`, `while`/`forEach`/`for`/`loop`, `opt`, `par`, `try`/`catch`/`finally`)
 *   and `// comment` notes
 */
object ZenUmlParser {

    private data class CallFrame(
        val callerId: String,
        val calleeId: String,
    )

    private class MutableParticipant(
        val id: String,
        var label: String,
        var stereotype: ZenUmlParticipantStereotype = ZenUmlParticipantStereotype.DEFAULT,
        var rawAnnotation: String? = null,
        var groupName: String? = null,
        var explicitOrder: Boolean = false,
    )

    fun parse(source: String): ZenUmlDiagram {
        var frontmatterTitle: String? = null
        var mirrorParticipants = false

        val (strippedSource, initDirectives) = stripAndExtractActiveInitDirectives(source)
        val rawLines = strippedSource.lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripMermaidComment(rawLines[idx]).trim().isEmpty()) {
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
                    val rawVal = fmLine.substring(colon + 1).trim()
                    val value = unquote(rawVal)
                    if (key == "title" && value.isNotEmpty()) {
                        frontmatterTitle = normalizeText(value)
                    } else if (key == "mirrorparticipants") {
                        mirrorParticipants = value.equals("true", ignoreCase = true)
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        for (initDirective in initDirectives) {
            val titleMatch = Regex("""['"]?title['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(initDirective)
            if (titleMatch != null) {
                frontmatterTitle = normalizeText(titleMatch.groupValues[1])
            }
            val mirrorMatch = Regex("""['"]?mirrorParticipants['"]?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE).find(initDirective)
            if (mirrorMatch != null) {
                mirrorParticipants = mirrorMatch.groupValues[1].equals("true", ignoreCase = true)
            }
        }

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val contentLines = mutableListOf<String>()
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

            val commentFree = stripMermaidComment(rawLine)
            val trimmed = commentFree.trim()
            if (trimmed.isEmpty()) continue

            if (!headerSeen) {
                val headerMatch = Regex(
                    """^zenuml(?:\s+title(?:\s+|:)\s*(.*)|\s*)?$""",
                    RegexOption.IGNORE_CASE,
                ).find(trimmed)
                if (headerMatch != null) {
                    headerSeen = true
                    val inlineTitle = headerMatch.groupValues[1].trim()
                    if (inlineTitle.isNotEmpty()) {
                        chartTitle = normalizeText(inlineTitle)
                    }
                    continue
                }
                throw IllegalArgumentException("Expected zenuml header, got: $trimmed")
            }

            if (!trimmed.startsWith("//")) {
                val accBlockMatch = Regex("""^accDescr\s*\{(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
                if (accBlockMatch != null) {
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

                val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
                if (titleMatch != null && !Regex("""^title\s*(?:->|[.(])""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                    val after = titleMatch.groupValues[1].trim()
                    if (after.isNotEmpty()) chartTitle = normalizeText(after)
                    continue
                }
                val accTitleMatch = Regex("""^accTitle\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
                if (accTitleMatch != null) {
                    accTitle = normalizeText(accTitleMatch.groupValues[1].trim())
                    continue
                }
                val accDescrMatch = Regex("""^accDescr\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
                if (accDescrMatch != null) {
                    accDescr = normalizeText(accDescrMatch.groupValues[1].trim())
                    continue
                }
            }

            contentLines += trimmed
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in zenuml diagram")
        }
        if (!headerSeen) {
            throw IllegalArgumentException("Expected zenuml header")
        }

        val tokens = tokenizeZenUmlLines(contentLines)
        val participantsById = LinkedHashMap<String, MutableParticipant>()
        val groups = LinkedHashMap<String, MutableList<String>>()
        var explicitStarterId: String? = null

        fun ensureParticipant(
            rawId: String,
            label: String? = null,
            stereotype: ZenUmlParticipantStereotype? = null,
            rawAnnotation: String? = null,
            groupName: String? = null,
            explicit: Boolean = false,
        ): String {
            val id = unquote(rawId.trim())
            val existing = participantsById[id]
            if (existing != null) {
                if (label != null) existing.label = normalizeText(label)
                if (stereotype != null && stereotype != ZenUmlParticipantStereotype.DEFAULT) {
                    existing.stereotype = stereotype
                }
                if (rawAnnotation != null) existing.rawAnnotation = rawAnnotation
                if (groupName != null && existing.groupName == null) {
                    existing.groupName = groupName
                    groups.getOrPut(groupName) { mutableListOf() }.let { list ->
                        if (id !in list) list += id
                    }
                }
                if (explicit) existing.explicitOrder = true
            } else {
                val p = MutableParticipant(
                    id = id,
                    label = normalizeText(label ?: id),
                    stereotype = stereotype ?: ZenUmlParticipantStereotype.DEFAULT,
                    rawAnnotation = rawAnnotation,
                    groupName = groupName,
                    explicitOrder = explicit,
                )
                participantsById[id] = p
                if (groupName != null) {
                    groups.getOrPut(groupName) { mutableListOf() }.add(id)
                }
            }
            return id
        }

        var cursor = 0
        var currentGroup: String? = null

        fun resolveDefaultCaller(targetId: String?): String {
            explicitStarterId?.let { return it }
            val firstExplicit = participantsById.values.firstOrNull { it.explicitOrder }
            if (firstExplicit != null && firstExplicit.id != targetId) {
                return firstExplicit.id
            }
            // Insert or reuse a synthetic starter participant at the front of the lifeline order
            val starterId = "_STARTER_"
            if (starterId !in participantsById) {
                val existingEntries = participantsById.entries.map { it.key to it.value }
                participantsById.clear()
                participantsById[starterId] = MutableParticipant(
                    id = starterId,
                    label = "Starter",
                    stereotype = ZenUmlParticipantStereotype.ACTOR,
                    explicitOrder = true,
                )
                for ((k, v) in existingEntries) {
                    participantsById[k] = v
                }
            }
            explicitStarterId = starterId
            return starterId
        }

        fun parseBlock(
            callStack: MutableList<CallFrame>,
            stopOnCloseBrace: Boolean,
            initialComment: String? = null,
        ): List<ZenUmlStatement> {
            val statements = mutableListOf<ZenUmlStatement>()
            var pendingComment: String? = initialComment
            var pendingReturnAnnotation = false

            fun flushTrailingComment() {
                val trailing = pendingComment ?: return
                pendingComment = null
                statements += ZenUmlStatement.NoteComment(
                    text = trailing,
                    onParticipant = callStack.lastOrNull()?.calleeId ?: participantsById.keys.firstOrNull(),
                )
            }

            while (cursor < tokens.size) {
                val tok = tokens[cursor]
                if (tok == "}") {
                    if (stopOnCloseBrace) {
                        flushTrailingComment()
                        cursor++
                        return statements
                    } else {
                        throw IllegalArgumentException("Unmatched '}' in zenuml diagram")
                    }
                }
                cursor++

                if (tok.startsWith("//")) {
                    val commentText = tok.removePrefix("//").trim()
                    if (commentText.isNotEmpty()) {
                        pendingComment = if (pendingComment == null) commentText else "$pendingComment\n$commentText"
                    }
                    continue
                }

                // Standalone `@return` / `@reply` annotation before `B->A: msg`
                if (tok.equals("@return", ignoreCase = true) || tok.equals("@reply", ignoreCase = true)) {
                    pendingReturnAnnotation = true
                    continue
                }

                // Group block: `group Name {`
                val groupMatch = Regex("""^group\s+(.+?)\s*\{$""", RegexOption.IGNORE_CASE).find(tok)
                if (groupMatch != null) {
                    val gName = normalizeText(groupMatch.groupValues[1].trim())
                    val prevGroup = currentGroup
                    currentGroup = gName
                    groups.getOrPut(gName) { mutableListOf() }
                    val innerStmts = parseBlock(callStack, stopOnCloseBrace = true)
                    statements += innerStmts
                    currentGroup = prevGroup
                    continue
                }

                // `@Starter(Client)`
                val starterMatch = Regex("""^@Starter\s*\(\s*([^)]+?)\s*\)\s*;?$""", RegexOption.IGNORE_CASE).find(tok)
                if (starterMatch != null) {
                    val stId = ensureParticipant(
                        rawId = starterMatch.groupValues[1],
                        stereotype = ZenUmlParticipantStereotype.ACTOR,
                        rawAnnotation = "Starter",
                        groupName = currentGroup,
                        explicit = true,
                    )
                    explicitStarterId = stId
                    pendingComment = null
                    continue
                }

                // `@return A->B: text` or `@reply A->B: text`
                val atReturnMatch = Regex(
                    """^@(?:return|reply)\s+("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)\s*->\s*("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)\s*:\s*(.*?)\s*;?$""",
                    RegexOption.IGNORE_CASE,
                ).find(tok)
                if (atReturnMatch != null) {
                    val fromId = ensureParticipant(atReturnMatch.groupValues[1])
                    val toId = ensureParticipant(atReturnMatch.groupValues[2])
                    val retVal = normalizeText(atReturnMatch.groupValues[3])
                    statements += ZenUmlStatement.Message(
                        from = fromId,
                        to = toId,
                        text = retVal,
                        kind = ZenUmlMessageKind.RETURN,
                        comment = pendingComment,
                    )
                    pendingComment = null
                    pendingReturnAnnotation = false
                    continue
                }

                // Stereotyped participant declaration: `@Actor A`, `@Database Db as "Primary DB"`
                val stereoMatch = Regex(
                    """^@([A-Za-z0-9_]+)\s+("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)(?:\s+as\s+("[^"]+"|'[^']+'|[^;]+))?\s*;?$""",
                ).find(tok)
                if (stereoMatch != null) {
                    val rawStereo = stereoMatch.groupValues[1]
                    val rawId = stereoMatch.groupValues[2]
                    val rawAlias = stereoMatch.groupValues[3].trim().takeIf { it.isNotEmpty() }
                    val st = parseStereotype(rawStereo)
                    ensureParticipant(
                        rawId = rawId,
                        label = rawAlias ?: rawId,
                        stereotype = st,
                        rawAnnotation = rawStereo,
                        groupName = currentGroup,
                        explicit = true,
                    )
                    pendingComment = null
                    continue
                }

                // Control blocks: `if (...) {`, `while (...) {`, `for (...) {`, `forEach (...) {`, `loop (...) {`, `opt {`, `par {`, `try {`
                val ctrlHeader = matchControlHeader(tok)
                if (ctrlHeader != null) {
                    val (ctrlKind, keyword, cond) = ctrlHeader
                    val blockComment = pendingComment
                    pendingComment = null
                    val branches = mutableListOf<ZenUmlControlBranch>()
                    val firstBody = parseBlock(callStack, stopOnCloseBrace = true)
                    branches += ZenUmlControlBranch(keyword = keyword, condition = cond, statements = firstBody)

                    if (ctrlKind == ZenUmlControlKind.ALT) {
                        while (cursor < tokens.size) {
                            var lookIdx = cursor
                            val branchComments = mutableListOf<String>()
                            while (lookIdx < tokens.size && tokens[lookIdx].startsWith("//")) {
                                val cText = tokens[lookIdx].removePrefix("//").trim()
                                if (cText.isNotEmpty()) branchComments += cText
                                lookIdx++
                            }
                            if (lookIdx >= tokens.size) break
                            val nextTok = tokens[lookIdx]
                            val elseIfMatch = Regex("""^else\s+if\s*\((.*)\)\s*\{$""", RegexOption.IGNORE_CASE).find(nextTok)
                            val elseMatch = Regex("""^else(?:\s*\{|\s+\{)$""", RegexOption.IGNORE_CASE).find(nextTok)
                            val initialBranchComment = branchComments.takeIf { it.isNotEmpty() }?.joinToString("\n")
                            if (elseIfMatch != null) {
                                cursor = lookIdx + 1
                                val bBody = parseBlock(callStack, stopOnCloseBrace = true, initialComment = initialBranchComment)
                                branches += ZenUmlControlBranch(
                                    keyword = "else if",
                                    condition = elseIfMatch.groupValues[1].trim(),
                                    statements = bBody,
                                )
                            } else if (elseMatch != null) {
                                cursor = lookIdx + 1
                                val bBody = parseBlock(callStack, stopOnCloseBrace = true, initialComment = initialBranchComment)
                                branches += ZenUmlControlBranch(
                                    keyword = "else",
                                    condition = null,
                                    statements = bBody,
                                )
                                break
                            } else {
                                break
                            }
                        }
                    } else if (ctrlKind == ZenUmlControlKind.TRY) {
                        while (cursor < tokens.size) {
                            var lookIdx = cursor
                            val branchComments = mutableListOf<String>()
                            while (lookIdx < tokens.size && tokens[lookIdx].startsWith("//")) {
                                val cText = tokens[lookIdx].removePrefix("//").trim()
                                if (cText.isNotEmpty()) branchComments += cText
                                lookIdx++
                            }
                            if (lookIdx >= tokens.size) break
                            val nextTok = tokens[lookIdx]
                            val catchMatch = Regex("""^catch\s*(?:\((.*)\))?\s*\{$""", RegexOption.IGNORE_CASE).find(nextTok)
                            val finallyMatch = Regex("""^finally\s*\{$""", RegexOption.IGNORE_CASE).find(nextTok)
                            val initialBranchComment = branchComments.takeIf { it.isNotEmpty() }?.joinToString("\n")
                            if (catchMatch != null) {
                                cursor = lookIdx + 1
                                val bBody = parseBlock(callStack, stopOnCloseBrace = true, initialComment = initialBranchComment)
                                branches += ZenUmlControlBranch(
                                    keyword = "catch",
                                    condition = catchMatch.groupValues[1].trim().takeIf { it.isNotEmpty() },
                                    statements = bBody,
                                )
                            } else if (finallyMatch != null) {
                                cursor = lookIdx + 1
                                val bBody = parseBlock(callStack, stopOnCloseBrace = true, initialComment = initialBranchComment)
                                branches += ZenUmlControlBranch(
                                    keyword = "finally",
                                    condition = null,
                                    statements = bBody,
                                )
                                break
                            } else {
                                break
                            }
                        }
                    }

                    statements += ZenUmlStatement.ControlBlock(
                        kind = ctrlKind,
                        branches = branches,
                        comment = blockComment,
                    )
                    continue
                }

                // `return expr`
                val returnMatch = Regex("""^return(?:\s+(.*?))?\s*;?$""").find(tok)
                if (returnMatch != null && !tok.contains("->") && !tok.startsWith("return(")) {
                    val retVal = normalizeText(returnMatch.groupValues[1].trim())
                    val activeFrame = callStack.lastOrNull()
                    val fromParticipant = activeFrame?.calleeId ?: resolveDefaultCaller(null)
                    val toParticipant = activeFrame?.callerId ?: resolveDefaultCaller(fromParticipant)
                    statements += ZenUmlStatement.Message(
                        from = fromParticipant,
                        to = toParticipant,
                        text = retVal.ifEmpty { "return" },
                        kind = ZenUmlMessageKind.RETURN,
                        comment = pendingComment,
                    )
                    pendingComment = null
                    pendingReturnAnnotation = false
                    continue
                }

                // Participant alias declaration: `A as "Alias"` or `"Participant A" as A`
                val aliasMatch = Regex(
                    """^("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)\s+as\s+("[^"]+"|'[^']+'|[A-Za-z0-9_.\s-]+?)\s*;?$""",
                ).find(tok)
                if (aliasMatch != null && !tok.contains("(") && !tok.contains("->") && !tok.contains(":")) {
                    val rawFirst = aliasMatch.groupValues[1].trim()
                    val rawSecond = aliasMatch.groupValues[2].trim()
                    ensureParticipant(
                        rawId = rawFirst,
                        label = rawSecond,
                        groupName = currentGroup,
                        explicit = true,
                    )
                    pendingComment = null
                    continue
                }

                // Bare participant declaration inside a group or header: `A`
                val bareParticipantMatch = Regex("""^([A-Za-z_][A-Za-z0-9_-]*)\s*;?$""").find(tok)
                if (bareParticipantMatch != null) {
                    ensureParticipant(
                        rawId = bareParticipantMatch.groupValues[1],
                        groupName = currentGroup,
                        explicit = true,
                    )
                    pendingComment = null
                    continue
                }

                // Async message: `A->B: message text` (and optional `{ ... }` block)
                val asyncOpensBlock = tok.endsWith("{")
                val asyncCandidate = if (asyncOpensBlock) tok.dropLast(1).trimEnd() else tok
                val asyncMatch = Regex(
                    """^("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)\s*->\s*("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)\s*:\s*(.*?)\s*;?$""",
                ).find(asyncCandidate)
                if (asyncMatch != null) {
                    val fromId = ensureParticipant(asyncMatch.groupValues[1])
                    val toId = ensureParticipant(asyncMatch.groupValues[2])
                    val msgText = normalizeText(asyncMatch.groupValues[3])
                    val msgComment = pendingComment
                    pendingComment = null
                    val isReturnKind = pendingReturnAnnotation
                    pendingReturnAnnotation = false
                    val nested = if (asyncOpensBlock) {
                        callStack.add(CallFrame(callerId = fromId, calleeId = toId))
                        try {
                            parseBlock(callStack, stopOnCloseBrace = true)
                        } finally {
                            callStack.removeAt(callStack.lastIndex)
                        }
                    } else {
                        emptyList()
                    }
                    statements += ZenUmlStatement.Message(
                        from = fromId,
                        to = toId,
                        text = msgText,
                        kind = if (isReturnKind) ZenUmlMessageKind.RETURN else ZenUmlMessageKind.ASYNC,
                        comment = msgComment,
                        nestedStatements = nested,
                    )
                    continue
                }

                // Constructor creation call: `[Type] [ret =] [A->]new ClassName(args) [{]`
                val opensBlock = tok.endsWith("{")
                val cleanStmt = (if (opensBlock) tok.dropLast(1).trimEnd() else tok).removeSuffix(";").trim()
                val newMatch = Regex(
                    """^(?:(?:([A-Za-z0-9_<>]+)\s+)?([A-Za-z0-9_]+)\s*=\s*)?(?:([A-Za-z0-9_."'-]+)\s*->\s*)?new\s+([A-Za-z0-9_."'-]+)\s*\((.*)\)$""",
                ).find(cleanStmt)
                if (newMatch != null) {
                    val retType = newMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    val retVar = newMatch.groupValues[2].trim().takeIf { it.isNotEmpty() }
                    val explicitFrom = newMatch.groupValues[3].trim().takeIf { it.isNotEmpty() }
                    val className = unquote(newMatch.groupValues[4].trim())
                    val argsText = newMatch.groupValues[5].trim()
                    val toId = ensureParticipant(className)
                    val fromId = if (explicitFrom != null) {
                        ensureParticipant(explicitFrom)
                    } else {
                        callStack.lastOrNull()?.calleeId ?: resolveDefaultCaller(toId)
                    }
                    val msgComment = pendingComment
                    pendingComment = null
                    pendingReturnAnnotation = false
                    val nested = if (opensBlock) {
                        callStack.add(CallFrame(callerId = fromId, calleeId = toId))
                        try {
                            parseBlock(callStack, stopOnCloseBrace = true)
                        } finally {
                            callStack.removeAt(callStack.lastIndex)
                        }
                    } else {
                        emptyList()
                    }
                    statements += ZenUmlStatement.Message(
                        from = fromId,
                        to = toId,
                        text = "«create» ($argsText)",
                        returnVar = retVar,
                        returnType = retType,
                        kind = ZenUmlMessageKind.CREATE,
                        comment = msgComment,
                        nestedStatements = nested,
                    )
                    continue
                }

                // Synchronous method call:
                // `[ReturnType] [ret =] [A->]B.method(args)` OR self-call `[ReturnType] [ret =] method(args)`
                val syncMatch = Regex(
                    """^(?:(?:([A-Za-z0-9_<>]+)\s+)?([A-Za-z0-9_]+)\s*=\s*)?(?:("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)\s*->\s*)?(?:("[^"]+"|'[^']+'|[A-Za-z0-9_.-]+)\s*\.\s*)?([A-Za-z0-9_$-]+)\s*\((.*)\)$""",
                ).find(cleanStmt)
                if (syncMatch != null) {
                    val retType = syncMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    val retVar = syncMatch.groupValues[2].trim().takeIf { it.isNotEmpty() }
                    val explicitFrom = syncMatch.groupValues[3].trim().takeIf { it.isNotEmpty() }
                    val explicitTo = syncMatch.groupValues[4].trim().takeIf { it.isNotEmpty() }
                    val methodName = syncMatch.groupValues[5].trim()
                    val argsText = syncMatch.groupValues[6].trim()

                    val fromId: String
                    val toId: String
                    if (explicitFrom != null && explicitTo != null) {
                        fromId = ensureParticipant(explicitFrom)
                        toId = ensureParticipant(explicitTo)
                    } else if (explicitTo != null) {
                        val targetClean = unquote(explicitTo)
                        fromId = callStack.lastOrNull()?.calleeId ?: resolveDefaultCaller(targetClean)
                        toId = ensureParticipant(explicitTo)
                    } else if (explicitFrom != null) {
                        // `A->method(args)` treated as `A` calling `method` on itself or target
                        fromId = ensureParticipant(explicitFrom)
                        toId = fromId
                    } else {
                        // Self-call `method(args)` on active caller
                        fromId = callStack.lastOrNull()?.calleeId ?: resolveDefaultCaller(null)
                        toId = fromId
                    }

                    val msgComment = pendingComment
                    pendingComment = null
                    pendingReturnAnnotation = false
                    val nested = if (opensBlock) {
                        callStack.add(CallFrame(callerId = fromId, calleeId = toId))
                        try {
                            parseBlock(callStack, stopOnCloseBrace = true)
                        } finally {
                            callStack.removeAt(callStack.lastIndex)
                        }
                    } else {
                        emptyList()
                    }
                    statements += ZenUmlStatement.Message(
                        from = fromId,
                        to = toId,
                        text = "$methodName($argsText)",
                        returnVar = retVar,
                        returnType = retType,
                        kind = ZenUmlMessageKind.SYNC,
                        comment = msgComment,
                        nestedStatements = nested,
                    )
                    continue
                }

                throw IllegalArgumentException("Unrecognized ZenUML statement: $tok")
            }

            if (stopOnCloseBrace) {
                throw IllegalArgumentException("Unclosed '{' block in zenuml diagram")
            }
            flushTrailingComment()
            return statements
        }

        val callStack = mutableListOf<CallFrame>()
        val topStatements = parseBlock(callStack, stopOnCloseBrace = false)

        val finalParticipants = participantsById.values.map { p ->
            ZenUmlParticipant(
                id = p.id,
                label = p.label,
                stereotype = p.stereotype,
                rawAnnotation = p.rawAnnotation,
                groupName = p.groupName,
            )
        }
        val finalGroups = groups.entries
            .filter { it.value.isNotEmpty() }
            .map { (name, ids) -> ZenUmlParticipantGroup(name = name, participantIds = ids) }

        return ZenUmlDiagram(
            participants = finalParticipants,
            groups = finalGroups,
            statements = topStatements,
            mirrorParticipants = mirrorParticipants,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    /**
     * Splits ZenUML lines into discrete statements and `{` / `}` tokens so that:
     * - `// comment` lines remain intact
     * - `{` on its own line attaches to the preceding statement (`stmt {`)
     * - `}` on the same line or its own line becomes a standalone `"}"` token
     * - `} else if (...) {` or `} catch (...) {` splits into `"}"` followed by `"else if (...) {"`
     */
    private fun tokenizeZenUmlLines(lines: List<String>): List<String> {
        val rawTokens = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("//")) {
                rawTokens += trimmed
                continue
            }
            // Strip trailing inline `// comment` outside quotes and emit it before the statement
            val (codePart, inlineComment) = splitInlineSlashComment(trimmed)
            if (inlineComment != null && inlineComment.isNotEmpty()) {
                rawTokens += "// $inlineComment"
            }
            if (codePart.isEmpty()) continue
            splitBracesAndStatements(codePart, rawTokens)
        }

        // Merge standalone `{` onto the previous non-comment statement
        val merged = mutableListOf<String>()
        for (tok in rawTokens) {
            if (tok == "{") {
                val prevIdx = merged.indexOfLast { !it.startsWith("//") && it != "}" }
                if (prevIdx >= 0 && !merged[prevIdx].endsWith("{")) {
                    merged[prevIdx] = "${merged[prevIdx]} {"
                } else {
                    throw IllegalArgumentException("Unexpected '{' in zenuml diagram")
                }
            } else {
                merged += tok
            }
        }
        return merged
    }

    private fun splitBracesAndStatements(line: String, out: MutableList<String>) {
        val cur = StringBuilder()
        var activeQuote: Char? = null
        var parenDepth = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && activeQuote != null && i + 1 < line.length) {
                cur.append(line[i]).append(line[i + 1])
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || c == '\'')) {
                activeQuote = c
                cur.append(c)
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                cur.append(c)
                i++
                continue
            }
            if (activeQuote == null) {
                if (c == '(') parenDepth++
                else if (c == ')') parenDepth = (parenDepth - 1).coerceAtLeast(0)

                if (parenDepth == 0) {
                    if (c == '{') {
                        val before = cur.toString().trim()
                        cur.clear()
                        if (before.isNotEmpty()) {
                            out += "$before {"
                        } else {
                            out += "{"
                        }
                        i++
                        continue
                    }
                    if (c == '}') {
                        val before = cur.toString().trim()
                        cur.clear()
                        if (before.isNotEmpty()) {
                            out += before
                        }
                        out += "}"
                        i++
                        continue
                    }
                    if (c == ';') {
                        val before = cur.toString().trim()
                        cur.clear()
                        if (before.isNotEmpty()) {
                            out += before
                        }
                        i++
                        continue
                    }
                }
            }
            cur.append(c)
            i++
        }
        val rem = cur.toString().trim()
        if (rem.isNotEmpty()) {
            out += rem
        }
    }

    private fun splitInlineSlashComment(line: String): Pair<String, String?> {
        var activeQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && activeQuote != null && i + 1 < line.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || c == '\'')) {
                activeQuote = c
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                i++
                continue
            }
            if (activeQuote == null && line.startsWith("//", i)) {
                // Make sure it's not `http://` or `https://` inside an unquoted URI
                val prevChar = if (i > 0) line[i - 1] else ' '
                if (prevChar != ':') {
                    return line.substring(0, i).trim() to line.substring(i + 2).trim()
                }
            }
            i++
        }
        return line to null
    }

    private fun matchControlHeader(tok: String): Triple<ZenUmlControlKind, String, String?>? {
        if (!tok.endsWith("{")) return null
        val head = tok.dropLast(1).trim()
        val ifMatch = Regex("""^if\s*\((.*)\)$""", RegexOption.IGNORE_CASE).find(head)
        if (ifMatch != null) {
            return Triple(ZenUmlControlKind.ALT, "if", ifMatch.groupValues[1].trim())
        }
        val loopMatch = Regex("""^(while|forEach|foreach|for|loop)\s*(?:\((.*)\)|(.+))?$""", RegexOption.IGNORE_CASE).find(head)
        if (loopMatch != null) {
            val kw = loopMatch.groupValues[1]
            val cond = (loopMatch.groupValues[2].ifEmpty { loopMatch.groupValues[3] }).trim().takeIf { it.isNotEmpty() }
            return Triple(ZenUmlControlKind.LOOP, kw, cond)
        }
        if (head.equals("opt", ignoreCase = true)) {
            return Triple(ZenUmlControlKind.OPT, "opt", null)
        }
        if (head.equals("par", ignoreCase = true)) {
            return Triple(ZenUmlControlKind.PAR, "par", null)
        }
        if (head.equals("try", ignoreCase = true)) {
            return Triple(ZenUmlControlKind.TRY, "try", null)
        }
        return null
    }

    private fun parseStereotype(raw: String): ZenUmlParticipantStereotype = when (raw.lowercase()) {
        "actor", "starter" -> ZenUmlParticipantStereotype.ACTOR
        "boundary" -> ZenUmlParticipantStereotype.BOUNDARY
        "control" -> ZenUmlParticipantStereotype.CONTROL
        "entity" -> ZenUmlParticipantStereotype.ENTITY
        "database", "db", "rds" -> ZenUmlParticipantStereotype.DATABASE
        "collections", "s3" -> ZenUmlParticipantStereotype.COLLECTIONS
        "queue" -> ZenUmlParticipantStereotype.QUEUE
        else -> ZenUmlParticipantStereotype.DEFAULT
    }

    private fun isQuoteOpeningContext(s: String, index: Int): Boolean {
        var j = index - 1
        while (j >= 0 && (s[j] == ' ' || s[j] == '\t')) {
            j--
        }
        return j < 0 || s[j] == '\n' || s[j] == '\r' || s[j] == ':' || s[j] == '{' || s[j] == ',' || s[j] == '(' || s[j] == '='
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

    private fun stripMermaidComment(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("%%")) return ""
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
