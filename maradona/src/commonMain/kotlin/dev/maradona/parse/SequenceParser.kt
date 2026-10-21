package dev.maradona.parse

import dev.maradona.model.NotePlacement
import dev.maradona.model.ParticipantKind
import dev.maradona.model.SequenceArrow
import dev.maradona.model.SequenceBlockKind
import dev.maradona.model.SequenceDiagram
import dev.maradona.model.SequenceHead
import dev.maradona.model.SequenceParticipant
import dev.maradona.model.SequenceParticipantBoxGroup
import dev.maradona.model.SequenceSection
import dev.maradona.model.SequenceStatement

/**
 * Parser for Mermaid `sequenceDiagram` sources.
 *
 * Supports `participant` and `actor` declarations (with optional `as` aliases),
 * implicit participants from messages and notes, all standard arrow operators
 * (`->>`, `-->>`, `->`, `-->`, `-x`, `--x`, `-)`, `--)`, `<<->>`, `<<-->>`),
 * activation shorthands (`+` / `-`) and `activate` / `deactivate` statements,
 * `Note left of` / `right of` / `over`, control blocks (`loop`, `alt`/`else`,
 * `opt`, `par`/`and`, `critical`/`option`, `break`, `rect` ... `end`),
 * `autonumber`, `title`, YAML frontmatter (`--- ... ---`), and `%%{init}%%`.
 */
object SequenceParser {

    private const val MAX_SOURCE_LENGTH = 50_000
    private const val MAX_PARTICIPANTS = 500
    private const val MAX_STATEMENTS = 5_000

    private data class ArrowToken(val token: String, val arrow: SequenceArrow)

    // Longest arrow operators first so `<<-->>` matches before `-->>` and `->>` before `->`.
    private val arrows = listOf(
        ArrowToken("<<-->>", SequenceArrow(dashed = true, head = SequenceHead.Filled, bidirectional = true)),
        ArrowToken("<<->>", SequenceArrow(dashed = false, head = SequenceHead.Filled, bidirectional = true)),
        ArrowToken("-->>", SequenceArrow(dashed = true, head = SequenceHead.Filled)),
        ArrowToken("->>", SequenceArrow(dashed = false, head = SequenceHead.Filled)),
        ArrowToken("-->", SequenceArrow(dashed = true, head = SequenceHead.Open)),
        ArrowToken("->", SequenceArrow(dashed = false, head = SequenceHead.Open)),
        ArrowToken("--x", SequenceArrow(dashed = true, head = SequenceHead.Cross)),
        ArrowToken("-x", SequenceArrow(dashed = false, head = SequenceHead.Cross)),
        ArrowToken("--)", SequenceArrow(dashed = true, head = SequenceHead.Async)),
        ArrowToken("-)", SequenceArrow(dashed = false, head = SequenceHead.Async)),
    )

    fun parse(source: String): SequenceDiagram {
        if (source.length > MAX_SOURCE_LENGTH) {
            throw MaradonaParseException(1, "Source exceeds $MAX_SOURCE_LENGTH characters")
        }
        val state = State()
        val lines = FlowchartParser.stripDirectives(source).lines()
        var headerSeen = false
        var inFrontmatter = false
        for ((index, rawLine) in lines.withIndex()) {
            val lineNo = index + 1
            val trimmedRaw = rawLine.trim()
            if (!headerSeen) {
                if (inFrontmatter) {
                    if (trimmedRaw == "---" || trimmedRaw == "...") {
                        inFrontmatter = false
                    } else if (trimmedRaw.lowercase().startsWith("title:")) {
                        state.title = NodeRefReader.cleanLabel(trimmedRaw.substring("title:".length))
                    }
                    continue
                }
                if (trimmedRaw == "---") {
                    inFrontmatter = true
                    continue
                }
            }
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            if (!headerSeen) {
                if (!line.lowercase().startsWith("sequencediagram")) {
                    throw MaradonaParseException(lineNo, "Expected 'sequenceDiagram', got: ${line.take(40)}")
                }
                headerSeen = true
                val rest = line.substring("sequenceDiagram".length).trim().trimStart(';')
                for (stmt in splitStatements(rest)) {
                    state.statement(stmt, lineNo)
                }
                continue
            }
            for (stmt in splitStatements(line)) {
                state.statement(stmt, lineNo)
            }
        }
        if (!headerSeen) throw MaradonaParseException(1, "Expected 'sequenceDiagram'")
        if (state.participants.size > MAX_PARTICIPANTS) {
            throw MaradonaParseException(1, "Too many participants")
        }
        if (state.statementCount > MAX_STATEMENTS) {
            throw MaradonaParseException(1, "Too many statements")
        }
        return state.build()
    }

    /**
     * Splits a line into semicolon-delimited statements while preserving `;`
     * inside double quotes, parentheses (`rgb(...)`), and HTML entities (`&amp;`, `&#59;`).
     */
    private fun splitStatements(line: String): List<String> {
        if (';' !in line) {
            val t = line.trim()
            return if (t.isEmpty()) emptyList() else listOf(t)
        }
        val out = mutableListOf<String>()
        var start = 0
        var inQuote = false
        var parenDepth = 0
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuote = !inQuote
                '(' -> if (!inQuote) parenDepth++
                ')' -> if (!inQuote && parenDepth > 0) parenDepth--
                ';' -> if (!inQuote && parenDepth == 0 && !isEntitySemicolon(line, i)) {
                    val part = line.substring(start, i).trim()
                    if (part.isNotEmpty()) out += part
                    start = i + 1
                }
            }
        }
        val tail = line.substring(start).trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    private fun isEntitySemicolon(line: String, semiIdx: Int): Boolean {
        var j = semiIdx - 1
        while (j >= 0 && line[j].isLetterOrDigit()) j--
        if (j < 0 || j == semiIdx - 1) return false
        return line[j] == '&' || line[j] == '#'
    }

    private fun stripComment(line: String): String {
        var inQuote = false
        var i = 0
        while (i < line.length - 1) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            if (!inQuote && c == '%' && line[i + 1] == '%') return line.substring(0, i)
            i++
        }
        return line
    }

    private class OpenBlock(
        val kind: SequenceBlockKind,
        val label: String?,
        val sections: MutableList<Pair<String?, MutableList<SequenceStatement>>> = mutableListOf(
            null to mutableListOf(),
        ),
    )

    private class OpenBox(
        val color: String?,
        val label: String?,
        val participantIds: MutableList<String> = mutableListOf(),
    )

    private val boxColorTokens = setOf(
        "transparent", "black", "white", "red", "green", "blue", "yellow",
        "orange", "purple", "pink", "gray", "grey", "cyan", "aqua", "teal",
        "lightblue", "lightgreen", "lightyellow", "magenta",
    )

    private class State {
        var title: String? = null
        var autonumber: Boolean = false
        val participants = LinkedHashMap<String, SequenceParticipant>()
        private val boxes = mutableListOf<SequenceParticipantBoxGroup>()
        private var openBox: OpenBox? = null
        private val rootStatements = mutableListOf<SequenceStatement>()
        private val blockStack = ArrayDeque<OpenBlock>()
        var statementCount = 0

        private fun currentList(): MutableList<SequenceStatement> =
            blockStack.lastOrNull()?.sections?.last()?.second ?: rootStatements

        fun statement(stmt: String, lineNo: Int) {
            statementCount++
            val lower = stmt.lowercase()
            when {
                lower == "autonumber" || lower.startsWith("autonumber ") -> autonumber = true
                lower.startsWith("title:") -> title = NodeRefReader.cleanLabel(stmt.substring("title:".length))
                lower.startsWith("title ") -> title = NodeRefReader.cleanLabel(stmt.substring("title ".length))
                lower.startsWith("participant ") -> declareParticipant(
                    stmt.substring("participant ".length).trim(),
                    ParticipantKind.Participant,
                    lineNo,
                )
                lower.startsWith("actor ") -> declareParticipant(
                    stmt.substring("actor ".length).trim(),
                    ParticipantKind.Actor,
                    lineNo,
                )
                lower.startsWith("activate ") -> {
                    val id = unquoteId(stmt.substring("activate ".length).trim())
                    ensureParticipant(id)
                    currentList() += SequenceStatement.Activate(id)
                }
                lower.startsWith("deactivate ") -> {
                    val id = unquoteId(stmt.substring("deactivate ".length).trim())
                    ensureParticipant(id)
                    currentList() += SequenceStatement.Deactivate(id)
                }
                lower.startsWith("note ") -> parseNote(stmt.substring("note ".length).trim(), lineNo)
                lower == "end" -> {
                    val activeBox = openBox
                    if (activeBox != null) {
                        openBox = null
                        if (activeBox.participantIds.isNotEmpty()) {
                            boxes += SequenceParticipantBoxGroup(
                                color = activeBox.color,
                                label = activeBox.label,
                                participantIds = activeBox.participantIds.toList(),
                            )
                        }
                    } else if (blockStack.isNotEmpty()) {
                        val block = blockStack.removeLast()
                        val built = SequenceStatement.Block(
                            kind = block.kind,
                            label = block.label,
                            sections = block.sections.map { (secLabel, list) -> SequenceSection(secLabel, list) },
                        )
                        currentList() += built
                    } else {
                        throw MaradonaParseException(lineNo, "'end' without open block")
                    }
                }
                lower.startsWith("loop ") || lower == "loop" ->
                    openBlock(SequenceBlockKind.Loop, stmt.drop(4).trim())
                lower.startsWith("alt ") || lower == "alt" ->
                    openBlock(SequenceBlockKind.Alt, stmt.drop(3).trim())
                lower.startsWith("opt ") || lower == "opt" ->
                    openBlock(SequenceBlockKind.Opt, stmt.drop(3).trim())
                lower.startsWith("par ") || lower == "par" ->
                    openBlock(SequenceBlockKind.Par, stmt.drop(3).trim())
                lower.startsWith("critical ") || lower == "critical" ->
                    openBlock(SequenceBlockKind.Critical, stmt.drop(8).trim())
                lower.startsWith("break ") || lower == "break" ->
                    openBlock(SequenceBlockKind.Break, stmt.drop(5).trim())
                lower.startsWith("rect ") || lower == "rect" ->
                    openBlock(SequenceBlockKind.Rect, stmt.drop(4).trim())
                lower.startsWith("else") && (stmt.length == 4 || stmt[4].isWhitespace()) ->
                    addSection(stmt.drop(4).trim(), lineNo)
                lower.startsWith("and") && (stmt.length == 3 || stmt[3].isWhitespace()) ->
                    addSection(stmt.drop(3).trim(), lineNo)
                lower.startsWith("option") && (stmt.length == 6 || stmt[6].isWhitespace()) ->
                    addSection(stmt.drop(6).trim(), lineNo)
                lower.startsWith("link ") || lower.startsWith("links ") ||
                    lower.startsWith("properties ") || lower.startsWith("details ") ||
                    lower.startsWith("create ") || lower.startsWith("destroy ") -> Unit
                (lower == "box" || lower.startsWith("box ")) && !containsArrow(stmt) ->
                    startBox(stmt.drop(3).trim(), lineNo)
                else -> parseMessage(stmt, lineNo)
            }
        }

        private fun startBox(rest: String, lineNo: Int) {
            if (openBox != null) {
                throw MaradonaParseException(lineNo, "Nested 'box' blocks are not supported")
            }
            if (rest.isEmpty()) {
                openBox = OpenBox(color = null, label = null)
                return
            }
            val lower = rest.lowercase()
            if (lower.startsWith("rgb(") || lower.startsWith("rgba(")) {
                val closeParen = rest.indexOf(')')
                if (closeParen > 0) {
                    val color = rest.substring(0, closeParen + 1).trim()
                    val label = rest.substring(closeParen + 1).trim().takeIf { it.isNotEmpty() }
                        ?.let(NodeRefReader::cleanLabel)
                    openBox = OpenBox(color = color, label = label)
                    return
                }
            }
            val firstToken = rest.substringBefore(' ').trim()
            if (firstToken.startsWith("#") || firstToken.lowercase() in boxColorTokens) {
                val label = rest.substringAfter(' ', "").trim().takeIf { it.isNotEmpty() }
                    ?.let(NodeRefReader::cleanLabel)
                openBox = OpenBox(color = firstToken, label = label)
            } else {
                openBox = OpenBox(color = null, label = NodeRefReader.cleanLabel(rest))
            }
        }

        private fun containsArrow(stmt: String): Boolean {
            var inQuote = false
            for (i in stmt.indices) {
                if (stmt[i] == '"') inQuote = !inQuote
                if (!inQuote && arrows.any { stmt.startsWith(it.token, i) }) return true
            }
            return false
        }

        private fun openBlock(kind: SequenceBlockKind, rawLabel: String) {
            val label = rawLabel.takeIf { it.isNotEmpty() }?.let { NodeRefReader.cleanLabel(it) }
            blockStack.addLast(OpenBlock(kind, label))
        }

        private fun addSection(rawLabel: String, lineNo: Int) {
            val current = blockStack.lastOrNull()
                ?: throw MaradonaParseException(lineNo, "Section divider outside of a block")
            val label = rawLabel.takeIf { it.isNotEmpty() }?.let { NodeRefReader.cleanLabel(it) }
            current.sections += label to mutableListOf()
        }

        private fun declareParticipant(rest: String, kind: ParticipantKind, lineNo: Int) {
            if (rest.isEmpty()) throw MaradonaParseException(lineNo, "Participant needs an id")
            val asMatch = Regex("\\s+as\\s+", RegexOption.IGNORE_CASE).find(rest)
            val id: String
            val label: String
            if (asMatch != null) {
                id = unquoteId(rest.substring(0, asMatch.range.first).trim())
                label = NodeRefReader.cleanLabel(rest.substring(asMatch.range.last + 1).trim())
            } else {
                id = unquoteId(rest)
                label = NodeRefReader.cleanLabel(rest)
            }
            if (id.isEmpty()) throw MaradonaParseException(lineNo, "Participant needs an id")
            participants[id] = SequenceParticipant(id = id, label = label, kind = kind)
            openBox?.let { box -> if (id !in box.participantIds) box.participantIds += id }
        }

        private fun parseNote(rest: String, lineNo: Int) {
            val colon = rest.indexOf(':')
            if (colon < 0) throw MaradonaParseException(lineNo, "Note is missing ':'")
            val header = rest.substring(0, colon).trim()
            val text = NodeRefReader.cleanLabel(rest.substring(colon + 1))
            val lower = header.lowercase()
            val placement: NotePlacement
            val targetPart: String
            when {
                lower.startsWith("left of ") -> {
                    placement = NotePlacement.LeftOf
                    targetPart = header.substring("left of ".length).trim()
                }
                lower.startsWith("right of ") -> {
                    placement = NotePlacement.RightOf
                    targetPart = header.substring("right of ".length).trim()
                }
                lower.startsWith("over ") -> {
                    placement = NotePlacement.Over
                    targetPart = header.substring("over ".length).trim()
                }
                else -> throw MaradonaParseException(lineNo, "Unknown note placement in: $header")
            }
            val targets = targetPart.split(',').map { unquoteId(it.trim()) }.filter { it.isNotEmpty() }
            if (targets.isEmpty()) throw MaradonaParseException(lineNo, "Note needs a target participant")
            targets.forEach(::ensureParticipant)
            currentList() += SequenceStatement.Note(placement, targets, text)
        }

        private fun parseMessage(stmt: String, lineNo: Int) {
            val colon = stmt.indexOf(':')
            val left = if (colon >= 0) stmt.substring(0, colon).trim() else stmt.trim()
            val text = if (colon >= 0) NodeRefReader.cleanLabel(stmt.substring(colon + 1)) else ""

            var bestAt = -1
            var bestArrow: ArrowToken? = null
            var inQuote = false
            for (i in left.indices) {
                if (left[i] == '"') inQuote = !inQuote
                if (inQuote) continue
                for (candidate in arrows) {
                    if (left.startsWith(candidate.token, i)) {
                        bestAt = i
                        bestArrow = candidate
                        break
                    }
                }
                if (bestArrow != null) break
            }
            val match = bestArrow
                ?: throw MaradonaParseException(lineNo, "Expected a sequence statement or message at: ${stmt.take(40)}")
            val from = unquoteId(left.substring(0, bestAt).trim())
            var right = left.substring(bestAt + match.token.length).trim()
            var activateTarget = false
            var deactivateSource = false
            if (right.startsWith("+")) {
                activateTarget = true
                right = right.substring(1).trim()
            } else if (right.startsWith("-")) {
                deactivateSource = true
                right = right.substring(1).trim()
            }
            val to = unquoteId(right)
            if (from.isEmpty() || to.isEmpty()) {
                throw MaradonaParseException(lineNo, "Message needs both sender and receiver: $stmt")
            }
            ensureParticipant(from)
            ensureParticipant(to)
            currentList() += SequenceStatement.Message(
                from = from,
                to = to,
                text = text,
                arrow = match.arrow,
                activateTarget = activateTarget,
                deactivateSource = deactivateSource,
            )
        }

        private fun ensureParticipant(id: String) {
            if (id !in participants) {
                participants[id] = SequenceParticipant(id = id, label = id)
            }
            openBox?.let { box -> if (id !in box.participantIds) box.participantIds += id }
        }

        private fun unquoteId(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
                trimmed.substring(1, trimmed.length - 1).trim()
            } else {
                trimmed
            }
        }

        fun build(): SequenceDiagram {
            if (openBox != null) {
                throw MaradonaParseException(statementCount, "Unclosed 'box' block")
            }
            if (blockStack.isNotEmpty()) {
                throw MaradonaParseException(statementCount, "Unclosed '${blockStack.last().kind.name.lowercase()}' block")
            }
            return SequenceDiagram(
                title = title,
                autonumber = autonumber,
                participants = participants.values.toList(),
                boxes = boxes.toList(),
                statements = rootStatements.toList(),
            )
        }
    }
}
