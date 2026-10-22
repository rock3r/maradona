package dev.maradona.parse

import dev.maradona.model.ClassClassifier
import dev.maradona.model.ClassDiagram
import dev.maradona.model.ClassMember
import dev.maradona.model.ClassNamespace
import dev.maradona.model.ClassNode
import dev.maradona.model.ClassNote
import dev.maradona.model.ClassRelation
import dev.maradona.model.ClassRelationEnd
import dev.maradona.model.Direction
import dev.maradona.model.NodeStyle

/**
 * Parser for Mermaid `classDiagram` and `classDiagram-v2` sources.
 *
 * Supports class declarations (`class Name`, `class Name["Label"]`, ``class `Name!` ``,
 * `class Square~Shape~`, `class Shape <<interface>>`, `class Name:::styleClass`, and
 * `{ ... }` member blocks), colon member assignments (`Animal : +int age`),
 * visibility prefixes (`+`, `-`, `#`, `~`), generic type syntax (`~T~` and nested
 * `List~List~int~~`), abstract (`*`) and static (`$`) classifiers, annotations
 * (`<<interface>>`, `<<enumeration>>`, etc.), all UML relationships (`<|--`, `--|>`,
 * `*--`, `--*`, `o--`, `--o`, `<--`, `-->`, `--`, `<..`, `..>`, `<|..`, `..|>`, `..`,
 * `()--`, `--()`, `<|--|>`) with cardinalities (`"1" --> "*"`) and `: label`,
 * `note "..."` and `note for Class "..."`, `namespace` blocks (hierarchical and flat),
 * `direction TB|TD|BT|LR|RL`, `style`, `classDef`, `cssClass`, YAML frontmatter,
 * and `%%{init}%%` directives.
 */
object ClassParser {

    private const val MAX_SOURCE_LENGTH = 50_000
    private const val MAX_CLASSES = 500
    private const val MAX_RELATIONS = 2_000

    private data class RelOp(
        val token: String,
        val startEnd: ClassRelationEnd,
        val endEnd: ClassRelationEnd,
        val dashed: Boolean,
    )

    // Longest operators first so `<|--|>` and `<|--` match before `<--` and `--`.
    private val relOps: List<RelOp> = buildList {
        val lefts = listOf(
            "<|" to ClassRelationEnd.Extension,
            "()" to ClassRelationEnd.Lollipop,
            "*" to ClassRelationEnd.Composition,
            "o" to ClassRelationEnd.Aggregation,
            "<" to ClassRelationEnd.Dependency,
            "" to ClassRelationEnd.None,
        )
        val stems = listOf("--" to false, ".." to true)
        val rights = listOf(
            "|>" to ClassRelationEnd.Extension,
            "()" to ClassRelationEnd.Lollipop,
            "*" to ClassRelationEnd.Composition,
            "o" to ClassRelationEnd.Aggregation,
            ">" to ClassRelationEnd.Dependency,
            "" to ClassRelationEnd.None,
        )
        for ((lTok, lEnd) in lefts) {
            for ((sTok, dashed) in stems) {
                for ((rTok, rEnd) in rights) {
                    add(RelOp(lTok + sTok + rTok, lEnd, rEnd, dashed))
                }
            }
        }
    }.sortedByDescending { it.token.length }

    fun parse(source: String): ClassDiagram {
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
                    } else {
                        val lowerFm = trimmedRaw.lowercase()
                        when {
                            lowerFm.startsWith("title:") ->
                                state.title = NodeRefReader.cleanLabel(trimmedRaw.substring("title:".length))
                            lowerFm.startsWith("hideemptymembersbox:") ->
                                state.hideEmptyMembersBox = trimmedRaw.substringAfter(':').trim().equals("true", ignoreCase = true)
                            lowerFm.startsWith("hierarchicalnamespaces:") ->
                                state.hierarchicalNamespaces = !trimmedRaw.substringAfter(':').trim().equals("false", ignoreCase = true)
                        }
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
                val lower = line.lowercase()
                val headerLen = when {
                    lower.startsWith("classdiagram-v2") && (line.length == 15 || !line[15].isLetterOrDigit()) -> 15
                    lower.startsWith("classdiagram") && (line.length == 12 || !line[12].isLetterOrDigit()) -> 12
                    else -> throw MaradonaParseException(lineNo, "Expected 'classDiagram', got: ${line.take(40)}")
                }
                headerSeen = true
                val rest = line.substring(headerLen).trim().trimStart(';')
                if (rest.isNotEmpty()) state.processLine(rest, lineNo)
                continue
            }
            state.processLine(line, lineNo)
        }
        if (!headerSeen) throw MaradonaParseException(1, "Expected 'classDiagram'")
        if (state.classes.size > MAX_CLASSES) {
            throw MaradonaParseException(1, "Too many classes")
        }
        if (state.relations.size > MAX_RELATIONS) {
            throw MaradonaParseException(1, "Too many relations")
        }
        return state.build()
    }

    private fun stripComment(line: String): String {
        var inQuote = false
        var inBacktick = false
        var i = 0
        while (i < line.length - 1) {
            when (line[i]) {
                '"' -> if (!inBacktick) inQuote = !inQuote
                '`' -> if (!inQuote) inBacktick = !inBacktick
                '%' -> if (!inQuote && !inBacktick && line[i + 1] == '%') return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private sealed interface Scope {
        data class Namespace(val id: String) : Scope
        data class ClassBody(val classId: String) : Scope
    }

    private sealed interface PendingBrace {
        data class Namespace(val id: String) : PendingBrace
        data class ClassBody(val classId: String) : PendingBrace
    }

    private class MutableClass(
        val id: String,
        var label: String = id,
        var genericType: String? = null,
        val annotations: MutableList<String> = mutableListOf(),
        val attributes: MutableList<ClassMember> = mutableListOf(),
        val methods: MutableList<ClassMember> = mutableListOf(),
        var namespaceId: String? = null,
        val styleClasses: MutableList<String> = mutableListOf(),
        var inlineStyle: NodeStyle? = null,
    )

    private class MutableNamespace(
        val id: String,
        var label: String,
        val parentId: String?,
        var explicit: Boolean,
    )

    private class State {
        var title: String? = null
        var direction: Direction = Direction.TB
        var hideEmptyMembersBox: Boolean = false
        var hierarchicalNamespaces: Boolean = true

        val classes = LinkedHashMap<String, MutableClass>()
        val relations = mutableListOf<ClassRelation>()
        val notes = mutableListOf<ClassNote>()
        private val namespaces = LinkedHashMap<String, MutableNamespace>()
        private val classDefs = HashMap<String, NodeStyle>()
        private val scopeStack = ArrayDeque<Scope>()
        private var pendingBrace: PendingBrace? = null
        private var inMultilineAccDescr = false

        private fun currentNamespaceId(): String? {
            for (i in scopeStack.indices.reversed()) {
                val s = scopeStack[i]
                if (s is Scope.Namespace) return s.id
            }
            return null
        }

        fun processLine(line: String, lineNo: Int) {
            if (inMultilineAccDescr) {
                if ('}' in line) inMultilineAccDescr = false
                return
            }
            if (line.trim().lowercase().startsWith("accdescr") && '{' in line && '}' !in line) {
                inMultilineAccDescr = true
                return
            }
            // Split line around `{` and `}` outside quotes and backticks so single-line
            // and multi-line `class` / `namespace` blocks both work uniformly.
            var pos = 0
            var inQuote = false
            var inBacktick = false
            var i = 0
            while (i < line.length) {
                when (val c = line[i]) {
                    '"' -> if (!inBacktick) inQuote = !inQuote
                    '`' -> if (!inQuote) inBacktick = !inBacktick
                    '{' -> if (!inQuote && !inBacktick) {
                        val before = line.substring(pos, i).trim()
                        if (before.isNotEmpty()) {
                            handleOpenBraceHeader(before, lineNo)
                        } else {
                            when (val p = pendingBrace) {
                                is PendingBrace.ClassBody -> scopeStack.addLast(Scope.ClassBody(p.classId))
                                is PendingBrace.Namespace -> scopeStack.addLast(Scope.Namespace(p.id))
                                null -> throw MaradonaParseException(lineNo, "Unexpected '{'")
                            }
                            pendingBrace = null
                        }
                        pos = i + 1
                    }
                    '}' -> if (!inQuote && !inBacktick) {
                        val before = line.substring(pos, i).trim()
                        if (before.isNotEmpty()) {
                            handleStatementChunk(before, lineNo)
                        }
                        if (scopeStack.isEmpty()) {
                            throw MaradonaParseException(lineNo, "Unexpected '}'")
                        }
                        scopeStack.removeLast()
                        pendingBrace = null
                        pos = i + 1
                    }
                    else -> Unit
                }
                i++
            }
            val tail = line.substring(pos).trim()
            if (tail.isNotEmpty()) {
                handleStatementChunk(tail, lineNo)
            }
        }

        private fun handleOpenBraceHeader(header: String, lineNo: Int) {
            pendingBrace = null
            val parts = splitSemicolons(header)
            if (parts.isEmpty()) throw MaradonaParseException(lineNo, "Unexpected '{'")
            for (prefix in parts.dropLast(1)) {
                handleStatementChunk(prefix, lineNo)
            }
            val blockHeader = parts.last()
            val lower = blockHeader.lowercase()
            when {
                lower.startsWith("namespace ") -> {
                    val nsId = declareNamespace(blockHeader.substring("namespace ".length).trim(), lineNo)
                    scopeStack.addLast(Scope.Namespace(nsId))
                }
                lower.startsWith("class ") -> {
                    val c = declareClassSpec(blockHeader.substring("class ".length).trim(), lineNo, assignNamespace = true)
                    scopeStack.addLast(Scope.ClassBody(c.id))
                }
                else -> {
                    val c = declareClassSpec(blockHeader, lineNo, assignNamespace = true)
                    scopeStack.addLast(Scope.ClassBody(c.id))
                }
            }
        }

        private fun handleStatementChunk(chunk: String, lineNo: Int) {
            val activeScope = scopeStack.lastOrNull()
            if (activeScope is Scope.ClassBody) {
                for (part in splitSemicolons(chunk)) {
                    addClassMember(activeScope.classId, part)
                }
                return
            }
            for (stmt in splitSemicolons(chunk)) {
                statement(stmt, lineNo)
            }
        }

        private fun splitSemicolons(text: String): List<String> {
            if (';' !in text) {
                val t = text.trim()
                return if (t.isEmpty()) emptyList() else listOf(t)
            }
            val out = mutableListOf<String>()
            var start = 0
            var inQuote = false
            var inBacktick = false
            for (i in text.indices) {
                when (text[i]) {
                    '"' -> if (!inBacktick) inQuote = !inQuote
                    '`' -> if (!inQuote) inBacktick = !inBacktick
                    ';' -> if (!inQuote && !inBacktick && !isEntitySemicolon(text, i)) {
                        val part = text.substring(start, i).trim()
                        if (part.isNotEmpty()) out += part
                        start = i + 1
                    }
                }
            }
            val tail = text.substring(start).trim()
            if (tail.isNotEmpty()) out += tail
            return out
        }

        private fun isEntitySemicolon(line: String, semiIdx: Int): Boolean {
            var j = semiIdx - 1
            while (j >= 0 && line[j].isLetterOrDigit()) j--
            if (j < 0 || j == semiIdx - 1) return false
            return line[j] == '&' || line[j] == '#'
        }

        private fun statement(stmt: String, lineNo: Int) {
            pendingBrace = null
            val lower = stmt.lowercase()
            when {
                lower.startsWith("direction ") -> {
                    val dirToken = stmt.substring("direction ".length).trim().uppercase()
                    direction = when (dirToken) {
                        "TB", "TD" -> Direction.TB
                        "BT" -> Direction.BT
                        "LR" -> Direction.LR
                        "RL" -> Direction.RL
                        else -> throw MaradonaParseException(lineNo, "Unknown direction '$dirToken'")
                    }
                }
                lower.startsWith("title:") -> title = NodeRefReader.cleanLabel(stmt.substring("title:".length))
                lower.startsWith("title ") -> title = NodeRefReader.cleanLabel(stmt.substring("title ".length))
                lower.startsWith("acctitle:") || lower.startsWith("accdescr:") -> Unit
                lower.startsWith("link ") || lower.startsWith("click ") || lower.startsWith("callback ") -> Unit
                lower.startsWith("classdef ") -> parseClassDef(stmt.substring("classDef ".length).trim())
                lower.startsWith("cssclass ") -> parseCssClass(stmt.substring("cssClass ".length).trim())
                lower.startsWith("style ") -> parseStyle(stmt.substring("style ".length).trim())
                lower.startsWith("note for ") -> parseNoteFor(stmt.substring("note for ".length).trim(), lineNo)
                lower.startsWith("note ") -> parseGeneralNote(stmt.substring("note ".length).trim(), lineNo)
                lower.startsWith("namespace ") -> {
                    val nsId = declareNamespace(stmt.substring("namespace ".length).trim(), lineNo)
                    pendingBrace = PendingBrace.Namespace(nsId)
                }
                lower.startsWith("class ") -> {
                    val c = declareClassSpec(stmt.substring("class ".length).trim(), lineNo, assignNamespace = true)
                    pendingBrace = PendingBrace.ClassBody(c.id)
                }
                stmt.startsWith("<<") && ">>" in stmt -> {
                    val close = stmt.indexOf(">>")
                    val ann = NodeRefReader.cleanLabel(stmt.substring(2, close))
                    val targetRaw = stmt.substring(close + 2).trim()
                    if (targetRaw.isNotEmpty()) {
                        val c = declareClassSpec(targetRaw, lineNo)
                        if (ann.isNotEmpty() && ann !in c.annotations) c.annotations += ann
                    }
                }
                else -> {
                    if (!tryParseRelation(stmt, lineNo)) {
                        if (!tryParseColonMember(stmt, lineNo)) {
                            throw MaradonaParseException(lineNo, "Unrecognized classDiagram statement: ${stmt.take(40)}")
                        }
                    }
                }
            }
        }

        private fun declareNamespace(raw: String, lineNo: Int): String {
            var rest = raw.trim()
            if (rest.isEmpty()) throw MaradonaParseException(lineNo, "Namespace needs an id")
            var explicitLabel: String? = null
            val bracketStart = rest.indexOf('[')
            if (bracketStart > 0 && rest.endsWith("]")) {
                explicitLabel = NodeRefReader.cleanLabel(rest.substring(bracketStart + 1, rest.length - 1))
                rest = rest.substring(0, bracketStart).trim()
            }
            val baseId = unquoteClassId(rest)
            if (baseId.isEmpty()) throw MaradonaParseException(lineNo, "Namespace needs an id")
            val parentScopeId = currentNamespaceId()
            val qualifiedId = if (parentScopeId != null) "$parentScopeId.$baseId" else baseId
            val existing = namespaces[qualifiedId]
            if (existing != null) {
                existing.explicit = true
                if (explicitLabel != null) existing.label = explicitLabel
                return qualifiedId
            }
            val parts = qualifiedId.split('.')
            for (i in parts.indices) {
                val curId = parts.subList(0, i + 1).joinToString(".")
                val parId = if (i > 0) parts.subList(0, i).joinToString(".") else null
                val isLeaf = i == parts.lastIndex
                val lbl = if (isLeaf && explicitLabel != null) explicitLabel else parts[i]
                val cur = namespaces[curId]
                if (cur == null) {
                    namespaces[curId] = MutableNamespace(
                        id = curId,
                        label = lbl,
                        parentId = parId,
                        explicit = isLeaf,
                    )
                } else if (isLeaf) {
                    cur.explicit = true
                    if (explicitLabel != null) cur.label = explicitLabel
                }
            }
            return qualifiedId
        }

        private fun declareClassSpec(raw: String, lineNo: Int, assignNamespace: Boolean = false): MutableClass {
            var s = raw.trim()
            var annotation: String? = null
            val openAnn = findSubstringOutsideQuotesAndBackticks(s, "<<")
            if (openAnn >= 0) {
                val closeAnn = findSubstringOutsideQuotesAndBackticks(s, ">>", openAnn + 2)
                if (closeAnn > openAnn) {
                    annotation = NodeRefReader.cleanLabel(s.substring(openAnn + 2, closeAnn))
                    s = (s.substring(0, openAnn) + " " + s.substring(closeAnn + 2)).trim()
                }
            }
            var styleClass: String? = null
            val tripleColon = findSubstringOutsideQuotesAndBackticks(s, ":::")
            if (tripleColon >= 0) {
                val after = s.substring(tripleColon + 3).trim()
                val token = after.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
                if (token.isNotEmpty()) styleClass = token
                s = (s.substring(0, tripleColon) + " " + after.drop(token.length)).trim()
            }
            var explicitLabel: String? = null
            val bracketStart = findOutsideBackticks(s, '[')
            if (bracketStart > 0 && s.endsWith("]")) {
                explicitLabel = NodeRefReader.cleanLabel(s.substring(bracketStart + 1, s.length - 1))
                s = s.substring(0, bracketStart).trim()
            }
            var genericType: String? = null
            val tildeStart = findOutsideBackticks(s, '~')
            if (tildeStart > 0 && s.endsWith("~")) {
                val rawGen = s.substring(tildeStart + 1, s.length - 1).trim()
                genericType = convertGenerics(rawGen)
                s = s.substring(0, tildeStart).trim()
            }
            val id = unquoteClassId(s)
            if (id.isEmpty()) throw MaradonaParseException(lineNo, "Expected class name in: $raw")
            val node = ensureClass(id)
            if (explicitLabel != null) node.label = explicitLabel
            if (genericType != null) node.genericType = genericType
            if (annotation != null && annotation.isNotEmpty() && annotation !in node.annotations) {
                node.annotations += annotation
            }
            if (styleClass != null && styleClass !in node.styleClasses) {
                node.styleClasses += styleClass
            }
            if (assignNamespace) {
                val nsId = currentNamespaceId()
                if (nsId != null) node.namespaceId = nsId
            }
            return node
        }

        private fun ensureClass(id: String): MutableClass {
            return classes.getOrPut(id) {
                MutableClass(id = id, label = id, namespaceId = null)
            }
        }

        private fun addClassMember(classId: String, rawMember: String) {
            val trimmed = rawMember.trim()
            if (trimmed.isEmpty()) return
            val node = ensureClass(classId)
            if (trimmed.startsWith("<<") && trimmed.endsWith(">>") && trimmed.length > 4) {
                val ann = NodeRefReader.cleanLabel(trimmed.substring(2, trimmed.length - 2))
                if (ann.isNotEmpty() && ann !in node.annotations) {
                    node.annotations += ann
                }
                return
            }
            val parsed = parseMemberString(trimmed)
            if (parsed.isMethod) {
                node.methods += parsed
            } else {
                node.attributes += parsed
            }
        }

        private fun parseMemberString(raw: String): ClassMember {
            var s = NodeRefReader.cleanLabel(raw).trim()
            val visibility = if (s.isNotEmpty() && s[0] in "+-#~") {
                val v = s[0].toString()
                s = s.substring(1).trimStart()
                v
            } else ""

            val openParen = s.indexOf('(')
            val closeParen = if (openParen >= 0) s.lastIndexOf(')') else -1
            if (openParen > 0 && closeParen > openParen) {
                val name = convertGenerics(s.substring(0, openParen).trim())
                val params = convertGenerics(s.substring(openParen + 1, closeParen).trim())
                var after = s.substring(closeParen + 1).trim()
                var classifier = ClassClassifier.None
                if (after.startsWith("*") || after.startsWith("$")) {
                    classifier = if (after[0] == '*') ClassClassifier.Abstract else ClassClassifier.Static
                    after = after.substring(1).trim()
                } else if (after.endsWith("*") || after.endsWith("$")) {
                    classifier = if (after.last() == '*') ClassClassifier.Abstract else ClassClassifier.Static
                    after = after.dropLast(1).trim()
                }
                if (after.startsWith(":")) {
                    after = after.substring(1).trim()
                }
                val returnPart = if (after.isNotEmpty()) " : ${convertGenerics(after)}" else ""
                val display = "$visibility$name($params)$returnPart"
                return ClassMember(displayText = display, isMethod = true, classifier = classifier)
            }

            var classifier = ClassClassifier.None
            if (s.endsWith("*") || s.endsWith("$")) {
                classifier = if (s.last() == '*') ClassClassifier.Abstract else ClassClassifier.Static
                s = s.dropLast(1).trimEnd()
            }
            val body = convertGenerics(s)
            val display = "$visibility$body"
            return ClassMember(displayText = display, isMethod = false, classifier = classifier)
        }

        private fun tryParseRelation(stmt: String, lineNo: Int): Boolean {
            // Find a relation operator outside `"..."`, `` `...` ``, and `~...~`, before any `:` label.
            var inQuote = false
            var inBacktick = false
            var tildeDepth = 0
            var matchedAt = -1
            var matchedOp: RelOp? = null
            var i = 0
            while (i < stmt.length) {
                when (stmt[i]) {
                    '"' -> if (!inBacktick) inQuote = !inQuote
                    '`' -> if (!inQuote) inBacktick = !inBacktick
                    '~' -> if (!inQuote && !inBacktick) tildeDepth = if (tildeDepth == 0) 1 else 0
                    ':' -> if (!inQuote && !inBacktick && tildeDepth == 0) {
                        if (stmt.startsWith(":::", i)) {
                            i += 3
                            continue
                        }
                        break
                    }
                    else -> if (!inQuote && !inBacktick && tildeDepth == 0) {
                        for (op in relOps) {
                            if (stmt.startsWith(op.token, i) && isValidOpBoundary(stmt, i, op)) {
                                matchedAt = i
                                matchedOp = op
                                break
                            }
                        }
                        if (matchedOp != null) break
                    }
                }
                i++
            }
            val op = matchedOp ?: return false
            val leftPart = stmt.substring(0, matchedAt).trim()
            val afterOp = stmt.substring(matchedAt + op.token.length).trim()
            if (leftPart.isEmpty() || afterOp.isEmpty()) return false

            // Extract optional `: label` on the right side outside quotes and backticks.
            var colonAt = -1
            inQuote = false
            inBacktick = false
            var j = 0
            while (j < afterOp.length) {
                when (afterOp[j]) {
                    '"' -> if (!inBacktick) inQuote = !inQuote
                    '`' -> if (!inQuote) inBacktick = !inBacktick
                    ':' -> if (!inQuote && !inBacktick) {
                        if (afterOp.startsWith(":::", j)) {
                            j += 3
                            continue
                        }
                        colonAt = j
                        break
                    }
                }
                j++
            }
            val rightTargetPart = if (colonAt >= 0) afterOp.substring(0, colonAt).trim() else afterOp
            val label = if (colonAt >= 0) {
                afterOp.substring(colonAt + 1).trim().takeIf { it.isNotEmpty() }?.let(NodeRefReader::cleanLabel)
            } else null

            val (fromRaw, fromCard) = splitClassAndTrailingCardinality(leftPart)
            val (toCard, toRaw) = splitLeadingCardinalityAndClass(rightTargetPart)
            if (fromRaw.isEmpty() || toRaw.isEmpty()) return false

            val fromClass = declareClassSpec(fromRaw, lineNo)
            val toClass = declareClassSpec(toRaw, lineNo)
            relations += ClassRelation(
                from = fromClass.id,
                to = toClass.id,
                startEnd = op.startEnd,
                endEnd = op.endEnd,
                dashed = op.dashed,
                label = label,
                fromCardinality = fromCard,
                toCardinality = toCard,
            )
            return true
        }

        private fun isValidOpBoundary(s: String, start: Int, op: RelOp): Boolean {
            // If operator starts with 'o', the char before 'o' must be whitespace so class names ending in 'o'
            // (like `foo -- bar` or `Hero-->Villain`) are not split at their trailing 'o'.
            if (op.token.startsWith("o") && start > 0 && !s[start - 1].isWhitespace()) {
                return false
            }
            // Similarly if operator ends with 'o', the char after 'o' must be whitespace.
            val end = start + op.token.length
            if (op.token.endsWith("o") && end < s.length && !s[end].isWhitespace()) {
                return false
            }
            return true
        }

        private fun splitClassAndTrailingCardinality(left: String): Pair<String, String?> {
            val trimmed = left.trim()
            if (trimmed.endsWith("\"")) {
                val openQuote = trimmed.lastIndexOf('"', trimmed.length - 2)
                if (openQuote > 0) {
                    val before = trimmed.substring(0, openQuote).trim()
                    if (before.isNotEmpty()) {
                        val card = NodeRefReader.cleanLabel(trimmed.substring(openQuote, trimmed.length))
                        return before to card
                    }
                }
            }
            return trimmed to null
        }

        private fun splitLeadingCardinalityAndClass(right: String): Pair<String?, String> {
            val trimmed = right.trim()
            if (trimmed.startsWith("\"")) {
                val closeQuote = trimmed.indexOf('"', 1)
                if (closeQuote > 0) {
                    val after = trimmed.substring(closeQuote + 1).trim()
                    if (after.isNotEmpty()) {
                        val card = NodeRefReader.cleanLabel(trimmed.substring(0, closeQuote + 1))
                        return card to after
                    }
                }
            }
            return null to trimmed
        }

        private fun tryParseColonMember(stmt: String, lineNo: Int): Boolean {
            var inQuote = false
            var inBacktick = false
            var colonAt = -1
            var i = 0
            while (i < stmt.length) {
                when (stmt[i]) {
                    '"' -> if (!inBacktick) inQuote = !inQuote
                    '`' -> if (!inQuote) inBacktick = !inBacktick
                    ':' -> if (!inQuote && !inBacktick) {
                        if (stmt.startsWith(":::", i)) {
                            i += 3
                            continue
                        }
                        colonAt = i
                        break
                    }
                }
                i++
            }
            if (colonAt <= 0) return false
            val classRaw = stmt.substring(0, colonAt).trim()
            val memberRaw = stmt.substring(colonAt + 1).trim()
            if (classRaw.isEmpty() || memberRaw.isEmpty()) return false
            val node = declareClassSpec(classRaw, lineNo)
            addClassMember(node.id, memberRaw)
            return true
        }

        private fun parseNoteFor(rest: String, lineNo: Int) {
            val trimmed = rest.trim()
            val firstQuote = trimmed.indexOf('"')
            if (firstQuote <= 0) throw MaradonaParseException(lineNo, "Expected quoted note text in: note for $rest")
            val targetRaw = trimmed.substring(0, firstQuote).trim()
            val textRaw = trimmed.substring(firstQuote).trim()
            val target = declareClassSpec(targetRaw, lineNo)
            val id = "note${notes.size}"
            notes += ClassNote(
                id = id,
                text = NodeRefReader.cleanLabel(textRaw),
                forClassId = target.id,
                namespaceId = currentNamespaceId(),
            )
        }

        private fun parseGeneralNote(rest: String, lineNo: Int) {
            val trimmed = rest.trim()
            if (trimmed.isEmpty()) throw MaradonaParseException(lineNo, "Empty note")
            val id = "note${notes.size}"
            notes += ClassNote(
                id = id,
                text = NodeRefReader.cleanLabel(trimmed),
                forClassId = null,
                namespaceId = currentNamespaceId(),
            )
        }

        private fun parseClassDef(rest: String) {
            val parts = rest.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return
            val names = parts[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val style = parseStyleProps(parts[1])
            for (name in names) {
                classDefs[name] = (classDefs[name] ?: NodeStyle()).merge(style)
            }
        }

        private fun parseCssClass(rest: String) {
            var s = rest.trim()
            val targets: List<String>
            val className: String
            if (s.startsWith("\"")) {
                val close = s.indexOf('"', 1)
                if (close < 0) return
                targets = s.substring(1, close).split(',').map { unquoteClassId(it.trim()) }
                className = s.substring(close + 1).trim().takeWhile { !it.isWhitespace() }
            } else {
                val parts = s.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) return
                targets = parts[0].split(',').map { unquoteClassId(it.trim()) }
                className = parts[1].trim().takeWhile { !it.isWhitespace() }
            }
            if (className.isEmpty()) return
            for (t in targets) {
                if (t.isNotEmpty()) {
                    val c = ensureClass(t)
                    if (className !in c.styleClasses) c.styleClasses += className
                }
            }
        }

        private fun parseStyle(rest: String) {
            val parts = rest.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return
            val id = unquoteClassId(parts[0].substringBefore('~'))
            if (id.isEmpty()) return
            val c = ensureClass(id)
            c.inlineStyle = (c.inlineStyle ?: NodeStyle()).merge(parseStyleProps(parts[1]))
        }

        private fun parseStyleProps(raw: String): NodeStyle {
            var fill: String? = null
            var stroke: String? = null
            var color: String? = null
            var strokeWidth: String? = null
            for (entry in raw.split(',')) {
                val kv = entry.split(':', limit = 2)
                if (kv.size != 2) continue
                val k = kv[0].trim().lowercase()
                val v = kv[1].trim().removeSuffix(";")
                when (k) {
                    "fill" -> fill = v
                    "stroke" -> stroke = v
                    "color" -> color = v
                    "stroke-width" -> strokeWidth = v
                }
            }
            return NodeStyle(fill = fill, stroke = stroke, textColor = color, strokeWidth = strokeWidth)
        }

        private fun resolveExplicitNamespace(nsId: String?): String? {
            if (nsId == null) return null
            if (hierarchicalNamespaces) return nsId
            var cur: String? = nsId
            while (cur != null) {
                val ns = namespaces[cur] ?: return null
                if (ns.explicit) return cur
                cur = ns.parentId
            }
            return null
        }

        fun build(): ClassDiagram {
            if (scopeStack.isNotEmpty()) {
                throw MaradonaParseException(1, "Unclosed '${scopeStack.last()}' block")
            }
            val defaultStyle = classDefs["default"]
            val builtNamespaces = namespaces.values
                .filter { hierarchicalNamespaces || it.explicit }
                .map { ns ->
                    ClassNamespace(
                        id = ns.id,
                        label = if (hierarchicalNamespaces) ns.label else ns.id,
                        parentId = if (hierarchicalNamespaces) ns.parentId else resolveExplicitNamespace(ns.parentId),
                    )
                }
            val builtClasses = classes.values.map { c ->
                var style: NodeStyle? = defaultStyle
                for (cls in c.styleClasses) {
                    val def = classDefs[cls] ?: continue
                    style = (style ?: NodeStyle()).merge(def)
                }
                if (c.inlineStyle != null) {
                    style = (style ?: NodeStyle()).merge(c.inlineStyle)
                }
                ClassNode(
                    id = c.id,
                    label = c.label,
                    genericType = c.genericType,
                    annotations = c.annotations.toList(),
                    attributes = c.attributes.toList(),
                    methods = c.methods.toList(),
                    namespaceId = resolveExplicitNamespace(c.namespaceId),
                    style = style,
                )
            }
            val builtNotes = notes.map { n ->
                n.copy(namespaceId = resolveExplicitNamespace(n.namespaceId))
            }
            return ClassDiagram(
                title = title,
                direction = direction,
                hideEmptyMembersBox = hideEmptyMembersBox,
                classes = builtClasses,
                relations = relations.toList(),
                notes = builtNotes,
                namespaces = builtNamespaces,
            )
        }
    }

    private fun findOutsideBackticks(s: String, target: Char): Int {
        var inBacktick = false
        var inQuote = false
        for (i in s.indices) {
            when (s[i]) {
                '`' -> if (!inQuote) inBacktick = !inBacktick
                '"' -> if (!inBacktick) inQuote = !inQuote
                target -> if (!inBacktick && !inQuote) return i
            }
        }
        return -1
    }

    private fun findSubstringOutsideQuotesAndBackticks(s: String, needle: String, fromIndex: Int = 0): Int {
        var inBacktick = false
        var inQuote = false
        for (i in 0..s.length - needle.length) {
            when (s[i]) {
                '`' -> if (!inQuote) inBacktick = !inBacktick
                '"' -> if (!inBacktick) inQuote = !inQuote
                else -> if (i >= fromIndex && !inBacktick && !inQuote && s.startsWith(needle, i)) return i
            }
        }
        return -1
    }

    private fun unquoteClassId(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.first() == '`' && t.last() == '`') {
            return t.substring(1, t.length - 1).trim()
        }
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            return t.substring(1, t.length - 1).trim()
        }
        return t
    }

    /**
     * Converts Mermaid's `~T~` generic syntax (including nested `List~List~int~~`,
     * nested multi-parameter `List~Map~string, int~~`, sibling generic arguments
     * `Map~List~int~, List~string~~`, and suffixed `List~int~[]` / `List~int~?`)
     * into `<T>` (`List<List<int>>`, `List<Map<string, int>>`, `Map<List<int>, List<string>>`).
     */
    internal fun convertGenerics(input: String): String {
        var remainingTildes = input.count { it == '~' }
        if (remainingTildes < 2) return input
        val sb = StringBuilder(input.length)
        var depth = 0
        for (i in input.indices) {
            val c = input[i]
            if (c != '~') {
                sb.append(c)
                continue
            }
            val prevIsIdent = i > 0 && (input[i - 1].isLetterOrDigit() || input[i - 1] == '_' || input[i - 1] == '$')
            val next = if (i + 1 < input.length) input[i + 1] else '\u0000'
            val nextStartsTypeArg = next != '\u0000' && next != '~' && next != ',' &&
                next != ')' && next != ']' && next != '[' && next != '?' && !next.isWhitespace()
            if (depth == 0 || (prevIsIdent && nextStartsTypeArg && remainingTildes > depth)) {
                sb.append('<')
                depth++
            } else {
                sb.append('>')
                depth--
            }
            remainingTildes--
        }
        return sb.toString()
    }
}
