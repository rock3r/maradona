package dev.maradona.parse

import dev.maradona.model.Direction
import dev.maradona.model.EdgeStyle
import dev.maradona.model.NodeStyle
import dev.maradona.model.StateDiagram
import dev.maradona.model.StateKind
import dev.maradona.model.StateNode
import dev.maradona.model.StateNote
import dev.maradona.model.StateNotePosition
import dev.maradona.model.StateTransition

/**
 * Hand-written parser for Mermaid `stateDiagram` and `stateDiagram-v2` sources.
 *
 * Supports:
 * - `stateDiagram` and `stateDiagram-v2` headers, YAML frontmatter (`title`, `hideEmptyDescription`),
 *   `%%{init}%%` directives, `title`, `accTitle`, `accDescr`, `hide empty description`,
 *   and `direction TB|TD|BT|LR|RL` (top-level and per composite state)
 * - Simple states (`StateId`, `StateId : description`, `state "Label" as StateId`)
 * - Pseudostates: `[*]` start/end per composite/region scope, `<<choice>>`, `<<fork>>`, `<<join>>`
 *   (and `[[choice]]`, `[[fork]]`, `[[join]]`)
 * - Composite states (`state CompositeId { ... }`, `state "Label" as CompositeId { ... }`) with
 *   nested states, transitions, notes, and `--` / `---` concurrent regions
 * - Transitions (`A --> B`, `A --> B : label`, chained `A --> B --> C : label`, `:::className`)
 * - Notes (`note right of S : text`, `note left of S ... end note`, `note "text" as N1`)
 * - `classDef`, `class`, `cssClass`, `style`, and inline `:::className`
 */
object StateParser {

    private data class ScopeFrame(
        val stateId: String,
        var regionIndex: Int = 0,
        var maxRegionIndex: Int = 0,
    )

    private class MutableState(
        val id: String,
        var label: String,
        var kind: StateKind = StateKind.Normal,
        val descriptions: MutableList<String> = mutableListOf(),
        var parentId: String? = null,
        var regionIndex: Int = 0,
        var direction: Direction? = null,
        var regionCount: Int = 1,
        var explicitlyOwned: Boolean = false,
        var styleOnlyPlaceholder: Boolean = false,
        val styleClasses: MutableList<String> = mutableListOf(),
        var inlineStyle: NodeStyle? = null,
    )

    private class MutableTransition(
        val from: String,
        val to: String,
        val label: String?,
        val styleClasses: MutableList<String> = mutableListOf(),
        var inlineStyle: EdgeStyle? = null,
    )

    fun parse(source: String): StateDiagram {
        var frontmatterTitle: String? = null
        var hideEmptyDescription = false

        val rawLines = FlowchartParser.stripDirectives(source).lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            while (idx < rawLines.size) {
                val fmLine = rawLines[idx].trim()
                idx++
                if (fmLine == "---" || fmLine == "...") break
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    val value = unquote(fmLine.substring(colon + 1).trim())
                    when (key) {
                        "title" -> if (value.isNotEmpty()) frontmatterTitle = value
                        "hideemptydescription" -> hideEmptyDescription = value.equals("true", ignoreCase = true)
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        // Expand inline `{` and `}` outside quotes/stereotypes so composite state blocks
        // can be parsed line-by-line even when written on a single line.
        val normalizedLines = expandBraces(bodyLines)

        var headerSeen = false
        var direction = Direction.TB
        var title: String? = frontmatterTitle
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        var inNoteBlock = false
        var noteBlockPos = StateNotePosition.Right
        var noteBlockTarget: String? = null
        var noteBlockParentId: String? = null
        var noteBlockRegion = 0
        val noteBlockLines = mutableListOf<String>()

        val scopeStack = ArrayDeque<ScopeFrame>()
        val states = LinkedHashMap<String, MutableState>()
        val transitions = mutableListOf<MutableTransition>()
        val notes = mutableListOf<StateNote>()
        val classDefs = HashMap<String, NodeStyle>()
        var noteCounter = 0

        fun currentParentId(): String? = scopeStack.lastOrNull()?.stateId
        fun currentRegionIndex(): Int = scopeStack.lastOrNull()?.regionIndex ?: 0

        fun wouldCreateCycle(stateId: String, candidateParentId: String?): Boolean {
            var cur = candidateParentId
            val visited = HashSet<String>()
            while (cur != null && visited.add(cur)) {
                if (cur == stateId) return true
                cur = states[cur]?.parentId
            }
            return false
        }

        fun ensureState(
            rawId: String,
            explicitLabel: String? = null,
            explicitKind: StateKind? = null,
            parentOverride: String? = currentParentId(),
            regionOverride: Int = currentRegionIndex(),
            isDeclaration: Boolean = false,
            styleOnly: Boolean = false,
        ): MutableState {
            val (cleanId, inlineClasses) = splitInlineClasses(rawId.trim())
            val id = unquote(cleanId).ifEmpty { cleanId }
            val existing = states[id]
            if (existing != null) {
                if (explicitLabel != null && (existing.label == existing.id || existing.kind == StateKind.Composite)) {
                    existing.label = explicitLabel
                }
                if (explicitKind != null && (existing.kind == StateKind.Normal || explicitKind == StateKind.Composite)) {
                    existing.kind = explicitKind
                }
                if (
                    !styleOnly &&
                    (existing.styleOnlyPlaceholder || (isDeclaration && !existing.explicitlyOwned)) &&
                    parentOverride != null &&
                    existing.parentId == null &&
                    !wouldCreateCycle(existing.id, parentOverride)
                ) {
                    existing.parentId = parentOverride
                    existing.regionIndex = regionOverride
                }
                if (!styleOnly) {
                    if (existing.styleOnlyPlaceholder && parentOverride != null) {
                        existing.explicitlyOwned = true
                    }
                    existing.styleOnlyPlaceholder = false
                }
                if (isDeclaration) {
                    existing.explicitlyOwned = true
                }
                existing.styleClasses += inlineClasses
                return existing
            }
            val safeParent = if (!styleOnly && parentOverride != null && !wouldCreateCycle(id, parentOverride)) parentOverride else null
            val created = MutableState(
                id = id,
                label = explicitLabel ?: id,
                kind = explicitKind ?: StateKind.Normal,
                parentId = safeParent,
                regionIndex = if (safeParent != null) regionOverride else 0,
                explicitlyOwned = !styleOnly && (isDeclaration || safeParent != null),
                styleOnlyPlaceholder = styleOnly,
            )
            created.styleClasses += inlineClasses
            states[id] = created
            return created
        }

        fun resolvePseudostateEndpoint(token: String, isSource: Boolean, localOwnership: Boolean = false): String {
            val trimmed = token.trim()
            if (trimmed == "[*]") {
                val pId = currentParentId()
                val reg = currentRegionIndex()
                val scopeSuffix = if (pId == null) "root" else "${pId}_r$reg"
                val id = if (isSource) "__start_${scopeSuffix}__" else "__end_${scopeSuffix}__"
                val kind = if (isSource) StateKind.Start else StateKind.End
                ensureState(
                    rawId = id,
                    explicitLabel = "",
                    explicitKind = kind,
                    parentOverride = pId,
                    regionOverride = reg,
                    isDeclaration = true,
                )
                return id
            }
            val (cleanToken, inlineClasses) = splitInlineClasses(trimmed)
            val unquoted = unquote(cleanToken)
            val st = ensureState(unquoted, isDeclaration = localOwnership)
            st.styleClasses += inlineClasses
            return st.id
        }

        for (rawLine in normalizedLines) {
            val stripped = stripLineComment(rawLine).trim()
            if (inAccDescrBlock) {
                if (stripped == "}") {
                    accDescr = accDescrLines.joinToString("\n").trim()
                    accDescrLines.clear()
                    inAccDescrBlock = false
                } else {
                    accDescrLines += rawLine.trim()
                }
                continue
            }
            if (inNoteBlock) {
                if (stripped.lowercase() == "end note" || stripped.lowercase() == "endnote") {
                    val noteId = "note_${++noteCounter}"
                    notes += StateNote(
                        id = noteId,
                        text = normalizeText(noteBlockLines.joinToString("\n").trim()),
                        forStateId = noteBlockTarget,
                        position = noteBlockPos,
                        parentId = noteBlockParentId,
                        regionIndex = noteBlockRegion,
                    )
                    noteBlockLines.clear()
                    inNoteBlock = false
                } else {
                    noteBlockLines += rawLine.trim()
                }
                continue
            }
            if (stripped.isEmpty()) continue

            for (stmt in splitSemicolonsOutsideQuotes(stripped)) {
                val line = stmt.trim()
                if (line.isEmpty()) continue
                val lower = line.lowercase()

                if (!headerSeen) {
                    if (lower.startsWith("statediagram-v2") || lower.startsWith("statediagram")) {
                        headerSeen = true
                        continue
                    }
                    headerSeen = true
                }

                // Closing brace of composite state
                if (line == "}") {
                    val finished = scopeStack.removeLastOrNull()
                    if (finished != null) {
                        states[finished.stateId]?.regionCount = finished.maxRegionIndex + 1
                    }
                    continue
                }

                // Concurrent region separator inside a composite state (`--` or `---`)
                if ((line == "--" || line == "---") && scopeStack.isNotEmpty()) {
                    val top = scopeStack.last()
                    top.regionIndex++
                    if (top.regionIndex > top.maxRegionIndex) {
                        top.maxRegionIndex = top.regionIndex
                    }
                    continue
                }

                // Direction
                if (lower.startsWith("direction ")) {
                    val dirToken = line.substring("direction ".length).trim().uppercase()
                    val parsedDir = when (dirToken) {
                        "TB", "TD" -> Direction.TB
                        "BT" -> Direction.BT
                        "LR" -> Direction.LR
                        "RL" -> Direction.RL
                        else -> null
                    }
                    if (parsedDir != null) {
                        val top = scopeStack.lastOrNull()
                        if (top != null) {
                            states[top.stateId]?.direction = parsedDir
                        } else {
                            direction = parsedDir
                        }
                    }
                    continue
                }

                if (lower == "hide empty description") {
                    hideEmptyDescription = true
                    continue
                }

                if (lower.startsWith("title ") || lower.startsWith("title:")) {
                    val after = if (lower.startsWith("title:")) line.substring(6) else line.substring(6)
                    title = normalizeText(unquote(after.trim()))
                    continue
                }

                if (lower.startsWith("acctitle:")) {
                    accTitle = normalizeText(unquote(line.substring("acctitle:".length).trim()))
                    continue
                }
                if (lower.startsWith("accdescr:")) {
                    accDescr = normalizeText(unquote(line.substring("accdescr:".length).trim()))
                    continue
                }
                if (lower.startsWith("accdescr") && line.endsWith("{")) {
                    inAccDescrBlock = true
                    accDescrLines.clear()
                    continue
                }

                // Styling directives
                if (lower.startsWith("classdef ")) {
                    val rest = line.substring("classDef ".length).trim()
                    val sp = rest.indexOfFirst { it.isWhitespace() }
                    if (sp > 0) {
                        val names = rest.substring(0, sp).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val style = parseNodeStyle(rest.substring(sp + 1).trim())
                        for (n in names) classDefs[n] = style
                    }
                    continue
                }
                if (lower.startsWith("class ")) {
                    val rest = line.substring("class ".length).trim()
                    val parts = rest.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val targets = parts[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val classNames = parts.drop(1).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                        for (t in targets) {
                            val st = ensureState(t, styleOnly = true)
                            st.styleClasses += classNames
                        }
                    }
                    continue
                }
                if (lower.startsWith("cssclass ")) {
                    val rest = line.substring("cssClass ".length).trim()
                    val m = Regex("""^"([^"]+)"\s+(\S+)$""").find(rest)
                    if (m != null) {
                        val targets = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val cls = m.groupValues[2].trim()
                        for (t in targets) ensureState(t, styleOnly = true).styleClasses += cls
                    }
                    continue
                }
                if (lower.startsWith("style ")) {
                    val rest = line.substring("style ".length).trim()
                    val sp = rest.indexOfFirst { it.isWhitespace() }
                    if (sp > 0) {
                        val target = rest.substring(0, sp).trim()
                        val style = parseNodeStyle(rest.substring(sp + 1).trim())
                        ensureState(target, styleOnly = true).inlineStyle = style
                    }
                    continue
                }

                // Notes
                if (lower.startsWith("note ")) {
                    val noteRest = line.substring(5).trim()
                    // Standalone note: `note "text" as N1`
                    val standaloneMatch = Regex("""^"((?:\\.|[^"\\])*)"\s+as\s+([^\s:]+)(?:\s*:\s*(.*))?$""", RegexOption.IGNORE_CASE)
                        .find(noteRest)
                    if (standaloneMatch != null) {
                        val initialText = unquote("\"${standaloneMatch.groupValues[1]}\"")
                        val noteId = unquote(standaloneMatch.groupValues[2].trim())
                        val extra = standaloneMatch.groupValues.getOrNull(3)?.trim().orEmpty()
                        val fullText = if (extra.isNotEmpty()) "$initialText\n$extra" else initialText
                        notes += StateNote(
                            id = noteId,
                            text = normalizeText(fullText),
                            forStateId = null,
                            position = StateNotePosition.Right,
                            parentId = currentParentId(),
                            regionIndex = currentRegionIndex(),
                        )
                        continue
                    }

                    val posMatch = Regex("""^(right|left)\s+of\s+([^\s:]+)(?:\s*:\s*(.*))?$""", RegexOption.IGNORE_CASE)
                        .find(noteRest)
                    if (posMatch != null) {
                        val pos = if (posMatch.groupValues[1].equals("left", ignoreCase = true)) {
                            StateNotePosition.Left
                        } else {
                            StateNotePosition.Right
                        }
                        val rawTarget = posMatch.groupValues[2].trim()
                        val targetId = if (rawTarget == "[*]") {
                            resolvePseudostateEndpoint("[*]", isSource = true)
                        } else {
                            ensureState(rawTarget).id
                        }
                        val inlineText = posMatch.MatchesColon(noteRest)
                        if (inlineText != null) {
                            val noteId = "note_${++noteCounter}"
                            notes += StateNote(
                                id = noteId,
                                text = normalizeText(unquote(inlineText)),
                                forStateId = targetId,
                                position = pos,
                                parentId = currentParentId(),
                                regionIndex = currentRegionIndex(),
                            )
                        } else {
                            inNoteBlock = true
                            noteBlockPos = pos
                            noteBlockTarget = targetId
                            noteBlockParentId = currentParentId()
                            noteBlockRegion = currentRegionIndex()
                            noteBlockLines.clear()
                        }
                        continue
                    }
                }

                // Explicit `state ...` declaration or composite state block opener
                if (lower.startsWith("state ") || line.endsWith("{")) {
                    if (
                        parseStateDeclaration(
                            line = line,
                            ensureState = { id, lbl, k, p, r, decl -> ensureState(id, lbl, k, p, r, decl) },
                            scopeStack = scopeStack,
                        )
                    ) {
                        continue
                    }
                }

                // Transitions (`A --> B`, `A --> B --> C : label`)
                val arrowParts = splitArrowsOutsideQuotes(line)
                if (arrowParts.size >= 2) {
                    val lastPart = arrowParts.last()
                    val colonAt = findColonOutsideQuotes(lastPart)
                    val lastTargetRaw = if (colonAt >= 0) lastPart.substring(0, colonAt).trim() else lastPart.trim()
                    val transLabel = if (colonAt >= 0) {
                        normalizeText(unquote(lastPart.substring(colonAt + 1).trim())).takeIf { it.isNotEmpty() }
                    } else null
                    val endpoints = arrowParts.dropLast(1).map { it.trim() } + lastTargetRaw
                    for (i in 0 until endpoints.lastIndex) {
                        val rawFrom = endpoints[i]
                        val rawTo = endpoints[i + 1]
                        val cleanFrom = unquote(splitInlineClasses(rawFrom).first)
                        val cleanTo = unquote(splitInlineClasses(rawTo).first)
                        val localScope = currentParentId() != null &&
                            (rawFrom == "[*]" || rawTo == "[*]" || cleanFrom == cleanTo)
                        val fromId = resolvePseudostateEndpoint(rawFrom, isSource = true, localOwnership = localScope)
                        val toId = resolvePseudostateEndpoint(rawTo, isSource = false, localOwnership = localScope)
                        val lbl = if (i == endpoints.lastIndex - 1) transLabel else null
                        transitions += MutableTransition(from = fromId, to = toId, label = lbl)
                    }
                    continue
                }

                // State description line: `StateId : description`
                val colonAt = findColonOutsideQuotes(line)
                if (colonAt > 0) {
                    val rawState = line.substring(0, colonAt).trim()
                    val desc = normalizeText(unquote(line.substring(colonAt + 1).trim()))
                    if (rawState.isNotEmpty()) {
                        val st = ensureState(rawState, isDeclaration = true)
                        if (desc.isNotEmpty()) {
                            // Check for stereotype in description like `StateId : <<choice>>`
                            val stereo = parseStereotypeKind(desc)
                            if (stereo != null && st.descriptions.isEmpty()) {
                                st.kind = stereo
                            } else {
                                st.descriptions += desc
                            }
                        }
                        continue
                    }
                }

                // Bare state identifier (`StateId` or `StateId <<choice>>`)
                parseBareStateLine(line, currentParentId(), currentRegionIndex()) { id, lbl, k, p, r, decl ->
                    ensureState(id, lbl, k, p, r, decl)
                }
            }
        }

        val finalStates = states.values.map { st ->
            val resolvedStyle = resolveStyle(st.styleClasses, st.inlineStyle, classDefs)
            StateNode(
                id = st.id,
                label = normalizeText(st.label),
                kind = st.kind,
                descriptions = st.descriptions.toList(),
                parentId = st.parentId,
                regionIndex = st.regionIndex,
                direction = st.direction,
                regionCount = st.regionCount,
                style = resolvedStyle,
            )
        }
        val finalTransitions = transitions.map { tr ->
            StateTransition(
                from = tr.from,
                to = tr.to,
                label = tr.label,
                style = tr.inlineStyle,
            )
        }

        return StateDiagram(
            direction = direction,
            states = finalStates,
            transitions = finalTransitions,
            notes = notes,
            title = title,
            accTitle = accTitle,
            accDescr = accDescr,
            hideEmptyDescription = hideEmptyDescription,
        )
    }

    private fun MatchResult.MatchesColon(noteRest: String): String? {
        val colonIdx = findColonOutsideQuotes(noteRest)
        return if (colonIdx >= 0) noteRest.substring(colonIdx + 1).trim() else null
    }

    private fun parseStateDeclaration(
        line: String,
        ensureState: (String, String?, StateKind?, String?, Int, Boolean) -> MutableState,
        scopeStack: ArrayDeque<ScopeFrame>,
    ): Boolean {
        val opensBlock = line.endsWith("{")
        val core = (if (opensBlock) line.dropLast(1).trim() else line).let {
            if (it.lowercase().startsWith("state ")) it.substring(6).trim() else it
        }
        if (core.isEmpty()) return false

        val parentId = scopeStack.lastOrNull()?.stateId
        val regionIdx = scopeStack.lastOrNull()?.regionIndex ?: 0

        // Extract optional trailing `: description` (only when not opening a block)
        val colonIdx = if (!opensBlock) findColonOutsideQuotes(core) else -1
        val declPart = if (colonIdx >= 0) core.substring(0, colonIdx).trim() else core
        val descPart = if (colonIdx >= 0) normalizeText(unquote(core.substring(colonIdx + 1).trim())) else null

        // Match `"Label" as Id` first so stereotype tokens inside quoted labels stay literal.
        var stereoKind: StateKind? = null
        val stereoRegex = Regex("""(?:<<\s*([a-zA-Z0-9_]+)\s*>>|\[\[\s*([a-zA-Z0-9_]+)\s*]])""")
        fun extractStereo(unquotedTarget: String): String {
            val m = stereoRegex.find(unquotedTarget) ?: return unquotedTarget
            val tag = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
            stereoKind = when (tag) {
                "choice" -> StateKind.Choice
                "fork" -> StateKind.Fork
                "join" -> StateKind.Join
                else -> null
            }
            return unquotedTarget.removeRange(m.range).trim()
        }

        val asRegex = Regex("""^"((?:\\.|[^"\\])*)"\s+as\s+(.+)$""", RegexOption.IGNORE_CASE)
        val asMatch = asRegex.find(declPart)
        val (idRaw, label) = if (asMatch != null) {
            val lbl = unquote("\"${asMatch.groupValues[1]}\"")
            val idToken = extractStereo(asMatch.groupValues[2].trim())
            idToken to lbl
        } else if (declPart.startsWith('"') && declPart.endsWith('"')) {
            val unq = unquote(declPart)
            unq to unq
        } else {
            val withoutStereo = extractStereo(declPart)
            val (cleanWithoutClasses, _) = splitInlineClasses(withoutStereo)
            val unq = unquote(cleanWithoutClasses)
            withoutStereo to unq
        }

        val kind = when {
            opensBlock -> StateKind.Composite
            stereoKind != null -> stereoKind
            else -> null
        }
        val st = ensureState(idRaw, label, kind, parentId, regionIdx, true)
        if (!descPart.isNullOrEmpty()) {
            st.descriptions += descPart
        }
        if (opensBlock) {
            st.kind = StateKind.Composite
            scopeStack.addLast(ScopeFrame(st.id))
        }
        return true
    }

    private fun parseBareStateLine(
        line: String,
        parentId: String?,
        regionIdx: Int,
        ensureState: (String, String?, StateKind?, String?, Int, Boolean) -> MutableState,
    ) {
        val stereoRegex = Regex("""^(\S+)\s*(?:<<\s*([a-zA-Z0-9_]+)\s*>>|\[\[\s*([a-zA-Z0-9_]+)\s*]])$""")
        val m = stereoRegex.find(line)
        if (m != null) {
            val id = m.groupValues[1]
            val tag = (m.groupValues[2].ifEmpty { m.groupValues[3] }).lowercase()
            val kind = when (tag) {
                "choice" -> StateKind.Choice
                "fork" -> StateKind.Fork
                "join" -> StateKind.Join
                else -> StateKind.Normal
            }
            ensureState(id, null, kind, parentId, regionIdx, true)
            return
        }
        if (!line.contains(' ')) {
            ensureState(line, null, null, parentId, regionIdx, true)
        }
    }

    private fun parseStereotypeKind(text: String): StateKind? {
        val m = Regex("""^(?:<<\s*([a-zA-Z0-9_]+)\s*>>|\[\[\s*([a-zA-Z0-9_]+)\s*]])$""").find(text.trim())
            ?: return null
        val tag = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
        return when (tag) {
            "choice" -> StateKind.Choice
            "fork" -> StateKind.Fork
            "join" -> StateKind.Join
            else -> null
        }
    }

    private fun expandBraces(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var inNoteBlock = false
        var inAccDescrBlock = false
        for (raw in lines) {
            val clean = stripLineComment(raw)
            val trimmed = clean.trim()
            val lower = trimmed.lowercase()
            if (inAccDescrBlock) {
                out += clean
                if (trimmed == "}") inAccDescrBlock = false
                continue
            }
            if (inNoteBlock) {
                out += clean
                if (lower == "end note" || lower == "endnote") inNoteBlock = false
                continue
            }
            if (lower.startsWith("accdescr")) {
                out += clean
                if (trimmed.endsWith("{")) inAccDescrBlock = true
                continue
            }
            if (lower.startsWith("note ")) {
                out += clean
                val noteRest = trimmed.substring(5).trim()
                val posMatch = Regex("""^(right|left)\s+of\s+([^\s:]+)(?:\s*:\s*(.*))?$""", RegexOption.IGNORE_CASE).find(noteRest)
                if (posMatch != null && findColonOutsideQuotes(noteRest) < 0) {
                    inNoteBlock = true
                }
                continue
            }
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < clean.length) {
                val c = clean[i]
                if (c == '\\' && inQuotes && i + 1 < clean.length) {
                    sb.append(c).append(clean[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                    sb.append(c)
                    i++
                    continue
                }
                // Once an unquoted `:` (not `:::`) is reached, the rest of the line is a
                // transition label or state description — preserve any `{` or `}` verbatim.
                if (!inQuotes && c == ':') {
                    if (clean.startsWith(":::", i)) {
                        sb.append(":::")
                        i += 3
                        continue
                    }
                    sb.append(clean.substring(i))
                    break
                }
                if (!inQuotes && c == '{') {
                    sb.append(" {")
                    out += sb.toString()
                    sb.setLength(0)
                    i++
                    continue
                }
                if (!inQuotes && c == '}') {
                    if (sb.isNotBlank()) {
                        out += sb.toString()
                        sb.setLength(0)
                    }
                    out += "}"
                    i++
                    continue
                }
                sb.append(c)
                i++
            }
            if (sb.isNotBlank()) {
                out += sb.toString()
            }
        }
        return out
    }

    private fun splitArrowsOutsideQuotes(line: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                sb.append(c).append(line[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                sb.append(c)
                i++
                continue
            }
            if (!inQuotes && c == ':') {
                // Everything from `:` to the end of the line is a label/description, not an arrow.
                // However, ignore `:::` style class operator.
                if (line.startsWith(":::", i)) {
                    sb.append(":::")
                    i += 3
                    continue
                }
                sb.append(line.substring(i))
                break
            }
            if (!inQuotes && line.startsWith("-->", i)) {
                parts += sb.toString()
                sb.setLength(0)
                i += 3
                continue
            }
            sb.append(c)
            i++
        }
        parts += sb.toString()
        return parts
    }

    private fun findColonOutsideQuotes(s: String): Int {
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && inQuotes && i + 1 < s.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && c == ':') {
                if (s.startsWith(":::", i)) {
                    i += 3
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    private fun splitInlineClasses(raw: String): Pair<String, List<String>> {
        val idx = raw.indexOf(":::")
        if (idx < 0) return raw to emptyList()
        val base = raw.substring(0, idx).trim()
        val classes = raw.substring(idx + 3).split(":::", ",").map { it.trim() }.filter { it.isNotEmpty() }
        return base to classes
    }

    private fun stripLineComment(line: String): String {
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun splitSemicolonsOutsideQuotes(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                sb.append(c).append(line[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                sb.append(c)
                i++
                continue
            }
            if (!inQuotes && c == ';') {
                out += sb.toString()
                sb.setLength(0)
                i++
                continue
            }
            sb.append(c)
            i++
        }
        out += sb.toString()
        return out
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
            return t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return t
    }

    private fun normalizeText(s: String): String =
        s.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")

    private fun parseNodeStyle(spec: String): NodeStyle {
        var fill: String? = null
        var stroke: String? = null
        var strokeWidth: String? = null
        var color: String? = null
        for (part in spec.split(',')) {
            val kv = part.split(':', limit = 2)
            if (kv.size != 2) continue
            val k = kv[0].trim().lowercase()
            val v = kv[1].trim()
            when (k) {
                "fill", "background", "background-color" -> fill = v
                "stroke", "border-color" -> stroke = v
                "stroke-width" -> strokeWidth = v
                "color" -> color = v
            }
        }
        return NodeStyle(
            fill = fill,
            stroke = stroke,
            textColor = color,
            strokeWidth = strokeWidth,
        )
    }

    private fun resolveStyle(
        classes: List<String>,
        inline: NodeStyle?,
        classDefs: Map<String, NodeStyle>,
    ): NodeStyle? {
        var acc: NodeStyle? = null
        for (c in classes) {
            val def = classDefs[c] ?: continue
            acc = acc?.merge(def) ?: def
        }
        if (inline != null) {
            acc = acc?.merge(inline) ?: inline
        }
        return acc
    }
}
